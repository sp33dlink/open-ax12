package org.openax12.feature.modelsetup.common

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.Flow
import org.openax12.core.designsystem.LocalAxToastState
import androidx.compose.runtime.State
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.openax12.core.designsystem.AxToastKind
import org.openax12.core.model.LiveSnapshot
import org.openax12.core.radio.Radio
import org.openax12.core.runtime.ConfigSession
import org.openax12.feature.modelsetup.R

/**
 * The live frame as Compose state. It must only be read inside draw / layout lambdas (the
 * `() -> Float` parameters of the design-system live components) or in a `derivedStateOf`, never
 * directly in a composable body: that would recompose the whole screen 25 times per second.
 */
internal typealias LiveFeed = State<LiveSnapshot>

/** Collects the radio's live flow as [LiveFeed]. */
@Composable
internal fun StateFlow<LiveSnapshot>.collectLive(): LiveFeed = collectAsStateWithLifecycle()

/** A short message a view model wants the screen to show as a toast. */
internal data class SetupMessage(@param:StringRes val textRes: Int, val kind: AxToastKind = AxToastKind.Error)

/**
 * Base of the setup tabs' view models: serialised edits through the [ConfigSession] and a message
 * channel for failures.
 *
 * @param scopeOverride scope for tests; production uses `viewModelScope`
 */
internal abstract class SetupViewModel(
    protected val session: ConfigSession,
    scopeOverride: CoroutineScope? = null,
) : ViewModel() {

    /** The scope this view model launches in. */
    protected val scope: CoroutineScope = scopeOverride ?: viewModelScope

    private val _messages = MutableSharedFlow<SetupMessage>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Toasts for the screen. Nothing is replayed: a message nobody is there to show is dropped. */
    val messages: SharedFlow<SetupMessage> = _messages.asSharedFlow()

    /** Emits a message. */
    protected fun message(@StringRes res: Int, kind: AxToastKind = AxToastKind.Error) {
        _messages.tryEmit(SetupMessage(res, kind))
    }

    /**
     * Runs [block] as a session edit. When it returns false (the radio rejected something, or is
     * not ready) the screen shows [failure]. The edit is serialised with every other one.
     */
    protected fun edit(
        @StringRes failure: Int = R.string.setup_write_failed,
        refresh: Boolean = true,
        block: suspend (Radio) -> Boolean,
    ): Job = scope.launch {
        val ok = session.edit(refresh = refresh) { block(it) }
        if (!ok) message(failure)
    }
}

/**
 * The activity-scoped view model [key] of a tab, created with [initializer] on first use. Keys keep
 * the tabs' view models apart; they survive switching tabs and opening the curve editor.
 */
@Composable
internal inline fun <reified VM : ViewModel> setupViewModel(key: String, crossinline initializer: () -> VM): VM =
    viewModel(
        key = key,
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T = initializer() as T
        },
    )

/** Shows the messages of a view model (or the chrome) as toasts while the screen is composed. */
@Composable
internal fun CollectMessages(messages: Flow<SetupMessage>) {
    val toast = LocalAxToastState.current
    val context = LocalContext.current
    LaunchedEffect(messages) {
        messages.collect { toast.show(context.getString(it.textRes), it.kind) }
    }
}
