package org.openax12.feature.models

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

/**
 * Returns a function that opens a system file picker for [mimeType] (images, or any file type).
 *
 * The document picker (`ACTION_OPEN_DOCUMENT`) is tried first; if the radio's Android build has
 * no such activity, `ACTION_GET_CONTENT` is tried, and if that is missing too [onUnavailable] is
 * called (see the document-picker entry in docs/ASSUMPTIONS.md).
 *
 * @param onPicked called with the chosen file, or null if the user cancelled
 */
@Composable
internal fun rememberFilePicker(
    mimeType: String,
    onPicked: (Uri?) -> Unit,
    onUnavailable: () -> Unit,
): () -> Unit {
    val currentOnPicked = rememberUpdatedState(onPicked)
    val currentOnUnavailable = rememberUpdatedState(onUnavailable)
    val getContent = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { currentOnPicked.value(it) }
    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { currentOnPicked.value(it) }
    return remember(mimeType, getContent, openDocument) {
        {
            try {
                openDocument.launch(arrayOf(mimeType))
            } catch (_: ActivityNotFoundException) {
                try {
                    getContent.launch(mimeType)
                } catch (_: ActivityNotFoundException) {
                    currentOnUnavailable.value()
                }
            }
        }
    }
}
