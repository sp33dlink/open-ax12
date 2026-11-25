package org.openax12.core.data.crsf

import org.openax12.core.model.SensorId
import org.openax12.core.model.SensorUnit
import org.openax12.core.model.Sensors

/** How the value of a Rotorflight custom-telemetry record is encoded (all big-endian). */
enum class RotorflightEncoding(val bytes: Int) {
    U8(1),
    U16(2),
    S16(2),
    U32(4),

    /** Two 12-bit values in three bytes (lowest / average cell voltage). */
    U12U12(3),

    /** Variable length, 12 bits per cell; consumes the rest of the frame. */
    CELLS(-1),
}

/**
 * One sensor of the Rotorflight custom telemetry frame (0x88).
 *
 * @property scale the raw value divided by this gives the physical value
 */
data class RotorflightSensor(
    val sid: Int,
    val name: String,
    val unit: SensorUnit,
    val encoding: RotorflightEncoding,
    val scale: Double,
    val precision: Int,
)

/**
 * Sensor table of the Rotorflight custom telemetry frame, taken from the SDK demo
 * (`docs/sdk/reference/rmAxSdkJni.cpp`), including the value lengths that were measured there
 * rather than documented (0x1013 is two bytes; 0x1090–0x1093 and 0x10A0–0x10A3 have no official
 * names).
 */
object RotorflightSensors {

    val ALL: List<RotorflightSensor> = listOf(
        // Battery 0x1000–0x102F
        RotorflightSensor(0x1011, "Vbat", SensorUnit.VOLT, RotorflightEncoding.U16, 100.0, 2),
        RotorflightSensor(0x1012, "Curr", SensorUnit.AMPERE, RotorflightEncoding.S16, 10.0, 1),
        RotorflightSensor(0x1013, "Capa", SensorUnit.MILLIAMPERE_HOUR, RotorflightEncoding.U16, 1.0, 0),
        RotorflightSensor(0x1014, "Bat%", SensorUnit.PERCENT, RotorflightEncoding.U8, 1.0, 0),
        RotorflightSensor(0x1020, "Cel#", SensorUnit.COUNT, RotorflightEncoding.U8, 1.0, 0),
        RotorflightSensor(0x1021, "Vcel", SensorUnit.VOLT, RotorflightEncoding.U12U12, 1000.0, 3),
        RotorflightSensor(0x102F, "Cels", SensorUnit.VOLT, RotorflightEncoding.CELLS, 1000.0, 3),
        // ESC 1 0x1041–0x1047
        RotorflightSensor(0x1041, "EscV", SensorUnit.VOLT, RotorflightEncoding.U16, 100.0, 2),
        RotorflightSensor(0x1042, "EscI", SensorUnit.AMPERE, RotorflightEncoding.U16, 10.0, 1),
        RotorflightSensor(0x1043, "EscC", SensorUnit.MILLIAMPERE_HOUR, RotorflightEncoding.U32, 1.0, 0),
        RotorflightSensor(0x1044, "EscR", SensorUnit.RPM, RotorflightEncoding.U16, 1.0, 0),
        RotorflightSensor(0x1045, "EscP", SensorUnit.PERCENT, RotorflightEncoding.U16, 1.0, 0),
        RotorflightSensor(0x1046, "Esc%", SensorUnit.PERCENT, RotorflightEncoding.U8, 1.0, 0),
        RotorflightSensor(0x1047, "EscT", SensorUnit.CELSIUS, RotorflightEncoding.S16, 1.0, 0),
        // ESC 2 0x1051–0x1057
        RotorflightSensor(0x1051, "Esc2V", SensorUnit.VOLT, RotorflightEncoding.U16, 100.0, 2),
        RotorflightSensor(0x1052, "Esc2I", SensorUnit.AMPERE, RotorflightEncoding.U16, 10.0, 1),
        RotorflightSensor(0x1053, "Esc2C", SensorUnit.MILLIAMPERE_HOUR, RotorflightEncoding.U32, 1.0, 0),
        RotorflightSensor(0x1054, "Esc2R", SensorUnit.RPM, RotorflightEncoding.U16, 1.0, 0),
        RotorflightSensor(0x1055, "Esc2P", SensorUnit.PERCENT, RotorflightEncoding.U16, 1.0, 0),
        RotorflightSensor(0x1056, "Esc2%", SensorUnit.PERCENT, RotorflightEncoding.U8, 1.0, 0),
        RotorflightSensor(0x1057, "Esc2T", SensorUnit.CELSIUS, RotorflightEncoding.S16, 1.0, 0),
        // Voltages and currents 0x1080–0x1087
        RotorflightSensor(0x1080, "Vesc", SensorUnit.VOLT, RotorflightEncoding.U16, 100.0, 2),
        RotorflightSensor(0x1081, "Vbec", SensorUnit.VOLT, RotorflightEncoding.U16, 100.0, 2),
        RotorflightSensor(0x1082, "Vbus", SensorUnit.VOLT, RotorflightEncoding.U16, 100.0, 2),
        RotorflightSensor(0x1083, "Vmcu", SensorUnit.VOLT, RotorflightEncoding.U16, 100.0, 2),
        RotorflightSensor(0x1084, "Iesc", SensorUnit.AMPERE, RotorflightEncoding.S16, 10.0, 1),
        RotorflightSensor(0x1085, "Ibec", SensorUnit.AMPERE, RotorflightEncoding.S16, 10.0, 1),
        RotorflightSensor(0x1086, "Ibus", SensorUnit.AMPERE, RotorflightEncoding.S16, 10.0, 1),
        RotorflightSensor(0x1087, "Imcu", SensorUnit.AMPERE, RotorflightEncoding.S16, 10.0, 1),
        // Undocumented, lengths measured
        RotorflightSensor(0x1090, "S1090", SensorUnit.NONE, RotorflightEncoding.U16, 1.0, 0),
        RotorflightSensor(0x1091, "S1091", SensorUnit.NONE, RotorflightEncoding.U16, 1.0, 0),
        RotorflightSensor(0x1092, "S1092", SensorUnit.NONE, RotorflightEncoding.U16, 1.0, 0),
        RotorflightSensor(0x1093, "S1093", SensorUnit.NONE, RotorflightEncoding.U16, 1.0, 0),
        RotorflightSensor(0x10A0, "S10A0", SensorUnit.NONE, RotorflightEncoding.U8, 1.0, 0),
        RotorflightSensor(0x10A1, "S10A1", SensorUnit.NONE, RotorflightEncoding.U8, 1.0, 0),
        RotorflightSensor(0x10A2, "S10A2", SensorUnit.NONE, RotorflightEncoding.U8, 1.0, 0),
        RotorflightSensor(0x10A3, "S10A3", SensorUnit.NONE, RotorflightEncoding.U8, 1.0, 0),
    )

    private val BY_SID: Map<Int, RotorflightSensor> = ALL.associateBy { it.sid }

    /** Suffix of the second value of a [RotorflightEncoding.U12U12] record (the average cell voltage). */
    const val AVERAGE_SUFFIX: String = ".avg"

    /** Definition of [sid], or null if the decoder does not know it. */
    fun find(sid: Int): RotorflightSensor? = BY_SID[sid]

    /** Sensor id of the average cell voltage carried in the same record as the lowest cell ([sid] 0x1021). */
    fun averageCellId(sid: Int): SensorId = SensorId(Sensors.rotorflight(sid).key + AVERAGE_SUFFIX)

    /** The definition behind a `crsf.rf.<sid>` / `crsf.rf.<sid>.avg` sensor id, or null. */
    fun forSensor(id: SensorId): RotorflightSensor? {
        val local = id.key.removePrefix(PREFIX)
        if (local.length == id.key.length) return null
        val sid = local.removeSuffix(AVERAGE_SUFFIX).toIntOrNull(16) ?: return null
        return find(sid)
    }

    private const val PREFIX = "crsf.rf."
}
