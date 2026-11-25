package org.openax12.core.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.openax12.core.model.AppDispatchers
import org.openax12.core.model.AudioCategory
import org.openax12.core.model.BeepPattern
import org.openax12.core.model.HapticPattern
import org.openax12.core.model.SensorUnit
import org.openax12.core.model.SpeechPriority
import org.openax12.core.model.ToneStep
import org.openax12.core.model.VarioTone
import java.io.File
import java.util.Locale

/**
 * The production [AudioEngine].
 *
 * Threads:
 * - **control thread** (`ax12-audio`, a [HandlerThread]): the speech / sound queue, text-to-speech,
 *   media players, audio focus and the vibrator. Every public call that concerns them is posted
 *   there, so callers never wait for a binder call.
 * - **tone thread** (`ax12-tone`, see [TonePlayer]): beeps and the vario tone. Requests go
 *   straight into its lock-free queue, bypassing the control thread, to keep latency low.
 *
 * Settings (volumes, speech language / rate, haptics switch) live in the engine and survive
 * [shutdown] / [start] cycles; everything else belongs to one [Session] per start.
 */
internal class DefaultAudioEngine(
    context: Context,
    scope: CoroutineScope,
    private val dispatchers: AppDispatchers,
    soundsDirectory: () -> File?,
) : AudioEngine {

    private val appContext: Context = context.applicationContext ?: context
    private val library = SoundLibrary(soundsDirectory)
    private val volumes = VolumeModel()

    private val ttsStatusState = MutableStateFlow<TtsStatus>(TtsStatus.Initializing)
    private val lastSpokenState = MutableStateFlow<SpokenText?>(null)
    private val isSpeakingState = MutableStateFlow(false)
    private val musicPlayingState = MutableStateFlow(false)

    override val ttsStatus: StateFlow<TtsStatus> = ttsStatusState.asStateFlow()
    override val lastSpoken: StateFlow<SpokenText?> = lastSpokenState.asStateFlow()
    override val isSpeaking: StateFlow<Boolean> = isSpeakingState.asStateFlow()
    override val musicPlaying: StateFlow<Boolean> = musicPlayingState.asStateFlow()

    private val lifecycleLock = Any()

    @Volatile
    private var session: Session? = null

    @Volatile
    private var speechLanguage = ""

    @Volatile
    private var speechRate = 1f

    @Volatile
    private var hapticsEnabled = true

    init {
        // The engine dies with the application scope even if nobody calls shutdown().
        scope.coroutineContext[Job]?.invokeOnCompletion { shutdown() }
    }

    // -- Life cycle -----------------------------------------------------------------------------

    override fun start() {
        synchronized(lifecycleLock) {
            if (session != null) return
            session = try {
                Session()
            } catch (e: RuntimeException) {
                Log.w(TAG, "audio engine could not start", e)
                null
            }
        }
    }

    override fun shutdown() {
        val closing = synchronized(lifecycleLock) {
            val current = session
            session = null
            current
        }
        closing?.close()
    }

    // -- Speech ---------------------------------------------------------------------------------

    override fun speak(text: String, priority: SpeechPriority, category: AudioCategory, flush: Boolean) {
        val active = session ?: return
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return
        active.post { active.submit(ItemKind.SPEECH, cleaned, priority, category, flush) }
    }

    override fun speakValue(
        value: Double,
        unit: SensorUnit,
        precision: Int,
        name: String?,
        priority: SpeechPriority,
        category: AudioCategory,
    ) {
        speak(SpokenNumbers.withName(name, spokenValue(value, unit, precision)), priority, category)
    }

    override fun spokenValue(value: Double, unit: SensorUnit, precision: Int): String =
        SpokenNumbers.phrase(value, unit, precision, phraseLanguage())

    override fun setSpeech(language: String, rate: Float) {
        speechLanguage = language.trim()
        speechRate = rate
        val active = session ?: return
        active.post { active.configureSpeech() }
    }

    override suspend fun availableLanguages(): List<String> {
        val active = session ?: return emptyList()
        // The settings screen asks: a good moment to notice an engine installed in the meantime.
        val rechecked = CompletableDeferred<Unit>()
        active.post(always = true) {
            try {
                active.recheckSpeech()
            } finally {
                rechecked.complete(Unit)
            }
        }
        withTimeoutOrNull(CONTROL_ROUND_TRIP_MS) { rechecked.await() } ?: return emptyList()
        withTimeoutOrNull(SPEECH_INIT_WAIT_MS) { ttsStatus.first { it != TtsStatus.Initializing } }
        return withContext(dispatchers.io) { active.availableLanguages() }
    }

    /** The language number phrases are built for: the one the engine really speaks. */
    private fun phraseLanguage(): String {
        val status = ttsStatusState.value
        if (status is TtsStatus.Ready) return status.language
        return SpeechLanguage.tag(SpeechLanguage.requestedLocale(speechLanguage, Locale.getDefault()))
    }

    // -- Beeps, tones, vario --------------------------------------------------------------------

    override fun beep(pattern: BeepPattern, category: AudioCategory) {
        session?.tones?.play(category, BeepPatterns.steps(pattern))
    }

    override fun tone(frequencyHz: Int, durationMs: Int, category: AudioCategory) {
        if (frequencyHz <= 0 || durationMs <= 0) return
        session?.tones?.play(category, listOf(ToneStep(frequencyHz, durationMs)))
    }

    override fun tones(steps: List<ToneStep>, category: AudioCategory) {
        session?.tones?.play(category, steps)
    }

    override fun setVarioTone(tone: VarioTone) {
        session?.tones?.setVario(tone)
    }

    override fun stopVario() {
        session?.tones?.stopVario()
    }

    // -- Sound files and music ------------------------------------------------------------------

    override fun playSound(file: String, priority: SpeechPriority, category: AudioCategory): Boolean {
        val active = session ?: return false
        val resolved = playable(file) ?: return false
        val name = library.relativeName(resolved)
        active.post { active.submit(ItemKind.SOUND, name, priority, category, flush = false) }
        return true
    }

    override fun startMusic(file: String, loop: Boolean): Boolean {
        val active = session ?: return false
        val resolved = playable(file) ?: return false
        active.post { active.startMusic(resolved, loop) }
        return true
    }

    override fun pauseMusic() {
        val active = session ?: return
        active.post { active.pauseMusic() }
    }

    override fun stopMusic() {
        val active = session ?: return
        active.post { active.stopMusic() }
    }

    override suspend fun listSoundFiles(): List<SoundFile> =
        withContext(dispatchers.io) { library.list(::probeDurationMs) }

    /** The existing, supported and not known-broken file behind [name]. Only file-system metadata is read. */
    private fun playable(name: String): File? {
        val resolved = try {
            library.resolve(name)
        } catch (e: RuntimeException) {
            null
        } ?: return null
        return if (library.isUndecodable(resolved)) null else resolved
    }

    // -- Haptics --------------------------------------------------------------------------------

    override fun haptic(pattern: HapticPattern) {
        if (!hapticsEnabled) return
        val active = session ?: return
        active.post { active.vibrate(pattern) }
    }

    override fun setHapticsEnabled(enabled: Boolean) {
        hapticsEnabled = enabled
        if (!enabled) {
            val active = session ?: return
            active.post { active.cancelVibration() }
        }
    }

    // -- Volume and flow control ----------------------------------------------------------------

    override fun setVolume(category: AudioCategory, percent: Int) {
        if (volumes.setCategory(category, percent)) session?.volumesChanged()
    }

    override fun setMasterVolume(percent: Int) {
        if (volumes.setMaster(percent)) session?.volumesChanged()
    }

    override fun setVolumeOverride(percent: Int?) {
        if (volumes.setOverride(percent)) session?.volumesChanged()
    }

    override fun flush(category: AudioCategory?) {
        val active = session ?: return
        active.post { active.flushQueue(category) }
    }

    override fun stopAll() {
        val active = session ?: return
        active.tones.stopAll()
        active.post { active.stopEverything() }
    }

    // -- One start()..shutdown() cycle ----------------------------------------------------------

    private inner class Session :
        PlaybackController.Output,
        SpeechOutput.Listener,
        MusicPlayback.Listener,
        FocusController.Callbacks {

        private val thread = HandlerThread("ax12-audio", Process.THREAD_PRIORITY_FOREGROUND).apply { start() }
        private val handler = Handler(thread.looper)

        /** The tone thread; safe to use from any thread. */
        val tones = TonePlayer(appContext, attributes(AudioAttributes.CONTENT_TYPE_SONIFICATION))

        /** Guards the engine's state flows against a late write of a closed session. */
        private val publishLock = Any()
        private var publishing = true

        @Volatile
        private var closed = false

        // Control-thread state, created by setUp() before any other posted task runs.
        private val controller = PlaybackController(this, SystemClock::elapsedRealtime)
        private lateinit var speech: SpeechOutput
        private lateinit var sound: SoundPlayback
        private lateinit var music: MusicPlayback
        private lateinit var focus: FocusController
        private lateinit var haptics: Haptics
        private var receiverRegistered = false
        private var speechPlaying = false

        private val watchdogToken = Any()
        private val volumeToken = Any()
        private val recheckToken = Any()

        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                // Several broadcasts arrive per package change; look once they have settled.
                handler.removeCallbacksAndMessages(recheckToken)
                handler.postDelayed({ guarded { recheckSpeech() } }, recheckToken, RECHECK_DELAY_MS)
            }
        }

        init {
            tones.start()
            pushToneGains()
            handler.post { if (!closed) guarded(::setUp) }
        }

        /** Runs [task] on the control thread unless the session is closed ([always] ignores that). */
        fun post(always: Boolean = false, task: () -> Unit) {
            if (closed && !always) return
            handler.post { if (always || !closed) guarded(task) }
        }

        /** Audio is a convenience (design rule 2): a failure in here must never take the app down. */
        private inline fun guarded(task: () -> Unit) {
            try {
                task()
            } catch (e: RuntimeException) {
                Log.w(TAG, "audio task failed", e)
            }
        }

        private inline fun publish(update: () -> Unit) {
            synchronized(publishLock) { if (publishing) update() }
        }

        private fun setUp() {
            val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            haptics = Haptics(appContext)
            focus = FocusController(audioManager, handler, attributes(AudioAttributes.CONTENT_TYPE_SPEECH), this)
            sound = SoundPlayback(handler, attributes(AudioAttributes.CONTENT_TYPE_SONIFICATION), ::onSoundFinished)
            music = MusicPlayback(handler, attributes(AudioAttributes.CONTENT_TYPE_MUSIC), this)
            music.setVolume(volumes.gain(AudioCategory.MUSIC))
            speech = SpeechOutput(appContext, handler, attributes(AudioAttributes.CONTENT_TYPE_SPEECH), this)
            speech.configure(speechLanguage, speechRate)
            registerReceiver()
            speech.connect()
        }

        /** Ends the session. Callable from any thread; does not wait. */
        fun close() {
            closed = true
            synchronized(publishLock) {
                publishing = false
                ttsStatusState.value = TtsStatus.Initializing
                isSpeakingState.value = false
                musicPlayingState.value = false
            }
            tones.shutdown()
            handler.post {
                guarded(::tearDown)
                handler.removeCallbacksAndMessages(null)
                thread.quitSafely()
            }
        }

        private fun tearDown() {
            if (receiverRegistered) {
                receiverRegistered = false
                try {
                    appContext.unregisterReceiver(receiver)
                } catch (e: IllegalArgumentException) {
                    // Was never registered after all.
                }
            }
            if (!::speech.isInitialized) return
            controller.stopAll()
            music.stop()
            sound.release()
            speech.release()
            focus.abandon()
            haptics.cancel()
        }

        private fun registerReceiver() {
            val packages = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addDataScheme("package")
            }
            val other = IntentFilter().apply {
                addAction(TextToSpeech.Engine.ACTION_TTS_DATA_INSTALLED)
                addAction(Intent.ACTION_LOCALE_CHANGED)
            }
            // Exported: the voice-data broadcast comes from the engine's app. The receiver only
            // triggers a debounced re-check, so there is nothing to abuse.
            ContextCompat.registerReceiver(appContext, receiver, packages, null, handler, ContextCompat.RECEIVER_EXPORTED)
            receiverRegistered = true
            ContextCompat.registerReceiver(appContext, receiver, other, null, handler, ContextCompat.RECEIVER_EXPORTED)
        }

        // -- Requests (control thread) ----------------------------------------------------------

        fun submit(kind: ItemKind, content: String, priority: SpeechPriority, category: AudioCategory, flush: Boolean) {
            controller.submit(kind, content, priority, category, flush)
        }

        fun configureSpeech() = speech.configure(speechLanguage, speechRate)

        fun recheckSpeech() = speech.recheck()

        /** Blocking engine query; called from an IO thread after a control-thread round trip. */
        fun availableLanguages(): List<String> = if (closed) emptyList() else speech.availableLanguages()

        fun startMusic(file: File, loop: Boolean) {
            if (!music.start(file, loop, volumes.gain(AudioCategory.MUSIC))) library.markUndecodable(file)
            music.setDucked(speechPlaying)
            musicChanged()
        }

        fun pauseMusic() {
            music.pause()
            musicChanged()
        }

        fun stopMusic() {
            music.stop()
            musicChanged()
        }

        fun vibrate(pattern: HapticPattern) {
            // Checked again here: the switch may have been turned off after the request was posted.
            if (hapticsEnabled) haptics.play(pattern)
        }

        fun cancelVibration() = haptics.cancel()

        fun flushQueue(category: AudioCategory?) = controller.flush(category)

        fun stopEverything() {
            controller.stopAll()
            music.stop()
            musicChanged()
        }

        /** Callable from any thread. Tone gains change at once; players follow on the control thread. */
        fun volumesChanged() {
            if (closed) return
            pushToneGains()
            // A pot driving the volume override fires on every tick: one update is enough.
            handler.removeCallbacksAndMessages(volumeToken)
            handler.postDelayed({ if (!closed) guarded(::applyPlayerVolumes) }, volumeToken, 0L)
        }

        private fun pushToneGains() {
            for (category in AudioCategory.entries) tones.setGain(category, volumes.gain(category))
        }

        private fun applyPlayerVolumes() {
            music.setVolume(volumes.gain(AudioCategory.MUSIC))
            val playing = controller.current
            if (playing != null && playing.kind == ItemKind.SOUND) sound.setVolume(volumes.gain(playing.category))
            // Speech takes its volume per utterance; the next one uses the new value.
        }

        // -- PlaybackController.Output ----------------------------------------------------------

        override fun availability(kind: ItemKind): PlaybackController.Availability = when (kind) {
            ItemKind.SOUND -> PlaybackController.Availability.READY
            ItemKind.SPEECH -> when (speech.status) {
                TtsStatus.Initializing -> PlaybackController.Availability.WAIT
                is TtsStatus.Ready -> PlaybackController.Availability.READY
                else -> PlaybackController.Availability.NEVER
            }
        }

        override fun begin(item: QueueItem): Boolean {
            val gain = volumes.gain(item.category)
            return when (item.kind) {
                ItemKind.SPEECH -> {
                    if (!speech.speak(item.id, item.content, gain)) return false
                    publish {
                        lastSpokenState.value = SpokenText(item.content, item.priority, SystemClock.elapsedRealtime())
                    }
                    armWatchdog(item)
                    true
                }
                ItemKind.SOUND -> {
                    val file = library.resolve(item.content) ?: return false
                    sound.begin(item.id, file, gain)
                }
            }
        }

        override fun cancel(item: QueueItem) {
            when (item.kind) {
                ItemKind.SPEECH -> {
                    handler.removeCallbacksAndMessages(watchdogToken)
                    speech.stop()
                }
                ItemKind.SOUND -> sound.cancel()
            }
        }

        override fun onStateChanged(playing: Boolean, busy: Boolean) {
            speechPlaying = playing
            publish { isSpeakingState.value = busy }
            music.setDucked(playing)
            updateFocus()
        }

        /** An engine that never reports the end of an utterance must not block the queue. */
        private fun armWatchdog(item: QueueItem) {
            handler.removeCallbacksAndMessages(watchdogToken)
            val rate = speechRate.let { if (it.isNaN()) 1f else it.coerceIn(0.5f, 2.5f) }
            val limit = ((SPEECH_BASE_LIMIT_MS + SPEECH_LIMIT_PER_CHAR_MS * item.content.length) / rate)
                .toLong().coerceAtMost(SPEECH_MAX_LIMIT_MS)
            handler.postDelayed(
                {
                    guarded {
                        if (controller.current?.id == item.id) {
                            speech.stop()
                            controller.onFinished(item.id)
                        }
                    }
                },
                watchdogToken,
                limit,
            )
        }

        // -- SpeechOutput.Listener --------------------------------------------------------------

        override fun onSpeechStatus(status: TtsStatus) {
            publish { ttsStatusState.value = status }
            // Waiting utterances start now, or are dropped if speech turned out to be unavailable.
            controller.onAvailabilityChanged()
        }

        override fun onUtteranceFinished(id: Long) {
            val current = controller.current ?: return
            if (current.id != id || current.kind != ItemKind.SPEECH) return
            handler.removeCallbacksAndMessages(watchdogToken)
            controller.onFinished(id)
        }

        private fun onSoundFinished(id: Long, file: File, ok: Boolean) {
            if (!ok) library.markUndecodable(file)
            controller.onFinished(id)
        }

        // -- Music and focus --------------------------------------------------------------------

        override fun onMusicStateChanged() = musicChanged()

        override fun onMusicFailed(file: File) = library.markUndecodable(file)

        override fun onMusicFocusLost(transient: Boolean) {
            music.pause(forFocus = transient)
            musicChanged()
        }

        override fun onMusicFocusRegained() {
            music.resumeAfterFocusLoss()
            musicChanged()
        }

        private fun musicChanged() {
            publish { musicPlayingState.value = music.isPlaying }
            updateFocus()
        }

        private fun updateFocus() = focus.update(music = music.wantsFocus, speech = speechPlaying)
    }

    private companion object {
        const val TAG = "Ax12Audio"

        /** How long [availableLanguages] waits for the control thread. */
        const val CONTROL_ROUND_TRIP_MS = 2_000L

        /** How long [availableLanguages] waits for a binding engine. */
        const val SPEECH_INIT_WAIT_MS = 6_000L

        const val RECHECK_DELAY_MS = 1_500L

        const val SPEECH_BASE_LIMIT_MS = 4_000f
        const val SPEECH_LIMIT_PER_CHAR_MS = 200f
        const val SPEECH_MAX_LIMIT_MS = 120_000L

        /**
         * Everything plays on the media stream so that one hardware volume controls all of it;
         * the per-category volumes are applied by the engine itself.
         */
        fun attributes(contentType: Int): AudioAttributes =
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(contentType).build()
    }
}
