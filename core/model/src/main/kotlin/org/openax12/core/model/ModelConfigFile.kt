package org.openax12.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * The system fields of an SDK model file (`RC_MODEL_CFG`) as raw device codes, i.e. what
 * `RcSysCfgDataUI` mirrors. Unlike [SystemConfig] nothing is mapped to an enum, so unknown codes
 * survive a round trip.
 *
 * @property failsafeMode `fsMode`
 * @property failsafeValues `fsChVal[16]`
 * @property stickMode `stickMode` (0 = Mode 1, 1 = Mode 2)
 * @property preFlightCheck `preFlightCheckChannel[36]`
 * @property rfModuleMode `rfModuleMode` (0 internal, 1 external, 2 off)
 * @property alarmVoltageDeciVolt `alramVtg` in 0.1 V
 * @property stickBeep `joytickBeep`
 * @property modelMatchId `modelMatchId`
 * @property crsfBaudRate `crsfBaudRate`
 */
data class RawSystemFields(
    val failsafeMode: Int,
    val failsafeValues: List<Int>,
    val stickMode: Int,
    val preFlightCheck: List<Int>,
    val rfModuleMode: Int,
    val alarmVoltageDeciVolt: Int,
    val stickBeep: Int,
    val modelMatchId: Int,
    val crsfBaudRate: Int,
) {
    /** The three radio-wide settings of these fields. */
    val radioWide: RadioWidePatch
        get() = RadioWidePatch(stickMode = stickMode, rfModuleMode = rfModuleMode, alarmVoltageDeciVolt = alarmVoltageDeciVolt)
}

/**
 * A change of the radio-wide settings: stick mode, RF module mode and low-voltage alarm are
 * settings of the transmitter, but the SDK stores them in every model file. A `null` field is left
 * as it is. Values are raw device codes (see [SystemConfigValidity]).
 */
data class RadioWidePatch(
    val stickMode: Int? = null,
    val rfModuleMode: Int? = null,
    val alarmVoltageDeciVolt: Int? = null,
) {
    /** True if the patch changes nothing. */
    val isEmpty: Boolean get() = stickMode == null && rfModuleMode == null && alarmVoltageDeciVolt == null

    /** The first implausible code of this patch, or null. */
    fun problem(): String? = when {
        stickMode != null && !SystemConfigValidity.isStickModeCode(stickMode) -> "stickMode $stickMode is not 0 or 1"
        rfModuleMode != null && !SystemConfigValidity.isRfModuleCode(rfModuleMode) -> "rfModuleMode $rfModuleMode is not 0, 1, 2 or 255"
        alarmVoltageDeciVolt != null && !SystemConfigValidity.isAlarmVoltage(alarmVoltageDeciVolt) ->
            "alramVtg $alarmVoltageDeciVolt is not 0..255"
        else -> null
    }

    /** The fields of this patch whose value differs from [fields]; everything else becomes null. */
    fun differingFrom(fields: RawSystemFields): RadioWidePatch = RadioWidePatch(
        stickMode = stickMode?.takeIf { it != fields.stickMode },
        rfModuleMode = rfModuleMode?.takeIf { it != fields.rfModuleMode },
        alarmVoltageDeciVolt = alarmVoltageDeciVolt?.takeIf { it != fields.alarmVoltageDeciVolt },
    )
}

/**
 * Reads and patches the JSON model files of the SDK (`files/rmAx/rcModel/<n>.json`, and the
 * library's `model.json` copies of them). Only the radio-wide fields of the `RC_MODEL_CFG`
 * section are ever touched; the rest of the document is carried along as it is.
 *
 * The same section is what the stock app reads its radio-wide settings from, whereas
 * `rcGetRcSysConfig()` returns an uninitialised struct on the AX12 (assumption r5).
 */
object ModelConfigFile {

    /** Name of the section that holds the model configuration, system fields included. */
    const val CONFIG_KEY: String = "RC_MODEL_CFG"

    private val parser = Json { isLenient = false }

    /**
     * The system fields of [text], or null if it is not a JSON object with a complete
     * `RC_MODEL_CFG` section. The values are **not** checked for plausibility, see
     * [SystemConfigValidity.problem].
     */
    fun readSystem(text: String): RawSystemFields? {
        val config = section(text) ?: return null
        return try {
            RawSystemFields(
                failsafeMode = config.int("fsMode"),
                failsafeValues = config.ints("fsChVal"),
                stickMode = config.int("stickMode"),
                preFlightCheck = config.ints("preFlightCheckChannel"),
                rfModuleMode = config.int("rfModuleMode"),
                alarmVoltageDeciVolt = config.int("alramVtg"),
                stickBeep = config.int("joytickBeep"),
                modelMatchId = config.int("modelMatchId"),
                crsfBaudRate = config.int("crsfBaudRate"),
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /**
     * [text] with the fields of [patch] replaced; null if [text] has no usable `RC_MODEL_CFG`
     * section or [patch] contains an implausible code (nothing is ever half-patched).
     */
    fun patch(text: String, patch: RadioWidePatch): String? {
        if (patch.problem() != null) return null
        val root = parse(text) ?: return null
        val config = root[CONFIG_KEY] as? JsonObject ?: return null
        if (readSystem(text) == null) return null
        val updated = LinkedHashMap(config)
        patch.stickMode?.let { updated["stickMode"] = JsonPrimitive(it) }
        patch.rfModuleMode?.let { updated["rfModuleMode"] = JsonPrimitive(it) }
        patch.alarmVoltageDeciVolt?.let { updated["alramVtg"] = JsonPrimitive(it) }
        val result = LinkedHashMap(root)
        result[CONFIG_KEY] = JsonObject(updated)
        return JsonObject(result).toString()
    }

    private fun parse(text: String): JsonObject? = try {
        parser.parseToJsonElement(text) as? JsonObject
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun section(text: String): JsonObject? = parse(text)?.get(CONFIG_KEY) as? JsonObject

    private fun JsonPrimitive.intValue(): Int? = if (isString) null else intOrNull

    private fun JsonObject.int(key: String): Int {
        val value = this[key] as? JsonPrimitive ?: throw IllegalArgumentException("missing $key")
        return value.intValue() ?: throw IllegalArgumentException("$key is not an int")
    }

    private fun JsonObject.ints(key: String): List<Int> {
        val array = this[key] as? JsonArray ?: throw IllegalArgumentException("missing $key")
        return array.map { element: JsonElement ->
            (element as? JsonPrimitive)?.intValue() ?: throw IllegalArgumentException("$key has a non-int entry")
        }
    }
}
