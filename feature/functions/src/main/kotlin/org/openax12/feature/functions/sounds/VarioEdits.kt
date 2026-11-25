package org.openax12.feature.functions.sounds

import org.openax12.core.model.VarioConfig

/** Limits of the vario settings. */
internal object VarioLimits {
    /** Pitch range in Hz the tone synth handles well. */
    const val PITCH_MIN_HZ: Int = 100
    const val PITCH_MAX_HZ: Int = 3000

    /** Beep period range in ms. */
    const val PERIOD_MIN_MS: Int = 40
    const val PERIOD_MAX_MS: Int = 2000

    /** Largest climb / sink rate in m/s the range can be set to. */
    const val RATE_LIMIT: Double = 30.0
}

/** Pure consistency rules for [VarioConfig] edits. */
internal object VarioEdits {

    /**
     * Brings [config] into a consistent shape: dead band around zero (`centerMin ≤ 0 ≤ centerMax`),
     * the range outside the dead band (at least 0.5 m/s), pitches and periods within [VarioLimits].
     * Already consistent configurations are returned unchanged.
     */
    fun normalize(config: VarioConfig): VarioConfig {
        val centerMin = config.centerMin.coerceIn(-VarioLimits.RATE_LIMIT, 0.0)
        val centerMax = config.centerMax.coerceIn(0.0, VarioLimits.RATE_LIMIT)
        val rangeMax = config.rangeMax.coerceIn(centerMax + MIN_RANGE_GAP, VarioLimits.RATE_LIMIT.coerceAtLeast(centerMax + MIN_RANGE_GAP))
        val rangeMin = config.rangeMin.coerceIn(-VarioLimits.RATE_LIMIT.coerceAtLeast(-centerMin + MIN_RANGE_GAP), centerMin - MIN_RANGE_GAP)
        val normalized = config.copy(
            centerMin = centerMin,
            centerMax = centerMax,
            rangeMin = rangeMin,
            rangeMax = rangeMax,
            pitchZeroHz = config.pitchZeroHz.coerceIn(VarioLimits.PITCH_MIN_HZ, VarioLimits.PITCH_MAX_HZ),
            pitchMaxHz = config.pitchMaxHz.coerceIn(VarioLimits.PITCH_MIN_HZ, VarioLimits.PITCH_MAX_HZ),
            pitchMinHz = config.pitchMinHz.coerceIn(VarioLimits.PITCH_MIN_HZ, VarioLimits.PITCH_MAX_HZ),
            repeatZeroMs = config.repeatZeroMs.coerceIn(VarioLimits.PERIOD_MIN_MS, VarioLimits.PERIOD_MAX_MS),
            repeatMaxMs = config.repeatMaxMs.coerceIn(VarioLimits.PERIOD_MIN_MS, VarioLimits.PERIOD_MAX_MS),
        )
        return if (normalized == config) config else normalized
    }

    /** Smallest distance between the dead band and the end of the range in m/s. */
    const val MIN_RANGE_GAP: Double = 0.5

    /** Seconds → tenths for the numeric fields. */
    fun tenths(value: Double): Int = Math.round(value * 10).toInt()

    /** Tenths from a numeric field → value. */
    fun fromTenths(value: Int): Double = value / 10.0
}
