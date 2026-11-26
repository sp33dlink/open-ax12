package org.openax12.core.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Which vertical edge of a [StickPad] carries the vertical trim slider (the outer one). */
enum class StickPadTrimEdge {
    /** Left edge: the left stick pad. */
    Start,

    /** Right edge: the right stick pad. */
    End,
}

/**
 * Stick deflection in percent at which a border of the [StickPad] lights up: exactly full
 * deflection (+-100 %). Live values are integer percent clamped to +-100, so a stick that never
 * reaches its full raw travel never lights its border, which reveals missing calibration.
 */
const val STICK_PAD_MAX_THRESHOLD: Float = 100f

/** True if [value] (percent) is at full positive deflection (>= +100 %). */
internal fun isStickAtPositiveMax(value: Float): Boolean = value >= STICK_PAD_MAX_THRESHOLD

/** True if [value] (percent) is at full negative deflection (<= -100 %). */
internal fun isStickAtNegativeMax(value: Float): Boolean = value <= -STICK_PAD_MAX_THRESHOLD

/** Largest deflection in percent on both axes that still counts as dead centre. */
const val STICK_PAD_CENTER_THRESHOLD: Float = 2f

/**
 * Live stick display, the modernised version of the stock pad (SPEC section 2): a rounded square
 * field with a subtle grid, the stick position as a dot, four border segments and a centre marker.
 * There is no card around the field: the trims ride on its border, so the field can use the whole
 * pad area.
 *
 * - A border **lights up with an accent glow while the stick is at its maximum on that side**
 *   (exactly +-[STICK_PAD_MAX_THRESHOLD] %, so an uncalibrated stick that does not reach full
 *   travel never lights it); in a corner two borders and the corner between them
 *   are lit.
 * - The **centre marker lights up while the stick is at dead centre** (both axes within
 *   +-[STICK_PAD_CENTER_THRESHOLD] %).
 * - Trims are small markers sliding along the outer vertical edge and the bottom edge of the
 *   field, with a tick at the neutral position. They are drawn on top of a lit border with a
 *   contrasting rim, so they stay readable.
 *
 * All values are lambdas read in the draw phase: pass e.g. `{ live.value.logic(src).toFloat() }`.
 * Nothing recomposes while the stick moves, and only this pad's layer is redrawn.
 *
 * @param x horizontal deflection in percent, -100 (left) .. +100 (right)
 * @param y vertical deflection in percent, -100 (down) .. +100 (up)
 * @param trimX horizontal trim in percent of its range (-100..+100); null hides the marker
 * @param trimY vertical trim in percent of its range (-100..+100); null hides the marker
 * @param verticalTrimEdge edge carrying the vertical trim: [StickPadTrimEdge.Start] for the left
 *   pad, [StickPadTrimEdge.End] for the right pad
 */
@Composable
fun StickPad(
    x: () -> Float,
    y: () -> Float,
    modifier: Modifier = Modifier,
    trimX: (() -> Float)? = null,
    trimY: (() -> Float)? = null,
    verticalTrimEdge: StickPadTrimEdge = StickPadTrimEdge.Start,
) {
    val colors = AxTheme.colors
    val description = stringResource(R.string.ax_cd_stick_pad)
    val trimAtStart = verticalTrimEdge == StickPadTrimEdge.Start

    // Static layer: the sunken field and its grid. Not redrawn while the stick moves.
    val fieldColor = colors.surfaceSunken
    val gridColor = colors.outline
    val neumorphic = colors.neumorphic
    val lightShadow = colors.shadowLight
    val darkShadow = colors.shadowDark
    Box(modifier = modifier.semantics { contentDescription = description }) {
        Spacer(
            modifier = Modifier
                .fillMaxSize()
                .drawWithCache {
                    val field = padField(this, size)
                    val radius = FieldRadius.toPx()
                    val patch = if (neumorphic) {
                        NeumorphicCache.sunken(
                            width = field.width.toInt(),
                            height = field.height.toInt(),
                            radius = radius.toInt(),
                            blur = 4.dp.roundToPx(),
                            offset = 2.dp.roundToPx(),
                            face = fieldColor,
                            light = lightShadow.copy(alpha = 0.9f),
                            dark = darkShadow.copy(alpha = 0.7f),
                        )
                    } else {
                        null
                    }
                    onDrawBehind {
                        if (patch != null) {
                            translate(left = field.left, top = field.top) {
                                drawSurfacePatch(patch, field.width.toInt(), field.height.toInt())
                            }
                        } else {
                            drawRoundRect(fieldColor, topLeft = field.topLeft, size = field.size, cornerRadius = CornerRadius(radius))
                        }
                        drawGrid(field, travelInset(this), gridColor)
                    }
                },
        )
        // Live layer.
        Spacer(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer()
                .drawWithCache {
                    val field = padField(this, size)
                    val inset = travelInset(this)
                    val centerHalo = Brush.radialGradient(
                        colors = listOf(colors.glow.copy(alpha = 0.55f), colors.glow.copy(alpha = 0.18f), Color.Transparent),
                        center = field.center,
                        radius = 20.dp.toPx(),
                    )
                    onDrawBehind {
                        val px = sanitize(x())
                        val py = sanitize(y())
                        drawBorders(field, px, py, colors)
                        drawCenterMarker(field, px, py, colors, centerHalo)
                        drawStickDot(field, inset, px, py, colors)
                        if (trimY != null) drawVerticalTrim(field, trimAtStart, sanitize(trimY()), colors, litSide = if (trimAtStart) isStickAtNegativeMax(px) else isStickAtPositiveMax(px))
                        if (trimX != null) drawHorizontalTrim(field, sanitize(trimX()), colors, litSide = isStickAtNegativeMax(py))
                    }
                },
        )
    }
}

private val FieldRadius: Dp = 14.dp

/** Space kept free around the field for the glow of a lit border. */
private val PadPadding: Dp = 4.dp
private val DotRadius: Dp = 6.5.dp

private fun sanitize(v: Float): Float = if (v.isNaN()) 0f else v.coerceIn(-100f, 100f)

/** The square field, centred in the pad. */
private fun padField(density: Density, size: Size): Rect = with(density) {
    val pad = PadPadding.toPx()
    val side = max(0f, min(size.width, size.height) - 2f * pad).toInt().toFloat()
    val l = ((size.width - side) / 2f).toInt().toFloat()
    val t = ((size.height - side) / 2f).toInt().toFloat()
    Rect(l, t, l + side, t + side)
}

/** Distance from the field edge to the dot centre at full deflection. */
private fun travelInset(density: Density): Float = with(density) { DotRadius.toPx() + 7.dp.toPx() }

private fun DrawScope.drawGrid(field: Rect, inset: Float, color: Color) {
    val stroke = 1.dp.toPx()
    val travel = Rect(field.left + inset, field.top + inset, field.right - inset, field.bottom - inset)
    val faint = color.copy(alpha = color.alpha * 0.45f)
    val strong = color.copy(alpha = color.alpha * 0.9f)
    // Quarter lines.
    for (i in 1..3) {
        val f = i / 4f
        val c = if (i == 2) strong else faint
        val gx = travel.left + travel.width * f
        val gy = travel.top + travel.height * f
        drawLine(c, Offset(gx, field.top + inset * 0.5f), Offset(gx, field.bottom - inset * 0.5f), strokeWidth = stroke)
        drawLine(c, Offset(field.left + inset * 0.5f, gy), Offset(field.right - inset * 0.5f, gy), strokeWidth = stroke)
    }
    // Travel limits as small corner ticks.
    val tick = 5.dp.toPx()
    for (cx in 0..1) {
        for (cy in 0..1) {
            val px = if (cx == 0) travel.left else travel.right
            val py = if (cy == 0) travel.top else travel.bottom
            val sx = if (cx == 0) 1f else -1f
            val sy = if (cy == 0) 1f else -1f
            drawLine(strong, Offset(px, py), Offset(px + sx * tick, py), strokeWidth = stroke)
            drawLine(strong, Offset(px, py), Offset(px, py + sy * tick), strokeWidth = stroke)
        }
    }
}

/** Draws a line with a soft halo around it (three wide translucent passes, no blur). */
private fun DrawScope.drawGlowLine(color: Color, from: Offset, to: Offset, width: Float) {
    drawLine(color.copy(alpha = 0.10f), from, to, strokeWidth = width * 5.2f, cap = StrokeCap.Round)
    drawLine(color.copy(alpha = 0.16f), from, to, strokeWidth = width * 3.4f, cap = StrokeCap.Round)
    drawLine(color.copy(alpha = 0.30f), from, to, strokeWidth = width * 2.0f, cap = StrokeCap.Round)
    drawLine(color, from, to, strokeWidth = width, cap = StrokeCap.Round)
}

private fun DrawScope.drawGlowArc(color: Color, topLeft: Offset, diameter: Float, startAngle: Float, width: Float) {
    val size = Size(diameter, diameter)
    drawArc(color.copy(alpha = 0.10f), startAngle, 90f, false, topLeft, size, style = Stroke(width * 5.2f))
    drawArc(color.copy(alpha = 0.16f), startAngle, 90f, false, topLeft, size, style = Stroke(width * 3.4f))
    drawArc(color.copy(alpha = 0.30f), startAngle, 90f, false, topLeft, size, style = Stroke(width * 2.0f))
    drawArc(color, startAngle, 90f, false, topLeft, size, style = Stroke(width))
}

/**
 * The four border segments. Each straight side is its own segment and lights up while the stick
 * is at the maximum on that side; a corner arc lights up when both adjacent sides are lit.
 */
private fun DrawScope.drawBorders(field: Rect, x: Float, y: Float, colors: AxColors) {
    val right = isStickAtPositiveMax(x)
    val left = isStickAtNegativeMax(x)
    val top = isStickAtPositiveMax(y)
    val bottom = isStickAtNegativeMax(y)

    val idleWidth = 1.5.dp.toPx()
    val litWidth = 3.dp.toPx()
    val half = 1.dp.toPx()
    val r = FieldRadius.toPx()
    val gap = 3.dp.toPx()
    val l = field.left + half
    val t = field.top + half
    val rr = field.right - half
    val b = field.bottom - half
    val idle = colors.outline.copy(alpha = if (colors.neumorphic) 0.9f else 1f)
    val lit = colors.glow

    // Corner arcs first (idle or lit), so the segments end on top of them.
    val d = 2f * (r - half)
    fun corner(isLit: Boolean, cx: Float, cy: Float, start: Float) {
        if (isLit) {
            drawGlowArc(lit, Offset(cx, cy), d, start, litWidth)
        } else {
            drawArc(idle.copy(alpha = idle.alpha * 0.55f), start + 14f, 62f, false, Offset(cx, cy), Size(d, d), style = Stroke(idleWidth, cap = StrokeCap.Round))
        }
    }
    corner(top && left, l, t, 180f)
    corner(top && right, rr - d, t, 270f)
    corner(bottom && right, rr - d, b - d, 0f)
    corner(bottom && left, l, b - d, 90f)

    fun side(isLit: Boolean, from: Offset, to: Offset) {
        if (isLit) {
            drawGlowLine(lit, from, to, litWidth)
        } else {
            drawLine(idle, from, to, strokeWidth = idleWidth, cap = StrokeCap.Round)
        }
    }
    // A lit side reaches right up to the corner arc, an idle one leaves a small gap.
    fun inset(isLit: Boolean, cornerLit: Boolean) = if (isLit && cornerLit) r - half else r - half + gap
    side(top, Offset(l + inset(top, top && left), t), Offset(rr - inset(top, top && right), t))
    side(bottom, Offset(l + inset(bottom, bottom && left), b), Offset(rr - inset(bottom, bottom && right), b))
    side(left, Offset(l, t + inset(left, top && left)), Offset(l, b - inset(left, bottom && left)))
    side(right, Offset(rr, t + inset(right, top && right)), Offset(rr, b - inset(right, bottom && right)))
}

/** Centre marker: a small ring with four ticks, glowing while the stick is at dead centre. */
private fun DrawScope.drawCenterMarker(field: Rect, x: Float, y: Float, colors: AxColors, halo: Brush) {
    val centered = abs(x) <= STICK_PAD_CENTER_THRESHOLD && abs(y) <= STICK_PAD_CENTER_THRESHOLD
    val c = field.center
    val ring = 10.dp.toPx()
    val tickIn = ring + 2.5.dp.toPx()
    val tickOut = ring + 7.dp.toPx()
    if (centered) {
        drawCircle(halo, radius = 20.dp.toPx(), center = c)
        val w = 2.5.dp.toPx()
        drawCircle(colors.glow, radius = ring, center = c, style = Stroke(w))
        drawLine(colors.glow, Offset(c.x - tickOut, c.y), Offset(c.x - tickIn, c.y), strokeWidth = w, cap = StrokeCap.Round)
        drawLine(colors.glow, Offset(c.x + tickIn, c.y), Offset(c.x + tickOut, c.y), strokeWidth = w, cap = StrokeCap.Round)
        drawLine(colors.glow, Offset(c.x, c.y - tickOut), Offset(c.x, c.y - tickIn), strokeWidth = w, cap = StrokeCap.Round)
        drawLine(colors.glow, Offset(c.x, c.y + tickIn), Offset(c.x, c.y + tickOut), strokeWidth = w, cap = StrokeCap.Round)
    } else {
        val w = 1.5.dp.toPx()
        val idle = colors.textFaint
        drawCircle(idle, radius = ring, center = c, style = Stroke(w))
        drawLine(idle, Offset(c.x - tickOut, c.y), Offset(c.x - tickIn, c.y), strokeWidth = w, cap = StrokeCap.Round)
        drawLine(idle, Offset(c.x + tickIn, c.y), Offset(c.x + tickOut, c.y), strokeWidth = w, cap = StrokeCap.Round)
        drawLine(idle, Offset(c.x, c.y - tickOut), Offset(c.x, c.y - tickIn), strokeWidth = w, cap = StrokeCap.Round)
        drawLine(idle, Offset(c.x, c.y + tickIn), Offset(c.x, c.y + tickOut), strokeWidth = w, cap = StrokeCap.Round)
    }
}

private fun stickX(field: Rect, inset: Float, x: Float): Float =
    field.center.x + (field.width / 2f - inset) * x / 100f

private fun stickY(field: Rect, inset: Float, y: Float): Float =
    field.center.y - (field.height / 2f - inset) * y / 100f

private fun DrawScope.drawStickDot(field: Rect, inset: Float, x: Float, y: Float, colors: AxColors) {
    val c = Offset(stickX(field, inset, x), stickY(field, inset, y))
    val r = DotRadius.toPx()
    if (colors.neumorphic) {
        drawCircle(colors.shadowDark.copy(alpha = 0.55f), radius = r + 1.dp.toPx(), center = Offset(c.x + 1.5.dp.toPx(), c.y + 2.dp.toPx()))
    }
    drawCircle(if (colors.isLight) Color.White else colors.background, radius = r + 2.dp.toPx(), center = c)
    drawCircle(colors.accent, radius = r, center = c)
}

/** Marker long side, short side and the contrasting rim around it. */
private val TrimLong: Dp = 18.dp
private val TrimShort: Dp = 6.dp
private val TrimRim: Dp = 1.75.dp

/** The marker: an accent pill with a contrasting rim, so it reads on top of an idle or a lit border. */
private fun DrawScope.drawTrimMarker(center: Offset, vertical: Boolean, colors: AxColors) {
    val long = TrimLong.toPx()
    val short = TrimShort.toPx()
    val rim = TrimRim.toPx()
    val w = if (vertical) short else long
    val h = if (vertical) long else short
    val rimColor = if (colors.isLight) Color.White else colors.background
    drawRoundRect(
        color = rimColor,
        topLeft = Offset(center.x - w / 2f - rim, center.y - h / 2f - rim),
        size = Size(w + 2f * rim, h + 2f * rim),
        cornerRadius = CornerRadius(short / 2f + rim),
    )
    drawRoundRect(
        color = colors.accent,
        topLeft = Offset(center.x - w / 2f, center.y - h / 2f),
        size = Size(w, h),
        cornerRadius = CornerRadius(short / 2f),
    )
}

/** Neutral-position tick across the border at [center]; drawn light on a lit border. */
private fun DrawScope.drawTrimTick(center: Offset, vertical: Boolean, litSide: Boolean, colors: AxColors) {
    val half = 5.dp.toPx()
    val color = if (litSide) (if (colors.isLight) Color.White else colors.background) else colors.textFaint
    val from = if (vertical) Offset(center.x - half, center.y) else Offset(center.x, center.y - half)
    val to = if (vertical) Offset(center.x + half, center.y) else Offset(center.x, center.y + half)
    drawLine(color, from, to, strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
}

/** Range of a marker along its border: stays on the straight part of the edge, clear of the corners. */
private fun DrawScope.trimTravel(): Float = FieldRadius.toPx() + TrimLong.toPx() / 2f

private fun DrawScope.drawVerticalTrim(field: Rect, atStart: Boolean, trim: Float, colors: AxColors, litSide: Boolean) {
    val x = (if (atStart) field.left else field.right) + (if (atStart) 1.dp.toPx() else -1.dp.toPx())
    val travel = field.height / 2f - trimTravel()
    drawTrimTick(Offset(x, field.center.y), vertical = true, litSide = litSide, colors = colors)
    drawTrimMarker(Offset(x, field.center.y - travel * trim / 100f), vertical = true, colors = colors)
}

private fun DrawScope.drawHorizontalTrim(field: Rect, trim: Float, colors: AxColors, litSide: Boolean) {
    val y = field.bottom - 1.dp.toPx()
    val travel = field.width / 2f - trimTravel()
    drawTrimTick(Offset(field.center.x, y), vertical = false, litSide = litSide, colors = colors)
    drawTrimMarker(Offset(field.center.x + travel * trim / 100f, y), vertical = false, colors = colors)
}
