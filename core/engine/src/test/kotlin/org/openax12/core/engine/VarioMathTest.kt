package org.openax12.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openax12.core.model.SensorId
import org.openax12.core.model.Sensors
import org.openax12.core.model.VarioConfig
import org.openax12.core.model.VarioTone

class VarioMathTest {

    private val config = VarioConfig()

    @Test
    fun `silent inside the dead band and for a missing value`() {
        assertEquals(VarioTone.SILENT, VarioMath.tone(0.0, config))
        assertEquals(VarioTone.SILENT, VarioMath.tone(0.5, config))
        assertEquals(VarioTone.SILENT, VarioMath.tone(-0.5, config))
        assertEquals(VarioTone.SILENT, VarioMath.tone(Double.NaN, config))
    }

    @Test
    fun `steady beep at the zero pitch when the centre is not silent`() {
        val tone = VarioMath.tone(0.0, config.copy(centerSilent = false))
        assertFalse(tone.silent)
        assertEquals(700f, tone.frequencyHz, 0.01f)
        assertEquals(250, tone.onMs)
        assertEquals(250, tone.offMs)
        assertFalse(tone.isContinuous)
    }

    @Test
    fun `climb beeps with rising pitch and faster cadence`() {
        val half = VarioMath.tone(5.0, config)
        assertEquals(1200f, half.frequencyHz, 0.01f)
        assertEquals(145, half.onMs)
        assertEquals(145, half.offMs)

        val slow = VarioMath.tone(1.0, config)
        val fast = VarioMath.tone(8.0, config)
        assertTrue(fast.frequencyHz > slow.frequencyHz)
        assertTrue(fast.onMs + fast.offMs < slow.onMs + slow.offMs)
        assertFalse(fast.isContinuous)
    }

    @Test
    fun `climb beyond the range is clamped`() {
        val max = VarioMath.tone(10.0, config)
        val beyond = VarioMath.tone(25.0, config)
        assertEquals(1700f, max.frequencyHz, 0.01f)
        assertEquals(40, max.onMs)
        assertEquals(40, max.offMs)
        assertEquals(max, beyond)
    }

    @Test
    fun `sink is a continuous tone that falls with the sink rate`() {
        val half = VarioMath.tone(-5.0, config)
        assertTrue(half.isContinuous)
        assertEquals(0, half.offMs)
        assertEquals(500f, half.frequencyHz, 0.01f)
        assertEquals(300f, VarioMath.tone(-10.0, config).frequencyHz, 0.01f)
        assertEquals(300f, VarioMath.tone(-30.0, config).frequencyHz, 0.01f)
    }

    @Test
    fun `just outside the dead band the tone starts near the zero pitch`() {
        val tone = VarioMath.tone(0.6, config)
        assertFalse(tone.silent)
        assertEquals(760f, tone.frequencyHz, 0.01f)
    }

    @Test
    fun `nonsense settings never produce a zero period or pitch`() {
        val odd = config.copy(rangeMax = 0.0, rangeMin = 0.0, repeatMaxMs = 0, repeatZeroMs = 0, pitchMinHz = 0, pitchMaxHz = 0)
        val climb = VarioMath.tone(3.0, odd)
        assertTrue(climb.onMs >= 1 && climb.offMs >= 1)
        assertTrue(climb.frequencyHz >= 20f)
        val sink = VarioMath.tone(-3.0, odd)
        assertTrue(sink.frequencyHz >= 20f)
        assertTrue(sink.isContinuous)
    }

    @Test
    fun `speed and altitude sensor selection`() {
        assertEquals(Sensors.VERTICAL_SPEED, VarioMath.speedSensor(config))
        assertEquals(SensorId("sdk.42"), VarioMath.speedSensor(config.copy(sensor = SensorId("sdk.42"))))
        assertNull(VarioMath.speedSensor(config.copy(deriveFromAltitude = true)))
        assertEquals(Sensors.BARO_ALTITUDE, VarioMath.altitudeSensor(config))
        assertEquals(Sensors.GPS_ALTITUDE, VarioMath.altitudeSensor(config.copy(altitudeSensor = Sensors.GPS_ALTITUDE)))
    }

    @Test
    fun `altitude derivative converges to a constant climb rate`() {
        val filter = VarioMath.AltitudeDerivative(timeConstantMs = 800)
        assertEquals(0.0, filter.update(100.0, 0), EPS)
        var speed = 0.0
        for (step in 1..100) speed = filter.update(100.0 + 2.0 * step * 0.1, step * 100L)
        assertEquals(2.0, speed, 0.01)
    }

    @Test
    fun `altitude derivative smooths a step`() {
        val filter = VarioMath.AltitudeDerivative(timeConstantMs = 800)
        filter.update(0.0, 0)
        val first = filter.update(1.0, 100)
        // Raw rate is 10 m/s; one 100 ms step of an 800 ms filter lets about 12 % through.
        assertEquals(10.0 * (1 - Math.exp(-0.125)), first, 1e-9)
        val second = filter.update(1.0, 200)
        assertTrue(second < first && second > 0.0)
    }

    @Test
    fun `altitude derivative without filter returns the raw rate`() {
        val filter = VarioMath.AltitudeDerivative(timeConstantMs = 0)
        filter.update(10.0, 1000)
        assertEquals(-4.0, filter.update(8.0, 1500), EPS)
    }

    @Test
    fun `altitude derivative ignores bad samples and restarts after a gap`() {
        val filter = VarioMath.AltitudeDerivative(timeConstantMs = 0)
        filter.update(0.0, 1000)
        val speed = filter.update(1.0, 2000)
        assertEquals(1.0, speed, EPS)
        assertEquals(speed, filter.update(50.0, 2000), EPS)
        assertEquals(speed, filter.update(50.0, 1500), EPS)
        assertEquals(speed, filter.update(Double.NaN, 2500), EPS)
        // More than five seconds without a sample: start again from zero.
        assertEquals(0.0, filter.update(500.0, 8000), EPS)
        assertEquals(2.0, filter.update(502.0, 9000), EPS)
        filter.reset()
        assertEquals(0.0, filter.update(0.0, 9100), EPS)
    }
}
