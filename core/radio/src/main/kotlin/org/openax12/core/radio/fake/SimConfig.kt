package org.openax12.core.radio.fake

import kotlinx.serialization.Serializable
import org.openax12.core.model.CHANNEL_COUNT
import org.openax12.core.model.COUNTER_COUNT
import org.openax12.core.model.CURVE_COUNT
import org.openax12.core.model.CURVE_MAX_POINTS
import org.openax12.core.model.CompareOp
import org.openax12.core.model.CurveConfig
import org.openax12.core.model.CurveType
import org.openax12.core.model.DR_SLOTS
import org.openax12.core.model.DualRateSlot
import org.openax12.core.model.MixLine
import org.openax12.core.model.MixMode
import org.openax12.core.model.ModelInfo
import org.openax12.core.model.OutputChannelConfig
import org.openax12.core.model.PREFLIGHT_NO_CHECK
import org.openax12.core.model.PREFLIGHT_SOURCE_COUNT
import org.openax12.core.model.SOURCE_COUNT
import org.openax12.core.model.SourceId
import org.openax12.core.model.SourceInfo
import org.openax12.core.model.SourceKind
import org.openax12.core.model.Sources
import org.openax12.core.model.SwitchCondition
import org.openax12.core.model.SwitchPosition
import org.openax12.core.model.SystemConfig
import org.openax12.core.model.TIMER_COUNT
import org.openax12.core.model.TelemetryAlarm
import org.openax12.core.model.TimerAlertMode
import org.openax12.core.model.TimerConfig
import org.openax12.core.model.Trims

/**
 * A complete simulated radio configuration: what the real SDK keeps as its working config, what
 * the simulated MCU "flies" with, and what a simulated model file stores. Immutable — every
 * change produces a copy, so "save to device" and "read from device" are plain assignments.
 *
 * Live fields of the embedded model types ([SourceInfo.raw], [OutputChannelConfig.output],
 * [OutputChannelConfig.mixCount], [MixLine.mixCount], [TimerConfig.count], …) are not maintained
 * here; `SimulatorCore` fills them in when a getter is called.
 *
 * @property mixes mix lines per channel in evaluation order
 * @property dualRates [DR_SLOTS] slots per channel
 * @property system system configuration. Stick mode, RF module mode and alarm voltage are
 *   radio-wide: loading a model file keeps the radio's values and takes only the pre-flight
 *   presets from the file.
 * @property trims trim positions of the model (hardware-side: changed by the trim buttons)
 * @property telemetryAlarms SDK-side telemetry notifications
 */
@Serializable
internal data class SimConfig(
    val sources: List<SourceInfo> = SimDefaults.sources(),
    val channels: List<OutputChannelConfig> = List(CHANNEL_COUNT) { OutputChannelConfig(channel = it) },
    val mixes: List<List<MixLine>> = List(CHANNEL_COUNT) { emptyList() },
    val curves: List<CurveConfig> = List(CURVE_COUNT) { CurveConfig.empty(it) },
    val dualRates: List<List<DualRateSlot>> = List(CHANNEL_COUNT) { ch -> List(DR_SLOTS) { DualRateSlot(ch, it) } },
    val timers: List<TimerConfig> = List(TIMER_COUNT) { TimerConfig(index = it) },
    val counters: List<TimerConfig> = List(COUNTER_COUNT) { TimerConfig(index = it, startTime = SimDefaults.COUNTER_MAX) },
    val system: SystemConfig = SimDefaults.system(),
    val trims: Trims = Trims.ZERO,
    val telemetryAlarms: List<TelemetryAlarm> = emptyList(),
) {

    /**
     * A copy with every list brought to its fixed size and every embedded index made consistent
     * with its position. Applied to everything read from a file, so that a damaged or hand-edited
     * model file can never produce out-of-range accesses in the mixer.
     */
    fun normalized(): SimConfig {
        val defaults = SimDefaults.sources()
        val validCurve = { index: Int -> if (index in 0 until CURVE_COUNT) index else org.openax12.core.model.NO_CURVE }
        val validCondition = { c: SwitchCondition -> if (c.source.isValid) c else SwitchCondition.NONE }
        return copy(
            sources = List(SOURCE_COUNT) { i ->
                (sources.firstOrNull { it.index == i } ?: defaults[i]).copy(index = i, raw = 0, logic = 0, output = 0)
            },
            channels = List(CHANNEL_COUNT) { ch ->
                val c = channels.firstOrNull { it.channel == ch } ?: OutputChannelConfig(channel = ch)
                c.copy(
                    channel = ch,
                    curve = validCurve(c.curve),
                    lock = validCondition(c.lock),
                    mixCount = 0,
                    output = 0,
                    logic = 0f,
                    dualRates = emptyList(),
                )
            },
            mixes = List(CHANNEL_COUNT) { ch ->
                mixes.getOrNull(ch).orEmpty()
                    .filter { it.source.isValid }
                    .distinctBy { it.source }
                    .take(SimDefaults.MAX_MIXES_PER_CHANNEL)
                    .mapIndexed { i, line ->
                        line.copy(
                            channel = ch,
                            mixIndex = i,
                            curve = validCurve(line.curve),
                            switch = validCondition(line.switch),
                            sourceName = "",
                            sourceType = 0,
                            mixCount = 0,
                        )
                    }
            },
            curves = List(CURVE_COUNT) { i ->
                val c = curves.firstOrNull { it.index == i } ?: CurveConfig.empty(i)
                c.copy(
                    index = i,
                    pointCount = c.pointCount.coerceIn(0, CURVE_MAX_POINTS),
                    x = List(CURVE_MAX_POINTS) { c.x.getOrElse(it) { 0 } },
                    y = List(CURVE_MAX_POINTS) { c.y.getOrElse(it) { 0 } },
                )
            },
            dualRates = List(CHANNEL_COUNT) { ch ->
                List(DR_SLOTS) { slot ->
                    val d = dualRates.getOrNull(ch)?.getOrNull(slot) ?: DualRateSlot(ch, slot)
                    d.copy(channel = ch, slot = slot, count = 0, switch = validCondition(d.switch))
                }
            },
            timers = List(TIMER_COUNT) { i -> normalizedTimer(timers.getOrNull(i), i, isCounter = false) },
            counters = List(COUNTER_COUNT) { i -> normalizedTimer(counters.getOrNull(i), i, isCounter = true) },
            system = system.copy(
                preFlightCheck = List(PREFLIGHT_SOURCE_COUNT) { system.preFlightCheck.getOrElse(it) { PREFLIGHT_NO_CHECK } },
            ),
            telemetryAlarms = telemetryAlarms.filter { it.hash != 0L }.mapIndexed { i, alarm -> alarm.copy(index = i) },
        )
    }

    private fun normalizedTimer(config: TimerConfig?, index: Int, isCounter: Boolean): TimerConfig {
        val c = config ?: TimerConfig(index = index, startTime = if (isCounter) SimDefaults.COUNTER_MAX else 0)
        return c.copy(
            index = index,
            switch = if (c.switch.source.isValid) c.switch else SwitchCondition.NONE,
            resetSwitch = if (c.resetSwitch.source.isValid) c.resetSwitch else SwitchCondition.NONE,
            runningDirection = 0,
            runningStartTime = 0,
            count = 0,
            notifyCode = 0,
        )
    }
}

/**
 * Content of a simulated model file (`models/model_<n>.json`) and of a simulated template file.
 * The real SDK's model files have their own, undocumented format; nothing outside the simulator
 * interprets either of them.
 *
 * @property format fixed marker, see [FORMAT]; a file without it is not a simulator model
 */
@Serializable
internal data class SimModelFile(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val name: String = "",
    val image: String = "",
    val index: Long = 0,
    val modelMatch: Long = 0,
    val config: SimConfig = SimConfig(),
) {
    /** Metadata of this file as the `Radio` interface reports it. */
    fun info(filePath: String): ModelInfo =
        ModelInfo(name = name, image = image, filePath = filePath, index = index, modelMatch = modelMatch)

    companion object {
        const val FORMAT = "open-ax12-simulator-model"
        const val VERSION = 1
    }
}

/**
 * The simulator's own persistent state (`simulator.json`): what a real radio keeps in the MCU and
 * the SDK keeps besides its model files.
 *
 * @property currentModelFile file name (inside `models/`) of the current model, or null
 * @property lastModelIndex highest `modelIdx` handed out so far
 * @property device the configuration the simulated MCU holds; null before the first start
 * @property flightTimeSec total simulated flight time reported in the firmware info
 * @property elrsValues values of the simulated ELRS module parameters by menu item index (the
 *   module keeps them itself, independent of the model)
 */
@Serializable
internal data class SimStateFile(
    val version: Int = 1,
    val currentModelFile: String? = null,
    val lastModelIndex: Long = 0,
    val device: SimConfig? = null,
    val flightTimeSec: Long = 0,
    val elrsValues: Map<Int, Int> = emptyMap(),
)

/** Factory defaults of the simulated radio. */
internal object SimDefaults {

    /**
     * Mixes a channel can hold. The real limit is unknown (assumption 6); the simulator uses a
     * generous one and offers `SimulatedFault.ADD_MIX_FAILS` to exercise the failure path.
     */
    const val MAX_MIXES_PER_CHANNEL = 16

    /** Default maximum of a counter (`startTime` of a counter must not be 0). */
    const val COUNTER_MAX = 10

    /** Low-voltage alarm threshold of a fresh radio, in 0.1 V. */
    const val ALARM_VOLTAGE_DECIVOLT = 68

    /** `srcType` values the simulator reports (firmware-defined on the real radio). */
    const val TYPE_STICK = 0
    const val TYPE_POT = 1
    const val TYPE_SLIDER = 2
    const val TYPE_SWITCH_2POS = 3
    const val TYPE_SWITCH_3POS = 4
    const val TYPE_COUNTER = 5

    /** Switches with two positions; all other enabled switches have three. */
    private val TWO_POSITION = setOf(Sources.SF, Sources.SH)

    /** Sources that exist on the simulated transmitter. */
    private fun isPresent(index: Int): Boolean = when (index) {
        in Sources.STICKS -> true
        in Sources.P1..Sources.P4 -> true
        in Sources.S1..Sources.S2 -> true
        in Sources.SA..Sources.SH -> true
        in Sources.CNT1..Sources.CNT6 -> true
        else -> false
    }

    /** True if [index] is a switch with only two positions (up / down). */
    fun isTwoPosition(index: Int): Boolean = index in TWO_POSITION

    /** The 42 sources of a fresh radio with default names. */
    fun sources(): List<SourceInfo> = List(SOURCE_COUNT) { i ->
        SourceInfo(
            index = i,
            name = Sources.defaultName(i),
            type = when (Sources.kindOf(i)) {
                SourceKind.STICK -> TYPE_STICK
                SourceKind.POT -> TYPE_POT
                SourceKind.SLIDER -> TYPE_SLIDER
                SourceKind.SWITCH -> if (isTwoPosition(i)) TYPE_SWITCH_2POS else TYPE_SWITCH_3POS
                SourceKind.COUNTER -> TYPE_COUNTER
            },
            enabled = isPresent(i),
            weight = 100,
        )
    }

    /** System configuration of a fresh radio: mode 2, internal module, throttle must be low before flight. */
    fun system(): SystemConfig = SystemConfig(alarmVoltageDeciVolt = ALARM_VOLTAGE_DECIVOLT)
        .withPreFlightPreset(SourceId.THR, -100)

    /** Rest position of a physical input: throttle and switches low / up, everything else centred. */
    fun restPosition(index: Int): Int = when {
        index == Sources.THR -> -100
        index in Sources.SWITCHES -> SwitchPosition.UP.logic
        else -> 0
    }

    /**
     * The model a fresh simulator starts with: a 4-channel plane (CH1 aileron, CH2 elevator,
     * CH3 throttle, CH4 rudder) with a little of everything, so that no screen is empty:
     * expo on aileron and elevator, a 70 % dual rate on SB down, throttle cut on SF down,
     * CH5 on the 3-position switch SA, CH6 on slider S1, a flight timer, a 5-minute countdown
     * and a counter on SH.
     */
    fun plane(): SimConfig {
        val base = SimConfig()
        val expo = 0
        val throttleRunning = SwitchCondition(SourceId.THR, CompareOp.GREATER, -90)
        val lowRates = SwitchCondition.at(SourceId.SB, SwitchPosition.DOWN)

        fun line(channel: Int, source: Int, curve: Int = org.openax12.core.model.NO_CURVE) =
            MixLine(channel = channel, mixIndex = 0, source = SourceId(source), curve = curve, mode = MixMode.ADD)

        val mixes = base.mixes.toMutableList()
        mixes[0] = listOf(line(0, Sources.AIL, expo))
        mixes[1] = listOf(line(1, Sources.ELE, expo))
        mixes[2] = listOf(line(2, Sources.THR))
        mixes[3] = listOf(line(3, Sources.RUD))
        mixes[4] = listOf(line(4, Sources.SA))
        mixes[5] = listOf(line(5, Sources.S1))

        val curves = base.curves.toMutableList()
        curves[expo] = CurveConfig.parametric(expo, CurveType.EXPO, 30)

        val dualRates = base.dualRates.toMutableList()
        dualRates[0] = dualRates[0].toMutableList().also { it[0] = DualRateSlot(0, 0, switch = lowRates, weight = 70) }
        dualRates[1] = dualRates[1].toMutableList().also { it[0] = DualRateSlot(1, 0, switch = lowRates, weight = 70) }

        val channels = base.channels.toMutableList()
        channels[2] = channels[2].copy(
            lock = SwitchCondition.at(SourceId.SF, SwitchPosition.DOWN),
            lockValue = -100,
        )

        val timers = base.timers.toMutableList()
        timers[0] = TimerConfig(index = 0, switch = throttleRunning, startTime = 0, countUp = true)
        timers[1] = TimerConfig(
            index = 1,
            switch = throttleRunning,
            startTime = 300,
            countUp = false,
            alertMode = TimerAlertMode.BEEPS,
            alertIntervalSec = 30,
        )

        val counters = base.counters.toMutableList()
        counters[0] = TimerConfig(
            index = 0,
            switch = SwitchCondition.at(SourceId.SH, SwitchPosition.DOWN),
            startTime = COUNTER_MAX,
            countUp = true,
            outputMin = -100,
            outputMax = 100,
        )

        return base.copy(
            channels = channels,
            mixes = mixes,
            curves = curves,
            dualRates = dualRates,
            timers = timers,
            counters = counters,
        ).normalized()
    }

    /**
     * What `rcResetDefault` leaves behind: the four sticks straight through on CH1–CH4 (AETR
     * order as in [plane]) and nothing else.
     */
    fun factory(): SimConfig {
        val base = SimConfig()
        fun line(channel: Int, source: Int) = MixLine(channel = channel, source = SourceId(source), mode = MixMode.ADD)
        val mixes = base.mixes.toMutableList()
        mixes[0] = listOf(line(0, Sources.AIL))
        mixes[1] = listOf(line(1, Sources.ELE))
        mixes[2] = listOf(line(2, Sources.THR))
        mixes[3] = listOf(line(3, Sources.RUD))
        return base.copy(mixes = mixes).normalized()
    }

    /**
     * A flybarless helicopter: cyclic on CH1 / CH2, throttle on CH3 through a throttle curve,
     * tail on CH4, gyro gain on CH5 (slider S1), collective pitch on CH6 (throttle stick),
     * throttle hold on SF down.
     */
    fun helicopter(): SimConfig {
        val base = SimConfig()
        val throttleCurve = 0
        fun line(channel: Int, source: Int, curve: Int = org.openax12.core.model.NO_CURVE) =
            MixLine(channel = channel, source = SourceId(source), curve = curve, mode = MixMode.ADD)
        val mixes = base.mixes.toMutableList()
        mixes[0] = listOf(line(0, Sources.AIL))
        mixes[1] = listOf(line(1, Sources.ELE))
        mixes[2] = listOf(line(2, Sources.THR, throttleCurve))
        mixes[3] = listOf(line(3, Sources.RUD))
        mixes[4] = listOf(line(4, Sources.S1))
        mixes[5] = listOf(line(5, Sources.THR))
        val curves = base.curves.toMutableList()
        curves[throttleCurve] = CurveConfig.custom(
            throttleCurve,
            listOf(
                org.openax12.core.model.CurvePoint(-100, -100),
                org.openax12.core.model.CurvePoint(-50, 0),
                org.openax12.core.model.CurvePoint(0, 40),
                org.openax12.core.model.CurvePoint(50, 60),
                org.openax12.core.model.CurvePoint(100, 100),
            ),
        )
        val channels = base.channels.toMutableList()
        channels[2] = channels[2].copy(lock = SwitchCondition.at(SourceId.SF, SwitchPosition.DOWN), lockValue = -100)
        val timers = base.timers.toMutableList()
        timers[0] = TimerConfig(index = 0, switch = SwitchCondition.at(SourceId.SF, SwitchPosition.UP))
        return base.copy(mixes = mixes, curves = curves, channels = channels, timers = timers).normalized()
    }

    /** A simple multirotor: AETR on CH1–CH4, arm on SF (CH5), flight mode on SA (CH6), beeper on SH (CH7). */
    fun multirotor(): SimConfig {
        val base = SimConfig()
        fun line(channel: Int, source: Int) = MixLine(channel = channel, source = SourceId(source), mode = MixMode.ADD)
        val mixes = base.mixes.toMutableList()
        mixes[0] = listOf(line(0, Sources.AIL))
        mixes[1] = listOf(line(1, Sources.ELE))
        mixes[2] = listOf(line(2, Sources.THR))
        mixes[3] = listOf(line(3, Sources.RUD))
        mixes[4] = listOf(line(4, Sources.SF))
        mixes[5] = listOf(line(5, Sources.SA))
        mixes[6] = listOf(line(6, Sources.SH))
        val timers = base.timers.toMutableList()
        timers[0] = TimerConfig(index = 0, switch = SwitchCondition.at(SourceId.SF, SwitchPosition.DOWN))
        return base.copy(mixes = mixes, timers = timers).normalized()
    }

    /** A 2-servo-wing glider with V-tail: ailerons on CH1 / CH5, V-tail on CH2 / CH4, motor on CH3. */
    fun glider(): SimConfig {
        val base = SimConfig()
        fun line(channel: Int, source: Int, weight: Int = 100) = MixLine(
            channel = channel,
            source = SourceId(source),
            mode = MixMode.ADD,
            weightUp = weight,
            weightDown = weight,
        )
        val mixes = base.mixes.toMutableList()
        mixes[0] = listOf(line(0, Sources.AIL))
        mixes[1] = listOf(line(1, Sources.ELE, 50), line(1, Sources.RUD, 50))
        mixes[2] = listOf(line(2, Sources.THR))
        mixes[3] = listOf(line(3, Sources.ELE, 50), line(3, Sources.RUD, -50))
        mixes[4] = listOf(line(4, Sources.AIL))
        val channels = base.channels.toMutableList()
        channels[2] = channels[2].copy(lock = SwitchCondition.at(SourceId.SF, SwitchPosition.DOWN), lockValue = -100)
        channels[4] = channels[4].copy(weight = -100)
        val timers = base.timers.toMutableList()
        timers[0] = TimerConfig(index = 0, switch = SwitchCondition(SourceId.THR, CompareOp.GREATER, -90))
        return base.copy(mixes = mixes, channels = channels, timers = timers).normalized()
    }
}
