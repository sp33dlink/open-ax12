package org.openax12.feature.system.settings

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.openax12.core.designsystem.AxIcon
import org.openax12.core.designsystem.AxTheme
import org.openax12.core.designsystem.ConfirmDialog
import org.openax12.core.designsystem.DoubleConfirmDialog
import org.openax12.core.designsystem.axSunken
import org.openax12.core.navigation.LocalNavigator
import org.openax12.core.navigation.LocalShellActions
import org.openax12.core.runtime.LocalAppGraph
import org.openax12.feature.system.R
import org.openax12.feature.system.common.FeedbackEffect
import org.openax12.feature.system.common.graphViewModel

/** Stateful entry content of `SystemSettings`: wires the ViewModel, navigation and shell actions. */
@Composable
internal fun SystemSettingsRoute() {
    val graph = LocalAppGraph.current
    val viewModel = graphViewModel(graph) {
        SettingsViewModel(
            radio = it.radio,
            session = it.configSession,
            settings = it.settings,
            audio = it.audio,
            runtime = it.runtime,
            models = it.models,
            diagnostics = it.diagnostics,
            activeBackend = it.activeBackend,
        )
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val shell = LocalShellActions.current
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val context = LocalContext.current
    FeedbackEffect(viewModel.feedback)
    SystemSettingsScreen(
        state = state,
        appVersion = remember(context) { appVersionName(context) },
        selectedTab = SettingsTab.entries[selected.coerceIn(0, SettingsTab.entries.lastIndex)],
        onTabSelected = { selected = it.ordinal },
        onEvent = { event ->
            when (event) {
                is SettingsEvent.Open -> navigator.navigate(event.key)
                SettingsEvent.RequestStoragePermission -> shell.requestStoragePermission()
                else -> viewModel.onEvent(event)
            }
        },
    )
}

/** Version name and code of the installed app, for the About panel. */
private fun appVersionName(context: Context): String = try {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    info.versionName ?: "?"
} catch (e: Exception) {
    "?"
}

/**
 * The system settings hub: a rail of categories on the left, the selected category's panel on
 * the right. Stateless: everything comes from [state].
 */
@Composable
internal fun SystemSettingsScreen(
    state: SettingsUiState,
    appVersion: String,
    selectedTab: SettingsTab,
    onTabSelected: (SettingsTab) -> Unit,
    onEvent: (SettingsEvent) -> Unit,
) {
    Row(modifier = Modifier.fillMaxSize()) {
        Rail(selectedTab = selectedTab, onTabSelected = onTabSelected)
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            when (selectedTab) {
                SettingsTab.RADIO -> RadioPanel(state, onEvent)
                SettingsTab.AUDIO -> AudioPanel(state, onEvent)
                SettingsTab.APP -> AppPanel(state, onEvent)
                SettingsTab.STORAGE -> StoragePanel(state, onEvent)
                SettingsTab.ABOUT -> AboutPanel(state, appVersion, onEvent)
            }
        }
    }
    PendingActionDialog(state.pending, onEvent)
}

@Composable
private fun Rail(selectedTab: SettingsTab, onTabSelected: (SettingsTab) -> Unit) {
    Column(
        modifier = Modifier
            .width(150.dp)
            .fillMaxHeight()
            .padding(start = 10.dp, top = 12.dp, end = 4.dp),
    ) {
        SettingsTab.entries.forEach { tab ->
            val selected = tab == selectedTab
            val colors = AxTheme.colors
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .padding(vertical = 2.dp)
                    .then(if (selected) Modifier.axSunken(cornerRadius = 14.dp, depth = 2.dp) else Modifier)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Tab,
                        onClick = { onTabSelected(tab) },
                    )
                    .semantics { this.selected = selected }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AxIcon(
                    icon = tab.icon,
                    contentDescription = null,
                    tint = if (selected) colors.accent else colors.textMuted,
                    size = 22.dp,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(tab.title),
                    style = if (selected) AxTheme.typography.bodyStrong else AxTheme.typography.body,
                    color = if (selected) colors.text else colors.textMuted,
                    maxLines = 1,
                )
            }
        }
    }
}

/** The confirmation of a pending radio action: a double confirmation for restore defaults, a plain one while linked. */
@Composable
private fun PendingActionDialog(pending: RadioAction?, onEvent: (SettingsEvent) -> Unit) {
    when (pending) {
        null -> Unit
        RadioAction.RestoreDefaults -> DoubleConfirmDialog(
            title = stringResource(R.string.restore_title),
            message = stringResource(R.string.restore_message),
            secondMessage = stringResource(R.string.restore_second),
            confirmLabel = stringResource(R.string.restore_hold),
            onConfirm = { onEvent(SettingsEvent.ConfirmPending) },
            onDismiss = { onEvent(SettingsEvent.DismissPending) },
        )
        else -> ConfirmDialog(
            title = stringResource(R.string.linked_title),
            message = stringResource(R.string.linked_message, stringResource(actionLabel(pending))),
            onConfirm = { onEvent(SettingsEvent.ConfirmPending) },
            onDismiss = { onEvent(SettingsEvent.DismissPending) },
            confirmLabel = stringResource(R.string.linked_confirm),
            destructive = true,
        )
    }
}

private fun actionLabel(action: RadioAction): Int = when (action) {
    is RadioAction.SetStickMode -> R.string.action_stick_mode
    is RadioAction.SetAlarmVoltage -> R.string.action_alarm_voltage
    is RadioAction.SetRfMode -> R.string.action_rf_mode
    RadioAction.RebootRf -> R.string.action_reboot_rf
    is RadioAction.UsbPassthrough -> R.string.action_usb
    RadioAction.RestoreDefaults -> R.string.action_restore
}
