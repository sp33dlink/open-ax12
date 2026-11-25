package org.openax12.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest

@Composable
private fun PadRow(vararg pads: Pair<Float, Float>) {
    Ax12Theme {
        Row(
            modifier = Modifier.fillMaxSize().background(AxTheme.colors.background).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            pads.forEach { (px, py) ->
                StickPad(
                    x = { px },
                    y = { py },
                    modifier = Modifier.size(200.dp),
                    trimX = { 10f },
                    trimY = { -15f },
                )
            }
        }
    }
}

/** Dead centre (centre marker lit), partial deflection, and the four max-deflection borders. */
@PreviewTest
@Ax12Preview
@Composable
fun StickPadStatesScreenshot() {
    PadRow(0f to 0f, 40f to -30f, 100f to 0f, -100f to 0f)
}

/** Top and bottom maxima plus a corner. */
@PreviewTest
@Ax12Preview
@Composable
fun StickPadMaximaScreenshot() {
    PadRow(0f to 100f, 0f to -100f, 100f to 100f, -100f to -100f)
}
