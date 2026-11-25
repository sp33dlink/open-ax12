package org.openax12.feature.telemetry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.openax12.core.model.AlarmOutputs
import org.openax12.core.model.BeepPattern
import org.openax12.core.model.CompareOp
import org.openax12.core.model.SensorAlarm
import org.openax12.core.model.SensorInfo
import org.openax12.core.model.Sensors
import org.openax12.core.model.newItemId

/**
 * What the alarm screen shows: the list, or the editor while [draft] is not null.
 *
 * @property draft the alarm being edited; null shows the list
 * @property isNewDraft the draft is not stored yet
 * @property activeIds ids of the alarms that are raised right now
 * @property soundFiles names of the user sound files offered for the alarm
 */
internal data class AlarmUiState(
    val alarms: List<SensorAlarm> = emptyList(),
    val sensors: List<SensorInfo> = emptyList(),
    val activeIds: Set<String> = emptySet(),
    val draft: SensorAlarm? = null,
    val isNewDraft: Boolean = false,
    val soundFiles: List<String> = emptyList(),
) {
    /** The registry entry of the draft's sensor, or null. */
    val draftSensor: SensorInfo? get() = draft?.let { alarm -> sensors.firstOrNull { it.id == alarm.sensor } }
}

/** What the user can do on the alarm screen. */
internal sealed interface AlarmAction {
    /** Starts a new alarm in the editor. */
    data object Add : AlarmAction

    /** Opens the stored alarm [id] in the editor. */
    data class Open(val id: String) : AlarmAction

    /** Replaces the draft with [alarm] (every field change). */
    data class Edit(val alarm: SensorAlarm) : AlarmAction
    data object Save : AlarmAction

    /** Closes the editor without saving. */
    data object Cancel : AlarmAction
    data class Delete(val id: String) : AlarmAction
    data class SetEnabled(val id: String, val enabled: Boolean) : AlarmAction
}

/** One-shot results of the alarm screen. */
internal sealed interface AlarmEvent {
    /** The screen was opened on one alarm and that alarm is done: leave the screen. */
    data object Close : AlarmEvent
}

/**
 * App-side threshold alarms of the model ([SensorAlarm] in the extras): list and editor.
 *
 * @param openId alarm to open in the editor straight away (`AlarmEditor.alarmId`); when it is
 *   done the screen is left instead of returning to the list
 * @param activeAlarmIds ids of the alarms the runtime has raised
 * @param soundFiles names of the user sound files, loaded once
 * @param defaultVoiceText voice text of a new alarm (a template such as `Battery {RxBt} volts`)
 */
internal class AlarmViewModel(
    private val store: ExtrasStore,
    sensorList: StateFlow<List<SensorInfo>>,
    activeAlarmIds: Flow<Set<String>>,
    private val openId: String?,
    private val soundFiles: suspend () -> List<String> = { emptyList() },
    private val defaultVoiceText: String = "",
) : ViewModel() {

    private val draft = MutableStateFlow<SensorAlarm?>(null)
    private val isNew = MutableStateFlow(false)
    private val sounds = MutableStateFlow<List<String>>(emptyList())
    private val events = Channel<AlarmEvent>(Channel.BUFFERED)

    /** The state of the screen. */
    val state: StateFlow<AlarmUiState> = combine(
        store.extras, sensorList, activeAlarmIds, draft, isNew,
    ) { extras, sensors, active, d, new ->
        AlarmUiState(alarms = extras.alarms, sensors = sensors, activeIds = active, draft = d, isNewDraft = new)
    }.combine(sounds) { s, files -> s.copy(soundFiles = files) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AlarmUiState())

    /** One-shot results. */
    val results: Flow<AlarmEvent> = events.receiveAsFlow()

    init {
        viewModelScope.launch { sounds.value = runCatching { soundFiles() }.getOrDefault(emptyList()) }
    }

    /** Opens the alarm the screen was started on, if any; does nothing when the editor is already open. */
    fun start() {
        if (openId != null && draft.value == null) openStored(openId)
    }

    /** Closes the editor without a result; called when the screen is left. */
    fun reset() {
        draft.value = null
        isNew.value = false
    }

    /** Handles [action]. */
    fun onAction(action: AlarmAction) {
        when (action) {
            AlarmAction.Add -> {
                draft.value = newAlarm()
                isNew.value = true
            }
            is AlarmAction.Open -> openStored(action.id)
            is AlarmAction.Edit -> if (draft.value != null) draft.value = action.alarm
            AlarmAction.Save -> save()
            AlarmAction.Cancel -> close()
            is AlarmAction.Delete -> {
                viewModelScope.launch { store.update { it.copy(alarms = it.alarms.filterNot { alarm -> alarm.id == action.id }) } }
                if (draft.value?.id == action.id) close()
            }
            is AlarmAction.SetEnabled -> viewModelScope.launch {
                store.update { extras ->
                    extras.copy(alarms = extras.alarms.map { if (it.id == action.id) it.copy(enabled = action.enabled) else it })
                }
            }
        }
    }

    private fun openStored(id: String) {
        val alarm = store.extras.value.alarms.firstOrNull { it.id == id }
        if (alarm == null) {
            events.trySend(AlarmEvent.Close)
            return
        }
        draft.value = alarm
        isNew.value = false
    }

    private fun save() {
        val alarm = draft.value?.let { it.copy(name = it.name.trim()) } ?: return
        viewModelScope.launch {
            store.update { extras ->
                val exists = extras.alarms.any { it.id == alarm.id }
                extras.copy(alarms = if (exists) extras.alarms.map { if (it.id == alarm.id) alarm else it } else extras.alarms + alarm)
            }
            close()
        }
    }

    private fun close() {
        draft.value = null
        isNew.value = false
        if (openId != null) events.trySend(AlarmEvent.Close)
    }

    /** A new alarm with sensible defaults: receiver battery below 10.5 V, announced by voice. */
    private fun newAlarm(): SensorAlarm = SensorAlarm(
        id = newItemId(),
        sensor = Sensors.BATTERY_VOLTAGE,
        op = CompareOp.LESS,
        threshold = 10.5,
        hysteresis = 0.2,
        delaySec = 2.0,
        repeatSec = 10,
        outputs = AlarmOutputs(voiceText = defaultVoiceText, beep = BeepPattern.WARNING),
    )
}
