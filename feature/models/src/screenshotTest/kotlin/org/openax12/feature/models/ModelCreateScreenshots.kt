package org.openax12.feature.models

import androidx.compose.runtime.Composable
import com.android.tools.screenshot.PreviewTest
import org.openax12.core.designsystem.Ax12Preview
import org.openax12.core.engine.ModelTemplates

private val switches = ('A'..'F').map { SwitchChoice(org.openax12.core.model.SourceId.switch(it), "S$it") }

private fun create(templateId: String, name: String) = CreateUiState(
    templateId = templateId,
    name = name,
    switches = switches,
    existingNames = PreviewModels.map { it.name },
)

@PreviewTest
@Ax12Preview
@Composable
fun ModelCreateGliderScreenshot() {
    ShellFrame { ModelCreateScreen(create(ModelTemplates.GLIDER, "Glider"), CreateActions()) }
}

@PreviewTest
@Ax12Preview
@Composable
fun ModelCreateMultirotorScreenshot() {
    ShellFrame { ModelCreateScreen(create(ModelTemplates.MULTIROTOR, "Multirotor"), CreateActions()) }
}

@PreviewTest
@Ax12Preview
@Composable
fun ModelCreateBlankNameTakenScreenshot() {
    ShellFrame { ModelCreateScreen(create(BLANK_TEMPLATE_ID, "Easy Star"), CreateActions()) }
}

@PreviewTest
@Ax12Preview
@Composable
fun ModelCreateUnsavedScreenshot() {
    ShellFrame {
        ModelCreateScreen(
            create(ModelTemplates.PLANE, "Plane").copy(guard = DeviceWriteGuard.GuardState(GuardStep.UNSAVED)),
            CreateActions(),
        )
    }
}
