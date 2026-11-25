package org.openax12.feature.modelsetup.curves

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.openax12.core.designsystem.AxButton
import org.openax12.core.designsystem.AxButtonVariant
import org.openax12.core.designsystem.AxChip
import org.openax12.core.designsystem.AxDropdown
import org.openax12.core.designsystem.AxIcons
import org.openax12.core.designsystem.AxSegmented
import org.openax12.core.designsystem.AxSlider
import org.openax12.core.designsystem.AxTheme
import org.openax12.core.designsystem.AxValueBox
import org.openax12.core.designsystem.NumberField
import org.openax12.core.designsystem.SetupTabItem
import org.openax12.core.designsystem.axSunken
import org.openax12.core.engine.CurveIssue
import org.openax12.core.engine.CurveMath
import org.openax12.core.model.CURVE_MAX_POINTS
import org.openax12.core.model.CurveConfig
import org.openax12.core.model.CurveType
import org.openax12.core.model.PERCENT_MAX
import org.openax12.core.model.PERCENT_MIN
import org.openax12.core.model.SourceId
import org.openax12.core.navigation.LocalNavigator
import org.openax12.core.runtime.LocalAppGraph
import org.openax12.feature.modelsetup.R
import org.openax12.feature.modelsetup.chrome.ChromeHost
import org.openax12.feature.modelsetup.chrome.SetupChromeViewModel
import org.openax12.feature.modelsetup.common.CollectMessages
import org.openax12.feature.modelsetup.common.DialogRow
import org.openax12.feature.modelsetup.common.LiveFeed
import org.openax12.feature.modelsetup.common.ManagedBadge
import org.openax12.feature.modelsetup.common.SetupDims
import org.openax12.feature.modelsetup.common.SetupNotReady
import org.openax12.feature.modelsetup.common.SourcePickerDialog
import org.openax12.feature.modelsetup.common.channelLabel
import org.openax12.feature.modelsetup.common.collectLive
import org.openax12.feature.modelsetup.common.curveTypeLabel
import org.openax12.feature.modelsetup.common.funcName
import org.openax12.feature.modelsetup.common.setupViewModel
import org.openax12.feature.modelsetup.logic.CurveEdit
import org.openax12.feature.modelsetup.logic.CurveUse
import kotlin.math.hypot

/** The curve editor entry: the model-setup bar (back, Save) around the editor. */
@Composable
internal fun CurveEditorRoute(index: Int) {
    val graph = LocalAppGraph.current
    val navigator = LocalNavigator.current
    val chrome = setupViewModel("modelsetup.chrome") { SetupChromeViewModel(graph.configSession, graph.radio) }
    val vm = setupViewModel("modelsetup.curve.$index") {
        CurveEditorViewModel(index, graph.configSession, graph.radio, graph.models.currentExtras)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val firmware by vm.firmwareMarker.collectAsStateWithLifecycle()
    val live = graph.radio.live.collectLive()
    CollectMessages(vm.messages)
    val title = stringResource(R.string.curve_editor_title, index + 1)
    ChromeHost(
        chrome = chrome,
        tabs = listOf(SetupTabItem(CURVE_TAB_ID, title)),
        selectedTabId = CURVE_TAB_ID,
        onTabSelected = {},
        onBack = { navigator.back() },
    ) {
        CurveEditorScreen(
            state = state,
            live = live,
            firmwareY = firmware,
            onAcknowledge = vm::acknowledge,
            onSetType = vm::setType,
            onDragParameter = vm::dragParameter,
            onSetParameter = vm::setParameter,
            onSelect = vm::select,
            onDragPoint = vm::dragPoint,
            onFinishDrag = vm::finishDrag,
            onSetPointX = vm::setPointX,
            onSetPointY = vm::setPointY,
            onAddPoint = vm::addPoint,
            onRemovePoint = vm::removePoint,
            onStraighten = vm::straighten,
            onMarkerSource = vm::setMarkerSource,
        )
    }
}

private const val CURVE_TAB_ID = "curve"
private val PlotSize = 316.dp

/** Stateless curve editor: the plot with draggable points on the left, the controls on the right. */
@Composable
internal fun CurveEditorScreen(
    state: CurveEditorState,
    live: LiveFeed,
    firmwareY: Double?,
    onAcknowledge: () -> Unit,
    onSetType: (CurveType) -> Unit,
    onDragParameter: (Int) -> Unit,
    onSetParameter: (Int) -> Unit,
    onSelect: (Int) -> Unit,
    onDragPoint: (Int, Int, Int) -> Unit,
    onFinishDrag: () -> Unit,
    onSetPointX: (Int, Int) -> Unit,
    onSetPointY: (Int, Int) -> Unit,
    onAddPoint: () -> Unit,
    onRemovePoint: () -> Unit,
    onStraighten: () -> Unit,
    onMarkerSource: (SourceId) -> Unit,
) {
    if (!state.ready) {
        SetupNotReady()
        return
    }
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CurvePlot(
            curve = state.curve,
            selectedPoint = state.selectedPoint,
            markerSource = state.markerSource,
            live = live,
            enabled = !state.locked,
            onSelect = onSelect,
            onDragPoint = onDragPoint,
            onFinishDrag = onFinishDrag,
            modifier = Modifier.size(PlotSize),
        )
        Controls(
            state = state,
            live = live,
            firmwareY = firmwareY,
            onAcknowledge = onAcknowledge,
            onSetType = onSetType,
            onDragParameter = onDragParameter,
            onSetParameter = onSetParameter,
            onSelect = onSelect,
            onSetPointX = onSetPointX,
            onSetPointY = onSetPointY,
            onAddPoint = onAddPoint,
            onRemovePoint = onRemovePoint,
            onStraighten = onStraighten,
            onMarkerSource = onMarkerSource,
            modifier = Modifier.weight(1f),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Controls(
    state: CurveEditorState,
    live: LiveFeed,
    firmwareY: Double?,
    onAcknowledge: () -> Unit,
    onSetType: (CurveType) -> Unit,
    onDragParameter: (Int) -> Unit,
    onSetParameter: (Int) -> Unit,
    onSelect: (Int) -> Unit,
    onSetPointX: (Int, Int) -> Unit,
    onSetPointY: (Int, Int) -> Unit,
    onAddPoint: () -> Unit,
    onRemovePoint: () -> Unit,
    onStraighten: () -> Unit,
    onMarkerSource: (SourceId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AxTheme.colors
    val curve = state.curve
    val enabled = !state.locked
    var pickingMarker by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (state.managed != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                ManagedBadge(state.managed)
                Text(
                    text = stringResource(if (state.locked) R.string.curve_managed_locked else R.string.curve_managed_unlocked),
                    style = AxTheme.typography.label,
                    color = if (state.locked) colors.warning else colors.textMuted,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                    maxLines = 2,
                )
                if (state.locked) {
                    AxButton(
                        text = stringResource(R.string.managed_edit_confirm),
                        onClick = onAcknowledge,
                        height = SetupDims.Field,
                    )
                }
            }
        }
        val types = CurveType.entries
        val typeLabels = types.map { curveTypeLabel(it) }
        AxSegmented(
            options = typeLabels,
            selectedIndex = types.indexOf(curve.type),
            onSelected = { onSetType(types[it]) },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
        when (curve.type) {
            CurveType.NONE -> Text(
                text = stringResource(R.string.curve_free_hint),
                style = AxTheme.typography.label,
                color = colors.textMuted,
                modifier = Modifier.padding(vertical = 8.dp),
            )

            CurveType.DIFF, CurveType.EXPO -> {
                DialogRow(stringResource(if (curve.type == CurveType.DIFF) R.string.curve_param_diff else R.string.curve_param_expo)) {
                    NumberField(
                        value = curve.parameter,
                        onValueChange = onSetParameter,
                        min = PERCENT_MIN,
                        max = PERCENT_MAX,
                        unit = "%",
                        enabled = enabled,
                        defaultValue = 0,
                        height = SetupDims.Field,
                        modifier = Modifier.width(110.dp),
                    )
                }
                AxSlider(
                    value = curve.parameter.toFloat(),
                    onValueChange = { onDragParameter(it.toInt()) },
                    valueRange = PERCENT_MIN.toFloat()..PERCENT_MAX.toFloat(),
                    enabled = enabled,
                    onValueChangeFinished = { onSetParameter(state.curve.parameter) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            CurveType.FUNC -> DialogRow(stringResource(R.string.curve_param_func)) {
                val numbers = (0..FUNC_LAST).toList()
                val labels = numbers.map { funcName(it) }
                AxDropdown(
                    options = labels,
                    selectedIndex = curve.parameter.coerceIn(0, FUNC_LAST),
                    onSelected = { onSetParameter(numbers[it]) },
                    title = stringResource(R.string.curve_param_func),
                    enabled = enabled,
                    columns = 3,
                    height = SetupDims.Field,
                    modifier = Modifier.width(140.dp),
                )
            }

            CurveType.CUSTOM -> {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(vertical = 4.dp),
                ) {
                    for (i in 0 until curve.pointCount) {
                        AxChip(
                            text = stringResource(R.string.curve_point_short, i + 1),
                            selected = i == state.selectedPoint,
                            onClick = { onSelect(i) },
                        )
                    }
                }
                val point = curve.points.getOrNull(state.selectedPoint)
                if (point != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.curve_point_x), style = AxTheme.typography.label, color = colors.textMuted)
                        NumberField(
                            value = point.x,
                            onValueChange = { onSetPointX(state.selectedPoint, it) },
                            min = PERCENT_MIN,
                            max = PERCENT_MAX,
                            unit = "%",
                            enabled = enabled,
                            title = stringResource(R.string.curve_point_x_title, state.selectedPoint + 1),
                            height = SetupDims.Field,
                            modifier = Modifier.width(100.dp),
                        )
                        Text(stringResource(R.string.curve_point_y), style = AxTheme.typography.label, color = colors.textMuted)
                        NumberField(
                            value = point.y,
                            onValueChange = { onSetPointY(state.selectedPoint, it) },
                            min = PERCENT_MIN,
                            max = PERCENT_MAX,
                            unit = "%",
                            enabled = enabled,
                            title = stringResource(R.string.curve_point_y_title, state.selectedPoint + 1),
                            height = SetupDims.Field,
                            modifier = Modifier.width(100.dp),
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    AxButton(
                        text = stringResource(R.string.curve_add_point),
                        onClick = onAddPoint,
                        enabled = enabled && curve.pointCount < CURVE_MAX_POINTS,
                        icon = AxIcons.Add,
                        height = SetupDims.Field,
                    )
                    AxButton(
                        text = stringResource(R.string.curve_remove_point),
                        onClick = onRemovePoint,
                        enabled = enabled && curve.pointCount > 2,
                        icon = AxIcons.Remove,
                        height = SetupDims.Field,
                    )
                    AxButton(
                        text = stringResource(R.string.curve_straighten),
                        onClick = onStraighten,
                        enabled = enabled,
                        height = SetupDims.Field,
                    )
                }
            }
        }
        val issueTexts = state.issues.map { stringResource(issueText(it)) }
        if (issueTexts.isNotEmpty()) {
            Text(
                text = issueTexts.joinToString(" "),
                style = AxTheme.typography.label,
                color = colors.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (!curve.isUnused) {
            DialogRow(stringResource(R.string.curve_marker_input)) {
                AxValueBox(
                    text = state.config.sourceName(state.markerSource),
                    onClick = { pickingMarker = true },
                    trailingIcon = AxIcons.ExpandMore,
                    height = SetupDims.Field,
                    modifier = Modifier.width(110.dp),
                )
            }
            MarkerReadout(curve, state.markerSource, live, firmwareY)
        }
        Text(
            text = usesText(state),
            style = AxTheme.typography.label,
            color = colors.textMuted,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
    if (pickingMarker) {
        SourcePickerDialog(
            config = state.config,
            title = stringResource(R.string.curve_marker_input),
            selected = state.markerSource,
            onPick = {
                onMarkerSource(it)
                pickingMarker = false
            },
            onDismiss = { pickingMarker = false },
        )
    }
}

private const val FUNC_LAST = CurveMath.FUNC_SIGN

private fun issueText(issue: CurveIssue): Int = when (issue) {
    CurveIssue.TOO_FEW_POINTS -> R.string.curve_issue_too_few
    CurveIssue.TOO_MANY_POINTS -> R.string.curve_issue_too_many
    CurveIssue.X_NOT_ASCENDING -> R.string.curve_issue_order
    CurveIssue.VALUE_OUT_OF_RANGE -> R.string.curve_issue_range
    CurveIssue.INVALID_INDEX -> R.string.curve_issue_index
}

@Composable
private fun usesText(state: CurveEditorState): String {
    if (state.uses.isEmpty()) return stringResource(R.string.curve_used_none)
    val parts = state.uses.take(6).map { use ->
        when (use) {
            is CurveUse.Mix -> stringResource(R.string.curves_use_mix, channelLabel(use.channel), state.config.sourceName(use.source))
            is CurveUse.Output -> stringResource(R.string.curves_use_output, channelLabel(use.channel))
        }
    }
    return stringResource(R.string.curve_used_by, parts.joinToString(", "))
}

/**
 * "Input 45 % → 52 % (radio 51 %)": where the marker is, the app's value and the radio's own
 * evaluation (a difference points at assumption 12 / 19). Derived from the live frame, so only
 * this line recomposes.
 */
@Composable
private fun MarkerReadout(curve: CurveConfig, source: SourceId, live: LiveFeed, firmwareY: Double?) {
    val withRadio = stringResource(R.string.curve_marker_readout_radio)
    val withoutRadio = stringResource(R.string.curve_marker_readout)
    val text by remember(curve, source, firmwareY, withRadio, withoutRadio) {
        derivedStateOf {
            val x = live.value.logic(source)
            val y = Math.round(CurveMath.apply(curve, x.toDouble())).toInt()
            if (firmwareY == null) {
                withoutRadio.format(x, y)
            } else {
                withRadio.format(x, y, Math.round(firmwareY).toInt())
            }
        }
    }
    Text(text = text, style = AxTheme.typography.value, color = AxTheme.colors.text, modifier = Modifier.padding(top = 2.dp))
}

/**
 * The curve plot: grid, the curve, draggable points of a custom curve and the live input marker.
 * The marker is read in the draw phase; points are picked within 32 dp of the touch.
 */
@Composable
internal fun CurvePlot(
    curve: CurveConfig,
    selectedPoint: Int,
    markerSource: SourceId,
    live: LiveFeed,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
    onDragPoint: (Int, Int, Int) -> Unit,
    onFinishDrag: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AxTheme.colors
    val samples = remember(curve) { FloatArray(PLOT_STEPS + 1) { CurveMath.apply(curve, -100.0 + 200.0 * it / PLOT_STEPS).toFloat() } }
    val latestCurve by rememberUpdatedState(curve)
    val latestSelect by rememberUpdatedState(onSelect)
    val latestDrag by rememberUpdatedState(onDragPoint)
    val latestFinish by rememberUpdatedState(onFinishDrag)
    Canvas(
        modifier = modifier
            .axSunken(cornerRadius = 10.dp, depth = 2.dp)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                val margin = PLOT_MARGIN.toPx()
                val pick = PICK_RADIUS.toPx()
                var dragging = -1
                detectDragGestures(
                    onDragStart = { start ->
                        dragging = nearestPoint(latestCurve, start, size.width.toFloat(), margin, pick)
                        if (dragging >= 0) latestSelect(dragging)
                    },
                    onDrag = { change, _ ->
                        if (dragging >= 0) {
                            change.consume()
                            val plot = size.width - 2 * margin
                            latestDrag(
                                dragging,
                                CurveEdit.fromPlotX(change.position.x - margin, plot),
                                CurveEdit.fromPlotY(change.position.y - margin, plot),
                            )
                        }
                    },
                    onDragEnd = {
                        if (dragging >= 0) latestFinish()
                        dragging = -1
                    },
                    onDragCancel = {
                        if (dragging >= 0) latestFinish()
                        dragging = -1
                    },
                )
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { tap ->
                    val hit = nearestPoint(latestCurve, tap, size.width.toFloat(), PLOT_MARGIN.toPx(), PICK_RADIUS.toPx())
                    if (hit >= 0) latestSelect(hit)
                }
            },
    ) {
        val margin = PLOT_MARGIN.toPx()
        val plot = size.width - 2 * margin
        fun px(x: Int) = margin + CurveEdit.toPlotX(x, plot)
        fun py(y: Int) = margin + CurveEdit.toPlotY(y, plot)
        val hair = 1.dp.toPx()
        // Grid every 50 %, stronger axes through the centre.
        for (v in intArrayOf(-50, 50)) {
            drawLine(colors.outline.copy(alpha = 0.5f), Offset(px(v), margin), Offset(px(v), margin + plot), hair)
            drawLine(colors.outline.copy(alpha = 0.5f), Offset(margin, py(v)), Offset(margin + plot, py(v)), hair)
        }
        drawLine(colors.outline, Offset(px(0), margin), Offset(px(0), margin + plot), 1.5.dp.toPx())
        drawLine(colors.outline, Offset(margin, py(0)), Offset(margin + plot, py(0)), 1.5.dp.toPx())
        drawRoundRect(
            color = colors.outline,
            topLeft = Offset(margin, margin),
            size = Size(plot, plot),
            cornerRadius = CornerRadius(3.dp.toPx()),
            style = Stroke(hair),
        )
        // The identity line as a reference for curves that bend away from it.
        drawLine(colors.textFaint.copy(alpha = 0.4f), Offset(px(-100), py(-100)), Offset(px(100), py(100)), hair)
        if (!curve.isUnused) {
            val path = Path()
            samples.forEachIndexed { i, y ->
                val x = margin + plot * i / PLOT_STEPS
                val yPx = margin + plot - (y + 100f) / 200f * plot
                if (i == 0) path.moveTo(x, yPx) else path.lineTo(x, yPx)
            }
            drawPath(path, colors.accent, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        if (curve.type == CurveType.CUSTOM) {
            curve.points.forEachIndexed { i, p ->
                val center = Offset(px(p.x), py(p.y))
                if (i == selectedPoint) {
                    drawCircle(colors.surfaceBright, radius = 11.dp.toPx(), center = center)
                    drawCircle(colors.accent, radius = 8.dp.toPx(), center = center)
                } else {
                    drawCircle(colors.surfaceBright, radius = 7.dp.toPx(), center = center)
                    drawCircle(colors.accent, radius = 7.dp.toPx(), center = center, style = Stroke(2.dp.toPx()))
                }
            }
        }
        // Live marker: the input's position and the curve's value there.
        val x = live.value.logic(markerSource).coerceIn(PERCENT_MIN, PERCENT_MAX)
        val y = CurveMath.apply(curve, x.toDouble()).coerceIn(PERCENT_MIN.toDouble(), PERCENT_MAX.toDouble())
        drawLine(
            colors.orange.copy(alpha = 0.7f),
            Offset(px(x), margin),
            Offset(px(x), margin + plot),
            1.5.dp.toPx(),
        )
        drawCircle(colors.orange, radius = 5.dp.toPx(), center = Offset(px(x), margin + plot - ((y.toFloat() + 100f) / 200f) * plot))
    }
}

private const val PLOT_STEPS = 100
private val PLOT_MARGIN = 14.dp
private val PICK_RADIUS = 32.dp

/** Index of the custom point nearest to [touch] within [pick] pixels, or -1. Plot is square of [width]. */
private fun nearestPoint(curve: CurveConfig, touch: Offset, width: Float, margin: Float, pick: Float): Int {
    if (curve.type != CurveType.CUSTOM) return -1
    val plot = width - 2 * margin
    var best = -1
    var bestDistance = pick
    curve.points.forEachIndexed { i, p ->
        val d = hypot(
            touch.x - (margin + CurveEdit.toPlotX(p.x, plot)),
            touch.y - (margin + CurveEdit.toPlotY(p.y, plot)),
        )
        if (d <= bestDistance) {
            best = i
            bestDistance = d
        }
    }
    return best
}
