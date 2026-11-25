package org.openax12.core.engine

import org.openax12.core.model.SensorId
import org.openax12.core.model.Sensors
import org.openax12.core.model.VarioConfig
import org.openax12.core.model.VarioTone
import kotlin.math.exp
import kotlin.math.roundToInt

/** Vario tone maths (EdgeTX behaviour): vertical speed → pitch and beep cadence. Pure functions. */
object VarioMath {

    /**
     * Tone for a vertical speed.
     *
     * - Inside the dead band `[centerMin, centerMax]`: silent when `centerSilent`, otherwise a
     *   steady beep at `pitchZeroHz` with period `repeatZeroMs`.
     * - Climb (above `centerMax`): beeps; the pitch rises linearly from `pitchZeroHz` to
     *   `pitchMaxHz` at `rangeMax`, the period shrinks from `repeatZeroMs` to `repeatMaxMs`
     *   (on-time = half the period).
     * - Sink (below `centerMin`): a **continuous** tone (`offMs == 0`) falling linearly from
     *   `pitchZeroHz` to `pitchMinHz` at `rangeMin`.
     * - Values beyond the range are clamped. `NaN` is silent.
     *
     * @param verticalSpeed climb rate in m/s (positive = up)
     */
    fun tone(verticalSpeed: Double, config: VarioConfig): VarioTone {
        if (verticalSpeed.isNaN()) return VarioTone.SILENT
        val zeroHz = config.pitchZeroHz.toDouble()
        if (verticalSpeed >= config.centerMin && verticalSpeed <= config.centerMax) {
            if (config.centerSilent) return VarioTone.SILENT
            return beep(zeroHz, config.repeatZeroMs.toDouble())
        }
        if (verticalSpeed > config.centerMax) {
            val fraction = if (config.rangeMax > 0.0) (verticalSpeed / config.rangeMax).coerceIn(0.0, 1.0) else 1.0
            val hz = zeroHz + (config.pitchMaxHz - zeroHz) * fraction
            val period = config.repeatZeroMs + (config.repeatMaxMs - config.repeatZeroMs) * fraction
            return beep(hz, period)
        }
        val fraction = if (config.rangeMin < 0.0) (verticalSpeed / config.rangeMin).coerceIn(0.0, 1.0) else 1.0
        val hz = zeroHz + (config.pitchMinHz - zeroHz) * fraction
        return VarioTone(
            silent = false,
            frequencyHz = hz.coerceAtLeast(MIN_FREQUENCY_HZ).toFloat(),
            onMs = config.repeatZeroMs.coerceAtLeast(MIN_PERIOD_MS),
            offMs = 0,
        )
    }

    /**
     * The vertical speed the vario should sound for: [VarioConfig.sensor] (default
     * `Sensors.VERTICAL_SPEED`), or null when the configuration asks to derive it from the
     * altitude ([VarioConfig.deriveFromAltitude]) — the caller then feeds
     * [VarioConfig.altitudeSensor] (default `Sensors.BARO_ALTITUDE`) into an [AltitudeDerivative].
     */
    fun speedSensor(config: VarioConfig): SensorId? =
        if (config.deriveFromAltitude) null else config.sensor ?: Sensors.VERTICAL_SPEED

    /** The altitude sensor to derive the vertical speed from (see [speedSensor]). */
    fun altitudeSensor(config: VarioConfig): SensorId = config.altitudeSensor ?: Sensors.BARO_ALTITUDE

    private fun beep(hz: Double, periodMs: Double): VarioTone {
        val period = periodMs.roundToInt().coerceAtLeast(MIN_PERIOD_MS)
        val on = (period / 2).coerceAtLeast(1)
        return VarioTone(
            silent = false,
            frequencyHz = hz.coerceAtLeast(MIN_FREQUENCY_HZ).toFloat(),
            onMs = on,
            offMs = (period - on).coerceAtLeast(1),
        )
    }

    /** Shortest beep period the synth is asked for. */
    private const val MIN_PERIOD_MS = 20

    /** Lowest pitch ever returned (guards against nonsense settings). */
    private const val MIN_FREQUENCY_HZ = 20.0

    /** A gap between altitude samples longer than this restarts the derivative filter. */
    private const val MAX_SAMPLE_GAP_MS = 5_000L

    /**
     * Derives a smoothed vertical speed from altitude samples, for receivers that report altitude
     * but no vario frame. Stateful; one instance per altitude sensor. Not thread-safe.
     *
     * @param timeConstantMs time constant of the low-pass filter applied to the derivative
     */
    class AltitudeDerivative(private val timeConstantMs: Int = 800) {

        /**
         * Feeds one altitude sample and returns the filtered vertical speed in m/s. The first
         * sample returns 0. Samples with a non-increasing timestamp are ignored (the previous
         * result is returned); a gap of more than 5 s restarts the filter.
         *
         * @param altitude altitude in metres
         * @param timestampMs monotonic time of the sample
         */
        fun update(altitude: Double, timestampMs: Long): Double {
            if (altitude.isNaN()) return filtered
            if (!hasSample || timestampMs - lastTimestampMs > MAX_SAMPLE_GAP_MS) {
                hasSample = true
                lastAltitude = altitude
                lastTimestampMs = timestampMs
                filtered = 0.0
                return 0.0
            }
            val dtMs = timestampMs - lastTimestampMs
            if (dtMs <= 0) return filtered
            val raw = (altitude - lastAltitude) / (dtMs / 1000.0)
            val alpha = if (timeConstantMs <= 0) 1.0 else 1.0 - exp(-dtMs.toDouble() / timeConstantMs)
            filtered += alpha * (raw - filtered)
            lastAltitude = altitude
            lastTimestampMs = timestampMs
            return filtered
        }

        /** Forgets all samples. */
        fun reset() {
            hasSample = false
            lastAltitude = 0.0
            lastTimestampMs = 0L
            filtered = 0.0
        }

        private var hasSample = false
        private var lastAltitude = 0.0
        private var lastTimestampMs = 0L
        private var filtered = 0.0
    }
}
