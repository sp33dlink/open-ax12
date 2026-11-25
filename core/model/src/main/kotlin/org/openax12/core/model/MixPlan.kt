package org.openax12.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// A MixPlan is the declarative output of the compilers and model templates: "these mixes, curves,
// dual rates and channel settings should exist". PlanDiffer (:core:engine) turns a plan plus the
// current working config into a PlanDiff (concrete operations + conflicts), which
// ConfigSession.applyPlan (:core:runtime) executes against the radio.

/** Reference from a planned mix / channel to a curve. JSON class discriminator `"type"`. */
@Serializable
sealed interface CurveRef {

    /** No curve ([NO_CURVE]). */
    @Serializable
    @SerialName("none")
    data object None : CurveRef

    /** An existing curve of the model that the plan uses but does not own. */
    @Serializable
    @SerialName("existing")
    data class Existing(val index: Int) : CurveRef

    /** A curve defined by the plan itself, see [PlannedCurve.key]. The differ allocates its index. */
    @Serializable
    @SerialName("planned")
    data class Planned(val key: String) : CurveRef
}

/**
 * A curve the plan needs. The differ allocates a free curve index (or reuses a managed curve of
 * the same generator, or shares one with an identical shape).
 *
 * @property key name unique within the plan, referenced by [CurveRef.Planned]
 * @property points points of a [CurveType.CUSTOM] curve (2..[CURVE_MAX_POINTS], x ascending)
 * @property parameter parameter of a DIFF / EXPO / FUNC curve
 */
@Serializable
data class PlannedCurve(
    val key: String,
    val type: CurveType = CurveType.CUSTOM,
    val points: List<CurvePoint> = emptyList(),
    val parameter: Int = 0,
    val comment: String = "",
) {
    /** The curve as it would be written at [index]. */
    fun toConfig(index: Int): CurveConfig = when (type) {
        CurveType.CUSTOM -> CurveConfig.custom(index, points)
        CurveType.NONE -> CurveConfig.empty(index)
        else -> CurveConfig.parametric(index, type, parameter)
    }
}

/** A mix line the plan wants on [channel] from [source] (identity = `(channel, source)`). */
@Serializable
data class PlannedMix(
    val channel: Int,
    val source: SourceId,
    val mode: MixMode = MixMode.ADD,
    val weightUp: Int = 100,
    val weightDown: Int = 100,
    val offset: Int = 0,
    val curve: CurveRef = CurveRef.None,
    val switch: SwitchCondition = SwitchCondition.NONE,
    val slowUpMs: Int = 0,
    val slowDownMs: Int = 0,
    val comment: String = "",
) {
    /** Identity of the planned mix. */
    val key: MixKey get() = MixKey(channel, source.index)
}

/** A dual rate the plan wants on [channel]; the differ allocates the slot. */
@Serializable
data class PlannedDualRate(
    val channel: Int,
    val switch: SwitchCondition,
    val weight: Int,
    val comment: String = "",
)

/**
 * Output-channel settings the plan wants. Null properties are left as they are. Channel settings
 * are not tracked as managed items: they are set once and belong to the user afterwards.
 */
@Serializable
data class PlannedChannel(
    val channel: Int,
    val min: Int? = null,
    val max: Int? = null,
    val middle: Int? = null,
    val weight: Int? = null,
    val curve: CurveRef? = null,
    val lock: SwitchCondition? = null,
    val lockValue: Int? = null,
    val comment: String = "",
)

/**
 * Declarative result of a compiler or template.
 *
 * @property generator who produced the plan ([ManagedItems.GENERATOR_FLIGHT_MODES],
 *   [ManagedItems.GENERATOR_WING], `"template:<id>"`). Applying the plan first removes every
 *   managed item of the same generator that the plan no longer contains.
 * @property replaceAllMixes true (model templates on a new model): every existing mix line that
 *   is not part of the plan is removed, user-made or not
 * @property resetConfig true (first plan applied to a newly created model, see [ModelBlueprint]):
 *   the model was created as a copy of another model's configuration, so everything the plan does
 *   not set itself is neutralised — implies [replaceAllMixes]; every curve the plan does not
 *   define becomes `NONE`; every dual-rate slot the plan does not use is disabled; every output
 *   channel not listed in [channels] gets min −100 / max +100 / middle 0 / weight +100 / no curve /
 *   no lock (for listed channels the null properties get these defaults); every timer and counter
 *   not listed in [timers] / [counters] gets its run and reset conditions disabled and its alert
 *   silenced (all other fields stay as read). Recorded managed items are ignored and replaced.
 * @property timers timer configurations to write (templates only). Only the configuration fields
 *   matter: `Radio.setTimerConfig` never writes the read-back fields, so objects built from
 *   scratch are safe.
 * @property counters counter configurations to write (templates only), like [timers]
 */
@Serializable
data class MixPlan(
    val generator: String,
    val mixes: List<PlannedMix> = emptyList(),
    val curves: List<PlannedCurve> = emptyList(),
    val dualRates: List<PlannedDualRate> = emptyList(),
    val channels: List<PlannedChannel> = emptyList(),
    val timers: List<TimerConfig> = emptyList(),
    val replaceAllMixes: Boolean = false,
    val description: String = "",
    val resetConfig: Boolean = false,
    val counters: List<TimerConfig> = emptyList(),
) {
    /**
     * True if the plan asks for nothing. Applying an empty plan is still meaningful: it removes
     * the generator's managed items (and with [resetConfig] / [replaceAllMixes] a lot more).
     */
    val isEmpty: Boolean
        get() = mixes.isEmpty() && curves.isEmpty() && dualRates.isEmpty() && channels.isEmpty() &&
            timers.isEmpty() && counters.isEmpty()

    /** The planned curve named [key], or null. */
    fun curve(key: String): PlannedCurve? = curves.firstOrNull { it.key == key }

    companion object {
        /** An empty plan for [generator]; applying it removes all managed items of that generator. */
        fun empty(generator: String): MixPlan = MixPlan(generator)
    }
}

/** Why a plan cannot be applied as intended. */
enum class PlanConflictKind {
    /** A user-made mix, or a managed mix of another generator, already uses the same `(channel, source)`. */
    MIX_KEY_IN_USE,

    /** No free curve index for a planned curve. */
    NO_FREE_CURVE,

    /** No free dual-rate slot on the channel. */
    NO_FREE_DUAL_RATE_SLOT,

    /** The plan references a channel outside 0..[CHANNEL_COUNT]-1. */
    INVALID_CHANNEL,

    /** The plan references an invalid or disabled source. */
    INVALID_SOURCE,

    /** A [CurveRef.Planned] key has no [PlannedCurve], or an [CurveRef.Existing] index is invalid. */
    INVALID_CURVE,

    /**
     * A managed item was edited by hand since it was generated (its recorded
     * [ManagedMix.fingerprint] differs from the item's current one); applying overwrites the
     * edit. Always a warning.
     */
    MANAGED_ITEM_MODIFIED,

    /**
     * A managed item recorded in [ManagedItems] no longer exists in the config (mix key gone,
     * curve of type `NONE`, dual-rate slot disabled). It is recreated. Always a warning.
     */
    MANAGED_ITEM_MISSING,

    /** A managed curve is also referenced by user-made mixes; it is kept instead of freed. */
    CURVE_SHARED_WITH_USER,

    /** The plan changes a channel setting (limits, lock) the user had set differently. */
    CHANNEL_SETTING_CHANGED,

    /**
     * The settings the plan was compiled from are invalid (`FlightModeCompiler.validate` /
     * `WingCompiler.validate`: no mode switch, two modes on one switch position, the same channel
     * assigned twice, values out of range, …). Always blocking; [PlanConflict.detail] says what.
     */
    INVALID_SETTINGS,
}

/** Whether a conflict prevents applying the plan. */
enum class PlanConflictSeverity {
    /** The plan must not be applied until the conflict is resolved. */
    BLOCKING,

    /** The plan can be applied; the user should know. */
    WARNING,
}

/**
 * One conflict found while diffing a plan. The fields that apply depend on [kind].
 *
 * @property detail English diagnostic text for logs; the UI builds its own localized message from the fields
 */
data class PlanConflict(
    val kind: PlanConflictKind,
    val severity: PlanConflictSeverity = PlanConflictSeverity.BLOCKING,
    val channel: Int? = null,
    val source: SourceId? = null,
    val slot: Int? = null,
    val curveIndex: Int? = null,
    val curveKey: String? = null,
    val detail: String = "",
) {
    /** True if the conflict forbids applying the plan. */
    val isBlocking: Boolean get() = severity == PlanConflictSeverity.BLOCKING
}

/** One concrete change to the working config. Executed in list order by `ConfigSession.applyPlan`. */
sealed interface PlanOp {

    /** Delete the mix `(channel, source)` of [existing]. */
    data class RemoveMix(val existing: MixLine) : PlanOp

    /**
     * Add a mix for `(mix.channel, mix.source)` with the planned values of [mix]. Executed as
     * `radio.addMix(channel, source)` followed by
     * `radio.setMix(mix.copy(mixIndex = added.mixIndex))`: the index comes from the line the SDK
     * just created, and its read-only fields (`sourceName`, `sourceType`, `mixCount`, which are
     * empty in the planned [mix]) are never written (`Radio.setMix`).
     */
    data class AddMix(val mix: MixLine) : PlanOp

    /** Rewrite an existing mix. [after] keeps the identity and mix index of [before]. */
    data class UpdateMix(val before: MixLine, val after: MixLine) : PlanOp

    /** Write curve [after] (allocate, change, or free when `after.type == NONE`). */
    data class SetCurve(val before: CurveConfig, val after: CurveConfig) : PlanOp

    /** Write dual-rate slot [after] (a slot is "removed" by disabling its switch). */
    data class SetDualRate(val before: DualRateSlot, val after: DualRateSlot) : PlanOp

    /** Write output-channel settings [after]. */
    data class SetChannel(val before: OutputChannelConfig, val after: OutputChannelConfig) : PlanOp

    /** Write timer (or counter) configuration [after]; [before] is what was read (null if unreadable). */
    data class SetTimer(val before: TimerConfig?, val after: TimerConfig, val isCounter: Boolean = false) : PlanOp
}

/**
 * Dry-run result of applying a [MixPlan]: what will be added / changed / removed, and what stands
 * in the way.
 *
 * @property ops operations in execution order (removals, curves, mixes, dual rates, channels, timers)
 * @property conflicts everything the user must know; any [PlanConflict.isBlocking] entry forbids applying
 * @property managedAfter the complete [ManagedItems] to store in the model extras once [ops] were applied
 * @property baseRevision `WorkingConfig.revision` the diff was computed against; applying is refused
 *   if the config changed since
 */
data class PlanDiff(
    val generator: String,
    val ops: List<PlanOp> = emptyList(),
    val conflicts: List<PlanConflict> = emptyList(),
    val managedAfter: ManagedItems = ManagedItems.NONE,
    val baseRevision: Long = 0,
) {
    /** Nothing would change. */
    val isNoOp: Boolean get() = ops.isEmpty()

    /** True if any conflict forbids applying. */
    val hasBlockingConflicts: Boolean get() = conflicts.any { it.isBlocking }

    /** True if the diff may be applied (possibly with warnings). */
    val canApply: Boolean get() = !hasBlockingConflicts

    /** The mixes that would be added. */
    val added: List<PlanOp> get() = ops.filter { it is PlanOp.AddMix }

    /** The mixes that would be removed. */
    val removed: List<PlanOp> get() = ops.filter { it is PlanOp.RemoveMix }

    /** Everything else: updated mixes, curves, dual rates, channels, timers. */
    val changed: List<PlanOp> get() = ops.filter { it !is PlanOp.AddMix && it !is PlanOp.RemoveMix }

    /** Copy with [more] conflicts appended (the compilers' `validate` results). */
    fun withConflicts(more: List<PlanConflict>): PlanDiff =
        if (more.isEmpty()) this else copy(conflicts = conflicts + more)
}

/**
 * Everything needed to create a model: the firmware side as a plan and the app side as extras.
 * Applied by `ConfigSession.createModel`.
 *
 * ## Who generates what (binding for `ModelTemplates` and `ConfigSession.createModel`)
 * A new model starts as a copy of the configuration that happens to be loaded (the SDK cannot
 * create a default model file), and whatever the wing wizard or the flight-mode compiler can
 * express must stay owned by *their* generators — otherwise the user could never recompile it
 * from the Flaps / Flight modes tabs (the differ blocks a `(channel, source)` key owned by another
 * generator). Therefore:
 * 1. [plan] has `resetConfig = true` and contains **only** what the wizards do not produce: the
 *    base stick → channel mixes, channel limits, channel locks (throttle cut), timers. It must
 *    not contain any mix key, curve or dual rate that `WingCompiler.compile(extras.wing)` or
 *    `FlightModeCompiler.compile(extras.flightModes)` produce.
 * 2. Everything expressible as [WingConfig] / [FlightModesConfig] goes **only** into [extras].
 * 3. `ConfigSession.createModel` applies [plan], then compiles and applies `extras.wing` under
 *    the generator `"wing"` and `extras.flightModes` under `"flightModes"`.
 *
 * @property templateId id of the template (`ModelTemplates` in `:core:engine`), or null for a blank model
 * @property plan the base plan; null is treated like the plan of [blank]
 */
@Serializable
data class ModelBlueprint(
    val templateId: String? = null,
    val modelType: ModelType = ModelType.OTHER,
    val plan: MixPlan? = null,
    val extras: ModelExtras = ModelExtras(modelType = modelType),
) {
    /** [plan], or the neutralising plan of a blank model when none was given. */
    val effectivePlan: MixPlan get() = plan ?: BLANK_PLAN

    companion object {
        /** The plan of a blank model: no mixes, no curves, no dual rates, neutral channels, stopped timers. */
        val BLANK_PLAN: MixPlan = MixPlan(
            generator = ManagedItems.GENERATOR_BLANK,
            replaceAllMixes = true,
            resetConfig = true,
        )

        /** A blank model of [type]: neutral configuration without mixes ([BLANK_PLAN]), default extras. */
        fun blank(type: ModelType = ModelType.OTHER): ModelBlueprint =
            ModelBlueprint(modelType = type, plan = BLANK_PLAN)
    }
}
