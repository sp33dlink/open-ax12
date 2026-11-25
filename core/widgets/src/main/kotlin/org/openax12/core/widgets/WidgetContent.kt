package org.openax12.core.widgets

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.openax12.core.designsystem.axRaised
import org.openax12.core.designsystem.axSunken
import org.openax12.core.model.WidgetConfig
import org.openax12.core.model.WidgetOptions
import org.openax12.core.model.WidgetType

/**
 * One widget of any [WidgetType], drawn into the space it is given (no card behind it; see
 * [WidgetCard]). Text and graphics scale with the size, from a single small grid cell upwards.
 *
 * The widget observes its values itself through [dataSource] and redraws on a new value without
 * recomposing. Missing sensors show `--`, stale values are dimmed and lost sensors keep their
 * last value, dimmed and marked.
 *
 * @param config what to show; grid position and size in it are ignored here
 * @param dataSource where the values come from; by default [LocalWidgetDataSource]
 */
@Composable
fun WidgetContent(
    config: WidgetConfig,
    modifier: Modifier = Modifier,
    dataSource: WidgetDataSource = LocalWidgetDataSource.current,
) {
    // A widget whose type changes starts from scratch.
    key(config.type) {
        when (config.type) {
            WidgetType.VALUE -> ValueWidget(config, dataSource, showMinMax = false, modifier)
            WidgetType.VALUE_MIN_MAX -> ValueWidget(config, dataSource, showMinMax = true, modifier)
            WidgetType.BAR -> BarWidget(config, dataSource, modifier)
            WidgetType.ARC_GAUGE -> ArcGaugeWidget(config, dataSource, modifier)
            WidgetType.GRAPH -> GraphWidget(config, dataSource, modifier)
            WidgetType.BATTERY -> BatteryWidget(config, dataSource, modifier)
            WidgetType.LINK_QUALITY -> LinkQualityWidget(config, dataSource, modifier)
            WidgetType.TIMER -> TimerWidget(config, dataSource, modifier)
            WidgetType.VARIO -> VarioWidget(config, dataSource, modifier)
            WidgetType.HORIZON -> HorizonWidget(config, dataSource, modifier)
            WidgetType.GPS -> GpsWidget(config, dataSource, modifier)
            WidgetType.SWITCH_STATE -> SwitchStateWidget(config, dataSource, modifier)
            WidgetType.FLIGHT_MODE -> FlightModeWidget(config, dataSource, modifier)
            WidgetType.TEXT -> TextWidget(config, dataSource, modifier)
        }
    }
}

/** How the card behind a widget looks. */
enum class WidgetCardStyle {
    /**
     * A raised card of the design system (soft double shadow in the light theme), for widgets
     * directly on the screen background. The shadow needs a few dp of room around the card.
     */
    RAISED,

    /** A sunken well of the design system, for widgets inside another card (home centre card). */
    FLAT,

    /** No background at all. */
    NONE,
}

/** Sizes shared by the widget cards and grids. */
object WidgetDefaults {
    /** Gap between grid cells. */
    val GridSpacing: Dp = 8.dp

    /** Corner radius of a widget card. */
    val CardCornerRadius: Dp = 14.dp

    /** Smallest cell edge at which a widget is still readable. */
    val MinCellSize: Dp = 60.dp
}

/**
 * A widget on its card: [WidgetContent] over a background in the given [style].
 *
 * @param selected draws an accent frame (the widget being edited)
 * @param outlined draws a thin frame (edit mode)
 */
@Composable
fun WidgetCard(
    config: WidgetConfig,
    modifier: Modifier = Modifier,
    style: WidgetCardStyle = WidgetCardStyle.RAISED,
    selected: Boolean = false,
    outlined: Boolean = false,
    dataSource: WidgetDataSource = LocalWidgetDataSource.current,
) {
    val palette = widgetPalette()
    val shape = RoundedCornerShape(WidgetDefaults.CardCornerRadius)
    val frameModifier = when (style) {
        WidgetCardStyle.RAISED -> modifier.axRaised(cornerRadius = WidgetDefaults.CardCornerRadius, elevation = 3.dp, color = palette.card)
        WidgetCardStyle.FLAT -> modifier.axSunken(cornerRadius = WidgetDefaults.CardCornerRadius, depth = 1.5.dp, color = palette.cardFlat)
        WidgetCardStyle.NONE -> modifier
    }
    var frame = frameModifier
    if (selected) {
        frame = frame.border(2.dp, accentOf(config.option(WidgetOptions.COLOR), palette.accent), shape)
    } else if (outlined) {
        frame = frame.border(1.dp, palette.accent.copy(alpha = 0.45f), shape)
    }
    Box(frame) {
        WidgetContent(config, Modifier.fillMaxSize(), dataSource)
    }
}
