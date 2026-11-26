package org.openax12.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * One output channel as stored in the `channelOut` section of an SDK model file.
 *
 * @property index `chIdx`
 * @property min `chCfg.min`
 * @property max `chCfg.max`
 * @property middle `chCfg.middle`
 * @property weight `chCfg.weight`
 * @property mixesNum `chCfg.mixesNum` as declared by the file
 * @property mixes the entries of `mixCfg`
 */
data class FileChannel(
    val index: Int,
    val min: Int,
    val max: Int,
    val middle: Int,
    val weight: Int,
    val mixesNum: Int,
    val mixes: List<FileMix>,
)

/** One `mixCfg` entry of an SDK model file (the fields the check compares). */
data class FileMix(val source: Int, val weightUp: Int, val weightDown: Int, val offset: Int)

/** The channel and mix part of an SDK model file, see [ModelFileCheck.summarize]. */
data class ModelFileSummary(val channels: List<FileChannel>) {
    /** Number of mix lines in the whole file. */
    val mixCount: Int get() = channels.sumOf { it.mixes.size }
}

/**
 * Completeness checks for model files and working configs.
 *
 * Background (assumption r11): `rcLoadModelFile` answers success for a model file that has no
 * (or an empty) `channelOut` section and leaves the SDK working config with channels whose limits
 * are all 0 and with no mixes. `taskSaveConfigToDevice` writes the whole working config, so such a
 * state saved to the radio would wipe its mixes and channel setup. The functions here decide
 * whether a file is complete before it is loaded or imported, whether the working config equals the
 * file it was loaded from, and whether a working config may be written to the radio at all.
 *
 * Files that are not in the SDK format (no `MODEL_FILE_CFG` section, e.g. the simulator's own
 * files) cannot be judged by the file checks and pass them; [workingProblem] applies to every
 * working config.
 */
object ModelFileCheck {

    /** Top-level section that identifies an SDK model file. */
    const val FILE_KEY: String = "MODEL_FILE_CFG"

    /** Top-level section with the output channels and their mixes. */
    const val CHANNELS_KEY: String = "channelOut"

    private const val MAX_REPORTED = 4

    private val parser = Json { isLenient = false }

    /** True if [text] is a JSON object with a `MODEL_FILE_CFG` object (an SDK model file). */
    fun isSdkFormat(text: String): Boolean = (parse(text)?.get(FILE_KEY)) is JsonObject

    /**
     * Why [text] must not be loaded, imported or kept as a model, or null if it may be. Applies
     * to SDK model files only; anything else passes (see the class comment).
     *
     * A complete SDK file has `RC_MODEL_CFG`, a non-empty `channelOut` of channels with a
     * `chCfg`, a `mixesNum` that equals the length of `mixCfg` and limits that are not all 0.
     */
    fun fileProblem(text: String): String? {
        val root = parse(text) ?: return null
        if (root[FILE_KEY] !is JsonObject) return null
        if (root[ModelConfigFile.CONFIG_KEY] !is JsonObject) return "the model file has no ${ModelConfigFile.CONFIG_KEY} section"
        val section = root[CHANNELS_KEY] as? JsonArray ?: return "the model file has no $CHANNELS_KEY section"
        if (section.isEmpty()) return "the model file has no output channels"
        val summary = summarize(root) ?: return "the $CHANNELS_KEY section of the model file is damaged"
        summary.channels.firstOrNull { it.mixesNum != it.mixes.size }?.let {
            return "channel ${it.index} declares ${it.mixesNum} mixes but lists ${it.mixes.size}"
        }
        if (summary.channels.all { it.min == 0 && it.max == 0 }) return "all channel limits of the model file are 0"
        return null
    }

    /**
     * The document that replaces the SDK model file [target] when the model file [source] is
     * imported into it: everything of [source] (configuration, channels, mixes, notifications)
     * under the identity (`MODEL_FILE_CFG`: file flag, index, name, image, time stamp) of
     * [target]. Null if [source] is not a complete SDK model file ([fileProblem]) or [target] is
     * not an SDK model file.
     *
     * Needed because `rcImportModelCfg` does not copy the channels and mixes (assumption r12): the
     * target ends up with 16 empty channels, which loads as an empty configuration.
     */
    fun importInto(source: String, target: String): String? {
        val incoming = parse(source) ?: return null
        val existing = parse(target) ?: return null
        val identity = existing[FILE_KEY] as? JsonObject ?: return null
        if (incoming[FILE_KEY] !is JsonObject || fileProblem(source) != null) return null
        val merged = LinkedHashMap<String, JsonElement>()
        merged[FILE_KEY] = identity
        for ((key, value) in incoming) if (key != FILE_KEY) merged[key] = value
        return JsonObject(merged).toString()
    }

    /**
     * The channel and mix part of [text], or null if it is not an SDK model file or the
     * section cannot be read.
     */
    fun summarize(text: String): ModelFileSummary? = parse(text)?.let(::summarize)

    private fun summarize(root: JsonObject): ModelFileSummary? {
        val array = root[CHANNELS_KEY] as? JsonArray ?: return null
        val channels = ArrayList<FileChannel>(array.size)
        for (element in array) {
            val channel = element as? JsonObject ?: return null
            val config = channel["chCfg"] as? JsonObject ?: return null
            val mixes = (channel["mixCfg"] as? JsonArray ?: JsonArray(emptyList())).map { mix ->
                val obj = mix as? JsonObject ?: return null
                FileMix(
                    source = obj.int("srcIdx") ?: return null,
                    weightUp = obj.int("weightUp") ?: return null,
                    weightDown = obj.int("weightDown") ?: return null,
                    offset = obj.int("offset") ?: return null,
                )
            }
            channels += FileChannel(
                index = channel.int("chIdx") ?: return null,
                min = config.int("min") ?: return null,
                max = config.int("max") ?: return null,
                middle = config.int("middle") ?: return null,
                weight = config.int("weight") ?: return null,
                mixesNum = config.int("mixesNum") ?: return null,
                mixes = mixes,
            )
        }
        return ModelFileSummary(channels)
    }

    /**
     * Why [config] must never be written to the radio, or null. A working config without
     * channels, or whose channel limits are all 0, is what an incomplete model load leaves behind;
     * a real configuration always has limits (the factory default is -100..100).
     */
    fun workingProblem(config: WorkingConfig): String? = channelsProblem(config.channels)

    /** [workingProblem] for just the channel list. */
    fun channelsProblem(channels: List<OutputChannelConfig>): String? = when {
        channels.isEmpty() -> "the working configuration has no output channels"
        channels.all { it.min == 0 && it.max == 0 } -> "all channel limits of the working configuration are 0"
        else -> null
    }

    /**
     * How [config] differs from [file], or null if channel count, mix count and the key fields
     * (limits, middle, weight, mix source, weights and offset) agree. Reports at most a few
     * differences.
     */
    fun mismatch(file: ModelFileSummary, config: WorkingConfig): String? {
        val found = ArrayList<String>()
        fun note(text: String) {
            if (found.size < MAX_REPORTED) found += text
        }
        if (file.channels.size != config.channels.size) {
            note("${file.channels.size} channels in the file, ${config.channels.size} loaded")
        }
        if (file.mixCount != config.mixes.size) note("${file.mixCount} mixes in the file, ${config.mixes.size} loaded")
        for (expected in file.channels) {
            val actual = config.channel(expected.index)
            if (actual == null) {
                note("channel ${expected.index} was not loaded")
                continue
            }
            if (actual.min != expected.min || actual.max != expected.max || actual.middle != expected.middle || actual.weight != expected.weight) {
                note(
                    "channel ${expected.index}: limits ${expected.min}/${expected.max}/${expected.middle}/${expected.weight} " +
                        "in the file, ${actual.min}/${actual.max}/${actual.middle}/${actual.weight} loaded",
                )
            }
            if (actual.mixCount != expected.mixesNum) {
                note("channel ${expected.index}: ${expected.mixesNum} mixes in the file, ${actual.mixCount} loaded")
            }
            val loaded = config.mixesOf(expected.index)
            expected.mixes.forEachIndexed { i, mix ->
                val other = loaded.getOrNull(i)
                if (other == null) {
                    note("channel ${expected.index}: mix $i was not loaded")
                } else if (other.source.index != mix.source || other.weightUp != mix.weightUp || other.weightDown != mix.weightDown || other.offset != mix.offset) {
                    note("channel ${expected.index}: mix $i differs from the file")
                }
            }
        }
        return found.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    private fun parse(text: String): JsonObject? = try {
        parser.parseToJsonElement(text) as? JsonObject
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun JsonObject.int(key: String): Int? {
        val value: JsonElement = this[key] ?: return null
        return (value as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
    }
}
