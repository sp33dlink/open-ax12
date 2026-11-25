package org.openax12.feature.functions.sounds

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.openax12.core.designsystem.AxButton
import org.openax12.core.designsystem.AxButtonVariant
import org.openax12.core.designsystem.AxIcons
import org.openax12.core.designsystem.AxSwitch
import org.openax12.core.designsystem.AxTheme
import org.openax12.core.designsystem.NumberField
import org.openax12.core.engine.VarioMath
import org.openax12.core.model.SensorId
import org.openax12.core.model.SensorUnit
import org.openax12.core.model.Sensors
import org.openax12.core.model.VarioConfig
import org.openax12.feature.functions.R
import org.openax12.feature.functions.common.Caption
import org.openax12.feature.functions.common.FormCard
import org.openax12.feature.functions.common.FormRow
import org.openax12.feature.functions.common.PickerData
import org.openax12.feature.functions.common.SensorField
import org.openax12.feature.functions.common.SensorGroup
import org.openax12.feature.functions.common.numberWithUnit

private val TEST_SPEEDS = listOf(-3.0, 0.0, 1.0, 3.0, 6.0)

/**
 * The vario settings: source, dead band, range / pitch / cadence on the left; a live curve of the
 * resulting tone and test buttons on the right. Every change is staged at once.
 */
@Composable
internal fun VarioPanel(config: VarioConfig, pickers: PickerData, callbacks: SoundsCallbacks, modifier: Modifier = Modifier) {
    fun edit(transform: (VarioConfig) -> VarioConfig) = callbacks.onEditVario(transform)
    Row(modifier = modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(
            modifier = Modifier.weight(1.15f).fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FormCard(title = stringResource(R.string.fn_vario_source)) {
                FormRow(label = stringResource(R.string.fn_vario_always), description = stringResource(R.string.fn_vario_always_help)) {
                    AxSwitch(checked = config.alwaysOn, onCheckedChange = { v -> edit { it.copy(alwaysOn = v) } })
                }
                FormRow(label = stringResource(R.string.fn_vario_derive), description = stringResource(R.string.fn_vario_derive_help)) {
                    AxSwitch(checked = config.deriveFromAltitude, onCheckedChange = { v -> edit { it.copy(deriveFromAltitude = v) } })
                }
                if (config.deriveFromAltitude) {
                    FormRow(label = stringResource(R.string.fn_vario_altitude_sensor)) {
                        SensorField(
                            selected = config.altitudeSensor ?: Sensors.BARO_ALTITUDE,
                            data = pickers,
                            onSelected = { s -> edit { it.copy(altitudeSensor = s) } },
                            filter = { it.group == SensorGroup.TELEMETRY },
                        )
                    }
                } else {
                    FormRow(label = stringResource(R.string.fn_vario_speed_sensor)) {
                        SensorField(
                            selected = config.sensor ?: Sensors.VERTICAL_SPEED,
                            data = pickers,
                            onSelected = { s: SensorId -> edit { it.copy(sensor = s) } },
                            filter = { it.group == SensorGroup.TELEMETRY },
                        )
                    }
                }
            }
            FormCard(title = stringResource(R.string.fn_vario_deadband)) {
                FormRow(label = stringResource(R.string.fn_vario_center_min)) {
                    RateField(config.centerMin, { v -> edit { it.copy(centerMin = v) } }, stringResource(R.string.fn_vario_center_min), -30.0, 0.0)
                }
                FormRow(label = stringResource(R.string.fn_vario_center_max)) {
                    RateField(config.centerMax, { v -> edit { it.copy(centerMax = v) } }, stringResource(R.string.fn_vario_center_max), 0.0, 30.0)
                }
                FormRow(label = stringResource(R.string.fn_vario_center_silent), description = stringResource(R.string.fn_vario_center_silent_help)) {
                    AxSwitch(checked = config.centerSilent, onCheckedChange = { v -> edit { it.copy(centerSilent = v) } })
                }
            }
            FormCard(title = stringResource(R.string.fn_vario_range)) {
                FormRow(label = stringResource(R.string.fn_vario_range_min)) {
                    RateField(config.rangeMin, { v -> edit { it.copy(rangeMin = v) } }, stringResource(R.string.fn_vario_range_min), -30.0, 0.0)
                }
                FormRow(label = stringResource(R.string.fn_vario_range_max)) {
                    RateField(config.rangeMax, { v -> edit { it.copy(rangeMax = v) } }, stringResource(R.string.fn_vario_range_max), 0.0, 30.0)
                }
            }
            FormCard(title = stringResource(R.string.fn_vario_pitch)) {
                FormRow(label = stringResource(R.string.fn_vario_pitch_min)) {
                    HzField(config.pitchMinHz, { v -> edit { it.copy(pitchMinHz = v) } }, stringResource(R.string.fn_vario_pitch_min))
                }
                FormRow(label = stringResource(R.string.fn_vario_pitch_zero)) {
                    HzField(config.pitchZeroHz, { v -> edit { it.copy(pitchZeroHz = v) } }, stringResource(R.string.fn_vario_pitch_zero))
                }
                FormRow(label = stringResource(R.string.fn_vario_pitch_max)) {
                    HzField(config.pitchMaxHz, { v -> edit { it.copy(pitchMaxHz = v) } }, stringResource(R.string.fn_vario_pitch_max))
                }
                FormRow(label = stringResource(R.string.fn_vario_repeat_zero), description = stringResource(R.string.fn_vario_repeat_help)) {
                    MsField(config.repeatZeroMs, { v -> edit { it.copy(repeatZeroMs = v) } }, stringResource(R.string.fn_vario_repeat_zero))
                }
                FormRow(label = stringResource(R.string.fn_vario_repeat_max)) {
                    MsField(config.repeatMaxMs, { v -> edit { it.copy(repeatMaxMs = v) } }, stringResource(R.string.fn_vario_repeat_max))
                }
            }
        }
        Column(
            modifier = Modifier.weight(1f).fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FormCard(title = stringResource(R.string.fn_vario_curve)) {
                VarioCurve(config = config, modifier = Modifier.fillMaxWidth().height(150.dp))
                Caption(stringResource(R.string.fn_vario_curve_help))
            }
            FormCard(title = stringResource(R.string.fn_vario_test)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    TEST_SPEEDS.forEach { speed ->
                        AxButton(
                            text = numberWithUnit(speed, SensorUnit.NONE),
                            onClick = { callbacks.onTestVario(speed) },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp),
                        )
                    }
                }
                Caption(stringResource(R.string.fn_vario_test_help))
                AxButton(
                    text = stringResource(R.string.fn_vario_reset),
                    onClick = callbacks.onResetVario,
                    variant = AxButtonVariant.Outlined,
                    icon = AxIcons.Restore,
                )
            }
        }
    }
}

@Composable
private fun RateField(value: Double, onChange: (Double) -> Unit, title: String, min: Double, max: Double) {
    NumberField(
        value = VarioEdits.tenths(value),
        onValueChange = { onChange(VarioEdits.fromTenths(it)) },
        min = VarioEdits.tenths(min),
        max = VarioEdits.tenths(max),
        decimals = 1,
        unit = " m/s",
        title = title,
        modifier = Modifier.width(112.dp),
    )
}

@Composable
private fun HzField(value: Int, onChange: (Int) -> Unit, title: String) {
    NumberField(
        value = value,
        onValueChange = onChange,
        min = VarioLimits.PITCH_MIN_HZ,
        max = VarioLimits.PITCH_MAX_HZ,
        step = 50,
        unit = " Hz",
        title = title,
        modifier = Modifier.width(112.dp),
    )
}

@Composable
private fun MsField(value: Int, onChange: (Int) -> Unit, title: String) {
    NumberField(
        value = value,
        onValueChange = onChange,
        min = VarioLimits.PERIOD_MIN_MS,
        max = VarioLimits.PERIOD_MAX_MS,
        step = 10,
        unit = " ms",
        title = title,
        modifier = Modifier.width(112.dp),
    )
}

/** Samples of the tone for the curve: vertical speed and pitch (null = silent), from `VarioMath`. */
internal fun varioCurve(config: VarioConfig, samples: Int = 120): List<Pair<Double, Float?>> {
    val low = config.rangeMin - 1.0
    val high = config.rangeMax + 1.0
    return List(samples) { i ->
        val speed = low + (high - low) * i / (samples - 1)
        val tone = VarioMath.tone(speed, config)
        speed to tone.takeIf { !it.silent }?.frequencyHz
    }
}

/** The pitch over the vertical speed; gaps where the tone is silent, a vertical marker at zero. */
@Composable
private fun VarioCurve(config: VarioConfig, modifier: Modifier = Modifier) {
    val colors = AxTheme.colors
    val points = varioCurve(config)
    val top = (listOf(config.pitchMaxHz, config.pitchZeroHz, config.pitchMinHz).max() * 1.1f).coerceAtLeast(1f)
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val low = points.first().first
        val high = points.last().first
        fun x(speed: Double) = ((speed - low) / (high - low) * w).toFloat()
        fun y(hz: Float) = h - hz / top * h
        // Dead band.
        drawRect(
            color = colors.outline.copy(alpha = 0.35f),
            topLeft = Offset(x(config.centerMin), 0f),
            size = Size(x(config.centerMax) - x(config.centerMin), h),
        )
        // Axes: zero climb and the baseline.
        drawLine(colors.outlineStrong, Offset(x(0.0), 0f), Offset(x(0.0), h), strokeWidth = 1.dp.toPx())
        drawLine(colors.outline, Offset(0f, h), Offset(w, h), strokeWidth = 1.dp.toPx())
        var path = Path()
        var open = false
        for ((speed, hz) in points) {
            if (hz == null) {
                if (open) {
                    drawPath(path, colors.accent, style = Stroke(width = 2.5.dp.toPx()))
                    path = Path()
                    open = false
                }
            } else if (!open) {
                path.moveTo(x(speed), y(hz))
                open = true
            } else {
                path.lineTo(x(speed), y(hz))
            }
        }
        if (open) drawPath(path, colors.accent, style = Stroke(width = 2.5.dp.toPx()))
    }
}
