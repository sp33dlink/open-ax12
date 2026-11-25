package org.openax12.feature.system.calibration

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.openax12.core.designsystem.AxButton
import org.openax12.core.designsystem.AxButtonVariant
import org.openax12.core.designsystem.AxCard
import org.openax12.core.designsystem.AxIcon
import org.openax12.core.designsystem.AxIcons
import org.openax12.core.designsystem.AxSectionHeader
import org.openax12.core.designsystem.AxTheme
import org.openax12.core.designsystem.ConfirmDialog
import org.openax12.core.designsystem.StickPad
import org.openax12.core.designsystem.StickPadTrimEdge
import org.openax12.core.model.LiveSnapshot
import org.openax12.core.model.SourceId
import org.openax12.core.navigation.LocalNavigator
import org.openax12.core.runtime.LocalAppGraph
import org.openax12.feature.system.R
import org.openax12.feature.system.common.ConnectionNotice
import org.openax12.feature.system.common.FeedbackEffect
import org.openax12.feature.system.common.NoticeBox
import org.openax12.feature.system.common.NoticeKind
import org.openax12.feature.system.common.SystemBar
import org.openax12.feature.system.common.graphViewModel

/** Stateful entry content of `Calibration`. */
@Composable
internal fun CalibrationRoute() {
    val graph = LocalAppGraph.current
    val viewModel = graphViewModel(graph) { CalibrationViewModel(it.radio, it.configSession, it.scope) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    // Not read here: read in the draw lambdas of the pads, so a stick move never recomposes the screen.
    val live = graph.radio.live.collectAsStateWithLifecycle()
    FeedbackEffect(viewModel.feedback)
    CalibrationScreen(
        state = state,
        live = { live.value },
        onBack = { navigator.back() },
        onStart = viewModel::requestStart,
        onFinish = viewModel::finish,
        onConfirmStart = viewModel::start,
        onDismissConfirm = viewModel::dismissConfirm,
    )
}

/** The stick calibration screen: live pads, instructions, start / finish and the observed ranges. Stateless. */
@Composable
internal fun CalibrationScreen(
    state: CalibrationUiState,
    live: () -> LiveSnapshot,
    onBack: () -> Unit,
    onStart: () -> Unit,
    onFinish: () -> Unit,
    onConfirmStart: () -> Unit,
    onDismissConfirm: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        SystemBar(title = stringResource(R.string.cal_title), onBack = onBack)
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val mode = state.stickMode
            StickPad(
                x = { live().logic(mode.leftHorizontal).toFloat() },
                y = { live().logic(mode.leftVertical).toFloat() },
                modifier = Modifier.size(228.dp),
                verticalTrimEdge = StickPadTrimEdge.Start,
            )
            Column(modifier = Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ConnectionNotice(state.connection)
                if (state.ready && state.linkUp && !state.running) {
                    NoticeBox(stringResource(R.string.cal_link_up_notice), kind = NoticeKind.Warning)
                }
                AxCard(modifier = Modifier.fillMaxWidth().padding(2.dp), contentPadding = PaddingValues(12.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        AxSectionHeader(
                            title = stringResource(if (state.running) R.string.cal_running else R.string.cal_idle),
                        )
                        Text(
                            text = stringResource(if (state.running) R.string.cal_steps_running else R.string.cal_steps_idle),
                            style = AxTheme.typography.body,
                            color = AxTheme.colors.text,
                        )
                        ExtentsList(state)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                            if (state.running) {
                                AxButton(
                                    text = stringResource(R.string.cal_finish),
                                    onClick = onFinish,
                                    variant = AxButtonVariant.Primary,
                                    icon = AxIcons.Check,
                                    enabled = state.ready && !state.busy,
                                )
                            } else {
                                AxButton(
                                    text = stringResource(R.string.cal_start),
                                    onClick = onStart,
                                    variant = AxButtonVariant.Primary,
                                    icon = AxIcons.Play,
                                    enabled = state.ready && !state.busy,
                                )
                            }
                        }
                        Text(
                            text = stringResource(R.string.cal_state, state.calibrationState),
                            style = AxTheme.typography.labelSmall,
                            color = AxTheme.colors.textMuted,
                        )
                    }
                }
            }
            StickPad(
                x = { live().logic(mode.rightHorizontal).toFloat() },
                y = { live().logic(mode.rightVertical).toFloat() },
                modifier = Modifier.size(228.dp),
                verticalTrimEdge = StickPadTrimEdge.End,
            )
        }
    }
    if (state.confirmStart) {
        ConfirmDialog(
            title = stringResource(R.string.cal_confirm_title),
            message = stringResource(R.string.cal_confirm_message),
            onConfirm = onConfirmStart,
            onDismiss = onDismissConfirm,
            confirmLabel = stringResource(R.string.cal_start),
            destructive = true,
        )
    }
}

@Composable
private fun ExtentsList(state: CalibrationUiState) {
    val colors = AxTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (index in StickExtents.STICK_SOURCES) {
            val source = SourceId(index)
            val extent = state.extents.of(source)
            val done = extent.reachedBothEnds()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = source.defaultName,
                    style = AxTheme.typography.bodyStrong,
                    color = colors.text,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${extent.min}% … ${extent.max}%",
                    style = AxTheme.typography.value,
                    color = colors.textMuted,
                    modifier = Modifier.padding(end = 8.dp),
                )
                AxIcon(
                    icon = if (done) AxIcons.Success else AxIcons.Touch,
                    contentDescription = null,
                    tint = if (done) colors.success else colors.textFaint,
                    size = 18.dp,
                )
            }
        }
    }
}
