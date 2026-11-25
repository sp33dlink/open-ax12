package org.openax12.core.model

import kotlinx.serialization.Serializable

/** Priority of a speech / sound request in the audio queue. Higher priorities are played first. */
@Serializable
enum class SpeechPriority {
    /** Dropped when the queue is busy (switch-position chatter). */
    LOW,
    NORMAL,

    /** Jumps ahead of [NORMAL] and [LOW] items. */
    HIGH,

    /** Pre-empts whatever is playing and flushes lower priorities (alarms). */
    ALARM,
}

/** Volume category of everything the audio engine plays; each has its own volume in [AppSettings.volumes]. */
@Serializable
enum class AudioCategory {
    /** Text-to-speech: voice alerts, spoken values, flight modes. */
    VOICE,

    /** Alarm sounds and alarm speech. */
    ALARM,

    /** The continuous vario tone. */
    VARIO,

    /** Short UI / trim / timer beeps. */
    BEEP,

    /** User sound files played by special functions. */
    SOUND,

    /** Background music. */
    MUSIC,
}

/** Built-in beep patterns of the tone synth. */
@Serializable
enum class BeepPattern {
    SHORT,
    DOUBLE,
    TRIPLE,
    LONG,
    WARNING,
    ERROR,

    /** One trim step. */
    TRIM_STEP,

    /** Trim passed the centre (distinct tone). */
    TRIM_CENTER,

    /** Trim reached its end. */
    TRIM_LIMIT,

    /** Timer countdown tick. */
    TIMER_TICK,

    /** Timer expired. */
    TIMER_EXPIRED,

    /** Confirmation of a user action (save, touch-lock). */
    CONFIRM,
}

/** Built-in vibration patterns. */
@Serializable
enum class HapticPattern {
    SHORT,
    DOUBLE,
    TRIPLE,
    LONG,
    WARNING,
}

/**
 * One step of a custom tone sequence.
 *
 * @property frequencyHz tone frequency; 0 = silence for [durationMs]
 * @property durationMs length of the tone
 * @property pauseMs silence after the tone
 */
@Serializable
data class ToneStep(val frequencyHz: Int, val durationMs: Int, val pauseMs: Int = 0)

/**
 * What the vario tone generator should play right now. Produced by `VarioMath.tone` in
 * `:core:engine`, consumed by `AudioEngine.setVarioTone` in `:core:audio`.
 *
 * @property silent no tone at all (inside the silent dead band, or vario off)
 * @property frequencyHz tone pitch
 * @property onMs length of one beep; ignored when [offMs] is 0
 * @property offMs pause between beeps; 0 = continuous tone (sink)
 */
data class VarioTone(
    val silent: Boolean = true,
    val frequencyHz: Float = 0f,
    val onMs: Int = 0,
    val offMs: Int = 0,
) {
    /** True for an uninterrupted tone (sink tone). */
    val isContinuous: Boolean get() = !silent && offMs == 0

    companion object {
        /** No tone. */
        val SILENT = VarioTone()
    }
}
