package org.openax12.feature.home

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.openax12.core.designsystem.StickPad
import org.openax12.core.designsystem.StickPadTrimEdge
import org.openax12.core.model.LiveSnapshot
import org.openax12.core.model.SourceId
import org.openax12.core.model.StickMode

/** Which of the two pads. */
internal enum class PadSide { LEFT, RIGHT }

/** Size of a stick pad on the home screen (the field inside is square). */
internal val PadWidth: Dp = 204.dp
internal val PadHeight: Dp = 192.dp

/** Source on the horizontal axis of [side] in [mode]. */
internal fun padHorizontal(mode: StickMode, side: PadSide): SourceId =
    if (side == PadSide.LEFT) mode.leftHorizontal else mode.rightHorizontal

/** Source on the vertical axis of [side] in [mode]. */
internal fun padVertical(mode: StickMode, side: PadSide): SourceId =
    if (side == PadSide.LEFT) mode.leftVertical else mode.rightVertical

/**
 * One stick pad. The axes follow the stick mode; the trims belong to the physical stick side
 * (LV / LH / RV / RH), not to a function. All values are read in the draw phase of the pad.
 */
@Composable
internal fun HomeStickPad(
    side: PadSide,
    stickMode: StickMode,
    live: () -> LiveSnapshot,
    modifier: Modifier = Modifier,
) {
    val horizontal = padHorizontal(stickMode, side)
    val vertical = padVertical(stickMode, side)
    StickPad(
        x = { live().logic(horizontal).toFloat() },
        y = { live().logic(vertical).toFloat() },
        modifier = modifier.size(PadWidth, PadHeight),
        trimX = { (if (side == PadSide.LEFT) live().trims.leftHorizontal else live().trims.rightHorizontal).toFloat() },
        trimY = { (if (side == PadSide.LEFT) live().trims.leftVertical else live().trims.rightVertical).toFloat() },
        verticalTrimEdge = if (side == PadSide.LEFT) StickPadTrimEdge.Start else StickPadTrimEdge.End,
    )
}
