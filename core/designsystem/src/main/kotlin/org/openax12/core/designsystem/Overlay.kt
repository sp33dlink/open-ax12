package org.openax12.core.designsystem

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastMap
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex

/**
 * Registry of the overlays currently shown by an [AxOverlayHost]. Overlays are composed inside
 * the host (in the activity's own window, on the fixed canvas) instead of in separate dialog
 * windows: no density or immersive-mode surprises on the AX12 and they render in screenshots.
 */
@Stable
class AxOverlayHostState internal constructor() {
    internal val entries = mutableStateListOf<OverlayEntry>()
}

internal class OverlayEntry(
    val zIndex: Float,
    val locals: CompositionLocalContext,
    val content: State<@Composable () -> Unit>,
)

/** The nearest [AxOverlayHost], or null outside of one. */
val LocalAxOverlayHost = staticCompositionLocalOf<AxOverlayHostState?> { null }

/** Z-order of an ordinary dialog overlay. */
const val AX_OVERLAY_DIALOG: Float = 10f

/** Z-order of the touch-lock overlay: above every dialog. */
const val AX_OVERLAY_LOCK: Float = 100f

/**
 * Hosts in-tree overlays above [content]. [FixedCanvas] installs one around the whole app;
 * use it directly only for previews that do not use [FixedCanvas].
 *
 * Overlays are composed in the same frame in which they are requested (the overlay layer is
 * subcomposed after the content), so a screen that starts with an open dialog renders it in the
 * first frame.
 */
@Composable
fun AxOverlayHost(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val state = remember { AxOverlayHostState() }
    SubcomposeLayout(modifier = modifier) { constraints ->
        val contentPlaceables = subcompose(OverlaySlot.CONTENT) {
            CompositionLocalProvider(LocalAxOverlayHost provides state, content = content)
        }.fastMap { it.measure(constraints) }
        val width = contentPlaceables.maxOfOrNull { it.width } ?: constraints.minWidth
        val height = contentPlaceables.maxOfOrNull { it.height } ?: constraints.minHeight
        val overlayConstraints = constraints.copy(minWidth = width, maxWidth = width, minHeight = height, maxHeight = height)
        val overlayPlaceables = subcompose(OverlaySlot.OVERLAYS) {
            CompositionLocalProvider(LocalAxOverlayHost provides state) {
                // Snapshot of the list: entries are added and removed by RememberObservers.
                state.entries.toList().sortedBy { it.zIndex }.fastForEach { entry ->
                    key(entry) {
                        CompositionLocalProvider(entry.locals) {
                            CompositionLocalProvider(LocalAxOverlayHost provides state) {
                                Box(modifier = Modifier.fillMaxSize().zIndex(entry.zIndex)) {
                                    entry.content.value()
                                }
                            }
                        }
                    }
                }
            }
        }.fastMap { it.measure(overlayConstraints) }
        layout(width, height) {
            contentPlaceables.fastForEach { it.place(0, 0) }
            overlayPlaceables.fastForEach { it.place(0, 0) }
        }
    }
}

private enum class OverlaySlot { CONTENT, OVERLAYS }

private class OverlayRegistration(
    private val host: AxOverlayHostState,
    private val entry: OverlayEntry,
) : RememberObserver {
    override fun onRemembered() {
        host.entries.add(entry)
    }

    override fun onForgotten() {
        host.entries.remove(entry)
    }

    override fun onAbandoned() {
        host.entries.remove(entry)
    }
}

/**
 * Shows [content] in a layer covering the whole canvas for as long as this call stays in the
 * composition. The content gets the full canvas; draw a scrim and consume touches yourself if
 * the overlay is modal ([AxDialog] does).
 *
 * Composition locals of the call site are available inside [content]. Outside of an
 * [AxOverlayHost] the overlay falls back to a full-screen platform dialog window.
 *
 * @param zIndex stacking order among overlays, see [AX_OVERLAY_DIALOG] and [AX_OVERLAY_LOCK]
 * @param onBack called for the system back action while the overlay is shown; null leaves back
 *   to whatever is underneath
 */
@Composable
fun AxOverlay(
    zIndex: Float = AX_OVERLAY_DIALOG,
    onBack: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val host = LocalAxOverlayHost.current
    if (host == null) {
        Dialog(
            onDismissRequest = { onBack?.invoke() },
            properties = DialogProperties(
                dismissOnBackPress = onBack != null,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false,
            ),
        ) {
            Box(modifier = Modifier.fillMaxSize()) { content() }
        }
        return
    }
    val currentContent = rememberUpdatedState(content)
    val currentOnBack = rememberUpdatedState(onBack)
    val locals = currentCompositionLocalContext
    val hasBack = onBack != null
    remember(host, zIndex, hasBack) {
        val wrapped: @Composable () -> Unit = {
            if (hasBack) AxBackHandler { currentOnBack.value?.invoke() }
            currentContent.value()
        }
        OverlayRegistration(host, OverlayEntry(zIndex, locals, rememberedStateOf(wrapped)))
    }
}

private fun <T> rememberedStateOf(value: T): State<T> = object : State<T> {
    override val value: T = value
}

/**
 * [BackHandler] that is a no-op where no back dispatcher exists (previews, screenshot tests).
 */
@Composable
fun AxBackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    if (LocalOnBackPressedDispatcherOwner.current != null) {
        BackHandler(enabled = enabled, onBack = onBack)
    }
}
