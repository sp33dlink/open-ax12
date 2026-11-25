package org.openax12.core.audio

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import org.openax12.core.model.AppDispatchers
import org.openax12.core.model.AudioCategory
import org.openax12.core.model.BeepPattern
import org.openax12.core.model.HapticPattern
import org.openax12.core.model.SensorUnit
import org.openax12.core.model.SpeechPriority
import org.openax12.core.model.ToneStep
import org.openax12.core.model.VarioTone
import java.io.File

/** Availability of text-to-speech. Shown in the system settings with a clear warning when not [Ready]. */
sealed interface TtsStatus {

    /** The engine is still binding. Speech requests are queued. */
    data object Initializing : TtsStatus

    /**
     * Text-to-speech works.
     *
     * @property engine package name of the engine in use
     * @property language BCP-47 tag of the language actually used
     * @property requestedLanguageAvailable false if the configured speech language is missing and
     *   [language] is a fallback
     */
    data class Ready(
        val engine: String,
        val language: String,
        val requestedLanguageAvailable: Boolean = true,
    ) : TtsStatus

    /** No text-to-speech engine is installed (assumption 9). Speech requests are dropped; beeps still work. */
    data object NoEngine : TtsStatus

    /** An engine exists but has no usable voice data for [language] or any fallback. */
    data class LanguageMissing(val engine: String, val language: String) : TtsStatus

    /** The engine failed to initialize. */
    data class Error(val message: String) : TtsStatus

    /** True for [Ready]. */
    val isReady: Boolean get() = this is Ready
}

/**
 * A user sound file in the library's `sounds/` folder.
 *
 * @property name file name relative to the sounds folder — the value stored in special functions
 * @property durationMs duration if known, else 0
 */
data class SoundFile(val name: String, val path: String, val sizeBytes: Long = 0, val durationMs: Long = 0)

/** What was spoken last, for the UI ("last announcement") and tests. */
data class SpokenText(val text: String, val priority: SpeechPriority, val timestampMs: Long)

/**
 * Everything audible and tactile: text-to-speech, tone synth, sound files, background music, the
 * vario tone and haptics.
 *
 * ## Behaviour
 * - **Queue**: speech and sound files share one priority queue per engine. [SpeechPriority.ALARM]
 *   interrupts whatever is playing and flushes lower priorities; [SpeechPriority.HIGH] goes to the
 *   front; [SpeechPriority.LOW] is dropped when anything is queued or playing. Identical texts
 *   already waiting in the queue are not queued twice.
 * - **Beeps / tones / vario** are synthesized with low latency and mixed over speech; they are not queued.
 * - **Audio focus**: speech and sounds request transient focus (ducking music of other apps);
 *   the app's own background music is ducked while speech plays.
 * - **Volume**: every request belongs to an [AudioCategory]; its effective volume is
 *   master × category (see [setVolume]).
 * - **Settings**: the engine never reads `AppSettings` itself (it gets no settings repository).
 *   The app graph pushes master volume, category volumes, speech language / rate and the haptics
 *   switch through [setMasterVolume], [setVolume], [setSpeech] and [setHapticsEnabled] — once
 *   before [start] and again on every settings change. Screens never call these four; they
 *   update the settings. Until the first push everything is at 100 %, system language, rate 1.0,
 *   haptics on.
 * - **Threading**: every function may be called from any thread and returns immediately; nothing
 *   blocks on the audio hardware. *Playback* requests made before [start] or after [shutdown] are
 *   ignored; the four configuration setters above are accepted at any time and remembered.
 * - Failures (missing file, no TTS engine) never throw; they are reflected in the return value
 *   or in [ttsStatus].
 */
interface AudioEngine {

    /** Text-to-speech engine / language availability. */
    val ttsStatus: StateFlow<TtsStatus>

    /** The last text that actually started speaking; null until then. */
    val lastSpoken: StateFlow<SpokenText?>

    /** True while speech or a sound file is playing or queued. */
    val isSpeaking: StateFlow<Boolean>

    /** True while background music plays (not paused). */
    val musicPlaying: StateFlow<Boolean>

    /** Binds text-to-speech and opens the audio outputs. Idempotent. Called by the app graph at startup. */
    fun start()

    /** Stops everything and releases text-to-speech and audio resources. Idempotent. */
    fun shutdown()

    // -- Speech ---------------------------------------------------------------------------------

    /**
     * Speaks [text].
     *
     * @param flush drop everything queued in the same or a lower priority first
     */
    fun speak(
        text: String,
        priority: SpeechPriority = SpeechPriority.NORMAL,
        category: AudioCategory = AudioCategory.VOICE,
        flush: Boolean = false,
    )

    /**
     * Speaks a number with its unit in the speech language ("12.4 volts", "153 metres",
     * "1 minute 25 seconds" for [SensorUnit.SECOND]), optionally preceded by [name].
     */
    fun speakValue(
        value: Double,
        unit: SensorUnit,
        precision: Int = 0,
        name: String? = null,
        priority: SpeechPriority = SpeechPriority.NORMAL,
        category: AudioCategory = AudioCategory.VOICE,
    )

    /**
     * The phrase [speakValue] would say, without speaking it — for composing longer sentences and
     * for the template renderer's speech formatter.
     */
    fun spokenValue(value: Double, unit: SensorUnit, precision: Int = 0): String

    /**
     * Sets the speech language (BCP-47 tag, empty = system default) and rate (1.0 = normal).
     * [ttsStatus] reflects the result.
     */
    fun setSpeech(language: String, rate: Float = 1.0f)

    /** Languages the current engine can speak, as BCP-47 tags; empty if no engine. */
    suspend fun availableLanguages(): List<String>

    // -- Beeps and tones ------------------------------------------------------------------------

    /** Plays a built-in beep pattern immediately. */
    fun beep(pattern: BeepPattern, category: AudioCategory = AudioCategory.BEEP)

    /** Plays one tone of [frequencyHz] for [durationMs] immediately. */
    fun tone(frequencyHz: Int, durationMs: Int, category: AudioCategory = AudioCategory.BEEP)

    /** Plays a custom tone sequence immediately; a running sequence of the same category is replaced. */
    fun tones(steps: List<ToneStep>, category: AudioCategory = AudioCategory.BEEP)

    // -- Vario ----------------------------------------------------------------------------------

    /**
     * Updates the continuous vario tone. Call on every vertical-speed update while the vario is
     * on; pitch and cadence glide smoothly to the new values. [VarioTone.SILENT] mutes without
     * releasing the generator.
     */
    fun setVarioTone(tone: VarioTone)

    /** Stops the vario tone and releases its generator. */
    fun stopVario()

    // -- Sound files and music ------------------------------------------------------------------

    /**
     * Queues a user sound file ([file] = [SoundFile.name]).
     *
     * @return false if the file does not exist or cannot be decoded
     */
    fun playSound(
        file: String,
        priority: SpeechPriority = SpeechPriority.NORMAL,
        category: AudioCategory = AudioCategory.SOUND,
    ): Boolean

    /**
     * Starts background music from a user sound file, replacing the current track. If the same
     * file is merely paused it resumes where it stopped.
     *
     * @return false if the file does not exist or cannot be decoded
     */
    fun startMusic(file: String, loop: Boolean = true): Boolean

    /** Pauses background music, keeping the position. */
    fun pauseMusic()

    /** Stops background music and forgets the position. */
    fun stopMusic()

    /** The sound files currently in the library's sounds folder, sorted by name. */
    suspend fun listSoundFiles(): List<SoundFile>

    // -- Haptics --------------------------------------------------------------------------------

    /** Vibrates, unless haptics are disabled with [setHapticsEnabled] or the device has no vibrator. */
    fun haptic(pattern: HapticPattern)

    /** Master switch for [haptic]. */
    fun setHapticsEnabled(enabled: Boolean)

    // -- Volume and flow control ----------------------------------------------------------------

    /** Sets the volume of one category in percent (0..100). */
    fun setVolume(category: AudioCategory, percent: Int)

    /** Sets the master volume in percent (0..100), applied on top of every category. */
    fun setMasterVolume(percent: Int)

    /**
     * Temporarily overrides the master volume (special function "set volume" from a pot);
     * `null` removes the override and returns to the value of [setMasterVolume].
     */
    fun setVolumeOverride(percent: Int?)

    /** Drops queued speech and sounds of [category] (all categories when null). What is playing finishes. */
    fun flush(category: AudioCategory? = null)

    /** Stops speech, sounds, tones, vario and music immediately and clears the queue. */
    fun stopAll()
}

/**
 * Creates the app's [AudioEngine].
 *
 * @param context application context
 * @param scope application scope the engine launches its workers in
 * @param soundsDirectory returns the library's `sounds/` folder (may change when storage permission
 *   is granted); null while no library is available
 *
 * There is deliberately no settings parameter: volumes, speech and haptics are pushed by the app
 * graph (see "Settings" in the [AudioEngine] documentation).
 */
fun createAudioEngine(
    context: Context,
    scope: CoroutineScope,
    dispatchers: AppDispatchers = AppDispatchers(),
    soundsDirectory: () -> File?,
): AudioEngine = DefaultAudioEngine(context, scope, dispatchers, soundsDirectory)
