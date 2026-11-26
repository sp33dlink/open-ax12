package org.openax12.feature.modelsetup

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.android.tools.screenshot.PreviewTest
import kotlinx.coroutines.flow.MutableStateFlow
import org.openax12.core.designsystem.Ax12Preview
import org.openax12.core.designsystem.LocalLiveFeed
import org.openax12.core.model.SourceId
import org.openax12.feature.modelsetup.common.SourcePickerDialog
import org.openax12.feature.modelsetup.mixes.MixesScreen

// Input pickers while "move to select" is active: the title row shows the touch icon and its hint.

private val Config = sampleConfig()

/** The mix source picker (Mixes tab > source of a line). */
@PreviewTest
@Ax12Preview
@Composable
fun MixSourcePickerMoveToSelectScreenshot() {
    val feed = remember { MutableStateFlow(sampleLive(Config)) }
    CompositionLocalProvider(LocalLiveFeed provides feed) {
        SetupFrame("mixes") {
            MixesScreen(mixesState(4), rememberLive(Config), {}, {}, { _, _ -> }, { _, _ -> }, { _, _ -> }, {})
            SourcePickerDialog(
                config = Config,
                title = stringResource(R.string.mixes_pick_input),
                selected = SourceId.AIL,
                disabled = setOf(SourceId.ELE),
                onPick = {},
                onDismiss = {},
            )
        }
    }
}

/** The source picker that the switch condition dialog (mix switch, dual rate, timers, channel lock) opens. */
@PreviewTest
@Ax12Preview
@Composable
fun SwitchConditionPickerMoveToSelectScreenshot() {
    val feed = remember { MutableStateFlow(sampleLive(Config)) }
    CompositionLocalProvider(LocalLiveFeed provides feed) {
        SetupFrame("mixes") {
            MixesScreen(mixesState(4), rememberLive(Config), {}, {}, { _, _ -> }, { _, _ -> }, { _, _ -> }, {})
            SourcePickerDialog(
                config = Config,
                title = stringResource(R.string.cond_pick_source),
                selected = SourceId.SB,
                onPick = {},
                onDismiss = {},
            )
        }
    }
}
