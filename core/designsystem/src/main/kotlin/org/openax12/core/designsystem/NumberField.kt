package org.openax12.core.designsystem

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Formats a scaled integer: `formatScaled(15, 1)` = `"1.5"`, `formatScaled(-100, 0)` = `"-100"`.
 *
 * @param value the value multiplied by 10^[decimals]
 * @param decimals number of decimal places (0..4)
 */
fun formatScaled(value: Int, decimals: Int): String {
    if (decimals <= 0) return value.toString()
    val scale = pow10(decimals)
    val abs = abs(value.toLong())
    val whole = abs / scale
    val frac = (abs % scale).toString().padStart(decimals, '0')
    return (if (value < 0) "-" else "") + whole + "." + frac
}

private fun pow10(n: Int): Long {
    var r = 1L
    repeat(n.coerceIn(0, 9)) { r *= 10 }
    return r
}

/** Parses number-pad text into a scaled integer, or null if it is not a number. */
internal fun parseScaled(text: String, decimals: Int): Long? {
    val t = text.trim()
    if (t.isEmpty() || t == "-" || t == "." || t == "-.") return null
    val negative = t.startsWith("-")
    val body = if (negative) t.substring(1) else t
    val dot = body.indexOf('.')
    val wholePart = if (dot >= 0) body.substring(0, dot) else body
    var fracPart = if (dot >= 0) body.substring(dot + 1) else ""
    if (wholePart.any { !it.isDigit() } || fracPart.any { !it.isDigit() }) return null
    if (wholePart.length > 9) return null
    fracPart = fracPart.take(decimals).padEnd(decimals.coerceAtLeast(0), '0')
    val whole = if (wholePart.isEmpty()) 0L else wholePart.toLong()
    val frac = if (fracPart.isEmpty()) 0L else fracPart.toLong()
    val v = whole * pow10(decimals) + frac
    return if (negative) -v else v
}

/**
 * Numeric value box that opens the [NumberPadDialog] when tapped: the way every number in the
 * app is edited (weights, offsets, times, thresholds). Finger-sized, no soft keyboard.
 *
 * Values are integers scaled by 10^[decimals], so `value = 15, decimals = 1, unit = "s"` shows
 * `1.5s` and steps of 0.1 s are `step = 1`.
 *
 * @param value current value (scaled)
 * @param onValueChange called with the confirmed new value, already clamped to [min]..[max]
 * @param min smallest allowed value (scaled)
 * @param max largest allowed value (scaled)
 * @param step increment of the +/- steppers (scaled)
 * @param decimals number of decimal places
 * @param unit suffix shown after the number ("%", "s", "V")
 * @param title title of the number pad; defaults to a generic one
 * @param defaultValue offers a "Default" button in the pad
 * @param steppers additionally shows inline - / + buttons next to the box
 * @param format custom display text of the box (e.g. "Off" for 0)
 */
@Composable
fun NumberField(
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    min: Int = -100,
    max: Int = 100,
    step: Int = 1,
    decimals: Int = 0,
    unit: String = "",
    title: String? = null,
    defaultValue: Int? = null,
    enabled: Boolean = true,
    steppers: Boolean = false,
    highlighted: Boolean = false,
    height: Dp = AxDimens.ControlHeight,
    format: ((Int) -> String)? = null,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val text = format?.invoke(value) ?: (formatScaled(value, decimals) + unit)
    if (steppers) {
        Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StepperButton(
                icon = AxIcons.Remove,
                description = stringResource(R.string.ax_decrease),
                enabled = enabled && value > min,
                onClick = { onValueChange((value - step).coerceIn(min, max)) },
            )
            AxValueBox(
                text = text,
                onClick = { open = true },
                enabled = enabled,
                highlighted = highlighted,
                textStyle = AxTheme.typography.value,
                height = height,
                modifier = Modifier.weight(1f),
            )
            StepperButton(
                icon = AxIcons.Add,
                description = stringResource(R.string.ax_increase),
                enabled = enabled && value < max,
                onClick = { onValueChange((value + step).coerceIn(min, max)) },
            )
        }
    } else {
        AxValueBox(
            text = text,
            onClick = { open = true },
            enabled = enabled,
            highlighted = highlighted,
            textStyle = AxTheme.typography.value,
            height = height,
            modifier = modifier,
        )
    }
    if (open) {
        NumberPadDialog(
            title = title ?: stringResource(R.string.ax_number_title),
            initialValue = value,
            min = min,
            max = max,
            step = step,
            decimals = decimals,
            unit = unit,
            defaultValue = defaultValue,
            onConfirm = {
                open = false
                if (it != value) onValueChange(it)
            },
            onDismiss = { open = false },
        )
    }
}

@Composable
private fun StepperButton(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    AxIconButton(
        icon = icon,
        contentDescription = description,
        onClick = onClick,
        variant = AxIconButtonVariant.Raised,
        enabled = enabled,
        tint = AxTheme.colors.text,
        iconSize = 20.dp,
    )
}

/**
 * Number pad: keypad with sign and decimal point, backspace, +/- steppers, range hint and unit.
 * The OK button is only enabled while the entry is inside [min]..[max].
 *
 * @param initialValue value shown when the pad opens (scaled by 10^[decimals]); the first digit
 *   typed replaces it
 * @param onConfirm called with the entered value (scaled)
 */
@Composable
fun NumberPadDialog(
    title: String,
    initialValue: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    min: Int = -100,
    max: Int = 100,
    step: Int = 1,
    decimals: Int = 0,
    unit: String = "",
    defaultValue: Int? = null,
) {
    val colors = AxTheme.colors
    val type = AxTheme.typography
    var entry by rememberSaveable { mutableStateOf(formatScaled(initialValue, decimals)) }
    // True until the first key: typing then replaces the initial value instead of appending.
    var fresh by rememberSaveable { mutableStateOf(true) }
    val parsed = parseScaled(entry, decimals)
    val inRange = parsed != null && parsed >= min && parsed <= max
    val allowNegative = min < 0
    val allowDecimal = decimals > 0

    fun typeDigit(d: Char) {
        val base = if (fresh) "" else entry
        fresh = false
        val dot = base.indexOf('.')
        if (dot >= 0 && base.length - dot - 1 >= decimals) return
        val digits = base.count { it.isDigit() }
        if (digits >= 9) return
        entry = when (base) {
            "0" -> d.toString()
            "-0" -> "-$d"
            else -> base + d
        }
    }

    fun stepBy(delta: Int) {
        val current = (parsed ?: initialValue.toLong()).coerceIn(min.toLong(), max.toLong())
        val next = (current + delta).coerceIn(min.toLong(), max.toLong())
        entry = formatScaled(next.toInt(), decimals)
        fresh = true
    }

    AxDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = title,
        width = 330.dp,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
        buttons = {
            if (defaultValue != null) {
                AxButton(
                    text = stringResource(R.string.ax_default),
                    onClick = {
                        entry = formatScaled(defaultValue.coerceIn(min, max), decimals)
                        fresh = true
                    },
                    variant = AxButtonVariant.Text,
                )
            }
            AxButton(text = stringResource(R.string.ax_cancel), onClick = onDismiss, variant = AxButtonVariant.Text)
            AxButton(
                text = stringResource(R.string.ax_ok),
                onClick = { parsed?.let { onConfirm(it.toInt()) } },
                variant = AxButtonVariant.Primary,
                enabled = inRange,
            )
        },
    ) {
        // Display.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .axSunken(cornerRadius = AxTheme.shapes.control, depth = 2.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.ax_number_range, formatScaled(min, decimals) + unit, formatScaled(max, decimals) + unit),
                    style = type.labelSmall,
                    color = if (inRange || parsed == null) colors.textMuted else colors.error,
                    maxLines = 1,
                )
                if (parsed != null && !inRange) {
                    Text(text = stringResource(R.string.ax_number_out_of_range), style = type.labelSmall, color = colors.error, maxLines = 1)
                }
            }
            Text(
                text = entry.ifEmpty { "0" },
                style = type.display,
                color = if (fresh) colors.accent else colors.text,
                maxLines = 1,
                textAlign = TextAlign.End,
            )
            if (unit.isNotEmpty()) {
                Text(text = unit, style = type.title, color = colors.textMuted, modifier = Modifier.padding(start = 3.dp, top = 6.dp))
            }
        }
        // Keys.
        val rows = listOf("789", "456", "123")
        Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            rows.forEachIndexed { rowIndex, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { digit ->
                        PadKey(label = digit.toString(), modifier = Modifier.weight(1f)) { typeDigit(digit) }
                    }
                    when (rowIndex) {
                        0 -> PadKey(icon = AxIcons.Backspace, description = stringResource(R.string.ax_backspace), modifier = Modifier.weight(1f), accent = true) {
                            fresh = false
                            entry = entry.dropLast(1)
                        }
                        1 -> PadKey(icon = AxIcons.Add, description = stringResource(R.string.ax_increase), modifier = Modifier.weight(1f), accent = true) { stepBy(step) }
                        else -> PadKey(icon = AxIcons.Remove, description = stringResource(R.string.ax_decrease), modifier = Modifier.weight(1f), accent = true) { stepBy(-step) }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PadKey(label = "+/−", modifier = Modifier.weight(1f), enabled = allowNegative) {
                    val base = if (fresh) entry else entry
                    fresh = false
                    entry = if (base.startsWith("-")) base.substring(1) else "-$base"
                }
                PadKey(label = "0", modifier = Modifier.weight(1f)) { typeDigit('0') }
                PadKey(label = ".", modifier = Modifier.weight(1f), enabled = allowDecimal) {
                    val base = if (fresh) "0" else entry
                    fresh = false
                    if (!base.contains('.')) entry = (if (base.isEmpty() || base == "-") base + "0" else base) + "."
                }
                PadKey(label = "C", modifier = Modifier.weight(1f), accent = true) {
                    fresh = false
                    entry = ""
                }
            }
        }
    }
}

@Composable
private fun PadKey(
    modifier: Modifier = Modifier,
    label: String? = null,
    icon: ImageVector? = null,
    description: String? = null,
    enabled: Boolean = true,
    accent: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = AxTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val radius = AxTheme.shapes.control
    Box(
        modifier = modifier
            .height(44.dp)
            .alpha(if (enabled) 1f else 0.35f)
            .axRaised(cornerRadius = radius, elevation = 2.5.dp, color = colors.surfaceRaised, pressed = pressed && enabled)
            .clip(RoundedCornerShape(radius))
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { if (description != null) contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            AxIcon(icon = icon, contentDescription = null, tint = if (accent) colors.accent else colors.text, size = 22.dp)
        } else if (label != null) {
            Text(text = label, style = AxTheme.typography.valueLarge, color = if (accent) colors.accent else colors.text, maxLines = 1)
        }
    }
}

/** Width that fits a [NumberField] showing values like `-100%`. */
val NumberFieldDefaultWidth: Dp = 76.dp

/** Convenience: [NumberField] for a percentage (-100..100 by default) with the "%" suffix. */
@Composable
fun PercentField(
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    min: Int = -100,
    max: Int = 100,
    title: String? = null,
    defaultValue: Int? = null,
    enabled: Boolean = true,
) {
    NumberField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.width(NumberFieldDefaultWidth),
        min = min,
        max = max,
        unit = "%",
        title = title,
        defaultValue = defaultValue,
        enabled = enabled,
    )
}
