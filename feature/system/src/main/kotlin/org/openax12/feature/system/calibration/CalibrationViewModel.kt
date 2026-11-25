package org.openax12.feature.system.calibration

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.openax12.core.model.StickMode
import org.openax12.core.radio.Radio
import org.openax12.core.runtime.ConfigSession
import org.openax12.feature.system.common.Feedback
import org.openax12.feature.system.common.edit

/**
 * Starts and finishes the stick calibration (`Radio.setStickCalibration`) and tracks how far each
 * axis was moved while it runs. The progress itself is `RadioStatus.calibrationState`.
 *
 * Leaving the screen while the calibration runs finishes it (see assumption 34): a calibration
 * that nobody watches would otherwise stay open.
 *
 * @param appScope scope that outlives the ViewModel, used to finish a running calibration on exit
 */
internal class CalibrationViewModel(
    private val radio: Radio,
    session: ConfigSession,
    private val appScope: CoroutineScope,
    scopeOverride: CoroutineScope? = null,
) : ViewModel() {

    private val scope: CoroutineScope = scopeOverride ?: viewModelScope
    private val local = MutableStateFlow(CalibrationUiState())
    private val feedbackChannel = Channel<Feedback>(Channel.BUFFERED)

    /** One-shot outcome messages for toasts. */
    val feedback: Flow<Feedback> = feedbackChannel.receiveAsFlow()

    /** The state of the screen. */
    val state: StateFlow<CalibrationUiState> = combine(local, radio.connection, session.workingConfig) { s, connection, working ->
        s.copy(
            connection = connection,
            stickMode = working.takeUnless { it.isEmpty }?.system?.stickMode ?: StickMode.MODE_2,
        )
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), CalibrationUiState())

    init {
        // Collects the live frames for as long as the ViewModel lives; state changes only on a real change.
        scope.launch {
            radio.live.collect { frame ->
                val status = frame.status
                local.edit { s ->
                    val extents = if (status.calibrationState != 0) s.extents.observe(frame) else s.extents
                    if (extents === s.extents && s.calibrationState == status.calibrationState && s.linkUp == status.isLinkUp) {
                        s
                    } else {
                        s.copy(extents = extents, calibrationState = status.calibrationState, linkUp = status.isLinkUp)
                    }
                }
            }
        }
    }

    /** Start pressed: asks first while the receiver link is up. */
    fun requestStart() {
        if (local.value.linkUp) local.edit { it.copy(confirmStart = true) } else start()
    }

    /** The user dismissed the "link is up" question. */
    fun dismissConfirm() = local.edit { it.copy(confirmStart = false) }

    /** Starts the calibration (after the optional confirmation). */
    fun start() {
        local.edit { it.copy(confirmStart = false, extents = StickExtents(), busy = true) }
        scope.launch {
            val ok = radio.setStickCalibration(true)
            local.edit { it.copy(busy = false) }
            if (!ok) feedbackChannel.trySend(Feedback.RADIO_NOT_READY)
        }
    }

    /** Finishes the calibration. */
    fun finish() {
        local.edit { it.copy(busy = true) }
        scope.launch {
            val ok = radio.setStickCalibration(false)
            local.edit { it.copy(busy = false) }
            feedbackChannel.trySend(if (ok) Feedback.DONE else Feedback.FAILED)
        }
    }

    override fun onCleared() {
        if (local.value.running) appScope.launch { radio.setStickCalibration(false) }
    }
}
