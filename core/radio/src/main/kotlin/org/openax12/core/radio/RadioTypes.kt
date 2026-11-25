package org.openax12.core.radio

import org.openax12.core.model.CrsfTelemetrySnapshot
import org.openax12.core.model.ElrsAck
import org.openax12.core.model.ElrsMenuItem
import org.openax12.core.model.ElrsStatus
import org.openax12.core.model.TimerNotify
import org.openax12.core.model.TimerReport

/**
 * Why the radio connection is in [ConnectionState.Error].
 *
 * The SDK opens the serial port itself and reports only its `InitResult` (success / already
 * initialized / thread start failed / communication init failed) — neither the port nor an errno.
 * A port held by the stock RadioMaster app (assumption 1) can therefore surface as
 * [CommunicationInitFailed], as [DeviceTimeout] (the SDK starts but the radio never answers) or,
 * when the backend's own probing succeeds, as [SerialPortBusy]. **The UI decides whether to show
 * the "close the stock RadioMaster app" hint from [mayBeStockAppConflict], never from the type.**
 */
sealed interface ConnectionError {

    /** English diagnostic text for logs and the diagnostics screen (the UI localizes by type). */
    val message: String

    /**
     * True if this failure is what a still-running stock app would look like. The UI then adds
     * the hint to force-close / disable the stock app (and that firmware updates stay there).
     */
    val mayBeStockAppConflict: Boolean get() = false

    /**
     * Another process holds the serial port — in practice the stock RadioMaster app.
     *
     * **Best-effort classification** by `SdkRadio` (assumption 24), e.g. a probing `open()` of the
     * tty answered `EBUSY`, or after a failed init another process is seen holding the device.
     * The SDK cannot report this itself, so a busy port is just as likely reported as
     * [DeviceTimeout] or [CommunicationInitFailed].
     */
    data class SerialPortBusy(override val message: String = "Serial port is in use by another app") : ConnectionError {
        override val mayBeStockAppConflict: Boolean get() = true
    }

    /**
     * The serial port device does not exist or cannot be accessed (not an AX12, missing
     * permission). Best-effort classification like [SerialPortBusy] (probing `open()` answered
     * `ENOENT` / `EACCES`, or no tty descriptor appeared in the process after `initialize()`).
     */
    data class SerialPortUnavailable(override val message: String = "Serial port not available") : ConnectionError {
        override val mayBeStockAppConflict: Boolean get() = true
    }

    /** The native SDK libraries could not be loaded (wrong ABI, e.g. on an x86 emulator). */
    data class NativeLibraryUnavailable(override val message: String = "SDK library could not be loaded") : ConnectionError

    /** `initialize()` returned `ThreadStartFailed`. */
    data class ThreadStartFailed(override val message: String = "SDK worker threads failed to start") : ConnectionError

    /** `initialize()` returned `CommunicationInitFailed` — possibly because the port is held by the stock app. */
    data class CommunicationInitFailed(override val message: String = "SDK communication init failed") : ConnectionError {
        override val mayBeStockAppConflict: Boolean get() = true
    }

    /**
     * The SDK started but the radio MCU never delivered its configuration (no
     * `RC_MSGID_GETDEVCFG_END`). This is the fallback classification and **includes the
     * busy-port case** when it could not be detected.
     */
    data class DeviceTimeout(override val message: String = "The radio did not answer") : ConnectionError {
        override val mayBeStockAppConflict: Boolean get() = true
    }

    /** The SDK reported that the device firmware is too old / incompatible. Update it with the stock app. */
    data class FirmwareMismatch(override val message: String = "Device firmware is not compatible") : ConnectionError

    /** The connection that was [ConnectionState.Ready] broke while running (also the simulator's `CONNECTION_LOST`). */
    data class ConnectionLost(override val message: String = "The connection to the radio was lost") : ConnectionError

    /** Anything else. */
    data class Unknown(override val message: String) : ConnectionError
}

/** State machine of the connection to the radio MCU, exposed as `Radio.connection`. */
sealed interface ConnectionState {

    /** Not started, or stopped. Getters return empty results. */
    data object Stopped : ConnectionState

    /** `start()` is running: loading the library, initializing the SDK, opening the serial port. */
    data object Initializing : ConnectionState

    /**
     * The SDK runs and the device configuration read is in progress (waiting for
     * `RC_MSGID_GETDEVCFG_END`). Local model files are readable, device data is not valid yet.
     */
    data object WaitingForDevice : ConnectionState

    /** Device configuration was read; everything works. */
    data object Ready : ConnectionState

    /** Could not connect, or the connection broke. `start()` may be called again to retry. */
    data class Error(val reason: ConnectionError) : ConnectionState

    /** True in [Ready]. */
    val isReady: Boolean get() = this is Ready
}

/** Result of an asynchronous SDK task that completes with a message. */
enum class TaskResult {
    /** The completion message arrived. */
    COMPLETED,

    /** No completion message within the timeout. The task may still finish later. */
    TIMED_OUT,

    /** The radio is not in a state that allows the task (not started / error). Nothing was sent. */
    NOT_READY,

    /** The same task is already running; this call did not start a second one. */
    BUSY;

    /** True for [COMPLETED]. */
    val isSuccess: Boolean get() = this == COMPLETED
}

/**
 * A complete raw CRSF frame as delivered by the SDK: `ADDR LEN TYPE DATA… CRC`
 * (total length = LEN + 2; CRC8-D5 over TYPE + DATA).
 *
 * The array is owned by the frame and must not be modified.
 */
class CrsfFrame(val bytes: ByteArray) {

    /** Device address byte, or -1 for a truncated frame. */
    val address: Int get() = if (bytes.isNotEmpty()) bytes[0].toInt() and 0xFF else -1

    /** Frame type byte (0x02 GPS, 0x07 vario, 0x08 battery, 0x14 link statistics, …), or -1. */
    val type: Int get() = if (bytes.size > 2) bytes[2].toInt() and 0xFF else -1

    /** Offset of the first payload byte (after TYPE). */
    val payloadOffset: Int get() = 3

    /** Number of payload bytes (without ADDR, LEN, TYPE and CRC). */
    val payloadLength: Int get() = (bytes.size - 4).coerceAtLeast(0)

    /** Unsigned payload byte [index] (0-based within the payload), or 0 if out of range. */
    fun payload(index: Int): Int =
        if (index in 0 until payloadLength) bytes[payloadOffset + index].toInt() and 0xFF else 0

    override fun toString(): String = "CrsfFrame(type=0x${type.toString(16)}, ${bytes.size} bytes)"

    companion object {
        const val TYPE_GPS = 0x02
        const val TYPE_VARIO = 0x07
        const val TYPE_BATTERY = 0x08
        const val TYPE_BARO_ALTITUDE = 0x09
        const val TYPE_AIRSPEED = 0x0A
        const val TYPE_LINK_STATISTICS = 0x14
        const val TYPE_ATTITUDE = 0x1E
        const val TYPE_FLIGHT_MODE = 0x21
        const val TYPE_ROTORFLIGHT_CUSTOM = 0x88
    }
}

/**
 * Everything the SDK reports through its message callback that is not already covered by
 * `Radio.live` (`RC_MSGID_MIXOUT_UPDATE`) or `Radio.connection`. Delivered on `Radio.events`.
 *
 * Messages of the firmware-update and app-update APIs are deliberately not represented
 * (design rule 4), and neither is the reserved `RC_MSGID_SRCINPUT_UPDATE`.
 */
sealed interface RadioEvent {

    /** `RC_MSGID_LVTG_NOTIFY`: transmitter battery is low. */
    data object LowVoltage : RadioEvent

    /**
     * `RC_MSGID_PFC_NOTIFY`: pre-flight check status. [states] holds one entry per source as
     * delivered (length as returned by the device); non-zero = that source is not at its preset.
     *
     * The firmware raises it right after power-up, and `Radio.events` has no replay: screens read
     * the retained copy `RuntimeEngine.preFlightCheck` instead of collecting this event.
     */
    data class PreFlightCheck(val states: List<Int>) : RadioEvent {
        /** True if any source fails the check. */
        val hasFailures: Boolean get() = states.any { it != 0 }
    }

    /** `RC_MSGID_TIM_REPORT`: periodic timer report. */
    data class TimerReported(val report: TimerReport) : RadioEvent

    /** `RC_MSGID_CNT_REPORT`: counter report. */
    data class CounterReported(val report: TimerReport) : RadioEvent

    /** `RC_MSGID_TIM_NOTIFY`: timer alert event (alarm, interval, reset). */
    data class TimerNotified(val notify: TimerNotify) : RadioEvent

    /** `RC_MSGID_TRIM_NOTIFY`: a trim value changed. [code] is the raw `uint8` payload. */
    data class TrimChanged(val code: Int) : RadioEvent

    /** `RC_MSGID_CFGTODEV_END`: the working config was written to the device and the current model file saved. */
    data object ConfigWritten : RadioEvent

    /** `RC_MSGID_GETDEVCFG_END`: the device configuration was read into the working config (and written into the current model file). */
    data object ConfigRead : RadioEvent

    /** `RC_MSGID_RCTELEMETER_NOTIFY`: the SDK's telemetry notification queue is non-empty; drain it with `Radio.pollTelemetryNotification`. */
    data object TelemetryNotificationQueued : RadioEvent

    /** `RC_MSGID_FWVERSION_NOT_MATCH`: device firmware too old or incompatible. */
    data object FirmwareVersionMismatch : RadioEvent

    /** `RC_MSGID_FWINF_UPDATED`: firmware info refreshed; read it with `Radio.getFirmwareInfo`. */
    data object FirmwareInfoUpdated : RadioEvent

    /** `RC_MSGID_ELRS_SET_ACK`: an ELRS menu command was acknowledged. */
    data class ElrsAcknowledged(val ack: ElrsAck) : RadioEvent

    /** `RC_MSGID_ELRS_STATUS`: answer to `Radio.requestElrsStatus`. */
    data class ElrsStatusReceived(val status: ElrsStatus) : RadioEvent

    /** `RC_MSGID_ELRS_TELEMETRY`: position / attitude / battery snapshot decoded by the SDK. */
    data class ElrsTelemetry(val snapshot: CrsfTelemetrySnapshot) : RadioEvent

    /** `RC_MSGID_ELRS_DEVINF_UPDATE`: name of the ELRS device whose menu is being read. */
    data class ElrsDeviceInfo(val deviceName: String) : RadioEvent

    /** `RC_MSGID_ELRS_MENUITEM_UPDATE`: one item of the ELRS menu (sent once per item). */
    data class ElrsMenuItemReceived(val item: ElrsMenuItem) : RadioEvent

    /** `RC_MSGID_CRSF_DATA_RECEIVED`: a complete raw CRSF frame. */
    data class CrsfReceived(val frame: CrsfFrame) : RadioEvent

    /** `RC_MSGID_TELEMETRY_UPDATED`: the SDK telemetry list changed (high rate; throttle re-reads). */
    data object TelemetryUpdated : RadioEvent

    /** `RC_MSGID_TELEMETRY_LOST`: receiver telemetry lost. */
    data object TelemetryLost : RadioEvent

    /** `RC_MSGID_TELEMETRY_RECOVER`: receiver telemetry recovered. */
    data object TelemetryRecovered : RadioEvent
}
