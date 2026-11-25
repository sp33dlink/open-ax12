package org.openax12.feature.system.help

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.openax12.core.designsystem.AxCard
import org.openax12.core.designsystem.AxIcon
import org.openax12.core.designsystem.AxIcons
import org.openax12.core.designsystem.AxTheme
import org.openax12.core.designsystem.axSunken
import org.openax12.core.navigation.LocalNavigator
import org.openax12.feature.system.R
import org.openax12.feature.system.common.SystemBar

/** One help topic: a title and its paragraphs. */
internal enum class HelpTopic(
    @param:StringRes val title: Int,
    val paragraphs: List<Int>,
) {
    START(R.string.help_start_title, listOf(R.string.help_start_1, R.string.help_start_2, R.string.help_start_3)),
    SAVING(R.string.help_saving_title, listOf(R.string.help_saving_1, R.string.help_saving_2, R.string.help_saving_3)),
    FLIGHT(R.string.help_flight_title, listOf(R.string.help_flight_1, R.string.help_flight_2)),
    VOICE(R.string.help_voice_title, listOf(R.string.help_voice_1, R.string.help_voice_2, R.string.help_voice_3)),
    SIMULATOR(R.string.help_sim_title, listOf(R.string.help_sim_1, R.string.help_sim_2)),
    FIRMWARE(R.string.help_firmware_title, listOf(R.string.help_firmware_1, R.string.help_firmware_2)),
}

/** Stateful entry content of `Help`. */
@Composable
internal fun HelpRoute() {
    val navigator = LocalNavigator.current
    var selected by rememberSaveable { mutableIntStateOf(0) }
    HelpScreen(
        selected = HelpTopic.entries[selected.coerceIn(0, HelpTopic.entries.lastIndex)],
        onSelect = { selected = it.ordinal },
        onBack = { navigator.back() },
    )
}

/** Help: the topics on the left, the selected one on the right. Stateless. */
@Composable
internal fun HelpScreen(selected: HelpTopic, onSelect: (HelpTopic) -> Unit, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        SystemBar(title = stringResource(R.string.help_title), onBack = onBack)
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.width(230.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
                HelpTopic.entries.forEach { topic ->
                    val isSelected = topic == selected
                    val colors = AxTheme.colors
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .padding(vertical = 2.dp)
                            .then(if (isSelected) Modifier.axSunken(cornerRadius = 14.dp, depth = 2.dp) else Modifier)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                role = Role.Tab,
                                onClick = { onSelect(topic) },
                            )
                            .semantics { this.selected = isSelected }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AxIcon(
                            icon = AxIcons.HelpOutline,
                            contentDescription = null,
                            tint = if (isSelected) colors.accent else colors.textMuted,
                            size = 20.dp,
                        )
                        Text(
                            text = stringResource(topic.title),
                            style = if (isSelected) AxTheme.typography.bodyStrong else AxTheme.typography.body,
                            color = if (isSelected) colors.text else colors.textMuted,
                            maxLines = 2,
                            modifier = Modifier.padding(start = 10.dp),
                        )
                    }
                }
            }
            AxCard(modifier = Modifier.weight(1f).fillMaxHeight().padding(4.dp), contentPadding = PaddingValues(16.dp)) {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(selected.title), style = AxTheme.typography.title, color = AxTheme.colors.text)
                    selected.paragraphs.forEach { paragraph ->
                        Text(stringResource(paragraph), style = AxTheme.typography.body, color = AxTheme.colors.text)
                    }
                }
            }
        }
    }
}
