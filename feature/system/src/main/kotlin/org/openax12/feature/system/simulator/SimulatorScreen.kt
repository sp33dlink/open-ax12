package org.openax12.feature.system.simulator

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.openax12.core.designsystem.AxButton
import org.openax12.core.designsystem.AxButtonVariant
import org.openax12.core.designsystem.AxCard
import org.openax12.core.designsystem.AxDivider
import org.openax12.core.designsystem.AxEmptyState
import org.openax12.core.designsystem.AxIcons
import org.openax12.core.designsystem.AxSectionHeader
import org.openax12.core.designsystem.AxSegmented
import org.openax12.core.designsystem.AxSettingRow
import org.openax12.core.designsystem.AxSlider
import org.openax12.core.designsystem.AxSwitch
import org.openax12.core.designsystem.AxTheme
import org.openax12.core.designsystem.NumberField
import org.openax12.core.designsystem.SetupTabItem
import org.openax12.core.designsystem.TextEntryDialog
import org.openax12.core.designsystem.axSunken
import org.openax12.core.model.LiveSnapshot
import org.openax12.core.model.SensorId
import org.openax12.core.model.SourceId
import org.openax12.core.model.SwitchPosition
import org.openax12.core.navigation.LocalNavigator
import org.openax12.core.radio.SimulatedFault
import org.openax12.core.runtime.LocalAppGraph
import org.openax12.feature.system.R
import org.openax12.feature.system.common.ChromeButton
import org.openax12.feature.system.common.NoticeBox
import org.openax12.feature.system.common.NoticeKind
import org.openax12.feature.system.common.PanelColumn
import org.openax12.feature.system.common.SystemBar
import org.openax12.feature.system.common.graphViewModel
import kotlin.math.roundToInt

private const val DOT_INSET = 12

internal object SimTab {
    const val INPUTS = "inputs"
    const val TELEMETRY = "telemetry"
    const val LINK = "link"
}

/** Stateful entry content of `SimulatorPanel`. */
@Composable
internal fun SimulatorRoute() {
    val graph = LocalAppGraph.current
    val viewModel = graphViewModel(graph) { SimulatorViewModel(it.radio, it.configSession) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val live = graph.radio.live.collectAsStateWithLifecycle()
    SimulatorScreen(
        state = state,
        live = { live.value },
        onBack = { navigator.back() },
        onStick = viewModel::setStick,
        onPot = viewModel::setPot,
        onSwitch = viewModel::setSwitch,
        onTelemetry = viewModel::setTelemetry,
        onFlightModeText = viewModel::setFlightModeText,
        onDemoMotion = viewModel::setDemoMotion,
        onLinkUp = viewModel::setLinkUp,
        onFault = viewModel::inject,
        onReset = viewModel::reset,
    )
}

/** The simulator panel: inputs, telemetry values, link and faults. Stateless. */
@Composable
internal fun SimulatorScreen(
    state: SimulatorUiState,
    live: () -> LiveSnapshot,
    onBack: () -> Unit,
    onStick: (SourceId, Int) -> Unit,
    onPot: (SourceId, Int) -> Unit,
    onSwitch: (SourceId, SwitchPosition) -> Unit,
    onTelemetry: (SensorId, Double) -> Unit,
    onFlightModeText: (String) -> Unit,
    onDemoMotion: (Boolean) -> Unit,
    onLinkUp: (Boolean) -> Unit,
    onFault: (SimulatedFault) -> Unit,
    onReset: () -> Unit,
    initialTab: String = SimTab.INPUTS,
) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    Column(modifier = Modifier.fillMaxSize()) {
        SystemBar(
            title = stringResource(R.string.sim_title),
            onBack = onBack,
            tabs = if (state.available) {
                listOf(
                    SetupTabItem(SimTab.INPUTS, stringResource(R.string.sim_tab_inputs)),
                    SetupTabItem(SimTab.TELEMETRY, stringResource(R.string.sim_tab_telemetry)),
                    SetupTabItem(SimTab.LINK, stringResource(R.string.sim_tab_link)),
                )
            } else {
                emptyList()
            },
            selectedTabId = tab,
            onTabSelected = { tab = it },
            trailing = {
                if (state.available) {
                    ChromeButton(
                        text = stringResource(R.string.sim_reset),
                        onClick = onReset,
                        icon = AxIcons.Reset,
                    )
                }
            },
        )
        if (!state.available) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                AxEmptyState(
                    title = stringResource(R.string.sim_unavailable),
                    message = stringResource(R.string.sim_unavailable_hint),
                    icon = AxIcons.Transmitter,
                )
            }
        } else {
            when (tab) {
                SimTab.TELEMETRY -> TelemetryTab(state, onTelemetry, onFlightModeText)
                SimTab.LINK -> LinkTab(state, onDemoMotion, onLinkUp, onTelemetry, onFault)
                else -> InputsTab(state, live, onStick, onPot, onSwitch)
            }
        }
    }
}

// -- Inputs ----------------------------------------------------------------------------------

@Composable
private fun InputsTab(
    state: SimulatorUiState,
    live: () -> LiveSnapshot,
    onStick: (SourceId, Int) -> Unit,
    onPot: (SourceId, Int) -> Unit,
    onSwitch: (SourceId, SwitchPosition) -> Unit,
) {
    val mode = state.stickMode
    Row(
        modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(modifier = Modifier.width(366.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DragPad(
                    x = { live().logic(mode.leftHorizontal).toFloat() },
                    y = { live().logic(mode.leftVertical).toFloat() },
                    onChange = { x, y ->
                        onStick(mode.leftHorizontal, x.roundToInt())
                        onStick(mode.leftVertical, y.roundToInt())
                    },
                    onRelease = {
                        if (springsBack(mode.leftHorizontal)) onStick(mode.leftHorizontal, 0)
                        if (springsBack(mode.leftVertical)) onStick(mode.leftVertical, 0)
                    },
                    modifier = Modifier.weight(1f),
                )
                DragPad(
                    x = { live().logic(mode.rightHorizontal).toFloat() },
                    y = { live().logic(mode.rightVertical).toFloat() },
                    onChange = { x, y ->
                        onStick(mode.rightHorizontal, x.roundToInt())
                        onStick(mode.rightVertical, y.roundToInt())
                    },
                    onRelease = {
                        if (springsBack(mode.rightHorizontal)) onStick(mode.rightHorizontal, 0)
                        if (springsBack(mode.rightVertical)) onStick(mode.rightVertical, 0)
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                text = stringResource(R.string.sim_pads_hint),
                style = AxTheme.typography.label,
                color = AxTheme.colors.textMuted,
            )
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            contentPadding = PaddingValues(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (state.switches.isNotEmpty()) {
                item { AxSectionHeader(stringResource(R.string.sim_switches)) }
                items(state.switches.chunked(2), key = { it.first().index }) { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { source ->
                            SwitchControl(source.name, source.id, live, onSwitch, Modifier.weight(1f))
                        }
                        if (pair.size == 1) Box(Modifier.weight(1f))
                    }
                }
            }
            if (state.knobs.isNotEmpty()) {
                item { AxSectionHeader(stringResource(R.string.sim_knobs)) }
                items(state.knobs.chunked(2), key = { "k" + it.first().index }) { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { source ->
                            KnobControl(source.name, source.id, live, onPot, Modifier.weight(1f))
                        }
                        if (pair.size == 1) Box(Modifier.weight(1f))
                    }
                }
            }
            if (state.switches.isEmpty() && state.knobs.isEmpty()) {
                item { NoticeBox(stringResource(R.string.sim_no_sources), kind = NoticeKind.Info) }
            }
        }
    }
}

@Composable
private fun SwitchControl(name: String, id: SourceId, live: () -> LiveSnapshot, onSwitch: (SourceId, SwitchPosition) -> Unit, modifier: Modifier) {
    // derivedStateOf: recomposes only when this switch really moved, not on every live frame.
    val current by remember(id) { derivedStateOf { switchIndex(live().logic(id)) } }
    Row(modifier = modifier.height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = name,
            style = AxTheme.typography.bodyStrong,
            color = AxTheme.colors.text,
            maxLines = 1,
            modifier = Modifier.width(34.dp),
        )
        AxSegmented(
            options = listOf("▲", "●", "▼"),
            selectedIndex = current,
            onSelected = { onSwitch(id, switchPosition(it)) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun KnobControl(name: String, id: SourceId, live: () -> LiveSnapshot, onPot: (SourceId, Int) -> Unit, modifier: Modifier) {
    val liveValue by remember(id) { derivedStateOf { live().logic(id).toFloat() } }
    var drag by remember(id) { mutableFloatStateOf(0f) }
    var dragging by remember(id) { mutableStateOf(false) }
    Row(modifier = modifier.height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text = name, style = AxTheme.typography.bodyStrong, color = AxTheme.colors.text, maxLines = 1, modifier = Modifier.width(34.dp))
        AxSlider(
            value = if (dragging) drag else liveValue,
            onValueChange = {
                dragging = true
                drag = it
                onPot(id, it.roundToInt())
            },
            onValueChangeFinished = { dragging = false },
            valueRange = -100f..100f,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * A draggable square: touching it moves a simulated stick to that position (−100..+100 on both
 * axes); releasing calls [onRelease]. The dot is drawn from live values in the draw phase.
 */
@Composable
internal fun DragPad(
    x: () -> Float,
    y: () -> Float,
    onChange: (Float, Float) -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AxTheme.colors
    Box(
        modifier = modifier
            .size(176.dp)
            .axSunken(cornerRadius = 16.dp, depth = 2.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    fun report(position: Offset) {
                        val inset = DOT_INSET.dp.toPx()
                        val usableW = (size.width - 2f * inset).coerceAtLeast(1f)
                        val usableH = (size.height - 2f * inset).coerceAtLeast(1f)
                        val px = ((position.x - inset) / usableW * 2f - 1f) * 100f
                        val py = -(((position.y - inset) / usableH) * 2f - 1f) * 100f
                        onChange(px.coerceIn(-100f, 100f), py.coerceIn(-100f, 100f))
                    }
                    report(down.position)
                    down.consume()
                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        report(change.position)
                        change.consume()
                    } while (event.changes.any { it.pressed })
                    onRelease()
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize().graphicsLayer()) {
            val grid = colors.outline.copy(alpha = 0.6f)
            drawLine(grid, Offset(size.width / 2f, 8.dp.toPx()), Offset(size.width / 2f, size.height - 8.dp.toPx()), strokeWidth = 1.dp.toPx())
            drawLine(grid, Offset(8.dp.toPx(), size.height / 2f), Offset(size.width - 8.dp.toPx(), size.height / 2f), strokeWidth = 1.dp.toPx())
            val inset = DOT_INSET.dp.toPx()
            val cx = inset + (x().coerceIn(-100f, 100f) / 100f + 1f) / 2f * (size.width - 2f * inset)
            val cy = inset + (1f - (y().coerceIn(-100f, 100f) / 100f + 1f) / 2f) * (size.height - 2f * inset)
            drawCircle(colors.accent, radius = 9.dp.toPx(), center = Offset(cx, cy))
            drawCircle(colors.onAccent, radius = 9.dp.toPx(), center = Offset(cx, cy), style = Stroke(1.5.dp.toPx()))
        }
    }
}

// -- Telemetry -------------------------------------------------------------------------------

@Composable
private fun TelemetryTab(state: SimulatorUiState, onTelemetry: (SensorId, Double) -> Unit, onFlightModeText: (String) -> Unit) {
    var editingMode by rememberSaveable { mutableStateOf(false) }
    val entries = remember(state.telemetry) { state.telemetry.entries.sortedBy { it.key.key } }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item { NoticeBox(stringResource(R.string.sim_telemetry_hint)) }
        item {
            AxSettingRow(title = stringResource(R.string.sim_flight_mode_text), icon = AxIcons.FlightMode) {
                AxButton(stringResource(R.string.sim_set_text), { editingMode = true }, variant = AxButtonVariant.Outlined)
            }
            AxDivider()
        }
        items(entries, key = { it.key.key }) { entry ->
            val decimals = decimalsFor(entry.key)
            AxSettingRow(title = entry.key.key) {
                NumberField(
                    value = scaleValue(entry.value, decimals),
                    onValueChange = { onTelemetry(entry.key, unscaleValue(it, decimals)) },
                    min = -TELEMETRY_SCALED_LIMIT,
                    max = TELEMETRY_SCALED_LIMIT,
                    decimals = decimals,
                    title = entry.key.key,
                    modifier = Modifier.width(170.dp),
                )
            }
            AxDivider()
        }
    }
    if (editingMode) {
        TextEntryDialog(
            title = stringResource(R.string.sim_flight_mode_text),
            initialValue = "",
            onConfirm = { editingMode = false; onFlightModeText(it) },
            onDismiss = { editingMode = false },
            maxLength = 16,
        )
    }
}

// -- Link and faults -------------------------------------------------------------------------

@StringRes
private fun faultLabel(fault: SimulatedFault): Int = when (fault) {
    SimulatedFault.SERIAL_PORT_BUSY -> R.string.fault_serial_busy
    SimulatedFault.DEVICE_TIMEOUT -> R.string.fault_device_timeout
    SimulatedFault.SAVE_TIMEOUT -> R.string.fault_save_timeout
    SimulatedFault.READ_TIMEOUT -> R.string.fault_read_timeout
    SimulatedFault.ADD_MIX_FAILS -> R.string.fault_add_mix
    SimulatedFault.LOW_TX_VOLTAGE -> R.string.fault_low_voltage
    SimulatedFault.FIRMWARE_MISMATCH -> R.string.fault_firmware
    SimulatedFault.PRE_FLIGHT_CHECK_FAILS -> R.string.fault_preflight
    SimulatedFault.CONNECTION_LOST -> R.string.fault_connection_lost
}

@Composable
private fun LinkTab(
    state: SimulatorUiState,
    onDemoMotion: (Boolean) -> Unit,
    onLinkUp: (Boolean) -> Unit,
    onTelemetry: (SensorId, Double) -> Unit,
    onFault: (SimulatedFault) -> Unit,
) {
    PanelColumn {
        AxCard(modifier = Modifier.fillMaxWidth().padding(2.dp), contentPadding = PaddingValues(4.dp)) {
            Column {
                AxSettingRow(title = stringResource(R.string.sim_link), description = stringResource(R.string.sim_link_desc), icon = AxIcons.Link) {
                    AxSwitch(state.linkUp, onLinkUp)
                }
                AxDivider()
                AxSettingRow(title = stringResource(R.string.sim_demo), description = stringResource(R.string.sim_demo_desc), icon = AxIcons.Play) {
                    AxSwitch(state.demoMotion, onDemoMotion)
                }
                AxDivider()
                val tx = state.telemetry[org.openax12.core.model.Sensors.TX_BATTERY]
                AxSettingRow(title = stringResource(R.string.sim_tx_voltage), icon = AxIcons.Battery) {
                    NumberField(
                        value = scaleValue(tx ?: 0.0, 1),
                        onValueChange = { onTelemetry(org.openax12.core.model.Sensors.TX_BATTERY, unscaleValue(it, 1)) },
                        min = 0,
                        max = 300,
                        decimals = 1,
                        unit = " V",
                        title = stringResource(R.string.sim_tx_voltage),
                        steppers = true,
                        modifier = Modifier.width(190.dp),
                    )
                }
            }
        }
        AxSectionHeader(stringResource(R.string.sim_faults))
        Text(
            text = stringResource(R.string.sim_faults_hint),
            style = AxTheme.typography.label,
            color = AxTheme.colors.textMuted,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        SimulatedFault.entries.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { fault ->
                    AxButton(
                        text = stringResource(faultLabel(fault)),
                        onClick = { onFault(fault) },
                        variant = AxButtonVariant.Raised,
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(3 - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}
