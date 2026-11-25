package org.openax12.core.engine

import org.junit.Assert.assertTrue
import org.openax12.core.model.CHANNEL_COUNT
import org.openax12.core.model.COUNTER_COUNT
import org.openax12.core.model.CURVE_COUNT
import org.openax12.core.model.CurveConfig
import org.openax12.core.model.DR_SLOTS
import org.openax12.core.model.DualRateSlot
import org.openax12.core.model.LiveSnapshot
import org.openax12.core.model.LogicalSwitchStates
import org.openax12.core.model.ManagedItems
import org.openax12.core.model.MixLine
import org.openax12.core.model.MixPlan
import org.openax12.core.model.ModelExtras
import org.openax12.core.model.OutputChannelConfig
import org.openax12.core.model.PlanDiff
import org.openax12.core.model.RadioStatus
import org.openax12.core.model.SOURCE_COUNT
import org.openax12.core.model.SensorId
import org.openax12.core.model.SensorInfo
import org.openax12.core.model.SensorUnit
import org.openax12.core.model.SensorValue
import org.openax12.core.model.SourceId
import org.openax12.core.model.SourceInfo
import org.openax12.core.model.TIMER_COUNT
import org.openax12.core.model.TimerConfig
import org.openax12.core.model.TimerState
import org.openax12.core.model.WorkingConfig

/** Tolerance for comparing channel values. */
internal const val EPS = 1e-6

/** A freshly reset model: all sources enabled, 16 neutral channels, no mixes, free curves and dual rates. */
internal fun neutralConfig(revision: Long = 1): WorkingConfig = WorkingConfig(
    revision = revision,
    sources = SourceId.all.map { SourceInfo(index = it.index) },
    channels = List(CHANNEL_COUNT) { OutputChannelConfig(channel = it) },
    curves = List(CURVE_COUNT) { CurveConfig.empty(it) },
    dualRates = List(CHANNEL_COUNT * DR_SLOTS) { DualRateSlot(channel = it / DR_SLOTS, slot = it % DR_SLOTS) },
    timers = List(TIMER_COUNT) { TimerConfig(index = it) },
    counters = List(COUNTER_COUNT) { TimerConfig(index = it, startTime = 10) },
)

/** Source logic array with the given values, everything else 0. */
internal fun logic(vararg values: Pair<SourceId, Int>): IntArray =
    IntArray(SOURCE_COUNT).also { array -> for ((source, value) in values) array[source.index] = value }

/** Copy with a user-made mix line appended to its channel. */
internal fun WorkingConfig.withMix(line: MixLine): WorkingConfig {
    val index = mixes.count { it.channel == line.channel }
    return copy(mixes = mixes + line.copy(mixIndex = index))
}

/** Copy with one curve replaced. */
internal fun WorkingConfig.withCurve(curve: CurveConfig): WorkingConfig =
    copy(curves = curves.map { if (it.index == curve.index) curve else it })

/** Copy with one dual-rate slot replaced. */
internal fun WorkingConfig.withDualRate(slot: DualRateSlot): WorkingConfig =
    copy(dualRates = dualRates.map { if (it.channel == slot.channel && it.slot == slot.slot) slot else it })

/** Copy with one output channel replaced. */
internal fun WorkingConfig.withChannel(channel: OutputChannelConfig): WorkingConfig =
    copy(channels = channels.map { if (it.channel == channel.channel) channel else it })

/** Result of applying a plan in a test. */
internal class Applied(val config: WorkingConfig, val managed: ManagedItems, val diff: PlanDiff)

/** Diffs [plan], requires that it can be applied, and returns the predicted configuration. */
internal fun WorkingConfig.applyPlan(plan: MixPlan, managed: ManagedItems = ManagedItems.NONE): Applied {
    val diff = PlanDiffer.diff(plan, this, managed)
    assertTrue("blocking conflicts: ${diff.conflicts.filter { it.isBlocking }}", diff.canApply)
    return Applied(PlanDiffer.preview(diff, this), diff.managedAfter, diff)
}

/** Channel outputs (no slow) for the given stick / switch positions. */
internal fun WorkingConfig.outputs(vararg values: Pair<SourceId, Int>): DoubleArray = MixerMath.computeOutputs(this, logic(*values))

/** A live frame for evaluator tests. */
internal fun frame(
    logic: Map<SourceId, Int> = emptyMap(),
    channels: List<Int> = emptyList(),
    linkQuality: Int = 100,
    timers: List<TimerState> = LiveSnapshot.DEFAULT_TIMERS,
    timestampMs: Long = 0,
    txVoltage: Float = 8.0f,
): LiveSnapshot = LiveSnapshot.of(
    timestampMs = timestampMs,
    logic = logic,
    channels = channels,
    status = RadioStatus(linkQuality = linkQuality, txVoltage = txVoltage),
    timers = timers,
)

/** An engine tick for evaluator tests; the link is up unless [linkQuality] is 0. */
internal fun tick(
    nowMs: Long,
    logic: Map<SourceId, Int> = emptyMap(),
    sensors: Map<SensorId, SensorValue> = emptyMap(),
    channels: List<Int> = emptyList(),
    flightMode: Int = 0,
    flightModeName: String = "",
    switches: LogicalSwitchStates = LogicalSwitchStates.NONE,
    events: List<EngineEvent> = emptyList(),
    startup: Boolean = false,
    linkQuality: Int = 100,
    timers: List<TimerState> = LiveSnapshot.DEFAULT_TIMERS,
    modelName: String = "Test model",
): EngineTick = EngineTick(
    nowMs = nowMs,
    live = frame(logic, channels, linkQuality, timers, nowMs),
    sensors = sensors,
    flightMode = flightMode,
    flightModeName = flightModeName,
    logicalSwitches = switches,
    modelName = modelName,
    events = events,
    startup = startup,
)

/** The production resolver over [tick]. */
internal fun resolverOf(
    tick: EngineTick,
    extras: ModelExtras = ModelExtras(),
    info: List<SensorInfo> = emptyList(),
): ValueResolver = EngineValueResolver(tick, extras, info)

/** A numeric sensor value. */
internal fun reading(value: Double, unit: SensorUnit = SensorUnit.NONE, precision: Int = 0, lost: Boolean = false): SensorValue =
    SensorValue(value = value, unit = unit, precision = precision, lost = lost)
