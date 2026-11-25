package org.openax12.feature.functions.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.openax12.core.designsystem.AxButton
import org.openax12.core.designsystem.AxButtonVariant
import org.openax12.core.designsystem.AxCard
import org.openax12.core.designsystem.AxChip
import org.openax12.core.designsystem.AxDimens
import org.openax12.core.designsystem.AxDropdown
import org.openax12.core.designsystem.AxIcon
import org.openax12.core.designsystem.AxIconButton
import org.openax12.core.designsystem.AxIcons
import org.openax12.core.designsystem.AxTheme
import org.openax12.core.designsystem.AxValueBox
import org.openax12.core.designsystem.PickerDialog
import org.openax12.core.designsystem.PickerOption
import org.openax12.core.model.SensorId
import org.openax12.core.model.SourceId
import org.openax12.feature.functions.R

/** Gutter between the screen edge and the content. */
internal val ScreenPadding: Dp = 10.dp

/** Width of the small numeric fields. */
internal val NumberWidth: Dp = 84.dp

/** Width of dropdowns that show a short label. */
internal val DropdownWidth: Dp = 150.dp

/** Width of source / sensor / channel fields. */
internal val SourceWidth: Dp = 132.dp

/**
 * Top bar of the full-screen editors (logical switch, special function, voice alert): navy like
 * the model-setup chrome, with a back arrow, the title and a "Done" button that applies the draft
 * to the model (staged; the real Save of the radio stays in the model setup chrome).
 *
 * @param dirty shows the unsaved-draft dot on the button
 */
@Composable
internal fun EditorScaffold(
    title: String,
    subtitle: String?,
    onBack: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    dirty: Boolean = false,
    doneEnabled: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    val colors = AxTheme.colors
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(AxDimens.SetupBarHeight)
                .background(colors.chrome),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AxIconButton(
                icon = AxIcons.Back,
                contentDescription = stringResource(R.string.fn_back),
                onClick = onBack,
                tint = colors.onChrome,
                modifier = Modifier.padding(start = 4.dp),
            )
            Column(modifier = Modifier.weight(1f).padding(start = 6.dp)) {
                Text(
                    text = title,
                    style = AxTheme.typography.chrome,
                    color = colors.onChrome,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = AxTheme.typography.labelSmall,
                        color = colors.onChromeMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            actions()
            DoneButton(
                dirty = dirty,
                enabled = doneEnabled,
                onClick = onDone,
                modifier = Modifier.padding(start = 8.dp, end = 8.dp),
            )
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) { content() }
    }
}

@Composable
private fun DoneButton(dirty: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = AxTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = modifier
            .height(AxDimens.SetupBarHeight)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .height(34.dp)
                .drawBehind {
                    val b = 1.5.dp.toPx()
                    val r = CornerRadius(4.dp.toPx())
                    if (pressed) drawRoundRect(colors.onChrome.copy(alpha = 0.18f), cornerRadius = r)
                    drawRoundRect(
                        color = colors.onChrome.copy(alpha = if (enabled) 1f else 0.45f),
                        topLeft = Offset(b / 2f, b / 2f),
                        size = Size(size.width - b, size.height - b),
                        cornerRadius = r,
                        style = Stroke(width = b),
                    )
                    if (dirty) {
                        val c = Offset(size.width - 1.dp.toPx(), 1.dp.toPx())
                        drawCircle(colors.chrome, radius = 6.5.dp.toPx(), center = c)
                        drawCircle(colors.orange, radius = 4.5.dp.toPx(), center = c)
                    }
                }
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.fn_done),
                style = AxTheme.typography.chrome,
                color = colors.onChrome.copy(alpha = if (enabled) 1f else 0.45f),
                maxLines = 1,
            )
        }
    }
}

/** A raised card with an optional title; the body is a padded column. */
@Composable
internal fun FormCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    AxCard(modifier = modifier.fillMaxWidth(), cornerRadius = AxTheme.shapes.panel, elevation = 4.dp) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (title != null) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = AxTheme.typography.titleSmall,
                        color = AxTheme.colors.accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    trailing?.invoke(this)
                }
            }
            content()
        }
    }
}

/** A label with a control to its right, 44 dp high at least: the row of every settings form. */
@Composable
internal fun FormRow(
    label: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    control: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(if (description == null) AxDimens.MinTouch else 52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(text = label, style = AxTheme.typography.body, color = AxTheme.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (description != null) {
                Text(
                    text = description,
                    style = AxTheme.typography.label,
                    color = AxTheme.colors.textMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        control()
    }
}

/** Tone of an [InfoBanner]. */
internal enum class BannerTone { INFO, WARNING, ERROR }

/** A one- or two-line notice with an icon and an optional action button. */
@Composable
internal fun InfoBanner(
    text: String,
    modifier: Modifier = Modifier,
    tone: BannerTone = BannerTone.INFO,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = AxTheme.colors
    val accent = when (tone) {
        BannerTone.INFO -> colors.accent
        BannerTone.WARNING -> colors.warning
        BannerTone.ERROR -> colors.error
    }
    val icon: ImageVector = when (tone) {
        BannerTone.INFO -> AxIcons.Info
        BannerTone.WARNING -> AxIcons.Warning
        BannerTone.ERROR -> AxIcons.Error
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                val r = CornerRadius(10.dp.toPx())
                drawRoundRect(accent.copy(alpha = 0.12f), cornerRadius = r)
                val b = 1.dp.toPx()
                drawRoundRect(
                    color = accent.copy(alpha = 0.55f),
                    topLeft = Offset(b / 2f, b / 2f),
                    size = Size(size.width - b, size.height - b),
                    cornerRadius = r,
                    style = Stroke(b),
                )
            }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AxIcon(icon = icon, contentDescription = null, tint = accent, size = 20.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = AxTheme.typography.label,
            color = colors.text,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.width(8.dp))
            AxButton(text = actionLabel, onClick = onAction, variant = AxButtonVariant.Text, height = 36.dp)
        }
    }
}

/** A small pill with a state colour, e.g. "ON" / "OFF" of a logical switch. */
@Composable
internal fun StatePill(text: String, active: Boolean, modifier: Modifier = Modifier) {
    val colors = AxTheme.colors
    val fill = if (active) colors.teal else colors.track
    Box(
        modifier = modifier
            .height(22.dp)
            .background(fill, RoundedCornerShape(11.dp))
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = AxTheme.typography.label,
            color = if (active) Color.White else colors.textMuted,
            maxLines = 1,
        )
    }
}

/** A round indicator dot: lit teal while [active] reads true. Read inside the lambda to keep recomposition local. */
@Composable
internal fun LiveDot(active: () -> Boolean, modifier: Modifier = Modifier, size: Dp = 12.dp) {
    val colors = AxTheme.colors
    Box(
        modifier = modifier
            .width(size)
            .height(size)
            .drawBehind {
                val on = active()
                drawCircle(if (on) colors.teal else colors.track)
                if (on) drawCircle(colors.teal.copy(alpha = 0.25f), radius = this.size.minDimension * 0.9f)
            },
    )
}

/** Field showing an input source; tapping opens a grouped picker of [options]. */
@Composable
internal fun SourceField(
    selected: SourceId?,
    options: List<SourceOption>,
    onSelected: (SourceId) -> Unit,
    modifier: Modifier = Modifier,
    title: String = stringResource(R.string.fn_pick_source),
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val nameOf = options.firstOrNull { it.id == selected }?.name ?: selected?.defaultName ?: "—"
    AxValueBox(text = nameOf, onClick = { open = true }, trailingIcon = AxIcons.ExpandMore, modifier = modifier.width(SourceWidth))
    if (open) {
        val groups = SourceGroups.labels()
        val pickerOptions = options.map { option ->
            PickerOption(
                value = option.id,
                label = option.name,
                group = groups[SourceGroups.of(option.id)],
                description = option.id.defaultName.takeIf { it != option.name },
            )
        }
        PickerDialog(
            title = title,
            options = pickerOptions,
            selected = selected,
            onSelect = {
                open = false
                onSelected(it)
            },
            onDismiss = { open = false },
            columns = 4,
            searchable = false,
        )
    }
}

/** Field showing a sensor; tapping opens a searchable, grouped picker. */
@Composable
internal fun SensorField(
    selected: SensorId?,
    data: PickerData,
    onSelected: (SensorId) -> Unit,
    modifier: Modifier = Modifier,
    title: String = stringResource(R.string.fn_pick_sensor),
    width: Dp = 190.dp,
    filter: (SensorOption) -> Boolean = { true },
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val name = selected?.let { data.sensorName(it) } ?: "—"
    AxValueBox(text = name, onClick = { open = true }, trailingIcon = AxIcons.ExpandMore, modifier = modifier.width(width))
    if (open) {
        val labels = SensorGroups.labels()
        val options = data.sensors.filter(filter).map { sensor ->
            PickerOption(
                value = sensor.id,
                label = sensor.name,
                group = labels[sensor.group],
                description = sensor.unit.symbol.takeIf { it.isNotEmpty() },
            )
        }
        PickerDialog(
            title = title,
            options = options,
            selected = selected,
            onSelect = {
                open = false
                onSelected(it)
            },
            onDismiss = { open = false },
            columns = 3,
            searchable = options.size > 18,
        )
    }
}

/** Labels of the source groups (resolved once per composition). */
internal object SourceGroups {
    enum class Kind { STICK, POT, SLIDER, SWITCH, COUNTER }

    fun of(id: SourceId): Kind = when {
        id.isStick -> Kind.STICK
        id.isPot -> Kind.POT
        id.isSlider -> Kind.SLIDER
        id.isSwitch -> Kind.SWITCH
        else -> Kind.COUNTER
    }

    @Composable
    fun labels(): Map<Kind, String> = mapOf(
        Kind.STICK to stringResource(R.string.fn_group_sticks),
        Kind.POT to stringResource(R.string.fn_group_knobs),
        Kind.SLIDER to stringResource(R.string.fn_group_sliders),
        Kind.SWITCH to stringResource(R.string.fn_group_switches),
        Kind.COUNTER to stringResource(R.string.fn_group_counters),
    )
}

/** Labels of the sensor groups. */
internal object SensorGroups {
    @Composable
    fun labels(): Map<SensorGroup, String> = mapOf(
        SensorGroup.INPUT to stringResource(R.string.fn_group_inputs),
        SensorGroup.CHANNEL to stringResource(R.string.fn_group_channels),
        SensorGroup.TIMER to stringResource(R.string.fn_group_timers),
        SensorGroup.TELEMETRY to stringResource(R.string.fn_group_telemetry),
        SensorGroup.TRANSMITTER to stringResource(R.string.fn_group_transmitter),
        SensorGroup.LOGICAL to stringResource(R.string.fn_group_logical),
    )
}

/** Dropdown over the 16 output channels; [allowNone] adds a "—" entry that reports null. */
@Composable
internal fun ChannelField(
    selected: Int?,
    onSelected: (Int?) -> Unit,
    modifier: Modifier = Modifier,
    allowNone: Boolean = true,
    width: Dp = 104.dp,
) {
    val none = stringResource(R.string.fn_none)
    val labels = remember(allowNone, none) {
        buildList {
            if (allowNone) add(none)
            for (channel in 0 until org.openax12.core.model.CHANNEL_COUNT) add("CH${channel + 1}")
        }
    }
    val offset = if (allowNone) 1 else 0
    val index = if (selected == null) 0 else selected + offset
    AxDropdown(
        options = labels,
        selectedIndex = index,
        onSelected = { picked ->
            val channel = picked - offset
            onSelected(if (channel < 0) null else channel)
        },
        title = stringResource(R.string.fn_pick_channel),
        columns = 4,
        modifier = modifier.width(width),
    )
}

/** Dropdown over any list of labels with the standard width. */
@Composable
internal fun LabelDropdown(
    labels: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = DropdownWidth,
    title: String? = null,
    enabled: Boolean = true,
) {
    AxDropdown(
        options = labels,
        selectedIndex = selectedIndex,
        onSelected = onSelected,
        title = title,
        enabled = enabled,
        modifier = modifier.width(width),
    )
}

/** A wrapping row of controls: used where several fields do not fit side by side. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ControlFlow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

/** A small caption above or beside a control. */
@Composable
internal fun Caption(text: String, modifier: Modifier = Modifier, maxLines: Int = 3) {
    Text(
        text = text,
        style = AxTheme.typography.label,
        color = AxTheme.colors.textMuted,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** A selectable row of chips (used for the template placeholders). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChipRow(items: List<String>, onClick: (String) -> Unit, modifier: Modifier = Modifier) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items.forEach { AxChip(text = it, selected = false, onClick = { onClick(it) }) }
    }
}

/** Fills the remaining height of a Row with a thin vertical divider. */
@Composable
internal fun VerticalRule(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxHeight().width(1.dp).background(AxTheme.colors.outline))
}
