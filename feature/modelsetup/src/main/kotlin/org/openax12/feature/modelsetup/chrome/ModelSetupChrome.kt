package org.openax12.feature.modelsetup.chrome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.openax12.core.designsystem.AxIcons
import org.openax12.core.designsystem.AxIcon
import org.openax12.core.designsystem.AxTheme
import org.openax12.core.designsystem.AxToastKind
import org.openax12.core.designsystem.ConfirmDialog
import org.openax12.core.designsystem.LocalAxToastState
import org.openax12.core.designsystem.SetupScaffold
import org.openax12.core.designsystem.SetupTabItem
import org.openax12.core.navigation.LocalNavigator
import org.openax12.core.navigation.ModelSetup
import org.openax12.core.navigation.SetupTab
import org.openax12.core.radio.Radio
import org.openax12.core.runtime.ConfigSession
import org.openax12.core.runtime.LocalAppGraph
import org.openax12.core.runtime.SaveState
import org.openax12.core.runtime.SessionError
import org.openax12.feature.modelsetup.R
import org.openax12.feature.modelsetup.common.SetupMessage
import org.openax12.feature.modelsetup.common.setupViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope

/**
 * What the chrome shows of the editing session.
 *
 * @property dirty the Save button carries its dot: unsaved edits, or the model is loaded but not
 *   confirmed on the radio (`!deviceInSync`)
 * @property deviceInSync false while the loaded model has not been written to the radio
 * @property saving a device write is running
 * @property confirmSave the "link is up, write now?" question is open
 */
internal data class ChromeState(
    val dirty: Boolean = false,
    val deviceInSync: Boolean = true,
    val saving: Boolean = false,
    val confirmSave: Boolean = false,
)

/**
 * View model of the model-setup chrome (and of the curve editor, which has the same Save button):
 * the Save flow with the "link is up" confirmation (SPEC section 9) and the failure messages of
 * the editing session.
 *
 * Failures and the success message come from `ConfigSession.saveState`, so a failed save of any
 * origin is reported while the chrome is visible.
 */
internal class SetupChromeViewModel(
    private val session: ConfigSession,
    private val radio: Radio,
    scopeOverride: CoroutineScope? = null,
) : ViewModel() {

    private val scope: CoroutineScope = scopeOverride ?: viewModelScope
    private val confirm = MutableStateFlow(false)

    /** State for the chrome. */
    val state: StateFlow<ChromeState> = combine(
        session.dirty,
        session.deviceInSync,
        session.saveState,
        confirm,
    ) { dirty, inSync, save, confirming ->
        ChromeState(
            dirty = dirty || !inSync,
            deviceInSync = inSync,
            saving = save.isSaving,
            confirmSave = confirming,
        )
    }.stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ChromeState())

    /** Toasts about the outcome of device writes. Cold: only collected while the chrome is on screen. */
    val messages: Flow<SetupMessage> = session.saveState.drop(1).mapNotNull { saveState ->
        when (saveState) {
            is SaveState.Saved -> SetupMessage(R.string.save_done, AxToastKind.Success)
            is SaveState.Failed -> SetupMessage(failureText(saveState.error), AxToastKind.Error)
            else -> null
        }
    }

    /** The Save button: asks first while the link is up. */
    fun onSaveClicked() {
        if (state.value.saving) return
        if (radio.live.value.status.isLinkUp) confirm.value = true else save()
    }

    /** The user confirmed writing while linked. */
    fun confirmSave() {
        confirm.value = false
        save()
    }

    /** The user declined. */
    fun dismissConfirm() {
        confirm.value = false
    }

    private fun save() {
        scope.launch { session.save() }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L

        fun failureText(error: SessionError): Int = when (error) {
            SessionError.RADIO_NOT_READY -> R.string.save_err_radio
            SessionError.DEVICE_TIMEOUT -> R.string.save_err_timeout
            SessionError.BUSY -> R.string.save_err_busy
            SessionError.DEVICE_WRITE_PENDING -> R.string.save_err_pending
            SessionError.DATA -> R.string.save_err_data
            else -> R.string.save_err_generic
        }
    }
}

/** The tab that was selected last in this process; the default of the next visit. */
internal object LastSetupTab {
    @Volatile
    var id: String? = null
}

/** The model-setup entry: chrome plus the selected tab's content. */
@Composable
internal fun ModelSetupRoute(key: ModelSetup, tabs: List<SetupTab>) {
    val graph = LocalAppGraph.current
    val navigator = LocalNavigator.current
    val chrome = setupViewModel("modelsetup.chrome") { SetupChromeViewModel(graph.configSession, graph.radio) }
    var selectedId by rememberSaveable {
        mutableStateOf(listOfNotNull(key.initialTabId, LastSetupTab.id).firstOrNull { id -> tabs.any { it.id == id } } ?: tabs.firstOrNull()?.id)
    }
    LaunchedEffect(navigator.setupTabRequest, tabs) {
        navigator.consumeSetupTabRequest()?.let { requested ->
            if (tabs.any { it.id == requested }) selectedId = requested
        }
    }
    LaunchedEffect(selectedId) { LastSetupTab.id = selectedId }
    val items = tabs.map { SetupTabItem(it.id, stringResource(it.titleRes)) }
    val selected = tabs.firstOrNull { it.id == selectedId } ?: tabs.firstOrNull()
    ChromeHost(chrome = chrome, tabs = items, selectedTabId = selected?.id, onTabSelected = { selectedId = it }, onBack = { navigator.back() }) {
        if (selected != null) androidx.compose.runtime.key(selected.id) { selected.content() }
    }
}

/** Collects the chrome view model's messages into toasts and draws [ModelSetupScreen]. */
@Composable
internal fun ChromeHost(
    chrome: SetupChromeViewModel,
    tabs: List<SetupTabItem>,
    selectedTabId: String?,
    onTabSelected: (String) -> Unit,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    val state by chrome.state.collectAsStateWithLifecycle()
    val toast = LocalAxToastState.current
    val context = LocalContext.current
    LaunchedEffect(chrome) {
        chrome.messages.collect { toast.show(context.getString(it.textRes), it.kind) }
    }
    ModelSetupScreen(
        state = state,
        tabs = tabs,
        selectedTabId = selectedTabId,
        onTabSelected = onTabSelected,
        onBack = onBack,
        onSave = chrome::onSaveClicked,
        onConfirmSave = chrome::confirmSave,
        onDismissConfirm = chrome::dismissConfirm,
        content = content,
    )
}

/**
 * The stateless model-setup chrome (navy bar with tabs and Save, "not written" banner, the
 * "link is up" question) around [content].
 */
@Composable
internal fun ModelSetupScreen(
    state: ChromeState,
    tabs: List<SetupTabItem>,
    selectedTabId: String?,
    onTabSelected: (String) -> Unit,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onConfirmSave: () -> Unit,
    onDismissConfirm: () -> Unit,
    content: @Composable () -> Unit,
) {
    SetupScaffold(
        tabs = tabs,
        selectedTabId = selectedTabId,
        onTabSelected = onTabSelected,
        onBack = onBack,
        onSave = onSave,
        dirty = state.dirty,
        saving = state.saving,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (!state.deviceInSync) NotWrittenBanner()
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) { content() }
        }
    }
    if (state.confirmSave) {
        ConfirmDialog(
            title = stringResource(R.string.save_linked_title),
            message = stringResource(R.string.save_linked_message),
            confirmLabel = stringResource(org.openax12.core.designsystem.R.string.ax_save),
            onConfirm = onConfirmSave,
            onDismiss = onDismissConfirm,
        )
    }
}

@Composable
private fun NotWrittenBanner() {
    val colors = AxTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(26.dp)
            .background(colors.orange.copy(alpha = 0.9f)),
        contentAlignment = Alignment.CenterStart,
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AxIcon(icon = AxIcons.Warning, contentDescription = null, tint = colors.text, size = 16.dp)
            Text(
                text = stringResource(R.string.not_written_banner),
                style = AxTheme.typography.label,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Start,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}
