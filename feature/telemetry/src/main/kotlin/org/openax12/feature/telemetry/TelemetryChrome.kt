package org.openax12.feature.telemetry

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.openax12.core.designsystem.AxDimens
import org.openax12.core.designsystem.AxIcon
import org.openax12.core.designsystem.AxIconButton
import org.openax12.core.designsystem.AxIcons
import org.openax12.core.designsystem.AxTheme
import org.openax12.core.designsystem.PickerDialog
import org.openax12.core.designsystem.PickerOption
import org.openax12.core.model.SensorId
import org.openax12.core.model.SensorInfo
import org.openax12.core.model.SensorOrigin

/**
 * Navy bar at the top of every telemetry screen, in the style of the model-setup chrome: an
 * optional back arrow, the [title] and [actions] at the end. The app shell draws the status and
 * bottom bars around the screens, so this is the only chrome the module adds.
 */
@Composable
internal fun TelemetryTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = AxTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(AxDimens.SetupBarHeight)
            .background(colors.chrome),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            AxIconButton(
                icon = AxIcons.Back,
                contentDescription = stringResource(R.string.telemetry_back),
                onClick = onBack,
                tint = colors.onChrome,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        Text(
            text = title,
            style = AxTheme.typography.chrome,
            color = colors.onChrome,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = if (onBack != null) 4.dp else 16.dp),
        )
        Row(
            modifier = Modifier.padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            content = actions,
        )
    }
}

/**
 * Pill-shaped button for the navy bar (outlined, or filled with the light chrome colour).
 *
 * @param filled the primary action of the bar
 */
@Composable
internal fun ChromeButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    filled: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = AxTheme.colors
    val shape = RoundedCornerShape(50)
    val contentColor = if (filled) colors.onChromeSelected else colors.onChrome
    Row(
        modifier = modifier
            .heightIn(min = AxDimens.MinTouch)
            .padding(vertical = 5.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .then(
                if (filled) Modifier.background(colors.chromeSelected, shape) else Modifier.border(1.5.dp, colors.onChromeMuted, shape),
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) AxIcon(icon = icon, contentDescription = null, tint = contentColor, size = 18.dp)
        Text(text = text, style = AxTheme.typography.button, color = contentColor, maxLines = 1)
    }
}

/** Coarse grouping of sensors for the picker chips and the sensor list filter. */
internal enum class SensorCategory { RECEIVER, TRANSMITTER, CONTROLS, LOGIC }

/** The category of a sensor, from its id namespace (falling back to its [origin]). */
internal fun sensorCategory(id: SensorId, origin: SensorOrigin = SensorOrigin.CRSF): SensorCategory = when (id.namespace) {
    SensorId.NS_CRSF, SensorId.NS_SDK -> SensorCategory.RECEIVER
    SensorId.NS_TX, "fm" -> SensorCategory.TRANSMITTER
    SensorId.NS_INPUT, SensorId.NS_CHANNEL -> SensorCategory.CONTROLS
    SensorId.NS_TIMER, SensorId.NS_COUNTER, SensorId.NS_LOGICAL_SWITCH -> SensorCategory.LOGIC
    else -> if (origin == SensorOrigin.INTERNAL) SensorCategory.TRANSMITTER else SensorCategory.RECEIVER
}

@Composable
internal fun sensorCategoryLabel(category: SensorCategory): String = stringResource(
    when (category) {
        SensorCategory.RECEIVER -> R.string.telemetry_category_receiver
        SensorCategory.TRANSMITTER -> R.string.telemetry_category_transmitter
        SensorCategory.CONTROLS -> R.string.telemetry_category_controls
        SensorCategory.LOGIC -> R.string.telemetry_category_logic
    },
)

/** Display name of [id]: the registry name, or the raw key for a sensor the registry does not know (yet). */
internal fun sensorLabel(id: SensorId?, sensors: List<SensorInfo>): String {
    if (id == null) return ""
    return sensors.firstOrNull { it.id == id }?.name?.takeIf { it.isNotBlank() } ?: id.key
}

/**
 * Picker for one sensor: the [suggestions] of the widget slot come first, then everything the
 * registry knows, grouped by [SensorCategory]. Hidden sensors are left out.
 *
 * @param allowNone offers an entry that unbinds the slot (reports null)
 */
@Composable
internal fun SensorPickerDialog(
    title: String,
    sensors: List<SensorInfo>,
    suggestions: List<SensorId>,
    selected: SensorId?,
    allowNone: Boolean,
    onSelect: (SensorId?) -> Unit,
    onDismiss: () -> Unit,
) {
    val suggestedLabel = stringResource(R.string.telemetry_picker_suggested)
    val noneLabel = stringResource(R.string.telemetry_picker_none)
    val categoryLabels = SensorCategory.entries.associateWith { sensorCategoryLabel(it) }
    val options = remember(sensors, suggestions, allowNone, suggestedLabel, noneLabel, categoryLabels) {
        buildList {
            if (allowNone) add(PickerOption<SensorId?>(value = null, label = noneLabel))
            val suggestedIds = suggestions.distinct()
            suggestedIds.forEach { id ->
                add(PickerOption<SensorId?>(value = id, label = sensorLabel(id, sensors), group = suggestedLabel, description = id.key, source = id.source))
            }
            sensors.filter { !it.hidden && it.id !in suggestedIds }.forEach { info ->
                add(
                    PickerOption<SensorId?>(
                        value = info.id,
                        label = info.name,
                        group = categoryLabels.getValue(sensorCategory(info.id, info.origin)),
                        description = info.id.key,
                        source = info.id.source,
                    ),
                )
            }
            // A binding to a sensor that is neither suggested nor known (renamed, not seen yet) stays visible.
            if (selected != null && none { it.value == selected }) {
                add(PickerOption<SensorId?>(value = selected, label = selected.key, description = selected.key))
            }
        }
    }
    PickerDialog(
        title = title,
        options = options,
        selected = selected,
        onSelect = onSelect,
        onDismiss = onDismiss,
        columns = 3,
        searchable = true,
        width = 640.dp,
    )
}

/** A small filled pill with a label, for status tags ("lost", "hidden", "active"). */
@Composable
internal fun StatusTag(text: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(color.copy(alpha = 0.16f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(text = text, style = AxTheme.typography.labelSmall, color = color, maxLines = 1)
    }
}
