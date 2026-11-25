package org.openax12.feature.modelsetup.curves

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.openax12.core.designsystem.AxIcon
import org.openax12.core.designsystem.AxIcons
import org.openax12.core.designsystem.AxTheme
import org.openax12.core.designsystem.SetupHeaderCell
import org.openax12.core.designsystem.axSunken
import org.openax12.core.engine.CurveMath
import org.openax12.core.model.CURVE_COUNT
import org.openax12.core.model.CurveConfig
import org.openax12.core.model.ModelExtras
import org.openax12.core.model.WorkingConfig
import org.openax12.core.navigation.CurveEditor
import org.openax12.core.navigation.LocalNavigator
import org.openax12.core.runtime.ConfigSession
import org.openax12.core.runtime.LocalAppGraph
import org.openax12.feature.modelsetup.R
import org.openax12.feature.modelsetup.common.ManagedBadge
import org.openax12.feature.modelsetup.common.SetupColumnHeaderRow
import org.openax12.feature.modelsetup.common.SetupDims
import org.openax12.feature.modelsetup.common.SetupNotReady
import org.openax12.feature.modelsetup.common.SetupRowCard
import org.openax12.feature.modelsetup.common.SetupViewModel
import org.openax12.feature.modelsetup.common.curveSummary
import org.openax12.feature.modelsetup.common.setupViewModel
import org.openax12.feature.modelsetup.logic.CurveUse
import org.openax12.feature.modelsetup.logic.ManagedKind
import org.openax12.feature.modelsetup.logic.curveUsage

/** One curve of the list. */
internal data class CurveRowUi(val curve: CurveConfig, val uses: List<CurveUse>, val managed: ManagedKind?)

/** State of the Curves tab. */
internal data class CurvesState(
    val ready: Boolean = false,
    val config: WorkingConfig = WorkingConfig.EMPTY,
    val rows: List<CurveRowUi> = emptyList(),
)

/** The 18 curves of [config] (missing ones as free curves) with their uses. */
internal fun curveRows(config: WorkingConfig, extras: ModelExtras): List<CurveRowUi> =
    (0 until CURVE_COUNT).map { index ->
        CurveRowUi(
            curve = config.curve(index) ?: CurveConfig.empty(index),
            uses = curveUsage(config, index),
            managed = ManagedKind.of(extras.managed.generatorOfCurve(index)),
        )
    }

/** View model of the Curves tab (the list; editing happens in the curve editor). */
internal class CurvesViewModel(
    session: ConfigSession,
    extras: StateFlow<ModelExtras>,
    scopeOverride: CoroutineScope? = null,
) : SetupViewModel(session, scopeOverride) {

    /** State for the screen. */
    val state: StateFlow<CurvesState> = combine(session.workingConfig, extras) { config, ex ->
        CurvesState(ready = !config.isEmpty, config = config, rows = curveRows(config, ex))
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), CurvesState())
}

/** The Curves tab: the list of the 18 curves; a tap opens the curve editor. */
@Composable
internal fun CurvesTab() {
    val graph = LocalAppGraph.current
    val navigator = LocalNavigator.current
    val vm = setupViewModel("modelsetup.curves") { CurvesViewModel(graph.configSession, graph.models.currentExtras) }
    val state by vm.state.collectAsStateWithLifecycle()
    CurvesScreen(state = state, onOpen = { navigator.navigate(CurveEditor(it)) })
}

private val IndexWidth = 70.dp
private val TypeWidth = 170.dp
private val PreviewWidth = 96.dp

/** Stateless Curves screen. */
@Composable
internal fun CurvesScreen(state: CurvesState, onOpen: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        SetupColumnHeaderRow {
            SetupHeaderCell(stringResource(R.string.curves_col_curve), Modifier.width(IndexWidth), Alignment.CenterStart)
            SetupHeaderCell(stringResource(R.string.curves_col_type), Modifier.width(TypeWidth), Alignment.CenterStart)
            SetupHeaderCell(stringResource(R.string.curves_col_shape), Modifier.width(PreviewWidth + 12.dp))
            SetupHeaderCell(stringResource(R.string.curves_col_used), Modifier.weight(1f), Alignment.CenterStart)
        }
        if (!state.ready) {
            SetupNotReady()
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 3.dp)) {
                items(state.rows, key = { it.curve.index }) { row ->
                    CurveRow(row, state.config, onOpen = { onOpen(row.curve.index) })
                }
            }
        }
    }
}

@Composable
private fun CurveRow(row: CurveRowUi, config: WorkingConfig, onOpen: () -> Unit) {
    val colors = AxTheme.colors
    val free = row.curve.isUnused
    SetupRowCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(SetupDims.Row)
                .clickable(role = Role.Button, onClick = onOpen)
                .padding(horizontal = SetupDims.Inner),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.curve_short, row.curve.index + 1),
                style = AxTheme.typography.bodyStrong,
                color = if (free) colors.textMuted else colors.text,
                modifier = Modifier.width(IndexWidth),
            )
            Column(modifier = Modifier.width(TypeWidth)) {
                Text(
                    text = curveSummary(row.curve),
                    style = AxTheme.typography.body,
                    color = if (free) colors.textFaint else colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (row.managed != null) ManagedBadge(row.managed)
            }
            CurveThumbnail(curve = row.curve, modifier = Modifier.size(PreviewWidth, 40.dp))
            Text(
                text = usedByText(row.uses, config),
                style = AxTheme.typography.label,
                color = colors.textMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            )
            AxIcon(icon = AxIcons.ChevronRight, contentDescription = null, tint = colors.textMuted)
        }
    }
}

@Composable
private fun usedByText(uses: List<CurveUse>, config: WorkingConfig): String {
    if (uses.isEmpty()) return stringResource(R.string.curves_unused)
    val shown = uses.take(MAX_USES_SHOWN).map { use ->
        when (use) {
            is CurveUse.Mix -> stringResource(R.string.curves_use_mix, org.openax12.feature.modelsetup.common.channelLabel(use.channel), config.sourceName(use.source))
            is CurveUse.Output -> stringResource(R.string.curves_use_output, org.openax12.feature.modelsetup.common.channelLabel(use.channel))
        }
    }
    val more = uses.size - shown.size
    return shown.joinToString(", ") + if (more > 0) stringResource(R.string.curves_use_more, more) else ""
}

private const val MAX_USES_SHOWN = 4

/** A small sketch of the curve: grid, centre lines and the curve itself. */
@Composable
internal fun CurveThumbnail(curve: CurveConfig, modifier: Modifier = Modifier) {
    val colors = AxTheme.colors
    val samples = remember(curve) { CurveMath.sample(curve, 40) }
    Canvas(modifier = modifier.axSunken(cornerRadius = 5.dp, depth = 1.dp)) {
        val pad = 4.dp.toPx()
        val w = size.width - 2 * pad
        val h = size.height - 2 * pad
        drawLine(colors.outline, Offset(pad, size.height / 2f), Offset(size.width - pad, size.height / 2f), strokeWidth = 1.dp.toPx())
        drawLine(colors.outline, Offset(size.width / 2f, pad), Offset(size.width / 2f, size.height - pad), strokeWidth = 1.dp.toPx())
        if (curve.isUnused) return@Canvas
        val path = Path()
        samples.forEachIndexed { i, p ->
            val x = pad + (p.x + 100) / 200f * w
            val y = pad + (100 - p.y) / 200f * h
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, colors.accent, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
    }
}
