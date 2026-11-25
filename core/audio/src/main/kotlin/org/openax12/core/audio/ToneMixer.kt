package org.openax12.core.audio

import org.openax12.core.model.AudioCategory
import org.openax12.core.model.BeepPattern
import org.openax12.core.model.ToneStep
import org.openax12.core.model.VarioTone
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tanh

private const val TWO_PI = 2.0 * Math.PI

/** Raised-cosine-like easing of a linear 0..1 ramp: no step in value or slope at either end. */
private fun smooth(level: Float): Float = level * level * (3f - 2f * level)

/** The built-in beep patterns as tone sequences. */
internal object BeepPatterns {

    /** Tone steps of [pattern]. */
    fun steps(pattern: BeepPattern): List<ToneStep> = when (pattern) {
        BeepPattern.SHORT -> listOf(ToneStep(1000, 80))
        BeepPattern.DOUBLE -> listOf(ToneStep(1000, 70, 70), ToneStep(1000, 70))
        BeepPattern.TRIPLE -> listOf(ToneStep(1000, 70, 70), ToneStep(1000, 70, 70), ToneStep(1000, 70))
        BeepPattern.LONG -> listOf(ToneStep(1000, 450))
        BeepPattern.WARNING -> listOf(ToneStep(1400, 160, 90), ToneStep(1400, 160, 90), ToneStep(1400, 160))
        BeepPattern.ERROR -> listOf(ToneStep(520, 180, 60), ToneStep(390, 380))
        BeepPattern.TRIM_STEP -> listOf(ToneStep(1200, 25))
        // Higher and clearly longer than a step so the centre is recognised without looking.
        BeepPattern.TRIM_CENTER -> listOf(ToneStep(1900, 110))
        BeepPattern.TRIM_LIMIT -> listOf(ToneStep(650, 70, 40), ToneStep(650, 70))
        BeepPattern.TIMER_TICK -> listOf(ToneStep(1500, 45))
        BeepPattern.TIMER_EXPIRED -> listOf(ToneStep(1000, 110), ToneStep(1300, 110), ToneStep(1700, 320))
        BeepPattern.CONFIRM -> listOf(ToneStep(900, 60), ToneStep(1350, 90))
    }
}

/**
 * Software synthesiser for beeps and the vario tone: one tone-sequence voice per
 * [AudioCategory] plus the vario voice, mixed to mono. Pure sample maths without Android, so it
 * is unit-tested on the JVM. Not thread-safe: the tone thread owns it.
 *
 * Every change of level is ramped (no clicks) and every change of pitch keeps the oscillator
 * phase (no jumps in the waveform).
 */
internal class ToneMixer(val sampleRate: Int) {

    init {
        require(sampleRate in 8_000..192_000) { "unsupported sample rate $sampleRate" }
    }

    private val sequences = Array(AudioCategory.entries.size) { SequenceVoice(sampleRate) }
    private val vario = VarioVoice(sampleRate)
    private var mix = FloatArray(0)

    /** True while every voice is silent and has nothing left to play. */
    val isIdle: Boolean
        get() {
            if (!vario.isIdle) return false
            for (voice in sequences) if (!voice.isIdle) return false
            return true
        }

    /** Sets the linear gain (0..1) of [category]; applied with a short ramp. */
    fun setGain(category: AudioCategory, gain: Float) {
        val clamped = if (gain.isNaN()) 0f else gain.coerceIn(0f, 1f)
        sequences[category.ordinal].gainTarget = clamped
        if (category == AudioCategory.VARIO) vario.gainTarget = clamped
    }

    /** Plays [steps] on the voice of [category]; a running sequence of that category fades out first. */
    fun playSequence(category: AudioCategory, steps: List<ToneStep>) {
        sequences[category.ordinal].play(steps)
    }

    /** Updates the vario voice (allocating it if necessary). */
    fun setVario(tone: VarioTone) = vario.set(tone)

    /** Fades the vario voice out and releases it. */
    fun stopVario() = vario.stop()

    /** Fades everything out within a few milliseconds and forgets all sequences. */
    fun stopAll() {
        for (voice in sequences) voice.stop()
        vario.stop()
    }

    /** Renders [frames] samples into [out] as floats in -1..1 (starting at index 0). */
    fun render(out: FloatArray, frames: Int) {
        java.util.Arrays.fill(out, 0, frames, 0f)
        for (voice in sequences) if (!voice.isIdle) voice.addTo(out, frames)
        if (!vario.isIdle) vario.addTo(out, frames)
        for (i in 0 until frames) out[i] = softClip(out[i])
    }

    /** Renders [frames] samples of 16-bit PCM into [out] (starting at index 0). */
    fun render(out: ShortArray, frames: Int) {
        if (mix.size < frames) mix = FloatArray(frames)
        render(mix, frames)
        for (i in 0 until frames) out[i] = (mix[i] * Short.MAX_VALUE).toInt().toShort()
    }

    companion object {
        /** Peak amplitude of a beep at gain 1. */
        const val BEEP_AMPLITUDE = 0.7f

        /** Peak amplitude of the vario tone at gain 1. */
        const val VARIO_AMPLITUDE = 0.6f

        /** Attack / release time of beeps. */
        const val BEEP_FADE_MS = 3

        /** Attack / release time of the vario tone. */
        const val VARIO_FADE_MS = 5

        /** Fade-out of a voice that is replaced or stopped. */
        const val REPLACE_FADE_MS = 2

        /** Time for a gain change to take effect. */
        const val GAIN_RAMP_MS = 10

        /** Time constant of the vario pitch glide. */
        const val VARIO_GLIDE_SECONDS = 0.03

        const val MIN_FREQUENCY_HZ = 20f
        const val MAX_FREQUENCY_HZ = 12_000f

        /** Longest single tone or pause of a sequence. */
        const val MAX_STEP_MS = 10_000

        /** Longest sequence. */
        const val MAX_STEPS = 64

        private const val CLIP_KNEE = 0.8f

        /** Linear up to the knee, then compressed smoothly towards ±1 (two loud voices at once). */
        fun softClip(x: Float): Float {
            val magnitude = abs(x)
            if (magnitude <= CLIP_KNEE) return x
            val room = 1f - CLIP_KNEE
            val shaped = CLIP_KNEE + room * tanh((magnitude - CLIP_KNEE) / room)
            return if (x < 0f) -shaped else shaped
        }

        /** Highest frequency the mixer plays at [sampleRate]. */
        fun maxFrequency(sampleRate: Int): Float = min(MAX_FREQUENCY_HZ, sampleRate * 0.45f)
    }
}

/** Plays one tone sequence; a new sequence replaces the running one after a 2 ms fade-out. */
internal class SequenceVoice(private val sampleRate: Int) {

    /** A validated sequence in frames. */
    private class Prepared(
        val count: Int,
        val increments: DoubleArray,
        val onFrames: IntArray,
        val offFrames: IntArray,
        val fadeFrames: IntArray,
        val legato: BooleanArray,
    )

    /** Gain the voice glides to. */
    var gainTarget = 1f

    private var gain = 1f
    private val gainStep = 1f / max(1, sampleRate * ToneMixer.GAIN_RAMP_MS / 1000)
    private val defaultFade = max(1, sampleRate * ToneMixer.BEEP_FADE_MS / 1000)
    private val replaceStep = 1f / max(1, sampleRate * ToneMixer.REPLACE_FADE_MS / 1000)

    private var sequence: Prepared? = null
    private var pending: Prepared? = null
    private var index = 0
    private var position = 0
    private var finishing = false
    private var phase = 0.0
    private var increment = 0.0
    private var level = 0f

    /** True when nothing sounds and nothing is waiting to start. */
    val isIdle: Boolean get() = sequence == null && pending == null

    /** Starts [steps]; an empty or all-zero sequence just stops the voice. */
    fun play(steps: List<ToneStep>) {
        pending = prepare(steps)
    }

    /** Fades out and forgets the sequence. */
    fun stop() {
        pending = if (sequence == null) null else EMPTY
    }

    /** Adds the next [frames] samples of this voice to [out]. */
    fun addTo(out: FloatArray, frames: Int) {
        for (i in 0 until frames) {
            val next = pending
            if (next != null) {
                if (sequence != null && level > 0f) {
                    // Fade the old sequence out before the new one starts.
                    level -= replaceStep
                    if (level < 0f) level = 0f
                    out[i] += sample()
                    continue
                }
                pending = null
                load(next)
            }
            val current = sequence ?: return

            val on = current.onFrames[index]
            val fade = current.fadeFrames[index]
            val target = when {
                finishing -> 0f
                position >= on -> 0f
                !current.legato[index] && position >= on - fade -> 0f
                else -> 1f
            }
            val step = 1f / fade
            if (level < target) {
                level = min(1f, level + step)
            } else if (level > target) {
                level -= step
                if (level < 1e-6f) level = 0f
            }

            if (finishing) {
                if (level == 0f) {
                    sequence = null
                    return
                }
                out[i] += sample()
                continue
            }

            if (level > 0f) out[i] += sample()

            position++
            if (position >= on + current.offFrames[index]) {
                position = 0
                index++
                if (index >= current.count) {
                    index = current.count - 1
                    finishing = true
                } else {
                    increment = current.increments[index]
                    if (level == 0f) phase = 0.0
                }
            }
        }
    }

    /** One output sample at the current phase and level; advances phase and gain. */
    private fun sample(): Float {
        if (gain != gainTarget) {
            gain = if (gain < gainTarget) min(gainTarget, gain + gainStep) else max(gainTarget, gain - gainStep)
        }
        val value = (sin(phase) * smooth(level) * gain * ToneMixer.BEEP_AMPLITUDE).toFloat()
        phase += increment
        if (phase >= TWO_PI) phase -= TWO_PI
        return value
    }

    private fun load(next: Prepared) {
        if (next.count == 0) {
            sequence = null
            return
        }
        if (sequence == null) gain = gainTarget
        sequence = next
        index = 0
        position = 0
        finishing = false
        level = 0f
        phase = 0.0
        increment = next.increments[0]
    }

    private fun prepare(steps: List<ToneStep>): Prepared {
        val limit = min(steps.size, ToneMixer.MAX_STEPS)
        val increments = DoubleArray(limit)
        val onFrames = IntArray(limit)
        val offFrames = IntArray(limit)
        val fadeFrames = IntArray(limit)
        val audible = BooleanArray(limit)
        val maxFrequency = ToneMixer.maxFrequency(sampleRate)
        var count = 0
        for (s in 0 until limit) {
            val step = steps[s]
            val durationFrames = msToFrames(step.durationMs)
            val pauseFrames = msToFrames(step.pauseMs)
            val hasTone = step.frequencyHz > 0 && durationFrames > 0
            val on = if (hasTone) durationFrames else 0
            val off = if (hasTone) pauseFrames else durationFrames + pauseFrames
            if (on + off == 0) continue
            val frequency = step.frequencyHz.toFloat().coerceIn(ToneMixer.MIN_FREQUENCY_HZ, maxFrequency)
            increments[count] = if (hasTone) TWO_PI * frequency / sampleRate else 0.0
            onFrames[count] = on
            offFrames[count] = off
            fadeFrames[count] = max(1, min(defaultFade, on / 2))
            audible[count] = hasTone
            count++
        }
        // Legato: a tone directly followed by another tone keeps sounding and only changes pitch.
        val legato = BooleanArray(count)
        for (s in 0 until count - 1) legato[s] = audible[s] && offFrames[s] == 0 && audible[s + 1]
        return Prepared(count, increments, onFrames, offFrames, fadeFrames, legato)
    }

    private fun msToFrames(ms: Int): Int =
        (ms.coerceIn(0, ToneMixer.MAX_STEP_MS).toLong() * sampleRate / 1000).toInt()

    private companion object {
        val EMPTY = Prepared(0, DoubleArray(0), IntArray(0), IntArray(0), IntArray(0), BooleanArray(0))
    }
}

/**
 * The vario tone: a continuous tone (sink) or a beep train (climb) whose pitch and cadence follow
 * the latest [VarioTone]. Pitch changes glide phase-continuously; pitch snaps only while the
 * voice is silent, so every beep starts at the pitch that is current then.
 */
internal class VarioVoice(private val sampleRate: Int) {

    /** Gain the voice glides to. */
    var gainTarget = 1f

    private var gain = 1f
    private val gainStep = 1f / max(1, sampleRate * ToneMixer.GAIN_RAMP_MS / 1000)
    private val fade = max(1, sampleRate * ToneMixer.VARIO_FADE_MS / 1000)
    private val levelStep = 1f / fade
    private val glide = 1.0 - exp(-1.0 / (ToneMixer.VARIO_GLIDE_SECONDS * sampleRate))

    private var silent = true
    private var targetFrequency = 0.0
    private var onFrames = 0
    private var offFrames = 0
    private var frequency = 0.0
    private var phase = 0.0
    private var level = 0f
    private var gateOn = false
    private var position = 0

    /** True when the voice is silent and will stay silent until the next [set]. */
    val isIdle: Boolean get() = silent && level == 0f

    /** Applies a new tone; takes effect within the glide / at the next beep boundary. */
    fun set(tone: VarioTone) {
        val requested = tone.frequencyHz
        if (tone.silent || requested.isNaN() || requested <= 0f) {
            silent = true
            return
        }
        val wasBeeping = !silent && offFrames > 0
        silent = false
        targetFrequency = requested.coerceIn(ToneMixer.MIN_FREQUENCY_HZ, ToneMixer.maxFrequency(sampleRate)).toDouble()
        if (tone.offMs > 0) {
            // A beep needs room for its attack and release.
            onFrames = max(msToFrames(tone.onMs), 2 * fade)
            offFrames = max(msToFrames(tone.offMs), 1)
            if (!wasBeeping) {
                // Start with a beep right away when lift sets in.
                gateOn = true
                position = 0
            }
        } else {
            onFrames = 0
            offFrames = 0
        }
        if (level == 0f) {
            frequency = targetFrequency
            phase = 0.0
            gain = gainTarget
        }
    }

    /** Fades out; the voice is idle afterwards. */
    fun stop() {
        silent = true
    }

    /** Adds the next [frames] samples of this voice to [out]. */
    fun addTo(out: FloatArray, frames: Int) {
        for (i in 0 until frames) {
            val target: Float
            if (silent) {
                target = 0f
            } else if (offFrames == 0) {
                target = 1f
            } else {
                if (gateOn) {
                    if (position >= onFrames) {
                        gateOn = false
                        position = 0
                    }
                } else if (position >= offFrames) {
                    gateOn = true
                    position = 0
                }
                // The release starts early enough to end with the beep.
                target = if (gateOn && position < onFrames - fade) 1f else 0f
                position++
            }

            if (level < target) {
                level = min(1f, level + levelStep)
            } else if (level > target) {
                level -= levelStep
                if (level < 1e-6f) level = 0f
            }

            if (level == 0f) {
                if (silent) return
                // Between beeps: jump to the current pitch, restart at a zero crossing.
                frequency = targetFrequency
                phase = 0.0
                gain = gainTarget
                continue
            }

            if (gain != gainTarget) {
                gain = if (gain < gainTarget) min(gainTarget, gain + gainStep) else max(gainTarget, gain - gainStep)
            }
            out[i] += (wave(phase) * smooth(level) * gain * ToneMixer.VARIO_AMPLITUDE).toFloat()
            frequency += (targetFrequency - frequency) * glide
            phase += TWO_PI * frequency / sampleRate
            if (phase >= TWO_PI) phase -= TWO_PI
        }
    }

    private fun msToFrames(ms: Int): Int = (ms.coerceIn(0, ToneMixer.MAX_STEP_MS).toLong() * sampleRate / 1000).toInt()

    companion object {
        /** Peak of the unnormalised waveform (fundamental + 0.3 × 2nd + 0.15 × 3rd harmonic). */
        private const val WAVE_PEAK = 1.15

        /** Steepest slope of [wave] per radian; bounds the sample-to-sample difference. */
        const val WAVE_MAX_SLOPE = (1.0 + 0.6 + 0.45) / WAVE_PEAK

        /**
         * A sine with a little 2nd and 3rd harmonic: small speakers reproduce the low sink
         * pitches poorly and the harmonics keep them audible. Peak ≤ 1, zero crossings as a sine.
         */
        fun wave(phase: Double): Double =
            (sin(phase) + 0.3 * sin(2.0 * phase) + 0.15 * sin(3.0 * phase)) / WAVE_PEAK
    }
}
