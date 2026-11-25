package org.openax12.feature.models

import androidx.compose.runtime.Composable
import com.android.tools.screenshot.PreviewTest
import org.openax12.core.designsystem.Ax12Preview
import org.openax12.core.designsystem.AxThemeMode

private fun listState(
    layout: ModelsLayout = ModelsLayout.GRID,
    models: List<org.openax12.core.data.ModelEntry> = PreviewModels,
) = ModelsUiState(loaded = true, models = models, layout = layout)

@PreviewTest
@Ax12Preview
@Composable
fun ModelsGridScreenshot() {
    ShellFrame { ModelsScreen(listState(), ModelsActions(), PreviewNow) }
}

@PreviewTest
@Ax12Preview
@Composable
fun ModelsGridDarkScreenshot() {
    ShellFrame(AxThemeMode.DARK) { ModelsScreen(listState(), ModelsActions(), PreviewNow) }
}

@PreviewTest
@Ax12Preview
@Composable
fun ModelsListScreenshot() {
    ShellFrame { ModelsScreen(listState(ModelsLayout.LIST), ModelsActions(), PreviewNow) }
}

@PreviewTest
@Ax12Preview
@Composable
fun ModelsBannersScreenshot() {
    ShellFrame {
        ModelsScreen(
            state = listState().copy(
                deviceInSync = false,
                needsStoragePermission = true,
                externalChanges = listOf(PreviewChange),
            ),
            actions = ModelsActions(),
            nowMs = PreviewNow,
        )
    }
}

@PreviewTest
@Ax12Preview
@Composable
fun ModelsLinkConfirmScreenshot() {
    ShellFrame {
        ModelsScreen(
            state = listState().copy(guard = DeviceWriteGuard.GuardState(GuardStep.LINK_UP)),
            actions = ModelsActions(),
            nowMs = PreviewNow,
        )
    }
}

@PreviewTest
@Ax12Preview
@Composable
fun ModelsExternalChangeScreenshot() {
    ShellFrame {
        ModelsScreen(
            state = listState().copy(externalChanges = listOf(PreviewChange, PreviewChange.copy(name = "New folder", isNew = true, modelId = null)), reviewing = PreviewChange),
            actions = ModelsActions(),
            nowMs = PreviewNow,
        )
    }
}

@PreviewTest
@Ax12Preview
@Composable
fun ModelsEmptyScreenshot() {
    ShellFrame { ModelsScreen(listState(models = emptyList()), ModelsActions(), PreviewNow) }
}
