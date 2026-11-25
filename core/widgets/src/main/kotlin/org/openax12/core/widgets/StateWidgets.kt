package org.openax12.core.widgets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.openax12.core.model.SwitchPosition
import org.openax12.core.model.UnitConversion
import org.openax12.core.model.WidgetConfig
import org.openax12.core.model.WidgetOptions
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * SWITCH_STATE: a logical switch (option `logicalSwitch` or an `ls.*` sensor) as an on / off
 * lamp, any other sensor as a three-position switch (up below −50 %, down above +50 %).
 */
@Composable
internal fun SwitchStateWidget(config: WidgetConfig, source: WidgetDataSource, modifier: Modifier = Modifier) {
    val logicalIndex = config.optionInt(WidgetOptions.LOGICAL_SWITCH) ?: config.sensor?.logicalSwitchIndex
    if (logicalIndex != null) {
        LogicalSwitchWidget(config, source, logicalIndex, modifier)
    } else {
        InputSwitchWidget(config, source, modifier)
    }
}

@Composable
private fun LogicalSwitchWidget(config: WidgetConfig, source: WidgetDataSource, index: Int, modifier: Modifier) {
    val states = remember(source) { source.logicalSwitches }.collectAsWidgetState()
    val fallback = source.logicalSwitchName(index)?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.widget_title_logical_switch, index + 1)
    val chrome = rememberChrome(config, source, null, fallback)
    val onLabel = stringResource(R.string.widget_switch_on)
    val offLabel = stringResource(R.string.widget_switch_off)
    WidgetCanvas(chrome, modifier) {
        val on = states.value[index]
        val body = header()
        val radius = min(body.height * 0.4f, body.width * 0.17f)
        val center = Offset(body.left + radius * 1.25f, body.center.y)
        if (on) {
            drawCircle(accent.copy(alpha = 0.22f), radius * 1.25f, center)
            drawCircle(accent, radius * 0.82f, center)
            drawCircle(palette.card.copy(alpha = 0.55f), radius * 0.24f, Offset(center.x - radius * 0.28f, center.y - radius * 0.28f))
        } else {
            drawCircle(palette.track, radius * 0.82f, center)
            drawCircle(palette.outline, radius * 0.82f, center, style = Stroke(1.5.dp.toPx()))
        }
        val textBox = Rect(center.x + radius * 1.25f + pad * 0.6f, body.top, body.right, body.bottom)
        text(
            if (on) onLabel else offLabel, textBox,
            if (on) palette.text else palette.textDim,
            body.height * 0.62f, FontWeight.Bold, alignX = 0.5f, minChars = 3,
        )
    }
}

@Composable
private fun InputSwitchWidget(config: WidgetConfig, source: WidgetDataSource, modifier: Modifier) {
    val binding = rememberSensor(source, config.sensor)
    val chrome = rememberChrome(config, source, config.sensor)
    val upLabel = stringResource(R.string.widget_switch_up)
    val midLabel = stringResource(R.string.widget_switch_mid)
    val downLabel = stringResource(R.string.widget_switch_down)
    WidgetCanvas(chrome, modifier) {
        val reading = binding.reading.value
        val body = header(reading.status)
        val position = if (reading.hasValue) SwitchPosition.fromLogic(reading.value.roundToInt()) else null

        // The switch: a vertical track with the knob in one of three slots.
        val trackWidth = min(body.height * 0.36f, body.width * 0.2f)
        val track = Rect(body.left + trackWidth * 0.2f, body.top, body.left + trackWidth * 1.2f, body.bottom)
        drawRoundRect(palette.track, track.topLeft, track.size, CornerRadius(trackWidth / 2f))
        val knobRadius = trackWidth * 0.5f
        val travel = track.height - trackWidth
        for (slot in 0..2) {
            val y = track.top + knobRadius + travel * slot / 2f
            drawCircle(palette.textDim.copy(alpha = 0.35f), knobRadius * 0.16f, Offset(track.center.x, y))
        }
        val color = when (position) {
            SwitchPosition.UP -> palette.switchUp
            SwitchPosition.DOWN -> palette.switchDown
            SwitchPosition.MID -> palette.switchMid
            null -> palette.textDim
        }
        if (position != null) {
            val slot = when (position) {
                SwitchPosition.UP -> 0
                SwitchPosition.MID -> 1
                SwitchPosition.DOWN -> 2
            }
            val knob = Offset(track.center.x, track.top + knobRadius + travel * slot / 2f)
            val toned = palette.tone(color, reading.status)
            drawCircle(toned.copy(alpha = toned.alpha * 0.25f), knobRadius * 1.28f, knob)
            drawCircle(toned, knobRadius * 0.86f, knob)
        }
        val label = when (position) {
            SwitchPosition.UP -> upLabel
            SwitchPosition.MID -> midLabel
            SwitchPosition.DOWN -> downLabel
            null -> UnitConversion.NO_VALUE
        }
        val textBox = Rect(track.right + trackWidth * 0.45f + pad * 0.5f, body.top, body.right, body.bottom)
        text(
            label, textBox,
            palette.tone(if (position == SwitchPosition.MID) palette.text.copy(alpha = 0.8f) else palette.text, reading.status),
            body.height * 0.56f, FontWeight.Bold, alignX = 0.5f, minChars = 4,
        )
    }
}

/**
 * FLIGHT_MODE: the model's active flight mode, or with a sensor bound (the flight controller's
 * mode, a text sensor) that sensor's text.
 */
@Composable
internal fun FlightModeWidget(config: WidgetConfig, source: WidgetDataSource, modifier: Modifier = Modifier) {
    val sensor = config.sensor
    val binding = rememberSensor(source, sensor)
    val mode = remember(source) { source.flightMode }.collectAsWidgetState()
    val chrome = rememberChrome(config, source, null, stringResource(R.string.widget_title_flight_mode))
    val defaultPattern = stringResource(R.string.widget_flight_mode_default)
    WidgetCanvas(chrome, modifier) {
        val label: String
        val status: ReadingStatus
        var badge: String? = null
        if (sensor != null) {
            val reading = binding.reading.value
            label = reading.number
            status = reading.status
        } else {
            val active = mode.value
            val short = String.format(Locale.ROOT, defaultPattern, active.index + 1)
            label = active.name.ifBlank { short }
            status = ReadingStatus.OK
            if (active.name.isNotBlank()) badge = short
        }
        val body = header(status, trailing = badge, trailingColor = palette.textDim)
        if (status == ReadingStatus.MISSING) {
            text(UnitConversion.NO_VALUE, body, palette.tone(palette.text, status), body.height * 0.6f, FontWeight.Bold, alignX = 0.5f)
        } else {
            paragraph(label, body, palette.tone(accent, status), body.height * 0.62f, FontWeight.Bold, maxLines = 2)
        }
    }
}

/** TEXT: a label, optionally a template the data source resolves (`{RxBt} V`). */
@Composable
internal fun TextWidget(config: WidgetConfig, source: WidgetDataSource, modifier: Modifier = Modifier) {
    val template = config.option(WidgetOptions.TEXT).orEmpty()
    val isTemplate = template.contains('{')
    val resolved = if (isTemplate) {
        rememberPolled(source, template) { source.resolveText(template) }
    } else {
        remember(template) { androidx.compose.runtime.mutableStateOf(template) }
    }
    // A text widget has a title line only when one was typed in.
    val chrome = rememberChrome(if (config.title.isBlank()) config.withOption(WidgetOptions.SHOW_TITLE, "false") else config, source, null)
    val hasColor = config.option(WidgetOptions.COLOR) != null
    WidgetCanvas(chrome, modifier) {
        val body = header()
        val label = resolved.value
        if (label.isBlank()) return@WidgetCanvas
        val lines = (body.height / (14.dp.toPx())).toInt().coerceIn(1, 6)
        paragraph(label, body, if (hasColor) accent else palette.text, body.height * 0.5f, FontWeight.SemiBold, maxLines = lines)
    }
}
