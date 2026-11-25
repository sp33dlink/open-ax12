package org.openax12.core.engine

import org.openax12.core.model.CHANNEL_COUNT
import org.openax12.core.model.CurveConfig
import org.openax12.core.model.DualRateSlot
import org.openax12.core.model.LiveSnapshot
import org.openax12.core.model.MixLine
import org.openax12.core.model.MixMode
import org.openax12.core.model.NO_CURVE
import org.openax12.core.model.OutputChannelConfig
import org.openax12.core.model.SOURCE_COUNT
import org.openax12.core.model.SwitchCondition
import org.openax12.core.model.WorkingConfig
import kotlin.math.max
import kotlin.math.min

/**
 * Per-channel state the mixer carries between steps (the "slow" ramps of mix lines). One instance
 * per simulated radio. Not thread-safe.
 */
class MixerState {

    /** Current value of every mix line that has a slow time, keyed by `(channel, source)`. */
    private val slow = HashMap<Int, Double>()

    /** Forgets all slow ramps: the next step jumps straight to the target values. */
    fun reset(): Unit = slow.clear()

    /** Forgets the ramp of one mix line (it was switched off or deleted). */
    internal fun forget(channel: Int, source: Int) {
        slow.remove(key(channel, source))
    }

    /**
     * Moves the line's value towards [target] at the configured rate ([upMs] / [downMs] = time
     * for a full −100 → +100 sweep, 0 = instant) and returns the new value. The first step of a
     * line has no previous value and jumps to the target.
     */
    internal fun ramp(channel: Int, source: Int, target: Double, upMs: Int, downMs: Int, dtMs: Long): Double {
        val key = key(channel, source)
        if (upMs <= 0 && downMs <= 0) {
            slow.remove(key)
            return target
        }
        val previous = slow[key]
        val dt = dtMs.coerceAtLeast(0L)
        val value = when {
            previous == null -> target
            target > previous -> if (upMs <= 0) target else min(target, previous + FULL_SWEEP * dt / upMs)
            target < previous -> if (downMs <= 0) target else max(target, previous - FULL_SWEEP * dt / downMs)
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
 * How one mix line contributed to a channel in one mixer step.
 *
 * @property active false if the line's switch condition is off (it then contributes nothing)
 * @property input logic value of the line's source in percent
 * @property afterCurve [input] after the line's curve
 * @property afterWeight after applying weight up / down and the offset
 * @property afterSlow after the slow ramp (equals [afterWeight] when no slow is set or no state was passed)
 * @property runningTotal channel value after combining this line (replace / add / multiply) with the lines before it
 */
data class MixContribution(
    val mix: MixLine,
    val active: Boolean,
    val input: Double,
    val afterCurve: Double,
    val afterWeight: Double,
    val afterSlow: Double,
    val runningTotal: Double,
)

/**
 * Step-by-step account of how a channel value is composed — the data behind the live "explain"
 * line of the Mixes tab.
 *
 * @property contributions the channel's mix lines in evaluation order
 * @property mixSum result of the mix lines (0 if the channel has none)
 * @property dualRateSlot the dual-rate slot in effect (0..2), or null if none is active
 * @property dualRateWeight weight of that slot in percent (100 if none)
 * @property afterDualRate [mixSum] scaled by the active dual rate
 * @property afterCurve after the channel's output curve
 * @property afterWeight after the channel weight (negative = reversed)
 * @property afterLimits after adding the middle (subtrim) and clamping to min / max
 * @property locked true if the channel lock is active
 * @property output final value: the lock value when [locked], else [afterLimits]
 */
data class ChannelExplanation(
    val channel: Int,
    val contributions: List<MixContribution>,
    val mixSum: Double,
    val dualRateSlot: Int?,
    val dualRateWeight: Int,
    val afterDualRate: Double,
    val afterCurve: Double,
    val afterWeight: Double,
    val afterLimits: Double,
    val locked: Boolean,
    val output: Double,
)

/**
 * Reference implementation of the firmware mixer: how sources become channel outputs through
 * mixes, curves, dual rates, limits and channel lock. Used by the simulator (`FakeRadio`) to
 * produce channel outputs and by the Mixes tab to explain a channel. It is the app's model of the
 * firmware, not the firmware itself; on the real radio the true outputs come from `Radio.live`.
 *
 * Per channel, in this order:
 * 1. Mix lines in `mixIndex` order. A line is active if its switch condition holds (disabled
 *    condition = always). Value of a line = `curve(source logic)` × weight (weightUp when the
 *    curve output is ≥ 0, weightDown otherwise) / 100 + offset, then slowed towards that target
 *    with `slowUpMs` / `slowDownMs` (time for a full −100 → +100 sweep; 0 = instant). Lines
 *    combine by mode: REPLACE sets the running total, ADD adds, MULTIPLY multiplies
 *    `total × value / 100`. Inactive lines are skipped.
 * 2. Dual rate: the first enabled slot (lowest slot index) whose switch condition holds scales
 *    the total by its weight.
 * 3. Channel curve, then channel weight (negative weight reverses).
 * 4. `+ middle`, clamped to `[min, max]`.
 * 5. Channel lock: while the lock condition holds the output is `lockValue`.
 *
 * Inputs are the sources' logic values in percent; outputs are percent, rounded only by callers.
 */
object MixerMath {

    /**
     * Computes all channel outputs for the given source logic values.
     *
     * @param sourceLogic logic value per source index (size `SOURCE_COUNT`; missing entries count as 0)
     * @param state slow-ramp state, updated in place; null = no slow (targets are reached at once)
     * @param dtMs time since the previous step with the same [state], in ms
     * @return one output per channel index (size `CHANNEL_COUNT`)
     */
    fun computeOutputs(
        config: WorkingConfig,
        sourceLogic: IntArray,
        state: MixerState? = null,
        dtMs: Long = 0,
    ): DoubleArray {
        val out = DoubleArray(CHANNEL_COUNT)
        if (config.channels.isEmpty()) return out
        val tables = Tables(config)
        for (channel in config.channels) {
            val index = channel.channel
            if (index !in 0 until CHANNEL_COUNT) continue
            out[index] = computeChannel(tables, channel, sourceLogic, state, dtMs, null).output
        }
        return out
    }

    /**
     * Explains channel [channel] for the given inputs without touching any slow state (slow
     * ramps are reported as already settled). Returns null for an invalid channel.
     */
    fun explain(config: WorkingConfig, sourceLogic: IntArray, channel: Int): ChannelExplanation? {
        if (channel !in 0 until CHANNEL_COUNT) return null
        val cfg = config.channel(channel) ?: return null
        return computeChannel(Tables(config), cfg, sourceLogic, null, 0, ArrayList())
    }

    /** [explain] with the inputs taken from a live frame. */
    fun explain(config: WorkingConfig, live: LiveSnapshot, channel: Int): ChannelExplanation? =
        explain(config, sourceLogic(live), channel)

    /** The source logic values of a live frame as the array [computeOutputs] expects (size `SOURCE_COUNT`). */
    fun sourceLogic(live: LiveSnapshot): IntArray = IntArray(SOURCE_COUNT) { live.logicAt(it) }

    /**
     * Output of one mix line for the given inputs, without slow: `curve(source) × weight / 100 +
     * offset`. Used by the compilers' tests and by editors that preview a single line.
     */
    fun lineValue(config: WorkingConfig, line: MixLine, sourceLogic: IntArray): Double {
        val input = sourceLogic.getOrElse(line.source.index) { 0 }.toDouble()
        val curved = applyCurve(curveOf(config, line.curve), input)
        return weighted(line, curved)
    }

    // ---- implementation ------------------------------------------------------------------

    /** Lookup tables built once per call, so that 16 channels do not each scan all lists. */
    private class Tables(val config: WorkingConfig) {
        val mixes: Map<Int, List<MixLine>> = config.mixes.groupBy { it.channel }.mapValues { (_, lines) -> lines.sortedBy { it.mixIndex } }
        val dualRates: Map<Int, List<DualRateSlot>> =
            config.dualRates.groupBy { it.channel }.mapValues { (_, slots) -> slots.sortedBy { it.slot } }
        val curves: Map<Int, CurveConfig> = config.curves.associateBy { it.index }

        fun curve(index: Int): CurveConfig? = if (index == NO_CURVE) null else curves[index]
    }

    private fun computeChannel(
        tables: Tables,
        cfg: OutputChannelConfig,
        logic: IntArray,
        state: MixerState?,
        dtMs: Long,
        contributions: MutableList<MixContribution>?,
    ): ChannelExplanation {
        val channel = cfg.channel
        var total = 0.0
        for (line in tables.mixes[channel].orEmpty()) {
            val source = line.source.index
            val input = logic.getOrElse(source) { 0 }.toDouble()
            val curved = applyCurve(tables.curve(line.curve), input)
            val target = weighted(line, curved)
            if (!isActive(line.switch, logic)) {
                state?.forget(channel, source)
                contributions?.add(MixContribution(line, false, input, curved, target, target, total))
                continue
            }
            val value = state?.ramp(channel, source, target, line.slowUpMs, line.slowDownMs, dtMs) ?: target
            total = when (line.mode) {
                MixMode.REPLACE -> value
                MixMode.ADD -> total + value
                MixMode.MULTIPLY -> total * value / 100.0
            }
            contributions?.add(MixContribution(line, true, input, curved, target, value, total))
        }
        val mixSum = total

        val rate = tables.dualRates[channel].orEmpty()
            .firstOrNull { it.isEnabled && it.switch.test(logic.getOrElse(it.switch.source.index) { 0 }) == true }
        val rateWeight = rate?.weight ?: 100
        val afterDualRate = if (rate != null) mixSum * rateWeight / 100.0 else mixSum

        val afterCurve = applyCurve(tables.curve(cfg.curve), afterDualRate)
        val afterWeight = afterCurve * cfg.weight / 100.0

        val low = min(cfg.min, cfg.max).toDouble()
        val high = max(cfg.min, cfg.max).toDouble()
        val afterLimits = (afterWeight + cfg.middle).coerceIn(low, high)

        val locked = cfg.lock.isEnabled && cfg.lock.test(logic.getOrElse(cfg.lock.source.index) { 0 }) == true
        return ChannelExplanation(
            channel = channel,
            contributions = contributions.orEmpty(),
            mixSum = mixSum,
            dualRateSlot = rate?.slot,
            dualRateWeight = rateWeight,
            afterDualRate = afterDualRate,
            afterCurve = afterCurve,
            afterWeight = afterWeight,
            afterLimits = afterLimits,
            locked = locked,
            output = if (locked) cfg.lockValue.toDouble() else afterLimits,
        )
    }

    /** `curve output × weight / 100 + offset`; weightUp for a curve output ≥ 0, weightDown below. */
    private fun weighted(line: MixLine, curved: Double): Double {
        val weight = if (curved >= 0) line.weightUp else line.weightDown
        return curved * weight / 100.0 + line.offset
    }

    /** No curve (or a missing one) passes the value through unchanged, without clamping. */
    private fun applyCurve(curve: CurveConfig?, x: Double): Double = if (curve == null) x else CurveMath.apply(curve, x)

    private fun curveOf(config: WorkingConfig, index: Int): CurveConfig? = if (index == NO_CURVE) null else config.curve(index)

    /** A disabled condition never gates: the owning line is always active. */
    private fun isActive(condition: SwitchCondition, logic: IntArray): Boolean =
        condition.test(logic.getOrElse(condition.source.index) { 0 }) ?: true
}
