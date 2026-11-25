package org.openax12.app.shell

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.android.tools.screenshot.PreviewTest
import org.openax12.core.designsystem.Ax12Preview
import org.openax12.core.designsystem.Ax12Theme
import org.openax12.core.designsystem.AxScreen
import org.openax12.core.designsystem.AxStatusBar
import org.openax12.core.radio.ConnectionError

@PreviewTest
@Ax12Preview
@Composable
fun StartupErrorScreenshot() {
    Ax12Theme {
        AxScreen(
            modifier = Modifier.fillMaxSize(),
            statusBar = { AxStatusBar(title = "OpenAX12", linkQuality = { 0 }, rxVoltage = { 0f }, txVoltage = { 7.9f }, dateTime = "2026-11-19  10:00") },
        ) {
            StartupErrorContent(reason = ConnectionError.DeviceTimeout(), onRetry = {}, onContinue = {})
        }
    }
}
