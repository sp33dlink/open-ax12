package org.openax12.core.radio.fake

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import org.openax12.core.model.CHANNEL_COUNT
import org.openax12.core.model.CURVE_MAX_POINTS
import org.openax12.core.model.CurveConfig
import org.openax12.core.model.CurveType
import org.openax12.core.model.MixMode
import org.openax12.core.model.NO_CURVE
import org.openax12.core.model.SwitchCondition

/**
 * The simulator's model of the firmware curves. `:core:radio` must not depend on `:core:engine`,
 * so this repeats the rules documented on `CurveMath` there (assumption 12); the two must stay in
 * line:
 *
 * - inputs are clamped to −100..+100 first;
 * - `NONE`: identity;
 * - `DIFF` (d = `y[0]`): d > 0 scales the negative half by (100 − d) %, d < 0 the positive half
 *   by (100 + d) %;
 * - `EXPO` (k = `y[0]`): `y = x·(1 − k') + x³·k'` on the normalized input for k ≥ 0; for k < 0 the
 *   mirrored shape `y = x·(1 − k') + (1 − (1 − x)³)·k'` with k' = |k| / 100 (more sensitive
 *   around centre; applied to |x|, the sign is restored);
 * - `FUNC` (number = `y[0]`): 0 `x>0`, 1 `x<0`, 2 `|x|`, 3 `f>0`, 4 `f<0`, 5 `|f|`, others identity;
 * - `CUSTOM`: linear interpolation, flat outside the first / last point.
 *
 * Invalid curves evaluate as identity.
 */
internal object SimCurves {

    /**
     * Output of [curve] for input [x] in percent. No curve (null) passes the value through
     * unchanged, without clamping; an invalid curve is the identity on the clamped input.
     */
    fun apply(curve: CurveConfig?, x: Double): Double {
        if (curve == null) return x
        if (x.isNaN()) return 0.0
        val input = x.coerceIn(-100.0, 100.0)
        return when (curve.type) {
            CurveType.NONE -> input
            CurveType.DIFF -> diff(curve.parameter.coerceIn(-100, 100), input)
            CurveType.EXPO -> expo(curve.parameter.coerceIn(-100, 100), input)
            CurveType.FUNC -> function(curve.parameter, input)
            CurveType.CUSTOM -> custom(curve, input)
        }
    }

    /**
     * True if the firmware would accept [curve] (`rcSetCurveCfg` returning 0): at most
     * [CURVE_MAX_POINTS] points and, for a custom curve, at least two points with strictly
     * ascending x and all coordinates within ±100.
     */
    fun isAcceptable(curve: CurveConfig): Boolean {
        if (curve.pointCount !in 0..CURVE_MAX_POINTS) return false
        if (curve.type != CurveType.CUSTOM) return true
        return isUsableCustom(curve)
    }

    private fun isUsableCustom(curve: CurveConfig): Boolean {
        val count = curve.pointCount
        if (count !in 2..CURVE_MAX_POINTS || curve.x.size < count || curve.y.size < count) return false
        for (i in 0 until count) {
            if (curve.x[i] !in -100..100 || curve.y[i] !in -100..100) return false
            if (i > 0 && curve.x[i] <= curve.x[i - 1]) return false
        }
        return true
    }

    private fun diff(d: Int, x: Double): Double = when {
        d > 0 && x < 0 -> x * (100 - d) / 100.0
        d < 0 && x > 0 -> x * (100 + d) / 100.0
        else -> x
    }

    private fun expo(k: Int, x: Double): Double {
        val n = x / 100.0
        val a = abs(n)
        val f = abs(k) / 100.0
        val shaped = if (k >= 0) {
            a * (1 - f) + a * a * a * f
        } else {
            // Mirror image of the positive shape: steep around the centre, flat towards the ends.
            val inverse = 1 - a
            a * (1 - f) + (1 - inverse * inverse * inverse) * f
        }
        return (if (n < 0) -shaped else shaped) * 100.0
    }

    private fun function(number: Int, x: Double): Double = when (number) {
        0 -> if (x > 0) x else 0.0
        1 -> if (x < 0) x else 0.0
        2 -> abs(x)
        3 -> if (x > 0) 100.0 else 0.0
        4 -> if (x < 0) -100.0 else 0.0
        5 -> if (x > 0) 100.0 else if (x < 0) -100.0 else 0.0
        else -> x
    }

    private fun custom(curve: CurveConfig, x: Double): Double {
        if (!isUsableCustom(curve)) return x
        val count = curve.pointCount
        if (x <= curve.x[0]) return curve.y[0].toDouble()
        if (x >= curve.x[count - 1]) return curve.y[count - 1].toDouble()
        for (i in 1 until count) {
            val x1 = curve.x[i]
            if (x <= x1) {
                val x0 = curve.x[i - 1]
                val y0 = curve.y[i - 1]
                val y1 = curve.y[i]
                return y0 + (y1 - y0) * (x - x0) / (x1 - x0).toDouble()
            }
        }
        return curve.y[count - 1].toDouble()
    }
}

/**
 * Slow-ramp state of the simulated mixer: the current value of every mix line that has a slow
 * time, keyed by `(channel, source)`. Not thread-safe.
 */
internal class SimMixerState {

    private val slow = HashMap<Int, Double>()

    /** Forgets all ramps: the next step jumps straight to the target values. */
    fun reset() = slow.clear()

    /** Forgets the ramp of one mix line. */
    fun forget(channel: Int, source: Int) {
        slow.remove(key(channel, source))
    }

    /**
     * Moves the line's value towards [target] at the configured rate ([upMs] / [downMs] = time
     * for a full −100 → +100 sweep, 0 = instant) and returns the new value. The first step of a
     * line has no previous value and jumps to the target.
     */
    fun ramp(channel: Int, source: Int, target: Double, upMs: Int, downMs: Int, dtMs: Long): Double {
        val key = key(channel, source)
        if (upMs <= 0 && downMs <= 0) {
            slow.remove(key)
            return target
        }
        val previous = slow[key]
        val value = when {
            previous == null -> target
            target > previous -> if (upMs <= 0) target else min(target, previous + FULL_SWEEP * dtMs / upMs)
            target < previous -> if (downMs <= 0) target else max(target, previous - FULL_SWEEP * dtMs / downMs)
            else -> target
        }
        slow[key] = value
        return value
    }

    private fun key(channel: Int, source: Int): Int = channel * 256 + source

    private companion object {
        /** A slow time is the duration of a sweep over the whole range, −100 → +100. */
        const val FULL_SWEEP = 200.0
    }
}

/**
 * The simulator's model of the firmware mixer. It follows the rules documented on `MixerMath` in
 * `:core:engine` (assumption 17), which `:core:radio` cannot depend on. Per channel, in this order:
 *
 * 1. Mix lines in order. A line is active while its switch condition holds (a disabled condition
 *    is always active). Its value is `curve(source logic)` × weight / 100 (weightUp when the
 *    curve output is ≥ 0, weightDown otherwise) + offset, then slowed towards that target with
 *    `slowUpMs` / `slowDownMs`. Lines combine by mode: REPLACE sets the running total, ADD adds,
 *    MULTIPLY gives `total × value / 100`. Inactive lines are skipped (and lose their ramp).
 * 2. Dual rate: the first enabled slot (lowest index) whose condition holds scales the total.
 * 3. Channel curve, then channel weight (negative = reversed).
 * 4. `+ middle`, clamped to `[min, max]`.
 * 5. Channel lock: while the lock condition holds the output is `lockValue`.
 *
 * Trims are not part of the documented mixer and are therefore not mixed in.
 */
internal object SimMixer {

    /**
     * Computes all channel outputs for the given source logic values.
     *
     * @param logic logic value per source index in percent; missing entries count as 0
     * @param state slow-ramp state, updated in place; null = no slow
     * @param dtMs time since the previous step with the same [state]
     * @return one unrounded output per channel of [config]
     */
    fun compute(config: SimConfig, logic: IntArray, state: SimMixerState? = null, dtMs: Long = 0): DoubleArray {
        val out = DoubleArray(max(config.channels.size, CHANNEL_COUNT))
        for (channel in config.channels.indices) {
            out[channel] = computeChannel(config, channel, logic, state, dtMs)
        }
        return out
    }

    /** Output of one channel, see [compute]. */
    fun computeChannel(config: SimConfig, channel: Int, logic: IntArray, state: SimMixerState?, dtMs: Long): Double {
        val cfg = config.channels.getOrNull(channel) ?: return 0.0

        var total = 0.0
        for (line in config.mixes.getOrNull(channel).orEmpty()) {
            val source = line.source.index
            if (!isActive(line.switch, logic)) {
                state?.forget(channel, source)
                continue
            }
            val curved = SimCurves.apply(curveOf(config, line.curve), logic.getOrElse(source) { 0 }.toDouble())
            val weight = if (curved >= 0) line.weightUp else line.weightDown
            val target = curved * weight / 100.0 + line.offset
            val value = state?.ramp(channel, source, target, line.slowUpMs, line.slowDownMs, dtMs) ?: target
            total = when (line.mode) {
                MixMode.REPLACE -> value
                MixMode.ADD -> total + value
                MixMode.MULTIPLY -> total * value / 100.0
            }
        }

        val rate = config.dualRates.getOrNull(channel).orEmpty()
            .firstOrNull { it.isEnabled && it.switch.test(logic.getOrElse(it.switch.source.index) { 0 }) == true }
        if (rate != null) total = total * rate.weight / 100.0

        val channelCurve = curveOf(config, cfg.curve)
        if (channelCurve != null) total = SimCurves.apply(channelCurve, total)
        total = total * cfg.weight / 100.0

        val low = min(cfg.min, cfg.max).toDouble()
        val high = max(cfg.min, cfg.max).toDouble()
        total = (total + cfg.middle).coerceIn(low, high)

        if (cfg.lock.isEnabled && cfg.lock.test(logic.getOrElse(cfg.lock.source.index) { 0 }) == true) {
            return cfg.lockValue.toDouble()
        }
        return total
    }

    /** A disabled condition never gates: the owning line is always active. */
    private fun isActive(condition: SwitchCondition, logic: IntArray): Boolean =
        condition.test(logic.getOrElse(condition.source.index) { 0 }) ?: true

    private fun curveOf(config: SimConfig, index: Int): CurveConfig? =
        if (index == NO_CURVE) null else config.curves.getOrNull(index)
}
