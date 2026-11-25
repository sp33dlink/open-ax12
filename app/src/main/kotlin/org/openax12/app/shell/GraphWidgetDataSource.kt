package org.openax12.app.shell

import org.openax12.core.engine.TemplateRenderer
import org.openax12.core.model.LogicalSwitchStates
import org.openax12.core.model.SensorId
import org.openax12.core.model.SensorInfo
import org.openax12.core.model.SensorValue
import org.openax12.core.model.TimerState
import org.openax12.core.model.UnitSystem
import org.openax12.core.runtime.AppGraph
import org.openax12.core.widgets.WidgetDataSource
import org.openax12.core.widgets.WidgetFlightMode
import org.openax12.core.widgets.WidgetSample
import org.openax12.core.widgets.WidgetStream

/**
 * Adapts the [AppGraph] to the [WidgetDataSource] the telemetry widgets read (provided through
 * `LocalWidgetDataSource` by the shell): sensors from the telemetry repository, timers from the
 * live frame, flight mode and logical switches from the runtime engine, units from the settings.
 *
 * Called on the main thread only (the widgets' contract); the stream cache is therefore a plain map.
 */
internal class GraphWidgetDataSource(private val graph: AppGraph) : WidgetDataSource {

    private val sensorStreams = HashMap<SensorId, WidgetStream<SensorValue?>>()

    override fun sensor(id: SensorId): WidgetStream<SensorValue?> = sensorStreams.getOrPut(id) {
        WidgetStream(initial = { graph.telemetry.value(id) }, flow = graph.telemetry.sensor(id))
    }

    override fun sensorInfo(id: SensorId): SensorInfo? = graph.telemetry.info(id)

    override fun history(id: SensorId, windowSec: Int): List<WidgetSample> =
        graph.telemetry.history(id, windowSec).map { WidgetSample(it.timestampMs, it.value) }

    override fun timer(index: Int, counter: Boolean): WidgetStream<TimerState?> =
        WidgetStream.of(graph.radio.live) { live -> if (counter) live.counter(index) else live.timer(index) }

    override val flightMode: WidgetStream<WidgetFlightMode> =
        WidgetStream.of(graph.runtime.flightMode) { WidgetFlightMode(it.index, it.name) }

    override val logicalSwitches: WidgetStream<LogicalSwitchStates> = WidgetStream.of(graph.runtime.logicalSwitches)

    override fun logicalSwitchName(index: Int): String? =
        graph.models.currentExtras.value.logicalSwitch(index).name.takeIf { it.isNotBlank() }

    override val units: WidgetStream<UnitSystem> = WidgetStream.of(graph.settings.settings) { it.units }

    override fun resolveText(template: String): String =
        TemplateRenderer.render(template, graph.runtime.valueResolver())
}
