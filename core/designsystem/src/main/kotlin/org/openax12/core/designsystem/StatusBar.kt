package org.openax12.core.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Top status bar shown on every screen, modelled on the stock app: link bars and LQ on the left,
 * the title in a trapezoid tab hanging from the top edge, RX voltage, TX voltage and date / time
 * on the right.
 *
 * Live values are lambdas that are read in the draw phase: feed them straight from the live
 * snapshot state, nothing recomposes.
 *
 * @param title text in the centre tab (model name or screen title)
 * @param linkQuality link quality in percent, 0 = no link
 * @param rxVoltage receiver / aircraft voltage in volts
 * @param txVoltage transmitter battery voltage in volts
 * @param dateTime date / time text; null shows the running system clock
 * @param simulator shows the "SIM" badge (simulator backend active)
 * @param txBatteryLevel transmitter battery level 0..1 for the battery icon; null shows a plain
 *   transmitter icon instead
 * @param onTitleClick makes the centre tab clickable
 * @param flightMode slot next to the title for the active flight-mode name, see [AxFlightModeChip]
 */
@Composable
fun AxStatusBar(
    title: String,
    linkQuality: () -> Int,
    rxVoltage: () -> Float,
    txVoltage: () -> Float,
    modifier: Modifier = Modifier,
    dateTime: String? = null,
    simulator: Boolean = false,
    txBatteryLevel: (() -> Float)? = null,
    onTitleClick: (() -> Unit)? = null,
    flightMode: (@Composable () -> Unit)? = null,
) {
    val colors = AxTheme.colors
    val type = AxTheme.typography
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(AxDimens.StatusBarHeight),
    ) {
        // Left: link.
        Row(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val linkDescription = stringResource(R.string.ax_cd_link_quality)
            LinkBars(
                quality = linkQuality,
                modifier = Modifier
                    .size(width = 24.dp, height = 17.dp)
                    .semantics { contentDescription = linkDescription },
            )
            Spacer(Modifier.width(6.dp))
            AxLiveText(
                value = linkQuality,
                format = ::formatPercent,
                template = "100%",
                style = type.value,
                color = colors.text,
                textAlign = TextAlign.Start,
            )
            if (simulator) {
                Spacer(Modifier.width(8.dp))
                AxBadge(text = stringResource(R.string.ax_sim_badge), color = colors.orange)
            }
        }

        // Centre: title tab.
        StatusTitleTab(
            title = title,
            onClick = onTitleClick,
            flightMode = flightMode,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        // Right: voltages and clock.
        Row(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AxIcon(
                icon = AxIcons.Receiver,
                contentDescription = stringResource(R.string.ax_cd_rx_voltage),
                size = 19.dp,
                tint = colors.text,
            )
            Spacer(Modifier.width(3.dp))
            AxLiveText(
                value = { (rxVoltage() * 10f).roundToInt() },
                format = ::formatTenthsVolt,
                template = "00.0V",
                style = type.value,
                color = colors.text,
                textAlign = TextAlign.Start,
            )
            Spacer(Modifier.width(10.dp))
            if (txBatteryLevel != null) {
                val txDescription = stringResource(R.string.ax_cd_tx_voltage)
                BatteryIcon(
                    level = txBatteryLevel,
                    modifier = Modifier
                        .size(width = 24.dp, height = 13.dp)
                        .semantics { contentDescription = txDescription },
                )
            } else {
                AxIcon(
                    icon = AxIcons.Transmitter,
                    contentDescription = stringResource(R.string.ax_cd_tx_voltage),
                    size = 19.dp,
                    tint = colors.text,
                )
            }
            Spacer(Modifier.width(4.dp))
            AxLiveText(
                value = { (txVoltage() * 10f).roundToInt() },
                format = ::formatTenthsVolt,
                template = "00.0V",
                style = type.value,
                color = colors.text,
                textAlign = TextAlign.Start,
            )
            Spacer(Modifier.width(10.dp))
            if (dateTime != null) {
                Text(text = dateTime, style = type.value, color = colors.text, maxLines = 1)
            } else {
                AxClock()
            }
        }
    }
}

/** Running date / time text (`yyyy-MM-dd HH:mm`), updated once per minute boundary. */
@Composable
fun AxClock(modifier: Modifier = Modifier, pattern: String = "yyyy-MM-dd  HH:mm") {
    val format = remember(pattern) { SimpleDateFormat(pattern, Locale.US) }
    var text by remember(format) { mutableStateOf(format.format(Date())) }
    LaunchedEffect(format) {
        while (true) {
            val now = System.currentTimeMillis()
            text = format.format(Date(now))
            delay(1_000L - now % 1_000L + 20L)
        }
    }
    Text(text = text, modifier = modifier, style = AxTheme.typography.value, color = AxTheme.colors.text, maxLines = 1)
}

/** Small filled badge ("SIM", "managed", …). */
@Composable
fun AxBadge(text: String, modifier: Modifier = Modifier, color: Color = AxTheme.colors.accent, contentColor: Color = Color.White) {
    Box(
        modifier = modifier
            .background(color, RoundedCornerShape(50))
            .padding(horizontal = 6.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = AxTheme.typography.labelSmall, color = contentColor, maxLines = 1)
    }
}

/** Chip for the active flight-mode name in the status bar title tab. */
@Composable
fun AxFlightModeChip(name: String, modifier: Modifier = Modifier) {
    val colors = AxTheme.colors
    Row(
        modifier = modifier
            .background(colors.accent.copy(alpha = if (colors.neumorphic) 0.14f else 0.22f), RoundedCornerShape(50))
            .padding(start = 5.dp, end = 8.dp, top = 1.dp, bottom = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AxIcon(icon = AxIcons.FlightMode, contentDescription = null, size = 12.dp, tint = colors.accent)
        Spacer(Modifier.width(3.dp))
        Text(
            text = name,
            style = AxTheme.typography.label,
            color = colors.accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 84.dp),
        )
    }
}

@Composable
private fun StatusTitleTab(
    title: String,
    onClick: (() -> Unit)?,
    flightMode: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = AxTheme.colors
    val border = AxTheme.shapes.border
    val tabWidth: Dp = 264.dp
    val tabHeight: Dp = 34.dp
    var m = modifier
        .size(tabWidth, tabHeight)
        .drawWithCache {
            val slant = 16.dp.toPx()
            val r = 12.dp.toPx()
            val w = size.width
            val h = size.height
            // Trapezoid hanging from the top edge, lower corners rounded.
            val path = Path().apply {
                moveTo(0f, 0f)
                lineTo(w, 0f)
                // Right side down to the lower right corner.
                val dx = slant
                val len = kotlin.math.sqrt(dx * dx + h * h)
                val ux = -dx / len
                val uy = h / len
                lineTo(w - slant - ux * r, h - uy * r)
                quadraticTo(w - slant, h, w - slant - r, h)
                lineTo(slant + r, h)
                quadraticTo(slant, h, slant + ux * r, h - uy * r)
                close()
            }
            val shadowStep = 1.3.dp.toPx()
            val borderPx = border.toPx()
            onDrawBehind {
                if (colors.neumorphic) {
                    for (i in 5 downTo 1) {
                        translate(left = i * shadowStep * 0.6f, top = i * shadowStep) {
                            drawPath(path, color = colors.shadowDark.copy(alpha = 0.075f))
                        }
                    }
                    translate(left = -shadowStep, top = shadowStep * 0.6f) {
                        drawPath(path, color = colors.shadowLight.copy(alpha = 0.8f))
                    }
                    drawPath(path, color = colors.surface)
                } else {
                    drawPath(path, color = colors.surface)
                    drawPath(path, color = colors.outline, style = Stroke(width = borderPx))
                }
            }
        }
    if (onClick != null) {
        m = m.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            role = Role.Button,
            onClick = onClick,
        )
    }
    Row(
        modifier = m.padding(horizontal = 30.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            style = AxTheme.typography.titleSmall,
            color = colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (flightMode != null) {
            Spacer(Modifier.width(8.dp))
            flightMode()
        }
    }
}

/**
 * Signal bars for the link quality: five ascending bars, lit in proportion to [quality]
 * (percent). No link (0) shows hollow bars; a weak link is drawn in the warning colour.
 */
@Composable
fun LinkBars(quality: () -> Int, modifier: Modifier = Modifier, bars: Int = 5) {
    val colors = AxTheme.colors
    Canvas(modifier = modifier.graphicsLayer()) {
        val q = quality().coerceIn(0, 100)
        val lit = if (q <= 0) 0 else ceil(q * bars / 100f).toInt().coerceIn(1, bars)
        val gap = size.width * 0.07f
        val barWidth = (size.width - gap * (bars - 1)) / bars
        val litColor = when {
            q < 30 -> colors.error
            q < 55 -> colors.warning
            else -> colors.text
        }
        val stroke = 1.dp.toPx()
        val radius = CornerRadius(barWidth * 0.3f)
        for (i in 0 until bars) {
            val h = size.height * (0.32f + 0.68f * i / (bars - 1).coerceAtLeast(1))
            val left = i * (barWidth + gap)
            val top = size.height - h
            if (i < lit) {
                drawRoundRect(color = litColor, topLeft = Offset(left, top), size = Size(barWidth, h), cornerRadius = radius)
            } else {
                drawRoundRect(
                    color = colors.textFaint,
                    topLeft = Offset(left + stroke / 2f, top + stroke / 2f),
                    size = Size(barWidth - stroke, h - stroke),
                    cornerRadius = radius,
                    style = Stroke(width = stroke),
                )
            }
        }
    }
}

/**
 * Battery outline filled according to [level] (0..1). Turns to the warning colour below 30 %
 * and to the error colour below 15 %.
 */
@Composable
fun BatteryIcon(level: () -> Float, modifier: Modifier = Modifier, color: Color = AxTheme.colors.text) {
    val colors = AxTheme.colors
    Canvas(modifier = modifier.graphicsLayer()) {
        val l = level().let { if (it.isNaN()) 0f else it.coerceIn(0f, 1f) }
        val stroke = 1.5.dp.toPx()
        val capWidth = size.width * 0.08f
        val bodyWidth = size.width - capWidth
        val r = CornerRadius(size.height * 0.22f)
        drawRoundRect(
            color = color,
            topLeft = Offset(stroke / 2f, stroke / 2f),
            size = Size(bodyWidth - stroke, size.height - stroke),
            cornerRadius = r,
            style = Stroke(width = stroke),
        )
        drawRoundRect(
            color = color,
            topLeft = Offset(bodyWidth, size.height * 0.3f),
            size = Size(capWidth, size.height * 0.4f),
            cornerRadius = CornerRadius(capWidth * 0.4f),
        )
        val inset = stroke * 1.9f
        val fillWidth = (bodyWidth - 2f * inset) * l
        if (fillWidth > 0.5f) {
            val fill = when {
                l < 0.15f -> colors.error
                l < 0.3f -> colors.warning
                else -> color
            }
            drawRoundRect(
                color = fill,
                topLeft = Offset(inset, inset),
                size = Size(fillWidth, size.height - 2f * inset),
                cornerRadius = CornerRadius(size.height * 0.1f),
            )
        }
    }
}

/** Live colour for a link-quality text: error colour when the link is down. */
@Composable
fun linkQualityColor(quality: () -> Int): ColorProducer {
    val colors = AxTheme.colors
    return remember(colors, quality) { ColorProducer { if (quality() <= 0) colors.textMuted else colors.text } }
}
