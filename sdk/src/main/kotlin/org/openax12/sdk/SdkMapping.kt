package org.openax12.sdk

import org.openax12.core.model.ChannelDualRate
import org.openax12.core.model.CompareOp
import org.openax12.core.model.CurveConfig
import org.openax12.core.model.CurveType
import org.openax12.core.model.DR_SLOTS
import org.openax12.core.model.DualRateSlot
import org.openax12.core.model.FailsafeMode
import org.openax12.core.model.FirmwareInfo
import org.openax12.core.model.MixLine
import org.openax12.core.model.MixMode
import org.openax12.core.model.ModelInfo
import org.openax12.core.model.NO_CURVE
import org.openax12.core.model.OutputChannelConfig
import org.openax12.core.model.PERCENT_MAX
import org.openax12.core.model.PERCENT_MIN
import org.openax12.core.model.PREFLIGHT_NO_CHECK
import org.openax12.core.model.RawSystemFields
import org.openax12.core.model.RfModuleMode
import org.openax12.core.model.SourceId
import org.openax12.core.model.SourceInfo
import org.openax12.core.model.StickMode
import org.openax12.core.model.SwitchCondition
import org.openax12.core.model.SystemConfig
import org.openax12.core.model.TelemetryAlarm
import org.openax12.core.model.TelemetryItem
import org.openax12.core.model.TimerAlertMode
import org.openax12.core.model.TimerConfig

/**
 * Which model index a list element stands for: the index it reports (`srcIdx`, `chIdx`, `idx`) or
 * its position in the list.
 */
internal object SdkIndex {

    /**
     * Resolves the model index of every element of a list from the indices the elements report.
     * The reported indices are used only if [SdkAssumptions.TRUST_REPORTED_INDICES] is set and
     * they form a permutation of `0 until size`; otherwise (duplicates, gaps, all zero because
     * the SDK did not fill the field) every element gets its position.
     *
     * @return for each position the model index of that element
     */
    fun resolve(reported: IntArray): IntArray {
        val size = reported.size
        if (SdkAssumptions.TRUST_REPORTED_INDICES && size > 0) {
            val seen = BooleanArray(size)
            var permutation = true
            for (value in reported) {
                if (value !in 0 until size || seen[value]) {
                    permutation = false
                    break
                }
                seen[value] = true
            }
            if (permutation) return reported.copyOf()
        }
        return IntArray(size) { it }
    }

    /**
     * The value to write back into an element's own index field (`chIdx`) so that the SDK
     * addresses the element it was read from: the reported value when the reported values are
     * usable keys (all distinct), otherwise the position — an SDK that leaves the field at zero
     * in its lists would else direct every write to element 0.
     *
     * @return for each position the key to write
     */
    fun keys(reported: IntArray): IntArray {
        val distinct = HashSet<Int>(reported.size * 2)
        for (value in reported) if (!distinct.add(value)) return IntArray(reported.size) { it }
        return reported.copyOf()
    }
}

/**
 * Sizes of the SDK lists at one moment — what indices are validated against ("Invalid index" in
 * the `Radio` KDoc): never the compile-time constants of `:core:model`.
 *
 * @property sourceCount size of `rcGetSrcCfgList()`
 * @property channelCount size of `rcGetChOutList()`
 * @property curveCount size of `rcGetCurveList(-1)`
 * @property mixCounts `mixesNum` per resolved channel index
 */
internal class SdkCounts(
    val sourceCount: Int,
    val channelCount: Int,
    val curveCount: Int,
    private val mixCounts: IntArray,
) {
    fun isChannel(channel: Int): Boolean = channel in 0 until channelCount

    fun isSource(source: Int): Boolean = source in 0 until sourceCount

    /** A real curve index ([NO_CURVE] is not). */
    fun isCurve(curve: Int): Boolean = curve in 0 until curveCount

    /** A value allowed in a `curveIdx` field: a real curve or [NO_CURVE]. */
    fun isCurveReference(curve: Int): Boolean = curve == NO_CURVE || isCurve(curve)

    /** `mixesNum` of [channel]; 0 for an invalid channel. */
    fun mixCount(channel: Int): Int = if (isChannel(channel)) mixCounts[channel].coerceAtLeast(0) else 0

    fun isMix(channel: Int, mixIndex: Int): Boolean = mixIndex in 0 until mixCount(channel)

    companion object {
        val EMPTY = SdkCounts(0, 0, 0, IntArray(0))

        /** Decodes the buffer of [NativeApi.readCounts]. */
        fun decode(buffer: IntArray): SdkCounts {
            if (buffer.size < NativeLayout.COUNTS_SIZE) return EMPTY
            val sources = buffer[NativeLayout.COUNTS_SOURCE_COUNT].coerceIn(0, NativeLayout.LIVE_MAX_SOURCES)
            val channels = buffer[NativeLayout.COUNTS_CHANNEL_COUNT].coerceIn(0, NativeLayout.LIVE_MAX_CHANNELS)
            val curves = buffer[NativeLayout.COUNTS_CURVE_COUNT].coerceIn(0, NO_CURVE)
            val reported = IntArray(channels) {
                buffer[NativeLayout.COUNTS_CHANNELS_OFFSET + it * NativeLayout.COUNTS_CHANNEL_STRIDE]
            }
            val slots = SdkIndex.resolve(reported)
            val mixes = IntArray(channels)
            for (position in 0 until channels) {
                mixes[slots[position]] =
                    buffer[NativeLayout.COUNTS_CHANNELS_OFFSET + position * NativeLayout.COUNTS_CHANNEL_STRIDE + 1]
            }
            return SdkCounts(sources, channels, curves, mixes)
        }
    }
}

/**
 * Pure conversions between the raw SDK structs and the model types, and the overlay step of every
 * setter.
 *
 * Overlay rules (binding, see "Read-modify-write" in the `Radio` KDoc):
 * - the target is always a **fresh native read**; read-only / read-back / live fields stay as read;
 * - a field the model maps to an enum or Boolean is written only if the wanted value differs from
 *   the mapped value of the fresh read, so unknown device codes survive unrelated writes;
 * - an index field may keep the value it was read with even if that value is out of range (it
 *   cannot get worse), a *changed* index must be valid;
 * - an `overlay…` function returns `false` without having modified anything the caller will use
 *   when the argument is not acceptable — the caller then writes nothing.
 */
internal object SdkMapping {

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private fun condition(source: Int, op: Int, value: Int): SwitchCondition =
        SwitchCondition(SourceId(source), CompareOp.fromSdk(op), value)

    /** The raw compare code to write: unchanged unless the wanted op differs from the mapped one. */
    private fun compareCode(raw: Int, wanted: CompareOp): Int = if (CompareOp.fromSdk(raw) != wanted) wanted.sdk else raw

    private fun sourceIndexOk(wanted: Int, fresh: Int, counts: SdkCounts): Boolean = wanted == fresh || counts.isSource(wanted)

    private fun curveReferenceOk(wanted: Int, fresh: Int, counts: SdkCounts): Boolean =
        wanted == fresh || counts.isCurveReference(wanted)

    // ---------------------------------------------------------------------------------------------
    // Sources
    // ---------------------------------------------------------------------------------------------

    fun sourceInfo(raw: RawSrc, index: Int): SourceInfo = SourceInfo(
        index = index,
        name = raw.srcName,
        type = raw.srcType,
        enabled = raw.isEnable != 0,
        raw = raw.rawVal,
        logic = SdkAssumptions.sourcePercent(raw.rawVal),
        output = SdkAssumptions.sourceOutputPercent(raw.rawVal, raw.outputVal),
        weight = raw.weight,
    )

    fun sources(raws: Array<RawSrc>): List<SourceInfo> {
        val slots = SdkIndex.resolve(IntArray(raws.size) { raws[it].srcIdx })
        return raws.indices.map { sourceInfo(raws[it], slots[it]) }.sortedBy { it.index }
    }

    // ---------------------------------------------------------------------------------------------
    // Output channels
    // ---------------------------------------------------------------------------------------------

    fun outputChannel(raw: RawChOut, index: Int): OutputChannelConfig = OutputChannelConfig(
        channel = index,
        middle = raw.middle,
        min = raw.min,
        max = raw.max,
        curve = raw.curveIdx,
        mixCount = raw.mixesNum,
        weight = raw.weight,
        output = SdkAssumptions.channelPercent(raw.chOut),
        logic = raw.logicVal,
        lock = condition(raw.lockChSwIdx, raw.lockChSwType, raw.lockChSwVal),
        lockValue = raw.lockChVal,
        dualRates = listOf(
            ChannelDualRate(raw.dr1SwIdx, raw.dr1SwAct, raw.dr1Weight),
            ChannelDualRate(raw.dr2SwIdx, raw.dr2SwAct, raw.dr2Weight),
            ChannelDualRate(raw.dr3SwIdx, raw.dr3SwAct, raw.dr3Weight),
        ),
    )

    fun outputChannels(raws: Array<RawChOut>): List<OutputChannelConfig> {
        val slots = channelSlots(raws)
        return raws.indices.map { outputChannel(raws[it], slots[it]) }.sortedBy { it.channel }
    }

    /** Model channel index of every element of a fresh `rcGetChOutList()`. */
    fun channelSlots(raws: Array<RawChOut>): IntArray = SdkIndex.resolve(IntArray(raws.size) { raws[it].chIdx })

    /**
     * The element of a fresh `rcGetChOutList()` that is model channel [channel], or null. Its
     * `chIdx` is set to the key the SDK must get back ([SdkIndex.keys]); the caller overlays the
     * other fields and writes the struct.
     */
    fun findChannel(raws: Array<RawChOut>, channel: Int): RawChOut? {
        val reported = IntArray(raws.size) { raws[it].chIdx }
        val slots = SdkIndex.resolve(reported)
        val keys = SdkIndex.keys(reported)
        for (position in raws.indices) {
            if (slots[position] == channel) return raws[position].also { it.chIdx = keys[position] }
        }
        return null
    }

    /**
     * Overlays the writable fields of [config] onto [raw] (fresh read of that channel, from
     * [findChannel]): `middle`, `min`, `max`, `curveIdx`, `weight` and the lock fields. `chIdx`,
     * `mixesNum`, `chOut`, `logicVal` and `dr1..dr3` stay as read.
     */
    fun overlayChannel(raw: RawChOut, config: OutputChannelConfig, counts: SdkCounts): Boolean {
        if (!curveReferenceOk(config.curve, raw.curveIdx, counts)) return false
        if (!sourceIndexOk(config.lock.source.index, raw.lockChSwIdx, counts)) return false
        raw.middle = config.middle
        raw.min = config.min
        raw.max = config.max
        raw.curveIdx = config.curve
        raw.weight = config.weight
        raw.lockChSwIdx = config.lock.source.index
        raw.lockChSwType = compareCode(raw.lockChSwType, config.lock.op)
        raw.lockChSwVal = config.lock.value
        raw.lockChVal = config.lockValue
        return true
    }

    // ---------------------------------------------------------------------------------------------
    // Mixes
    // ---------------------------------------------------------------------------------------------

    fun mixLine(raw: RawMix, channel: Int, mixIndex: Int): MixLine = MixLine(
        channel = channel,
        mixIndex = mixIndex,
        source = SourceId(raw.srcIdx),
        sourceName = raw.srcName,
        sourceType = raw.srcType,
        offset = raw.offset,
        curve = raw.curveIdx,
        switch = condition(raw.switchIdx, raw.swCmpType, raw.swCmpVal),
        mode = MixMode.fromSdk(raw.mixesMode),
        slowUpMs = raw.slowMsUp,
        slowDownMs = raw.slowMsDown,
        weightUp = raw.weightUp,
        weightDown = raw.weightDown,
        mixCount = raw.mixesCount,
    )

    /**
     * Overlays the writable fields of [mix] onto [raw] (fresh read of that line): `srcIdx`,
     * `offset`, `curveIdx`, the switch triple, `mixesMode`, the slow times and the weights.
     * `srcName`, `srcType` and `mixesCount` stay as read.
     */
    fun overlayMix(raw: RawMix, mix: MixLine, counts: SdkCounts): Boolean {
        if (!sourceIndexOk(mix.source.index, raw.srcIdx, counts)) return false
        if (!curveReferenceOk(mix.curve, raw.curveIdx, counts)) return false
        if (!sourceIndexOk(mix.switch.source.index, raw.switchIdx, counts)) return false
        if (mix.slowUpMs < 0 || mix.slowDownMs < 0) return false
        raw.chIdx = mix.channel
        raw.mixIdx = mix.mixIndex
        raw.srcIdx = mix.source.index
        raw.offset = mix.offset
        raw.curveIdx = mix.curve
        raw.switchIdx = mix.switch.source.index
        raw.swCmpType = compareCode(raw.swCmpType, mix.switch.op)
        raw.swCmpVal = mix.switch.value
        if (MixMode.fromSdk(raw.mixesMode) != mix.mode) raw.mixesMode = mix.mode.sdk
        raw.slowMsUp = mix.slowUpMs
        raw.slowMsDown = mix.slowDownMs
        raw.weightUp = mix.weightUp
        raw.weightDown = mix.weightDown
        return true
    }

    // ---------------------------------------------------------------------------------------------
    // Dual rates
    // ---------------------------------------------------------------------------------------------

    fun dualRate(raw: RawDr, channel: Int, slot: Int): DualRateSlot = DualRateSlot(
        channel = channel,
        slot = slot,
        count = raw.drCount,
        switch = condition(raw.swSrcIdx, raw.swCmpType, raw.swCmpVal),
        weight = raw.drWeight,
    )

    fun isDualRateSlot(slot: Int): Boolean = slot in 0 until DR_SLOTS

    /** Overlays the switch condition and the weight of [slot] onto [raw]; `drCount` stays as read. */
    fun overlayDualRate(raw: RawDr, slot: DualRateSlot, counts: SdkCounts): Boolean {
        if (!sourceIndexOk(slot.switch.source.index, raw.swSrcIdx, counts)) return false
        raw.chIdx = slot.channel
        raw.idx = slot.slot
        raw.swSrcIdx = slot.switch.source.index
        raw.swCmpType = compareCode(raw.swCmpType, slot.switch.op)
        raw.swCmpVal = slot.switch.value
        raw.drWeight = slot.weight
        return true
    }

    // ---------------------------------------------------------------------------------------------
    // Curves
    // ---------------------------------------------------------------------------------------------

    fun curve(raw: RawCurve, index: Int): CurveConfig = CurveConfig(
        index = index,
        type = CurveType.fromSdk(raw.cType),
        pointCount = raw.ptNum,
        x = raw.xVal.toList(),
        y = raw.yVal.toList(),
    )

    fun curves(raws: Array<RawCurve>): List<CurveConfig> {
        val slots = SdkIndex.resolve(IntArray(raws.size) { raws[it].idx })
        return raws.indices.map { curve(raws[it], slots[it]) }.sortedBy { it.index }
    }

    /**
     * Overlays `cType`, `ptNum` and the points of [config] onto [raw] (fresh read of that curve).
     * The point lists may be shorter than the SDK arrays (the rest stays as read) but never
     * longer, and `ptNum` must fit the arrays.
     */
    fun overlayCurve(raw: RawCurve, config: CurveConfig): Boolean {
        if (raw.xVal.size != RawSizes.CURVE_POINTS || raw.yVal.size != RawSizes.CURVE_POINTS) return false
        if (config.pointCount !in 0..RawSizes.CURVE_POINTS) return false
        if (config.x.size > RawSizes.CURVE_POINTS || config.y.size > RawSizes.CURVE_POINTS) return false
        raw.idx = config.index
        if (CurveType.fromSdk(raw.cType) != config.type) raw.cType = config.type.sdk
        raw.ptNum = config.pointCount
        for (i in config.x.indices) raw.xVal[i] = config.x[i]
        for (i in config.y.indices) raw.yVal[i] = config.y[i]
        return true
    }

    // ---------------------------------------------------------------------------------------------
    // Timers and counters
    // ---------------------------------------------------------------------------------------------

    fun timerConfig(raw: RawTimer, index: Int): TimerConfig = TimerConfig(
        index = index,
        mode = raw.mode,
        switch = condition(raw.switchIdx, raw.swCmpType, raw.switchAct),
        startTime = raw.startTime,
        countUp = raw.direction == 1,
        alertMode = TimerAlertMode.fromSdk(raw.countdown),
        alertIntervalSec = raw.beepTimeSec,
        resetSwitch = condition(raw.resetSwIdx, raw.rstSwCmpType, raw.resetSwAct),
        compareValue = raw.cmpValue,
        compareOp = CompareOp.fromSdk(raw.cmpType),
        loopMode = raw.loopMode,
        outputMode = raw.outputMode,
        outputMin = raw.outputMin,
        outputMax = raw.outputMax,
        threshold = raw.threshold,
        runningDirection = raw.trDirection,
        runningStartTime = raw.trStartTime,
        count = raw.timeCount,
        notifyCode = raw.notifyCode,
    )

    /**
     * Overlays the configuration fields of [config] onto [raw] (fresh read); `trDirection`,
     * `trStartTime`, `timeCount` and `notifyCode` stay as read. [sdkTimeIdx] is the value for
     * `timeIdx` from [SdkAssumptions.timerArguments].
     */
    fun overlayTimer(raw: RawTimer, config: TimerConfig, sdkTimeIdx: Int, isCounter: Boolean, counts: SdkCounts): Boolean {
        if (!sourceIndexOk(config.switch.source.index, raw.switchIdx, counts)) return false
        if (!sourceIndexOk(config.resetSwitch.source.index, raw.resetSwIdx, counts)) return false
        if (config.startTime < 0 || config.alertIntervalSec < 0) return false
        if (isCounter && SdkAssumptions.COUNTER_REQUIRES_NONZERO_MAXIMUM && config.startTime == 0) return false
        if (config.outputMin !in PERCENT_MIN..PERCENT_MAX && config.outputMin != raw.outputMin) return false
        if (config.outputMax !in PERCENT_MIN..PERCENT_MAX && config.outputMax != raw.outputMax) return false
        raw.timeIdx = sdkTimeIdx
        raw.mode = config.mode
        raw.switchIdx = config.switch.source.index
        raw.swCmpType = compareCode(raw.swCmpType, config.switch.op)
        raw.switchAct = config.switch.value
        raw.startTime = config.startTime
        if ((raw.direction == 1) != config.countUp) raw.direction = if (config.countUp) 1 else 0
        if (TimerAlertMode.fromSdk(raw.countdown) != config.alertMode) raw.countdown = config.alertMode.sdk
        raw.beepTimeSec = config.alertIntervalSec
        raw.resetSwIdx = config.resetSwitch.source.index
        raw.rstSwCmpType = compareCode(raw.rstSwCmpType, config.resetSwitch.op)
        raw.resetSwAct = config.resetSwitch.value
        raw.cmpValue = config.compareValue
        raw.cmpType = compareCode(raw.cmpType, config.compareOp)
        raw.loopMode = config.loopMode
        raw.outputMode = config.outputMode
        raw.outputMin = config.outputMin
        raw.outputMax = config.outputMax
        raw.threshold = config.threshold
        return true
    }

    // ---------------------------------------------------------------------------------------------
    // System configuration
    // ---------------------------------------------------------------------------------------------

    fun systemConfig(raw: RawSysCfg): SystemConfig = SystemConfig(
        failsafeMode = FailsafeMode.fromSdk(raw.fsMode),
        failsafeValues = raw.fsChVal.toList(),
        stickMode = StickMode.fromSdk(raw.stickMode),
        preFlightCheck = raw.preFlightCheckChannel.toList(),
        rfModuleMode = RfModuleMode.fromSdk(raw.rfModuleMode),
        alarmVoltageDeciVolt = raw.alramVtg,
        stickBeep = raw.joytickBeep,
        modelMatchId = raw.modelMatchId,
        crsfBaudRate = raw.crsfBaudRate,
    )

    /** The system struct for the raw [fields] of a model file (arrays copied). */
    fun rawSysCfg(fields: RawSystemFields): RawSysCfg = RawSysCfg().apply {
        fsMode = fields.failsafeMode
        fsChVal = fields.failsafeValues.toIntArray()
        stickMode = fields.stickMode
        preFlightCheckChannel = fields.preFlightCheck.toIntArray()
        rfModuleMode = fields.rfModuleMode
        alramVtg = fields.alarmVoltageDeciVolt
        joytickBeep = fields.stickBeep
        modelMatchId = fields.modelMatchId
        crsfBaudRate = fields.crsfBaudRate
    }

    /** The raw system fields of [raw] (arrays copied), for the plausibility check and the model-file comparison. */
    fun rawSystemFields(raw: RawSysCfg): RawSystemFields = RawSystemFields(
        failsafeMode = raw.fsMode,
        failsafeValues = raw.fsChVal.toList(),
        stickMode = raw.stickMode,
        preFlightCheck = raw.preFlightCheckChannel.toList(),
        rfModuleMode = raw.rfModuleMode,
        alarmVoltageDeciVolt = raw.alramVtg,
        stickBeep = raw.joytickBeep,
        modelMatchId = raw.modelMatchId,
        crsfBaudRate = raw.crsfBaudRate,
    )

    private fun byteOk(wanted: Int, fresh: Int): Boolean = wanted == fresh || wanted in SdkAssumptions.UINT8_RANGE

    /**
     * Overlays [config] onto [raw] (fresh `rcGetRcSysConfig()`). The arrays must have exactly the
     * SDK lengths; pre-flight presets must be −100..100 or [PREFLIGHT_NO_CHECK], failsafe values
     * must fit `int8_t`, the `uint8_t` members 0..255 (an unchanged value always passes).
     */
    fun overlaySystemConfig(raw: RawSysCfg, config: SystemConfig): Boolean {
        if (raw.fsChVal.size != RawSizes.FAILSAFE_CHANNELS || raw.preFlightCheckChannel.size != RawSizes.PREFLIGHT_SOURCES) {
            return false
        }
        if (config.failsafeValues.size != RawSizes.FAILSAFE_CHANNELS) return false
        if (config.preFlightCheck.size != RawSizes.PREFLIGHT_SOURCES) return false
        for (i in config.failsafeValues.indices) {
            val value = config.failsafeValues[i]
            if (value != raw.fsChVal[i] && value !in SdkAssumptions.INT8_RANGE) return false
        }
        for (i in config.preFlightCheck.indices) {
            val value = config.preFlightCheck[i]
            if (value != raw.preFlightCheckChannel[i] && value != PREFLIGHT_NO_CHECK && value !in PERCENT_MIN..PERCENT_MAX) {
                return false
            }
        }
        if (!byteOk(config.alarmVoltageDeciVolt, raw.alramVtg)) return false
        if (!byteOk(config.stickBeep, raw.joytickBeep)) return false
        if (!byteOk(config.modelMatchId, raw.modelMatchId)) return false
        if (!byteOk(config.crsfBaudRate, raw.crsfBaudRate)) return false

        if (FailsafeMode.fromSdk(raw.fsMode) != config.failsafeMode) raw.fsMode = config.failsafeMode.sdk
        for (i in config.failsafeValues.indices) raw.fsChVal[i] = config.failsafeValues[i]
        if (StickMode.fromSdk(raw.stickMode) != config.stickMode) raw.stickMode = config.stickMode.sdk
        for (i in config.preFlightCheck.indices) raw.preFlightCheckChannel[i] = config.preFlightCheck[i]
        if (RfModuleMode.fromSdk(raw.rfModuleMode) != config.rfModuleMode) raw.rfModuleMode = config.rfModuleMode.sdk
        raw.alramVtg = config.alarmVoltageDeciVolt
        raw.joytickBeep = config.stickBeep
        raw.modelMatchId = config.modelMatchId
        raw.crsfBaudRate = config.crsfBaudRate
        return true
    }

    // ---------------------------------------------------------------------------------------------
    // Models
    // ---------------------------------------------------------------------------------------------

    fun modelInfo(raw: RawModel): ModelInfo = ModelInfo(
        name = raw.modelName,
        image = raw.modelImg,
        filePath = raw.filePath,
        index = raw.modelIdx,
        modelMatch = raw.modelMatch,
    )

    /**
     * The raw struct for [model]. Every field of `RcModelDataUI` is mirrored losslessly by
     * [ModelInfo], so no fresh read is needed. Null if a value cannot be represented: a model
     * match outside the unsigned 32-bit range, an empty file path or a NUL character in a string.
     */
    fun rawModel(model: ModelInfo): RawModel? {
        if (model.modelMatch !in SdkAssumptions.MODEL_MATCH_RANGE) return null
        if (!isUsablePath(model.filePath)) return null
        if (model.name.indexOf('\u0000') >= 0 || model.image.indexOf('\u0000') >= 0) return null
        return RawModel().apply {
            modelName = model.name
            modelImg = model.image
            filePath = model.filePath
            modelIdx = model.index
            modelMatch = model.modelMatch
        }
    }

    /** A path that may be handed to the SDK: not blank and without NUL characters. */
    fun isUsablePath(path: String): Boolean = path.isNotBlank() && path.indexOf('\u0000') < 0

    /** A name (template name, …) that may be handed to the SDK. */
    fun isUsableName(name: String): Boolean = name.isNotBlank() && name.indexOf('\u0000') < 0

    // ---------------------------------------------------------------------------------------------
    // Firmware info
    // ---------------------------------------------------------------------------------------------

    fun firmwareInfo(raw: RawFwInfo): FirmwareInfo = FirmwareInfo(
        systemId = raw.SysId,
        deviceId = raw.DevId,
        flightTimeSec = raw.FlyTime,
        buildDate = raw.BuildTime,
        firmwareVersion = raw.VER_FW,
        hardwareVersion = raw.VER_HW,
        deviceText = raw.DevText,
        firmwareVersionText = raw.FwVerText,
        buildTimeText = raw.BulidTimeText,
        cachedFirmwareVersionText = raw.cacheFwFileVerTxt,
    )

    // ---------------------------------------------------------------------------------------------
    // Telemetry
    // ---------------------------------------------------------------------------------------------

    fun telemetryItem(raw: RawTelemetry): TelemetryItem = TelemetryItem(
        hash = raw.hashValue,
        index = raw.idx,
        name = raw.notifyName,
        displayName = raw.nameTxt,
        value = raw.currValue,
        valueText = raw.valTxt,
        unit = raw.valueUnit,
        showInUi = raw.showUI != 0,
    )

    fun telemetryAlarm(raw: RawTelemetry): TelemetryAlarm = TelemetryAlarm(
        hash = raw.hashValue,
        index = raw.idx,
        popup = raw.popupWindow != 0,
        trigger = CompareOp.fromSdk(raw.triggerType),
        intervalSec = raw.intervalSec,
        triggerValue = raw.triggerValue,
        unit = raw.valueUnit,
        name = raw.notifyName,
        text = raw.notifyText,
        audioFile = raw.audioFile,
        valueText = raw.valTxt,
        displayName = raw.nameTxt,
        value = raw.currValue,
        showInUi = raw.showUI != 0,
    )

    /**
     * Overlays the configuration fields of [alarm] onto [raw]: the watched hash, popup, trigger,
     * interval, trigger value, unit, name, text, audio file and the show flag. `valTxt`,
     * `nameTxt`, `currValue` and `idx` stay as they are in [raw] — a fresh
     * `rcGetNotifyCfgItem` when modifying, a blank struct when adding.
     */
    fun overlayTelemetryAlarm(raw: RawTelemetry, alarm: TelemetryAlarm): Boolean {
        if (alarm.hash == 0L) return false
        if (alarm.intervalSec < 0) return false
        if (!alarm.triggerValue.isFinite()) return false
        if (alarm.name.indexOf('\u0000') >= 0 || alarm.text.indexOf('\u0000') >= 0 || alarm.audioFile.indexOf('\u0000') >= 0) {
            return false
        }
        raw.hashValue = alarm.hash
        if ((raw.popupWindow != 0) != alarm.popup) raw.popupWindow = if (alarm.popup) 1 else 0
        if (CompareOp.fromSdk(raw.triggerType) != alarm.trigger) raw.triggerType = alarm.trigger.sdk
        raw.intervalSec = alarm.intervalSec
        raw.triggerValue = alarm.triggerValue
        raw.valueUnit = alarm.unit
        raw.notifyName = alarm.name
        raw.notifyText = alarm.text
        raw.audioFile = alarm.audioFile
        if ((raw.showUI != 0) != alarm.showInUi) raw.showUI = if (alarm.showInUi) 1 else 0
        return true
    }
}
