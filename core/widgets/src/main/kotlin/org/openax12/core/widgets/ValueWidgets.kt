package org.openax12.core.widgets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.openax12.core.model.Sensors
import org.openax12.core.model.WidgetConfig
import org.openax12.core.model.WidgetOptions
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** VALUE and VALUE_MIN_MAX: a big number with its unit, optionally the extremes underneath. */
@Composable
internal fun ValueWidget(config: WidgetConfig, source: WidgetDataSource, showMinMax: Boolean, modifier: Modifier = Modifier) {
    val binding = rememberSensor(source, config.sensor)
    val gauge = rememberGauge(config, binding)
    val chrome = rememberChrome(config, source, config.sensor)
    WidgetCanvas(chrome, modifier) {
        val g = gauge.value
        val reading = g.reading
        val body = header(reading.status)
        val valueColor = palette.tone(if (g.warn) palette.warn else palette.text, reading.status)
        val unitColor = palette.tone(palette.textDim, reading.status)
        if (!showMinMax) {
            valueText(reading.number, reading.unit, body, valueColor, unitColor, body.height * 0.74f, tabular = !reading.isText)
            return@WidgetCanvas
        }
        val rowHeight = (body.height * 0.3f).coerceIn(labelPx * 1.2f, 22.dp.toPx())
        val valueBox = Rect(body.left, body.top, body.right, body.bottom - rowHeight)
        valueText(reading.number, reading.unit, valueBox, valueColor, unitColor, valueBox.height * 0.82f, tabular = !reading.isText)

        // Extremes: "v min" at the start, "^ max" at the end.
        val row = Rect(body.left, body.bottom - rowHeight, body.right, body.bottom)
        val fontPx = min(row.height * 0.72f, 15.dp.toPx())
        val mark = fontPx * 0.62f
        val half = row.width / 2f
        val numberColor = palette.tone(palette.text.copy(alpha = 0.86f), reading.status)
        triangle(Offset(row.left + mark / 2f, row.center.y), mark, up = false, color = palette.tone(accent, reading.status))
        text(
            reading.minText,
            Rect(row.left + mark * 1.5f, row.top, row.left + half - mark * 0.3f, row.bottom),
            numberColor, fontPx, FontWeight.SemiBold, alignX = 0f, tabular = true,
        )
        val maxWidth = measure(reading.maxText, fontPx, FontWeight.SemiBold, tabular = true).size.width
            .coerceAtMost((half - mark * 1.8f).toInt().coerceAtLeast(1))
        text(
            reading.maxText,
            Rect(row.right - maxWidth, row.top, row.right, row.bottom),
            numberColor, fontPx, FontWeight.SemiBold, alignX = 1f, tabular = true,
        )
        triangle(Offset(row.right - maxWidth - mark, row.center.y), mark, up = true, color = palette.tone(accent, reading.status))
    }
}

/** BAR: the value over a horizontal bar on the configured scale. */
@Composable
internal fun BarWidget(config: WidgetConfig, source: WidgetDataSource, modifier: Modifier = Modifier) {
    val binding = rememberSensor(source, config.sensor)
    val gauge = rememberGauge(config, binding)
    val chrome = rememberChrome(config, source, config.sensor)
    WidgetCanvas(chrome, modifier) {
        val g = gauge.value
        val reading = g.reading
        val scale = g.scale
        val body = header(reading.status)
        val barHeight = (body.height * 0.2f).coerceIn(5.dp.toPx(), 16.dp.toPx())
        val showScale = body.height >= 56.dp.toPx()
        val scaleHeight = if (showScale) labelPx * 1.25f else 0f
        val gap = barHeight * 0.45f
        val valueBox = Rect(body.left, body.top, body.right, body.bottom - barHeight - scaleHeight - gap)
        val fillColor = palette.tone(if (g.warn) palette.warn else accent, reading.status)
        valueText(
            reading.number, reading.unit, valueBox,
            palette.tone(if (g.warn) palette.warn else palette.text, reading.status),
            palette.tone(palette.textDim, reading.status),
            valueBox.height * 0.84f, tabular = !reading.isText,
        )
        val track = Rect(body.left, valueBox.bottom + gap, body.right, valueBox.bottom + gap + barHeight)
        val origin = scale.originFraction
        if (reading.hasValue) bar(track, origin, scale.fraction(reading.value), fillColor) else bar(track, 0f, 0f, fillColor)
        // Zero mark of a scale that crosses zero and the warning thresholds.
        if (scale.isBipolar) {
            val x = track.left + track.width * origin
            drawLine(palette.textDim, Offset(x, track.top - 2.dp.toPx()), Offset(x, track.bottom + 2.dp.toPx()), 1.5.dp.toPx())
        }
        for (threshold in doubleArrayOf(scale.warnBelow, scale.warnAbove)) {
            if (threshold.isNaN() || threshold <= scale.min || threshold >= scale.max) continue
            val x = track.left + track.width * scale.fraction(threshold)
            drawLine(palette.warn, Offset(x, track.top), Offset(x, track.bottom), 2.dp.toPx())
        }
        if (showScale) {
            val row = Rect(body.left, track.bottom, body.right, body.bottom)
            val half = row.width / 2f
            text(WidgetFormat.scaleLabel(scale.min), Rect(row.left, row.top, row.left + half, row.bottom), palette.textDim, labelPx, alignX = 0f, tabular = true)
            text(WidgetFormat.scaleLabel(scale.max), Rect(row.left + half, row.top, row.right, row.bottom), palette.textDim, labelPx, alignX = 1f, tabular = true)
        }
    }
}

/** ARC_GAUGE: a 240 degree arc with the value in its centre. */
@Composable
internal fun ArcGaugeWidget(config: WidgetConfig, source: WidgetDataSource, modifier: Modifier = Modifier) {
    val binding = rememberSensor(source, config.sensor)
    val gauge = rememberGauge(config, binding)
    val chrome = rememberChrome(config, source, config.sensor)
    WidgetCanvas(chrome, modifier) {
        val g = gauge.value
        val reading = g.reading
        val scale = g.scale
        val body = header(reading.status)

        // The arc spans 240 degrees: its bounding box is 2r wide and about 1.5r high.
        // The scale ends are labelled under the arc ends once there is room for them.
        val showScale = min(body.width / 2f, body.height / ARC_HEIGHT_FACTOR) >= 46.dp.toPx()
        val scaleRoom = if (showScale) labelPx * 0.9f else 0f
        val outer = min(body.width / 2f, (body.height - scaleRoom) / ARC_HEIGHT_FACTOR)
        if (outer < 4.dp.toPx()) return@WidgetCanvas
        val stroke = outer * 0.17f
        val radius = outer - stroke / 2f
        val cx = body.center.x
        val cy = body.top + (body.height - scaleRoom - outer * ARC_HEIGHT_FACTOR) / 2f + outer
        val arcTopLeft = Offset(cx - radius, cy - radius)
        val arcSize = Size(radius * 2f, radius * 2f)
        val fillColor = palette.tone(if (g.warn) palette.warn else accent, reading.status)

        drawArc(palette.track, ARC_START, ARC_SWEEP, false, arcTopLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        // Warning zones as a thin line along the inside of the arc.
        val zoneRadius = radius - stroke * 0.78f
        val zoneStroke = Stroke(stroke * 0.16f, cap = StrokeCap.Round)
        if (!scale.warnBelow.isNaN() && scale.warnBelow > scale.min) {
            drawArc(
                palette.warn, ARC_START, ARC_SWEEP * scale.fraction(scale.warnBelow), false,
                Offset(cx - zoneRadius, cy - zoneRadius), Size(zoneRadius * 2f, zoneRadius * 2f), style = zoneStroke,
            )
        }
        if (!scale.warnAbove.isNaN() && scale.warnAbove < scale.max) {
            val from = scale.fraction(scale.warnAbove)
            drawArc(
                palette.warn, ARC_START + ARC_SWEEP * from, ARC_SWEEP * (1f - from), false,
                Offset(cx - zoneRadius, cy - zoneRadius), Size(zoneRadius * 2f, zoneRadius * 2f), style = zoneStroke,
            )
        }
        if (reading.hasValue) {
            val origin = scale.originFraction
            val fraction = scale.fraction(reading.value)
            val from = min(origin, fraction)
            val sweep = kotlin.math.abs(fraction - origin) * ARC_SWEEP
            if (sweep > 0.5f) {
                drawArc(fillColor, ARC_START + ARC_SWEEP * from, sweep, false, arcTopLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            // Knob at the value.
            val angle = Math.toRadians((ARC_START + ARC_SWEEP * fraction).toDouble())
            val knob = Offset(cx + radius * cos(angle).toFloat(), cy + radius * sin(angle).toFloat())
            drawCircle(fillColor, stroke * 0.5f, knob)
            drawCircle(palette.card, stroke * 0.27f, knob)
        }

        val valueColor = palette.tone(if (g.warn) palette.warn else palette.text, reading.status)
        val inside = radius - stroke * 0.9f
        text(
            reading.number,
            Rect(cx - inside * 0.86f, cy - inside * 0.5f, cx + inside * 0.86f, cy + inside * 0.26f),
            valueColor, inside * 0.7f, FontWeight.Bold, alignX = 0.5f, tabular = !reading.isText, minChars = 3,
        )
        if (reading.unit.isNotEmpty()) {
            text(
                reading.unit,
                Rect(cx - inside * 0.6f, cy + inside * 0.3f, cx + inside * 0.6f, cy + inside * 0.72f),
                palette.tone(palette.textDim, reading.status), inside * 0.3f, FontWeight.Medium, alignX = 0.5f,
            )
        }
        if (showScale) {
            val fontPx = labelPx
            val endX = radius * 0.866f
            val top = cy + radius * 0.5f + stroke * 0.6f
            val w = outer * 0.7f
            text(WidgetFormat.scaleLabel(scale.min), Rect(cx - endX - w / 2f, top, cx - endX + w / 2f, top + fontPx * 1.25f), palette.textDim, fontPx, alignX = 0.5f, tabular = true)
            text(WidgetFormat.scaleLabel(scale.max), Rect(cx + endX - w / 2f, top, cx + endX + w / 2f, top + fontPx * 1.25f), palette.textDim, fontPx, alignX = 0.5f, tabular = true)
        }
    }
}

/** VARIO: vertical speed as a bar around zero (climb up, sink down) next to the signed value. */
@Composable
internal fun VarioWidget(config: WidgetConfig, source: WidgetDataSource, modifier: Modifier = Modifier) {
    val sensor = config.sensor ?: Sensors.VERTICAL_SPEED
    val binding = rememberSensor(source, sensor)
    val gauge = remember(config, binding) {
        derivedStateOf {
            val reading = binding.reading.value
            val resolved = Scale.resolve(config, binding.id, binding.raw.value, reading, binding.units.value)
            // Without an explicit scale a vario shows +-5 m/s.
            val span = Readings.displayOption(DEFAULT_VARIO_RANGE, binding.raw.value, binding.units.value)
            val scale = resolved.copy(
                min = if (config.optionDouble(WidgetOptions.MIN) == null) -span else resolved.min,
                max = if (config.optionDouble(WidgetOptions.MAX) == null) span else resolved.max,
            )
            Gauge(reading, if (scale.max > scale.min) scale else resolved)
        }
    }
    val chrome = rememberChrome(config, source, sensor, stringResource(R.string.widget_title_vario))
    WidgetCanvas(chrome, modifier) {
        val g = gauge.value
        val reading = g.reading
        val scale = g.scale
        val body = header(reading.status)
        val barWidth = (body.width * 0.12f).coerceIn(7.dp.toPx(), 20.dp.toPx())
        val track = Rect(body.left, body.top, body.left + barWidth, body.bottom)
        val origin = scale.originFraction
        val climbing = reading.hasValue && reading.value >= 0.0
        val fillColor = palette.tone(if (climbing) palette.good else palette.warn, reading.status)
        if (reading.hasValue) bar(track, origin, scale.fraction(reading.value), fillColor) else bar(track, 0f, 0f, fillColor)
        val zeroY = track.bottom - track.height * origin
        drawLine(palette.textDim, Offset(track.left - 1.5.dp.toPx(), zeroY), Offset(track.right + 1.5.dp.toPx(), zeroY), 1.5.dp.toPx())

        var left = track.right + barWidth * 0.5f
        if (body.height >= 70.dp.toPx() && body.width >= 110.dp.toPx()) {
            // Scale labels next to the bar.
            val fontPx = labelPx
            val labelWidth = fontPx * 2.2f
            val rowHeight = fontPx * 1.2f
            text(WidgetFormat.scaleLabel(scale.max), Rect(left, track.top, left + labelWidth, track.top + rowHeight), palette.textDim, fontPx, tabular = true)
            text(WidgetFormat.scaleLabel(scale.min), Rect(left, track.bottom - rowHeight, left + labelWidth, track.bottom), palette.textDim, fontPx, tabular = true)
            if (scale.isBipolar) {
                text("0", Rect(left, zeroY - rowHeight / 2f, left + labelWidth, zeroY + rowHeight / 2f), palette.textDim, fontPx, tabular = true)
            }
            left += labelWidth
        }
        val valueBox = Rect(left, body.top, body.right, body.bottom)
        valueText(
            WidgetFormat.signed(reading), reading.unit, valueBox,
            palette.tone(if (g.warn) palette.warn else palette.text, reading.status),
            palette.tone(palette.textDim, reading.status),
            min(body.height * 0.62f, valueBox.width * 0.5f), minChars = 4, tabular = !reading.isText,
        )
        // A notch on the bar's rounded track end looks cleaner than a clipped fill at the limits.
        drawRoundRect(palette.outline, track.topLeft, track.size, CornerRadius(barWidth / 2f), style = Stroke(1.dp.toPx()))
    }
}

private const val ARC_START = 150f
private const val ARC_SWEEP = 240f
private const val ARC_HEIGHT_FACTOR = 1.54f
private const val DEFAULT_VARIO_RANGE = 5.0
