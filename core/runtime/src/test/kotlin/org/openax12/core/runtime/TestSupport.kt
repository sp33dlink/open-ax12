package org.openax12.core.runtime

import android.net.Uri
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import org.openax12.core.audio.AudioEngine
import org.openax12.core.audio.SoundFile
import org.openax12.core.audio.SpokenText
import org.openax12.core.audio.TtsStatus
import org.openax12.core.data.CrsfStats
import org.openax12.core.data.DataError
import org.openax12.core.data.DataResult
import org.openax12.core.data.ExternalChange
import org.openax12.core.data.FlightLogFile
import org.openax12.core.data.FlightLogState
import org.openax12.core.data.FlightLogger
import org.openax12.core.data.HistoryPoint
import org.openax12.core.data.ImportValidation
import org.openax12.core.data.ModelEntry
import org.openax12.core.data.ModelRepository
import org.openax12.core.data.ModelSnapshot
import org.openax12.core.data.PictureCropRect
import org.openax12.core.data.ReconcileReport
import org.openax12.core.data.SettingsRepository
import org.openax12.core.data.StorageInfo
import org.openax12.core.data.TelemetryRepository
import org.openax12.core.model.AppSettings
import org.openax12.core.model.AudioCategory
import org.openax12.core.model.BeepPattern
import org.openax12.core.model.GpsFix
import org.openax12.core.model.HapticPattern
import org.openax12.core.model.ModelBlueprint
import org.openax12.core.model.ModelExtras
import org.openax12.core.model.SensorId
import org.openax12.core.model.SensorInfo
import org.openax12.core.model.SensorUnit
import org.openax12.core.model.SensorValue
import org.openax12.core.model.SpeechPriority
import org.openax12.core.model.ToneStep
import org.openax12.core.model.VarioTone
import org.openax12.core.radio.Radio
import org.openax12.core.radio.getCurrentModel

/** English phrases for tests (production reads them from string resources). */
internal val TEST_PHRASES = RuntimePhrases(
    telemetryLost = "Telemetry lost",
    telemetryRecovered = "Telemetry recovered",
    lowTxVoltage = "Transmitter battery low",
    timerExpiredFormat = "Timer %1\$d expired",
    switchUp = "up",
    switchMiddle = "middle",
    switchDown = "down",
)

/** An [AudioEngine] that only records what it was asked to do. */
internal class RecordingAudio : AudioEngine {
    data class Spoken(val text: String, val priority: SpeechPriority, val category: AudioCategory)

    val spoken = CopyOnWriteArrayList<Spoken>()
    val beeps = CopyOnWriteArrayList<BeepPattern>()
    val haptics = CopyOnWriteArrayList<HapticPattern>()
    val varioTones = CopyOnWriteArrayList<VarioTone>()
    val sounds = CopyOnWriteArrayList<String>()
    val music = CopyOnWriteArrayList<String?>()
    val values = CopyOnWriteArrayList<Triple<Double, SensorUnit, String?>>()
    val calls = CopyOnWriteArrayList<String>()
    var overrideVolume: Int? = null
    var varioStops = 0
    var master = -1
    val categoryVolumes = HashMap<AudioCategory, Int>()
    var speech: Pair<String, Float>? = null
    var hapticsEnabled: Boolean? = null
    var missingSounds: Set<String> = emptySet()

    private val _tts = MutableStateFlow<TtsStatus>(TtsStatus.Initializing)
    private val _lastSpoken = MutableStateFlow<SpokenText?>(null)
    override val ttsStatus: StateFlow<TtsStatus> = _tts.asStateFlow()
    override val lastSpoken: StateFlow<SpokenText?> = _lastSpoken.asStateFlow()
    override val isSpeaking: StateFlow<Boolean> = MutableStateFlow(false)
    override val musicPlaying: StateFlow<Boolean> = MutableStateFlow(false)

    fun setTts(status: TtsStatus) {
        _tts.value = status
    }

    val spokenTexts: List<String> get() = spoken.map { it.text }

    override fun start() {
        calls += "audio.start"
    }

    override fun shutdown() {
        calls += "audio.shutdown"
    }

    override fun speak(text: String, priority: SpeechPriority, category: AudioCategory, flush: Boolean) {
        spoken += Spoken(text, priority, category)
        _lastSpoken.value = SpokenText(text, priority, spoken.size.toLong())
    }

    override fun speakValue(
        value: Double,
        unit: SensorUnit,
        precision: Int,
        name: String?,
        priority: SpeechPriority,
        category: AudioCategory,
    ) {
        values += Triple(value, unit, name)
    }

    override fun spokenValue(value: Double, unit: SensorUnit, precision: Int): String = "$value ${unit.symbol}"
    override fun setSpeech(language: String, rate: Float) {
        speech = language to rate
    }

    override suspend fun availableLanguages(): List<String> = emptyList()
    override fun beep(pattern: BeepPattern, category: AudioCategory) {
        beeps += pattern
    }

    override fun tone(frequencyHz: Int, durationMs: Int, category: AudioCategory) = Unit
    override fun tones(steps: List<ToneStep>, category: AudioCategory) = Unit
    override fun setVarioTone(tone: VarioTone) {
        varioTones += tone
    }

    override fun stopVario() {
        varioStops++
    }

    override fun playSound(file: String, priority: SpeechPriority, category: AudioCategory): Boolean {
        if (file in missingSounds) return false
        sounds += file
        return true
    }

    override fun startMusic(file: String, loop: Boolean): Boolean {
        if (file in missingSounds) return false
        music += file
        return true
    }

    override fun pauseMusic() = Unit
    override fun stopMusic() {
        music += null
    }

    override suspend fun listSoundFiles(): List<SoundFile> = emptyList()
    override fun haptic(pattern: HapticPattern) {
        haptics += pattern
    }

    override fun setHapticsEnabled(enabled: Boolean) {
        hapticsEnabled = enabled
    }

    override fun setVolume(category: AudioCategory, percent: Int) {
        categoryVolumes[category] = percent
    }

    override fun setMasterVolume(percent: Int) {
        master = percent
    }

    override fun setVolumeOverride(percent: Int?) {
        overrideVolume = percent
    }

    override fun flush(category: AudioCategory?) {
        calls += "audio.flush:$category"
    }

    override fun stopAll() = Unit
}

/** A [TelemetryRepository] with settable values. */
internal class FakeTelemetry : TelemetryRepository {
    private val _sensors = MutableStateFlow<Map<SensorId, SensorValue>>(emptyMap())
    override val sensors: StateFlow<Map<SensorId, SensorValue>> = _sensors.asStateFlow()
    override val sensorList: StateFlow<List<SensorInfo>> = MutableStateFlow(emptyList())
    override val linkLost: StateFlow<Boolean> = MutableStateFlow(false)
    override val lastKnownPosition: StateFlow<GpsFix?> = MutableStateFlow(null)
    override val homePosition: StateFlow<GpsFix?> = MutableStateFlow(null)
    override val crsfStats: StateFlow<CrsfStats> = MutableStateFlow(CrsfStats())

    val published = CopyOnWriteArrayList<Pair<SensorId, SensorValue>>()
    val calls = CopyOnWriteArrayList<String>()

    fun set(id: SensorId, value: Double, lost: Boolean = false) {
        _sensors.value = _sensors.value + (id to SensorValue(value = value, precision = 1, lost = lost))
    }

    override fun sensor(id: SensorId): Flow<SensorValue?> = flowOf(_sensors.value[id])
    override fun value(id: SensorId): SensorValue? = _sensors.value[id]
    override fun info(id: SensorId): SensorInfo? = null
    override fun history(id: SensorId, windowSec: Int): List<HistoryPoint> = emptyList()
    override suspend fun resetMinMax(id: SensorId?) {
        calls += "resetMinMax"
    }

    override suspend fun setHome(fix: GpsFix?) = Unit
    override suspend fun resetFlight() {
        calls += "resetFlight"
    }

    override fun publish(id: SensorId, value: SensorValue, info: SensorInfo?) {
        published += id to value
    }

    override fun start() {
        calls += "telemetry.start"
    }

    override fun stop() {
        calls += "telemetry.stop"
    }
}

internal class FakeSettings(initial: AppSettings = AppSettings()) : SettingsRepository {
    private val state = MutableStateFlow(initial)
    override val settings: StateFlow<AppSettings> = state.asStateFlow()
    override suspend fun awaitLoaded(): AppSettings = state.value
    override suspend fun update(transform: (AppSettings) -> AppSettings): AppSettings {
        state.value = transform(state.value)
        return state.value
    }
}

internal class FakeFlightLogger : FlightLogger {
    private val _state = MutableStateFlow<FlightLogState>(FlightLogState.Idle)
    override val state: StateFlow<FlightLogState> = _state.asStateFlow()
    var starts = 0
    var stops = 0
    var failStart = false

    override suspend fun start(): DataResult<FlightLogFile> {
        starts++
        if (failStart) return DataResult.Failure(DataError.IO, "disk full")
        val file = FlightLogFile(name = "log.csv", path = "/tmp/log.csv")
        _state.value = FlightLogState.Recording(file, startedMs = 0)
        return DataResult.Success(file)
    }

    override suspend fun stop() {
        stops++
        _state.value = FlightLogState.Idle
    }

    override suspend fun logs(): List<FlightLogFile> = emptyList()
    override suspend fun delete(log: FlightLogFile): DataResult<Unit> = DataResult.Success(Unit)
    override suspend fun share(log: FlightLogFile): DataResult<Uri> = DataResult.Failure(DataError.NOT_FOUND)
}

/**
 * A [ModelRepository] for the session tests: the models are the radio's own (SDK files of the
 * `FakeRadio`), extras are kept in memory with the staged / saved split of the real repository.
 * Only what `ConfigSession` and the runtime use is implemented.
 */
internal class FakeModels(private val radio: Radio) : ModelRepository {
    private val _models = MutableStateFlow<List<ModelEntry>>(emptyList())
    private val _current = MutableStateFlow<ModelEntry?>(null)
    private val _inSync = MutableStateFlow(true)
    private val _extras = MutableStateFlow(ModelExtras())
    private val _staged = MutableStateFlow(false)
    private val saved = HashMap<String, ModelExtras>()

    val calls = CopyOnWriteArrayList<String>()
    var savedCount = 0
    var writtenCount = 0

    override val models: StateFlow<List<ModelEntry>> = _models.asStateFlow()
    override val currentModel: StateFlow<ModelEntry?> = _current.asStateFlow()
    override val deviceInSync: StateFlow<Boolean> = _inSync.asStateFlow()
    override val currentExtras: StateFlow<ModelExtras> = _extras.asStateFlow()
    override val hasStagedExtras: StateFlow<Boolean> = _staged.asStateFlow()
    override val externalChanges: StateFlow<List<ExternalChange>> = MutableStateFlow(emptyList())
    override val storage: StateFlow<StorageInfo> = MutableStateFlow(StorageInfo())

    /** Replaces the extras of the current model (saved and working), as `updateExtras` would. */
    fun setExtras(extras: ModelExtras) {
        _extras.value = extras
        _current.value?.let { saved[it.id] = extras }
    }

    suspend fun refresh() {
        val current = radio.getCurrentModel()
        _models.value = radio.getModels().map { ModelEntry(id = it.filePath, name = it.name, sdk = it, isCurrent = it.filePath == current?.filePath) }
        _current.value = _models.value.firstOrNull { it.isCurrent }
        _current.value?.let { saved.getOrPut(it.id) { ModelExtras() } }
    }

    override suspend fun reconcileLibrary(): DataResult<ReconcileReport> {
        calls += "reconcile"
        refresh()
        _current.value?.let { _extras.value = saved.getValue(it.id) }
        return DataResult.Success(ReconcileReport(problems = listOf("a problem")))
    }

    override suspend fun refreshStorage(): StorageInfo = StorageInfo()
    override suspend fun acceptExternalChange(change: ExternalChange): DataResult<ModelEntry> = unused()
    override suspend fun rejectExternalChange(change: ExternalChange): DataResult<Unit> = unused()

    override suspend fun createModel(name: String, blueprint: ModelBlueprint): DataResult<ModelEntry> {
        val info = radio.createModelInfo() ?: return DataResult.Failure(DataError.RADIO_FAILED, "createModelInfo")
        val named = info.copy(name = name)
        if (!radio.saveWorkingConfigToModel(named)) return DataResult.Failure(DataError.RADIO_FAILED, "saveWorkingConfigToModel")
        saved[named.filePath] = blueprint.extras
        refresh()
        return DataResult.Success(_models.value.first { it.id == named.filePath })
    }

    override suspend fun duplicateModel(modelId: String, newName: String): DataResult<ModelEntry> = unused()
    override suspend fun renameModel(modelId: String, newName: String): DataResult<ModelEntry> = unused()
    override suspend fun setModelMatch(modelId: String, modelMatch: Long): DataResult<ModelEntry> = unused()

    override suspend fun deleteModel(modelId: String): DataResult<Unit> {
        calls += "delete:$modelId"
        if (modelId == _current.value?.id) return DataResult.Failure(DataError.IS_CURRENT_MODEL)
        if (!radio.deleteModel(modelId)) return DataResult.Failure(DataError.NOT_FOUND)
        saved.remove(modelId)
        refresh()
        return DataResult.Success(Unit)
    }

    override suspend fun selectModel(modelId: String): DataResult<ModelEntry> {
        calls += "select:$modelId"
        if (_models.value.none { it.id == modelId }) return DataResult.Failure(DataError.NOT_FOUND)
        return load(modelId)
    }

    override suspend fun reloadCurrentModel(): DataResult<ModelEntry> {
        calls += "reload"
        val id = _current.value?.id ?: return DataResult.Failure(DataError.NOT_FOUND)
        return load(id)
    }

    private suspend fun load(modelId: String): DataResult<ModelEntry> {
        if (!radio.loadModelFile(modelId)) return DataResult.Failure(DataError.RADIO_FAILED, "loadModelFile")
        _inSync.value = false
        refresh()
        _extras.value = saved.getValue(modelId)
        _staged.value = false
        return DataResult.Success(_current.value!!)
    }

    override suspend fun updateExtras(transform: (ModelExtras) -> ModelExtras): ModelExtras {
        val updated = transform(_extras.value)
        setExtras(updated)
        return updated
    }

    override suspend fun stageExtras(transform: (ModelExtras) -> ModelExtras): ModelExtras {
        _extras.value = transform(_extras.value)
        _staged.value = true
        return _extras.value
    }

    override suspend fun revertStagedExtras(): ModelExtras {
        calls += "revertStaged"
        _current.value?.let { _extras.value = saved.getValue(it.id) }
        _staged.value = false
        return _extras.value
    }

    override suspend fun readExtras(modelId: String): DataResult<ModelExtras> = unused()
    override suspend fun updateExtrasOf(modelId: String, transform: (ModelExtras) -> ModelExtras): DataResult<ModelExtras> = unused()

    override suspend fun onDeviceConfigSaved(): DataResult<ModelSnapshot> {
        savedCount++
        val id = _current.value?.id ?: return DataResult.Failure(DataError.NOT_FOUND)
        saved[id] = _extras.value
        _staged.value = false
        _inSync.value = true
        return DataResult.Success(ModelSnapshot(id = "snap$savedCount", modelId = id, timeMs = 0))
    }

    override suspend fun onDeviceConfigWritten(): DataResult<ModelEntry> {
        writtenCount++
        _inSync.value = true
        return DataResult.Success(_current.value ?: return DataResult.Failure(DataError.NOT_FOUND))
    }

    override suspend fun setPicture(modelId: String, source: Uri, crop: PictureCropRect): DataResult<ModelEntry> = unused()
    override suspend fun removePicture(modelId: String): DataResult<ModelEntry> = unused()
    override suspend fun exportModel(modelId: String): DataResult<Uri> = unused()
    override suspend fun inspectImport(source: Uri): ImportValidation = unused()
    override suspend fun importModel(source: Uri, name: String?): DataResult<ModelEntry> = unused()
    override suspend fun listSnapshots(modelId: String): List<ModelSnapshot> = emptyList()
    override suspend fun restoreSnapshot(snapshot: ModelSnapshot): DataResult<ModelEntry> = unused()
    override suspend fun deleteSnapshot(snapshot: ModelSnapshot): DataResult<Unit> = unused()

    private fun <T> unused(): T = error("not used by the runtime tests")
}
