package org.openax12.feature.system.elrs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.openax12.core.model.ElrsMenuItem
import org.openax12.core.radio.Radio
import org.openax12.core.radio.RadioEvent
import org.openax12.feature.system.common.Feedback
import org.openax12.feature.system.common.edit
import org.openax12.feature.system.common.linkUpFlow

/**
 * Browses the ELRS parameter menu of the TX module or the receiver and changes its items.
 *
 * Protocol (SDK manual 19.2): one `requestElrsMenu`, then one `ElrsMenuItemReceived` per item;
 * the request is *not* repeated — "frequent repeated fetching causes the page to stutter" — and
 * nothing is polled after the screen is left. The menu is complete when no item arrived for
 * [QUIET_MS].
 */
internal class ElrsViewModel(
    private val radio: Radio,
    scopeOverride: CoroutineScope? = null,
) : ViewModel() {

    private val scope: CoroutineScope = scopeOverride ?: viewModelScope
    private val local = MutableStateFlow(ElrsUiState())
    private val feedbackChannel = Channel<Feedback>(Channel.BUFFERED)
    private var settleJob: Job? = null

    /** One-shot outcome messages for toasts. */
    val feedback: Flow<Feedback> = feedbackChannel.receiveAsFlow()

    /** The state of the screen. */
    val state: StateFlow<ElrsUiState> = combine(local, radio.connection, radio.linkUpFlow()) { s, connection, linkUp ->
        s.copy(connection = connection, linkUp = linkUp)
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), ElrsUiState())

    init {
        scope.launch { radio.events.collect(::onEvent) }
    }

    /** Requests the menu of [device] (the current device when null). Ignored by the SDK while a request runs. */
    fun load(device: ElrsDevice? = null) {
        val target = device ?: local.value.device
        scope.launch {
            local.edit { it.copy(device = target, items = emptyList(), folder = ElrsTree.ROOT, loading = true, noAnswer = false, commands = emptyMap(), deviceName = "") }
            if (!radio.connection.value.isReady || !radio.requestElrsMenu(target.address)) {
                local.edit { it.copy(loading = false, noAnswer = true) }
                return@launch
            }
            // Items may already have arrived while the request call returned: keep the shorter quiet timer then.
            arm(if (local.value.items.isEmpty()) NO_ANSWER_MS else QUIET_MS)
        }
    }

    /** Switches between the TX module and the receiver menu. */
    fun selectDevice(device: ElrsDevice) {
        if (device == local.value.device) return
        if (device == ElrsDevice.RECEIVER && !state.value.linkUp) {
            feedbackChannel.trySend(Feedback.LINK_DOWN)
            return
        }
        load(device)
    }

    /** Opens the folder [index]. */
    fun openFolder(index: Int) = local.edit { it.copy(folder = index) }

    /** Goes one folder up. */
    fun up() = local.edit { it.copy(folder = ElrsTree.parentOf(it.items, it.folder)) }

    /** Sets a selection or number item. The shown value changes at once; the module's answer follows. */
    fun setValue(item: ElrsMenuItem, value: Int) {
        scope.launch {
            val before = local.value.items
            local.edit { it.copy(items = ElrsTree.upsert(it.items, item.copy(value = value))) }
            if (!radio.sendElrsMenuCommand(item.index, value)) {
                local.edit { it.copy(items = before) }
                feedbackChannel.trySend(Feedback.REJECTED)
            }
        }
    }

    /** Sends a command step ([ElrsCommandStep]) to a command item. */
    fun command(item: ElrsMenuItem, step: Int) {
        scope.launch {
            if (!radio.sendElrsMenuCommand(item.index, step)) {
                feedbackChannel.trySend(Feedback.REJECTED)
                return@launch
            }
            if (step == ElrsCommandStep.START) {
                local.edit { it.copy(commands = it.commands + (item.index to CommandProgress.Running(""))) }
            } else if (step == ElrsCommandStep.CANCEL) {
                local.edit { it.copy(commands = it.commands + (item.index to CommandProgress.Idle)) }
            }
        }
    }

    /** Asks for the link status (good / bad packets, model match). */
    fun requestStatus() {
        scope.launch {
            if (radio.requestElrsStatus()) {
                local.edit { it.copy(statusPending = true) }
            } else {
                feedbackChannel.trySend(if (radio.connection.value.isReady) Feedback.BUSY else Feedback.RADIO_NOT_READY)
            }
        }
    }

    private fun onEvent(event: RadioEvent) {
        when (event) {
            is RadioEvent.ElrsDeviceInfo -> local.edit { it.copy(deviceName = event.deviceName) }
            is RadioEvent.ElrsMenuItemReceived -> {
                local.edit { it.copy(items = ElrsTree.upsert(it.items, event.item), noAnswer = false) }
                if (local.value.loading) arm(QUIET_MS)
            }
            is RadioEvent.ElrsAcknowledged -> local.edit {
                it.copy(commands = it.commands + (event.ack.lastSetIndex to commandProgress(event.ack)))
            }
            is RadioEvent.ElrsStatusReceived -> local.edit { it.copy(status = event.status, statusPending = false) }
            else -> Unit
        }
    }

    private fun arm(delayMs: Long) {
        settleJob?.cancel()
        settleJob = scope.launch {
            delay(delayMs)
            val empty = local.value.items.isEmpty()
            local.edit { it.copy(loading = false, noAnswer = empty) }
            if (!empty && local.value.status == null) requestStatus()
        }
    }

    override fun onCleared() {
        settleJob?.cancel()
    }

    internal companion object {
        /** The menu is complete when no item arrived for this long. */
        const val QUIET_MS = 1_200L

        /** Waiting for the first item before giving up. */
        const val NO_ANSWER_MS = 5_000L
    }
}
