package org.openax12.app.shell

import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import org.openax12.core.model.AppSettings
import org.openax12.core.runtime.AppGraph

/** Screen brightness while the touch lock dims the screen (fraction: nearly dark but readable). */
private const val DIMMED_BRIGHTNESS = 0.08f

/**
 * Applies the settings that belong to the activity window: keep-screen-on and the screen
 * brightness (the special function's override, or dimmed while the touch lock is on and
 * `TouchLockBehaviour.dimWhileLocked` is set).
 */
@Composable
internal fun WindowSettingsEffect(window: Window, graph: AppGraph, settings: AppSettings, locked: Boolean) {
    DisposableEffect(window, settings.keepScreenOn) {
        if (settings.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    val override by graph.runtime.brightnessOverride.collectAsStateWithLifecycle()
    val dim = locked && settings.touchLock.dimWhileLocked
    LaunchedEffect(window, override, dim) {
        val requested = override
        val attributes = window.attributes
        attributes.screenBrightness = when {
            dim -> DIMMED_BRIGHTNESS
            requested != null -> (requested / 100f).coerceIn(0.01f, 1f)
            else -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        window.attributes = attributes
    }
}

/**
 * State of the touch lock (SPEC section 9): the bottom bar sets it, holding the overlay's pill
 * clears it, and two settings can set it automatically: after `autoLockSec` seconds without a
 * touch and when the receiver link comes up (`lockWhenLinked`).
 */
internal class TouchLockState(initiallyLocked: Boolean) {
    /** True while all touches are blocked. */
    var locked by mutableStateOf(initiallyLocked)
        private set

    /** Wall-clock time of the last touch, for the idle detection. */
    @Volatile
    var lastTouchMs: Long = System.currentTimeMillis()

    /** Locks the screen. */
    fun lock() {
        locked = true
    }

    /** Unlocks the screen and restarts the idle timer. */
    fun unlock() {
        locked = false
        lastTouchMs = System.currentTimeMillis()
    }
}

/** Remembers the [TouchLockState]; the locked flag survives configuration changes and process restarts. */
@Composable
internal fun rememberTouchLockState(): TouchLockState = rememberSaveable(
    saver = Saver<TouchLockState, Boolean>(save = { it.locked }, restore = { TouchLockState(it) }),
) { TouchLockState(initiallyLocked = false) }

/** The automatic triggers of the touch lock; see [TouchLockState]. */
@Composable
internal fun TouchLockAutomation(state: TouchLockState, graph: AppGraph, settings: AppSettings) {
    val behaviour = settings.touchLock
    val autoLockSec by rememberUpdatedState(behaviour.autoLockSec)
    LaunchedEffect(behaviour.autoLockSec > 0) {
        if (behaviour.autoLockSec <= 0) return@LaunchedEffect
        while (true) {
            delay(1_000)
            val limitMs = autoLockSec * 1_000L
            if (!state.locked && limitMs > 0 && System.currentTimeMillis() - state.lastTouchMs >= limitMs) state.lock()
        }
    }
    LaunchedEffect(behaviour.lockWhenLinked, graph) {
        if (!behaviour.lockWhenLinked) return@LaunchedEffect
        // Only the change from "no link" to "link": an app started with the link up is not locked.
        graph.radio.live.map { it.status.isLinkUp }.distinctUntilChanged().drop(1).collect { up -> if (up) state.lock() }
    }
}

/** Reports every touch to [state] without consuming it (idle detection for the auto lock). */
internal fun Modifier.trackTouches(state: TouchLockState): Modifier = pointerInput(state) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Initial)
            state.lastTouchMs = System.currentTimeMillis()
        }
    }
}
