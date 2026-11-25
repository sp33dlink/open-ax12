package org.openax12.core.runtime

import android.content.Context

/**
 * The words of the runtime engine's built-in announcements. They are text-to-speech input, so
 * they live in string resources of this module (`runtime_spoken_*`) and are read with
 * [fromContext]; tests pass their own instance.
 *
 * @property timerExpiredFormat format string with one `%1$d` for the 1-based timer number
 */
data class RuntimePhrases(
    val telemetryLost: String,
    val telemetryRecovered: String,
    val lowTxVoltage: String,
    val timerExpiredFormat: String,
    val switchUp: String,
    val switchMiddle: String,
    val switchDown: String,
) {
    /** "Timer 1 expired" for [timer] (1-based). */
    fun timerExpired(timer: Int): String = String.format(java.util.Locale.ROOT, timerExpiredFormat, timer)

    companion object {
        /** Reads the phrases from the string resources of this module. */
        fun fromContext(context: Context): RuntimePhrases = RuntimePhrases(
            telemetryLost = context.getString(R.string.runtime_spoken_telemetry_lost),
            telemetryRecovered = context.getString(R.string.runtime_spoken_telemetry_recovered),
            lowTxVoltage = context.getString(R.string.runtime_spoken_low_tx_voltage),
            timerExpiredFormat = context.getString(R.string.runtime_spoken_timer_expired),
            switchUp = context.getString(R.string.runtime_spoken_switch_up),
            switchMiddle = context.getString(R.string.runtime_spoken_switch_middle),
            switchDown = context.getString(R.string.runtime_spoken_switch_down),
        )
    }
}
