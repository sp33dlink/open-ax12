package org.openax12.core.audio

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Android text-to-speech behind the queue: finds an engine, picks a language, speaks one
 * utterance at a time and reports when it ends.
 *
 * Confined to the audio control thread ([handler]) except [availableLanguages], which may be
 * called from any background thread. Engine callbacks arrive on other threads and are forwarded
 * to [handler].
 */
internal class SpeechOutput(
    private val context: Context,
    private val handler: Handler,
    private val attributes: AudioAttributes,
    private val listener: Listener,
) {

    /** Callbacks on the control thread. */
    interface Listener {
        fun onSpeechStatus(status: TtsStatus)

        /** The utterance [id] ended (done, stopped or failed). May be stale. */
        fun onUtteranceFinished(id: Long)
    }

    /** Current availability. */
    @Volatile
    var status: TtsStatus = TtsStatus.Initializing
        private set

    @Volatile
    private var tts: TextToSpeech? = null

    private var engine = ""
    private var remainingEngines: List<String> = emptyList()
    private var generation = 0
    private var language = ""
    private var rate = 1f
    private var lastReconnectMs = Long.MIN_VALUE / 2
    private var released = false

    /** Token of everything this class posts with a delay. */
    private val delayed = Any()

    /** The configured language (BCP-47, empty = system) and rate. Applied to the next utterance. */
    fun configure(newLanguage: String, newRate: Float) {
        val sanitizedRate = if (newRate.isNaN()) 1f else newRate.coerceIn(MIN_RATE, MAX_RATE)
        val languageChanged = newLanguage.trim() != language
        val rateChanged = sanitizedRate != rate
        language = newLanguage.trim()
        rate = sanitizedRate
        if (released) return
        when (status) {
            is TtsStatus.Ready, is TtsStatus.LanguageMissing ->
                if (languageChanged) applyLanguage() else if (rateChanged) applyRate()
            // Initializing: applied when the engine answers. NoEngine / Error: nothing to configure.
            else -> Unit
        }
    }

    /** Finds an engine and binds it. [status] goes through [TtsStatus.Initializing]. */
    fun connect() {
        if (released) return
        closeEngine()
        val engines = installedEngines()
        if (engines.isEmpty()) {
            setStatus(TtsStatus.NoEngine)
            return
        }
        tryEngines(engines, TtsStatus.NoEngine)
    }

    /**
     * Looks again after something changed outside (engine or voice data installed / removed,
     * system language changed, settings screen opened). Cheap when nothing changed.
     */
    fun recheck() {
        if (released) return
        when (val current = status) {
            TtsStatus.Initializing -> Unit
            is TtsStatus.Ready -> when {
                current.engine !in installedEngines() -> connect()
                !current.requestedLanguageAvailable || language.isEmpty() -> applyLanguage()
            }
            is TtsStatus.LanguageMissing, TtsStatus.NoEngine, is TtsStatus.Error -> connect()
        }
    }

    /**
     * Speaks [text] now, replacing whatever the engine is saying.
     *
     * @param volume linear gain 0..1
     * @return false if the engine refused; [Listener.onUtteranceFinished] is then not called
     */
    fun speak(id: Long, text: String, volume: Float): Boolean {
        val engineInstance = tts ?: return false
        if (status !is TtsStatus.Ready) return false
        val params = Bundle()
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume.coerceIn(0f, 1f))
        val limit = TextToSpeech.getMaxSpeechInputLength().coerceAtMost(MAX_TEXT_LENGTH)
        val result = try {
            engineInstance.speak(text.take(limit), TextToSpeech.QUEUE_FLUSH, params, UTTERANCE_PREFIX + id)
        } catch (e: RuntimeException) {
            TextToSpeech.ERROR
        }
        if (result != TextToSpeech.SUCCESS) {
            // The engine process probably died; bind again, but not in a tight loop.
            scheduleReconnect()
            return false
        }
        return true
    }

    /** Interrupts the current utterance. */
    fun stop() {
        try {
            tts?.stop()
        } catch (e: RuntimeException) {
            // Engine gone; nothing is speaking then.
        }
    }

    /** Languages the engine offers as BCP-47 tags. Blocking binder calls: not for the main thread. */
    fun availableLanguages(): List<String> {
        val engineInstance = tts ?: return emptyList()
        val current = status
        if (current !is TtsStatus.Ready && current !is TtsStatus.LanguageMissing) return emptyList()
        val reported = try {
            engineInstance.availableLanguages
        } catch (e: RuntimeException) {
            null
        }
        val locales = if (!reported.isNullOrEmpty()) {
            reported
        } else {
            // Engines without a voice list: probe the usual suspects.
            COMMON_LANGUAGES.map(Locale::forLanguageTag).filter { locale ->
                try {
                    engineInstance.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE
                } catch (e: RuntimeException) {
                    false
                }
            }
        }
        return locales.map(SpeechLanguage::tag).filter(String::isNotEmpty).distinct().sorted()
    }

    /** Stops speaking and unbinds. The instance is unusable afterwards. */
    fun release() {
        released = true
        closeEngine()
    }

    // -- Engine life cycle ----------------------------------------------------------------------

    /** Package names of the installed engines: the system default first, then preinstalled ones. */
    private fun installedEngines(): List<String> {
        val packageManager = context.packageManager
        val services = try {
            @Suppress("DEPRECATION")
            packageManager.queryIntentServices(
                Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE),
                PackageManager.MATCH_DEFAULT_ONLY,
            )
        } catch (e: RuntimeException) {
            emptyList()
        }
        val default = try {
            Settings.Secure.getString(context.contentResolver, SETTING_DEFAULT_ENGINE)
        } catch (e: RuntimeException) {
            null
        }
        return services
            .mapNotNull { it.serviceInfo }
            .sortedWith(
                compareByDescending<android.content.pm.ServiceInfo> { it.packageName == default }
                    .thenByDescending { (it.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 }
                    .thenBy { it.packageName },
            )
            .map { it.packageName }
            .distinct()
    }

    private fun tryEngines(engines: List<String>, failure: TtsStatus) {
        if (engines.isEmpty()) {
            setStatus(failure)
            return
        }
        engine = engines.first()
        remainingEngines = engines.drop(1)
        setStatus(TtsStatus.Initializing)
        val attempt = ++generation
        // The listener runs on the main thread, possibly before the constructor returns.
        tts = try {
            TextToSpeech(context, { result -> handler.post { onInit(attempt, result) } }, engine)
        } catch (e: RuntimeException) {
            handler.post { onInit(attempt, TextToSpeech.ERROR) }
            null
        }
        handler.postDelayed({ onInitTimeout(attempt) }, delayed, INIT_TIMEOUT_MS)
    }

    private fun onInit(attempt: Int, result: Int) {
        if (released || attempt != generation || status != TtsStatus.Initializing) return
        handler.removeCallbacksAndMessages(delayed)
        val engineInstance = tts
        if (result != TextToSpeech.SUCCESS || engineInstance == null) {
            failEngine(context.getString(R.string.audio_tts_error_init, engine))
            return
        }
        try {
            engineInstance.setOnUtteranceProgressListener(Progress())
            engineInstance.setAudioAttributes(attributes)
        } catch (e: RuntimeException) {
            failEngine(context.getString(R.string.audio_tts_error_unexpected, e.message ?: e.javaClass.simpleName))
            return
        }
        applyLanguage()
    }

    private fun onInitTimeout(attempt: Int) {
        if (released || attempt != generation || status != TtsStatus.Initializing) return
        failEngine(context.getString(R.string.audio_tts_error_timeout, engine))
    }

    /** Gives up on the current engine and tries the next installed one. */
    private fun failEngine(message: String) {
        val next = remainingEngines
        closeEngine()
        tryEngines(next, TtsStatus.Error(message))
    }

    private fun closeEngine() {
        generation++
        handler.removeCallbacksAndMessages(delayed)
        val engineInstance = tts
        tts = null
        if (engineInstance != null) {
            try {
                engineInstance.stop()
            } catch (e: RuntimeException) {
                // Shutting down anyway.
            }
            try {
                engineInstance.shutdown()
            } catch (e: RuntimeException) {
                // Nothing left to release.
            }
        }
    }

    private fun scheduleReconnect() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastReconnectMs < RECONNECT_INTERVAL_MS) return
        lastReconnectMs = now
        handler.postDelayed({ connect() }, delayed, RECONNECT_DELAY_MS)
    }

    // -- Language -------------------------------------------------------------------------------

    /** Selects the best available language and publishes [TtsStatus.Ready] or [TtsStatus.LanguageMissing]. */
    private fun applyLanguage() {
        val engineInstance = tts ?: return
        val system = Locale.getDefault()
        val requested = SpeechLanguage.requestedLocale(language, system)
        for (candidate in SpeechLanguage.candidates(language, system)) {
            val result = try {
                engineInstance.setLanguage(candidate)
            } catch (e: RuntimeException) {
                TextToSpeech.LANG_NOT_SUPPORTED
            }
            if (result >= TextToSpeech.LANG_AVAILABLE) {
                applyRate()
                setStatus(
                    TtsStatus.Ready(
                        engine = engine,
                        language = SpeechLanguage.tag(candidate),
                        requestedLanguageAvailable = SpeechLanguage.isRequestedLanguage(candidate, requested),
                    ),
                )
                return
            }
        }
        // Last resort: whatever voice the engine considers its default.
        val fallback = try {
            val voice = engineInstance.defaultVoice
            if (voice != null && engineInstance.setVoice(voice) == TextToSpeech.SUCCESS) voice.locale else null
        } catch (e: RuntimeException) {
            null
        }
        if (fallback != null && fallback.language.isNotEmpty()) {
            applyRate()
            setStatus(
                TtsStatus.Ready(
                    engine = engine,
                    language = SpeechLanguage.tag(fallback),
                    requestedLanguageAvailable = SpeechLanguage.isRequestedLanguage(fallback, requested),
                ),
            )
        } else {
            setStatus(TtsStatus.LanguageMissing(engine, SpeechLanguage.tag(requested)))
        }
    }

    private fun applyRate() {
        try {
            tts?.setSpeechRate(rate)
        } catch (e: RuntimeException) {
            // Keeps the previous rate.
        }
    }

    private fun setStatus(newStatus: TtsStatus) {
        if (status == newStatus) return
        status = newStatus
        listener.onSpeechStatus(newStatus)
    }

    /** Engine callbacks (binder threads) → control thread. */
    private inner class Progress : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) = finished(utteranceId)

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) = finished(utteranceId)

        override fun onError(utteranceId: String?, errorCode: Int) = finished(utteranceId)

        override fun onStop(utteranceId: String?, interrupted: Boolean) = finished(utteranceId)

        private fun finished(utteranceId: String?) {
            val id = utteranceId?.removePrefix(UTTERANCE_PREFIX)?.toLongOrNull() ?: return
            handler.post { if (!released) listener.onUtteranceFinished(id) }
        }
    }

    private companion object {
        const val UTTERANCE_PREFIX = "ax12-"

        /** `Settings.Secure.TTS_DEFAULT_SYNTH` (the constant is deprecated, the key is not gone). */
        const val SETTING_DEFAULT_ENGINE = "tts_default_synth"

        const val INIT_TIMEOUT_MS = 10_000L
        const val RECONNECT_DELAY_MS = 1_000L
        const val RECONNECT_INTERVAL_MS = 10_000L
        const val MAX_TEXT_LENGTH = 1_000
        const val MIN_RATE = 0.5f
        const val MAX_RATE = 2.5f

        val COMMON_LANGUAGES = listOf(
            "en-US", "en-GB", "de-DE", "fr-FR", "es-ES", "it-IT", "nl-NL", "pl-PL", "pt-PT", "pt-BR",
            "cs-CZ", "sv-SE", "da-DK", "nb-NO", "fi-FI", "ru-RU", "tr-TR", "hu-HU", "ja-JP", "ko-KR", "zh-CN",
        )
    }
}
