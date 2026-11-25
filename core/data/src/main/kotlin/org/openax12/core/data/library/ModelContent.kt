package org.openax12.core.data.library

import java.io.File
import java.io.IOException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.openax12.core.data.storage.FileOps
import org.openax12.core.model.Ax12Json

/**
 * Compares SDK model files by what they configure, not by what the SDK stamps on them.
 *
 * A model file carries the model's name, the SDK's model index and the second it was created in
 * (`MODEL_FILE_CFG.modelName` / `modelIdx` / `timeStamp`). Two files that differ only in those
 * hold the same model: that is how a model re-created by the SDK from the radio's MCU after the
 * app's data was cleared is recognised in the library.
 *
 * Stick mode, RF module mode and the alarm voltage (`RC_MODEL_CFG.stickMode` / `rfModuleMode` /
 * `alramVtg`) are settings of the radio that the app keeps equal in every model; a copy that
 * missed a change, or one that was adjusted to the radio when it was loaded, is still the same model.
 */
internal object ModelContent {

    /** Keys the SDK or the app sets per file; they say nothing about the configuration. */
    private val VOLATILE_KEYS = setOf("modelName", "timeStamp", "modelIdx")

    private const val FILE_HEADER_KEY = "MODEL_FILE_CFG"

    private const val CONFIG_KEY = "RC_MODEL_CFG"

    /** Radio-wide fields inside `RC_MODEL_CFG`: unified across the models, so no part of a model's identity. */
    private val RADIO_WIDE_KEYS = setOf("stickMode", "rfModuleMode", "alramVtg")

    /**
     * A fingerprint of the configuration in [file], independent of the model name, the SDK's
     * model index, the creation time and the order of keys. Null if the file is missing, too
     * large or not a JSON object.
     */
    fun fingerprint(file: File): String? {
        if (!file.isFile || file.length() > ModelArchive.MAX_STOCK_MODEL_BYTES) return null
        val root = try {
            Ax12Json.parseToJsonElement(file.readText(Charsets.UTF_8)) as? JsonObject
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } ?: return null
        val canonical = StringBuilder()
        append(canonical, withoutVolatile(root, isRoot = true))
        return FileOps.sha256(canonical.toString().toByteArray(Charsets.UTF_8))
    }

    private fun withoutVolatile(root: JsonObject, isRoot: Boolean): JsonObject {
        val kept = LinkedHashMap<String, JsonElement>()
        for ((key, value) in root) {
            when {
                key in VOLATILE_KEYS -> Unit
                isRoot && key == FILE_HEADER_KEY && value is JsonObject -> kept[key] = withoutVolatile(value, isRoot = false)
                isRoot && key == CONFIG_KEY && value is JsonObject ->
                    kept[key] = JsonObject(value.filterKeys { it !in RADIO_WIDE_KEYS })
                else -> kept[key] = value
            }
        }
        return JsonObject(kept)
    }

    /** Key-sorted compact rendering, so equal content gives equal text whatever the key order. */
    private fun append(out: StringBuilder, element: JsonElement) {
        when (element) {
            is JsonObject -> {
                out.append('{')
                for ((i, key) in element.keys.sorted().withIndex()) {
                    if (i > 0) out.append(',')
                    out.append(JsonPrimitive(key)).append(':')
                    append(out, element.getValue(key))
                }
                out.append('}')
            }
            is JsonArray -> {
                out.append('[')
                element.forEachIndexed { i, item ->
                    if (i > 0) out.append(',')
                    append(out, item)
                }
                out.append(']')
            }
            else -> out.append(element.toString())
        }
    }
}
