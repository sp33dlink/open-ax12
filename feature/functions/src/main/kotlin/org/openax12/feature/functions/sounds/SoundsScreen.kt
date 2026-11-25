package org.openax12.feature.functions.sounds

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.openax12.core.audio.TtsStatus
import org.openax12.core.designsystem.AxButton
import org.openax12.core.designsystem.AxCard
import org.openax12.core.designsystem.AxEmptyState
import org.openax12.core.designsystem.AxIconButton
import org.openax12.core.designsystem.AxIcons
import org.openax12.core.designsystem.AxSegmented
import org.openax12.core.designsystem.AxSwitch
import org.openax12.core.designsystem.AxTheme
import org.openax12.core.model.Announcements
import org.openax12.core.model.VarioConfig
import org.openax12.core.model.VoiceAlert
import org.openax12.feature.functions.R
import org.openax12.feature.functions.common.BannerTone
import org.openax12.feature.functions.common.Caption
import org.openax12.feature.functions.common.FormCard
import org.openax12.feature.functions.common.FormRow
import org.openax12.feature.functions.common.InfoBanner
import org.openax12.feature.functions.common.PickerData
import org.openax12.feature.functions.common.ScreenPadding
import org.openax12.feature.functions.common.describeTrigger
import org.openax12.feature.functions.common.priorityLabel

private enum class SoundsPage { ALERTS, VARIO, ANNOUNCEMENTS }

/** Everything the Sounds tab can do besides navigation, as callbacks. */
internal class SoundsCallbacks(
    val onOpenAlert: (String?) -> Unit,
    val onSetAlertEnabled: (String, Boolean) -> Unit,
    val onTestAlert: (String) -> Unit,
    val onEditVario: ((VarioConfig) -> VarioConfig) -> Unit,
    val onResetVario: () -> Unit,
    val onTestVario: (Double) -> Unit,
    val onEditAnnouncements: ((Announcements) -> Announcements) -> Unit,
)

/**
 * The "Sounds" tab: voice alerts (text templates with triggers), the vario settings and the
 * built-in announcements. A banner warns when text-to-speech is not available.
 */
@Composable
internal fun SoundsScreen(
    state: SoundsState,
    callbacks: SoundsCallbacks,
    modifier: Modifier = Modifier,
    initialPage: Int = 0,
) {
    var pageIndex by rememberSaveable { mutableStateOf(initialPage) }
    val page = SoundsPage.entries[pageIndex.coerceIn(0, SoundsPage.entries.lastIndex)]
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = ScreenPadding, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AxSegmented(
                options = listOf(
                    stringResource(R.string.fn_sounds_page_alerts),
                    stringResource(R.string.fn_sounds_page_vario),
                    stringResource(R.string.fn_sounds_page_announcements),
                ),
                selectedIndex = page.ordinal,
                onSelected = { pageIndex = it },
                modifier = Modifier.width(450.dp),
            )
            Box(modifier = Modifier.weight(1f))
            if (page == SoundsPage.ALERTS) {
                AxButton(
                    text = stringResource(R.string.fn_alert_add),
                    onClick = { callbacks.onOpenAlert(null) },
                    icon = AxIcons.Add,
                )
            }
        }
        TtsBanner(state.tts)
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (page) {
                SoundsPage.ALERTS -> AlertsPage(state, callbacks)
                SoundsPage.VARIO -> VarioPanel(state.vario, state.pickers, callbacks)
                SoundsPage.ANNOUNCEMENTS -> AnnouncementsPage(state.announcements, callbacks.onEditAnnouncements)
            }
        }
    }
}

@Composable
private fun TtsBanner(status: TtsStatus) {
    val text = when (status) {
        TtsStatus.NoEngine -> stringResource(R.string.fn_tts_no_engine)
        is TtsStatus.LanguageMissing -> stringResource(R.string.fn_tts_language_missing, status.language.ifBlank { "—" })
        is TtsStatus.Error -> stringResource(R.string.fn_tts_error, status.message)
        is TtsStatus.Ready ->
            if (status.requestedLanguageAvailable) null else stringResource(R.string.fn_tts_fallback, status.language)
        TtsStatus.Initializing -> null
    }
    if (text != null) InfoBanner(text = text, tone = BannerTone.WARNING)
}

@Composable
private fun AlertsPage(state: SoundsState, callbacks: SoundsCallbacks) {
    if (state.alerts.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AxEmptyState(
                title = stringResource(R.string.fn_alerts_empty_title),
                message = stringResource(R.string.fn_alerts_empty_message),
                icon = AxIcons.Voice,
            )
        }
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(state.alerts, key = { it.id }) { alert ->
            AlertRow(
                alert = alert,
                data = state.pickers,
                onClick = { callbacks.onOpenAlert(alert.id) },
                onToggle = { callbacks.onSetAlertEnabled(alert.id, it) },
                onTest = { callbacks.onTestAlert(alert.id) },
            )
        }
    }
}

@Composable
private fun AlertRow(alert: VoiceAlert, data: PickerData, onClick: () -> Unit, onToggle: (Boolean) -> Unit, onTest: () -> Unit) {
    val colors = AxTheme.colors
    AxCard(
        modifier = Modifier.fillMaxWidth().height(60.dp),
        cornerRadius = AxTheme.shapes.control + 2.dp,
        elevation = 3.dp,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = alert.name.ifBlank { alert.text },
                    style = AxTheme.typography.bodyStrong,
                    color = if (alert.enabled) colors.text else colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = describeTrigger(alert.trigger, data) + " · " + priorityLabel(alert.priority),
                    style = AxTheme.typography.label,
                    color = colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            AxIconButton(icon = AxIcons.Play, contentDescription = stringResource(R.string.fn_test), onClick = onTest)
            AxSwitch(checked = alert.enabled, onCheckedChange = onToggle)
            Box(modifier = Modifier.width(4.dp))
        }
    }
}

@Composable
private fun AnnouncementsPage(announcements: Announcements, onEdit: ((Announcements) -> Announcements) -> Unit) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FormCard(title = stringResource(R.string.fn_ann_title)) {
            Caption(stringResource(R.string.fn_ann_help))
            AnnouncementRow(R.string.fn_ann_telemetry_lost, announcements.telemetryLost) { v -> onEdit { it.copy(telemetryLost = v) } }
            AnnouncementRow(R.string.fn_ann_telemetry_recovered, announcements.telemetryRecovered) { v -> onEdit { it.copy(telemetryRecovered = v) } }
            AnnouncementRow(R.string.fn_ann_low_tx, announcements.lowTxVoltage) { v -> onEdit { it.copy(lowTxVoltage = v) } }
            AnnouncementRow(R.string.fn_ann_flight_modes, announcements.flightModes) { v -> onEdit { it.copy(flightModes = v) } }
            AnnouncementRow(R.string.fn_ann_switches, announcements.switchPositions) { v -> onEdit { it.copy(switchPositions = v) } }
            AnnouncementRow(R.string.fn_ann_trim, announcements.trimBeeps) { v -> onEdit { it.copy(trimBeeps = v) } }
            AnnouncementRow(R.string.fn_ann_model_name, announcements.modelName) { v -> onEdit { it.copy(modelName = v) } }
        }
    }
}

@Composable
private fun AnnouncementRow(labelRes: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    FormRow(label = stringResource(labelRes)) {
        AxSwitch(checked = checked, onCheckedChange = onChange)
    }
}
