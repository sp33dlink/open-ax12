package org.openax12.core.widgets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.openax12.core.model.SensorUnit
import org.openax12.core.model.Sensors
import org.openax12.core.model.UnitConversion
import org.openax12.core.model.WidgetConfig
import org.openax12.core.model.WidgetOptions
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * BATTERY: a battery symbol divided into the pack's cells, the pack voltage, cell count and
 * voltage per cell, and (second / third sensor) current and used capacity.
 */
@Composable
internal fun BatteryWidget(config: WidgetConfig, source: WidgetDataSource, modifier: Modifier = Modifier) {
    val voltageId = config.sensors.getOrNull(0) ?: Sensors.BATTERY_VOLTAGE
    val voltage = rememberSensor(source, voltageId)
    val current = rememberSensor(source, config.sensors.getOrNull(1))
    val capacity = rememberSensor(source, config.sensors.getOrNull(2))
    val gauge = rememberGauge(config, voltage)
    val chrome = rememberChrome(config, source, voltageId, stringResource(R.string.widget_title_battery))
    val cellsPattern = stringResource(R.string.widget_battery_cells)
    val percentPattern = stringResource(R.string.widget_battery_percent)
    val configuredCells = config.optionInt(WidgetOptions.CELLS) ?: 0
    WidgetCanvas(chrome, modifier) {
        val g = gauge.value
        val reading = g.reading
        val raw = voltage.raw.value
        val body = header(reading.status)

        // Charge state: straight from a percent sensor, otherwise from the voltage per cell.
        val isPercent = raw?.unit == SensorUnit.PERCENT
        val volts = raw?.value ?: Double.NaN
        val cells = if (isPercent || raw == null) 0 else BatteryMath.cellCount(configuredCells, volts, raw.max)
        val cellVolts = if (cells > 0) volts / cells else Double.NaN
        val percent = when {
            !reading.hasValue -> -1
            isPercent -> volts.roundToInt().coerceIn(0, 100)
            else -> BatteryMath.percent(cellVolts)
        }
        val levelColor = palette.tone(
            when {
                percent < 0 -> palette.textDim
                g.warn || percent <= 15 -> palette.alarm
                percent <= 30 -> palette.warn
                else -> palette.good
            },
            reading.status,
        )
        val valueColor = palette.tone(if (g.warn) palette.alarm else palette.text, reading.status)
        val dim = palette.tone(palette.textDim, reading.status)

        val cellLine = when {
            cells > 0 && percent >= 0 ->
                String.format(Locale.ROOT, cellsPattern, cells, UnitConversion.formatNumber(cellVolts, 2)) +
                    "  " + String.format(Locale.ROOT, percentPattern, percent)
            cells > 0 -> String.format(Locale.ROOT, cellsPattern, cells, UnitConversion.formatNumber(cellVolts, 2))
            else -> ""
        }
        val extras = buildString {
            for (extra in arrayOf(current, capacity)) {
                val r = extra.reading.value
                if (extra.id == null || !r.hasContent) continue
                if (isNotEmpty()) append("   ")
                append(r.number)
                if (r.unit.isNotEmpty()) append(' ').append(r.unit)
            }
        }

        val wide = body.width >= body.height * 1.7f
        val symbol: Rect
        val textBox: Rect
        if (wide) {
            val symbolWidth = min(body.width * 0.4f, body.height * 1.7f)
            val symbolHeight = min(symbolWidth * 0.52f, body.height * 0.86f)
            symbol = Rect(body.left, body.center.y - symbolHeight / 2f, body.left + symbolWidth, body.center.y + symbolHeight / 2f)
            textBox = Rect(symbol.right + pad, body.top, body.right, body.bottom)
        } else {
            val symbolHeight = body.height * 0.32f
            val symbolWidth = min(body.width * 0.82f, symbolHeight * 2.4f)
            symbol = Rect(body.center.x - symbolWidth / 2f, body.top, body.center.x + symbolWidth / 2f, body.top + symbolHeight)
            textBox = Rect(body.left, symbol.bottom + body.height * 0.04f, body.right, body.bottom)
        }
        batterySymbol(symbol, if (cells in 1..MAX_DRAWN_CELLS) cells else 1, percent, levelColor, palette.tone(palette.textDim, reading.status))

        // Text block: voltage, then the cell line, then current / capacity when there is room.
        val alignX = if (wide) 0f else 0.5f
        val lineCount = 1 + (if (cellLine.isNotEmpty()) 1 else 0) + (if (extras.isNotEmpty()) 1 else 0)
        val subPx = min(labelPx * 1.05f, textBox.height * 0.2f).coerceAtLeast(WidgetScope.MIN_FONT_DP.dp.toPx())
        val subRow = subPx * 1.35f
        val maxSubLines = ((textBox.height * 0.56f) / subRow).toInt().coerceIn(0, 2)
        val subLines = min(lineCount - 1, maxSubLines)
        val mainBox = Rect(textBox.left, textBox.top, textBox.right, textBox.bottom - subLines * subRow)
        valueText(reading.number, reading.unit, mainBox, valueColor, dim, mainBox.height * 0.8f, alignX = alignX, tabular = !reading.isText)
        var top = mainBox.bottom
        val lines = listOf(cellLine, extras).filter { it.isNotEmpty() }.take(subLines)
        for (line in lines) {
            text(line, Rect(textBox.left, top, textBox.right, top + subRow), dim, subPx, FontWeight.Medium, alignX = alignX, tabular = true)
            top += subRow
        }
    }
}

/** A horizontal battery: [cells] compartments, each filled from the bottom to [percent]. */
private fun WidgetScope.batterySymbol(rect: Rect, cells: Int, percent: Int, fill: Color, frame: Color) {
    if (rect.width < 8f || rect.height < 6f) return
    val nubWidth = rect.width * 0.045f
    val shell = Rect(rect.left, rect.top, rect.right - nubWidth, rect.bottom)
    val stroke = (rect.height * 0.07f).coerceIn(1.dp.toPx(), 2.5.dp.toPx())
    val corner = CornerRadius(rect.height * 0.2f)
    drawRoundRect(frame, shell.topLeft, shell.size, corner, style = Stroke(stroke))
    drawRoundRect(
        frame,
        Offset(shell.right + stroke * 0.2f, rect.center.y - rect.height * 0.2f),
        Size(nubWidth, rect.height * 0.4f),
        CornerRadius(nubWidth * 0.4f),
    )
    val inset = stroke * 1.9f
    val innerRect = shell.deflate(inset)
    val gap = stroke * 1.2f
    val cellWidth = (innerRect.width - gap * (cells - 1)) / cells
    if (cellWidth <= 1f || innerRect.height <= 1f) return
    val cellCorner = CornerRadius(min(rect.height * 0.1f, cellWidth * 0.3f))
    val level = (percent.coerceIn(0, 100)) / 100f
    for (i in 0 until cells) {
        val left = innerRect.left + i * (cellWidth + gap)
        val cell = Rect(left, innerRect.top, left + cellWidth, innerRect.bottom)
        drawRoundRect(palette.track, cell.topLeft, cell.size, cellCorner)
        if (percent > 0) {
            val outline = Path().apply { addRoundRect(RoundRect(cell, cellCorner)) }
            clipPath(outline) {
                if (cells == 1) {
                    drawRect(fill, cell.topLeft, Size(cell.width * level, cell.height))
                } else {
                    drawRect(fill, Offset(cell.left, cell.bottom - cell.height * level), Size(cell.width, cell.height * level))
                }
            }
        }
    }
}

/** LINK_QUALITY: signal bars, link quality in percent and (second sensor) the signal strength. */
@Composable
internal fun LinkQualityWidget(config: WidgetConfig, source: WidgetDataSource, modifier: Modifier = Modifier) {
    val qualityId = config.sensors.getOrNull(0) ?: Sensors.TX_LINK_QUALITY
    val signalId = config.sensors.getOrNull(1) ?: if (config.sensors.isEmpty()) Sensors.TX_RSSI else null
    val quality = rememberSensor(source, qualityId)
    val signal = rememberSensor(source, signalId)
    val gauge = rememberGauge(config, quality)
    val chrome = rememberChrome(config, source, null, stringResource(R.string.widget_title_link))
    WidgetCanvas(chrome, modifier) {
        val g = gauge.value
        val reading = g.reading
        val body = header(reading.status)
        val percent = if (reading.hasValue) reading.value else Double.NaN
        val lit = when {
            percent.isNaN() || reading.status == ReadingStatus.LOST -> 0
            percent >= 90 -> 5
            percent >= 70 -> 4
            percent >= 50 -> 3
            percent >= 30 -> 2
            percent > 0 -> 1
            else -> 0
        }
        val levelColor = palette.tone(
            when {
                g.warn || lit <= 2 -> palette.alarm
                lit == 3 -> palette.warn
                else -> palette.good
            },
            reading.status,
        )

        // Bars at the start.
        val barsWidth = min(body.width * 0.38f, body.height * 1.15f)
        val barsHeight = min(body.height, barsWidth * 0.9f)
        val barsTop = body.center.y - barsHeight / 2f
        val gap = barsWidth * 0.07f
        val barWidth = (barsWidth - gap * (SIGNAL_BARS - 1)) / SIGNAL_BARS
        val corner = CornerRadius(barWidth * 0.3f)
        for (i in 0 until SIGNAL_BARS) {
            val height = barsHeight * (0.28f + 0.72f * i / (SIGNAL_BARS - 1))
            val left = body.left + i * (barWidth + gap)
            drawRoundRect(
                if (i < lit) levelColor else palette.track,
                Offset(left, barsTop + barsHeight - height),
                Size(barWidth, height),
                corner,
            )
        }

        val textBox = Rect(body.left + barsWidth + pad, body.top, body.right, body.bottom)
        val signalReading = signal.reading.value
        val showSignal = signal.id != null && signalReading.hasContent && body.height >= 40.dp.toPx()
        val subPx = min(labelPx * 1.05f, body.height * 0.22f)
        val subRow = if (showSignal) subPx * 1.35f else 0f
        val mainBox = Rect(textBox.left, textBox.top, textBox.right, textBox.bottom - subRow)
        valueText(
            reading.number, reading.unit, mainBox,
            palette.tone(if (g.warn) palette.alarm else palette.text, reading.status),
            palette.tone(palette.textDim, reading.status),
            mainBox.height * 0.8f, alignX = 0f, minChars = 3, tabular = !reading.isText,
        )
        if (showSignal) {
            val line = if (signalReading.unit.isEmpty()) signalReading.number else signalReading.number + " " + signalReading.unit
            text(
                line, Rect(textBox.left, mainBox.bottom, textBox.right, textBox.bottom),
                palette.tone(palette.textDim, signalReading.status), subPx, FontWeight.Medium, tabular = true,
            )
        }
    }
}

/** TIMER: one firmware timer or counter with a running marker and, for a countdown, the remaining share. */
@Composable
internal fun TimerWidget(config: WidgetConfig, source: WidgetDataSource, modifier: Modifier = Modifier) {
    val index = config.optionInt(WidgetOptions.TIMER) ?: 0
    val counter = config.optionBoolean(WidgetOptions.COUNTER)
    val state = remember(source, index, counter) { source.timer(index, counter) }.collectAsWidgetState()
    val fallback = stringResource(if (counter) R.string.widget_title_counter else R.string.widget_title_timer, index + 1)
    val chrome = rememberChrome(config, source, null, fallback)
    WidgetCanvas(chrome, modifier) {
        val timer = state.value
        val markerSize = titlePx * 0.72f
        val showMarker = chrome.title != null && timer != null && !counter
        val body = header(reserveEnd = if (showMarker) markerSize * 1.4f else 0f)
        if (timer == null) {
            text(UnitConversion.NO_VALUE, body, palette.tone(palette.text, ReadingStatus.MISSING), body.height * 0.6f, FontWeight.Bold, alignX = 0.5f)
            return@WidgetCanvas
        }
        if (showMarker) {
            // Running: a filled dot in the "good" colour. Stopped: two pause bars.
            val cx = size.width - pad - markerSize / 2f
            val cy = pad + titlePx * 0.65f
            if (timer.running) {
                drawCircle(palette.good.copy(alpha = 0.25f), markerSize * 0.72f, Offset(cx, cy))
                drawCircle(palette.good, markerSize * 0.42f, Offset(cx, cy))
            } else {
                val barWidth = markerSize * 0.26f
                val barHeight = markerSize * 0.9f
                val corner = CornerRadius(barWidth * 0.4f)
                drawRoundRect(palette.textDim, Offset(cx - barWidth * 1.4f, cy - barHeight / 2f), Size(barWidth, barHeight), corner)
                drawRoundRect(palette.textDim, Offset(cx + barWidth * 0.4f, cy - barHeight / 2f), Size(barWidth, barHeight), corner)
            }
        }

        // Countdown (or counter with a maximum): the share that is left.
        val hasLimit = timer.startTime > 0
        val remaining = if (timer.showsElapsed) timer.startTime - timer.count else timer.count
        val expired = hasLimit && !counter && remaining <= 0
        val barHeight = if (hasLimit) (body.height * 0.1f).coerceIn(3.dp.toPx(), 7.dp.toPx()) else 0f
        val valueBox = Rect(body.left, body.top, body.right, body.bottom - barHeight * 1.8f)
        val label = if (counter) timer.count.toString() else UnitConversion.formatTime(timer.count)
        val valueColor = when {
            expired -> palette.alarm
            counter || timer.running -> palette.text
            else -> palette.text.copy(alpha = 0.72f)
        }
        text(label, valueBox, valueColor, valueBox.height * 0.78f, FontWeight.Bold, alignX = 0.5f, tabular = true, minChars = 4)
        if (hasLimit) {
            val fraction = if (counter) timer.count.toFloat() / timer.startTime else remaining.toFloat() / timer.startTime
            val color = when {
                counter -> accent
                expired || fraction <= 0.1f -> palette.alarm
                fraction <= 0.25f -> palette.warn
                else -> accent
            }
            bar(Rect(body.left, body.bottom - barHeight, body.right, body.bottom), 0f, fraction.coerceIn(0f, 1f), color)
        }
    }
}

private const val MAX_DRAWN_CELLS = 8
private const val SIGNAL_BARS = 5
