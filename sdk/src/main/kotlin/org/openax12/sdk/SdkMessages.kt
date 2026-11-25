package org.openax12.sdk

import org.openax12.core.model.CrsfTelemetrySnapshot
import org.openax12.core.model.ElrsAck
import org.openax12.core.model.ElrsMenuItem
import org.openax12.core.model.ElrsStatus
import org.openax12.core.model.TimerNotify
import org.openax12.core.model.TimerReport
import org.openax12.core.radio.CrsfFrame
import org.openax12.core.radio.RadioEvent

/**
 * One SDK output message as it left the bridge: the id plus the payload split into primitives.
 * Created on the SDK output thread, consumed on the SDK thread.
 */
internal class RawMessage(
    val msgId: Int,
    val ints: IntArray?,
    val doubles: DoubleArray?,
    val bytes: ByteArray?,
    val strings: Array<String>?,
)

/** A decoded SDK output message. */
internal sealed interface SdkMessage {

    /** `RC_MSGID_MIXOUT_UPDATE`: live data changed, re-read it. */
    data object LiveUpdated : SdkMessage

    /** `RC_MSGID_GETDEVCFG_END`. */
    data object DeviceConfigRead : SdkMessage

    /** `RC_MSGID_CFGTODEV_END`. */
    data object DeviceConfigWritten : SdkMessage

    /** `RC_MSGID_RCTELEMETER_NOTIFY`: drain the notification queue. */
    data object TelemetryNotifyQueued : SdkMessage

    /** `RC_MSGID_TELEMETRY_UPDATED` (high rate). */
    data object TelemetryListChanged : SdkMessage

    /** `RC_MSGID_FWVERSION_NOT_MATCH`. */
    data object FirmwareMismatch : SdkMessage

    /** `RC_MSGID_FWINF_UPDATED`. */
    data object FirmwareInfoUpdated : SdkMessage

    /** `RC_MSGID_TIM_REPORT` / `RC_MSGID_CNT_REPORT`. */
    data class TimerReported(val report: TimerReport, val isCounter: Boolean) : SdkMessage

    /** `RC_MSGID_TIM_NOTIFY`. */
    data class TimerNotified(val notify: TimerNotify) : SdkMessage

    /** `RC_MSGID_ELRS_MENUITEM_UPDATE`. */
    data class ElrsMenuItemReceived(val item: ElrsMenuItem) : SdkMessage

    /** Everything that is only forwarded as a [RadioEvent]. */
    data class Event(val event: RadioEvent) : SdkMessage
}

/**
 * Turns the primitive payload of an SDK message into a [SdkMessage]. The layouts are produced by
 * `dispatchMessage()` in `openax12_jni.cpp`:
 *
 * | message | ints | other |
 * |---|---|---|
 * | `PFC_NOTIFY` | one state per source | |
 * | `TIM_REPORT`, `CNT_REPORT` | `timeIdx`, `direction`, `startTime`, `timeCount` | |
 * | `TIM_NOTIFY` | `timeIdx`, `isRunning`, `notifyCode`, `timeCount` | |
 * | `TRIM_NOTIFY` | the `uint8` code | |
 * | `ELRS_SET_ACK` | `type`, `status`, `lastSetIdx` | strings: `options` |
 * | `ELRS_STATUS` | `badPkt`, `goodPkt`, `modelMismatch` | |
 * | `ELRS_TELEMETRY` | | doubles: `lat`, `lng`, `pitch`, `roll`, `yaw`, `voltage`, `current` |
 * | `ELRS_DEVINF_UPDATE` | | strings: device name |
 * | `ELRS_MENUITEM_UPDATE` | `idx`, `val`, `type`, `parentIdx`, `itemEnabled`, `itemVisible` | strings: `name`, `deviceName`, `units`, options… |
 * | `CRSF_DATA_RECEIVED` | | bytes: the frame |
 *
 * A message whose payload is missing or too short is dropped (null), never guessed.
 */
internal object SdkMessageDecoder {

    fun decode(message: RawMessage): SdkMessage? = decode(message.msgId, message.ints, message.doubles, message.bytes, message.strings)

    fun decode(msgId: Int, ints: IntArray?, doubles: DoubleArray?, bytes: ByteArray?, strings: Array<String>?): SdkMessage? =
        when (msgId) {
            SdkMsg.MIXOUT_UPDATE -> SdkMessage.LiveUpdated
            SdkMsg.GETDEVCFG_END -> SdkMessage.DeviceConfigRead
            SdkMsg.CFGTODEV_END -> SdkMessage.DeviceConfigWritten
            SdkMsg.RCTELEMETER_NOTIFY -> SdkMessage.TelemetryNotifyQueued
            SdkMsg.TELEMETRY_UPDATED -> SdkMessage.TelemetryListChanged
            SdkMsg.FWVERSION_NOT_MATCH -> SdkMessage.FirmwareMismatch
            SdkMsg.FWINF_UPDATED -> SdkMessage.FirmwareInfoUpdated

            SdkMsg.LVTG_NOTIFY -> SdkMessage.Event(RadioEvent.LowVoltage)
            SdkMsg.TELEMETRY_LOST -> SdkMessage.Event(RadioEvent.TelemetryLost)
            SdkMsg.TELEMETRY_RECOVER -> SdkMessage.Event(RadioEvent.TelemetryRecovered)

            SdkMsg.PFC_NOTIFY -> ints?.let { SdkMessage.Event(RadioEvent.PreFlightCheck(it.toList())) }

            SdkMsg.TIM_REPORT, SdkMsg.CNT_REPORT -> ints.takeIfSize(4)?.let {
                SdkMessage.TimerReported(
                    report = TimerReport(index = it[0], showsElapsed = it[1] == 1, startTime = it[2], count = it[3]),
                    isCounter = msgId == SdkMsg.CNT_REPORT,
                )
            }

            SdkMsg.TIM_NOTIFY -> ints.takeIfSize(4)?.let {
                SdkMessage.TimerNotified(TimerNotify(index = it[0], running = it[1] != 0, code = it[2], count = it[3]))
            }

            // The code is optional: a trim change without payload is still a trim change.
            SdkMsg.TRIM_NOTIFY -> SdkMessage.Event(RadioEvent.TrimChanged(ints?.getOrNull(0) ?: 0))

            SdkMsg.ELRS_SET_ACK -> ints.takeIfSize(3)?.let {
                SdkMessage.Event(
                    RadioEvent.ElrsAcknowledged(
                        ElrsAck(type = it[0], status = it[1], lastSetIndex = it[2], options = strings?.getOrNull(0).orEmpty()),
                    ),
                )
            }

            SdkMsg.ELRS_STATUS -> ints.takeIfSize(3)?.let {
                SdkMessage.Event(RadioEvent.ElrsStatusReceived(ElrsStatus(badPackets = it[0], goodPackets = it[1], modelMismatch = it[2])))
            }

            SdkMsg.ELRS_TELEMETRY -> doubles?.takeIf { it.size >= 7 }?.let {
                SdkMessage.Event(
                    RadioEvent.ElrsTelemetry(
                        CrsfTelemetrySnapshot(
                            latitude = it[0],
                            longitude = it[1],
                            pitch = it[2],
                            roll = it[3],
                            yaw = it[4],
                            voltage = it[5],
                            current = it[6],
                        ),
                    ),
                )
            }

            SdkMsg.ELRS_DEVINF_UPDATE -> strings?.getOrNull(0)?.let { SdkMessage.Event(RadioEvent.ElrsDeviceInfo(it)) }

            SdkMsg.ELRS_MENUITEM_UPDATE -> {
                val values = ints.takeIfSize(6)
                if (values == null || strings == null || strings.size < 3) {
                    null
                } else {
                    SdkMessage.ElrsMenuItemReceived(
                        ElrsMenuItem(
                            index = values[0],
                            value = values[1],
                            type = values[2],
                            parentIndex = values[3],
                            enabled = values[4] != 0,
                            visible = values[5] != 0,
                            name = strings[0],
                            deviceName = strings[1],
                            units = strings[2],
                            options = strings.drop(3),
                        ),
                    )
                }
            }

            SdkMsg.CRSF_DATA_RECEIVED -> bytes?.takeIf { it.isNotEmpty() }?.let { SdkMessage.Event(RadioEvent.CrsfReceived(CrsfFrame(it))) }

            // Reserved / update-feature ids are dropped in the bridge; unknown ids carry nothing usable.
            else -> null
        }

    private fun IntArray?.takeIfSize(size: Int): IntArray? = this?.takeIf { it.size >= size }
}
