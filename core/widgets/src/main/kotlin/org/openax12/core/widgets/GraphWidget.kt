package org.openax12.core.widgets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.openax12.core.model.SensorUnit
import org.openax12.core.model.UnitConversion
import org.openax12.core.model.UnitSystem
import org.openax12.core.model.WidgetConfig
import org.openax12.core.model.WidgetOptions
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** History of a GRAPH widget as polled from the data source. */
@Immutable
internal data class GraphData(val nowMs: Long, val samples: List<WidgetSample>)

/** Vertical range of a graph in display units. */
internal data class GraphRange(val min: Double, val max: Double) {
    companion object {
        /**
         * The range for [values]: the configured ends where given, otherwise the extremes of the
         * data with a little head-room. Never empty.
         */
        fun of(values: DoubleArray, count: Int, optMin: Double, optMax: Double): GraphRange {
            var lo = Double.POSITIVE_INFINITY
            var hi = Double.NEGATIVE_INFINITY
            for (i in 0 until count) {
                val v = values[i]
                if (v.isNaN()) continue
                if (v < lo) lo = v
                if (v > hi) hi = v
            }
            if (lo > hi) {
                lo = 0.0
                hi = 1.0
            }
            val span = hi - lo
            val margin = if (span < 1e-9) max(abs(hi) * 0.05, 0.5) else span * 0.12
            var rangeMin = if (optMin.isNaN()) lo - margin else optMin
            var rangeMax = if (optMax.isNaN()) hi + margin else optMax
            // Data that never goes negative keeps a non-negative axis.
            if (optMin.isNaN() && lo >= 0.0 && rangeMin < 0.0) rangeMin = 0.0
            if (rangeMax <= rangeMin) rangeMax = rangeMin + 1.0
            return GraphRange(rangeMin, rangeMax)
        }
    }
}

/** GRAPH: the recent history of a sensor as a line with the current value in the title line. */
@Composable
internal fun GraphWidget(config: WidgetConfig, source: WidgetDataSource, modifier: Modifier = Modifier) {
    val sensor = config.sensor
    val binding = rememberSensor(source, sensor)
    val windowSec = (config.optionInt(WidgetOptions.HISTORY_SEC) ?: DEFAULT_HISTORY_SEC).coerceIn(5, 3600)
    val data = rememberPolled(source, sensor, windowSec) {
        GraphData(source.nowMs(), if (sensor == null) emptyList() else source.history(sensor, windowSec))
    }
    val chrome = rememberChrome(config, source, sensor)
    val optMin = config.optionDouble(WidgetOptions.MIN)
    val optMax = config.optionDouble(WidgetOptions.MAX)
    WidgetCanvas(chrome, modifier) {
        val reading = binding.reading.value
        val raw = binding.raw.value
        val units = binding.units.value
        val unit = raw?.unit ?: SensorUnit.NONE
        val lineColor = palette.tone(accent, if (reading.status == ReadingStatus.MISSING) ReadingStatus.OK else reading.status)
        val trailing = if (reading.unit.isEmpty()) reading.number else reading.number + " " + reading.unit
        val plot = header(reading.status, trailing = trailing, trailingColor = palette.tone(palette.text, reading.status))
        if (plot.height < 6f || plot.width < 6f) return@WidgetCanvas

        // Convert the samples to display units and find the range.
        val graph = data.value
        val samples = graph.samples
        val windowMs = windowSec * 1000L
        val first = samples.indexOfFirst { graph.nowMs - it.timestampMs <= windowMs }.let { if (it < 0) samples.size else it }
        val count = samples.size - first
        val values = DoubleArray(count) { displayValue(samples[first + it].value, unit, units) }
        val range = GraphRange.of(
            values, count,
            Readings.displayOption(optMin, raw, units),
            Readings.displayOption(optMax, raw, units),
        )

        // Grid.
        val gridColor = palette.track
        val hair = 1.dp.toPx()
        for (i in 0..GRID_LINES) {
            val y = plot.top + plot.height * i / GRID_LINES
            drawLine(gridColor, Offset(plot.left, y), Offset(plot.right, y), hair)
        }

        if (count >= 2) {
            val stroke = (min(plot.height, plot.width) * 0.035f).coerceIn(1.5.dp.toPx(), 3.dp.toPx())
            val line = Path()
            val area = Path()
            var lastX = plot.left
            var lastY = plot.bottom
            for (i in 0 until count) {
                val age = (graph.nowMs - samples[first + i].timestampMs).coerceAtLeast(0L)
                val x = plot.right - plot.width * (age.toFloat() / windowMs)
                val fraction = ((values[i] - range.min) / (range.max - range.min)).coerceIn(0.0, 1.0).toFloat()
                val y = plot.bottom - plot.height * fraction
                if (i == 0) {
                    line.moveTo(x, y)
                    area.moveTo(x, plot.bottom)
                    area.lineTo(x, y)
                } else {
                    line.lineTo(x, y)
                    area.lineTo(x, y)
                }
                lastX = x
                lastY = y
            }
            area.lineTo(lastX, plot.bottom)
            area.close()
            clipRect(plot.left, plot.top, plot.right, plot.bottom + stroke) {
                drawPath(
                    area,
                    Brush.verticalGradient(
                        listOf(lineColor.copy(alpha = lineColor.alpha * 0.32f), lineColor.copy(alpha = 0f)),
                        startY = plot.top,
                        endY = plot.bottom,
                    ),
                )
                drawPath(line, lineColor, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            drawCircle(palette.card, stroke * 1.7f, Offset(lastX, lastY))
            drawCircle(lineColor, stroke * 1.15f, Offset(lastX, lastY))
        } else {
            // No history yet.
            text(UnitConversion.NO_VALUE, plot, palette.tone(Color.Unspecified, ReadingStatus.MISSING), plot.height * 0.4f, FontWeight.Bold, alignX = 0.5f)
        }

        if (plot.height >= 54.dp.toPx() && count >= 2) {
            val fontPx = labelPx * 0.92f
            val rowHeight = fontPx * 1.25f
            val inset = 3.dp.toPx()
            text(
                WidgetFormat.scaleLabel(range.max),
                Rect(plot.left + inset, plot.top + inset * 0.5f, plot.left + plot.width * 0.4f, plot.top + inset * 0.5f + rowHeight),
                palette.textDim, fontPx, tabular = true,
            )
            text(
                WidgetFormat.scaleLabel(range.min),
                Rect(plot.left + inset, plot.bottom - rowHeight - inset * 0.5f, plot.left + plot.width * 0.4f, plot.bottom - inset * 0.5f),
                palette.textDim, fontPx, tabular = true,
            )
        }
    }
}

private fun displayValue(value: Double, unit: SensorUnit, system: UnitSystem): Double =
    UnitConversion.convert(value, unit, system).value

private const val DEFAULT_HISTORY_SEC = 60
private const val GRID_LINES = 3
