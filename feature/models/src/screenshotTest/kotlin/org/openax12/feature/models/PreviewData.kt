package org.openax12.feature.models

import androidx.compose.runtime.Composable
import org.openax12.core.data.ExternalChange
import org.openax12.core.data.ImportKind
import org.openax12.core.data.ImportValidation
import org.openax12.core.data.ModelEntry
import org.openax12.core.data.ModelSnapshot
import org.openax12.core.data.SnapshotReason
import org.openax12.core.designsystem.AxBottomBar
import org.openax12.core.designsystem.AxDestination
import org.openax12.core.designsystem.AxScreen
import org.openax12.core.designsystem.AxStatusBar
import org.openax12.core.designsystem.AxThemeMode
import org.openax12.core.designsystem.Ax12Theme
import org.openax12.core.designsystem.FixedCanvas
import org.openax12.core.model.ModelType
import java.time.LocalDateTime
import java.time.ZoneId

private fun at(day: Int, hour: Int, minute: Int): Long =
    LocalDateTime.of(2026, 11, day, hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

/** "Now" of all previews: 19 Nov 2026, 14:05. */
internal val PreviewNow: Long = at(19, 14, 5)

internal val PreviewModels: List<ModelEntry> = listOf(
    ModelEntry(id = "1", name = "Swift 3m", type = ModelType.GLIDER, modelMatch = 3, lastUsedMs = at(19, 9, 40), isCurrent = true),
    ModelEntry(id = "2", name = "Easy Star", type = ModelType.PLANE, modelMatch = 1, lastUsedMs = at(18, 17, 20)),
    ModelEntry(id = "3", name = "Zagi Wing", type = ModelType.WING, lastUsedMs = at(2, 10, 0), externallyModified = true),
    ModelEntry(id = "4", name = "Mini Quad 5\"", type = ModelType.MULTIROTOR, modelMatch = 7),
    ModelEntry(id = "5", name = "Align T-Rex 450", type = ModelType.HELICOPTER, modelMatch = 12, lastUsedMs = at(15, 16, 45)),
    ModelEntry(id = "6", name = "Stock import", type = ModelType.OTHER, lastUsedMs = at(10, 8, 15)),
)

internal val PreviewSnapshots: List<ModelSnapshot> = listOf(
    ModelSnapshot("s5", "1", at(19, 9, 38), SnapshotReason.SAVE, 18_400),
    ModelSnapshot("s4", "1", at(18, 20, 2), SnapshotReason.BEFORE_RESTORE, 18_100),
    ModelSnapshot("s3", "1", at(18, 19, 55), SnapshotReason.SAVE, 17_950),
    ModelSnapshot("s2", "1", at(12, 11, 30), SnapshotReason.BEFORE_REIMPORT, 15_200),
    ModelSnapshot("s1", "1", at(1, 8, 12), SnapshotReason.SAVE, 9_800),
)

internal val PreviewChange = ExternalChange(modelId = "3", name = "Zagi Wing", folderPath = "/sdcard/OpenAX12/models/Zagi Wing", isNew = false)

internal val PreviewImport = ImportValidation.Valid(
    kind = ImportKind.ARCHIVE,
    name = "Swift 3m",
    nameExists = true,
    suggestedName = "Swift 3m (2)",
    type = ModelType.GLIDER,
    hasPicture = true,
    sizeBytes = 214_000,
)

/** Frames [content] like the app shell does: status bar above, bottom bar below, on the fixed canvas. */
@Composable
internal fun ShellFrame(mode: AxThemeMode = AxThemeMode.LIGHT, content: @Composable () -> Unit) {
    Ax12Theme(mode) {
        FixedCanvas {
            AxScreen(
                statusBar = {
                    AxStatusBar(
                        title = "Models",
                        linkQuality = { 98 },
                        rxVoltage = { 8.3f },
                        txVoltage = { 7.9f },
                        dateTime = "2026-11-19 14:05",
                    )
                },
                bottomBar = { AxBottomBar(selected = AxDestination.MODELS, onSelect = {}) },
            ) { content() }
        }
    }
}
