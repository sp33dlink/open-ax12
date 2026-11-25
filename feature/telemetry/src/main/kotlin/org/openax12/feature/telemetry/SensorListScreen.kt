package org.openax12.feature.telemetry

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.openax12.core.designsystem.AxButton
import org.openax12.core.designsystem.AxButtonVariant
import org.openax12.core.designsystem.AxCard
import org.openax12.core.designsystem.AxChip
import org.openax12.core.designsystem.AxDialog
import org.openax12.core.designsystem.AxDivider
import org.openax12.core.designsystem.AxDropdown
import org.openax12.core.designsystem.AxEmptyState
import org.openax12.core.designsystem.AxIconButton
import org.openax12.core.designsystem.AxIcons
import org.openax12.core.designsystem.AxSegmented
import org.openax12.core.designsystem.AxSettingRow
import org.openax12.core.designsystem.AxSwitch
import org.openax12.core.designsystem.AxTextField
import org.openax12.core.designsystem.AxTheme
import org.openax12.core.designsystem.ConfirmDialog
import org.openax12.core.model.SensorId
import org.openax12.core.model.SensorInfo
import org.openax12.core.model.SensorOverride
import org.openax12.core.model.SensorUnit
import org.openax12.core.model.SensorValue

private val ValueWidth = 112.dp
private val BoundWidth = 78.dp
private val AgeWidth = 66.dp

/**
 * List of all sensors with their current values and the per-model overrides.
 *
 * @param nowMs monotonic "now" used for the age column (see [rememberNowMs])
 * @param liveValue the value of a live sensor (inputs, channels); they are not part of `state.values`
 */
@Composable
internal fun SensorListScreen(
    state: SensorListState,
    onAction: (SensorListAction) -> Unit,
    onBack: () -> Unit,
    nowMs: Long,
    modifier: Modifier = Modifier,
    liveValue: @Composable (SensorId) -> SensorValue? = { null },
) {
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmResetAll by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxSize()) {
        TelemetryTopBar(
            title = stringResource(R.string.telemetry_sensors_title),
            onBack = onBack,
            actions = {
                ChromeButton(
                    text = stringResource(R.string.telemetry_reset_min_max_all),
                    onClick = { confirmResetAll = true },
                    icon = AxIcons.Reset,
                )
            },
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterRow(state, onAction)
            AxCard(modifier = Modifier.weight(1f).fillMaxWidth(), clipContent = true, cornerRadius = AxTheme.shapes.panel, elevation = 4.dp) {
                Column(Modifier.fillMaxSize()) {
                    ColumnHeader()
                    if (state.sensors.isEmpty()) {
                        AxEmptyState(
                            title = stringResource(if (state.total == 0) R.string.telemetry_sensors_empty else R.string.telemetry_sensors_no_match),
                            message = if (state.total == 0) stringResource(R.string.telemetry_sensors_empty_message) else null,
                            icon = AxIcons.Sensor,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(state.sensors, key = { it.id.key }) { info ->
                                val value = if (info.id.isLive) liveValue(info.id) else state.values[info.id]
                                SensorRow(
                                    info = info,
                                    value = value,
                                    overridden = state.overrides.containsKey(info.id.key),
                                    state = state,
                                    nowMs = nowMs,
                                    onEdit = { editing = info.id.key },
                                )
                                AxDivider()
                            }
                        }
                    }
                }
            }
        }
    }
    val editedInfo = editing?.let { key -> state.sensors.firstOrNull { it.id.key == key } }
    if (editedInfo != null) {
        SensorOverrideDialog(
            info = editedInfo,
            current = state.overrides[editedInfo.id.key],
            onSave = {
                editing = null
                onAction(SensorListAction.SaveOverride(editedInfo.id, it))
            },
            onResetMinMax = {
                editing = null
                onAction(SensorListAction.ResetMinMax(editedInfo.id))
            },
            onDismiss = { editing = null },
        )
    } else if (editing != null) {
        editing = null
    }
    if (confirmResetAll) {
        ConfirmDialog(
            title = stringResource(R.string.telemetry_reset_min_max_all),
            message = stringResource(R.string.telemetry_reset_min_max_message),
            onConfirm = {
                confirmResetAll = false
                onAction(SensorListAction.ResetMinMax(null))
            },
            onDismiss = { confirmResetAll = false },
        )
    }
}

/** A monotonic clock reading that updates every second, for age displays. */
@Composable
internal fun rememberNowMs(): Long {
    val now by produceState(initialValue = android.os.SystemClock.elapsedRealtime()) {
        while (true) {
            delay(1000)
            value = android.os.SystemClock.elapsedRealtime()
        }
    }
    return now
}

@Composable
private fun FilterRow(state: SensorListState, onAction: (SensorListAction) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AxChip(
            text = stringResource(R.string.telemetry_filter_all),
            selected = state.category == null,
            onClick = { onAction(SensorListAction.SetCategory(null)) },
        )
        SensorCategory.entries.forEach { category ->
            AxChip(
                text = sensorCategoryLabel(category),
                selected = state.category == category,
                onClick = { onAction(SensorListAction.SetCategory(category)) },
            )
        }
        AxTextField(
            value = state.query,
            onValueChange = { onAction(SensorListAction.SetQuery(it)) },
            modifier = Modifier.weight(1f),
            placeholder = stringResource(R.string.telemetry_search_sensors),
            leadingIcon = AxIcons.Search,
        )
    }
}

@Composable
private fun ColumnHeader() {
    val colors = AxTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp)
            .padding(start = 14.dp, end = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.telemetry_col_sensor), style = AxTheme.typography.label, color = colors.textMuted, modifier = Modifier.weight(1f))
        Text(stringResource(R.string.telemetry_col_value), style = AxTheme.typography.label, color = colors.textMuted, textAlign = TextAlign.End, modifier = Modifier.width(ValueWidth))
        Text(stringResource(R.string.telemetry_col_min), style = AxTheme.typography.label, color = colors.textMuted, textAlign = TextAlign.End, modifier = Modifier.width(BoundWidth))
        Text(stringResource(R.string.telemetry_col_max), style = AxTheme.typography.label, color = colors.textMuted, textAlign = TextAlign.End, modifier = Modifier.width(BoundWidth))
        Text(stringResource(R.string.telemetry_col_age), style = AxTheme.typography.label, color = colors.textMuted, textAlign = TextAlign.End, modifier = Modifier.width(AgeWidth))
    }
    AxDivider(inset = 0.dp)
}

@Composable
private fun SensorRow(
    info: SensorInfo,
    value: SensorValue?,
    overridden: Boolean,
    state: SensorListState,
    nowMs: Long,
    onEdit: () -> Unit,
) {
    val colors = AxTheme.colors
    val lost = value?.lost == true
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(start = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .alpha(if (info.hidden) 0.5f else 1f),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = info.name,
                    style = AxTheme.typography.bodyStrong,
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (overridden) StatusTag(stringResource(R.string.telemetry_tag_renamed), colors.accent)
                if (info.hidden) StatusTag(stringResource(R.string.telemetry_tag_hidden), colors.textMuted)
                if (lost) StatusTag(stringResource(R.string.telemetry_tag_lost), colors.warning)
            }
            Text(
                text = listOfNotNull(info.templateName?.takeIf { it != info.name }, info.id.key).joinToString(" · "),
                style = AxTheme.typography.labelSmall,
                color = colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val dim = if (lost || value == null) 0.55f else 1f
        Text(
            text = formatSensorValue(value, state.units),
            style = AxTheme.typography.value,
            color = colors.text,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .width(ValueWidth)
                .alpha(dim),
        )
        Text(
            text = formatSensorBound(value?.min ?: Double.NaN, value, state.units),
            style = AxTheme.typography.value,
            color = colors.textMuted,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(BoundWidth),
        )
        Text(
            text = formatSensorBound(value?.max ?: Double.NaN, value, state.units),
            style = AxTheme.typography.value,
            color = colors.textMuted,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(BoundWidth),
        )
        Text(
            text = if (value == null || info.id.isLive) "" else ageText(Age.of(value.ageMs(nowMs))),
            style = AxTheme.typography.value,
            color = colors.textMuted,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(AgeWidth),
        )
        AxIconButton(
            icon = AxIcons.Edit,
            contentDescription = stringResource(R.string.telemetry_cd_edit_sensor, info.name),
            onClick = onEdit,
            tint = colors.textMuted,
        )
    }
}

/** "12 s", "3 min", "1 h 5 min", "2 d". */
@Composable
internal fun ageText(age: Age): String = when (age.unit) {
    AgeUnit.SECONDS -> stringResource(R.string.telemetry_age_seconds, age.first)
    AgeUnit.MINUTES -> stringResource(R.string.telemetry_age_minutes, age.first)
    AgeUnit.HOURS_MINUTES -> stringResource(R.string.telemetry_age_hours_minutes, age.first, age.second)
    AgeUnit.DAYS -> stringResource(R.string.telemetry_age_days, age.first)
}

/** Editor of the per-model override of one sensor. */
@Composable
internal fun SensorOverrideDialog(
    info: SensorInfo,
    current: SensorOverride?,
    onSave: (SensorOverride) -> Unit,
    onResetMinMax: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(current?.name.orEmpty()) }
    var unit by rememberSaveable { mutableStateOf(current?.unit) }
    var precision by rememberSaveable { mutableStateOf(current?.precision) }
    var hidden by rememberSaveable { mutableStateOf(current?.hidden == true) }
    // Offered units: the symbolled ones, plus "default".
    val units = remember { SensorUnit.entries.filter { it != SensorUnit.TEXT } }
    val defaultUnitLabel = stringResource(R.string.telemetry_default_with, info.unit.pickerLabel())
    val unitLabels = remember(defaultUnitLabel) { listOf(defaultUnitLabel) + units.map { it.pickerLabel() } }
    val precisionLabels = listOf(stringResource(R.string.telemetry_default_short)) + (0..3).map { it.toString() }
    AxDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.telemetry_sensor_edit_title, info.defaultName),
        icon = AxIcons.Sensor,
        width = 560.dp,
        alignment = Alignment.TopCenter,
        dismissOnOutsideClick = false,
        buttons = {
            AxButton(text = stringResource(R.string.telemetry_reset_min_max), onClick = onResetMinMax, variant = AxButtonVariant.Text)
            Box(Modifier.weight(1f))
            AxButton(text = stringResource(R.string.telemetry_cancel), onClick = onDismiss, variant = AxButtonVariant.Text)
            AxButton(
                text = stringResource(R.string.telemetry_save),
                onClick = { onSave(SensorOverride(name = name, unit = unit, precision = precision, hidden = hidden)) },
                variant = AxButtonVariant.Primary,
            )
        },
    ) {
        AxSettingRow(title = stringResource(R.string.telemetry_sensor_name)) {
            AxTextField(
                value = name,
                onValueChange = { name = it.take(24) },
                placeholder = info.defaultName,
                modifier = Modifier.width(280.dp),
            )
        }
        AxSettingRow(title = stringResource(R.string.telemetry_sensor_unit)) {
            AxDropdown(
                options = unitLabels,
                selectedIndex = unit?.let { units.indexOf(it) + 1 } ?: 0,
                onSelected = { unit = if (it == 0) null else units[it - 1] },
                title = stringResource(R.string.telemetry_sensor_unit),
                columns = 4,
                modifier = Modifier.width(280.dp),
            )
        }
        AxSettingRow(title = stringResource(R.string.telemetry_sensor_precision)) {
            AxSegmented(
                options = precisionLabels,
                selectedIndex = (precision?.plus(1)) ?: 0,
                onSelected = { precision = if (it == 0) null else it - 1 },
                modifier = Modifier.width(280.dp),
            )
        }
        AxSettingRow(
            title = stringResource(R.string.telemetry_sensor_hidden),
            description = stringResource(R.string.telemetry_sensor_hidden_hint),
        ) {
            AxSwitch(checked = hidden, onCheckedChange = { hidden = it })
        }
    }
}
