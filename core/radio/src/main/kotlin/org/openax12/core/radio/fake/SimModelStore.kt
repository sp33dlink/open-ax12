package org.openax12.core.radio.fake

import java.io.File
import kotlinx.serialization.KSerializer
import org.openax12.core.model.Ax12Json
import org.openax12.core.model.ModelInfo

/**
 * Persistent side of the simulator: what the real SDK keeps in its private data directory and
 * the radio keeps in its MCU.
 *
 * ```
 * <directory>/simulator.json          SimStateFile: current model, device configuration, ...
 * <directory>/models/model_<n>.json   SimModelFile, one per model
 * <directory>/templates/<name>.json   SimModelFile, one per stock template
 * ```
 *
 * With a null directory nothing is persisted: models live in memory under virtual paths
 * (`/simulator/models/model_<n>.json`), which is what previews and most tests use. Export and
 * import still work on real files in that case.
 *
 * All file errors are swallowed: like the SDK the simulator reports failure through its return
 * values only. Not thread-safe (confined to the simulator worker).
 */
internal class SimModelStore(directory: File?) {

    private val directory: File? = directory?.absoluteFile
    private val modelsDir: File = File(this.directory ?: File(VIRTUAL_ROOT), MODELS_DIR)
    private val templatesDir: File = File(this.directory ?: File(VIRTUAL_ROOT), TEMPLATES_DIR)
    private val stateFile: File? = this.directory?.let { File(it, STATE_FILE) }

    private val models = LinkedHashMap<String, SimModelFile>()
    private val templates = LinkedHashMap<String, SimModelFile>()
    private var loaded = false

    /** The simulator state as last loaded or saved. */
    var state: SimStateFile = SimStateFile()
        private set

    /** Path of the current model file, or null. */
    val currentModelPath: String?
        get() = state.currentModelFile?.let { File(modelsDir, it).path }?.takeIf { it in models }

    /**
     * Reads everything from disk (once; later calls do nothing). On the very first start — no
     * model exists — it creates the default 4-channel plane as the current model, makes it the
     * device configuration and writes the stock templates.
     */
    fun load() {
        if (loaded) return
        loaded = true
        state = stateFile?.let { read(it, SimStateFile.serializer()) } ?: SimStateFile()
        state = state.copy(device = state.device?.normalized())
        for (file in listJson(modelsDir)) {
            val model = read(file, SimModelFile.serializer())?.takeIf { it.format == SimModelFile.FORMAT } ?: continue
            models[file.path] = model.copy(config = model.config.normalized())
        }
        for (file in listJson(templatesDir)) {
            val template = read(file, SimModelFile.serializer())?.takeIf { it.format == SimModelFile.FORMAT } ?: continue
            templates[file.path] = template.copy(config = template.config.normalized())
        }
        if (templates.isEmpty()) {
            writeTemplate("Plane", SimDefaults.plane())
            writeTemplate("Glider", SimDefaults.glider())
            writeTemplate("Multirotor", SimDefaults.multirotor())
            writeTemplate("Helicopter", SimDefaults.helicopter())
        }
        val highestIndex = models.values.maxOfOrNull { it.index } ?: 0L
        if (state.lastModelIndex < highestIndex) state = state.copy(lastModelIndex = highestIndex)
        if (models.isEmpty()) {
            val index = state.lastModelIndex + 1
            val path = pathOfIndex(index)
            val plane = SimDefaults.plane()
            writeModel(path, SimModelFile(name = DEFAULT_MODEL_NAME, index = index, config = plane))
            state = state.copy(currentModelFile = File(path).name, lastModelIndex = index, device = plane)
        } else if (currentModelPath == null) {
            // The state file is gone or points to a deleted model: take the first model.
            val (path, model) = models.entries.minByOrNull { it.value.index }!!
            state = state.copy(currentModelFile = File(path).name, device = state.device ?: model.config)
        }
        if (state.device == null) state = state.copy(device = currentModel()?.config ?: SimDefaults.factory())
        saveState()
    }

    /** All models ordered by their index. */
    fun modelInfos(): List<ModelInfo> = models.entries.sortedBy { it.value.index }.map { it.value.info(it.key) }

    /** All templates in name order; their index is the position in this list. */
    fun templateInfos(): List<ModelInfo> = templates.entries
        .sortedBy { it.value.name.lowercase() }
        .mapIndexed { i, entry -> entry.value.info(entry.key).copy(index = i.toLong()) }

    /** The model stored at [path], or null. */
    fun model(path: String): SimModelFile? = models[normalize(path)]

    /** The current model, or null. */
    fun currentModel(): SimModelFile? = currentModelPath?.let { models[it] }

    /** True if [path] names a file directly inside the models directory (existing or not). */
    fun isModelPath(path: String): Boolean {
        if (path.isBlank()) return false
        val file = File(normalize(path))
        return file.parentFile?.path == modelsDir.path && file.name.endsWith(JSON_SUFFIX) && file.name.length > JSON_SUFFIX.length
    }

    /** Hands out the next model index and the file path that belongs to it. */
    fun nextModel(): Pair<Long, String> {
        var index = state.lastModelIndex + 1
        while (pathOfIndex(index) in models) index++
        updateState(state.copy(lastModelIndex = index))
        return index to pathOfIndex(index)
    }

    /** Stores [model] at [path] (which must satisfy [isModelPath]). Returns false if it could not be written. */
    fun writeModel(path: String, model: SimModelFile): Boolean {
        val key = normalize(path)
        if (!isModelPath(key)) return false
        if (directory != null && !write(File(key), SimModelFile.serializer(), model)) return false
        models[key] = model
        return true
    }

    /** Deletes the model at [path]. Returns false if there is none. */
    fun deleteModel(path: String): Boolean {
        val key = normalize(path)
        if (models.remove(key) == null) return false
        if (directory != null) runCatching { File(key).delete() }
        return true
    }

    /** Makes the model at [path] the current one. */
    fun setCurrent(path: String) {
        updateState(state.copy(currentModelFile = File(normalize(path)).name))
    }

    /** Replaces the state and persists it. */
    fun updateState(newState: SimStateFile) {
        state = newState
        saveState()
    }

    /** Replaces the state without writing it (for values that change often; [saveState] follows later). */
    fun updateStateLazily(newState: SimStateFile) {
        state = newState
    }

    /** Writes the state file. */
    fun saveState() {
        stateFile?.let { write(it, SimStateFile.serializer(), state) }
    }

    /**
     * Reads a model for an import: a template or model of this store by its path, or any file on
     * disk in the simulator's format. Null if it does not exist or is not a simulator model.
     */
    fun readForImport(path: String): SimModelFile? {
        val key = normalize(path)
        templates[key]?.let { return it }
        models[key]?.let { return it }
        val file = File(path)
        if (!file.isFile || file.length() > MAX_IMPORT_BYTES) return null
        // The marker has a default value, so it must be checked in the raw JSON: a foreign file
        // without it would otherwise decode as a valid (empty) model.
        val marker = runCatching {
            (Ax12Json.parseToJsonElement(file.readText()) as? kotlinx.serialization.json.JsonObject)
                ?.get("format")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
        }.getOrNull()
        if (marker != SimModelFile.FORMAT) return null
        val model = read(file, SimModelFile.serializer()) ?: return null
        return model.copy(config = model.config.normalized())
    }

    /** Writes [model] to an arbitrary [destination] file (export). */
    fun export(model: SimModelFile, destination: String): Boolean {
        if (destination.isBlank()) return false
        return write(File(destination), SimModelFile.serializer(), model)
    }

    /** Stores [config] as the template [name], replacing a template of the same name. */
    fun writeTemplate(name: String, config: SimConfig): Boolean {
        val clean = name.trim()
        if (clean.isEmpty()) return false
        val slug = clean.lowercase().map { if (it.isLetterOrDigit()) it else '_' }.joinToString("").take(MAX_FILE_NAME)
        val file = File(templatesDir, slug + JSON_SUFFIX)
        val template = SimModelFile(name = clean, config = config)
        if (directory != null && !write(file, SimModelFile.serializer(), template)) return false
        templates[file.path] = template
        return true
    }

    private fun pathOfIndex(index: Long): String = File(modelsDir, "model_$index$JSON_SUFFIX").path

    private fun normalize(path: String): String = File(path).path

    private fun listJson(dir: File): List<File> {
        if (directory == null) return emptyList()
        return runCatching {
            dir.listFiles { file -> file.isFile && file.name.endsWith(JSON_SUFFIX) }?.sortedBy { it.name }
        }.getOrNull().orEmpty()
    }

    private fun <T> read(file: File, serializer: KSerializer<T>): T? = runCatching {
        if (!file.isFile) null else Ax12Json.decodeFromString(serializer, file.readText())
    }.getOrNull()

    /** Writes through a temporary file so that a crash never leaves a half-written file behind. */
    private fun <T> write(file: File, serializer: KSerializer<T>, value: T): Boolean = runCatching {
        file.absoluteFile.parentFile?.mkdirs()
        val temporary = File(file.absoluteFile.parentFile, file.name + ".tmp")
        temporary.writeText(Ax12Json.encodeToString(serializer, value))
        if (!temporary.renameTo(file)) {
            file.delete()
            check(temporary.renameTo(file)) { "cannot replace ${file.name}" }
        }
        true
    }.getOrDefault(false)

    companion object {
        const val STATE_FILE = "simulator.json"
        const val MODELS_DIR = "models"
        const val TEMPLATES_DIR = "templates"
        const val DEFAULT_MODEL_NAME = "Plane"

        /** Root of the paths handed out when nothing is persisted. */
        const val VIRTUAL_ROOT = "/simulator"

        private const val JSON_SUFFIX = ".json"
        private const val MAX_FILE_NAME = 48
        private const val MAX_IMPORT_BYTES = 4L * 1024 * 1024
    }
}
