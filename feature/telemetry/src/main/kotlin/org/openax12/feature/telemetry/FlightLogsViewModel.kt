package org.openax12.feature.telemetry

import android.net.Uri
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
import org.openax12.core.data.DataError
import org.openax12.core.data.DataResult
import org.openax12.core.data.FlightLogFile
import org.openax12.core.data.FlightLogState
import org.openax12.core.data.FlightLogger
import org.openax12.core.data.StorageInfo

/**
 * What the flight-log screen shows.
 *
 * @property logs the log files, newest first
 * @property loaded false until the first listing arrived
 */
internal data class FlightLogsState(
    val logs: List<FlightLogFile> = emptyList(),
    val loaded: Boolean = false,
    val recording: FlightLogState = FlightLogState.Idle,
    val storage: StorageInfo = StorageInfo(),
)

/** What the user can do in the flight-log screen. */
internal sealed interface FlightLogsAction {
    data object StartLogging : FlightLogsAction
    data object StopLogging : FlightLogsAction
    data object Refresh : FlightLogsAction
    data class Delete(val log: FlightLogFile) : FlightLogsAction
    data class Share(val log: FlightLogFile) : FlightLogsAction
}

/** One-shot results of the flight-log screen. */
internal sealed interface FlightLogsEvent {
    /** A shareable [uri] for [log] is ready: hand it to the share sheet. */
    data class ShareReady(val log: FlightLogFile, val uri: Uri) : FlightLogsEvent

    /** A message for a toast. */
    data class Message(val message: UiMessage) : FlightLogsEvent
}

/** Lists, records, shares and deletes the CSV flight logs. */
internal class FlightLogsViewModel(
    private val logger: FlightLogger,
    storage: StateFlow<StorageInfo>,
) : ViewModel() {

    private val logs = MutableStateFlow<List<FlightLogFile>?>(null)
    private val events = Channel<FlightLogsEvent>(Channel.BUFFERED)

    /** The state of the screen. */
    val state: StateFlow<FlightLogsState> = combine(logs, logger.state, storage) { files, recording, info ->
        FlightLogsState(logs = files.orEmpty(), loaded = files != null, recording = recording, storage = info)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, FlightLogsState())

    /** One-shot results. */
    val results: Flow<FlightLogsEvent> = events.receiveAsFlow()

    init {
        // A log appears or grows when recording starts or stops: list again after every change.
        viewModelScope.launch {
            logger.state.collect { refresh() }
        }
    }

    /** Handles [action]. */
    fun onAction(action: FlightLogsAction) {
        when (action) {
            FlightLogsAction.Refresh -> viewModelScope.launch { refresh() }
            FlightLogsAction.StartLogging -> viewModelScope.launch { report(logger.start()) }
            FlightLogsAction.StopLogging -> viewModelScope.launch { logger.stop() }
            is FlightLogsAction.Delete -> viewModelScope.launch {
                report(logger.delete(action.log))
                refresh()
            }
            is FlightLogsAction.Share -> viewModelScope.launch {
                when (val result = logger.share(action.log)) {
                    is DataResult.Success -> events.trySend(FlightLogsEvent.ShareReady(action.log, result.value))
                    is DataResult.Failure -> report(result)
                }
            }
        }
    }

    private suspend fun refresh() {
        logs.value = logger.logs()
    }

    private fun report(result: DataResult<*>) {
        if (result is DataResult.Failure) {
            events.trySend(FlightLogsEvent.Message(UiMessage(messageFor(result.error), isError = true)))
        }
    }

    internal companion object {
        /** The string resource that explains [error] to the user. */
        fun messageFor(error: DataError): Int = when (error) {
            DataError.STORAGE_PERMISSION -> R.string.telemetry_msg_log_permission
            DataError.NOT_FOUND -> R.string.telemetry_msg_log_not_found
            DataError.BUSY -> R.string.telemetry_msg_log_busy
            else -> R.string.telemetry_msg_log_failed
        }
    }
}
