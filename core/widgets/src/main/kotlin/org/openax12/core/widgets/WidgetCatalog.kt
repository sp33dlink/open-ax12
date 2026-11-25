package org.openax12.core.widgets

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import org.openax12.core.model.LOGICAL_SWITCH_COUNT
import org.openax12.core.model.SensorId
import org.openax12.core.model.Sensors
import org.openax12.core.model.SourceId
import org.openax12.core.model.TIMER_COUNT
import org.openax12.core.model.WidgetConfig
import org.openax12.core.model.WidgetOptions
import org.openax12.core.model.WidgetType

/** What kind of editor an option needs. */
enum class WidgetOptionKind {
    /** Decimal number (in the sensor's native unit for scale ends and thresholds). */
    NUMBER,

    /** Whole number. */
    INTEGER,

    /** `"true"` / `"false"`. */
    BOOLEAN,

    /** Free text. */
    TEXT,

    /** Colour as `#RRGGBB`; absent = theme accent. */
    COLOR,
}

/**
 * One option of a widget type (a key of `WidgetConfig.options`).
 *
 * @property key the option key, see [WidgetOptions]
 * @property default value shown while the option is absent; null = no default (empty field)
 * @property min / [max] allowed range of the stored value for numeric kinds; null = unlimited
 * @property displayOffset added to the stored value for display: options holding a 0-based index
 *   (timer, logical switch) are shown 1-based with an offset of 1
 */
@Immutable
data class WidgetOptionSpec(
    val key: String,
    @param:StringRes val labelRes: Int,
    val kind: WidgetOptionKind,
    val default: String? = null,
    val min: Double? = null,
    val max: Double? = null,
    val displayOffset: Int = 0,
)

/**
 * One sensor binding of a widget type, i.e. one position of `WidgetConfig.sensors`.
 *
 * @property required false if the widget also works without this sensor
 * @property default the sensor a new widget is bound to; null = the user has to choose
 * @property suggestions sensors offered first in the picker
 */
@Immutable
data class WidgetSensorSlot(
    @param:StringRes val labelRes: Int,
    val required: Boolean = true,
    val default: SensorId? = null,
    val suggestions: List<SensorId> = emptyList(),
)

/**
 * Everything the widget editor needs to know about a widget type.
 *
 * @property defaultWidth / [defaultHeight] size of a new widget in cells
 * @property sensorSlots the sensor bindings in the order of `WidgetConfig.sensors`
 * @property options the options this type understands
 * @property preview a configuration that shows the type at its best with [WidgetCatalog.previewDataSource]
 */
@Immutable
data class WidgetTypeInfo(
    val type: WidgetType,
    @param:StringRes val nameRes: Int,
    @param:StringRes val descriptionRes: Int,
    val defaultWidth: Int,
    val defaultHeight: Int,
    val sensorSlots: List<WidgetSensorSlot>,
    val options: List<WidgetOptionSpec>,
    val preview: WidgetConfig,
)

/** Metadata of all widget types, for the widget editor and the "add widget" picker. */
object WidgetCatalog {

    private val optTitle = WidgetOptionSpec(WidgetOptions.SHOW_TITLE, R.string.widget_option_show_title, WidgetOptionKind.BOOLEAN, default = "true")
    private val optColor = WidgetOptionSpec(WidgetOptions.COLOR, R.string.widget_option_color, WidgetOptionKind.COLOR)
    private val optMin = WidgetOptionSpec(WidgetOptions.MIN, R.string.widget_option_min, WidgetOptionKind.NUMBER)
    private val optMax = WidgetOptionSpec(WidgetOptions.MAX, R.string.widget_option_max, WidgetOptionKind.NUMBER)
    private val optWarnBelow = WidgetOptionSpec(WidgetOptions.WARN_BELOW, R.string.widget_option_warn_below, WidgetOptionKind.NUMBER)
    private val optWarnAbove = WidgetOptionSpec(WidgetOptions.WARN_ABOVE, R.string.widget_option_warn_above, WidgetOptionKind.NUMBER)
    private val warnOptions = listOf(optWarnBelow, optWarnAbove)
    private val scaleOptions = listOf(optMin, optMax, optWarnBelow, optWarnAbove)
    private val common = listOf(optColor, optTitle)

    private val numericSuggestions = listOf(
        Sensors.BATTERY_VOLTAGE, Sensors.BARO_ALTITUDE, Sensors.VERTICAL_SPEED, Sensors.AIRSPEED,
        Sensors.GPS_GROUND_SPEED, Sensors.BATTERY_CURRENT, Sensors.TX_BATTERY, Sensors.TX_LINK_QUALITY,
    )

    private fun anySensor(default: SensorId) =
        WidgetSensorSlot(R.string.widget_slot_sensor, default = default, suggestions = numericSuggestions)

    private fun preview(type: WidgetType, sensors: List<SensorId> = emptyList(), options: Map<String, String> = emptyMap(), title: String = "") =
        WidgetConfig(id = "preview-${type.name.lowercase()}", type = type, sensors = sensors, options = options, title = title)

    /** All widget types in the order they are offered. */
    val types: List<WidgetTypeInfo> = listOf(
        WidgetTypeInfo(
            type = WidgetType.VALUE,
            nameRes = R.string.widget_type_value,
            descriptionRes = R.string.widget_desc_value,
            defaultWidth = 1, defaultHeight = 1,
            sensorSlots = listOf(anySensor(Sensors.BARO_ALTITUDE)),
            options = warnOptions + common,
            preview = preview(WidgetType.VALUE, listOf(Sensors.BARO_ALTITUDE)),
        ),
        WidgetTypeInfo(
            type = WidgetType.VALUE_MIN_MAX,
            nameRes = R.string.widget_type_value_min_max,
            descriptionRes = R.string.widget_desc_value_min_max,
            defaultWidth = 2, defaultHeight = 1,
            sensorSlots = listOf(anySensor(Sensors.BARO_ALTITUDE)),
            options = warnOptions + common,
            preview = preview(WidgetType.VALUE_MIN_MAX, listOf(Sensors.BARO_ALTITUDE)),
        ),
        WidgetTypeInfo(
            type = WidgetType.BAR,
            nameRes = R.string.widget_type_bar,
            descriptionRes = R.string.widget_desc_bar,
            defaultWidth = 2, defaultHeight = 1,
            sensorSlots = listOf(anySensor(Sensors.BATTERY_REMAINING)),
            options = scaleOptions + common,
            preview = preview(WidgetType.BAR, listOf(Sensors.BATTERY_REMAINING), options = mapOf(WidgetOptions.WARN_BELOW to "25")),
        ),
        WidgetTypeInfo(
            type = WidgetType.ARC_GAUGE,
            nameRes = R.string.widget_type_arc_gauge,
            descriptionRes = R.string.widget_desc_arc_gauge,
            defaultWidth = 2, defaultHeight = 2,
            sensorSlots = listOf(anySensor(Sensors.AIRSPEED)),
            options = scaleOptions + common,
            preview = preview(
                WidgetType.ARC_GAUGE, listOf(Sensors.AIRSPEED),
                options = mapOf(WidgetOptions.MIN to "0", WidgetOptions.MAX to "120", WidgetOptions.WARN_ABOVE to "100"),
            ),
        ),
        WidgetTypeInfo(
            type = WidgetType.GRAPH,
            nameRes = R.string.widget_type_graph,
            descriptionRes = R.string.widget_desc_graph,
            defaultWidth = 3, defaultHeight = 2,
            sensorSlots = listOf(anySensor(Sensors.BARO_ALTITUDE)),
            options = listOf(
                WidgetOptionSpec(WidgetOptions.HISTORY_SEC, R.string.widget_option_history, WidgetOptionKind.INTEGER, default = "60", min = 5.0, max = 3600.0),
                optMin, optMax,
            ) + common,
            preview = preview(WidgetType.GRAPH, listOf(Sensors.BARO_ALTITUDE)),
        ),
        WidgetTypeInfo(
            type = WidgetType.BATTERY,
            nameRes = R.string.widget_type_battery,
            descriptionRes = R.string.widget_desc_battery,
            defaultWidth = 2, defaultHeight = 1,
            sensorSlots = listOf(
                WidgetSensorSlot(
                    R.string.widget_slot_voltage, default = Sensors.BATTERY_VOLTAGE,
                    suggestions = listOf(Sensors.BATTERY_VOLTAGE, Sensors.TX_RX_BATTERY, Sensors.TX_BATTERY, Sensors.BATTERY_REMAINING),
                ),
                WidgetSensorSlot(R.string.widget_slot_current, required = false, suggestions = listOf(Sensors.BATTERY_CURRENT)),
                WidgetSensorSlot(R.string.widget_slot_capacity, required = false, suggestions = listOf(Sensors.BATTERY_CAPACITY)),
            ),
            options = listOf(
                WidgetOptionSpec(WidgetOptions.CELLS, R.string.widget_option_cells, WidgetOptionKind.INTEGER, default = "0", min = 0.0, max = 14.0),
                optWarnBelow,
            ) + common,
            preview = preview(WidgetType.BATTERY, listOf(Sensors.BATTERY_VOLTAGE, Sensors.BATTERY_CURRENT, Sensors.BATTERY_CAPACITY)),
        ),
        WidgetTypeInfo(
            type = WidgetType.LINK_QUALITY,
            nameRes = R.string.widget_type_link_quality,
            descriptionRes = R.string.widget_desc_link_quality,
            defaultWidth = 1, defaultHeight = 1,
            sensorSlots = listOf(
                WidgetSensorSlot(
                    R.string.widget_slot_link_quality, default = Sensors.TX_LINK_QUALITY,
                    suggestions = listOf(Sensors.TX_LINK_QUALITY, Sensors.LINK_RX_QUALITY, Sensors.LINK_TX_QUALITY),
                ),
                WidgetSensorSlot(
                    R.string.widget_slot_signal, required = false, default = Sensors.TX_RSSI,
                    suggestions = listOf(Sensors.TX_RSSI, Sensors.LINK_RSSI_1, Sensors.LINK_RSSI_2, Sensors.LINK_RX_SNR),
                ),
            ),
            options = listOf(optWarnBelow) + common,
            preview = preview(WidgetType.LINK_QUALITY, listOf(Sensors.TX_LINK_QUALITY, Sensors.TX_RSSI)),
        ),
        WidgetTypeInfo(
            type = WidgetType.TIMER,
            nameRes = R.string.widget_type_timer,
            descriptionRes = R.string.widget_desc_timer,
            defaultWidth = 1, defaultHeight = 1,
            sensorSlots = emptyList(),
            options = listOf(
                WidgetOptionSpec(
                    WidgetOptions.TIMER, R.string.widget_option_timer, WidgetOptionKind.INTEGER,
                    default = "0", min = 0.0, max = (TIMER_COUNT - 1).toDouble(), displayOffset = 1,
                ),
                WidgetOptionSpec(WidgetOptions.COUNTER, R.string.widget_option_counter, WidgetOptionKind.BOOLEAN, default = "false"),
            ) + common,
            preview = preview(WidgetType.TIMER, options = mapOf(WidgetOptions.TIMER to "0")),
        ),
        WidgetTypeInfo(
            type = WidgetType.VARIO,
            nameRes = R.string.widget_type_vario,
            descriptionRes = R.string.widget_desc_vario,
            defaultWidth = 1, defaultHeight = 1,
            sensorSlots = listOf(
                WidgetSensorSlot(
                    R.string.widget_slot_vertical_speed, default = Sensors.VERTICAL_SPEED,
                    suggestions = listOf(Sensors.VERTICAL_SPEED, Sensors.BARO_VERTICAL_SPEED),
                ),
            ),
            options = scaleOptions + common,
            preview = preview(WidgetType.VARIO, listOf(Sensors.VERTICAL_SPEED)),
        ),
        WidgetTypeInfo(
            type = WidgetType.HORIZON,
            nameRes = R.string.widget_type_horizon,
            descriptionRes = R.string.widget_desc_horizon,
            defaultWidth = 2, defaultHeight = 2,
            sensorSlots = listOf(
                WidgetSensorSlot(R.string.widget_slot_pitch, default = Sensors.ATTITUDE_PITCH, suggestions = listOf(Sensors.ATTITUDE_PITCH)),
                WidgetSensorSlot(R.string.widget_slot_roll, default = Sensors.ATTITUDE_ROLL, suggestions = listOf(Sensors.ATTITUDE_ROLL)),
            ),
            options = listOf(optTitle),
            preview = preview(WidgetType.HORIZON, listOf(Sensors.ATTITUDE_PITCH, Sensors.ATTITUDE_ROLL)),
        ),
        WidgetTypeInfo(
            type = WidgetType.GPS,
            nameRes = R.string.widget_type_gps,
            descriptionRes = R.string.widget_desc_gps,
            defaultWidth = 2, defaultHeight = 2,
            sensorSlots = listOf(
                WidgetSensorSlot(R.string.widget_slot_latitude, default = Sensors.GPS_LATITUDE, suggestions = listOf(Sensors.GPS_LATITUDE)),
                WidgetSensorSlot(R.string.widget_slot_longitude, default = Sensors.GPS_LONGITUDE, suggestions = listOf(Sensors.GPS_LONGITUDE)),
            ),
            options = common,
            preview = preview(WidgetType.GPS, listOf(Sensors.GPS_LATITUDE, Sensors.GPS_LONGITUDE)),
        ),
        WidgetTypeInfo(
            type = WidgetType.SWITCH_STATE,
            nameRes = R.string.widget_type_switch_state,
            descriptionRes = R.string.widget_desc_switch_state,
            defaultWidth = 1, defaultHeight = 1,
            sensorSlots = listOf(
                WidgetSensorSlot(
                    R.string.widget_slot_switch, required = false, default = SensorId.input(SourceId.SA),
                    suggestions = (0 until 8).map { SensorId.input(SourceId.switch('A' + it)) },
                ),
            ),
            options = listOf(
                WidgetOptionSpec(
                    WidgetOptions.LOGICAL_SWITCH, R.string.widget_option_logical_switch, WidgetOptionKind.INTEGER,
                    min = 0.0, max = (LOGICAL_SWITCH_COUNT - 1).toDouble(), displayOffset = 1,
                ),
            ) + common,
            preview = preview(WidgetType.SWITCH_STATE, listOf(SensorId.input(SourceId.SA))),
        ),
        WidgetTypeInfo(
            type = WidgetType.FLIGHT_MODE,
            nameRes = R.string.widget_type_flight_mode,
            descriptionRes = R.string.widget_desc_flight_mode,
            defaultWidth = 2, defaultHeight = 1,
            sensorSlots = listOf(
                WidgetSensorSlot(R.string.widget_slot_flight_mode, required = false, suggestions = listOf(Sensors.FC_FLIGHT_MODE)),
            ),
            options = common,
            preview = preview(WidgetType.FLIGHT_MODE),
        ),
        WidgetTypeInfo(
            type = WidgetType.TEXT,
            nameRes = R.string.widget_type_text,
            descriptionRes = R.string.widget_desc_text,
            defaultWidth = 2, defaultHeight = 1,
            sensorSlots = emptyList(),
            options = listOf(WidgetOptionSpec(WidgetOptions.TEXT, R.string.widget_option_text, WidgetOptionKind.TEXT, default = "")) + listOf(optColor),
            preview = preview(WidgetType.TEXT, options = mapOf(WidgetOptions.TEXT to "Launch height {Alt} m")),
        ),
    )

    private val byType: Map<WidgetType, WidgetTypeInfo> = types.associateBy { it.type }

    /** The metadata of [type]. */
    fun info(type: WidgetType): WidgetTypeInfo = byType.getValue(type)

    /**
     * A new widget of [type] at cell ([x], [y]) with the type's default size, its default sensor
     * bindings and the defaults of its required options.
     *
     * @param id stable id for the widget (`newItemId()`)
     */
    fun create(type: WidgetType, id: String, x: Int = 0, y: Int = 0): WidgetConfig {
        val info = info(type)
        // Trailing slots without a default are left unbound.
        val sensors = info.sensorSlots.map { it.default }.dropLastWhile { it == null }.filterNotNull()
        val options = when (type) {
            WidgetType.TIMER -> mapOf(WidgetOptions.TIMER to "0")
            WidgetType.TEXT -> mapOf(WidgetOptions.TEXT to "")
            else -> emptyMap()
        }
        return WidgetConfig(id = id, type = type, x = x, y = y, w = info.defaultWidth, h = info.defaultHeight, sensors = sensors, options = options)
    }

    /** Fake data for [WidgetTypeInfo.preview]: one shared, never changing demo flight. */
    val previewDataSource: WidgetDataSource by lazy { FakeWidgetDataSource.demo() }
}

/**
 * A preview of a widget type with fake data on a card, for the widget editor's type picker.
 * Give it the size (or aspect) of the type's default cell span.
 */
@Composable
fun WidgetTypePreview(
    type: WidgetType,
    modifier: Modifier = Modifier,
    style: WidgetCardStyle = WidgetCardStyle.RAISED,
) {
    WidgetCard(WidgetCatalog.info(type).preview, modifier, style, dataSource = WidgetCatalog.previewDataSource)
}
