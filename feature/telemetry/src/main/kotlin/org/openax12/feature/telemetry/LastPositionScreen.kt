package org.openax12.feature.telemetry

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.openax12.core.designsystem.AxButton
import org.openax12.core.designsystem.AxButtonVariant
import org.openax12.core.designsystem.AxCard
import org.openax12.core.designsystem.AxDivider
import org.openax12.core.designsystem.AxEmptyState
import org.openax12.core.designsystem.AxIcons
import org.openax12.core.designsystem.AxTheme
import org.openax12.core.model.GpsFix
import org.openax12.core.model.SensorUnit
import org.openax12.core.model.UnitConversion
import org.openax12.core.model.UnitSystem
import java.text.DateFormat
import java.util.Date

/**
 * Last known position of the model: coordinates, when it was seen and how far from home, with
 * the actions to find it again (open in maps, copy, share).
 *
 * @param wallClockMs current wall-clock time, for "seen 3 min ago"
 */
@Composable
internal fun LastPositionScreen(
    state: LastPositionState,
    wallClockMs: Long,
    onBack: () -> Unit,
    onOpenMaps: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onUseAsHome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        TelemetryTopBar(title = stringResource(R.string.telemetry_position_title), onBack = onBack)
        val fix = state.fix
        if (fix == null) {
            AxEmptyState(
                title = stringResource(R.string.telemetry_position_empty),
                message = stringResource(R.string.telemetry_position_empty_message),
                icon = AxIcons.Gps,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        } else {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                PositionCard(state, fix, wallClockMs, Modifier.weight(1f))
                Column(Modifier.width(250.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    AxButton(
                        text = stringResource(R.string.telemetry_position_open_maps),
                        onClick = onOpenMaps,
                        variant = AxButtonVariant.Primary,
                        icon = AxIcons.Gps,
                        modifier = Modifier.fillMaxWidth(),
                        height = 48.dp,
                    )
                    AxButton(
                        text = stringResource(R.string.telemetry_position_copy),
                        onClick = onCopy,
                        icon = AxIcons.Copy,
                        modifier = Modifier.fillMaxWidth(),
                        height = 48.dp,
                    )
                    AxButton(
                        text = stringResource(R.string.telemetry_position_share),
                        onClick = onShare,
                        icon = AxIcons.Share,
                        modifier = Modifier.fillMaxWidth(),
                        height = 48.dp,
                    )
                    AxButton(
                        text = stringResource(R.string.telemetry_position_use_as_home),
                        onClick = onUseAsHome,
                        icon = AxIcons.Flag,
                        modifier = Modifier.fillMaxWidth(),
                        height = 48.dp,
                    )
                    Text(
                        text = stringResource(R.string.telemetry_position_hint),
                        style = AxTheme.typography.label,
                        color = AxTheme.colors.textMuted,
                    )
                }
            }
        }
    }
}

@Composable
private fun PositionCard(state: LastPositionState, fix: GpsFix, wallClockMs: Long, modifier: Modifier) {
    val colors = AxTheme.colors
    val seen = remember(fix.timeMs) {
        if (fix.timeMs > 0) DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(Date(fix.timeMs)) else ""
    }
    AxCard(modifier = modifier.fillMaxHeight(), cornerRadius = AxTheme.shapes.panel, contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.telemetry_position_caption), style = AxTheme.typography.label, color = colors.textMuted)
            Text(GeoMath.formatDecimal(fix.latitude, fix.longitude), style = AxTheme.typography.display, color = colors.text)
            Text(
                text = GeoMath.formatDms(fix.latitude, isLatitude = true) + "  " + GeoMath.formatDms(fix.longitude, isLatitude = false),
                style = AxTheme.typography.body,
                color = colors.textMuted,
            )
            AxDivider(inset = 0.dp, modifier = Modifier.padding(vertical = 6.dp))
            if (fix.timeMs > 0) {
                Fact(
                    label = stringResource(R.string.telemetry_position_seen),
                    value = stringResource(R.string.telemetry_position_seen_value, seen, ageText(Age.of(wallClockMs - fix.timeMs))),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Fact(stringResource(R.string.telemetry_position_altitude), formatUnit(fix.altitude, SensorUnit.METER, state.units), Modifier.weight(1f))
                Fact(stringResource(R.string.telemetry_position_speed), formatUnit(fix.groundSpeed, SensorUnit.KMH, state.units), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Fact(stringResource(R.string.telemetry_position_heading), UnitConversion.formatNumber(fix.heading, 0) + SensorUnit.DEGREE.symbol, Modifier.weight(1f))
                Fact(stringResource(R.string.telemetry_position_satellites), fix.satellites.toString(), Modifier.weight(1f))
            }
            val distance = state.distanceFromHomeM
            val bearing = state.bearingFromHome
            if (distance != null && bearing != null) {
                Fact(
                    label = stringResource(R.string.telemetry_position_from_home),
                    value = stringResource(
                        R.string.telemetry_position_from_home_value,
                        formatDistance(distance, state.units),
                        UnitConversion.formatNumber(bearing, 0),
                    ),
                )
            } else {
                Fact(stringResource(R.string.telemetry_position_from_home), stringResource(R.string.telemetry_position_no_home))
            }
        }
    }
}

@Composable
private fun Fact(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(vertical = 2.dp), horizontalAlignment = Alignment.Start) {
        Text(label, style = AxTheme.typography.label, color = AxTheme.colors.textMuted)
        Text(value, style = AxTheme.typography.bodyStrong, color = AxTheme.colors.text)
    }
}

private fun formatUnit(value: Double, unit: SensorUnit, units: UnitSystem): String {
    val display = UnitConversion.convert(value, unit, units, 0)
    return UnitConversion.formatNumber(display.value, display.precision) + display.unit.symbol
}

/** `152m` / `1.4km` (metric), feet / miles (imperial). */
internal fun formatDistance(meters: Double, units: UnitSystem): String = when {
    units == UnitSystem.METRIC && meters >= 1000 -> UnitConversion.formatNumber(meters / 1000.0, 1) + SensorUnit.KILOMETER.symbol
    units == UnitSystem.IMPERIAL && meters >= METERS_PER_MILE -> UnitConversion.formatNumber(meters / METERS_PER_MILE, 1) + SensorUnit.MILE.symbol
    else -> formatUnit(meters, SensorUnit.METER, units)
}

private const val METERS_PER_MILE = 1609.344
