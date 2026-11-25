package org.openax12.feature.modelsetup.logic

import kotlin.math.roundToInt
import org.openax12.core.engine.ChannelExplanation
import org.openax12.core.engine.CurveMath
import org.openax12.core.model.CURVE_MAX_POINTS
import org.openax12.core.model.CompareOp
import org.openax12.core.model.CurveConfig
import org.openax12.core.model.CurvePoint
import org.openax12.core.model.CurveType
import org.openax12.core.model.DualRateSlot
import org.openax12.core.model.ManagedItems
import org.openax12.core.model.MixMode
import org.openax12.core.model.NO_CURVE
import org.openax12.core.model.OutputChannelConfig
import org.openax12.core.model.PERCENT_MAX
import org.openax12.core.model.PERCENT_MIN
import org.openax12.core.model.SourceId
import org.openax12.core.model.SwitchCondition
import org.openax12.core.model.SwitchPosition
import org.openax12.core.model.WorkingConfig

// Pure helpers of the setup tabs: no Android, no Compose, unit-tested.

/** Who generated a managed firmware item (badge text of the Mixes / Curves / DualRate tabs). */
internal enum class ManagedKind {
    FLIGHT_MODES,
    WING,
    TEMPLATE,
    OTHER,
    ;

    companion object {
        /** The kind of [generator] (`ManagedItems` generator name), or null for a user-made item. */
        fun of(generator: String?): ManagedKind? = when {
            generator == null -> null
            generator == ManagedItems.GENERATOR_FLIGHT_MODES -> FLIGHT_MODES
            generator == ManagedItems.GENERATOR_WING -> WING
            generator.startsWith(ManagedItems.GENERATOR_TEMPLATE_PREFIX) -> TEMPLATE
            else -> OTHER
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Switch conditions
// ---------------------------------------------------------------------------------------------

/** What a [SwitchCondition] says, independent of the wording. */
internal sealed interface ConditionText {
    /** No condition. */
    data object Off : ConditionText

    /** A switch source at one of its three positions (`SA down`). */
    data class SwitchAt(val source: SourceId, val position: SwitchPosition) : ConditionText

    /** Any other comparison (`Thr > -90`). */
    data class Compare(val source: SourceId, val op: CompareOp, val value: Int) : ConditionText
}

/** Classifies the condition for display. */
internal fun SwitchCondition.describe(): ConditionText = when {
    !isEnabled -> ConditionText.Off
    source.isSwitch && op == CompareOp.EQUAL && value in SWITCH_LOGIC_VALUES ->
        ConditionText.SwitchAt(source, SwitchPosition.fromLogic(value))
    else -> ConditionText.Compare(source, op, value)
}

private val SWITCH_LOGIC_VALUES = setOf(SwitchPosition.UP.logic, SwitchPosition.MID.logic, SwitchPosition.DOWN.logic)

/** A condition with the operator [op] on [source] and a sensible default value for it. */
internal fun conditionFor(source: SourceId, op: CompareOp, previous: SwitchCondition): SwitchCondition {
    if (op == CompareOp.DISABLED) return SwitchCondition(source, CompareOp.DISABLED, 0)
    val value = when {
        previous.isEnabled && previous.source == source -> previous.value
        source.isSwitch -> SwitchPosition.DOWN.logic
        else -> 0
    }
    val snapped = if (source.isSwitch && op == CompareOp.EQUAL) SwitchPosition.fromLogic(value).logic else value
    return SwitchCondition(source, op, snapped.coerceIn(PERCENT_MIN, PERCENT_MAX))
}

// ---------------------------------------------------------------------------------------------
// Mixes
// ---------------------------------------------------------------------------------------------

/** The symbol of a mix mode in the stock list: `:=`, `+=`, `*=`. */
internal fun MixMode.symbol(): String = when (this) {
    MixMode.REPLACE -> ":="
    MixMode.ADD -> "+="
    MixMode.MULTIPLY -> "*="
}

/** One step of the "explain" line of an expanded channel. Values are rounded percent. */
internal sealed interface ExplainStep {
    /** A mix line: its contribution and the running total after combining it. */
    data class Line(val source: SourceId, val active: Boolean, val mode: MixMode, val value: Int, val total: Int) : ExplainStep

    /** The active dual-rate slot (0-based) scaled the sum. */
    data class DualRate(val slot: Int, val weight: Int, val result: Int) : ExplainStep

    /** The channel's output curve. */
    data class OutputCurve(val curve: Int, val result: Int) : ExplainStep

    /** The channel weight (shown when it is not 100 %). */
    data class Weight(val weight: Int, val result: Int) : ExplainStep

    /** Middle (subtrim) and the min / max clamp, shown when they changed the value. */
    data class Limits(val result: Int) : ExplainStep

    /** The channel lock holds the output at [value]. */
    data class Lock(val value: Int) : ExplainStep

    /** The final channel value the model arrives at. */
    data class Output(val value: Int) : ExplainStep
}

/**
 * Breaks a [ChannelExplanation] (the firmware mixer model, assumption 17) into display steps.
 * Steps that change nothing (no dual rate, no curve, weight 100 %, limits not reached) are left out.
 */
internal fun explainSteps(explanation: ChannelExplanation, channel: OutputChannelConfig?): List<ExplainStep> {
    val steps = ArrayList<ExplainStep>()
    for (c in explanation.contributions) {
        steps += ExplainStep.Line(c.mix.source, c.active, c.mix.mode, c.afterSlow.roundToInt(), c.runningTotal.roundToInt())
    }
    explanation.dualRateSlot?.let {
        steps += ExplainStep.DualRate(it, explanation.dualRateWeight, explanation.afterDualRate.roundToInt())
    }
    if (channel != null && channel.curve != NO_CURVE) {
        steps += ExplainStep.OutputCurve(channel.curve, explanation.afterCurve.roundToInt())
    }
    if (channel != null && channel.weight != 100) {
        steps += ExplainStep.Weight(channel.weight, explanation.afterWeight.roundToInt())
    }
    if (explanation.afterLimits.roundToInt() != explanation.afterWeight.roundToInt()) {
        steps += ExplainStep.Limits(explanation.afterLimits.roundToInt())
    }
    if (explanation.locked) steps += ExplainStep.Lock(explanation.output.roundToInt())
    steps += ExplainStep.Output(explanation.output.roundToInt())
    return steps
}

/**
 * Format strings of [renderExplain] (java.util.Formatter syntax, supplied from string resources).
 * `line`: symbol, source name, value; `lineOff`: source name; `dualRate`: slot number, weight,
 * result; `curve`: curve number, result; `weight`: weight, result; `limits`: result; `lock`: value;
 * `output`: value.
 */
internal class ExplainTemplates(
    val line: String,
    val lineOff: String,
    val dualRate: String,
    val curve: String,
    val weight: String,
    val limits: String,
    val lock: String,
    val output: String,
    val separator: String = "  \u203A  ",
)

/** The "explain" line of a channel as one text: lines, dual rate, curve, weight, limits, lock, result. */
internal fun renderExplain(steps: List<ExplainStep>, sourceName: (SourceId) -> String, t: ExplainTemplates): String =
    steps.joinToString(t.separator) { step ->
        when (step) {
            is ExplainStep.Line ->
                if (step.active) t.line.format(step.mode.symbol(), sourceName(step.source), step.value)
                else t.lineOff.format(sourceName(step.source))
            is ExplainStep.DualRate -> t.dualRate.format(step.slot + 1, step.weight, step.result)
            is ExplainStep.OutputCurve -> t.curve.format(step.curve + 1, step.result)
            is ExplainStep.Weight -> t.weight.format(step.weight, step.result)
            is ExplainStep.Limits -> t.limits.format(step.result)
            is ExplainStep.Lock -> t.lock.format(step.value)
            is ExplainStep.Output -> t.output.format(step.value)
        }
    }

/** The channel that carries the throttle: the first channel with a mix of the throttle stick, or null. */
internal fun findThrottleChannel(config: WorkingConfig): Int? =
    config.mixes.firstOrNull { it.source == SourceId.THR }?.channel

// ---------------------------------------------------------------------------------------------
// Dual rate
// ---------------------------------------------------------------------------------------------

/**
 * The slot that is in effect: the first enabled slot (lowest index) whose condition holds, the
 * same rule as the mixer model. [slots] must be ordered by slot (it is called from draw code, so
 * it does not sort); [logicOf] gives the live logic value of a source index.
 */
internal fun activeDualRateSlot(slots: List<DualRateSlot>, logicOf: (Int) -> Int): Int? =
    slots.firstOrNull { it.isEnabled && it.switch.test(logicOf(it.switch.source.index)) == true }?.slot

// ---------------------------------------------------------------------------------------------
// Curves
// ---------------------------------------------------------------------------------------------

/** Where a curve is used. */
internal sealed interface CurveUse {
    val channel: Int

    /** The mix line of [source] on [channel] uses the curve. */
    data class Mix(override val channel: Int, val source: SourceId) : CurveUse

    /** The channel's output curve. */
    data class Output(override val channel: Int) : CurveUse
}

/** All uses of curve [index] in [config], channels ascending. */
internal fun curveUsage(config: WorkingConfig, index: Int): List<CurveUse> {
    val uses = ArrayList<CurveUse>()
    for (line in config.mixes) if (line.curve == index) uses += CurveUse.Mix(line.channel, line.source)
    for (channel in config.channels) if (channel.curve == index) uses += CurveUse.Output(channel.channel)
    return uses.sortedBy { it.channel }
}

/**
 * The input source that moves the curve editor's marker: the first mix line that uses the curve,
 * else the first mix line of a channel whose output curve it is, else the aileron.
 */
internal fun defaultMarkerSource(config: WorkingConfig, index: Int): SourceId {
    val uses = curveUsage(config, index)
    uses.filterIsInstance<CurveUse.Mix>().firstOrNull()?.let { return it.source }
    uses.filterIsInstance<CurveUse.Output>().firstOrNull()?.let { out ->
        config.mixesOf(out.channel).firstOrNull()?.let { return it.source }
    }
    return SourceId.AIL
}

/** Editing operations on a [CurveConfig]; every result is a valid curve for [CurveMath.validate]. */
internal object CurveEdit {

    /** Smallest distance between the x values of two neighbouring points. */
    const val MIN_X_GAP: Int = 1

    /** A straight custom curve with [points] evenly spaced points (2..12). */
    fun identity(index: Int, points: Int = 3): CurveConfig {
        val n = points.coerceIn(2, CURVE_MAX_POINTS)
        return CurveConfig.custom(
            index,
            List(n) { i ->
                val v = (PERCENT_MIN + 200.0 * i / (n - 1)).roundToInt()
                CurvePoint(v, v)
            },
        )
    }

    /**
     * [curve] converted to [type]: a custom curve keeps the shape of the parametric one it
     * replaces (sampled), a parametric type starts neutral, none frees the curve.
     */
    fun withType(curve: CurveConfig, type: CurveType): CurveConfig = when {
        type == curve.type -> curve
        type == CurveType.NONE -> CurveConfig.empty(curve.index)
        type == CurveType.CUSTOM ->
            if (curve.type == CurveType.NONE) identity(curve.index) else CurveMath.toCustom(curve, 9)
        else -> CurveConfig.parametric(curve.index, type, 0)
    }

    /** The parameter of a DIFF / EXPO / FUNC curve set to [value] (clamped to −100..+100). */
    fun withParameter(curve: CurveConfig, value: Int): CurveConfig =
        CurveConfig.parametric(curve.index, curve.type, value.coerceIn(PERCENT_MIN, PERCENT_MAX))

    /**
     * Moves custom point [i] to ([x], [y]); x stays strictly between its neighbours, both
     * coordinates inside −100..+100. Anything else returns [curve] unchanged.
     */
    fun movePoint(curve: CurveConfig, i: Int, x: Int, y: Int): CurveConfig {
        if (curve.type != CurveType.CUSTOM || i !in 0 until curve.pointCount) return curve
        val points = curve.points
        val low = if (i == 0) PERCENT_MIN else points[i - 1].x + MIN_X_GAP
        val high = if (i == points.lastIndex) PERCENT_MAX else points[i + 1].x - MIN_X_GAP
        if (low > high) return curve
        val moved = points.toMutableList()
        moved[i] = CurvePoint(x.coerceIn(low, high), y.coerceIn(PERCENT_MIN, PERCENT_MAX))
        return CurveConfig.custom(curve.index, moved)
    }

    /**
     * Inserts a point after point [after] (halfway to the next one, or [after] + 20 at the end),
     * on the existing curve. Null if the curve is full or there is no room between the points.
     */
    fun addPoint(curve: CurveConfig, after: Int): CurveConfig? {
        if (curve.type != CurveType.CUSTOM || curve.pointCount >= CURVE_MAX_POINTS) return null
        val points = curve.points
        val i = after.coerceIn(0, points.lastIndex)
        val left = points[i]
        val right = points.getOrNull(i + 1)
        val x = if (right != null) (left.x + right.x) / 2 else minOf(PERCENT_MAX, left.x + 20)
        if (x <= left.x || (right != null && x >= right.x)) return null
        val y = CurveMath.interpolate(points, x.toDouble()).roundToInt().coerceIn(PERCENT_MIN, PERCENT_MAX)
        val result = points.toMutableList()
        result.add(i + 1, CurvePoint(x, y))
        return CurveConfig.custom(curve.index, result)
    }

    /** Removes point [i]; null if only two points are left (a curve needs at least two). */
    fun removePoint(curve: CurveConfig, i: Int): CurveConfig? {
        if (curve.type != CurveType.CUSTOM || curve.pointCount <= 2 || i !in 0 until curve.pointCount) return null
        return CurveConfig.custom(curve.index, curve.points.filterIndexed { index, _ -> index != i })
    }

    /** The x values and y values mapped to a plot of [size] pixels (y grows upwards in the data, downwards on screen). */
    fun toPlotX(x: Int, size: Float): Float = (x - PERCENT_MIN) / 200f * size

    /** See [toPlotX]. */
    fun toPlotY(y: Int, size: Float): Float = size - (y - PERCENT_MIN) / 200f * size

    /** Data x for a plot pixel. */
    fun fromPlotX(px: Float, size: Float): Int = (PERCENT_MIN + px / size * 200f).roundToInt().coerceIn(PERCENT_MIN, PERCENT_MAX)

    /** Data y for a plot pixel. */
    fun fromPlotY(py: Float, size: Float): Int = (PERCENT_MIN + (size - py) / size * 200f).roundToInt().coerceIn(PERCENT_MIN, PERCENT_MAX)
}
