package org.openax12.core.widgets

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.openax12.core.model.Sensors
import org.openax12.core.model.UnitConversion
import org.openax12.core.model.WidgetConfig
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * HORIZON: artificial horizon. Sensors: pitch, roll in degrees (nose up and right wing down are
 * positive); the attitude sensors are used when none are bound.
 */
@Composable
internal fun HorizonWidget(config: WidgetConfig, source: WidgetDataSource, modifier: Modifier = Modifier) {
    val pitch = rememberSensor(source, config.sensors.getOrNull(0) ?: Sensors.ATTITUDE_PITCH)
    val roll = rememberSensor(source, config.sensors.getOrNull(1) ?: Sensors.ATTITUDE_ROLL)
    val chrome = rememberChrome(config, source, null, stringResource(R.string.widget_title_horizon))
    val pitchPattern = stringResource(R.string.widget_horizon_pitch)
    val rollPattern = stringResource(R.string.widget_horizon_roll)
    WidgetCanvas(chrome, modifier) {
        val ws = this
        val pitchReading = pitch.reading.value
        val rollReading = roll.reading.value
        val status = Readings.worst(pitchReading.status, rollReading.status)
        val frame = header(status)
        if (frame.width < 8f || frame.height < 8f) return@WidgetCanvas
        val pitchDeg = if (pitchReading.hasValue) pitchReading.value.coerceIn(-90.0, 90.0).toFloat() else 0f
        val rollDeg = if (rollReading.hasValue) rollReading.value.toFloat() else 0f
        val corner = CornerRadius(min(10.dp.toPx(), min(frame.width, frame.height) * 0.16f))
        val outline = Path().apply { addRoundRect(RoundRect(frame, corner)) }
        val center = frame.center
        val pixelsPerDegree = frame.height / VISIBLE_PITCH_DEGREES
        val white = Color.White
        val hair = (frame.height * 0.012f).coerceIn(1.dp.toPx(), 2.dp.toPx())
        val big = hypot(frame.width, frame.height) + 90f * pixelsPerDegree

        clipPath(outline) {
            rotate(-rollDeg, center) {
                translate(0f, pitchDeg * pixelsPerDegree) {
                    drawRect(
                        Brush.verticalGradient(listOf(ws.palette.skyHigh, ws.palette.sky), startY = center.y - frame.height, endY = center.y),
                        Offset(center.x - big, center.y - big),
                        Size(big * 2f, big),
                    )
                    drawRect(
                        Brush.verticalGradient(listOf(ws.palette.ground, ws.palette.groundLow), startY = center.y, endY = center.y + frame.height),
                        Offset(center.x - big, center.y),
                        Size(big * 2f, big),
                    )
                    drawLine(white, Offset(center.x - big, center.y), Offset(center.x + big, center.y), hair * 1.3f)
                    // Pitch ladder: long rungs every 10 degrees, short ones in between.
                    val ladderColor = white.copy(alpha = 0.85f)
                    val labelled = frame.height >= 96.dp.toPx()
                    val fontPx = (frame.height * 0.075f).coerceIn(8.dp.toPx(), 12.dp.toPx())
                    var degrees = -90
                    while (degrees <= 90) {
                        if (degrees != 0) {
                            val y = center.y - degrees * pixelsPerDegree
                            val major = degrees % 10 == 0
                            val half = frame.width * (if (major) 0.15f else 0.07f)
                            drawLine(ladderColor, Offset(center.x - half, y), Offset(center.x + half, y), hair)
                            if (major && labelled) {
                                val label = abs(degrees).toString()
                                val box = fontPx * 1.8f
                                ws.text(label, Rect(center.x - half - box - hair * 3f, y - fontPx, center.x - half - hair * 3f, y + fontPx), ladderColor, fontPx, alignX = 1f)
                                ws.text(label, Rect(center.x + half + hair * 3f, y - fontPx, center.x + half + hair * 3f + box, y + fontPx), ladderColor, fontPx, alignX = 0f)
                            }
                        }
                        degrees += 5
                    }
                }
            }

            // Roll scale along the top: ticks turn with the horizon, the pointer is fixed.
            val scaleRadius = min(frame.height * 0.44f, frame.width * 0.46f)
            if (min(frame.width, frame.height) >= 64.dp.toPx()) {
                rotate(-rollDeg, center) {
                    for (tick in ROLL_TICKS) {
                        val length = scaleRadius * (if (tick % 30 == 0) 0.12f else 0.07f)
                        rotate(tick.toFloat(), center) {
                            drawLine(
                                white.copy(alpha = 0.9f),
                                Offset(center.x, center.y - scaleRadius),
                                Offset(center.x, center.y - scaleRadius + length),
                                hair,
                                cap = StrokeCap.Round,
                            )
                        }
                    }
                }
                val pointer = scaleRadius * 0.1f
                val tip = Offset(center.x, center.y - scaleRadius + scaleRadius * 0.14f)
                drawPath(
                    Path().apply {
                        moveTo(tip.x, tip.y)
                        lineTo(tip.x - pointer * 0.7f, tip.y + pointer * 1.2f)
                        lineTo(tip.x + pointer * 0.7f, tip.y + pointer * 1.2f)
                        close()
                    },
                    ws.palette.switchUp,
                )
            }

            // Fixed aircraft symbol.
            val wing = frame.width * 0.2f
            val gapHalf = frame.width * 0.07f
            val thick = (frame.height * 0.03f).coerceIn(2.dp.toPx(), 4.5.dp.toPx())
            for (pass in 0..1) {
                val color = if (pass == 0) Color.Black.copy(alpha = 0.45f) else ws.palette.switchUp
                val width = if (pass == 0) thick * 1.7f else thick
                drawLine(color, Offset(center.x - gapHalf - wing, center.y), Offset(center.x - gapHalf, center.y), width, cap = StrokeCap.Round)
                drawLine(color, Offset(center.x + gapHalf, center.y), Offset(center.x + gapHalf + wing, center.y), width, cap = StrokeCap.Round)
                drawCircle(color, width * 0.75f, center)
            }

            if (status != ReadingStatus.OK) {
                drawRect(ws.palette.card.copy(alpha = if (status == ReadingStatus.MISSING) 0.72f else 0.5f), frame.topLeft, frame.size)
            }
        }
        drawRoundRect(palette.outline, frame.topLeft, frame.size, corner, style = Stroke(1.dp.toPx()))

        if (status == ReadingStatus.MISSING) {
            text(UnitConversion.NO_VALUE, frame, palette.text.copy(alpha = 0.7f), frame.height * 0.3f, FontWeight.Bold, alignX = 0.5f)
        } else if (frame.height >= 84.dp.toPx() && frame.width >= 120.dp.toPx()) {
            // Numeric read-outs in the lower corners.
            val fontPx = (frame.height * 0.09f).coerceIn(9.dp.toPx(), 13.dp.toPx())
            val rowHeight = fontPx * 1.5f
            val inset = fontPx * 0.5f
            readout(String.format(Locale.ROOT, pitchPattern, WidgetFormat.signed(pitchReading)), Offset(frame.left + inset, frame.bottom - inset - rowHeight), rowHeight, fontPx, alignEnd = false)
            readout(String.format(Locale.ROOT, rollPattern, WidgetFormat.signed(rollReading)), Offset(frame.right - inset, frame.bottom - inset - rowHeight), rowHeight, fontPx, alignEnd = true)
        }
    }
}

/** A small label on a translucent dark pill, anchored at its start (or end) at [anchor]. */
private fun WidgetScope.readout(label: String, anchor: Offset, rowHeight: Float, fontPx: Float, alignEnd: Boolean) {
    val layout = measure(label, fontPx, FontWeight.SemiBold, tabular = true)
    val padX = fontPx * 0.5f
    val width = layout.size.width + padX * 2f
    val left = if (alignEnd) anchor.x - width else anchor.x
    drawRoundRect(Color.Black.copy(alpha = 0.42f), Offset(left, anchor.y), Size(width, rowHeight), CornerRadius(rowHeight / 2f))
    text(label, Rect(left + padX, anchor.y, left + width - padX, anchor.y + rowHeight), Color.White, fontPx, FontWeight.SemiBold, alignX = 0.5f, tabular = true)
}

/**
 * GPS: distance and bearing to home with a north-up arrow, satellites, and the coordinates when
 * the cell is tall enough. Sensors: latitude, longitude (the CRSF GPS sensors by default);
 * distance, bearing and satellites always come from the well-known GPS sensors.
 */
@Composable
internal fun GpsWidget(config: WidgetConfig, source: WidgetDataSource, modifier: Modifier = Modifier) {
    val latitude = rememberSensor(source, config.sensors.getOrNull(0) ?: Sensors.GPS_LATITUDE)
    val longitude = rememberSensor(source, config.sensors.getOrNull(1) ?: Sensors.GPS_LONGITUDE)
    val distance = rememberSensor(source, Sensors.GPS_HOME_DISTANCE)
    val bearing = rememberSensor(source, Sensors.GPS_HOME_BEARING)
    val satellites = rememberSensor(source, Sensors.GPS_SATELLITES)
    val chrome = rememberChrome(config, source, null, stringResource(R.string.widget_title_gps))
    val satellitesPattern = stringResource(R.string.widget_gps_satellites)
    val bearingPattern = stringResource(R.string.widget_gps_bearing)
    val compassPoints = stringArrayResource(R.array.widget_compass_points)
    val north = stringResource(R.string.widget_latitude_north)
    val south = stringResource(R.string.widget_latitude_south)
    val east = stringResource(R.string.widget_longitude_east)
    val west = stringResource(R.string.widget_longitude_west)
    WidgetCanvas(chrome, modifier) {
        val lat = latitude.reading.value
        val lon = longitude.reading.value
        val status = Readings.worst(lat.status, lon.status)
        val satReading = satellites.reading.value
        val satCount = if (satReading.hasValue) satReading.value.roundToInt() else -1
        val body = header(
            status,
            trailing = if (satCount >= 0) String.format(Locale.ROOT, satellitesPattern, satCount) else null,
            trailingColor = palette.tone(if (satCount >= MIN_GOOD_SATELLITES) palette.good else palette.warn, satReading.status),
        )
        val dim = palette.tone(palette.textDim, status)

        // Coordinates at the bottom once the cell is tall enough.
        val showCoordinates = body.height >= 74.dp.toPx()
        val coordPx = min(labelPx * 1.1f, body.width * 0.075f)
        val coordRow = coordPx * 1.4f
        val sideBySide = body.width >= coordPx * 21f
        val coordHeight = if (!showCoordinates) 0f else if (sideBySide) coordRow else coordRow * 2f
        val top = Rect(body.left, body.top, body.right, body.bottom - coordHeight - (if (showCoordinates) coordPx * 0.4f else 0f))

        // Compass with the bearing arrow.
        val bearingReading = bearing.reading.value
        val diameter = min(top.height, top.width * 0.4f)
        val radius = diameter / 2f
        val compassCenter = Offset(top.left + radius, top.center.y)
        val ringStroke = (diameter * 0.06f).coerceIn(1.5.dp.toPx(), 3.dp.toPx())
        drawCircle(palette.track, radius - ringStroke / 2f, compassCenter, style = Stroke(ringStroke))
        // North mark.
        drawLine(
            palette.textDim,
            Offset(compassCenter.x, compassCenter.y - radius),
            Offset(compassCenter.x, compassCenter.y - radius + diameter * 0.14f),
            ringStroke,
            cap = StrokeCap.Round,
        )
        if (bearingReading.hasValue) {
            val arrowColor = palette.tone(accent, Readings.worst(status, bearingReading.status))
            rotate(bearingReading.value.toFloat(), compassCenter) {
                val length = radius * 0.62f
                val arrow = Path().apply {
                    moveTo(compassCenter.x, compassCenter.y - length)
                    lineTo(compassCenter.x + length * 0.55f, compassCenter.y + length * 0.72f)
                    lineTo(compassCenter.x, compassCenter.y + length * 0.36f)
                    lineTo(compassCenter.x - length * 0.55f, compassCenter.y + length * 0.72f)
                    close()
                }
                drawPath(arrow, arrowColor)
            }
        } else {
            drawCircle(palette.track, radius * 0.18f, compassCenter)
        }

        // Distance, bearing as text.
        val textBox = Rect(top.left + diameter + pad, top.top, top.right, top.bottom)
        val distanceReading = distance.reading.value
        val distanceRaw = distance.raw.value
        val (distanceNumber, distanceUnit) =
            if (distanceRaw != null && distanceRaw.hasValue) WidgetFormat.distance(distanceRaw.value, distance.units.value) else UnitConversion.NO_VALUE to ""
        val distanceStatus = if (distanceReading.hasContent) Readings.worst(status, distanceReading.status) else ReadingStatus.MISSING
        val bearingText = if (bearingReading.hasValue) {
            val degrees = ((bearingReading.value % 360.0) + 360.0) % 360.0
            String.format(Locale.ROOT, bearingPattern, UnitConversion.formatNumber(degrees, 0), compassPoints.getOrElse(WidgetFormat.compassOctant(degrees)) { "" })
        } else {
            ""
        }
        val showBearing = bearingText.isNotEmpty() && top.height >= 34.dp.toPx()
        val subPx = min(labelPx * 1.05f, top.height * 0.24f)
        val subRow = if (showBearing) subPx * 1.35f else 0f
        val mainBox = Rect(textBox.left, textBox.top, textBox.right, textBox.bottom - subRow)
        valueText(
            distanceNumber, distanceUnit, mainBox,
            palette.tone(palette.text, distanceStatus), palette.tone(palette.textDim, distanceStatus),
            mainBox.height * 0.8f, alignX = 0f, minChars = 3,
        )
        if (showBearing) {
            text(bearingText, Rect(textBox.left, mainBox.bottom, textBox.right, textBox.bottom), dim, subPx, FontWeight.Medium, tabular = true)
        }

        if (showCoordinates) {
            val latText = if (lat.hasValue) WidgetFormat.coordinate(lat.value, north, south) else UnitConversion.NO_VALUE
            val lonText = if (lon.hasValue) WidgetFormat.coordinate(lon.value, east, west) else UnitConversion.NO_VALUE
            val color = palette.tone(palette.text.copy(alpha = 0.86f), status)
            val rowTop = body.bottom - coordHeight
            if (sideBySide) {
                val half = body.width / 2f
                text(latText, Rect(body.left, rowTop, body.left + half, rowTop + coordRow), color, coordPx, FontWeight.Medium, tabular = true)
                text(lonText, Rect(body.left + half, rowTop, body.right, rowTop + coordRow), color, coordPx, FontWeight.Medium, alignX = 1f, tabular = true)
            } else {
                text(latText, Rect(body.left, rowTop, body.right, rowTop + coordRow), color, coordPx, FontWeight.Medium, tabular = true)
                text(lonText, Rect(body.left, rowTop + coordRow, body.right, rowTop + coordRow * 2f), color, coordPx, FontWeight.Medium, tabular = true)
            }
        }
    }
}

/**
 * The `geo:` URI of a position for opening it in a maps app (the "open in maps" action of the
 * GPS widget, started by whoever handles the widget tap).
 */
fun gpsMapsUri(latitude: Double, longitude: Double): String =
    String.format(Locale.ROOT, "geo:%.6f,%.6f?q=%.6f,%.6f", latitude, longitude, latitude, longitude)

private const val VISIBLE_PITCH_DEGREES = 48f
private const val MIN_GOOD_SATELLITES = 6
private val ROLL_TICKS = intArrayOf(-60, -45, -30, -20, -10, 0, 10, 20, 30, 45, 60)
