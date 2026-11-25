package org.openax12.feature.models

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.openax12.core.data.ModelRepository
import org.openax12.core.data.PictureCropRect
import org.openax12.core.designsystem.AxToastKind

/**
 * State of the crop screen.
 *
 * @property loading the picked image is being decoded
 * @property image the decoded, upright preview; null while loading or if it cannot be read
 * @property saving the cropped picture is being stored
 */
internal data class CropUiState(
    val loading: Boolean = true,
    val image: Bitmap? = null,
    val saving: Boolean = false,
)

/** One-shot results of the crop view model. */
internal sealed interface CropEvent {
    /** Show a toast. */
    data class Message(val message: UiMessage) : CropEvent

    /** The picture was stored: leave the screen. */
    data object Saved : CropEvent
}

/**
 * View model of the crop screen: decodes the picked image and stores the chosen crop with
 * `ModelRepository.setPicture`.
 *
 * @param decode decodes the picked image (see [PictureDecoder]); null = unreadable
 */
internal class PictureCropViewModel(
    private val modelId: String,
    private val sourceUri: String,
    private val models: ModelRepository,
    decode: suspend () -> Bitmap?,
) : ViewModel() {

    private val _state = MutableStateFlow(CropUiState())
    private val eventChannel = Channel<CropEvent>(Channel.BUFFERED)

    /** The screen state. */
    val state: StateFlow<CropUiState> = _state.asStateFlow()

    /** One-shot events. */
    val events: Flow<CropEvent> = eventChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            val bitmap = decode()
            _state.update { it.copy(loading = false, image = bitmap) }
        }
    }

    /** Stores the picture cropped to [rect] (fractions of the upright image). */
    fun save(rect: PictureCropRect) {
        if (_state.value.saving || _state.value.image == null) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val failure = models.setPicture(modelId, Uri.parse(sourceUri), rect).failureMessage()
            _state.update { it.copy(saving = false) }
            eventChannel.trySend(
                if (failure == null) CropEvent.Saved else CropEvent.Message(failure.copy(kind = AxToastKind.Error)),
            )
        }
    }

    override fun onCleared() {
        _state.value.image?.let { if (!it.isRecycled) it.recycle() }
        super.onCleared()
    }
}
