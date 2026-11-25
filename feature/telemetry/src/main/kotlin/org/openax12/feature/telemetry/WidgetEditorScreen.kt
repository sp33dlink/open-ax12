package org.openax12.feature.telemetry

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.openax12.core.designsystem.AxBackHandler
import org.openax12.core.designsystem.AxIcon
import org.openax12.core.designsystem.AxIcons
import org.openax12.core.designsystem.AxListGroup
import org.openax12.core.designsystem.AxSectionHeader
import org.openax12.core.designsystem.AxSettingRow
import org.openax12.core.designsystem.AxSwitch
import org.openax12.core.designsystem.AxTheme
import org.openax12.core.designsystem.AxValueBox
import org.openax12.core.designsystem.AxWell
import org.openax12.core.designsystem.ConfirmDialog
import org.openax12.core.designsystem.NumberField
import org.openax12.core.designsystem.PickerDialog
import org.openax12.core.designsystem.PickerOption
import org.openax12.core.designsystem.TextEntryDialog
import org.openax12.core.engine.TemplateRenderer
import org.openax12.core.model.WidgetConfig
import org.openax12.core.model.WidgetOptions
import org.openax12.core.model.WidgetType
import org.openax12.core.widgets.WidgetCard
import org.openax12.core.widgets.WidgetCatalog
import org.openax12.core.widgets.WidgetOptionKind
import org.openax12.core.widgets.WidgetOptionSpec

/** Accent colours offered for a widget; the first entry of the picker is "theme default". */
private val WidgetColors: List<String> = listOf("#2F7FB5", "#1FB8A6", "#F2A65A", "#12A66A", "#D9534F", "#8E5BD0", "#E3B505", "#5C6B77")

/**
 * Editor of one widget: type, sensor bindings, options, title, size and pin flag, with a live
 * preview. Stateless; [onCancel] and the system back go through [confirmDiscard].
 */
@Composable
internal fun WidgetEditorScreen(
    state: WidgetEditorState,
    onAction: (WidgetEditorAction) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val leave = { if (state.dirty) confirmDiscard = true else onCancel() }
    AxBackHandler(enabled = state.dirty) { confirmDiscard = true }
    Column(modifier.fillMaxSize()) {
        TelemetryTopBar(
            title = stringResource(if (state.isNew) R.string.telemetry_editor_title_add else R.string.telemetry_editor_title_edit),
            onBack = leave,
            actions = {
                ChromeButton(text = stringResource(R.string.telemetry_cancel), onClick = leave)
                ChromeButton(
                    text = stringResource(if (state.isNew) R.string.telemetry_add else R.string.telemetry_save),
                    onClick = { onAction(WidgetEditorAction.Save) },
                    icon = AxIcons.Check,
                    filled = true,
                    enabled = state.canSave,
                )
            },
        )
        val draft = state.draft
        if (draft != null) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PreviewColumn(state, draft, onAction, Modifier.width(250.dp))
                SettingsColumn(state, draft, onAction, Modifier.weight(1f))
            }
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
                onCancel()
            },
            onDismiss = { confirmDiscard = false },
        )
    }
}

@Composable
private fun PreviewColumn(
    state: WidgetEditorState,
    draft: WidgetConfig,
    onAction: (WidgetEditorAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pickType by rememberSaveable { mutableStateOf(false) }
    val info = WidgetCatalog.info(draft.type)
    Column(modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AxSectionHeader(stringResource(R.string.telemetry_widget_type))
        AxValueBox(
            text = stringResource(info.nameRes),
            onClick = { pickType = true },
            trailingIcon = AxIcons.ExpandMore,
            modifier = Modifier.fillMaxWidth(),
        )
        AxWell(Modifier.fillMaxWidth().height(172.dp), cornerRadius = AxTheme.shapes.panel, contentPadding = androidx.compose.foundation.layout.PaddingValues(10.dp), contentAlignment = Alignment.Center) {
            val ratio = (draft.w.toFloat() / draft.h.coerceAtLeast(1)).coerceIn(0.6f, 3f)
            WidgetCard(draft, Modifier.aspectRatio(ratio))
        }
        Text(
            text = stringResource(info.descriptionRes),
            style = AxTheme.typography.label,
            color = AxTheme.colors.textMuted,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
    if (pickType) {
        val options = WidgetCatalog.types.map { type ->
            PickerOption(value = type.type, label = stringResource(type.nameRes))
        }
        PickerDialog(
            title = stringResource(R.string.telemetry_widget_type),
            options = options,
            selected = draft.type,
            onSelect = {
                pickType = false
                onAction(WidgetEditorAction.SetType(it))
            },
            onDismiss = { pickType = false },
            columns = 3,
            searchable = false,
            width = 640.dp,
        )
    }
}

@Composable
private fun SettingsColumn(
    state: WidgetEditorState,
    draft: WidgetConfig,
    onAction: (WidgetEditorAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val info = WidgetCatalog.info(draft.type)
    var pickSlot by rememberSaveable { mutableStateOf<Int?>(null) }
    var editTitle by rememberSaveable { mutableStateOf(false) }
    var editTextKey by rememberSaveable { mutableStateOf<String?>(null) }
    Column(
        modifier = modifier
            .fillMaxHeight()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (info.sensorSlots.isNotEmpty()) {
            AxSectionHeader(stringResource(R.string.telemetry_section_sensors))
            AxListGroup {
                info.sensorSlots.forEachIndexed { index, slot ->
                    val bound = draft.sensors.getOrNull(index)
                    val enabled = index <= draft.sensors.size
                    AxSettingRow(
                        title = stringResource(slot.labelRes),
                        enabled = enabled,
                        control = {
                            AxValueBox(
                                text = if (bound != null) sensorLabel(bound, state.sensors) else stringResource(if (slot.required) R.string.telemetry_choose_sensor else R.string.telemetry_none),
                                onClick = { pickSlot = index },
                                enabled = enabled,
                                trailingIcon = AxIcons.ExpandMore,
                                modifier = Modifier.width(210.dp),
                            )
                        },
                    )
                }
            }
        }
        val options = info.options
        if (options.isNotEmpty()) {
            AxSectionHeader(stringResource(R.string.telemetry_section_options))
            AxListGroup {
                options.forEach { spec ->
                    OptionRow(spec, draft, onAction, onEditText = { editTextKey = spec.key })
                }
            }
        }
        AxSectionHeader(stringResource(R.string.telemetry_section_widget))
        AxListGroup {
            // Types without the "show title" option (the text label) have no title to edit.
            if (info.options.any { it.key == WidgetOptions.SHOW_TITLE }) {
                AxSettingRow(
                    title = stringResource(R.string.telemetry_option_title),
                    control = {
                        AxValueBox(
                            text = draft.title.ifBlank { stringResource(R.string.telemetry_title_auto) },
                            onClick = { editTitle = true },
                            modifier = Modifier.width(250.dp),
                        )
                    },
                )
            }
            AxSettingRow(title = stringResource(R.string.telemetry_width)) {
                NumberField(
                    value = draft.w,
                    onValueChange = { onAction(WidgetEditorAction.SetSize(it, draft.h)) },
                    min = 1,
                    max = state.columns,
                    steppers = true,
                    modifier = Modifier.width(170.dp),
                )
            }
            AxSettingRow(title = stringResource(R.string.telemetry_height)) {
                NumberField(
                    value = draft.h,
                    onValueChange = { onAction(WidgetEditorAction.SetSize(draft.w, it)) },
                    min = 1,
                    max = state.rows,
                    steppers = true,
                    modifier = Modifier.width(170.dp),
                )
            }
            AxSettingRow(
                title = stringResource(R.string.telemetry_pin_to_home),
                description = stringResource(R.string.telemetry_pin_to_home_hint),
            ) {
                AxSwitch(checked = draft.pinned, onCheckedChange = { onAction(WidgetEditorAction.SetPinned(it)) })
            }
        }
    }
    val slotIndex = pickSlot
    if (slotIndex != null) {
        val slot = info.sensorSlots.getOrNull(slotIndex)
        if (slot != null) {
            SensorPickerDialog(
                title = stringResource(slot.labelRes),
                sensors = state.sensors,
                suggestions = slot.suggestions,
                selected = draft.sensors.getOrNull(slotIndex),
                allowNone = !slot.required,
                onSelect = {
                    pickSlot = null
                    onAction(WidgetEditorAction.SetSensor(slotIndex, it))
                },
                onDismiss = { pickSlot = null },
            )
        } else {
            pickSlot = null
        }
    }
    if (editTitle) {
        TextEntryDialog(
            title = stringResource(R.string.telemetry_option_title),
            initialValue = draft.title,
            placeholder = stringResource(R.string.telemetry_title_auto),
            allowEmpty = true,
            maxLength = 24,
            onConfirm = {
                editTitle = false
                onAction(WidgetEditorAction.SetTitle(it))
            },
            onDismiss = { editTitle = false },
        )
    }
    val textKey = editTextKey
    if (textKey != null) {
        val templateError = stringResource(R.string.telemetry_template_error)
        TextEntryDialog(
            title = stringResource(R.string.telemetry_option_text),
            initialValue = draft.option(textKey).orEmpty(),
            placeholder = stringResource(R.string.telemetry_option_text_hint),
            allowEmpty = true,
            maxLength = 80,
            validate = { text ->
                TemplateRenderer.parse(text).errors.firstOrNull()?.let { String.format(templateError, it.token) }
            },
            onConfirm = {
                editTextKey = null
                onAction(WidgetEditorAction.SetOption(textKey, it))
            },
            onDismiss = { editTextKey = null },
        )
    }
}

/** One option of the widget type, edited with the control its kind needs. */
@Composable
private fun OptionRow(
    spec: WidgetOptionSpec,
    draft: WidgetConfig,
    onAction: (WidgetEditorAction) -> Unit,
    onEditText: () -> Unit,
) {
    val title = stringResource(spec.labelRes)
    val noneLabel = stringResource(unsetLabelRes(spec.key))
    val stored = draft.option(spec.key)
    when (spec.kind) {
        WidgetOptionKind.BOOLEAN -> AxSettingRow(title = title) {
            val checked = (stored ?: spec.default)?.toBooleanStrictOrNull() ?: false
            AxSwitch(checked = checked, onCheckedChange = { onAction(WidgetEditorAction.SetOption(spec.key, it.toString())) })
        }
        WidgetOptionKind.INTEGER -> {
            val offset = spec.displayOffset
            val storedValue = (stored ?: spec.default)?.toIntOrNull()
            val isSet = storedValue != null
            AxSettingRow(title = title) {
                NumberField(
                    value = (storedValue ?: 0) + offset,
                    onValueChange = { onAction(WidgetEditorAction.SetOption(spec.key, (it - offset).toString())) },
                    min = ((spec.min ?: 0.0).toInt()) + offset,
                    max = ((spec.max ?: 999.0).toInt()) + offset,
                    format = if (isSet) null else { _ -> noneLabel },
                    title = title,
                    modifier = Modifier.width(120.dp),
                )
                ClearOptionButton(visible = stored != null && spec.default == null, onClear = { onAction(WidgetEditorAction.SetOption(spec.key, null)) })
            }
        }
        WidgetOptionKind.NUMBER -> {
            val scaled = OptionCodec.toScaled(stored ?: spec.default)
            AxSettingRow(title = title) {
                NumberField(
                    value = scaled ?: 0,
                    onValueChange = { onAction(WidgetEditorAction.SetOption(spec.key, OptionCodec.fromScaled(it))) },
                    min = OptionCodec.scaledLimit(spec.min, -100_000.0),
                    max = OptionCodec.scaledLimit(spec.max, 100_000.0),
                    decimals = OptionCodec.NUMBER_DECIMALS,
                    step = 100,
                    format = if (scaled != null) null else { _ -> noneLabel },
                    title = title,
                    modifier = Modifier.width(120.dp),
                )
                ClearOptionButton(visible = stored != null, onClear = { onAction(WidgetEditorAction.SetOption(spec.key, null)) })
            }
        }
        WidgetOptionKind.TEXT -> AxSettingRow(title = title) {
            AxValueBox(
                text = stored?.ifBlank { null } ?: stringResource(R.string.telemetry_none),
                onClick = onEditText,
                modifier = Modifier.width(300.dp),
            )
        }
        WidgetOptionKind.COLOR -> AxSettingRow(title = title) {
            ColorChoice(selected = stored, onSelect = { onAction(WidgetEditorAction.SetOption(spec.key, it)) })
        }
    }
}

/** What an option that is not set shows: scale ends fall back to automatic scaling, thresholds are off. */
private fun unsetLabelRes(key: String): Int = when (key) {
    WidgetOptions.MIN, WidgetOptions.MAX -> R.string.telemetry_auto
    WidgetOptions.WARN_BELOW, WidgetOptions.WARN_ABOVE -> R.string.telemetry_off
    else -> R.string.telemetry_none
}

@Composable
private fun ClearOptionButton(visible: Boolean, onClear: () -> Unit) {
    if (visible) {
        org.openax12.core.designsystem.AxIconButton(
            icon = AxIcons.Close,
            contentDescription = stringResource(R.string.telemetry_clear),
            onClick = onClear,
            tint = AxTheme.colors.textMuted,
        )
    }
}

@Composable
private fun ColorChoice(selected: String?, onSelect: (String?) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        // "Theme default": an empty ring.
        Swatch(color = null, isSelected = selected == null, onClick = { onSelect(null) })
        WidgetColors.forEach { hex ->
            Swatch(color = Color(android.graphics.Color.parseColor(hex)), isSelected = selected.equals(hex, ignoreCase = true), onClick = { onSelect(hex) })
        }
    }
}

@Composable
private fun Swatch(color: Color?, isSelected: Boolean, onClick: () -> Unit) {
    val colors = AxTheme.colors
    Box(
        modifier = Modifier
            .size(40.dp)
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .semantics { this.selected = isSelected },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .then(if (color != null) Modifier.background(color, CircleShape) else Modifier)
                .border(if (isSelected) 3.dp else 1.5.dp, if (isSelected) colors.text else colors.outlineStrong, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (color == null) {
                AxIcon(AxIcons.Close, contentDescription = null, tint = colors.textFaint, size = 14.dp)
            }
        }
    }
}
