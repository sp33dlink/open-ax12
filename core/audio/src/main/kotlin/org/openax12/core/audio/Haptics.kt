package org.openax12.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import org.openax12.core.model.HapticPattern

/** The built-in vibration patterns as waveform timings. Pure. */
internal object HapticPatterns {

    /**
     * Alternating off / on durations in milliseconds, starting with the initial delay (0), as
     * `VibrationEffect.createWaveform` expects them.
     */
    fun timings(pattern: HapticPattern): LongArray = when (pattern) {
        HapticPattern.SHORT -> longArrayOf(0, 60)
        HapticPattern.DOUBLE -> longArrayOf(0, 60, 90, 60)
        HapticPattern.TRIPLE -> longArrayOf(0, 60, 90, 60, 90, 60)
        HapticPattern.LONG -> longArrayOf(0, 450)
        HapticPattern.WARNING -> longArrayOf(0, 220, 110, 220, 110, 450)
    }

    /** Total length of [pattern] in milliseconds. */
    fun durationMs(pattern: HapticPattern): Long = timings(pattern).sum()
}

/**
 * Vibrator access with API-level guards. Calls are binder calls: use from the audio control
 * thread, never from the main thread.
 */
internal class Haptics(context: Context) {

    private val vibrator: Vibrator? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    } catch (e: RuntimeException) {
        null
    }

    /** False on devices without a vibration motor. */
    val available: Boolean = try {
        vibrator?.hasVibrator() == true
    } catch (e: RuntimeException) {
        false
    }

    /** Vibrates [pattern], replacing a running vibration of this app. */
    fun play(pattern: HapticPattern) {
        val device = vibrator ?: return
        if (!available) return
        try {
            // minSdk is 28, so VibrationEffect (API 26) is always there.
            val effect = VibrationEffect.createWaveform(HapticPatterns.timings(pattern), -1)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // "Alarm" is not silenced by the system's touch-feedback switch or by ringer modes.
                device.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
            } else {
                @Suppress("DEPRECATION")
                device.vibrate(
                    effect,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
            }
        } catch (e: RuntimeException) {
            // Missing permission or a broken vendor service: haptics are optional.
        }
    }

    /** Stops a running vibration. */
    fun cancel() {
        try {
            vibrator?.cancel()
        } catch (e: RuntimeException) {
            // See play().
        }
    }
}
