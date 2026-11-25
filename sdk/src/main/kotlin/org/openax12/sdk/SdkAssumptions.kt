package org.openax12.sdk

import org.openax12.core.model.COUNTER_COUNT
import org.openax12.core.model.TIMER_COUNT

/**
 * Every assumption the SDK backend makes about behaviour that is **not proven on the AX12**:
 * encodings, counts, scalings and timeouts. Nothing of this kind may be hard-coded elsewhere in
 * `:sdk`; a device finding is applied by changing one value here.
 *
 * Mirrored in `docs/ASSUMPTIONS.md` — the number in each KDoc is the row there.
 */
internal object SdkAssumptions {

    // ---------------------------------------------------------------------------------------------
    // Startup and connection
    // ---------------------------------------------------------------------------------------------

    /**
     * (#10, #26) Name of the SDK's data directory below the app's private `filesDir`. The SDK
     * keeps its global configuration, model files and templates there; `TMPDIR` is redirected to
     * `<dir>/qt_tmp` for the serial-port lock file.
     */
    const val DATA_DIRECTORY_NAME: String = "rmAx"

    /**
     * (#27, measured on the AX12 2026-11-21) How long `start()` waits for
     * `RC_MSGID_GETDEVCFG_END`. The AX12 answers the first configuration read and starts live
     * frames about 12 s after `initialize()`, independent of when or how often the read is
     * requested (a device-info request does not shorten it either). Generous margin on purpose.
     */
    const val INITIAL_DEVICE_READ_TIMEOUT_MS: Long = 30_000

    /**
     * (#27) The initial read request is repeated after this time without a completion. On the
     * AX12 a request issued right after `initialize()` gets no answer; a repeated one is answered
     * once the device is ready. Repeating it is harmless.
     */
    const val INITIAL_DEVICE_READ_RETRY_AFTER_MS: Long = 5_000

    /**
     * (#28) A running connection counts as lost when no `RC_MSGID_MIXOUT_UPDATE` (normally every
     * 40 ms) arrived for this long and no device read / write is in progress. It recovers by
     * itself when frames come back.
     */
    const val LIVE_LOST_TIMEOUT_MS: Long = 5_000

    /** Period of the connection watchdog. */
    const val WATCHDOG_PERIOD_MS: Long = 1_000

    /**
     * (#24) Prefixes of serial devices the port probe looks at after a silent start, the same
     * families Qt's `QSerialPortInfo` enumerates. The port the SDK really uses is unknown (it
     * picks one from `QSerialPortInfo::availablePorts()`).
     */
    val SERIAL_DEVICE_PREFIXES: List<String> = listOf(
        "ttyS", "ttyHS", "ttyMSM", "ttyMT", "ttyO", "ttyUSB", "ttyACM", "ttyGS", "ttyMI", "ttymxc", "ttyAMA", "ttyTHS",
    )

    /** (#24) At most this many serial devices are probed. */
    const val SERIAL_PROBE_MAX_DEVICES: Int = 8

    // ---------------------------------------------------------------------------------------------
    // Indices
    // ---------------------------------------------------------------------------------------------

    /**
     * (#16, #29) Whether the `srcIdx` / `chIdx` / `idx` reported inside list elements may be
     * trusted over the element's position. They are used only when they form a permutation of
     * `0 until size` (see [SdkIndex.resolve]); otherwise the position counts. Set to `false` to
     * always use positions.
     */
    const val TRUST_REPORTED_INDICES: Boolean = true

    /**
     * (#21) SDK arguments for the timer / counter with in-group index [index]: this follows the
     * manual (in-group index 0..2 plus the `isCounter` flag), not the header comment
     * ("0-2 timers, 3-5 counters"). **The only place that knows this mapping.**
     *
     * @return `(timerIdx, isCounter)` for `rcGetTimerCfgData` / `rcSetTimerCfgData`; `timerIdx`
     *   is also what is written into `RcTimeCfgDataUI.timeIdx`
     */
    fun timerArguments(index: Int, isCounter: Boolean): Pair<Int, Int> = index to (if (isCounter) 1 else 0)

    /**
     * (#21) Interprets `RcTimeNotifyUI.timeIdx`: 0..2 = timer, 3..5 = counter (device numbering).
     * If the firmware reports in-group indices instead, notifications of counters cannot be told
     * from those of timers and are attributed to the timers.
     *
     * @return `(inGroupIndex, isCounter)`, or null for an index outside 0..5
     */
    fun timerOfNotification(timeIdx: Int): Pair<Int, Boolean>? = when (timeIdx) {
        in 0 until TIMER_COUNT -> timeIdx to false
        in TIMER_COUNT until TIMER_COUNT + COUNTER_COUNT -> (timeIdx - TIMER_COUNT) to true
        else -> null
    }

    /**
     * (#21) Interprets `RcTimeReportUI.timeIdx` of a timer ([isCounter] false) or counter report:
     * the in-group index, with the device numbering 3..5 also accepted for counters.
     *
     * @return the in-group index 0..2, or null
     */
    fun timerOfReport(timeIdx: Int, isCounter: Boolean): Int? = when {
        timeIdx in 0 until TIMER_COUNT -> timeIdx
        isCounter && timeIdx in TIMER_COUNT until TIMER_COUNT + COUNTER_COUNT -> timeIdx - TIMER_COUNT
        else -> null
    }

    /** (#21) Device timer numbers accepted by `rcResetTimer`: 0..2 timers, 3..5 counters. */
    val RESET_TIMER_DEVICE_INDICES: IntRange = 0 until TIMER_COUNT + COUNTER_COUNT

    // ---------------------------------------------------------------------------------------------
    // Live data
    // ---------------------------------------------------------------------------------------------

    /**
     * (#30) A timer whose reported count did not change for this long is shown as stopped
     * (`TimerState.running`), unless a notification said otherwise in the meantime. Timer reports
     * are assumed to arrive about once per second while a timer runs.
     */
    const val TIMER_RUNNING_STALE_MS: Long = 3_000

    /**
     * (#11, #16, verified on the AX12 2026-11-21) Live value units as the device reports them:
     * - `rawVal`: half scale, ±500 = ±100 % (sticks, pots, sliders and switches: −500 / 0 / +500).
     *   Sources the radio does not have report [SOURCE_ABSENT] (65535).
     * - `rawLogicVal`: not usable; the AX12 reports the same large constant for every source.
     *   The logic value in percent is therefore derived from `rawVal` ([sourcePercent]).
     * - `outputVal`: percent after the input weight (absent sources report 100, mapped to 0).
     * - `chOut`: PWM pulse width in µs, 1500 = centre, ±500 µs = ±100 % ([channelPercent]).
     * - `logicVal` (channel): −1..+1 (passed on verbatim).
     * - `rcVtg` / `rxVtg`: volts.
     */
    const val SOURCE_ABSENT: Int = 65_535
    const val SOURCE_RAW_PER_100_PERCENT: Int = 500
    const val CHANNEL_PWM_CENTER_US: Int = 1_500
    const val CHANNEL_PWM_PER_100_PERCENT_US: Int = 500
    const val VOLTAGE_SCALE: Float = 1f

    /** `rawVal` → percent, −100..+100; 0 for a source the radio does not have. */
    fun sourcePercent(rawVal: Int): Int {
        if (rawVal == SOURCE_ABSENT) return 0
        return Math.round(rawVal * 100f / SOURCE_RAW_PER_100_PERCENT).coerceIn(-100, 100)
    }

    /** `outputVal` (percent) → percent; 0 for a source the radio does not have. */
    fun sourceOutputPercent(rawVal: Int, outputVal: Int): Int = if (rawVal == SOURCE_ABSENT) 0 else outputVal

    /** `chOut` (PWM µs) → percent. Not clamped: limits beyond ±100 % stay visible. */
    fun channelPercent(chOut: Int): Int =
        Math.round((chOut - CHANNEL_PWM_CENTER_US) * 100f / CHANNEL_PWM_PER_100_PERCENT_US)

    // ---------------------------------------------------------------------------------------------
    // Value ranges checked before a write
    // ---------------------------------------------------------------------------------------------

    /** `uint8_t` members of `RcSysCfgDataUI` (alarm voltage, stick beep, model match, baud rate code, modes). */
    val UINT8_RANGE: IntRange = 0..255

    /** `int8_t` failsafe values. */
    val INT8_RANGE: IntRange = -128..127

    /** `rcSetUsbToVcp(std::uint8_t mode)`. */
    val USB_PASSTHROUGH_MODES: IntRange = 0..255

    /** CRSF device addresses for the ELRS menu request (`reqSetDevID`). */
    val CRSF_ADDRESS_RANGE: IntRange = 0..255

    /**
     * (#31) Longest raw CRSF frame handed to `rcSendCrsfRawData` (CRSF frames are at most 64
     * bytes: address + length + 62).
     */
    const val CRSF_MAX_FRAME_BYTES: Int = 64

    /** `RcModelDataUI.modelMatch` is an unsigned 32-bit value. */
    val MODEL_MATCH_RANGE: LongRange = 0L..0xFFFF_FFFFL

    /**
     * (#32) Manual 14.2: in counter mode `startTime` is the counter maximum and "must not be 0".
     * `setTimerConfig` therefore rejects a counter with `startTime == 0`.
     */
    const val COUNTER_REQUIRES_NONZERO_MAXIMUM: Boolean = true

    // ---------------------------------------------------------------------------------------------
    // Telemetry and ELRS
    // ---------------------------------------------------------------------------------------------

    /**
     * (#33) `RCgetTelemetryItemNotify` is drained right after `RC_MSGID_RCTELEMETER_NOTIFY`; a
     * return value > 0 marks a valid item (manual 18.3 and the reference console). At most this
     * many items are taken per message so that a misbehaving queue cannot stall the SDK thread.
     */
    const val TELEMETRY_NOTIFY_DRAIN_LIMIT: Int = 64

    /** Drained notifications wait here for `Radio.pollTelemetryNotification`; the oldest are dropped. */
    const val TELEMETRY_NOTIFY_BUFFER: Int = 128

    /** `rcGetTelemetryList` is re-read at most this often; calls in between get the cached list. */
    const val TELEMETRY_LIST_MIN_INTERVAL_MS: Long = 250

    /** `RadioEvent.TelemetryUpdated` is emitted at most this often (the message can arrive per frame). */
    const val TELEMETRY_UPDATED_EVENT_INTERVAL_MS: Long = 250

    /**
     * (#34) An ELRS menu request counts as "in progress" until no menu item arrived for
     * [ELRS_MENU_IDLE_MS], or until [ELRS_MENU_FIRST_ITEM_TIMEOUT_MS] passed without any item.
     * The SDK ignores requests while one is in progress and sends no completion message.
     */
    const val ELRS_MENU_IDLE_MS: Long = 1_500
    const val ELRS_MENU_FIRST_ITEM_TIMEOUT_MS: Long = 5_000

    /** A device-info request counts as pending for this long unless `RC_MSGID_FWINF_UPDATED` arrives. */
    const val DEVICE_INFO_TIMEOUT_MS: Long = 5_000

    /** Cached list sizes used for index validation are re-read at least this often. */
    const val COUNTS_MAX_AGE_MS: Long = 2_000
}
