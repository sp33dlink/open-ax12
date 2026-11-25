package org.openax12.feature.models

import androidx.compose.runtime.Composable
import com.android.tools.screenshot.PreviewTest
import org.openax12.core.designsystem.Ax12Preview

private fun detail(
    index: Int = 0,
    dialog: DetailDialog = DetailDialog.None,
    snapshots: List<org.openax12.core.data.ModelSnapshot>? = PreviewSnapshots,
) = ModelDetailUiState(
    entry = PreviewModels[index].copy(folderPath = "/sdcard/OpenAX12/models/${PreviewModels[index].name}"),
    notes = "Winch launch, CG at 62 mm. Check the flap servo arms before flying.",
    dialog = dialog,
    snapshots = snapshots,
    existingNames = PreviewModels.map { it.name },
)

@PreviewTest
@Ax12Preview
@Composable
fun ModelDetailCurrentScreenshot() {
    ShellFrame { ModelDetailScreen(detail(), DetailActions(), PreviewNow) }
}

@PreviewTest
@Ax12Preview
@Composable
fun ModelDetailOtherScreenshot() {
    ShellFrame { ModelDetailScreen(detail(1).copy(notes = ""), DetailActions(), PreviewNow) }
}

@PreviewTest
@Ax12Preview
@Composable
fun ModelDetailSnapshotsScreenshot() {
    ShellFrame { ModelDetailScreen(detail(dialog = DetailDialog.Snapshots), DetailActions(), PreviewNow) }
}

@PreviewTest
@Ax12Preview
@Composable
fun ModelDetailRestoreScreenshot() {
    ShellFrame {
        ModelDetailScreen(
            detail(dialog = DetailDialog.RestoreSnapshot(PreviewSnapshots[1])).copy(dirty = true),
            DetailActions(),
            PreviewNow,
        )
    }
}

@PreviewTest
@Ax12Preview
@Composable
fun ModelDetailDeleteScreenshot() {
    ShellFrame { ModelDetailScreen(detail(1, DetailDialog.Delete), DetailActions(), PreviewNow) }
}

@PreviewTest
@Ax12Preview
@Composable
fun ModelDetailRenameScreenshot() {
    ShellFrame { ModelDetailScreen(detail(1, DetailDialog.Rename), DetailActions(), PreviewNow) }
}
