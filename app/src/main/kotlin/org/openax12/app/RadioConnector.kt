package org.openax12.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.openax12.core.radio.ConnectionError
import org.openax12.core.runtime.StartupState

/** After this long in "Connecting to the radio…" the screen suggests that the stock app may still run. */
internal const val SLOW_CONNECT_HINT_AFTER_MS: Long = 20_000L

/** Time the kernel needs to release the serial port after the stock app's process was killed. */
internal const val STOCK_APP_SETTLE_MS: Long = 1_000L

/** Shorter wait before the very first connect: the kill is only a precaution there. */
internal const val STOCK_APP_PRE_CONNECT_SETTLE_MS: Long = 500L

/**
 * Whether the "stock app may still be running" hint (and its button) shows under the spinner:
 * only when the stock app is installed and the connection took [SLOW_CONNECT_HINT_AFTER_MS].
 */
internal fun showSlowConnectHint(connectingForMs: Long, stockInstalled: Boolean): Boolean =
    stockInstalled && connectingForMs >= SLOW_CONNECT_HINT_AFTER_MS

/** Whether the error screen offers "Close stock app" / "Open app settings" for [reason]. */
internal fun showStockAppActions(reason: ConnectionError, stockInstalled: Boolean): Boolean =
    stockInstalled && reason.mayBeStockAppConflict

/**
 * Whether a failed start is worth one automatic "close the stock app and try again": the setting
 * is on, the stock app exists on this device and the error looks like a held serial port.
 */
internal fun shouldCloseAndRetry(result: StartupState, autoClose: Boolean, stockInstalled: Boolean): Boolean =
    autoClose && stockInstalled && result is StartupState.RadioFailed && result.reason.mayBeStockAppConflict

/**
 * Starts the radio connection and deals with the stock RadioMaster app around it. It is the only
 * caller of `AppGraph.start()`: the service, the activity fallback and every Retry button go
 * through [connect], so the stock-app handling cannot be forgotten on one path.
 *
 * One call to the connection sequence:
 * 1. if "close the stock app automatically" is on and the stock app is installed, close it and
 *    wait [preConnectSettleMs] (the process holds the port until it is really gone);
 * 2. start the radio;
 * 3. if that failed in the way a running stock app causes ([shouldCloseAndRetry]), close it once
 *    more, wait [settleMs] and start again — once, never in a loop. [recovering] is true during
 *    that second round so that the UI shows "Connecting…" instead of flashing the error screen.
 *
 * Plain Kotlin on purpose (Android parts are injected) so that the decisions are unit-tested.
 *
 * @param scope scope the attempts run in (the app graph's scope, so they outlive the UI)
 * @param stock the stock app
 * @param autoClose reads the system setting at the time of the call
 * @param isRunning true once the app has finished starting (then nothing is cancelled)
 * @param startRadio runs `AppGraph.start()` and returns its result
 * @param stopRadio `Radio.stop()`; ends a hanging connect so that a new one starts from scratch
 */
internal class RadioConnector(
    private val scope: CoroutineScope,
    private val stock: StockAppControl,
    private val autoClose: suspend () -> Boolean,
    private val isRunning: () -> Boolean,
    private val startRadio: suspend () -> StartupState,
    private val stopRadio: suspend () -> Unit,
    private val preConnectSettleMs: Long = STOCK_APP_PRE_CONNECT_SETTLE_MS,
    private val settleMs: Long = STOCK_APP_SETTLE_MS,
) {
    private val operations = Mutex()
    private var attemptJob: Job? = null
    private val _attempt = MutableStateFlow(0)
    private val _recovering = MutableStateFlow(false)

    /** Counts the connection attempts started so far; the UI restarts its "slow" timer when it changes. */
    val attempt: StateFlow<Int> = _attempt.asStateFlow()

    /** True while the automatic close-and-retry round runs. */
    val recovering: StateFlow<Boolean> = _recovering.asStateFlow()

    /** Whether the stock app is installed on this device (the UI hides its buttons otherwise). */
    val stockInstalled: Boolean get() = stock.isInstalled

    /**
     * Connects to the radio unless an attempt is already running. Returns at once; follow the
     * progress in `AppGraph.startup`. Also the Retry action of the error screens.
     */
    fun connect() {
        scope.launch {
            operations.withLock {
                if (attemptJob?.isActive == true) return@withLock
                launchAttempt(closeFirst = true)
            }
        }
    }

    /**
     * The "Close stock app" button: ends a connection attempt that hangs (if one runs), closes the
     * stock app, waits [settleMs] and connects again from scratch. Does nothing when the app is
     * already running.
     */
    fun closeStockAndReconnect() {
        scope.launch {
            operations.withLock {
                if (isRunning()) return@withLock
                val active = attemptJob?.takeIf { it.isActive }
                if (active != null) {
                    active.cancelAndJoin()
                    stopRadio()
                }
                stock.close()
                delay(settleMs)
                launchAttempt(closeFirst = false)
            }
        }
    }

    private fun launchAttempt(closeFirst: Boolean) {
        _attempt.value += 1
        attemptJob = scope.launch { runSequence(closeFirst) }
    }

    private suspend fun runSequence(closeFirst: Boolean) {
        val auto = autoClose()
        if (auto && closeFirst && stock.isInstalled) {
            stock.close()
            delay(preConnectSettleMs)
        }
        val result = startRadio()
        if (shouldCloseAndRetry(result, auto, stock.isInstalled)) {
            _recovering.value = true
            try {
                stock.close()
                delay(settleMs)
                startRadio()
            } finally {
                _recovering.value = false
            }
        }
    }
}
