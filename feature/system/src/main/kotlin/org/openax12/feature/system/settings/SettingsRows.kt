package org.openax12.feature.system.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.openax12.core.designsystem.AxIconButton
import org.openax12.core.designsystem.AxIcons
import org.openax12.core.designsystem.AxSlider
import org.openax12.core.designsystem.AxTheme
import kotlin.math.roundToInt

/**
 * A volume row: label, slider, percentage and an optional test button. The slider keeps the
 * dragged value locally and reports it once the finger lifts, so the settings are written once
 * per gesture.
 */
@Composable
internal fun VolumeRow(
    label: String,
    percent: Int,
    onCommit: (Int) -> Unit,
    modifier: Modifier = Modifier,
    testLabel: String? = null,
    testIcon: androidx.compose.ui.graphics.vector.ImageVector = AxIcons.Play,
    onTest: (() -> Unit)? = null,
) {
    var drag by remember(percent) { mutableFloatStateOf(percent.toFloat()) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(46.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = label,
            style = AxTheme.typography.bodyStrong,
            color = AxTheme.colors.text,
            maxLines = 1,
            modifier = Modifier.width(120.dp),
        )
        AxSlider(
            value = drag,
            onValueChange = { drag = it },
            valueRange = 0f..100f,
            steps = 19,
            onValueChangeFinished = { onCommit(drag.roundToInt().coerceIn(0, 100)) },
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "${drag.roundToInt()}%",
            style = AxTheme.typography.value,
            color = AxTheme.colors.text,
            textAlign = TextAlign.End,
            modifier = Modifier.width(46.dp),
        )
        if (onTest != null) {
            AxIconButton(
                icon = testIcon,
                contentDescription = testLabel,
                onClick = onTest,
            )
        } else {
            Row(modifier = Modifier.width(44.dp)) {}
        }
    }
}
