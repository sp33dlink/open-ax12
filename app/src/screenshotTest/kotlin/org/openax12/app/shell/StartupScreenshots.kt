package org.openax12.app.shell

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.android.tools.screenshot.PreviewTest
import org.openax12.core.designsystem.Ax12Preview
import org.openax12.core.designsystem.Ax12Theme
import org.openax12.core.designsystem.AxScreen
import org.openax12.core.designsystem.AxStatusBar
import org.openax12.app.R
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

@Composable
private fun StartupFrame(content: @Composable () -> Unit) {
    Ax12Theme {
        AxScreen(
            modifier = Modifier.fillMaxSize(),
            statusBar = { AxStatusBar(title = "OpenAX12", linkQuality = { 0 }, rxVoltage = { 0f }, txVoltage = { 7.9f }, dateTime = "2026-11-19  10:00") },
        ) {
            content()
        }
    }
}

@PreviewTest
@Ax12Preview
@Composable
fun StartupErrorStockInstalledScreenshot() {
    StartupFrame {
        StartupErrorContent(
            reason = ConnectionError.SerialPortBusy(),
            onRetry = {},
            onContinue = {},
            stockInstalled = true,
            onCloseStock = {},
            onOpenStockSettings = {},
        )
    }
}

@PreviewTest
@Ax12Preview
@Composable
fun StartupConnectingScreenshot() {
    StartupFrame { StartupLoadingContent(R.string.startup_connecting) }
}

@PreviewTest
@Ax12Preview
@Composable
fun StartupConnectingSlowScreenshot() {
    StartupFrame { StartupLoadingContent(R.string.startup_connecting, showStockHint = true, onCloseStock = {}) }
}

@Composable
private fun OnboardingFrame(page: Int, stockInstalled: Boolean = true) {
    Ax12Theme {
        OnboardingContent(
            page = page,
            onPageChange = {},
            stockInstalled = stockInstalled,
            closeStockApp = true,
            onCloseStockAppChange = {},
            startOnBoot = true,
            onStartOnBootChange = {},
            onFinish = {},
            statusBar = { AxStatusBar(title = "OpenAX12", linkQuality = { 0 }, rxVoltage = { 0f }, txVoltage = { 7.9f }, dateTime = "2026-11-19  10:00") },
        )
    }
}

@PreviewTest
@Ax12Preview
@Composable
fun OnboardingWelcomeScreenshot() {
    OnboardingFrame(page = 0)
}

@PreviewTest
@Ax12Preview
@Composable
fun OnboardingChoicesScreenshot() {
    OnboardingFrame(page = 1)
}

@PreviewTest
@Ax12Preview
@Composable
fun OnboardingChoicesNoStockScreenshot() {
    OnboardingFrame(page = 1, stockInstalled = false)
}
