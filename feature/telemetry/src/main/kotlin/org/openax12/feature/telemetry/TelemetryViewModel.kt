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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.openax12.core.model.TelemetryPage
import org.openax12.core.model.WidgetConfig
import org.openax12.core.model.newItemId
import org.openax12.feature.telemetry.WidgetLayoutOps.MAX_COLUMNS
import org.openax12.feature.telemetry.WidgetLayoutOps.MAX_ROWS
import org.openax12.feature.telemetry.WidgetLayoutOps.MIN_COLUMNS
import org.openax12.feature.telemetry.WidgetLayoutOps.MIN_ROWS

/** What the telemetry screen shows. */
internal data class TelemetryUiState(
    val pages: List<TelemetryPage> = emptyList(),
    val selectedPageId: String? = null,
    val editMode: Boolean = false,
    val selectedWidgetId: String? = null,
    val pinnedCount: Int = 0,
) {
    /** The page being shown: the selected one, else the first. */
    val page: TelemetryPage? get() = pages.firstOrNull { it.id == selectedPageId } ?: pages.firstOrNull()

    /** The selected widget of [page], or null. */
    val selectedWidget: WidgetConfig? get() = page?.widgets?.firstOrNull { it.id == selectedWidgetId }
}

/** Everything the user can do on the telemetry screen that changes data or screen state. */
internal sealed interface TelemetryAction {
    data class SelectPage(val id: String) : TelemetryAction
    data object AddPage : TelemetryAction
    data class UpdatePage(val id: String, val name: String, val columns: Int, val rows: Int) : TelemetryAction
    data class DeletePage(val id: String) : TelemetryAction

    /** Moves a page [delta] positions in the tab row (-1 = left). */
    data class MovePage(val id: String, val delta: Int) : TelemetryAction
    data class SetEditMode(val enabled: Boolean) : TelemetryAction
    data class SelectWidget(val id: String?) : TelemetryAction
    data class MoveWidget(val id: String, val dx: Int, val dy: Int) : TelemetryAction
    data class ResizeWidget(val id: String, val w: Int, val h: Int) : TelemetryAction
    data class TogglePin(val id: String) : TelemetryAction
    data class DeleteWidget(val id: String) : TelemetryAction
}

/**
 * State and edits of the telemetry pages. The layout lives in the model extras; this class only
 * adds what is purely about the screen (selected page / widget, edit mode).
 */
internal class TelemetryViewModel(private val store: ExtrasStore) : ViewModel() {

    private val selectedPageId = MutableStateFlow<String?>(null)
    private val editMode = MutableStateFlow(false)
    private val selectedWidgetId = MutableStateFlow<String?>(null)
    private val messages = Channel<UiMessage>(Channel.BUFFERED)

    /** The state of the screen. */
    val state: StateFlow<TelemetryUiState> = combine(
        store.extras, selectedPageId, editMode, selectedWidgetId,
    ) { extras, pageId, edit, widgetId ->
        val layout = extras.telemetry
        val shown = layout.pages.firstOrNull { it.id == pageId } ?: layout.pages.firstOrNull()
        TelemetryUiState(
            pages = layout.pages,
            selectedPageId = shown?.id,
            editMode = edit,
            selectedWidgetId = widgetId?.takeIf { id -> shown?.widgets?.any { it.id == id } == true },
            pinnedCount = layout.pinnedWidgets.size,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, TelemetryUiState())

    /** One-shot messages for toasts. */
    val events: Flow<UiMessage> = messages.receiveAsFlow()

    /** Handles [action]. */
    fun onAction(action: TelemetryAction) {
        when (action) {
            is TelemetryAction.SelectPage -> {
                selectedPageId.value = action.id
                selectedWidgetId.value = null
            }
            TelemetryAction.AddPage -> addPage()
            is TelemetryAction.UpdatePage -> updatePage(action)
            is TelemetryAction.DeletePage -> deletePage(action.id)
            is TelemetryAction.MovePage -> movePage(action.id, action.delta)
            is TelemetryAction.SetEditMode -> {
                editMode.value = action.enabled
                if (!action.enabled) selectedWidgetId.value = null
            }
            is TelemetryAction.SelectWidget -> selectedWidgetId.value = action.id
            is TelemetryAction.MoveWidget -> changeCurrentPage(R.string.telemetry_msg_no_room_move) { page ->
                WidgetLayoutOps.moveBy(page, action.id, action.dx, action.dy)
            }
            is TelemetryAction.ResizeWidget -> changeCurrentPage(R.string.telemetry_msg_no_room_resize) { page ->
                WidgetLayoutOps.resizeTo(page, action.id, action.w, action.h)
            }
            is TelemetryAction.TogglePin -> togglePin(action.id)
            is TelemetryAction.DeleteWidget -> {
                changeCurrentPage(null) { page -> WidgetLayoutOps.remove(page, action.id) }
                if (selectedWidgetId.value == action.id) selectedWidgetId.value = null
            }
        }
    }

    private fun currentPage(): TelemetryPage? {
        val layout = store.extras.value.telemetry
        return layout.pages.firstOrNull { it.id == selectedPageId.value } ?: layout.pages.firstOrNull()
    }

    /** Applies [change] to the shown page; shows [failure] if the change is not possible. */
    private fun changeCurrentPage(failure: Int?, change: (TelemetryPage) -> TelemetryPage?) {
        val pageId = currentPage()?.id ?: return
        viewModelScope.launch {
            var failed = false
            store.update { extras ->
                val page = extras.telemetry.page(pageId) ?: return@update extras
                val changed = change(page)
                if (changed == null) {
                    failed = true
                    extras
                } else {
                    extras.copy(telemetry = WidgetLayoutOps.withPage(extras.telemetry, changed))
                }
            }
            if (failed && failure != null) messages.trySend(UiMessage(failure, isError = true))
        }
    }

    private fun addPage() {
        val id = newItemId()
        viewModelScope.launch {
            store.update { extras ->
                extras.copy(telemetry = extras.telemetry.copy(pages = extras.telemetry.pages + TelemetryPage(id = id)))
            }
            selectedPageId.value = id
            selectedWidgetId.value = null
            editMode.value = true
        }
    }

    private fun updatePage(action: TelemetryAction.UpdatePage) {
        viewModelScope.launch {
            var failed = false
            store.update { extras ->
                val page = extras.telemetry.page(action.id) ?: return@update extras
                val resized = WidgetLayoutOps.resizeGrid(
                    page,
                    action.columns.coerceIn(MIN_COLUMNS, MAX_COLUMNS),
                    action.rows.coerceIn(MIN_ROWS, MAX_ROWS),
                )
                if (resized == null) {
                    failed = true
                    extras
                } else {
                    extras.copy(telemetry = WidgetLayoutOps.withPage(extras.telemetry, resized.copy(name = action.name.trim())))
                }
            }
            if (failed) messages.trySend(UiMessage(R.string.telemetry_msg_grid_too_small, isError = true))
        }
    }

    private fun deletePage(id: String) {
        viewModelScope.launch {
            store.update { extras ->
                extras.copy(telemetry = extras.telemetry.copy(pages = extras.telemetry.pages.filterNot { it.id == id }))
            }
            if (selectedPageId.value == id) selectedPageId.value = null
            selectedWidgetId.value = null
        }
    }

    private fun movePage(id: String, delta: Int) {
        viewModelScope.launch {
            store.update { extras ->
                val pages = extras.telemetry.pages.toMutableList()
                val from = pages.indexOfFirst { it.id == id }
                val to = from + delta
                if (from < 0 || to !in pages.indices) return@update extras
                pages.add(to, pages.removeAt(from))
                extras.copy(telemetry = extras.telemetry.copy(pages = pages))
            }
        }
    }

    private fun togglePin(id: String) {
        viewModelScope.launch {
            var nowPinned = false
            var pinnedTotal = 0
            store.update { extras ->
                val layout = extras.telemetry
                val widget = layout.widget(id) ?: return@update extras
                nowPinned = !widget.pinned
                val updated = if (nowPinned) {
                    widget.copy(pinned = true, pinOrder = WidgetLayoutOps.nextPinOrder(layout))
                } else {
                    widget.copy(pinned = false)
                }
                val next = layout.copy(
                    pages = layout.pages.map { page ->
                        if (page.widgets.any { it.id == id }) page.copy(widgets = page.widgets.map { if (it.id == id) updated else it }) else page
                    },
                )
                pinnedTotal = next.pinnedWidgets.size
                extras.copy(telemetry = next)
            }
            if (nowPinned && pinnedTotal > MAX_HOME_PINNED) {
                messages.trySend(UiMessage(R.string.telemetry_msg_pin_limit, listOf(MAX_HOME_PINNED)))
            }
        }
    }

    internal companion object {
        /** How many pinned widgets the home centre card shows (`PinnedWidgetGrid` limit). */
        const val MAX_HOME_PINNED = 6
    }
}

