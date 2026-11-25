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
import org.openax12.core.model.SensorId
import org.openax12.core.model.SensorInfo
import org.openax12.core.model.WidgetConfig
import org.openax12.core.model.WidgetType
import org.openax12.core.model.newItemId
import org.openax12.core.widgets.WidgetCatalog

/**
 * What the widget editor shows.
 *
 * @property draft the widget as edited so far; null until the editor has loaded
 * @property columns / [rows] grid of the page, the limits of the size
 */
internal data class WidgetEditorState(
    val isNew: Boolean = true,
    val draft: WidgetConfig? = null,
    val columns: Int = 1,
    val rows: Int = 1,
    val sensors: List<SensorInfo> = emptyList(),
    val dirty: Boolean = false,
) {
    /** True once the draft exists. */
    val ready: Boolean get() = draft != null

    /** Number of required sensor slots of the draft's type that are not bound. */
    val missingSensors: Int
        get() {
            val widget = draft ?: return 0
            val slots = WidgetCatalog.info(widget.type).sensorSlots
            return slots.withIndex().count { (index, slot) -> slot.required && widget.sensors.getOrNull(index) == null }
        }

    /** The draft can be saved. */
    val canSave: Boolean get() = ready && missingSensors == 0 && (isNew || dirty)
}

/** Everything the user can change in the widget editor. */
internal sealed interface WidgetEditorAction {
    data class SetType(val type: WidgetType) : WidgetEditorAction

    /** Binds sensor slot [slot] to [sensor]; null unbinds it and every slot after it. */
    data class SetSensor(val slot: Int, val sensor: SensorId?) : WidgetEditorAction
    data class SetTitle(val title: String) : WidgetEditorAction

    /** Sets option [key] to [value]; null removes it. */
    data class SetOption(val key: String, val value: String?) : WidgetEditorAction
    data class SetSize(val w: Int, val h: Int) : WidgetEditorAction
    data class SetPinned(val pinned: Boolean) : WidgetEditorAction
    data object Save : WidgetEditorAction
}

/** One-shot results of the widget editor. */
internal sealed interface WidgetEditorEvent {
    /** The widget was written; the screen closes. */
    data object Saved : WidgetEditorEvent

    /** The widget to edit does not exist (any more); the screen closes. */
    data object Missing : WidgetEditorEvent

    /** A message for a toast. */
    data class Message(val message: UiMessage) : WidgetEditorEvent
}

/**
 * Draft of one widget. The draft is thrown away when the screen is left ([discard]) and only
 * written to the model extras by [WidgetEditorAction.Save].
 *
 * @param widgetId the widget to edit; null adds a new widget to the page
 */
internal class WidgetEditorViewModel(
    private val store: ExtrasStore,
    sensorList: StateFlow<List<SensorInfo>>,
    private val pageId: String,
    private val widgetId: String?,
) : ViewModel() {

    private class Draft(val original: WidgetConfig?, val initial: WidgetConfig, val widget: WidgetConfig)

    private val draft = MutableStateFlow<Draft?>(null)
    private val events = Channel<WidgetEditorEvent>(Channel.BUFFERED)

    /** The state of the screen. */
    val state: StateFlow<WidgetEditorState> = combine(draft, sensorList, store.extras) { d, sensors, extras ->
        val page = extras.telemetry.page(pageId)
        WidgetEditorState(
            isNew = widgetId == null,
            draft = d?.widget,
            columns = page?.columns ?: 1,
            rows = page?.rows ?: 1,
            sensors = sensors,
            dirty = d != null && d.widget != d.initial,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, WidgetEditorState(isNew = widgetId == null))

    /** One-shot results. */
    val results: Flow<WidgetEditorEvent> = events.receiveAsFlow()

    /** Creates the draft (a copy of the widget, or a new one of the default type); does nothing if it exists. */
    fun start() {
        if (draft.value != null) return
        val layout = store.extras.value.telemetry
        val page = layout.page(pageId)
        if (page == null) {
            events.trySend(WidgetEditorEvent.Missing)
            return
        }
        if (widgetId == null) {
            val fresh = WidgetCatalog.create(WidgetType.VALUE, newItemId())
            // Show the spot and size the widget will really get.
            val placed = WidgetLayoutOps.add(page, fresh)?.widgets?.lastOrNull() ?: fresh
            draft.value = Draft(original = null, initial = placed, widget = placed)
        } else {
            val existing = layout.widget(widgetId)
            if (existing == null) {
                events.trySend(WidgetEditorEvent.Missing)
                return
            }
            draft.value = Draft(original = existing, initial = existing, widget = existing)
        }
    }

    /** Throws the draft away. */
    fun discard() {
        draft.value = null
    }

    /** Handles [action]. */
    fun onAction(action: WidgetEditorAction) {
        val current = draft.value ?: return
        val widget = current.widget
        val page = store.extras.value.telemetry.page(pageId)
        val updated: WidgetConfig = when (action) {
            is WidgetEditorAction.SetType -> {
                if (action.type == widget.type) return
                val fresh = WidgetCatalog.create(action.type, widget.id, widget.x, widget.y)
                fresh.copy(
                    title = widget.title,
                    pinned = widget.pinned,
                    pinOrder = widget.pinOrder,
                    // An existing widget keeps its size; a new one takes the default of its type.
                    w = if (widgetId == null) fresh.w else widget.w,
                    h = if (widgetId == null) fresh.h else widget.h,
                )
            }
            is WidgetEditorAction.SetSensor -> {
                val slots = WidgetCatalog.info(widget.type).sensorSlots
                if (action.slot !in slots.indices || action.slot > widget.sensors.size) return
                val head = widget.sensors.take(action.slot)
                widget.copy(
                    sensors = if (action.sensor == null) head else head + action.sensor + widget.sensors.drop(action.slot + 1),
                )
            }
            is WidgetEditorAction.SetTitle -> widget.copy(title = action.title)
            is WidgetEditorAction.SetOption -> widget.withOption(action.key, action.value)
            is WidgetEditorAction.SetSize -> widget.copy(
                w = action.w.coerceIn(1, (page?.columns ?: 1).coerceAtLeast(1)),
                h = action.h.coerceIn(1, (page?.rows ?: 1).coerceAtLeast(1)),
            )
            is WidgetEditorAction.SetPinned -> widget.copy(pinned = action.pinned)
            WidgetEditorAction.Save -> {
                save(current)
                return
            }
        }
        draft.value = Draft(current.original, current.initial, updated)
    }

    private fun save(current: Draft) {
        val widget = current.widget
        viewModelScope.launch {
            var failure: Int? = null
            store.update { extras ->
                val layout = extras.telemetry
                val page = layout.page(pageId)
                if (page == null) {
                    failure = R.string.telemetry_msg_page_gone
                    return@update extras
                }
                val pinOrder = when {
                    !widget.pinned -> widget.pinOrder
                    current.original?.pinned == true -> widget.pinOrder
                    else -> WidgetLayoutOps.nextPinOrder(layout)
                }
                val toWrite = widget.copy(pinOrder = pinOrder)
                val changed = if (current.original == null) {
                    WidgetLayoutOps.add(page, toWrite)
                } else {
                    WidgetLayoutOps.replace(page, toWrite)
                }
                if (changed == null) {
                    failure = if (current.original == null) R.string.telemetry_msg_page_full else R.string.telemetry_msg_no_room_resize
                    extras
                } else {
                    extras.copy(telemetry = WidgetLayoutOps.withPage(layout, changed))
                }
            }
            val reason = failure
            if (reason == null) {
                events.trySend(WidgetEditorEvent.Saved)
            } else {
                events.trySend(WidgetEditorEvent.Message(UiMessage(reason, isError = true)))
            }
        }
    }
}
