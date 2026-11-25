package org.openax12.core.widgets

import android.os.SystemClock
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import org.openax12.core.model.LogicalSwitchStates
import org.openax12.core.model.SensorId
import org.openax12.core.model.SensorInfo
import org.openax12.core.model.SensorValue
import org.openax12.core.model.TimerState
import org.openax12.core.model.UnitSystem

/**
 * A value that changes over time, as the widgets consume it: the value right now ([initial], used
 * for the first frame so a widget never starts empty) plus a [flow] of later values.
 *
 * Build one from a `StateFlow` with [of]; the flow should be conflated and emit only on change.
 */
@Stable
class WidgetStream<out T>(val initial: () -> T, val flow: Flow<T>) {
    companion object {
        /** The stream of a [StateFlow]. */
        fun <T> of(state: StateFlow<T>): WidgetStream<T> = WidgetStream({ state.value }, state)

        /** The stream of a [StateFlow] mapped through [transform]; emits only when the result changes. */
        fun <S, T> of(state: StateFlow<S>, transform: (S) -> T): WidgetStream<T> =
            WidgetStream({ transform(state.value) }, state.map { transform(it) }.distinctUntilChanged())

        /** A stream that never changes. */
        fun <T> constant(value: T): WidgetStream<T> = WidgetStream({ value }, emptyFlow())
    }
}

/** One history sample of a sensor for graphs. [timestampMs] is monotonic, same base as [WidgetDataSource.nowMs]. */
data class WidgetSample(val timestampMs: Long, val value: Double)

/**
 * The flight mode a FLIGHT_MODE widget shows.
 *
 * @property index 0-based index (0 = default mode)
 * @property name its name; empty when the model has no flight modes
 */
data class WidgetFlightMode(val index: Int = 0, val name: String = "")

/**
 * Everything the telemetry widgets need to know about the outside world. The widgets module has
 * no repositories; whoever shows widgets adapts the app graph to this interface (telemetry
 * repository, `Radio.live`, runtime engine, settings) and provides it through
 * [LocalWidgetDataSource] or the `dataSource` parameter of the grids.
 *
 * All members are called on the main thread and must be cheap (map lookups). Sensor values are in
 * the sensor's native (metric) unit; the widgets convert to [units] for display.
 */
@Stable
interface WidgetDataSource {

    /**
     * The value of one sensor; null while the sensor is unknown. Works for every namespace,
     * including the live ones (`in.*`, `ch.*`).
     */
    fun sensor(id: SensorId): WidgetStream<SensorValue?>

    /** Metadata (display name) of a sensor; null if unknown. Used for the default widget title. */
    fun sensorInfo(id: SensorId): SensorInfo?

    /**
     * Recent samples of a sensor for a GRAPH widget, oldest first, in the sensor's native unit.
     * Polled about twice per second while a graph is visible.
     *
     * @param windowSec how far back to go
     */
    fun history(id: SensorId, windowSec: Int): List<WidgetSample>

    /** State of timer (or, with [counter], counter) [index] (0-based); null if it does not exist. */
    fun timer(index: Int, counter: Boolean): WidgetStream<TimerState?>

    /** The active flight mode. */
    val flightMode: WidgetStream<WidgetFlightMode>

    /** States of the logical switches L1..L32. */
    val logicalSwitches: WidgetStream<LogicalSwitchStates>

    /** Custom name of logical switch [index] (0-based); null or empty = `L<n>`. */
    fun logicalSwitchName(index: Int): String? = null

    /** The unit system values are displayed in. */
    val units: WidgetStream<UnitSystem>

    /**
     * Renders the text of a TEXT widget, which may be a template (`{RxBt} V`). Polled about twice
     * per second. The default returns the text unchanged.
     */
    fun resolveText(template: String): String = template

    /** Monotonic clock on the same base as `SensorValue.timestampMs` (`elapsedRealtime`). */
    fun nowMs(): Long = SystemClock.elapsedRealtime()

    /**
     * Age after which a telemetry value (`crsf.*`, `sdk.*`) that is not yet flagged as lost is
     * drawn dimmed. 0 disables the stale state.
     */
    val staleAfterMs: Long get() = DEFAULT_STALE_AFTER_MS

    companion object {
        /** Default of [staleAfterMs]. */
        const val DEFAULT_STALE_AFTER_MS: Long = 3_000
    }
}

/** A data source without any data: every widget renders its "missing" state. */
object EmptyWidgetDataSource : WidgetDataSource {
    private val noSensor = WidgetStream.constant<SensorValue?>(null)
    private val noTimer = WidgetStream.constant<TimerState?>(null)

    override fun sensor(id: SensorId): WidgetStream<SensorValue?> = noSensor
    override fun sensorInfo(id: SensorId): SensorInfo? = null
    override fun history(id: SensorId, windowSec: Int): List<WidgetSample> = emptyList()
    override fun timer(index: Int, counter: Boolean): WidgetStream<TimerState?> = noTimer
    override val flightMode: WidgetStream<WidgetFlightMode> = WidgetStream.constant(WidgetFlightMode())
    override val logicalSwitches: WidgetStream<LogicalSwitchStates> = WidgetStream.constant(LogicalSwitchStates.NONE)
    override val units: WidgetStream<UnitSystem> = WidgetStream.constant(UnitSystem.METRIC)
    override fun nowMs(): Long = 0
}

/**
 * The data source [WidgetGrid], [PinnedWidgetGrid] and [WidgetContent] use when none is passed.
 * The app shell provides the adapter over the app graph; the default is [EmptyWidgetDataSource].
 */
val LocalWidgetDataSource = staticCompositionLocalOf<WidgetDataSource> { EmptyWidgetDataSource }
