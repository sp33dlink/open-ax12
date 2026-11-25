package org.openax12.feature.telemetry

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.openax12.core.model.ModelExtras
import org.openax12.core.model.Sensors
import org.openax12.core.model.TelemetryLayout
import org.openax12.core.model.TelemetryPage
import org.openax12.core.model.WidgetConfig

/** In-memory [ExtrasStore]. */
internal class FakeExtrasStore(initial: ModelExtras = ModelExtras()) : ExtrasStore {
    private val state = MutableStateFlow(initial)
    override val extras: StateFlow<ModelExtras> get() = state

    override suspend fun update(transform: (ModelExtras) -> ModelExtras) {
        state.value = transform(state.value)
    }

    val layout: TelemetryLayout get() = state.value.telemetry
}

/** Runs `viewModelScope` coroutines eagerly. */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(UnconfinedTestDispatcher())
    override fun finished(description: Description) = Dispatchers.resetMain()
}

internal fun widget(id: String, x: Int, y: Int, w: Int = 1, h: Int = 1, pinned: Boolean = false, pinOrder: Int = 0) =
    WidgetConfig(id = id, x = x, y = y, w = w, h = h, sensors = listOf(Sensors.BARO_ALTITUDE), pinned = pinned, pinOrder = pinOrder)

internal fun page(id: String, vararg widgets: WidgetConfig, columns: Int = 6, rows: Int = 4) =
    TelemetryPage(id = id, columns = columns, rows = rows, widgets = widgets.toList())

internal fun extrasWith(vararg pages: TelemetryPage) = ModelExtras(telemetry = TelemetryLayout(pages = pages.toList()))
