package org.openax12.core.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private fun formatCount(value: Int): String = value.toString()

/** Visual styles of [ChannelBar]. */
enum class ChannelBarStyle {
    /** Thin accent-blue bar on a hairline track: the home channel list. */
    Stock,

    /** Tall green bar in a sunken track: the rows of the setup screens. */
    Setup,
}

/**
 * Centre-zero bar for a channel or source value. The value is read in the draw phase.
 *
 * @param value value in percent, -100..+100; the bar grows from the centre
 * @param style [ChannelBarStyle.Stock] (home) or [ChannelBarStyle.Setup] (green, setup screens)
 * @param color bar colour; defaults to accent blue (Stock) or channel green (Setup)
 * @param range percent at the ends of the bar (100, or e.g. 150 for extended limits)
 */
@Composable
fun ChannelBar(
    value: () -> Float,
    modifier: Modifier = Modifier,
    style: ChannelBarStyle = ChannelBarStyle.Stock,
    color: Color = if (style == ChannelBarStyle.Stock) AxTheme.colors.accent else AxTheme.colors.channelGreen,
    range: Float = 100f,
) {
    val colors = AxTheme.colors
    when (style) {
        ChannelBarStyle.Stock -> Canvas(
            modifier = modifier
                .height(14.dp)
                .graphicsLayer(),
        ) {
            val v = value().let { if (it.isNaN()) 0f else it.coerceIn(-range, range) } / range
            val cy = size.height / 2f
            val cx = size.width / 2f
            val gap = 2.5.dp.toPx()
            val track = 2.dp.toPx()
            // Track in two halves with a gap at the centre, like the stock list.
            drawLine(colors.track, Offset(0f, cy), Offset(cx - gap, cy), strokeWidth = track, cap = StrokeCap.Round)
            drawLine(colors.track, Offset(cx + gap, cy), Offset(size.width, cy), strokeWidth = track, cap = StrokeCap.Round)
            val half = cx - gap
            val len = half * abs(v)
            if (len > 0.5f) {
                val bar = 4.dp.toPx()
                val from = if (v < 0f) cx - gap else cx + gap
                val to = if (v < 0f) from - len else from + len
                drawLine(color, Offset(from, cy), Offset(to, cy), strokeWidth = bar, cap = StrokeCap.Round)
            } else {
                drawCircle(colors.textFaint, radius = 1.5.dp.toPx(), center = Offset(cx, cy))
            }
        }

        ChannelBarStyle.Setup -> Box(
            modifier = modifier
                .height(22.dp)
                .axSunken(cornerRadius = 4.dp, depth = 1.5.dp, color = colors.surfaceSunken),
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(),
            ) {
                val v = value().let { if (it.isNaN()) 0f else it.coerceIn(-range, range) } / range
                val inset = 3.dp.toPx()
                val cx = size.width / 2f
                val half = cx - inset
                val len = half * abs(v)
                if (len > 0.5f) {
                    val left = if (v < 0f) cx - len else cx
                    drawRect(color, topLeft = Offset(left, inset), size = Size(len, size.height - 2f * inset))
                }
                drawLine(
                    color = colors.textFaint,
                    start = Offset(cx, inset * 0.5f),
                    end = Offset(cx, size.height - inset * 0.5f),
                    strokeWidth = 1.dp.toPx(),
                )
            }
        }
    }
}

/**
 * One row of the home channel list: label, centre-zero bar and percentage, e.g.
 * `CH03 ━━━━━━  -100%`.
 *
 * @param label channel label ("CH03")
 * @param value channel output in percent, read in the draw phase
 * @param emphasized true for channels that have mixes (dark label), false for unused channels
 *   (faint label), as in the stock list
 */
@Composable
fun ChannelRow(
    label: String,
    value: () -> Float,
    modifier: Modifier = Modifier,
    emphasized: Boolean = true,
) {
    val colors = AxTheme.colors
    val type = AxTheme.typography
    Row(modifier = modifier.height(18.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = if (emphasized) type.bodyStrong else type.body,
            color = if (emphasized) colors.text else colors.textFaint,
            maxLines = 1,
            modifier = Modifier.width(44.dp),
        )
        ChannelBar(
            value = value,
            modifier = Modifier.weight(1f),
            color = if (emphasized) colors.accent else colors.accent.copy(alpha = 0.55f),
        )
        AxLiveText(
            value = { value().let { if (it.isNaN()) 0 else it.roundToInt() } },
            format = ::formatPercent,
            template = "-100%",
            style = type.value,
            color = if (emphasized) colors.text else colors.textMuted,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

/**
 * State of a 2- or 3-position switch as a vertical pill, as in the stock home screen: the knob
 * is at the top in **orange** (up), in the middle in a **neutral** tone (mid) or at the bottom in
 * **teal** (down).
 *
 * @param position logic value of the switch, read in the draw phase: negative = up, 0 = mid,
 *   positive = down (the SDK reports -100 / 0 / +100)
 * @param label name under the pill ("SA")
 * @param positions 2 or 3; a 2-position switch has no middle detent mark
 */
@Composable
fun SwitchPill(
    position: () -> Int,
    label: String,
    modifier: Modifier = Modifier,
    positions: Int = 3,
) {
    val colors = AxTheme.colors
    Column(modifier = modifier.width(30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(width = 18.dp, height = 36.dp)
                .axSunken(cornerRadius = 9.dp, depth = 1.5.dp),
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(),
            ) {
                val p = position()
                val r = 6.dp.toPx()
                val cx = size.width / 2f
                val top = size.width / 2f
                val bottom = size.height - size.width / 2f
                val mid = size.height / 2f
                if (positions >= 3 && p != 0) {
                    drawCircle(colors.textFaint.copy(alpha = 0.5f), radius = 1.5.dp.toPx(), center = Offset(cx, mid))
                }
                val cy = when {
                    p < 0 -> top
                    p > 0 -> bottom
                    else -> mid
                }
                val knob = when {
                    p < 0 -> colors.orange
                    p > 0 -> colors.teal
                    else -> if (colors.neumorphic) Color.White else colors.textMuted
                }
                if (colors.neumorphic) {
                    drawCircle(colors.shadowDark.copy(alpha = 0.5f), radius = r, center = Offset(cx + 0.8.dp.toPx(), cy + 1.2.dp.toPx()))
                }
                drawCircle(knob, radius = r, center = Offset(cx, cy))
            }
        }
        Text(
            text = label,
            style = AxTheme.typography.label,
            color = colors.textMuted,
            maxLines = 1,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

/**
 * Half-circle gauge for a knob or slider, as in the stock home screen. The value is read in the
 * draw phase.
 *
 * @param value value in percent, -100..+100
 * @param label name shown inside the arc ("S1")
 * @param centerZero true (default): 0 is at the top and the arc fills towards the left or right;
 *   false: the arc fills from the left end, for 0..100 values
 * @param showValue shows the percentage under the label
 */
@Composable
fun ArcGauge(
    value: () -> Float,
    label: String,
    modifier: Modifier = Modifier,
    centerZero: Boolean = true,
    showValue: Boolean = true,
    color: Color = AxTheme.colors.accent,
) {
    val colors = AxTheme.colors
    Box(modifier = modifier.size(width = 84.dp, height = 50.dp)) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(),
        ) {
            val raw = value().let { if (it.isNaN()) 0f else it }
            val stroke = 9.dp.toPx()
            val radius = min(size.width / 2f, size.height) - stroke / 2f - 1.dp.toPx()
            val center = Offset(size.width / 2f, size.height - 1.dp.toPx())
            val topLeft = Offset(center.x - radius, center.y - radius)
            val arcSize = Size(radius * 2f, radius * 2f)
            drawArc(colors.track, 180f, 180f, false, topLeft, arcSize, style = Stroke(stroke))
            if (centerZero) {
                val v = raw.coerceIn(-100f, 100f)
                if (abs(v) > 0.5f) drawArc(color, 270f, 90f * v / 100f, false, topLeft, arcSize, style = Stroke(stroke))
                // Zero mark.
                drawLine(
                    color = colors.textFaint,
                    start = Offset(center.x, center.y - radius - stroke / 2f),
                    end = Offset(center.x, center.y - radius + stroke / 2f),
                    strokeWidth = 1.dp.toPx(),
                )
            } else {
                val v = raw.coerceIn(0f, 100f)
                if (v > 0.5f) drawArc(color, 180f, 180f * v / 100f, false, topLeft, arcSize, style = Stroke(stroke))
            }
        }
        Column(
            modifier = Modifier.align(Alignment.BottomCenter),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = label, style = AxTheme.typography.labelSmall, color = colors.textMuted, maxLines = 1)
            if (showValue) {
                AxLiveText(
                    value = { value().let { if (it.isNaN()) 0 else it.roundToInt() } },
                    format = ::formatPercent,
                    template = "-100%",
                    style = AxTheme.typography.label.copy(fontFeatureSettings = "tnum"),
                    color = colors.text,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * Timer row of the home centre card: stopwatch icon, coloured name, running time and a gear.
 *
 * The firmware has no manual start / stop, so the row offers a tap (the caller opens the timer
 * settings) and a long-press (the caller resets the timer); see `docs/NOT_DONE.md`.
 *
 * @param label timer name ("T1")
 * @param seconds current value in seconds, read in the draw phase
 * @param labelColor colour of the name; the stock app uses orange for T1 and teal for T2
 * @param running live flag, read in the draw phase: tints the icon while the timer runs
 * @param onClick tap on the row
 * @param onLongClick long-press on the row
 * @param onSettings tap on the gear; null hides it
 * @param isCounter shows the counter icon and a plain number instead of a time
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TimerRow(
    label: String,
    seconds: () -> Int,
    modifier: Modifier = Modifier,
    labelColor: Color = AxTheme.colors.orange,
    running: () -> Boolean = { false },
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onSettings: (() -> Unit)? = null,
    isCounter: Boolean = false,
) {
    val colors = AxTheme.colors
    val type = AxTheme.typography
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val clickable = onClick != null || onLongClick != null
    Row(
        modifier = modifier
            .height(40.dp)
            .axRaised(cornerRadius = 20.dp, elevation = 3.dp, pressed = pressed && clickable)
            .clip(RoundedCornerShape(20.dp))
            .then(
                if (clickable) {
                    Modifier.combinedClickable(
                        interactionSource = interaction,
                        indication = null,
                        role = Role.Button,
                        onLongClick = onLongClick,
                        onClick = { onClick?.invoke() },
                    )
                } else {
                    Modifier
                },
            )
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .graphicsLayer()
                .drawBehind {
                    // Small "running" dot next to the icon; read live.
                    if (running()) drawCircle(colors.success, radius = 3.dp.toPx(), center = Offset(size.width - 2.dp.toPx(), 3.dp.toPx()))
                },
        ) {
            AxIcon(icon = if (isCounter) AxIcons.Counter else AxIcons.Timer, contentDescription = null, tint = colors.accent, size = 22.dp)
        }
        Text(
            text = label,
            style = type.valueLarge,
            color = labelColor,
            maxLines = 1,
            modifier = Modifier
                .padding(start = 10.dp)
                .width(34.dp),
        )
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            AxLiveText(
                value = seconds,
                format = if (isCounter) ::formatCount else ::formatTimer,
                template = "-00:00:00",
                style = type.valueLarge,
                color = colors.text,
                textAlign = TextAlign.Center,
            )
        }
        if (onSettings != null) {
            AxIconButton(
                icon = AxIcons.System,
                contentDescription = stringResource(R.string.ax_cd_timer_settings),
                onClick = onSettings,
                variant = AxIconButtonVariant.Raised,
                tint = colors.text,
                size = 40.dp,
                iconSize = 18.dp,
            )
        } else {
            Spacer(Modifier.width(12.dp))
        }
    }
}

/**
 * Page selector `1 … n` of the home screen: small pills, the selected one filled with the accent
 * colour. Each page keeps a 44 dp high touch target.
 *
 * @param pageCount number of pages
 * @param selectedPage 0-based selected page
 * @param onSelect called with the 0-based page
 * @param labels optional custom labels (default "1".."n")
 */
@Composable
fun PageSelector(
    pageCount: Int,
    selectedPage: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    labels: List<String>? = null,
) {
    val colors = AxTheme.colors
    Row(
        modifier = modifier.height(AxDimens.MinTouch),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        for (page in 0 until pageCount) {
            val selected = page == selectedPage
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Tab,
                        onClick = { onSelect(page) },
                    )
                    .semantics { this.selected = selected },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 28.dp, height = 20.dp)
                        .then(
                            if (selected) {
                                Modifier.axRaised(cornerRadius = 10.dp, elevation = 2.dp, color = colors.accent)
                            } else {
                                Modifier.axRaised(cornerRadius = 10.dp, elevation = 2.dp, color = colors.surfaceRaised)
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = labels?.getOrNull(page) ?: (page + 1).toString(),
                        style = AxTheme.typography.label,
                        color = if (selected) colors.onAccent else colors.text,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                    )
                }
            }
        }
    }
}
