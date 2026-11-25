package org.openax12.core.model

import kotlinx.serialization.Serializable

/** Telemetry widget types (SPEC section 5). Renderers live in `:core:widgets`. */
@Serializable
enum class WidgetType {
    /** Sensor value with name and unit. */
    VALUE,

    /** Sensor value plus min / max since reset. */
    VALUE_MIN_MAX,

    /** Horizontal bar between [WidgetOptions.MIN] and [WidgetOptions.MAX]. */
    BAR,

    /** Arc gauge between min and max. */
    ARC_GAUGE,

    /** Sparkline / graph of the sensor history. */
    GRAPH,

    /** Battery with per-cell voltage ([WidgetOptions.CELLS]). */
    BATTERY,

    /** Link quality / RSSI. */
    LINK_QUALITY,

    /** Firmware timer ([WidgetOptions.TIMER]). */
    TIMER,

    /** Vario indicator (vertical speed). */
    VARIO,

    /** Artificial horizon (pitch + roll sensors). */
    HORIZON,

    /** GPS: coordinates, distance and bearing to home, open in maps. */
    GPS,

    /** State of a switch or logical switch. */
    SWITCH_STATE,

    /** Active flight mode. */
    FLIGHT_MODE,

    /** Static text label ([WidgetOptions.TEXT], may be a template). */
    TEXT,
}

/** Keys of [WidgetConfig.options]. Values are strings; use the typed getters on [WidgetConfig]. */
object WidgetOptions {
    /** Lower end of a bar / gauge / graph scale (decimal number). */
    const val MIN = "min"

    /** Upper end of a bar / gauge / graph scale (decimal number). */
    const val MAX = "max"

    /** Number of battery cells; 0 or absent = auto-detect. */
    const val CELLS = "cells"

    /** 0-based timer index of a TIMER widget. */
    const val TIMER = "timer"

    /** `"true"` if the TIMER widget shows a counter instead of a timer. */
    const val COUNTER = "counter"

    /** Text (template) of a TEXT widget. */
    const val TEXT = "text"

    /** Seconds of history shown by a GRAPH widget. */
    const val HISTORY_SEC = "historySec"

    /** Accent colour as `#RRGGBB`; absent = theme default. */
    const val COLOR = "color"

    /** Value below which the widget turns to the warning colour (decimal number). */
    const val WARN_BELOW = "warnBelow"

    /** Value above which the widget turns to the warning colour (decimal number). */
    const val WARN_ABOVE = "warnAbove"

    /** 0-based logical-switch index of a SWITCH_STATE widget (alternative to a sensor binding). */
    const val LOGICAL_SWITCH = "logicalSwitch"

    /** `"false"` to hide the title line. */
    const val SHOW_TITLE = "showTitle"
}

/**
 * One widget on a telemetry page.
 *
 * @property id stable id (see [newItemId])
 * @property x / [y] top-left cell on the page grid (0-based)
 * @property w / [h] size in cells (≥ 1)
 * @property sensors sensor bindings in the order the widget type expects them: one sensor for
 *   most types; HORIZON = pitch, roll; GPS = latitude, longitude; BATTERY = voltage, then
 *   optional current / capacity
 * @property title custom title; empty = the first sensor's name
 * @property options type-specific options, keys in [WidgetOptions]
 * @property pinned shown in the home centre card when the card is flipped to widgets
 * @property pinOrder order among the pinned widgets
 */
@Serializable
data class WidgetConfig(
    val id: String,
    val type: WidgetType = WidgetType.VALUE,
    val x: Int = 0,
    val y: Int = 0,
    val w: Int = 1,
    val h: Int = 1,
    val sensors: List<SensorId> = emptyList(),
    val title: String = "",
    val options: Map<String, String> = emptyMap(),
    val pinned: Boolean = false,
    val pinOrder: Int = 0,
) {
    /** First bound sensor, the one most widget types display. */
    val sensor: SensorId? get() = sensors.firstOrNull()

    /** Raw value of option [key], or null. */
    fun option(key: String): String? = options[key]
    /** Option [key] as a number, or null. */
    fun optionDouble(key: String): Double? = options[key]?.toDoubleOrNull()
    /** Option [key] as an integer, or null. */
    fun optionInt(key: String): Int? = options[key]?.toIntOrNull()
    /** Option [key] as a Boolean, or [default]. */
    fun optionBoolean(key: String, default: Boolean = false): Boolean = options[key]?.toBooleanStrictOrNull() ?: default

    /** Copy with option [key] set to [value] (null removes it). */
    fun withOption(key: String, value: String?): WidgetConfig =
        copy(options = if (value == null) options - key else options + (key to value))

    /** True if this widget's cells overlap [other]'s. */
    fun overlaps(other: WidgetConfig): Boolean =
        x < other.x + other.w && other.x < x + w && y < other.y + other.h && other.y < y + h
}

/**
 * One telemetry page: a grid of widgets.
 *
 * @property id stable id (see [newItemId])
 */
@Serializable
data class TelemetryPage(
    val id: String,
    val name: String = "",
    val columns: Int = DEFAULT_COLUMNS,
    val rows: Int = DEFAULT_ROWS,
    val widgets: List<WidgetConfig> = emptyList(),
) {
    companion object {
        /** Grid columns of a new page. */
        const val DEFAULT_COLUMNS = 6
        /** Grid rows of a new page. */
        const val DEFAULT_ROWS = 4
    }
}

/**
 * Telemetry layout of a model.
 *
 * @property pinnedColumns / [pinnedRows] grid of the home centre card when it shows the pinned widgets
 */
@Serializable
data class TelemetryLayout(
    val pages: List<TelemetryPage> = emptyList(),
    val pinnedColumns: Int = 3,
    val pinnedRows: Int = 2,
) {
    /** All pinned widgets of all pages in display order. */
    val pinnedWidgets: List<WidgetConfig>
        get() = pages.flatMap { it.widgets }.filter { it.pinned }.sortedBy { it.pinOrder }

    /** The page with [id], or null. */
    fun page(id: String): TelemetryPage? = pages.firstOrNull { it.id == id }

    /** The widget with [id] on any page, or null. */
    fun widget(id: String): WidgetConfig? = pages.firstNotNullOfOrNull { page -> page.widgets.firstOrNull { it.id == id } }
}

/** Per-model override of how a sensor is named and displayed. Null fields keep the default. */
@Serializable
data class SensorOverride(
    val name: String? = null,
    val unit: SensorUnit? = null,
    val precision: Int? = null,
    val hidden: Boolean = false,
)

/**
 * What happens when an app-side alarm fires.
 *
 * @property voiceText text template to speak; empty = no speech
 * @property beep beep pattern; null = none
 * @property haptic vibration pattern; null = none
 * @property popup show a popup over the current screen
 * @property soundFile user sound file to play; empty = none
 */
@Serializable
data class AlarmOutputs(
    val voiceText: String = "",
    val beep: BeepPattern? = BeepPattern.WARNING,
    val haptic: HapticPattern? = null,
    val popup: Boolean = false,
    val soundFile: String = "",
)

/**
 * An app-side threshold alarm on a sensor.
 *
 * The alarm is raised when the comparison holds for [delaySec]; it is cleared when the value
 * moves back past the threshold by [hysteresis]. While raised it repeats every [repeatSec].
 *
 * @property id stable id (see [newItemId])
 * @property op [CompareOp.LESS] / [CompareOp.GREATER] / [CompareOp.EQUAL]; DISABLED never fires
 * @property repeatSec 0 = announce once per activation
 * @property onlyWhenLinked suppress the alarm while telemetry is lost
 */
@Serializable
data class SensorAlarm(
    val id: String,
    val name: String = "",
    val enabled: Boolean = true,
    val sensor: SensorId,
    val op: CompareOp = CompareOp.LESS,
    val threshold: Double = 0.0,
    val hysteresis: Double = 0.0,
    val delaySec: Double = 0.0,
    val repeatSec: Int = 0,
    val onlyWhenLinked: Boolean = true,
    val outputs: AlarmOutputs = AlarmOutputs(),
    val priority: SpeechPriority = SpeechPriority.ALARM,
)
