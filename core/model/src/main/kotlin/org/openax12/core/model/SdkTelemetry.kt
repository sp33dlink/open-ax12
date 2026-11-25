package org.openax12.core.model

import kotlinx.serialization.Serializable

/**
 * One entry of the SDK telemetry list (`rcGetTelemetryList`, struct `TelemetryNotifyUI` /
 * `TelemetryItemUI`). Read-only: the value fields only.
 *
 * @property hash `hashValue`: 64-bit identity of the telemetry item. **Always a Long**, never Int.
 * @property index `idx`
 * @property name `notifyName`: raw item name
 * @property displayName `nameTxt`: name as formatted by the SDK
 * @property value `currValue`
 * @property valueText `valTxt`: value formatted by the SDK (may include the unit)
 * @property unit `valueUnit`: SDK unit code (kept verbatim; mapping to [SensorUnit] is done by the data layer)
 * @property showInUi `showUI != 0`
 */
@Serializable
data class TelemetryItem(
    val hash: Long,
    val index: Int = 0,
    val name: String = "",
    val displayName: String = "",
    val value: Double = 0.0,
    val valueText: String = "",
    val unit: Int = 0,
    val showInUi: Boolean = true,
)

/**
 * Complete mirror of `TelemetryNotifyUI` as used for the SDK-side telemetry notifications
 * ("alarms"): configuration items (`rcGetNotifyCfgItem` / `rcSetNotifyCfgItem` /
 * `rcGetNotifyCfgList`) and triggered notifications (`RCgetTelemetryItemNotify`).
 *
 * Note that open-ax12 implements its own, richer app-side alarms ([SensorAlarm]); the SDK alarms
 * are exposed for completeness and diagnostics.
 *
 * @property hash `hashValue`: hash of the watched [TelemetryItem] (64-bit, **Long**). It is
 *   **always** the item's hash, also for an alarm that is about to be added — whether the SDK adds
 *   or modifies is decided by the separate first argument of `rcSetNotifyCfgItem`, not by this
 *   field (use `Radio.addTelemetryAlarm` / `Radio.updateTelemetryAlarm`). 0 = not bound to an item
 *   yet; such an alarm cannot be written.
 * @property index `idx`: position in the alarm list (-1 = not in the list yet)
 * @property popup `popupWindow != 0`
 * @property trigger `triggerType`: 1 greater / 2 equal / 3 less
 * @property intervalSec `intervalSec`: repeat interval
 * @property triggerValue `triggerValue`
 * @property unit `valueUnit`
 * @property name `notifyName`
 * @property text `notifyText`
 * @property audioFile `audioFile`
 * @property valueText `valTxt`
 * @property displayName `nameTxt`
 * @property value `currValue`
 * @property showInUi `showUI != 0`
 */
@Serializable
data class TelemetryAlarm(
    val hash: Long = 0,
    val index: Int = -1,
    val popup: Boolean = false,
    val trigger: CompareOp = CompareOp.GREATER,
    val intervalSec: Int = 0,
    val triggerValue: Double = 0.0,
    val unit: Int = 0,
    val name: String = "",
    val text: String = "",
    val audioFile: String = "",
    val valueText: String = "",
    val displayName: String = "",
    val value: Double = 0.0,
    val showInUi: Boolean = true,
)

/**
 * Mirror of `ElrsSetItemUI`: one item of the ELRS parameter menu (pushed one by one).
 *
 * @property index `idx`
 * @property parentIndex `parentIdx`: index of the folder this item lives in (0 = root)
 * @property value `val`: current value / selected option index
 * @property type `type`: CRSF parameter type code, see [ElrsItemType]
 * @property enabled `itemEnabled`
 * @property visible `itemVisible`
 * @property name `name`
 * @property options `options`: choices of a text-selection item
 * @property deviceName `deviceName`
 * @property units `units`
 */
@Serializable
data class ElrsMenuItem(
    val index: Int,
    val parentIndex: Int = 0,
    val value: Int = 0,
    val type: Int = 0,
    val enabled: Boolean = true,
    val visible: Boolean = true,
    val name: String = "",
    val options: List<String> = emptyList(),
    val deviceName: String = "",
    val units: String = "",
)

/**
 * CRSF parameter type codes as used in [ElrsMenuItem.type] (CRSF specification; assumption 13:
 * the SDK forwards them unchanged).
 */
object ElrsItemType {
    const val UINT8 = 0
    const val INT8 = 1
    const val UINT16 = 2
    const val INT16 = 3
    const val FLOAT = 8
    const val TEXT_SELECTION = 9
    const val STRING = 10
    const val FOLDER = 11
    const val INFO = 12
    const val COMMAND = 13
}

/**
 * Mirror of `ElrsSetEndAckUI`: acknowledgement of an ELRS menu command.
 *
 * @property type `type`
 * @property status `status`
 * @property lastSetIndex `lastSetIdx`: the item the acknowledged command addressed
 * @property options `options`: status / info text of a command item
 */
@Serializable
data class ElrsAck(
    val type: Int = 0,
    val status: Int = 0,
    val lastSetIndex: Int = 0,
    val options: String = "",
)

/**
 * Mirror of `ElrsStatusInfoUI`.
 *
 * @property badPackets `badPkt`
 * @property goodPackets `goodPkt`
 * @property modelMismatch `modelMismatch`: non-zero means the receiver's model-match id differs
 */
@Serializable
data class ElrsStatus(
    val badPackets: Int = 0,
    val goodPackets: Int = 0,
    val modelMismatch: Int = 0,
) {
    /** True if the receiver's model-match id differs. */
    val isModelMismatch: Boolean get() = modelMismatch != 0
}

/**
 * Mirror of `TelemetryDataUI`, the payload of the SDK's "ELRS telemetry" message: a
 * position / attitude / battery snapshot decoded by the SDK itself.
 */
@Serializable
data class CrsfTelemetrySnapshot(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val pitch: Double = 0.0,
    val roll: Double = 0.0,
    val yaw: Double = 0.0,
    val voltage: Double = 0.0,
    val current: Double = 0.0,
)

/**
 * Mirror of `FirmwareInfUI` (read-only; firmware updates are done in the stock app).
 * Unsigned 32-bit SDK fields are kept as Long.
 *
 * @property systemId `SysId`
 * @property deviceId `DevId`
 * @property flightTimeSec `FlyTime`: total flight time in seconds
 * @property buildDate `BuildTime`: YYMMDD
 * @property firmwareVersion `VER_FW`: `0x01020003` = 01.02.0003
 * @property hardwareVersion `VER_HW`: `0x00000102` = 01.02
 * @property deviceText `DevText`
 * @property firmwareVersionText `FwVerText`
 * @property buildTimeText `BulidTimeText`
 * @property cachedFirmwareVersionText `cacheFwFileVerTxt`
 */
@Serializable
data class FirmwareInfo(
    val systemId: Long = 0,
    val deviceId: Long = 0,
    val flightTimeSec: Long = 0,
    val buildDate: Long = 0,
    val firmwareVersion: Long = 0,
    val hardwareVersion: Long = 0,
    val deviceText: String = "",
    val firmwareVersionText: String = "",
    val buildTimeText: String = "",
    val cachedFirmwareVersionText: String = "",
) {
    /** True if the device has not answered the device-info request yet. */
    val isEmpty: Boolean get() = firmwareVersion == 0L && deviceId == 0L && firmwareVersionText.isEmpty()
}
