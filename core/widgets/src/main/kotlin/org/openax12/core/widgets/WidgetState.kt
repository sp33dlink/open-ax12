package org.openax12.core.widgets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
import org.openax12.core.model.SensorId
import org.openax12.core.model.SensorValue
import org.openax12.core.model.UnitSystem
import org.openax12.core.model.WidgetConfig

/*
 * How the widgets observe data: every stream is collected into a Compose State that is read only
 * inside the draw lambda of the widget's canvas. A new value therefore redraws that one widget;
 * nothing is recomposed, neither the widget nor the grid.
 */

/** How often polled data (graph history, text templates) and the stale check are refreshed. */
internal const val POLL_PERIOD_MS: Long = 500

/**
 * Collects the stream into a [State]. The first frame already shows [WidgetStream.initial], which
 * also makes the widgets renderable in previews and screenshot tests.
 */
@Composable
internal fun <T> WidgetStream<T>.collectAsWidgetState(): State<T> {
    val stream = this
    val state = remember(stream) { mutableStateOf(stream.initial()) }
    LaunchedEffect(stream) { stream.flow.collect { state.value = it } }
    return state
}

/** The data source's clock as a [State], advanced every [periodMs]. */
@Composable
internal fun rememberNow(source: WidgetDataSource, periodMs: Long = POLL_PERIOD_MS): State<Long> {
    val now = remember(source) { mutableLongStateOf(source.nowMs()) }
    LaunchedEffect(source, periodMs) {
        while (true) {
            delay(periodMs)
            now.longValue = source.nowMs()
        }
    }
    return now
}

/** The result of [read], refreshed every [periodMs] (and whenever a key changes). */
@Composable
internal fun <T> rememberPolled(vararg keys: Any?, periodMs: Long = POLL_PERIOD_MS, read: () -> T): State<T> {
    val state = remember(*keys) { mutableStateOf(read()) }
    LaunchedEffect(*keys) {
        while (true) {
            delay(periodMs)
            state.value = read()
        }
    }
    return state
}

/**
 * One observed sensor of a widget.
 *
 * @property id the bound sensor; null if the widget has none in this slot
 * @property raw the sensor value as delivered (native unit)
 * @property reading the value prepared for display
 * @property units the display unit system
 */
@Stable
internal class SensorBinding(
    val id: SensorId?,
    val raw: State<SensorValue?>,
    val reading: State<Reading>,
    val units: State<UnitSystem>,
)

private val NO_SENSOR = WidgetStream.constant<SensorValue?>(null)

/** Observes sensor [id] of [source]. A null [id] yields a permanently missing reading. */
@Composable
internal fun rememberSensor(source: WidgetDataSource, id: SensorId?): SensorBinding {
    val stream = remember(source, id) { if (id == null) NO_SENSOR else source.sensor(id) }
    val raw = stream.collectAsWidgetState()
    val units = remember(source) { source.units }.collectAsWidgetState()
    // Only receiver telemetry can go stale; everything else needs no clock.
    val canGoStale = id != null && source.staleAfterMs > 0 &&
        (id.namespace == SensorId.NS_CRSF || id.namespace == SensorId.NS_SDK)
    val now: State<Long> = if (canGoStale) rememberNow(source) else remember(source) { mutableLongStateOf(source.nowMs()) }
    val reading = remember(source, id, raw, units, now) {
        derivedStateOf { Readings.of(id, raw.value, units.value, now.value, source.staleAfterMs) }
    }
    return remember(id, raw, reading, units) { SensorBinding(id, raw, reading, units) }
}

/** The reading of [binding] together with the scale configured in [config]. */
@Composable
internal fun rememberGauge(config: WidgetConfig, binding: SensorBinding): State<Gauge> =
    remember(config, binding) {
        derivedStateOf {
            val reading = binding.reading.value
            Gauge(reading, Scale.resolve(config, binding.id, binding.raw.value, reading, binding.units.value))
        }
    }

/**
 * The title line of a widget: null if hidden, otherwise the custom title, the name of the first
 * sensor, or [fallback].
 */
internal fun widgetTitle(config: WidgetConfig, source: WidgetDataSource, sensor: SensorId?, fallback: String): String? {
    if (!config.optionBoolean(org.openax12.core.model.WidgetOptions.SHOW_TITLE, default = true)) return null
    if (config.title.isNotBlank()) return config.title
    val name = sensor?.let { source.sensorInfo(it)?.name }
    return when {
        !name.isNullOrBlank() -> name
        fallback.isNotBlank() -> fallback
        sensor != null -> sensor.localName.ifEmpty { sensor.key }
        else -> ""
    }
}
