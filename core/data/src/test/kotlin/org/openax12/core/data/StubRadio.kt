package org.openax12.core.data

import java.io.File
import kotlin.time.Duration
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.openax12.core.model.Ax12Json
import org.openax12.core.model.CurveConfig
import org.openax12.core.model.DualRateSlot
import org.openax12.core.model.FirmwareInfo
import org.openax12.core.model.LiveSnapshot
import org.openax12.core.model.MixLine
import org.openax12.core.model.ModelInfo
import org.openax12.core.model.OutputChannelConfig
import org.openax12.core.model.RadioStatus
import org.openax12.core.model.SourceId
import org.openax12.core.model.SourceInfo
import org.openax12.core.model.SystemConfig
import org.openax12.core.model.TelemetryAlarm
import org.openax12.core.model.TelemetryItem
import org.openax12.core.model.TimerConfig
import org.openax12.core.model.Trims
import org.openax12.core.radio.ConnectionState
import org.openax12.core.radio.Radio
import org.openax12.core.radio.RadioEvent
import org.openax12.core.radio.SimulatorControl
import org.openax12.core.radio.TaskResult

/**
 * A [Radio] for the data-layer tests: real model files in [sdkDir] (small JSON objects with a
 * name, a model-match number and a `config` string standing in for the configuration), a working
 * config (one string), and the SDK behaviours the model library depends on. Everything else
 * answers "nothing".
 *
 * @property importNeedsExistingTarget models an SDK whose `rcImportModelCfg` fails for a target
 *   file that does not exist yet
 */
class StubRadio(private val sdkDir: File, var importNeedsExistingTarget: Boolean = false) : Radio {

    override val connection = MutableStateFlow<ConnectionState>(ConnectionState.Ready)
    override val live = MutableStateFlow(LiveSnapshot.EMPTY)
    override val events = MutableSharedFlow<RadioEvent>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val configRevision = MutableStateFlow(0L)
    override val sdkVersion: String = "stub"
    override val isSimulator: Boolean = false
    override val simulator: SimulatorControl? = null

    /** The working config. */
    var workingConfig: String = "default"

    /** What the device holds. */
    var deviceConfig: String = "default"

    /** Path of the SDK's current model file. */
    var currentFile: String? = null

    var telemetryItems: List<TelemetryItem> = emptyList()

    private var nextIndex = 1L

    init {
        sdkDir.mkdirs()
    }

    // -- test helpers ---------------------------------------------------------------------------

    /** Creates a model file directly in the SDK directory. */
    fun addModel(name: String, config: String = "config of $name", modelMatch: Long = 0): ModelInfo {
        val index = nextIndex++
        val file = File(sdkDir, "model_$index.json")
        write(file, name, modelMatch, config)
        return ModelInfo(name = name, filePath = file.path, index = index, modelMatch = modelMatch)
    }

    /** The `config` stored in an SDK model file. */
    fun configOf(filePath: String): String? = read(File(filePath))?.get("config")?.jsonPrimitive?.content

    /** A completed device read: the device config lands in the working config and in the current model file. */
    fun simulateDeviceRead() {
        workingConfig = deviceConfig
        currentFile?.let { path ->
            val file = File(path)
            val json = read(file) ?: return@let
            write(file, json.name(), json.match(), deviceConfig)
        }
        events.tryEmit(RadioEvent.ConfigRead)
    }

    // -- models ---------------------------------------------------------------------------------

    override suspend fun getModels(): List<ModelInfo> =
        sdkDir.listFiles().orEmpty().filter { it.name.endsWith(".json") }.mapNotNull { file ->
            val json = read(file) ?: return@mapNotNull null
            ModelInfo(name = json.name(), filePath = file.path, index = indexOf(file), modelMatch = json.match())
        }.sortedBy { it.index }

    override suspend fun getCurrentModelIndex(): Int? =
        currentFile?.let { path -> getModels().firstOrNull { it.filePath == path }?.index?.toInt() }

    /**
     * The next [collidingCreates] calls of [createModelInfo] return the path of the previous call
     * again, like the real SDK that names model files after the current second.
     */
    var collidingCreates: Int = 0

    private var lastCreated: ModelInfo? = null

    override suspend fun createModelInfo(): ModelInfo? {
        lastCreated?.takeIf { collidingCreates > 0 }?.let {
            collidingCreates--
            return it
        }
        return newTarget().also { lastCreated = it }
    }

    private fun newTarget(): ModelInfo {
        val index = nextIndex++
        return ModelInfo(name = "Model $index", filePath = File(sdkDir, "model_$index.json").path, index = index)
    }

    override suspend fun updateModelInfo(model: ModelInfo): ModelInfo? {
        val file = File(model.filePath)
        val json = read(file) ?: return null
        write(file, model.name, model.modelMatch, json["config"]?.jsonPrimitive?.content.orEmpty())
        return model
    }

    override suspend fun saveWorkingConfigToModel(model: ModelInfo): Boolean {
        write(File(model.filePath), model.name, model.modelMatch, workingConfig)
        return true
    }

    override suspend fun loadModelFile(modelFile: String): Boolean {
        val json = read(File(modelFile)) ?: return false
        workingConfig = json["config"]?.jsonPrimitive?.content.orEmpty()
        currentFile = modelFile
        configRevision.value++
        return true
    }

    override suspend fun exportModel(modelFile: String, destinationFile: String): Boolean {
        val source = File(modelFile)
        if (!source.isFile) return false
        source.copyTo(File(destinationFile), overwrite = true)
        return true
    }

    override suspend fun importModel(importFile: String, targetModelFile: String): Boolean {
        val json = read(File(importFile)) ?: return false
        if (json["config"] == null) return false
        val target = File(targetModelFile)
        if (!target.isFile && importNeedsExistingTarget) return false
        write(target, json.name(), json.match(), json["config"]!!.jsonPrimitive.content)
        return true
    }

    override suspend fun deleteModel(modelFile: String): Boolean = File(modelFile).delete()

    override suspend fun saveConfigToDevice(timeout: Duration): TaskResult {
        deviceConfig = workingConfig
        currentFile?.let { path ->
            val file = File(path)
            read(file)?.let { write(file, it.name(), it.match(), workingConfig) }
        }
        events.tryEmit(RadioEvent.ConfigWritten)
        return TaskResult.COMPLETED
    }

    override suspend fun readConfigFromDevice(timeout: Duration): TaskResult {
        simulateDeviceRead()
        return TaskResult.COMPLETED
    }

    override suspend fun getTelemetryList(uiOnly: Boolean): List<TelemetryItem> = telemetryItems

    private fun read(file: File): JsonObject? = try {
        if (file.isFile) Ax12Json.parseToJsonElement(file.readText()).jsonObject else null
    } catch (_: Exception) {
        null
    }

    private fun write(file: File, name: String, modelMatch: Long, config: String) {
        val json = JsonObject(
            mapOf(
                "modelName" to JsonPrimitive(name),
                "modelMatch" to JsonPrimitive(modelMatch),
                "config" to JsonPrimitive(config),
            ),
        )
        file.writeText(json.toString())
    }

    private fun JsonObject.name(): String = this["modelName"]?.jsonPrimitive?.content.orEmpty()

    private fun JsonObject.match(): Long = runCatching { this["modelMatch"]!!.jsonPrimitive.long }.getOrDefault(0L)

    private fun indexOf(file: File): Long = file.name.removePrefix("model_").removeSuffix(".json").toLongOrNull() ?: 0L

    // -- everything the data layer does not use -------------------------------------------------

    override suspend fun start(): ConnectionState = connection.value
    override suspend fun stop() = Unit
    override suspend fun resetToDefaults(): Boolean = false
    override suspend fun getSources(): List<SourceInfo> = emptyList()
    override suspend fun getOutputChannels(): List<OutputChannelConfig> = emptyList()
    override suspend fun getTrims(): Trims? = null
    override suspend fun getRadioStatus(): RadioStatus? = null
    override suspend fun setOutputChannel(config: OutputChannelConfig): Boolean = false
    override suspend fun getMix(channel: Int, mixIndex: Int): MixLine? = null
    override suspend fun setMix(mix: MixLine): Boolean = false
    override suspend fun addMix(channel: Int, source: SourceId): MixLine? = null
    override suspend fun deleteMix(channel: Int, source: SourceId): Int? = null
    override suspend fun getDualRate(channel: Int, slot: Int): DualRateSlot? = null
    override suspend fun findFreeDualRateSlot(channel: Int): DualRateSlot? = null
    override suspend fun setDualRate(slot: DualRateSlot): Int? = null
    override suspend fun deleteDualRate(slot: DualRateSlot): Boolean = false
    override suspend fun getCurves(): List<CurveConfig> = emptyList()
    override suspend fun getCurve(index: Int): CurveConfig? = null
    override suspend fun setCurve(curve: CurveConfig): Boolean = false
    override suspend fun applyCurve(curveIndex: Int, value: Double): Double = value
    override suspend fun getTimerConfig(index: Int, isCounter: Boolean): TimerConfig? = null
    override suspend fun setTimerConfig(config: TimerConfig, isCounter: Boolean): Boolean = false
    override suspend fun resetTimer(deviceIndex: Int): Boolean = false
    override suspend fun getSystemConfig(): SystemConfig? = null
    override suspend fun setSystemConfig(config: SystemConfig): Boolean = false
    override suspend fun skipPreFlightCheck(): Boolean = false
    override suspend fun setStickCalibration(active: Boolean): Boolean = false
    override suspend fun setUsbSerialPassthrough(mode: Int): Boolean = false
    override suspend fun rebootRfModule(): Boolean = false
    override suspend fun getFirmwareInfo(): FirmwareInfo? = null
    override suspend fun requestDeviceInfo(): Boolean = false
    override suspend fun getTemplates(): List<ModelInfo> = emptyList()
    override suspend fun saveModelAsTemplate(modelFile: String, templateName: String): Boolean = false
    override suspend fun getTelemetryAlarms(): List<TelemetryAlarm> = emptyList()
    override suspend fun getTelemetryAlarm(hash: Long, index: Int): TelemetryAlarm? = null
    override suspend fun setTelemetryAlarm(hash: Long, alarm: TelemetryAlarm, index: Int): Boolean = false
    override suspend fun deleteTelemetryAlarm(index: Int): Boolean = false
    override suspend fun pollTelemetryNotification(): TelemetryAlarm? = null
    override suspend fun requestElrsMenu(deviceAddress: Int): Boolean = false
    override suspend fun sendElrsMenuCommand(index: Int, value: Int): Boolean = false
    override suspend fun requestElrsStatus(): Boolean = false
    override suspend fun sendCrsfFrame(data: ByteArray): Boolean = false
}
