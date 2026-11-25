package org.openax12.core.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
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
import kotlinx.coroutines.flow.collectLatest
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

/** Stick deflection in percent from which a border of the [StickPad] lights up. */
const val STICK_PAD_MAX_THRESHOLD: Float = 98f

/** Largest deflection in percent on both axes that still counts as dead centre. */
const val STICK_PAD_CENTER_THRESHOLD: Float = 2f

/**
 * Live stick display, the modernised version of the stock pad (SPEC section 2): a raised card
 * holding a rounded square field with a subtle grid, the stick position as a dot with a short
 * motion trail, four separate border segments and a centre marker.
 *
 * - A border **lights up with an accent glow while the stick is at its maximum on that side**
 *   (|value| >= [STICK_PAD_MAX_THRESHOLD] %); in a corner two borders and the corner between them
 *   are lit.
 * - The **centre marker lights up while the stick is at dead centre** (both axes within
 *   +-[STICK_PAD_CENTER_THRESHOLD] %).
 * - Trims are slim sliders on the outer vertical edge and below the field.
 *
 * All values are lambdas read in the draw phase: pass e.g. `{ live.value.logic(src).toFloat() }`.
 * Nothing recomposes while the stick moves, and only this pad's layer is redrawn.
 *
 * @param x horizontal deflection in percent, -100 (left) .. +100 (right)
 * @param y vertical deflection in percent, -100 (down) .. +100 (up)
 * @param trimX horizontal trim in percent of its range (-100..+100); null hides the slider
 * @param trimY vertical trim in percent of its range (-100..+100); null hides the slider
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
    StickPadImpl(x, y, modifier, trimX, trimY, verticalTrimEdge, seedTrail = null)
}

/** Maximum age of a trail sample. */
private const val TRAIL_NANOS = 260_000_000L
private const val TRAIL_CAPACITY = 24

/** Ring buffer of recent stick positions (percent) with their time stamps. */
private class StickTrail {
    val xs = FloatArray(TRAIL_CAPACITY)
    val ys = FloatArray(TRAIL_CAPACITY)
    val times = LongArray(TRAIL_CAPACITY)
    var start = 0
    var count = 0

    /** True while there is something to fade out; drives the frame loop. */
    var active by mutableStateOf(false)

    /** Frame time; read by the draw so that it reruns while [active]. */
    val frame = mutableLongStateOf(0L)

    fun index(i: Int): Int = (start + i) % TRAIL_CAPACITY

    fun push(x: Float, y: Float, time: Long) {
        if (count == TRAIL_CAPACITY) {
            start = (start + 1) % TRAIL_CAPACITY
            count--
        }
        val i = index(count)
        xs[i] = x
        ys[i] = y
        times[i] = time
        count++
    }

    /** Drops samples older than [TRAIL_NANOS], always keeping the newest one. */
    fun prune(now: Long) {
        while (count > 1 && now - times[start] > TRAIL_NANOS) {
            start = (start + 1) % TRAIL_CAPACITY
            count--
        }
    }
}

@Composable
internal fun StickPadImpl(
    x: () -> Float,
    y: () -> Float,
    modifier: Modifier,
    trimX: (() -> Float)?,
    trimY: (() -> Float)?,
    verticalTrimEdge: StickPadTrimEdge,
    seedTrail: FloatArray?,
) {
    val colors = AxTheme.colors
    val description = stringResource(R.string.ax_cd_stick_pad)
    val trail = remember {
        StickTrail().also { t ->
            // Screenshot tests seed a trail (x, y pairs, oldest first) to make it visible in one frame.
            if (seedTrail != null) {
                val now = System.nanoTime()
                val n = seedTrail.size / 2
                for (i in 0 until n) {
                    t.push(seedTrail[2 * i], seedTrail[2 * i + 1], now - (n - i).toLong() * TRAIL_NANOS / (n + 2))
                }
            }
        }
    }
    LaunchedEffect(trail) {
        snapshotFlow { trail.active }.collectLatest { active ->
            if (active) {
                while (true) withFrameNanos { trail.frame.longValue = it }
            }
        }
    }

    Box(
        modifier = modifier
            .semantics { contentDescription = description }
            .axRaised(cornerRadius = AxTheme.shapes.card, elevation = 5.dp),
    ) {
        val geometry = remember(trimX != null, trimY != null, verticalTrimEdge) {
            PadGeometry(hasTrimX = trimX != null, hasTrimY = trimY != null, trimAtStart = verticalTrimEdge == StickPadTrimEdge.Start)
        }
        // Static layer: the sunken field and its grid. Not redrawn while the stick moves.
        val fieldColor = colors.surfaceSunken
        val gridColor = colors.outline
        val neumorphic = colors.neumorphic
        val lightShadow = colors.shadowLight
        val darkShadow = colors.shadowDark
        Spacer(
            modifier = Modifier
                .fillMaxSize()
                .drawWithCache {
                    val field = geometry.field(this, size)
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
                        drawGrid(field, geometry.travelInset(this), gridColor)
                    }
                },
        )
        // Live layer.
        Spacer(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer()
                .drawWithCache {
                    val field = geometry.field(this, size)
                    val inset = geometry.travelInset(this)
                    val centerHalo = Brush.radialGradient(
                        colors = listOf(colors.glow.copy(alpha = 0.55f), colors.glow.copy(alpha = 0.18f), Color.Transparent),
                        center = field.center,
                        radius = 20.dp.toPx(),
                    )
                    onDrawBehind {
                        // Subscribe to the frame clock while a trail is fading.
                        trail.frame.longValue
                        val px = sanitize(x())
                        val py = sanitize(y())
                        val now = System.nanoTime()
                        val last = if (trail.count > 0) trail.index(trail.count - 1) else -1
                        if (last < 0 || trail.xs[last] != px || trail.ys[last] != py) {
                            trail.push(px, py, now)
                        } else if (trail.count == 1) {
                            trail.times[last] = now
                        }
                        trail.prune(now)
                        val fading = trail.count > 1
                        if (trail.active != fading) trail.active = fading

                        drawBorders(field, px, py, colors)
                        drawCenterMarker(field, px, py, colors, centerHalo)
                        drawTrail(trail, field, inset, now, colors.accent)
                        drawStickDot(field, inset, px, py, colors)
                        if (trimY != null) drawVerticalTrim(geometry, field, sanitize(trimY()), colors)
                        if (trimX != null) drawHorizontalTrim(geometry, field, sanitize(trimX()), colors)
                    }
                },
        )
    }
}

private val FieldRadius: Dp = 14.dp
private val PadPadding: Dp = 10.dp
private val TrimStrip: Dp = 16.dp
private val DotRadius: Dp = 6.5.dp

private fun sanitize(v: Float): Float = if (v.isNaN()) 0f else v.coerceIn(-100f, 100f)

/** Where the square field and the trim sliders sit inside the pad. */
private class PadGeometry(val hasTrimX: Boolean, val hasTrimY: Boolean, val trimAtStart: Boolean) {
    fun field(density: Density, size: Size): Rect = with(density) {
        val pad = PadPadding.toPx()
        val strip = TrimStrip.toPx()
        val left = pad + if (hasTrimY && trimAtStart) strip else 0f
        val right = size.width - pad - if (hasTrimY && !trimAtStart) strip else 0f
        val top = pad
        val bottom = size.height - pad - if (hasTrimX) strip else 0f
        val side = max(0f, min(right - left, bottom - top))
        // Square, centred in the space the trims leave.
        val l = (left + (right - left - side) / 2f).toInt().toFloat()
        val t = (top + (bottom - top - side) / 2f).toInt().toFloat()
        Rect(l, t, l + side.toInt(), t + side.toInt())
    }

    /** Distance from the field edge to the dot centre at full deflection. */
    fun travelInset(density: Density): Float = with(density) { DotRadius.toPx() + 7.dp.toPx() }
}

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
    val right = x >= STICK_PAD_MAX_THRESHOLD
    val left = x <= -STICK_PAD_MAX_THRESHOLD
    val top = y >= STICK_PAD_MAX_THRESHOLD
    val bottom = y <= -STICK_PAD_MAX_THRESHOLD

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

/** Motion trail: line segments through the recent positions, fading and thinning with age. */
private fun DrawScope.drawTrail(trail: StickTrail, field: Rect, inset: Float, now: Long, color: Color) {
    if (trail.count < 2) return
    val maxWidth = DotRadius.toPx() * 1.5f
    for (i in 0 until trail.count - 1) {
        val a = trail.index(i)
        val b = trail.index(i + 1)
        val age = ((now - trail.times[b]).toFloat() / TRAIL_NANOS).coerceIn(0f, 1f)
        val strength = 1f - age
        if (strength <= 0.02f) continue
        drawLine(
            color = color.copy(alpha = 0.42f * strength),
            start = Offset(stickX(field, inset, trail.xs[a]), stickY(field, inset, trail.ys[a])),
            end = Offset(stickX(field, inset, trail.xs[b]), stickY(field, inset, trail.ys[b])),
            strokeWidth = maxWidth * (0.25f + 0.75f * strength),
            cap = StrokeCap.Round,
        )
    }
}

private fun DrawScope.drawStickDot(field: Rect, inset: Float, x: Float, y: Float, colors: AxColors) {
    val c = Offset(stickX(field, inset, x), stickY(field, inset, y))
    val r = DotRadius.toPx()
    if (colors.neumorphic) {
        drawCircle(colors.shadowDark.copy(alpha = 0.55f), radius = r + 1.dp.toPx(), center = Offset(c.x + 1.5.dp.toPx(), c.y + 2.dp.toPx()))
    }
    drawCircle(if (colors.isLight) Color.White else colors.background, radius = r + 2.dp.toPx(), center = c)
    drawCircle(colors.accent, radius = r, center = c)
}

private fun DrawScope.drawTrimThumb(center: Offset, vertical: Boolean, trim: Float, colors: AxColors) {
    val long = 16.dp.toPx()
    val short = 5.dp.toPx()
    val w = if (vertical) short else long
    val h = if (vertical) long else short
    val color = if (abs(trim) < 0.5f) colors.accent else colors.accent
    if (colors.neumorphic) {
        drawRoundRect(
            color = colors.shadowDark.copy(alpha = 0.45f),
            topLeft = Offset(center.x - w / 2f + 1.dp.toPx(), center.y - h / 2f + 1.5.dp.toPx()),
            size = Size(w, h),
            cornerRadius = CornerRadius(short / 2f),
        )
    }
    drawRoundRect(
        color = color,
        topLeft = Offset(center.x - w / 2f, center.y - h / 2f),
        size = Size(w, h),
        cornerRadius = CornerRadius(short / 2f),
    )
}

private fun DrawScope.drawTrimTrack(from: Offset, to: Offset, colors: AxColors) {
    val mid = Offset((from.x + to.x) / 2f, (from.y + to.y) / 2f)
    drawLine(colors.track, from, to, strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
    // Centre notch.
    val n = 4.dp.toPx()
    val vertical = from.x == to.x
    val a = if (vertical) Offset(mid.x - n, mid.y) else Offset(mid.x, mid.y - n)
    val b = if (vertical) Offset(mid.x + n, mid.y) else Offset(mid.x, mid.y + n)
    drawLine(colors.textFaint, a, b, strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
}

private fun DrawScope.drawVerticalTrim(geometry: PadGeometry, field: Rect, trim: Float, colors: AxColors) {
    val strip = TrimStrip.toPx()
    val cx = if (geometry.trimAtStart) field.left - strip / 2f - 1.dp.toPx() else field.right + strip / 2f + 1.dp.toPx()
    val margin = 12.dp.toPx()
    val top = field.top + margin
    val bottom = field.bottom - margin
    drawTrimTrack(Offset(cx, top), Offset(cx, bottom), colors)
    val cy = (top + bottom) / 2f - (bottom - top) / 2f * trim / 100f
    drawTrimThumb(Offset(cx, cy), vertical = true, trim = trim, colors = colors)
}

private fun DrawScope.drawHorizontalTrim(geometry: PadGeometry, field: Rect, trim: Float, colors: AxColors) {
    val strip = TrimStrip.toPx()
    val cy = field.bottom + strip / 2f + 1.dp.toPx()
    val margin = 12.dp.toPx()
    val left = field.left + margin
    val right = field.right - margin
    drawTrimTrack(Offset(left, cy), Offset(right, cy), colors)
    val cx = (left + right) / 2f + (right - left) / 2f * trim / 100f
    drawTrimThumb(Offset(cx, cy), vertical = false, trim = trim, colors = colors)
}
