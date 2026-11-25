package org.openax12.feature.telemetry

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.openax12.core.designsystem.AxBackHandler
import org.openax12.core.designsystem.AxCard
import org.openax12.core.designsystem.AxDivider
import org.openax12.core.designsystem.AxDropdown
import org.openax12.core.designsystem.AxEmptyState
import org.openax12.core.designsystem.AxIcon
import org.openax12.core.designsystem.AxIconButton
import org.openax12.core.designsystem.AxIcons
import org.openax12.core.designsystem.AxListGroup
import org.openax12.core.designsystem.AxListRow
import org.openax12.core.designsystem.AxSectionHeader
import org.openax12.core.designsystem.AxSegmented
import org.openax12.core.designsystem.AxSettingRow
import org.openax12.core.designsystem.AxSwitch
import org.openax12.core.designsystem.AxTheme
import org.openax12.core.designsystem.AxValueBox
import org.openax12.core.designsystem.ConfirmDialog
import org.openax12.core.designsystem.NumberField
import org.openax12.core.designsystem.TextEntryDialog
import org.openax12.core.engine.TemplateRenderer
import org.openax12.core.model.BeepPattern
import org.openax12.core.model.CompareOp
import org.openax12.core.model.HapticPattern
import org.openax12.core.model.SensorAlarm
import org.openax12.core.model.SensorId
import org.openax12.core.model.SensorInfo
import org.openax12.core.model.SpeechPriority
import org.openax12.core.model.Sensors
import org.openax12.core.model.UnitConversion

/** Beeps offered for an alarm (the others are system sounds for trims and timers). */
private val AlarmBeeps = listOf(BeepPattern.SHORT, BeepPattern.DOUBLE, BeepPattern.TRIPLE, BeepPattern.LONG, BeepPattern.WARNING, BeepPattern.ERROR, BeepPattern.CONFIRM)

/** Sensors offered first when choosing the sensor of an alarm. */
private val AlarmSensorSuggestions = listOf(
    Sensors.BATTERY_VOLTAGE, Sensors.TX_BATTERY, Sensors.TX_RX_BATTERY, Sensors.BATTERY_REMAINING, Sensors.BATTERY_CAPACITY,
    Sensors.BATTERY_CURRENT, Sensors.TX_LINK_QUALITY, Sensors.LINK_RX_QUALITY, Sensors.TX_RSSI, Sensors.BARO_ALTITUDE,
    Sensors.GPS_HOME_DISTANCE, Sensors.AIRSPEED,
)

/**
 * The alarm screen: the list of alarms, or the editor of one alarm while `state.draft` is set.
 *
 * @param previewText renders a voice text template with the current values, for the preview line
 */
@Composable
internal fun AlarmScreen(
    state: AlarmUiState,
    onAction: (AlarmAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    previewText: (String) -> String = { it },
) {
    val draft = state.draft
    if (draft == null) {
        AlarmList(state, onAction, onBack, modifier)
    } else {
        AlarmEditor(state, draft, onAction, previewText, modifier)
    }
}

@Composable
private fun AlarmList(state: AlarmUiState, onAction: (AlarmAction) -> Unit, onBack: () -> Unit, modifier: Modifier) {
    Column(modifier.fillMaxSize()) {
        TelemetryTopBar(
            title = stringResource(R.string.telemetry_alarms_title),
            onBack = onBack,
            actions = {
                ChromeButton(
                    text = stringResource(R.string.telemetry_alarm_add),
                    onClick = { onAction(AlarmAction.Add) },
                    icon = AxIcons.Add,
                    filled = true,
                )
            },
        )
        if (state.alarms.isEmpty()) {
            AxEmptyState(
                title = stringResource(R.string.telemetry_alarms_empty),
                message = stringResource(R.string.telemetry_alarms_empty_message),
                icon = AxIcons.Alarm,
                actionLabel = stringResource(R.string.telemetry_alarm_add),
                onAction = { onAction(AlarmAction.Add) },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AxListGroup {
                    state.alarms.forEachIndexed { index, alarm ->
                        if (index > 0) AxDivider()
                        AlarmRow(alarm, state, onAction)
                    }
                }
                Text(
                    text = stringResource(R.string.telemetry_alarms_builtin_hint),
                    style = AxTheme.typography.label,
                    color = AxTheme.colors.textMuted,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun AlarmRow(alarm: SensorAlarm, state: AlarmUiState, onAction: (AlarmAction) -> Unit) {
    val colors = AxTheme.colors
    val active = alarm.id in state.activeIds
    AxListRow(
        title = alarm.name.ifBlank { sensorLabel(alarm.sensor, state.sensors) },
        subtitle = alarmSummary(alarm, state.sensors),
        onClick = { onAction(AlarmAction.Open(alarm.id)) },
        leading = {
            AxSwitch(checked = alarm.enabled, onCheckedChange = { onAction(AlarmAction.SetEnabled(alarm.id, it)) })
        },
        minHeight = 56.dp,
        trailing = {
            if (active) StatusTag(stringResource(R.string.telemetry_alarm_active), colors.error, Modifier.padding(end = 8.dp))
            val outputs = alarm.outputs
            if (outputs.voiceText.isNotBlank()) OutputIcon(AxIcons.Voice)
            if (outputs.beep != null) OutputIcon(AxIcons.Sound)
            if (outputs.haptic != null) OutputIcon(AxIcons.Haptic)
            if (outputs.popup) OutputIcon(AxIcons.Warning)
            AxIcon(AxIcons.ChevronRight, contentDescription = null, tint = colors.textFaint, modifier = Modifier.padding(start = 4.dp))
        },
    )
}

@Composable
private fun OutputIcon(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    AxIcon(icon, contentDescription = null, tint = AxTheme.colors.textMuted, size = 18.dp, modifier = Modifier.padding(horizontal = 2.dp))
}

/** `RxBt < 10.5V after 2 s`: the rule of an alarm in one line. */
@Composable
internal fun alarmSummary(alarm: SensorAlarm, sensors: List<SensorInfo>): String {
    val info = sensors.firstOrNull { it.id == alarm.sensor }
    val precision = (info?.precision ?: 1).coerceIn(0, 3)
    val threshold = UnitConversion.formatNumber(alarm.threshold, precision) + (info?.unit?.symbol ?: "")
    val rule = stringResource(R.string.telemetry_alarm_rule, sensorLabel(alarm.sensor, sensors), compareSymbol(alarm.op), threshold)
    return if (alarm.delaySec > 0.0) {
        rule + " " + stringResource(R.string.telemetry_alarm_rule_delay, UnitConversion.formatNumber(alarm.delaySec, 1))
    } else {
        rule
    }
}

@Composable
private fun compareSymbol(op: CompareOp): String = stringResource(
    when (op) {
        CompareOp.LESS -> R.string.telemetry_op_less
        CompareOp.GREATER -> R.string.telemetry_op_greater
        CompareOp.EQUAL -> R.string.telemetry_op_equal
        CompareOp.DISABLED -> R.string.telemetry_op_none
    },
)

@Composable
private fun AlarmEditor(
    state: AlarmUiState,
    draft: SensorAlarm,
    onAction: (AlarmAction) -> Unit,
    previewText: (String) -> String,
    modifier: Modifier,
) {
    val stored = state.alarms.firstOrNull { it.id == draft.id }
    val dirty = state.isNewDraft || stored != draft
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val leave = { if (dirty) confirmDiscard = true else onAction(AlarmAction.Cancel) }
    AxBackHandler { leave() }
    Column(modifier.fillMaxSize()) {
        TelemetryTopBar(
            title = stringResource(if (state.isNewDraft) R.string.telemetry_alarm_new else R.string.telemetry_alarm_edit),
            onBack = leave,
            actions = {
                if (!state.isNewDraft) {
                    AxIconButton(
                        icon = AxIcons.Delete,
                        contentDescription = stringResource(R.string.telemetry_alarm_delete),
                        onClick = { confirmDelete = true },
                        tint = AxTheme.colors.onChrome,
                    )
                }
                ChromeButton(text = stringResource(R.string.telemetry_cancel), onClick = leave)
                ChromeButton(
                    text = stringResource(R.string.telemetry_save),
                    onClick = { onAction(AlarmAction.Save) },
                    icon = AxIcons.Check,
                    filled = true,
                    enabled = dirty,
                )
            },
        )
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ConditionColumn(state, draft, onAction, Modifier.weight(1f))
            ActionColumn(state, draft, onAction, previewText, Modifier.weight(1f))
        }
    }
    if (confirmDiscard) {
        ConfirmDialog(
            title = stringResource(R.string.telemetry_discard_title),
            message = stringResource(R.string.telemetry_discard_message),
            confirmLabel = stringResource(R.string.telemetry_discard),
            destructive = true,
            onConfirm = {
                confirmDiscard = false
                onAction(AlarmAction.Cancel)
            },
            onDismiss = { confirmDiscard = false },
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.telemetry_alarm_delete),
            message = stringResource(R.string.telemetry_alarm_delete_message),
            confirmLabel = stringResource(R.string.telemetry_delete),
            destructive = true,
            onConfirm = {
                confirmDelete = false
                onAction(AlarmAction.Delete(draft.id))
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun ConditionColumn(state: AlarmUiState, draft: SensorAlarm, onAction: (AlarmAction) -> Unit, modifier: Modifier) {
    val info = state.draftSensor
    val decimals = (info?.precision ?: 1).coerceIn(0, 3)
    val unit = info?.unit?.symbol.orEmpty()
    var pickSensor by rememberSaveable { mutableStateOf(false) }
    val ops = listOf(CompareOp.LESS, CompareOp.GREATER, CompareOp.EQUAL)
    val opLabels = ops.map { compareSymbol(it) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AxSectionHeader(stringResource(R.string.telemetry_alarm_section_condition))
        AxListGroup {
            AxSettingRow(title = stringResource(R.string.telemetry_alarm_sensor)) {
                AxValueBox(
                    text = sensorLabel(draft.sensor, state.sensors),
                    onClick = { pickSensor = true },
                    trailingIcon = AxIcons.ExpandMore,
                    modifier = Modifier.width(190.dp),
                )
            }
            AxSettingRow(title = stringResource(R.string.telemetry_alarm_when)) {
                AxSegmented(
                    options = opLabels,
                    selectedIndex = ops.indexOf(draft.op).coerceAtLeast(0),
                    onSelected = { onAction(AlarmAction.Edit(draft.copy(op = ops[it]))) },
                    modifier = Modifier.width(190.dp),
                )
            }
            AxSettingRow(title = stringResource(R.string.telemetry_alarm_threshold)) {
                NumberField(
                    value = scale(draft.threshold, decimals),
                    onValueChange = { onAction(AlarmAction.Edit(draft.copy(threshold = unscale(it, decimals)))) },
                    min = -scale(ALARM_LIMIT, decimals),
                    max = scale(ALARM_LIMIT, decimals),
                    decimals = decimals,
                    unit = unit,
                    step = scale(1.0, decimals).coerceAtLeast(1),
                    title = stringResource(R.string.telemetry_alarm_threshold),
                    modifier = Modifier.width(190.dp),
                )
            }
            AxSettingRow(
                title = stringResource(R.string.telemetry_alarm_hysteresis),
                description = stringResource(R.string.telemetry_alarm_hysteresis_hint),
            ) {
                NumberField(
                    value = scale(draft.hysteresis, decimals),
                    onValueChange = { onAction(AlarmAction.Edit(draft.copy(hysteresis = unscale(it, decimals)))) },
                    min = 0,
                    max = scale(ALARM_LIMIT, decimals),
                    decimals = decimals,
                    unit = unit,
                    title = stringResource(R.string.telemetry_alarm_hysteresis),
                    modifier = Modifier.width(190.dp),
                )
            }
            AxSettingRow(
                title = stringResource(R.string.telemetry_alarm_delay),
                description = stringResource(R.string.telemetry_alarm_delay_hint),
            ) {
                NumberField(
                    value = scale(draft.delaySec, 1),
                    onValueChange = { onAction(AlarmAction.Edit(draft.copy(delaySec = unscale(it, 1)))) },
                    min = 0,
                    max = 6000,
                    decimals = 1,
                    unit = " s",
                    step = 5,
                    title = stringResource(R.string.telemetry_alarm_delay),
                    modifier = Modifier.width(190.dp),
                )
            }
            AxSettingRow(title = stringResource(R.string.telemetry_alarm_only_linked), description = stringResource(R.string.telemetry_alarm_only_linked_hint)) {
                AxSwitch(checked = draft.onlyWhenLinked, onCheckedChange = { onAction(AlarmAction.Edit(draft.copy(onlyWhenLinked = it))) })
            }
        }
    }
    if (pickSensor) {
        SensorPickerDialog(
            title = stringResource(R.string.telemetry_alarm_sensor),
            sensors = state.sensors,
            suggestions = AlarmSensorSuggestions,
            selected = draft.sensor,
            allowNone = false,
            onSelect = { sensor ->
                pickSensor = false
                if (sensor != null) onAction(AlarmAction.Edit(draft.copy(sensor = sensor)))
            },
            onDismiss = { pickSensor = false },
        )
    }
}

@Composable
private fun ActionColumn(
    state: AlarmUiState,
    draft: SensorAlarm,
    onAction: (AlarmAction) -> Unit,
    previewText: (String) -> String,
    modifier: Modifier,
) {
    val outputs = draft.outputs
    var editName by rememberSaveable { mutableStateOf(false) }
    var editVoice by rememberSaveable { mutableStateOf(false) }
    val noneLabel = stringResource(R.string.telemetry_none)
    val beepLabels = listOf(noneLabel) + AlarmBeeps.map { beepLabel(it) }
    val hapticLabels = listOf(noneLabel) + HapticPattern.entries.map { hapticLabel(it) }
    val soundOptions = listOf(noneLabel) + state.soundFiles
    val priorities = SpeechPriority.entries
    val priorityLabels = priorities.map { priorityLabel(it) }
    fun withOutputs(change: (org.openax12.core.model.AlarmOutputs) -> org.openax12.core.model.AlarmOutputs) =
        onAction(AlarmAction.Edit(draft.copy(outputs = change(outputs))))
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AxSectionHeader(stringResource(R.string.telemetry_alarm_section_action))
        AxListGroup {
            AxSettingRow(title = stringResource(R.string.telemetry_alarm_name)) {
                AxValueBox(
                    text = draft.name.ifBlank { stringResource(R.string.telemetry_alarm_name_auto) },
                    onClick = { editName = true },
                    modifier = Modifier.width(190.dp),
                )
            }
            AxSettingRow(title = stringResource(R.string.telemetry_alarm_voice)) {
                AxValueBox(
                    text = outputs.voiceText.ifBlank { noneLabel },
                    onClick = { editVoice = true },
                    modifier = Modifier.width(190.dp),
                )
            }
            if (outputs.voiceText.isNotBlank()) {
                Text(
                    text = stringResource(R.string.telemetry_alarm_voice_preview, previewText(outputs.voiceText)),
                    style = AxTheme.typography.label,
                    color = AxTheme.colors.textMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                )
            }
            AxSettingRow(title = stringResource(R.string.telemetry_alarm_beep)) {
                AxDropdown(
                    options = beepLabels,
                    selectedIndex = outputs.beep?.let { AlarmBeeps.indexOf(it) + 1 } ?: 0,
                    onSelected = { index -> withOutputs { it.copy(beep = if (index == 0) null else AlarmBeeps[index - 1]) } },
                    title = stringResource(R.string.telemetry_alarm_beep),
                    modifier = Modifier.width(190.dp),
                )
            }
            AxSettingRow(title = stringResource(R.string.telemetry_alarm_haptic)) {
                AxDropdown(
                    options = hapticLabels,
                    selectedIndex = outputs.haptic?.let { HapticPattern.entries.indexOf(it) + 1 } ?: 0,
                    onSelected = { index -> withOutputs { it.copy(haptic = if (index == 0) null else HapticPattern.entries[index - 1]) } },
                    title = stringResource(R.string.telemetry_alarm_haptic),
                    modifier = Modifier.width(190.dp),
                )
            }
            AxSettingRow(title = stringResource(R.string.telemetry_alarm_sound_file)) {
                AxDropdown(
                    options = soundOptions,
                    selectedIndex = if (outputs.soundFile.isBlank()) 0 else soundOptions.indexOf(outputs.soundFile).let { if (it < 0) 0 else it },
                    onSelected = { index -> withOutputs { it.copy(soundFile = if (index == 0) "" else soundOptions[index]) } },
                    title = stringResource(R.string.telemetry_alarm_sound_file),
                    modifier = Modifier.width(190.dp),
                )
            }
            AxSettingRow(title = stringResource(R.string.telemetry_alarm_popup)) {
                AxSwitch(checked = outputs.popup, onCheckedChange = { checked -> withOutputs { it.copy(popup = checked) } })
            }
            AxSettingRow(
                title = stringResource(R.string.telemetry_alarm_repeat),
                description = stringResource(R.string.telemetry_alarm_repeat_hint),
            ) {
                NumberField(
                    value = draft.repeatSec,
                    onValueChange = { onAction(AlarmAction.Edit(draft.copy(repeatSec = it))) },
                    min = 0,
                    max = 3600,
                    unit = " s",
                    step = 5,
                    format = { if (it == 0) noneLabel else "$it s" },
                    title = stringResource(R.string.telemetry_alarm_repeat),
                    modifier = Modifier.width(190.dp),
                )
            }
            AxSettingRow(title = stringResource(R.string.telemetry_alarm_priority)) {
                AxDropdown(
                    options = priorityLabels,
                    selectedIndex = priorities.indexOf(draft.priority).coerceAtLeast(0),
                    onSelected = { onAction(AlarmAction.Edit(draft.copy(priority = priorities[it]))) },
                    title = stringResource(R.string.telemetry_alarm_priority),
                    modifier = Modifier.width(190.dp),
                )
            }
            AxSettingRow(title = stringResource(R.string.telemetry_alarm_enabled)) {
                AxSwitch(checked = draft.enabled, onCheckedChange = { onAction(AlarmAction.Edit(draft.copy(enabled = it))) })
            }
        }
    }
    if (editName) {
        TextEntryDialog(
            title = stringResource(R.string.telemetry_alarm_name),
            initialValue = draft.name,
            placeholder = stringResource(R.string.telemetry_alarm_name_auto),
            allowEmpty = true,
            maxLength = 32,
            onConfirm = {
                editName = false
                onAction(AlarmAction.Edit(draft.copy(name = it)))
            },
            onDismiss = { editName = false },
        )
    }
    if (editVoice) {
        val templateError = stringResource(R.string.telemetry_template_error)
        TextEntryDialog(
            title = stringResource(R.string.telemetry_alarm_voice),
            initialValue = outputs.voiceText,
            placeholder = stringResource(R.string.telemetry_alarm_voice_hint),
            allowEmpty = true,
            maxLength = 80,
            validate = { text -> TemplateRenderer.parse(text).errors.firstOrNull()?.let { String.format(templateError, it.token) } },
            onConfirm = { text ->
                editVoice = false
                withOutputs { it.copy(voiceText = text) }
            },
            onDismiss = { editVoice = false },
        )
    }
}

private const val ALARM_LIMIT = 100_000.0

private fun scale(value: Double, decimals: Int): Int = Math.round(value * Math.pow(10.0, decimals.toDouble())).toInt()

private fun unscale(value: Int, decimals: Int): Double = value / Math.pow(10.0, decimals.toDouble())

@Composable
private fun beepLabel(pattern: BeepPattern): String = stringResource(
    when (pattern) {
        BeepPattern.SHORT -> R.string.telemetry_beep_short
        BeepPattern.DOUBLE -> R.string.telemetry_beep_double
        BeepPattern.TRIPLE -> R.string.telemetry_beep_triple
        BeepPattern.LONG -> R.string.telemetry_beep_long
        BeepPattern.WARNING -> R.string.telemetry_beep_warning
        BeepPattern.ERROR -> R.string.telemetry_beep_error
        else -> R.string.telemetry_beep_confirm
    },
)

@Composable
private fun hapticLabel(pattern: HapticPattern): String = stringResource(
    when (pattern) {
        HapticPattern.SHORT -> R.string.telemetry_beep_short
        HapticPattern.DOUBLE -> R.string.telemetry_beep_double
        HapticPattern.TRIPLE -> R.string.telemetry_beep_triple
        HapticPattern.LONG -> R.string.telemetry_beep_long
        HapticPattern.WARNING -> R.string.telemetry_beep_warning
    },
)

@Composable
private fun priorityLabel(priority: SpeechPriority): String = stringResource(
    when (priority) {
        SpeechPriority.LOW -> R.string.telemetry_priority_low
        SpeechPriority.NORMAL -> R.string.telemetry_priority_normal
        SpeechPriority.HIGH -> R.string.telemetry_priority_high
        SpeechPriority.ALARM -> R.string.telemetry_priority_alarm
    },
)
