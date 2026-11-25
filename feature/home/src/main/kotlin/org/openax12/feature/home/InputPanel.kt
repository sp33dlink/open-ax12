package org.openax12.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.openax12.core.designsystem.ArcGauge
import org.openax12.core.designsystem.AxBadge
import org.openax12.core.designsystem.AxIcon
import org.openax12.core.designsystem.AxIcons
import org.openax12.core.designsystem.AxTheme
import org.openax12.core.designsystem.PageSelector
import org.openax12.core.designsystem.SwitchPill
import org.openax12.core.model.LiveSnapshot
import org.openax12.core.model.LogicalSwitchStates
import org.openax12.core.runtime.ActiveFlightMode

/**
 * The lower part of the right column: the current page (switch pills above arc gauges, or the
 * flight-mode page) and the page selector. Switch and knob values are read in the draw phase.
 */
@Composable
internal fun InputPanel(
    pages: List<HomePage>,
    pageIndex: Int,
    onSelectPage: (Int) -> Unit,
    live: () -> LiveSnapshot,
    flightMode: () -> ActiveFlightMode,
    logicalSwitches: () -> LogicalSwitchStates,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            when (val page = pages.getOrNull(pageIndex)) {
                is HomePage.Controls -> ControlsPage(page, live)
                is HomePage.Modes -> ModesPage(page, flightMode, logicalSwitches)
                null -> Unit
            }
        }
        if (pages.size > 1) {
            PageSelector(
                pageCount = pages.size,
                selectedPage = pageIndex,
                onSelect = onSelectPage,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Up to six switch pills and two arc gauges. */
@Composable
private fun ControlsPage(page: HomePage.Controls, live: () -> LiveSnapshot) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(SWITCH_ROW_HEIGHT),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        ) {
            for (item in page.switches) {
                SwitchPill(position = { live().logic(item.source) }, label = item.label)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        ) {
            for (item in page.knobs) {
                ArcGauge(
                    value = { knobPercent(live().logic(item.source), item.range) },
                    label = item.label,
                    centerZero = item.range == KnobRange.CENTERED,
                )
            }
        }
    }
}

private val SWITCH_ROW_HEIGHT = 58.dp

/** Active flight mode and the configured logical switches. */
@Composable
private fun ModesPage(
    page: HomePage.Modes,
    flightMode: () -> ActiveFlightMode,
    logicalSwitches: () -> LogicalSwitchStates,
) {
    val colors = AxTheme.colors
    val type = AxTheme.typography
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AxIcon(AxIcons.FlightMode, contentDescription = null, tint = colors.accent, size = 20.dp)
            Text(
                text = stringResource(R.string.home_flight_mode),
                style = type.label,
                color = colors.textMuted,
            )
        }
        FlightModeName(page.flightModesUsed, flightMode)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AxIcon(AxIcons.Logic, contentDescription = null, tint = colors.accent, size = 20.dp)
            Text(
                text = stringResource(R.string.home_logic_switches),
                style = type.label,
                color = colors.textMuted,
                modifier = Modifier.weight(1f),
            )
            if (page.hiddenLogicSwitches > 0) {
                Text(
                    text = stringResource(R.string.home_logic_switches_more, page.hiddenLogicSwitches),
                    style = type.labelSmall,
                    color = colors.textFaint,
                    maxLines = 1,
                )
            }
        }
        if (page.logicSwitches.isEmpty()) {
            Text(
                text = stringResource(R.string.home_logic_switches_none),
                style = type.body,
                color = colors.textFaint,
            )
        } else {
            LogicSwitchGrid(page, logicalSwitches)
        }
    }
}

/** The mode name; recomposes on its own when the mode changes. */
@Composable
private fun FlightModeName(used: Boolean, flightMode: () -> ActiveFlightMode) {
    val name = if (used) flightMode().name else ""
    Text(
        text = when {
            !used -> stringResource(R.string.home_flight_mode_none)
            name.isBlank() -> "-"
            else -> name
        },
        style = AxTheme.typography.title,
        color = if (used) AxTheme.colors.text else AxTheme.colors.textFaint,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Chips of the configured logical switches, lit while a switch is on. Recomposes only when a state changes. */
@Composable
private fun LogicSwitchGrid(page: HomePage.Modes, logicalSwitches: () -> LogicalSwitchStates) {
    val states = logicalSwitches()
    val colors = AxTheme.colors
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (item in page.logicSwitches) {
            val on = states[item.index]
            Box(
                modifier = Modifier
                    .size(width = 30.dp, height = 22.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .drawBehind { drawRect(if (on) colors.teal else colors.track) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = item.label,
                    style = AxTheme.typography.labelSmall,
                    color = if (on) colors.onAccent else colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
