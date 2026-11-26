package org.openax12.core.model

import kotlinx.serialization.Serializable

// One-to-one mirrors of the SDK configuration structs (rmAxSdkTypes.h). Field names are idiomatic;
// the KDoc of each property names the SDK field it mirrors.
//
// Every SDK field is represented, but the objects are NOT what is written back verbatim: a
// `Radio` setter overlays the writable fields onto a fresh native read (see "Read-modify-write"
// in the `Radio` KDoc). Fields documented as "read-only" / "read-back" are therefore ignored when
// writing — a struct built from scratch (compilers, templates) is as safe to write as a copy of a
// read one — and enum / Boolean mapped fields keep an unknown device code unless really changed
// (see SdkEnums.kt).

/**
 * Mirror of `RcSrcCfgUI`: one input source with its live values.
 *
 * @property index `srcIdx`
 * @property name `srcName` (user-visible)
 * @property type `srcType` (firmware-defined, kept verbatim)
 * @property enabled `isEnable != 0`: the source exists on this radio and should be shown
 * @property raw `rawVal`: half-scale raw value (multiply by 2 for the full-scale stick value)
 * @property logic `rawLogicVal`: logical value in percent, −100..+100 (switches: −100/0/+100)
 * @property output `outputVal`: value after the input weight, in percent
 * @property weight input weight in percent (negative = reversed)
 */
@Serializable
data class SourceInfo(
    val index: Int,
    val name: String = Sources.defaultName(index),
    val type: Int = 0,
    val enabled: Boolean = true,
    val raw: Int = 0,
    val logic: Int = 0,
    val output: Int = 0,
    val weight: Int = 100,
) {
    /** [index] as a [SourceId]. */
    val id: SourceId get() = SourceId(index)
    /** Stick, pot, slider, switch or counter. */
    val kind: SourceKind get() = Sources.kindOf(index)
}

/**
 * Mirror of `RcTrimmingTopKeyUI`: the four trim values (percent steps as reported by the firmware).
 *
 * @property leftVertical `trimLV`
 * @property leftHorizontal `trimLH`
 * @property rightVertical `trimRV`
 * @property rightHorizontal `trimRH`
 */
@Serializable
data class Trims(
    val leftVertical: Int = 0,
    val leftHorizontal: Int = 0,
    val rightVertical: Int = 0,
    val rightHorizontal: Int = 0,
) {
    companion object {
        /** All trims centred. */
        val ZERO = Trims()
    }
}

/**
 * Read-only summary of one dual-rate slot as embedded in `RcChOutCfgUI`
 * (`drNSwIdx` / `drNSwAct` / `drNWeight`). Edit dual rates through [DualRateSlot].
 */
@Serializable
data class ChannelDualRate(
    val switchSource: Int = 0,
    val switchAction: Int = 0,
    val weight: Int = 100,
)

/**
 * Mirror of `RcChOutCfgUI`: one output channel (limits, curve, weight, channel lock, live value).
 *
 * @property channel `chIdx`, 0-based (CH01 = 0)
 * @property middle `middle`: subtrim / centre in percent, ±100
 * @property min `min`: lower limit in percent
 * @property max `max`: upper limit in percent
 * @property curve `curveIdx`: output curve index or [NO_CURVE]
 * @property mixCount `mixesNum`: number of mix lines on this channel (read-only)
 * @property weight `weight`: output weight in percent; negative = reversed
 * @property output `chOut`: live output value in percent (read-only)
 * @property logic `logicVal`: live logical value (read-only)
 * @property lock channel lock condition: source = `lockChSwIdx`, op = `lockChSwType`,
 *   value = `lockChSwVal`. While it is true the channel outputs [lockValue]
 *   ([CompareOp.DISABLED] = no lock). The header documents no value set for `lockChSwType`
 *   (assumption 14): a device code outside 0..3 reads as [CompareOp.DISABLED] and is written back
 *   unchanged unless [lock]'s op is really changed, so editing limits never clears a lock.
 * @property lockValue `lockChVal`: the locked output in percent
 * @property dualRates `dr1..dr3` summaries (always [DR_SLOTS] entries; read-only — never written
 *   by `Radio.setOutputChannel`)
 */
@Serializable
data class OutputChannelConfig(
    val channel: Int,
    val middle: Int = 0,
    val min: Int = -100,
    val max: Int = 100,
    val curve: Int = NO_CURVE,
    val mixCount: Int = 0,
    val weight: Int = 100,
    val output: Int = 0,
    val logic: Float = 0f,
    val lock: SwitchCondition = SwitchCondition.NONE,
    val lockValue: Int = 0,
    val dualRates: List<ChannelDualRate> = List(DR_SLOTS) { ChannelDualRate() },
) {
    /** True if the output is reversed (negative [weight]). */
    val isReversed: Boolean get() = weight < 0

    /** True if a channel lock is configured. */
    val hasLock: Boolean get() = lock.isEnabled

    /** True if an output curve is assigned. */
    val hasCurve: Boolean get() = curve != NO_CURVE
}

/** Identity of a mix line for deletion and for managed-item bookkeeping: `(chIdx, srcIdx)`. */
@Serializable
data class MixKey(val channel: Int, val source: Int) {
    /** [source] as a [SourceId]. */
    val sourceId: SourceId get() = SourceId(source)
}

/**
 * Mirror of `RcMixCfgDataUI`: one mix line of an output channel.
 *
 * @property channel `chIdx`
 * @property mixIndex `mixIdx`: position within the channel
 * @property source `srcIdx`
 * @property sourceName `srcName` (read-only)
 * @property sourceType `srcType` (read-only, kept verbatim)
 * @property offset `offset` in percent, ±100
 * @property curve `curveIdx` or [NO_CURVE]
 * @property switch enable condition: source = `switchIdx`, op = `swCmpType`, value = `swCmpVal`
 * @property mode `mixesMode`
 * @property slowUpMs `slowMsUp`: slow-down for rising values in ms, 0 = off
 * @property slowDownMs `slowMsDown`
 * @property weightUp `weightUp`: weight for the positive half in percent
 * @property weightDown `weightDown`: weight for the negative half in percent
 * @property mixCount `mixesCount`: number of mixes on the channel (read-only)
 */
@Serializable
data class MixLine(
    val channel: Int,
    val mixIndex: Int = 0,
    val source: SourceId = SourceId(0),
    val sourceName: String = "",
    val sourceType: Int = 0,
    val offset: Int = 0,
    val curve: Int = NO_CURVE,
    val switch: SwitchCondition = SwitchCondition.NONE,
    val mode: MixMode = MixMode.ADD,
    val slowUpMs: Int = 0,
    val slowDownMs: Int = 0,
    val weightUp: Int = 100,
    val weightDown: Int = 100,
    val mixCount: Int = 0,
) {
    /** Identity of the line: `(channel, source)`. */
    val key: MixKey get() = MixKey(channel, source.index)
    val hasCurve: Boolean get() = curve != NO_CURVE
}

/** One point of a custom curve, both coordinates in percent (−100..+100). */
@Serializable
data class CurvePoint(val x: Int, val y: Int)

/**
 * Mirror of `RcCurveCfgDataUI`.
 *
 * [x] and [y] always hold [CURVE_MAX_POINTS] entries (the SDK arrays are fixed-length); only the
 * first [pointCount] are meaningful for [CurveType.CUSTOM].
 *
 * Parameter convention for the non-custom types (assumption 12, unverified): `y[0]` holds the
 * single parameter — the differential in percent for [CurveType.DIFF], the expo in percent for
 * [CurveType.EXPO] and the function number for [CurveType.FUNC] — and `x[0]` is unused (written
 * as 0 by [parametric]). Because the SDK's test console reads and writes an `(x0, y0)` pair for
 * these types, [sameShapeAs] compares both until the device confirmed that `x[0]` has no meaning.
 *
 * @property index `idx` (0..[CURVE_COUNT]-1)
 * @property type `cType`
 * @property pointCount `ptNum`
 * @property x `xVal[12]`
 * @property y `yVal[12]`
 */
@Serializable
data class CurveConfig(
    val index: Int,
    val type: CurveType = CurveType.NONE,
    val pointCount: Int = 0,
    val x: List<Int> = List(CURVE_MAX_POINTS) { 0 },
    val y: List<Int> = List(CURVE_MAX_POINTS) { 0 },
) {
    /** The meaningful points of a custom curve, in order. */
    val points: List<CurvePoint>
        get() = List(pointCount.coerceIn(0, CURVE_MAX_POINTS)) { CurvePoint(x.getOrElse(it) { 0 }, y.getOrElse(it) { 0 }) }

    /** The single parameter of a DIFF / EXPO / FUNC curve (see class doc). */
    val parameter: Int get() = y.getOrElse(0) { 0 }

    /** True for a free curve (type [CurveType.NONE]). */
    val isUnused: Boolean get() = type == CurveType.NONE

    /**
     * True if both curves certainly produce the same output: same type and, for
     * [CurveType.CUSTOM], the same points; for DIFF / EXPO / FUNC the same `x[0]` **and** `y[0]`
     * (deliberately conservative, see the class doc). The plan differ shares a curve between
     * mixes only when this is true.
     */
    fun sameShapeAs(other: CurveConfig): Boolean =
        type == other.type && when (type) {
            CurveType.NONE -> true
            CurveType.CUSTOM -> points == other.points
            else -> parameter == other.parameter && x.getOrElse(0) { 0 } == other.x.getOrElse(0) { 0 }
        }

    companion object {
        /** An unused curve at [index]. */
        fun empty(index: Int): CurveConfig = CurveConfig(index)

        /** A custom curve from up to [CURVE_MAX_POINTS] points (extra points are dropped). */
        fun custom(index: Int, points: List<CurvePoint>): CurveConfig {
            val used = points.take(CURVE_MAX_POINTS)
            return CurveConfig(
                index = index,
                type = CurveType.CUSTOM,
                pointCount = used.size,
                x = List(CURVE_MAX_POINTS) { used.getOrNull(it)?.x ?: 0 },
                y = List(CURVE_MAX_POINTS) { used.getOrNull(it)?.y ?: 0 },
            )
        }

        /** A DIFF / EXPO / FUNC curve with its single [parameter]. */
        fun parametric(index: Int, type: CurveType, parameter: Int): CurveConfig = CurveConfig(
            index = index,
            type = type,
            pointCount = 0,
            y = List(CURVE_MAX_POINTS) { if (it == 0) parameter else 0 },
        )
    }
}

/**
 * Mirror of `RcChCfgDrUI`: one dual-rate slot of a channel.
 *
 * `rcDelDrData` is a no-op in the SDK: "deleting" a slot means writing it back with
 * [CompareOp.DISABLED].
 *
 * @property channel `chIdx`
 * @property slot `idx` (0..[DR_SLOTS]-1)
 * @property count `drCount`: slots in use on the channel as the SDK counts them (read-only; since
 *   `rcDelDrData` does nothing this may include slots the app disabled)
 * @property switch enable condition: source = `swSrcIdx`, op = `swCmpType`, value = `swCmpVal`
 * @property weight `drWeight` in percent
 */
@Serializable
data class DualRateSlot(
    val channel: Int,
    val slot: Int,
    val count: Int = 0,
    val switch: SwitchCondition = SwitchCondition.NONE,
    val weight: Int = 100,
) {
    /** False for a disabled (= free) slot. */
    val isEnabled: Boolean get() = switch.isEnabled
}

/**
 * Mirror of `RcTimeCfgDataUI`: configuration of a timer or a counter.
 *
 * The firmware has **no manual start / stop**: a timer runs while its [switch] condition holds
 * (and can only be reset, `Radio.resetTimer`). The values of [mode], [loopMode] and [outputMode]
 * are not documented by the SDK (assumption 23): they are kept verbatim and the Timers tab shows
 * them as plain numbers until they were identified on the device.
 *
 * @property index `timeIdx`: in-group index 0..2 (used when writing). Whether the SDK really
 *   takes the in-group index together with `isCounter` is unverified (assumption 21).
 * @property mode `mode`: working mode (firmware-defined, kept verbatim)
 * @property switch run condition: source = `switchIdx`, op = `swCmpType`, value = `switchAct`
 * @property startTime `startTime`: 0 = count up; > 0 = countdown period in seconds. For a counter
 *   this is the maximum and must not be 0.
 * @property countUp `direction == 1`
 * @property alertMode `countdown`
 * @property alertIntervalSec `beepTimeSec`
 * @property resetSwitch reset condition: source = `resetSwIdx`, op = `rstSwCmpType`, value = `resetSwAct`
 * @property compareValue `cmpValue`: input comparison value for the channel-activated mode
 * @property compareOp `cmpType`
 * @property loopMode `loopMode`
 * @property outputMode `outputMode`: maps between [outputMin] and [outputMax] by [threshold]
 * @property outputMin `outputMin` in percent
 * @property outputMax `outputMax` in percent
 * @property threshold `threshold`
 * @property runningDirection `trDirection` (read-back, never written)
 * @property runningStartTime `trStartTime` (read-back, never written)
 * @property count `timeCount`: current value (read-back, never written)
 * @property notifyCode `notifyCode` (read-back, never written)
 */
@Serializable
data class TimerConfig(
    val index: Int,
    val mode: Int = 0,
    val switch: SwitchCondition = SwitchCondition.NONE,
    val startTime: Int = 0,
    val countUp: Boolean = true,
    val alertMode: TimerAlertMode = TimerAlertMode.SILENT,
    val alertIntervalSec: Int = 0,
    val resetSwitch: SwitchCondition = SwitchCondition.NONE,
    val compareValue: Int = 0,
    val compareOp: CompareOp = CompareOp.DISABLED,
    val loopMode: Int = 0,
    val outputMode: Int = 0,
    val outputMin: Int = 0,
    val outputMax: Int = 0,
    val threshold: Int = 0,
    val runningDirection: Int = 0,
    val runningStartTime: Int = 0,
    val count: Int = 0,
    val notifyCode: Int = 0,
)

/**
 * Mirror of `RcTimeReportUI` (payload of the timer and the counter report messages).
 *
 * @property index `timeIdx`: in-group index
 * @property showsElapsed `direction == 1`: [count] is elapsed/used; otherwise it is the remainder
 * @property startTime `startTime`: 0 = counting up, > 0 = countdown period
 * @property count `timeCount`: seconds for a timer, current value for a counter
 */
@Serializable
data class TimerReport(
    val index: Int,
    val showsElapsed: Boolean = true,
    val startTime: Int = 0,
    val count: Int = 0,
)

/**
 * Mirror of `RcTimeNotifyUI` (payload of the timer notification message).
 *
 * @property index `timeIdx` **as delivered by the SDK**. Whether this is the in-group index 0..2
 *   or the device timer number 0..5 (3..5 = counters) is unverified (assumption 21); only the
 *   radio backend interprets it (when it assembles [LiveSnapshot.timers] / [LiveSnapshot.counters]),
 *   everything else uses [TimerState].
 * @property running `isRunning != 0`
 * @property code `notifyCode` as reported by the device ([TimerAlertMode.NOTIFY_RESET] = reset)
 * @property count `timeCount` when the notification fired
 */
@Serializable
data class TimerNotify(
    val index: Int,
    val running: Boolean = false,
    val code: Int = 0,
    val count: Int = 0,
)

/**
 * Mirror of `RcSysCfgDataUI`.
 *
 * Only [alarmVoltageDeciVolt], [rfModuleMode], [stickMode] and [preFlightCheck] are really read
 * and written by the current SDK; the other fields are carried along but must not be relied on.
 *
 * @property failsafeMode `fsMode` (not persisted by the SDK)
 * @property failsafeValues `fsChVal[16]` in percent (not persisted by the SDK)
 * @property stickMode `stickMode`
 * @property preFlightCheck `preFlightCheckChannel[36]`: preset per source Rud..SP in percent,
 *   [PREFLIGHT_NO_CHECK] = do not check
 * @property rfModuleMode `rfModuleMode`
 * @property alarmVoltageDeciVolt `alramVtg`: low-voltage alarm threshold in 0.1 V
 * @property stickBeep `joytickBeep` (not persisted by the SDK)
 * @property modelMatchId `modelMatchId` (not persisted; use [ModelInfo.modelMatch])
 * @property crsfBaudRate `crsfBaudRate`: 1 = 400k, 2 = 921600, 3 = 1870000 (not persisted)
 */
@Serializable
data class SystemConfig(
    val failsafeMode: FailsafeMode = FailsafeMode.NONE,
    val failsafeValues: List<Int> = List(FAILSAFE_CHANNEL_COUNT) { 0 },
    val stickMode: StickMode = StickMode.MODE_2,
    val preFlightCheck: List<Int> = List(PREFLIGHT_SOURCE_COUNT) { PREFLIGHT_NO_CHECK },
    val rfModuleMode: RfModuleMode = RfModuleMode.INTERNAL,
    val alarmVoltageDeciVolt: Int = 0,
    val stickBeep: Int = 0,
    val modelMatchId: Int = 0,
    val crsfBaudRate: Int = 0,
) {
    /** Low-voltage alarm threshold in volts. */
    val alarmVoltage: Float get() = alarmVoltageDeciVolt / 10f

    /** Pre-flight preset of [source], or null for "do not check" / sources without a preset. */
    fun preFlightPreset(source: SourceId): Int? =
        preFlightCheck.getOrNull(source.index)?.takeIf { it != PREFLIGHT_NO_CHECK }

    /** Copy with the pre-flight preset of [source] set to [value] (null = do not check). */
    fun withPreFlightPreset(source: SourceId, value: Int?): SystemConfig {
        if (source.index !in 0 until PREFLIGHT_SOURCE_COUNT) return this
        val list = List(PREFLIGHT_SOURCE_COUNT) { preFlightCheck.getOrElse(it) { PREFLIGHT_NO_CHECK } }.toMutableList()
        list[source.index] = value?.coerceIn(PERCENT_MIN, PERCENT_MAX) ?: PREFLIGHT_NO_CHECK
        return copy(preFlightCheck = list)
    }
}

/**
 * Mirror of `RcModelDataUI`: metadata of a model file inside the SDK's private data directory.
 *
 * @property name `modelName`
 * @property image `modelImg` (stock-app image path/resource; open-ax12 keeps its own picture in the library)
 * @property filePath `filePath`
 * @property index `modelIdx` (64-bit). Its relation to the `int` returned by
 *   `rcGetCurrentModelCfgIndex` is unverified (assumption 15) — resolve the current model only
 *   through `Radio.getCurrentModel()`.
 * @property modelMatch `modelMatch`: ELRS model-match number (unsigned 32-bit, kept as Long)
 */
@Serializable
data class ModelInfo(
    val name: String = "",
    val image: String = "",
    val filePath: String = "",
    val index: Long = 0,
    val modelMatch: Long = 0,
)

/**
 * Complete copy of the SDK's current working configuration, as read through the `Radio` getters.
 * Input of the compilers, the plan differ, the reference mixer and the setup tabs.
 *
 * Live fields inside the elements ([SourceInfo.raw], [OutputChannelConfig.output], …) are the
 * values at the time of reading and are not kept up to date; use [LiveSnapshot] for live data.
 *
 * @property revision the `Radio.configRevision` this copy was read at
 * @property mixes all mix lines of all channels, ordered by channel then [MixLine.mixIndex]
 * @property dualRates all slots of all channels ([CHANNEL_COUNT] × [DR_SLOTS]), disabled ones included
 * @property system the system configuration as read, or null when the radio could not give a
 *   trustworthy one (`rcGetRcSysConfig()` is unreliable, assumption r5; the rest of the working
 *   configuration is still valid then and the System screen shows "unavailable"). Defaults are
 *   never substituted. Do not write it back from here — `Radio.setSystemConfig` callers start from
 *   a fresh `Radio.getSystemConfig()`.
 */
@Serializable
data class WorkingConfig(
    val revision: Long = 0,
    val sources: List<SourceInfo> = emptyList(),
    val channels: List<OutputChannelConfig> = emptyList(),
    val mixes: List<MixLine> = emptyList(),
    val curves: List<CurveConfig> = emptyList(),
    val dualRates: List<DualRateSlot> = emptyList(),
    val timers: List<TimerConfig> = emptyList(),
    val counters: List<TimerConfig> = emptyList(),
    val system: SystemConfig? = null,
) {
    /** True for the placeholder used while the radio is not usable (no channels were read). */
    val isEmpty: Boolean get() = channels.isEmpty()

    /** Output channel [index] (0-based), or null. */
    fun channel(index: Int): OutputChannelConfig? = channels.firstOrNull { it.channel == index }

    /** The input source [id], or null. */
    fun source(id: SourceId): SourceInfo? = sources.firstOrNull { it.index == id.index }

    /** User-visible name of a source, falling back to the firmware default name. */
    fun sourceName(id: SourceId): String = source(id)?.name?.takeIf { it.isNotBlank() } ?: id.defaultName

    /** Mix lines of [channel] in evaluation order. */
    fun mixesOf(channel: Int): List<MixLine> = mixes.filter { it.channel == channel }.sortedBy { it.mixIndex }

    /** The mix of [source] on [channel], or null. */
    fun mix(channel: Int, source: SourceId): MixLine? =
        mixes.firstOrNull { it.channel == channel && it.source == source }

    /** The mix with identity [key], or null. */
    fun mix(key: MixKey): MixLine? = mix(key.channel, key.sourceId)

    /** Curve [index], or null. */
    fun curve(index: Int): CurveConfig? = curves.firstOrNull { it.index == index }

    /** The dual-rate slots of [channel] ordered by slot, disabled ones included. */
    fun dualRatesOf(channel: Int): List<DualRateSlot> = dualRates.filter { it.channel == channel }.sortedBy { it.slot }

    /** Dual-rate [slot] of [channel], or null. */
    fun dualRate(channel: Int, slot: Int): DualRateSlot? =
        dualRates.firstOrNull { it.channel == channel && it.slot == slot }

    /** True if any mix line or output channel references curve [index]. */
    fun isCurveReferenced(index: Int): Boolean =
        mixes.any { it.curve == index } || channels.any { it.curve == index }

    companion object {
        /** Placeholder while the radio is not usable. */
        val EMPTY = WorkingConfig()
    }
}
