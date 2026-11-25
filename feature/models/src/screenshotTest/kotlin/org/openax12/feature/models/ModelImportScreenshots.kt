package org.openax12.feature.models

import androidx.compose.runtime.Composable
import com.android.tools.screenshot.PreviewTest
import org.openax12.core.data.ImportProblem
import org.openax12.core.designsystem.Ax12Preview

@PreviewTest
@Ax12Preview
@Composable
fun ModelImportReadyScreenshot() {
    ShellFrame {
        ModelImportScreen(
            ImportUiState(ImportPhase.Ready(PreviewImport), "Swift 3m (2)", PreviewModels.map { it.name }),
            ImportActions(),
        )
    }
}

@PreviewTest
@Ax12Preview
@Composable
fun ModelImportIdleScreenshot() {
    ShellFrame { ModelImportScreen(ImportUiState(), ImportActions()) }
}

@PreviewTest
@Ax12Preview
@Composable
fun ModelImportInvalidScreenshot() {
    ShellFrame {
        ModelImportScreen(ImportUiState(ImportPhase.Invalid(ImportProblem.SCHEMA_TOO_NEW, "")), ImportActions())
    }
}
