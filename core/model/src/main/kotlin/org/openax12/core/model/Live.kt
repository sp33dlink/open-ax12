package org.openax12.core.model

import kotlinx.serialization.Serializable

/**
 * Mirror of `RadioStatusUI`: basic transmitter and link status, refreshed with every live frame.
 *
 * @property linkQuality `rfLQ`: link quality in percent as reported by the receiver
 * @property rssiDbm `rfLDBM`: signal strength in dBm
 * @property lostCounter `rfLLostCnt`: firmware loss counter; 0 means the receiver is lost
 * @property txVoltage `rcVtg`: transmitter battery voltage in volts
 * @property rxVoltage `rxVtg`: voltage reported by the receiver / aircraft in volts
 * @property topKeyState `topKeyState`: state of the top keys (firmware-defined bit field)
 * @property calibrationState `adcSetState`: stick (ADC) calibration state; 0 = not calibrating
 */
@Serializable
data class RadioStatus(
    val linkQuality: Int = 0,
    val rssiDbm: Int = 0,
    val lostCounter: Int = 0,
    val txVoltage: Float = 0f,
    val rxVoltage: Float = 0f,
    val topKeyState: Int = 0,
    val calibrationState: Int = 0,
) {
    /** The receiver link is up (LQ > 0). Used for the "linked" confirmations. */
    val isLinkUp: Boolean get() = linkQuality > 0

    /** True while the stick calibration runs. */
    val isCalibrating: Boolean get() = calibrationState != 0

    companion object {
        /** All zero: no link, no voltages. */
        val EMPTY = RadioStatus()
    }
}

/**
 * Latest known state of one timer or counter, assembled by the radio backend from the timer /
 * counter report and notification messages.
 *
 * The firmware has **no manual start / stop command**: a timer runs while its configured switch
 * condition (`TimerConfig.switch`) holds; the only command is reset (`Radio.resetTimer`). The
 * home screen therefore offers reset (long-press) and a shortcut to the timer settings (tap),
 * nothing else.
 *
 * @property index in-group index 0..2
 * @property isCounter false = timer (seconds), true = counter (count value)
 * @property count seconds (timer) or current value (counter) as it should be displayed
 * @property showsElapsed true if [count] is the elapsed amount, false if it is the remainder
 * @property startTime 0 = counting up, > 0 = countdown period / counter maximum
 * @property running true while the timer runs (from the last notification / changing reports)
 */
@Serializable
data class TimerState(
    val index: Int,
    val isCounter: Boolean = false,
    val count: Int = 0,
    val showsElapsed: Boolean = true,
    val startTime: Int = 0,
    val running: Boolean = false,
) {
    /** Device timer number as used by `Radio.resetTimer`: timers 0..2, counters 3..5. */
    val deviceIndex: Int get() = if (isCounter) index + TIMER_COUNT else index
}

/**
 * One immutable frame of live radio data (about 25 per second).
 *
 * Built by the `Radio` implementation after every `RC_MSGID_MIXOUT_UPDATE`. To keep this cheap
 * the frame wraps primitive arrays **without copying**: whoever constructs a snapshot hands the
 * arrays over and must never touch them again. [trims], [status], [timers] and [counters] are
 * reused from the previous frame when unchanged.
 *
 * Equality is identity: two frames are never "equal", so a `StateFlow<LiveSnapshot>` emits every
 * frame (conflated). Do not put a snapshot into a data class that is compared.
 *
 * All percentages are −100..+100. Out-of-range indices return 0 instead of throwing so that UI
 * code can render before the first frame arrived.
 *
 * @property sequence frame counter, strictly increasing per radio backend; 0 = [EMPTY]
 * @property timestampMs monotonic time of the frame (`SystemClock.elapsedRealtime()` base)
 */
class LiveSnapshot(
    val sequence: Long,
    val timestampMs: Long,
    private val sourceRaw: IntArray,
    private val sourceLogic: IntArray,
    private val sourceOutput: IntArray,
    private val channelOutput: IntArray,
    private val channelLogic: FloatArray,
    val trims: Trims = Trims.ZERO,
    val status: RadioStatus = RadioStatus.EMPTY,
    val timers: List<TimerState> = DEFAULT_TIMERS,
    val counters: List<TimerState> = DEFAULT_COUNTERS,
) {
    /** Number of sources in this frame (normally [SOURCE_COUNT]). */
    val sourceCount: Int get() = sourceLogic.size

    /** Number of output channels in this frame (normally [CHANNEL_COUNT]). */
    val channelCount: Int get() = channelOutput.size

    /** True for the placeholder frame used before the radio delivered data. */
    val isEmpty: Boolean get() = sequence == 0L

    /** `rawVal` of a source: half-scale raw value. */
    fun raw(source: SourceId): Int = sourceRaw.getOrElse(source.index) { 0 }

    /** `rawLogicVal` of a source in percent; switches report −100 / 0 / +100. */
    fun logic(source: SourceId): Int = sourceLogic.getOrElse(source.index) { 0 }

    /** `outputVal` of a source in percent (after the input weight). */
    fun output(source: SourceId): Int = sourceOutput.getOrElse(source.index) { 0 }

    /** [logic] by raw SDK index. */
    fun logicAt(sourceIndex: Int): Int = sourceLogic.getOrElse(sourceIndex) { 0 }

    /** `chOut` of output channel [index] (0-based) in percent. */
    fun channel(index: Int): Int = channelOutput.getOrElse(index) { 0 }

    /** `logicVal` of output channel [index] (0-based). */
    fun channelLogic(index: Int): Float = channelLogic.getOrElse(index) { 0f }

    /** Position of a switch source derived from its logic value. */
    fun switchPosition(source: SourceId): SwitchPosition = SwitchPosition.fromLogic(logic(source))

    /** State of timer [index] (0..2), or null if out of range. */
    fun timer(index: Int): TimerState? = timers.getOrNull(index)

    /** State of counter [index] (0..2), or null if out of range. */
    fun counter(index: Int): TimerState? = counters.getOrNull(index)

    /** Copy of all source logic values (allocates; for logging / tests, not for per-frame UI). */
    fun logicValues(): IntArray = sourceLogic.copyOf()

    /** Copy of all channel output values (allocates; for logging / tests, not for per-frame UI). */
    fun channelValues(): IntArray = channelOutput.copyOf()

    override fun toString(): String = "LiveSnapshot(#$sequence @$timestampMs)"

    companion object {
        /** Stopped timers at zero, used until the radio reported. */
        val DEFAULT_TIMERS: List<TimerState> = List(TIMER_COUNT) { TimerState(index = it, isCounter = false) }
        /** Counters at zero, used until the radio reported. */
        val DEFAULT_COUNTERS: List<TimerState> = List(COUNTER_COUNT) { TimerState(index = it, isCounter = true) }

        /** Placeholder frame: everything zero, [sequence] 0. */
        val EMPTY: LiveSnapshot = LiveSnapshot(
            sequence = 0,
            timestampMs = 0,
            sourceRaw = IntArray(SOURCE_COUNT),
            sourceLogic = IntArray(SOURCE_COUNT),
            sourceOutput = IntArray(SOURCE_COUNT),
            channelOutput = IntArray(CHANNEL_COUNT),
            channelLogic = FloatArray(CHANNEL_COUNT),
        )

        /**
         * Convenience builder for tests, previews and the simulator panel: a frame with the given
         * source logic values (raw = logic / 2, output = logic) and channel outputs.
         */
        fun of(
            sequence: Long = 1,
            timestampMs: Long = 0,
            logic: Map<SourceId, Int> = emptyMap(),
            channels: List<Int> = emptyList(),
            trims: Trims = Trims.ZERO,
            status: RadioStatus = RadioStatus.EMPTY,
            timers: List<TimerState> = DEFAULT_TIMERS,
            counters: List<TimerState> = DEFAULT_COUNTERS,
        ): LiveSnapshot {
            val logicArray = IntArray(SOURCE_COUNT)
            for ((source, value) in logic) if (source.isValid) logicArray[source.index] = value
            val channelArray = IntArray(CHANNEL_COUNT) { channels.getOrElse(it) { 0 } }
            return LiveSnapshot(
                sequence = sequence,
                timestampMs = timestampMs,
                sourceRaw = IntArray(SOURCE_COUNT) { logicArray[it] / 2 },
                sourceLogic = logicArray,
                sourceOutput = logicArray.copyOf(),
                channelOutput = channelArray,
                channelLogic = FloatArray(CHANNEL_COUNT) { channelArray[it].toFloat() },
                trims = trims,
                status = status,
                timers = timers,
                counters = counters,
            )
        }
    }
}

/**
 * A condition on one input source, the common shape of every SDK "enable switch" triple
 * (`switchIdx` / `swCmpType` / `swCmpVal`). Compared against the source's logic value in percent.
 *
 * Examples: `SwitchCondition(SourceId.SA, CompareOp.EQUAL, 100)` = "SA down";
 * `SwitchCondition(SourceId.THR, CompareOp.GREATER, -90)` = "throttle above −90 %".
 */
@Serializable
data class SwitchCondition(
    val source: SourceId = SourceId(0),
    val op: CompareOp = CompareOp.DISABLED,
    val value: Int = 0,
) {
    /** False for [CompareOp.DISABLED] (the owning item is not gated). */
    val isEnabled: Boolean get() = op != CompareOp.DISABLED

    /**
     * Evaluates the condition against a logic value in percent. Returns null when the condition
     * is [CompareOp.DISABLED]. [CompareOp.EQUAL] tolerates ±[EQUAL_TOLERANCE] so that switch
     * positions match despite rounding.
     */
    fun test(logic: Int): Boolean? = when (op) {
        CompareOp.DISABLED -> null
        CompareOp.GREATER -> logic > value
        CompareOp.EQUAL -> logic >= value - EQUAL_TOLERANCE && logic <= value + EQUAL_TOLERANCE
        CompareOp.LESS -> logic < value
    }

    /** [test] against the live value of [source]; null when disabled. */
    fun evaluate(live: LiveSnapshot): Boolean? = test(live.logic(source))

    /**
     * True if the gated item is active for [live]. A disabled condition yields [whenDisabled]
     * (mixes and dual rates without a switch are always active → default `true`).
     */
    fun isActive(live: LiveSnapshot, whenDisabled: Boolean = true): Boolean = evaluate(live) ?: whenDisabled

    companion object {
        /** Tolerance in percent applied to [CompareOp.EQUAL]. */
        const val EQUAL_TOLERANCE: Int = 2

        /** The disabled condition. */
        val NONE = SwitchCondition()

        /** "Switch [source] is at [position]". */
        fun at(source: SourceId, position: SwitchPosition): SwitchCondition =
            SwitchCondition(source, CompareOp.EQUAL, position.logic)
    }
}

/**
 * States of the 32 app-side logical switches packed into one value (bit n = L(n+1)).
 * Cheap to produce every tick and to compare.
 */
@Serializable
@JvmInline
value class LogicalSwitchStates(val bits: Long = 0L) {

    /** State of logical switch [index] (0-based); false if out of range. */
    operator fun get(index: Int): Boolean = index in 0 until LOGICAL_SWITCH_COUNT && (bits shr index) and 1L == 1L

    /** Copy with switch [index] set to [on]. */
    fun with(index: Int, on: Boolean): LogicalSwitchStates {
        if (index !in 0 until LOGICAL_SWITCH_COUNT) return this
        val mask = 1L shl index
        return LogicalSwitchStates(if (on) bits or mask else bits and mask.inv())
    }

    /** Indices of all switches that are on. */
    fun activeIndices(): List<Int> = (0 until LOGICAL_SWITCH_COUNT).filter { this[it] }

    companion object {
        /** All switches off. */
        val NONE = LogicalSwitchStates(0L)
    }
}
