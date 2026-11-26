package org.openax12.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import org.openax12.core.model.LiveSnapshot
import org.openax12.core.model.MoveToSelectDetector
import org.openax12.core.model.SourceId

/**
 * The radio's live frames for "move to select": while an input picker is open it watches these
 * frames and picks the input the user moves (see [MoveToSelectDetector]). Provided once by the app
 * shell from `Radio.live`; `null` (the default) switches the feature off, e.g. in previews.
 */
val LocalLiveFeed = staticCompositionLocalOf<StateFlow<LiveSnapshot>?> { null }

/**
 * "Move to select" for an open picker. Does nothing, and returns false, when no [LocalLiveFeed] is
 * provided or none of [candidates] can be watched. Otherwise it records the baseline of every
 * candidate at the moment this composable enters the composition and calls [onMoved] once with the
 * first source that moves away from it; the caller then selects it exactly as a tap would.
 *
 * It collects the live frames in an effect, so nothing recomposes per frame.
 *
 * @param candidates the sources the picker offers and a tap could pick
 * @param isPresent false for sources the radio reports as absent
 * @return true while the feature is active (the picker shows the hint [MoveToSelectHint])
 */
@Composable
fun MoveToSelectEffect(
    candidates: List<SourceId>,
    isPresent: (SourceId) -> Boolean = { true },
    onMoved: (SourceId) -> Unit,
): Boolean {
    val feed = LocalLiveFeed.current
    val currentCandidates by rememberUpdatedState(candidates)
    val currentPresent by rememberUpdatedState(isPresent)
    val currentOnMoved by rememberUpdatedState(onMoved)
    val active = feed != null && candidates.any { it.isValid && !it.isCounter && isPresent(it) }
    if (feed != null && active) {
        // Keyed on the feed only: the baseline is taken when the picker opens, not when the list is rebuilt.
        LaunchedEffect(feed) {
            feed.awaitMovedInput(currentCandidates, currentPresent)?.let { currentOnMoved(it) }
        }
    }
    return active
}

/**
 * Suspends until one of [candidates] moves away from the value it has in the first frame with data
 * (see [MoveToSelectDetector]) and returns it. Returns null at once if nothing can be watched.
 */
internal suspend fun StateFlow<LiveSnapshot>.awaitMovedInput(
    candidates: Collection<SourceId>,
    isPresent: (SourceId) -> Boolean = { true },
): SourceId? {
    val detector = MoveToSelectDetector(candidates, isPresent = isPresent)
    if (detector.isEmpty) return null
    return mapNotNull { detector.update(it) }.first()
}

/**
 * The options of a picker that "move to select" can pick: enabled ones that stand for a radio
 * input, by that input. A disabled option cannot be tapped, so it cannot be selected by moving either.
 */
internal fun <T> moveToSelectTargets(options: List<PickerOption<T>>): Map<SourceId, PickerOption<T>> =
    options.filter { it.enabled && it.source != null }.associateBy { it.source!! }

/** Icon and short text that tell the user that moving an input selects it ("Move an input to select it"). */
@Composable
fun MoveToSelectHint(modifier: Modifier = Modifier) {
    val colors = AxTheme.colors
    val label = stringResource(R.string.ax_move_to_select)
    Row(
        modifier = modifier.clearAndSetSemantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AxIcon(icon = AxIcons.Touch, contentDescription = null, tint = colors.accent, size = 20.dp)
        Text(
            text = label,
            style = AxTheme.typography.label,
            color = colors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
