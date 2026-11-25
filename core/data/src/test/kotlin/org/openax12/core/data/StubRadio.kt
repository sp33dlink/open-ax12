package org.openax12.core.data

import java.io.File
import kotlin.time.Duration
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.openax12.core.model.Ax12Json
import org.openax12.core.model.FailsafeMode
import org.openax12.core.model.CurveConfig
import org.openax12.core.model.DualRateSlot
import org.openax12.core.model.FirmwareInfo
import org.openax12.core.model.LiveSnapshot
import org.openax12.core.model.MixLine
import org.openax12.core.model.ModelConfigFile
import org.openax12.core.model.ModelInfo
import org.openax12.core.model.OutputChannelConfig
import org.openax12.core.model.RadioWidePatch
import org.openax12.core.model.RadioStatus
import org.openax12.core.model.RawSystemFields
import org.openax12.core.model.RfModuleMode
import org.openax12.core.model.SourceId
import org.openax12.core.model.SourceInfo
import org.openax12.core.model.StickMode
import org.openax12.core.model.SystemConfig
import org.openax12.core.model.SystemConfigValidity
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

    /**
     * Creates a model file directly in the SDK directory. Like a real SDK model file it carries an
     * `RC_MODEL_CFG` section with the radio-wide fields ([rf], [alarm], [stick]) and the per-model
     * [modelMatchId].
     */
    fun addModel(
        name: String,
        config: String = "config of $name",
        modelMatch: Long = 0,
        rf: Int = 0,
        alarm: Int = 33,
        stick: Int = 1,
        modelMatchId: Int = 0,
    ): ModelInfo {
        val index = nextIndex++
        val file = File(sdkDir, "model_$index.json")
        write(file, name, modelMatch, config, systemSection(rf, alarm, stick, modelMatchId))
        return ModelInfo(name = name, filePath = file.path, index = index, modelMatch = modelMatch)
    }

    /** A model file in the format of [addModel] for the import tests (not placed in the SDK directory). */
    fun modelFileText(name: String, rf: Int = 0, alarm: Int = 33, stick: Int = 1, modelMatchId: Int = 0): String =
        JsonObject(
            mapOf(
                "modelName" to JsonPrimitive(name),
                "modelMatch" to JsonPrimitive(0),
                "config" to JsonPrimitive("config of $name"),
                ModelConfigFile.CONFIG_KEY to systemSection(rf, alarm, stick, modelMatchId),
            ),
        ).toString()

    /** The raw system fields stored in an SDK model file. */
    fun systemOf(filePath: String): RawSystemFields? = read(File(filePath))?.let { ModelConfigFile.readSystem(it.toString()) }

    /** Number of `importModel` calls the SDK received. */
    var importCalls: Int = 0
        private set

    private fun systemSection(rf: Int, alarm: Int, stick: Int, modelMatchId: Int): JsonObject = JsonObject(
        mapOf(
            "alramVtg" to JsonPrimitive(alarm),
            "crsfBaudRate" to JsonPrimitive(170),
            "fsChVal" to JsonArray(List(16) { JsonPrimitive(0) }),
            "fsMode" to JsonPrimitive(0),
            "joytickBeep" to JsonPrimitive(0),
            "modelMatchId" to JsonPrimitive(modelMatchId),
            "preFlightCheckChannel" to JsonArray(List(36) { JsonPrimitive(127) }),
            "rfModuleMode" to JsonPrimitive(rf),
            "stickMode" to JsonPrimitive(stick),
        ),
    )

    /** The `config` stored in an SDK model file. */
    fun configOf(filePath: String): String? = read(File(filePath))?.get("config")?.jsonPrimitive?.content

    /** A completed device read: the device config lands in the working config and in the current model file. */
    fun simulateDeviceRead() {
        workingConfig = deviceConfig
        currentFile?.let { path ->
            val file = File(path)
            val json = read(file) ?: return@let
            write(file, json.name(), json.match(), deviceConfig, json.rc())
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

    override suspend fun createModelInfo(): ModelInfo? {
        val index = nextIndex++
        return ModelInfo(name = "Model $index", filePath = File(sdkDir, "model_$index.json").path, index = index)
    }

    override suspend fun updateModelInfo(model: ModelInfo): ModelInfo? {
        val file = File(model.filePath)
        val json = read(file) ?: return null
        write(file, model.name, model.modelMatch, json["config"]?.jsonPrimitive?.content.orEmpty(), json.rc())
        return model
    }

    override suspend fun saveWorkingConfigToModel(model: ModelInfo): Boolean {
        // A new model starts as a copy of the working config: it takes over the current radio-wide values.
        val file = File(model.filePath)
        write(file, model.name, model.modelMatch, workingConfig, read(file)?.rc() ?: currentFile?.let { read(File(it))?.rc() })
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
        importCalls++
        write(target, json.name(), json.match(), json["config"]!!.jsonPrimitive.content, json.rc())
        return true
    }

    override suspend fun deleteModel(modelFile: String): Boolean = File(modelFile).delete()

    override suspend fun saveConfigToDevice(timeout: Duration): TaskResult {
        deviceConfig = workingConfig
        currentFile?.let { path ->
            val file = File(path)
            read(file)?.let { write(file, it.name(), it.match(), workingConfig, it.rc()) }
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

    private fun write(file: File, name: String, modelMatch: Long, config: String, rc: JsonObject? = null) {
        val fields = linkedMapOf<String, kotlinx.serialization.json.JsonElement>(
            "modelName" to JsonPrimitive(name),
            "modelMatch" to JsonPrimitive(modelMatch),
            "config" to JsonPrimitive(config),
        )
        if (rc != null) fields[ModelConfigFile.CONFIG_KEY] = rc
        file.writeText(JsonObject(fields).toString())
    }

    private fun JsonObject.rc(): JsonObject? = this[ModelConfigFile.CONFIG_KEY] as? JsonObject

    private fun JsonObject.name(): String = this["modelName"]?.jsonPrimitive?.content.orEmpty()

    private fun JsonObject.match(): Long = runCatching { this["modelMatch"]!!.jsonPrimitive.long }.getOrDefault(0L)

    private fun indexOf(file: File): Long = file.name.removePrefix("model_").removeSuffix(".json").toLongOrNull() ?: 0L

    // -- system configuration: read from the current model file, like the SDK backend ----------------

    override suspend fun getSystemConfig(): SystemConfig? {
        val fields = currentFile?.let { systemOf(it) } ?: return null
        if (SystemConfigValidity.problem(fields) != null) return null
        return SystemConfig(
            failsafeMode = FailsafeMode.fromSdk(fields.failsafeMode),
            failsafeValues = fields.failsafeValues,
            stickMode = StickMode.fromSdk(fields.stickMode),
            preFlightCheck = fields.preFlightCheck,
            rfModuleMode = RfModuleMode.fromSdk(fields.rfModuleMode),
            alarmVoltageDeciVolt = fields.alarmVoltageDeciVolt,
            stickBeep = fields.stickBeep,
            modelMatchId = fields.modelMatchId,
            crsfBaudRate = fields.crsfBaudRate,
        )
    }

    /** Writes the radio-wide fields straight into the current model file (the real SDK does it on save). */
    override suspend fun setSystemConfig(config: SystemConfig): Boolean {
        val path = currentFile ?: return false
        val text = File(path).takeIf { it.isFile }?.readText() ?: return false
        val patch = RadioWidePatch(config.stickMode.sdk, config.rfModuleMode.sdk, config.alarmVoltageDeciVolt)
        val patched = ModelConfigFile.patch(text, patch) ?: return false
        File(path).writeText(patched)
        return true
    }

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
