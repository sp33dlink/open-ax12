package org.openax12.core.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openax12.core.model.AudioCategory
import org.openax12.core.model.BeepPattern
import org.openax12.core.model.ToneStep
import org.openax12.core.model.VarioTone
import kotlin.math.PI
import kotlin.math.abs

class ToneMixerTest {

    private val rate = 48_000
    private val mixer = ToneMixer(rate)

    private fun frames(ms: Int) = rate * ms / 1000

    /** Renders [count] frames in blocks of [block] (an odd size on purpose). */
    private fun ToneMixer.take(count: Int, block: Int = 127): FloatArray {
        val result = FloatArray(count)
        val buffer = FloatArray(block)
        var done = 0
        while (done < count) {
            val n = minOf(block, count - done)
            render(buffer, n)
            System.arraycopy(buffer, 0, result, done, n)
            done += n
        }
        return result
    }

    private fun peak(samples: FloatArray, from: Int = 0, to: Int = samples.size): Float {
        var max = 0f
        for (i in from until to) max = maxOf(max, abs(samples[i]))
        return max
    }

    private fun maxStep(samples: FloatArray, previous: Float = 0f): Float {
        var max = 0f
        var last = previous
        for (sample in samples) {
            max = maxOf(max, abs(sample - last))
            last = sample
        }
        return max
    }

    /** Frequency from interpolated upward zero crossings between [from] and [to]. */
    private fun frequency(samples: FloatArray, from: Int = 0, to: Int = samples.size): Double {
        var first = Double.NaN
        var last = Double.NaN
        var count = 0
        for (i in from + 1 until to) {
            val a = samples[i - 1]
            val b = samples[i]
            if (a < 0f && b >= 0f) {
                val t = (i - 1) + a / (a - b).toDouble()
                if (count == 0) first = t
                last = t
                count++
            }
        }
        check(count >= 2) { "no periods found" }
        return (count - 1) * rate / (last - first)
    }

    /** Largest sample-to-sample step of a sine of [hz] at [amplitude] plus an envelope of [fadeMs]. */
    private fun sineStepBound(hz: Double, amplitude: Double, fadeMs: Double, slope: Double = 1.0): Float {
        val carrier = amplitude * slope * 2.0 * PI * hz / rate
        val envelope = amplitude * 1.5 / (rate * fadeMs / 1000.0)
        return (carrier + envelope + 1e-4).toFloat()
    }

    /** Lengths in frames of the audible stretches (1 ms resolution). */
    private fun bursts(samples: FloatArray, threshold: Float = 0.05f): List<IntRange> {
        val window = rate / 1000
        val result = ArrayList<IntRange>()
        var start = -1
        var index = 0
        while (index < samples.size) {
            val end = minOf(samples.size, index + window)
            val loud = peak(samples, index, end) > threshold
            if (loud && start < 0) start = index
            if (!loud && start >= 0) {
                result += start until index
                start = -1
            }
            index = end
        }
        if (start >= 0) result += start until samples.size
        return result
    }

    // -- Beeps ----------------------------------------------------------------------------------

    @Test
    fun `starts idle and silent`() {
        assertTrue(mixer.isIdle)
        assertEquals(0f, peak(mixer.take(1000)), 0f)
    }

    @Test
    fun `tone frequency is accurate`() {
        for (hz in listOf(220, 440, 1000, 1900, 2637, 5000)) {
            val fresh = ToneMixer(rate)
            fresh.playSequence(AudioCategory.BEEP, listOf(ToneStep(hz, 1000)))
            val samples = fresh.take(frames(1000))
            val measured = frequency(samples, frames(50), frames(950))
            assertEquals("$hz Hz", hz.toDouble(), measured, hz * 0.0005)
        }
    }

    @Test
    fun `tone frequency is accurate at other sample rates`() {
        for (otherRate in listOf(44_100, 22_050, 96_000)) {
            val other = ToneMixer(otherRate)
            other.playSequence(AudioCategory.BEEP, listOf(ToneStep(1000, 500)))
            val samples = FloatArray(otherRate / 2)
            other.render(samples, samples.size)
            var crossings = 0
            for (i in otherRate / 20 until otherRate * 9 / 20) if (samples[i - 1] < 0f && samples[i] >= 0f) crossings++
            // 400 ms of 1 kHz.
            assertEquals("$otherRate", 400.0, crossings.toDouble(), 1.0)
        }
    }

    @Test
    fun `a beep has the requested length, fades in and out and ends in silence`() {
        mixer.playSequence(AudioCategory.BEEP, listOf(ToneStep(1000, 100)))
        assertFalse(mixer.isIdle)
        val samples = mixer.take(frames(150))

        assertEquals(ToneMixer.BEEP_AMPLITUDE, peak(samples), 0.01f)
        assertTrue("starts at zero", abs(samples[0]) < 0.01f)
        assertTrue("soft attack", peak(samples, 0, frames(1)) < 0.3f)
        assertTrue("soft release", peak(samples, frames(99), frames(100)) < 0.3f)
        assertEquals("silent after its length", 0f, peak(samples, frames(100) + 1, samples.size), 0f)
        val audible = bursts(samples).single()
        assertEquals(frames(100).toDouble(), (audible.last - audible.first + 1).toDouble(), frames(4).toDouble())
        assertTrue(mixer.isIdle)
    }

    @Test
    fun `beeps have no discontinuities`() {
        for (pattern in BeepPattern.entries) {
            val fresh = ToneMixer(rate)
            val steps = BeepPatterns.steps(pattern)
            fresh.playSequence(AudioCategory.BEEP, steps)
            val samples = fresh.take(frames(steps.sumOf { it.durationMs + it.pauseMs } + 50))
            val highest = steps.maxOf { it.frequencyHz }.toDouble()
            // The shortest pattern step (25 ms) still gets the full 3 ms fade.
            val bound = sineStepBound(highest, ToneMixer.BEEP_AMPLITUDE.toDouble(), ToneMixer.BEEP_FADE_MS.toDouble())
            assertTrue("$pattern: step ${maxStep(samples)} > $bound", maxStep(samples) <= bound)
            assertEquals("$pattern ends in silence", 0f, samples.last(), 0f)
            assertTrue("$pattern", fresh.isIdle)
        }
    }

    @Test
    fun `very short tones still fade`() {
        mixer.playSequence(AudioCategory.BEEP, listOf(ToneStep(1000, 2)))
        val samples = mixer.take(frames(10))
        // 2 ms = 96 frames: 48 up, 48 down.
        val bound = sineStepBound(1000.0, ToneMixer.BEEP_AMPLITUDE.toDouble(), 1.0)
        assertTrue(maxStep(samples) <= bound)
        assertTrue(peak(samples) > 0.3f)
        assertTrue(mixer.isIdle)
    }

    @Test
    fun `sequence steps and pauses are timed`() {
        mixer.playSequence(AudioCategory.BEEP, listOf(ToneStep(1000, 100, 50), ToneStep(1500, 100)))
        val samples = mixer.take(frames(300))

        assertEquals(1000.0, frequency(samples, frames(10), frames(90)), 2.0)
        assertTrue("pause is silent", peak(samples, frames(100) + 1, frames(150)) < 1e-4f)
        assertEquals(1500.0, frequency(samples, frames(160), frames(240)), 2.0)
        assertTrue("silent afterwards", peak(samples, frames(250) + 1, samples.size) < 1e-4f)
        assertEquals(2, bursts(samples).size)
        assertTrue(mixer.isIdle)
    }

    @Test
    fun `a zero frequency step is a pause`() {
        mixer.playSequence(AudioCategory.BEEP, listOf(ToneStep(0, 100), ToneStep(1000, 50)))
        val samples = mixer.take(frames(200))
        assertEquals(0f, peak(samples, 0, frames(100)), 0f)
        assertTrue(peak(samples, frames(100), frames(150)) > 0.6f)
    }

    @Test
    fun `adjacent tones are played legato with a continuous phase`() {
        mixer.playSequence(AudioCategory.BEEP, listOf(ToneStep(1000, 100), ToneStep(1700, 100)))
        val samples = mixer.take(frames(250))

        // No dip between the tones: every millisecond around the change is at full level.
        for (ms in 95 until 105) {
            assertTrue("level at $ms ms", peak(samples, frames(ms), frames(ms + 1)) > 0.6f)
        }
        val bound = sineStepBound(1700.0, ToneMixer.BEEP_AMPLITUDE.toDouble(), ToneMixer.BEEP_FADE_MS.toDouble())
        assertTrue(maxStep(samples) <= bound)
        assertEquals(1, bursts(samples).size)
    }

    @Test
    fun `a new sequence replaces the running one without a click`() {
        mixer.playSequence(AudioCategory.BEEP, listOf(ToneStep(800, 1000)))
        val before = mixer.take(frames(100))
        mixer.playSequence(AudioCategory.BEEP, listOf(ToneStep(2000, 100)))
        val after = mixer.take(frames(300))

        val bound = sineStepBound(2000.0, ToneMixer.BEEP_AMPLITUDE.toDouble(), ToneMixer.REPLACE_FADE_MS.toDouble())
        assertTrue(maxStep(after, previous = before.last()) <= bound)
        assertEquals(2000.0, frequency(after, frames(20), frames(90)), 4.0)
        // The old one-second tone is gone: silence after the new 100 ms (+ 2 ms fade-out of the old).
        assertEquals(0f, peak(after, frames(110), after.size), 0f)
        assertTrue(mixer.isIdle)
    }

    @Test
    fun `an empty sequence stops the voice`() {
        mixer.playSequence(AudioCategory.BEEP, listOf(ToneStep(1000, 1000)))
        mixer.take(frames(50))
        mixer.playSequence(AudioCategory.BEEP, emptyList())
        val after = mixer.take(frames(50))
        assertEquals(0f, peak(after, frames(5), after.size), 0f)
        assertTrue(mixer.isIdle)
    }

    @Test
    fun `categories have their own voices and gains`() {
        mixer.setGain(AudioCategory.ALARM, 0.5f)
        mixer.playSequence(AudioCategory.ALARM, listOf(ToneStep(1000, 100)))
        val alarm = mixer.take(frames(150))
        assertEquals(ToneMixer.BEEP_AMPLITUDE * 0.5f, peak(alarm), 0.01f)

        // A beep does not cut a running alarm tone.
        mixer.playSequence(AudioCategory.ALARM, listOf(ToneStep(500, 200)))
        mixer.take(frames(20))
        mixer.playSequence(AudioCategory.BEEP, listOf(ToneStep(3000, 20)))
        val both = mixer.take(frames(250))
        assertEquals(500.0, frequency(both, frames(60), frames(170)), 3.0)
        assertTrue(peak(both, 0, frames(20)) > ToneMixer.BEEP_AMPLITUDE * 0.9f)
    }

    @Test
    fun `gain zero mutes and gain changes are ramped`() {
        mixer.setGain(AudioCategory.BEEP, 0f)
        mixer.playSequence(AudioCategory.BEEP, listOf(ToneStep(1000, 50)))
        assertEquals(0f, peak(mixer.take(frames(60))), 0f)

        mixer.setGain(AudioCategory.BEEP, 1f)
        mixer.playSequence(AudioCategory.BEEP, listOf(ToneStep(1000, 500)))
        val loud = mixer.take(frames(100))
        mixer.setGain(AudioCategory.BEEP, 0.1f)
        val fading = mixer.take(frames(100))
        val bound = sineStepBound(1000.0, ToneMixer.BEEP_AMPLITUDE.toDouble(), ToneMixer.BEEP_FADE_MS.toDouble()) +
            ToneMixer.BEEP_AMPLITUDE / frames(ToneMixer.GAIN_RAMP_MS)
        assertTrue(maxStep(fading, previous = loud.last()) <= bound)
        assertEquals(ToneMixer.BEEP_AMPLITUDE * 0.1f, peak(fading, frames(50), frames(100)), 0.005f)
    }

    @Test
    fun `nonsense input is survivable`() {
        mixer.setGain(AudioCategory.BEEP, Float.NaN)
        mixer.setGain(AudioCategory.BEEP, 9f)
        mixer.playSequence(
            AudioCategory.BEEP,
            listOf(ToneStep(1_000_000, 20), ToneStep(-5, -5, -5), ToneStep(1000, 0), ToneStep(5, 20)),
        )
        val samples = mixer.take(frames(100))
        assertTrue(samples.all { it.isFinite() && abs(it) <= 1f })
        assertTrue(mixer.isIdle)

        // A sequence far longer than allowed is cut, a far too long step is shortened.
        mixer.playSequence(AudioCategory.BEEP, List(10_000) { ToneStep(1000, 1) })
        mixer.take(frames(ToneMixer.MAX_STEPS + 20))
        assertTrue(mixer.isIdle)
        mixer.playSequence(AudioCategory.BEEP, listOf(ToneStep(1000, Int.MAX_VALUE, Int.MAX_VALUE)))
        mixer.take(frames(2 * ToneMixer.MAX_STEP_MS + 100), block = 4096)
        assertTrue(mixer.isIdle)
    }

    @Test
    fun `output never exceeds full scale`() {
        for (category in AudioCategory.entries) mixer.playSequence(category, listOf(ToneStep(700 + 13 * category.ordinal, 200)))
        mixer.setVario(VarioTone(false, 900f, 0, 0))
        val samples = mixer.take(frames(200))
        assertTrue(peak(samples) > 0.9f)
        assertTrue(peak(samples) <= 1f)
        assertEquals(0.5f, ToneMixer.softClip(0.5f), 0f)
        assertEquals(-0.5f, ToneMixer.softClip(-0.5f), 0f)
        assertTrue(ToneMixer.softClip(50f) <= 1f)
        assertTrue(ToneMixer.softClip(0.9f) < 0.9f)
        assertTrue(ToneMixer.softClip(0.9f) > 0.8f)
    }

    @Test
    fun `rendering does not depend on the block size`() {
        fun scenario(block: Int): FloatArray {
            val fresh = ToneMixer(rate)
            fresh.playSequence(AudioCategory.BEEP, BeepPatterns.steps(BeepPattern.TIMER_EXPIRED))
            fresh.setVario(VarioTone(false, 700f, 60, 40))
            return fresh.take(frames(700), block)
        }
        assertArrayEquals(scenario(1), scenario(4096), 0f)
        assertArrayEquals(scenario(37), scenario(480), 0f)
    }

    @Test
    fun `16 bit rendering matches the float rendering`() {
        val other = ToneMixer(rate)
        for (m in listOf(mixer, other)) {
            m.playSequence(AudioCategory.BEEP, BeepPatterns.steps(BeepPattern.WARNING))
            m.setVario(VarioTone(false, 500f, 0, 0))
        }
        val floats = FloatArray(4800)
        val shorts = ShortArray(4800)
        mixer.render(floats, 4800)
        other.render(shorts, 4800)
        for (i in floats.indices) assertEquals((floats[i] * Short.MAX_VALUE).toInt().toShort(), shorts[i])
    }

    // -- Vario ----------------------------------------------------------------------------------

    @Test
    fun `sink is a continuous tone at the requested pitch`() {
        mixer.setVario(VarioTone(silent = false, frequencyHz = 400f, onMs = 250, offMs = 0))
        val samples = mixer.take(frames(1000))

        assertEquals(400.0, frequency(samples, frames(100), frames(1000)), 0.5)
        assertEquals(1, bursts(samples).size)
        // Not a single period without signal after the attack.
        val period = rate / 400
        for (start in frames(10) until samples.size - period step period) {
            assertTrue(peak(samples, start, start + period) > ToneMixer.VARIO_AMPLITUDE * 0.8f)
        }
        assertTrue(peak(samples) <= ToneMixer.VARIO_AMPLITUDE + 1e-3f)
        assertFalse(mixer.isIdle)
    }

    @Test
    fun `climb is a beep train with the requested cadence`() {
        mixer.setVario(VarioTone(silent = false, frequencyHz = 1000f, onMs = 100, offMs = 100))
        val samples = mixer.take(frames(1000))

        val beeps = bursts(samples)
        assertEquals(5, beeps.size)
        assertEquals("starts with a beep", 0.0, beeps[0].first.toDouble(), frames(2).toDouble())
        for (beep in beeps) {
            assertEquals(frames(100).toDouble(), (beep.last - beep.first + 1).toDouble(), frames(6).toDouble())
            assertEquals(1000.0, frequency(samples, beep.first + frames(10), beep.last - frames(10)), 3.0)
        }
        for (i in 1 until beeps.size) {
            assertEquals(frames(200).toDouble(), (beeps[i].first - beeps[i - 1].first).toDouble(), frames(2).toDouble())
        }
    }

    @Test
    fun `faster climb means faster beeps`() {
        mixer.setVario(VarioTone(false, 1400f, 40, 40))
        assertEquals(12.5, bursts(mixer.take(frames(1000))).size.toDouble(), 0.6)
    }

    @Test
    fun `cadence changes keep the rhythm going`() {
        mixer.setVario(VarioTone(false, 1000f, 100, 100))
        val first = mixer.take(frames(50))
        // An update in the middle of a beep must not restart or cut it.
        mixer.setVario(VarioTone(false, 1010f, 90, 90))
        val second = mixer.take(frames(400))
        val all = first + second
        val beeps = bursts(all)
        assertEquals(0.0, beeps[0].first.toDouble(), frames(2).toDouble())
        assertEquals(frames(90).toDouble(), (beeps[0].last - beeps[0].first + 1).toDouble(), frames(6).toDouble())
        assertEquals(frames(180).toDouble(), (beeps[1].first - beeps[0].first).toDouble(), frames(2).toDouble())
    }

    @Test
    fun `pitch changes are phase continuous and glide`() {
        val slope = VarioVoice.WAVE_MAX_SLOPE
        mixer.setVario(VarioTone(false, 400f, 0, 0))
        val before = mixer.take(frames(200))
        mixer.setVario(VarioTone(false, 800f, 0, 0))
        val after = mixer.take(frames(500))

        val bound = sineStepBound(800.0, ToneMixer.VARIO_AMPLITUDE.toDouble(), ToneMixer.VARIO_FADE_MS.toDouble(), slope)
        assertTrue(maxStep(after, previous = before.last()) <= bound)
        // No drop-out during the change.
        assertTrue(peak(after, 0, frames(20)) > ToneMixer.VARIO_AMPLITUDE * 0.8f)
        // Glides: clearly between the two pitches shortly after, on target later.
        val early = frequency(after, 0, frames(20))
        assertTrue("early pitch $early", early > 420.0 && early < 780.0)
        assertEquals(800.0, frequency(after, frames(400), frames(500)), 1.0)
    }

    @Test
    fun `a stream of small pitch updates never clicks`() {
        val slope = VarioVoice.WAVE_MAX_SLOPE
        val bound = sineStepBound(1600.0, ToneMixer.VARIO_AMPLITUDE.toDouble(), ToneMixer.VARIO_FADE_MS.toDouble(), slope)
        var last = 0f
        // 25 updates per second, sweeping sink → climb → dead band → sink like a real flight.
        for (tick in 0 until 250) {
            val speed = 4.0 * kotlin.math.sin(tick / 20.0)
            val tone = when {
                abs(speed) < 0.3 -> VarioTone.SILENT
                speed > 0 -> VarioTone(false, (700 + 225 * speed).toFloat(), (300 - 60 * speed).toInt() / 2, (300 - 60 * speed).toInt() / 2)
                else -> VarioTone(false, (700 + 100 * speed).toFloat(), 250, 0)
            }
            mixer.setVario(tone)
            val block = mixer.take(frames(40))
            val step = maxStep(block, previous = last)
            assertTrue("tick $tick: step $step > $bound", step <= bound)
            last = block.last()
        }
    }

    @Test
    fun `the dead band is silent`() {
        mixer.setVario(VarioTone(false, 600f, 0, 0))
        val before = mixer.take(frames(100))
        mixer.setVario(VarioTone.SILENT)
        val after = mixer.take(frames(100))

        val bound = sineStepBound(600.0, ToneMixer.VARIO_AMPLITUDE.toDouble(), ToneMixer.VARIO_FADE_MS.toDouble(), VarioVoice.WAVE_MAX_SLOPE)
        assertTrue("fades instead of cutting", maxStep(after, previous = before.last()) <= bound)
        assertTrue(peak(after, 0, frames(2)) > 0.1f)
        assertEquals(0f, peak(after, frames(ToneMixer.VARIO_FADE_MS) + 2, after.size), 0f)
        assertTrue(mixer.isIdle)

        // And it comes back.
        mixer.setVario(VarioTone(false, 600f, 0, 0))
        assertTrue(peak(mixer.take(frames(50))) > ToneMixer.VARIO_AMPLITUDE * 0.9f)
    }

    @Test
    fun `invalid vario tones are silent`() {
        mixer.setVario(VarioTone(false, Float.NaN, 100, 100))
        assertEquals(0f, peak(mixer.take(frames(50))), 0f)
        mixer.setVario(VarioTone(false, 0f, 100, 100))
        assertEquals(0f, peak(mixer.take(frames(50))), 0f)
        mixer.setVario(VarioTone(false, 1000f, -5, 1))
        val samples = mixer.take(frames(50))
        assertTrue(samples.all { it.isFinite() })
    }

    @Test
    fun `vario follows its category gain`() {
        mixer.setGain(AudioCategory.VARIO, 0.25f)
        mixer.setVario(VarioTone(false, 500f, 0, 0))
        assertEquals(ToneMixer.VARIO_AMPLITUDE * 0.25f, peak(mixer.take(frames(100))), 0.01f)
    }

    @Test
    fun `stopVario and stopAll fade out quickly`() {
        mixer.setVario(VarioTone(false, 500f, 0, 0))
        mixer.playSequence(AudioCategory.ALARM, listOf(ToneStep(1000, 2000)))
        val before = mixer.take(frames(100))
        mixer.stopVario()
        val beepOnly = mixer.take(frames(100))
        assertEquals(1000.0, frequency(beepOnly, frames(20), frames(100)), 2.0)
        assertFalse(mixer.isIdle)

        mixer.stopAll()
        val after = mixer.take(frames(50))
        val bound = sineStepBound(1000.0, ToneMixer.BEEP_AMPLITUDE.toDouble(), ToneMixer.REPLACE_FADE_MS.toDouble())
        assertTrue(maxStep(after, previous = beepOnly.last()) <= bound)
        assertEquals(0f, peak(after, frames(ToneMixer.REPLACE_FADE_MS) + 2, after.size), 0f)
        assertTrue(mixer.isIdle)
        assertTrue(before.isNotEmpty())
    }

    @Test
    fun `vario waveform is bounded and crosses zero like a sine`() {
        var max = 0.0
        var maxSlope = 0.0
        val steps = 100_000
        for (i in 0 until steps) {
            val phase = 2 * PI * i / steps
            val value = VarioVoice.wave(phase)
            max = maxOf(max, abs(value))
            maxSlope = maxOf(maxSlope, abs(VarioVoice.wave(phase + 1e-6) - value) / 1e-6)
            if (i in 1 until steps / 2) assertTrue(value > 0.0)
            if (i > steps / 2) assertTrue(value < 0.0)
        }
        assertTrue(max <= 1.0)
        assertTrue(max > 0.95)
        assertTrue(maxSlope <= VarioVoice.WAVE_MAX_SLOPE + 1e-3)
    }
}
