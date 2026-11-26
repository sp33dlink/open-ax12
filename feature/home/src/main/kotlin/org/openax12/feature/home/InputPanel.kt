package org.openax12.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.openax12.core.designsystem.ArcGauge
import org.openax12.core.designsystem.AxTheme
import org.openax12.core.designsystem.SwitchPill
import org.openax12.core.designsystem.axRaised
import org.openax12.core.model.LiveSnapshot
import org.openax12.core.model.SourceId
import org.openax12.core.model.SpButtonBar

/**
 * The lower part of the right column, one page as in the stock app: switch pills above the arc
 * gauges, and under them the six-button row of the SP input. Switch, knob and SP values are read
 * in the draw phase (or, for the SP row, in a derived state that changes only when the button does).
 */
@Composable
internal fun InputPanel(
    state: InputPanelState,
    live: () -> LiveSnapshot,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(SWITCH_ROW_HEIGHT),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        ) {
            for (item in state.switches) {
                SwitchPill(position = { live().logic(item.source) }, label = item.label)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        ) {
            for (item in state.knobs) {
                ArcGauge(
                    value = { knobPercent(live().logic(item.source), item.range) },
                    label = item.label,
                    centerZero = item.range == KnobRange.CENTERED,
                )
            }
        }
        if (state.showSpButtons) SpButtonRow(live = live, modifier = Modifier.fillMaxWidth())
    }
}

private val SWITCH_ROW_HEIGHT = 58.dp

/** Height of the SP button row; the pills inside are 20 dp high. */
private val SP_ROW_HEIGHT = 28.dp

/**
 * The SP input as the stock app shows it: the pills "1" to "6", the active one filled with the accent
 * colour. SP is a button bar with exactly one button active at all times, so this is display only:
 * not tappable, no ripple, not a tab. Which button is active comes from [SpButtonBar].
 *
 * The active button is a derived state: the row recomposes when the button changes (a few times per
 * flight), not on every live frame.
 */
@Composable
private fun SpButtonRow(live: () -> LiveSnapshot, modifier: Modifier = Modifier) {
    val active by remember(live) { derivedStateOf { SpButtonBar.buttonFromLogic(live().logic(SourceId.SP)) } }
    val colors = AxTheme.colors
    val description = stringResource(R.string.home_cd_sp_button_active, active)
    Row(
        modifier = modifier
            .height(SP_ROW_HEIGHT)
            .semantics(mergeDescendants = true) { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        for (button in 1..SpButtonBar.BUTTON_COUNT) {
            val selected = button == active
            Box(
                modifier = Modifier
                    .size(width = 28.dp, height = 20.dp)
                    .axRaised(
                        cornerRadius = 10.dp,
                        elevation = 2.dp,
                        color = if (selected) colors.accent else colors.surfaceRaised,
                    )
                    .clearAndSetSemantics { },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = button.toString(),
                    style = AxTheme.typography.label,
                    color = if (selected) colors.onAccent else colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
            }
        }
    }
}
