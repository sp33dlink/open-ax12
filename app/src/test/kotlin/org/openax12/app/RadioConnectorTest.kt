package org.openax12.app

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openax12.core.radio.ConnectionError
import org.openax12.core.runtime.StartupState

@OptIn(ExperimentalCoroutinesApi::class)
class RadioConnectorTest {

    private class FakeStock(override val isInstalled: Boolean = true, private val log: MutableList<String>) : StockAppControl {
        override fun close() {
            log += "close"
        }
    }

    private class Rig(
        scope: TestScope,
        autoClose: Boolean = true,
        installed: Boolean = true,
        val results: ArrayDeque<StartupState>,
        var running: Boolean = false,
        private val hang: CompletableDeferred<StartupState>? = null,
    ) {
        val log = mutableListOf<String>()
        val connector = RadioConnector(
            scope = scope,
            stock = FakeStock(installed, log),
            autoClose = { autoClose },
            isRunning = { running },
            startRadio = {
                log += "start"
                if (hang != null && log.count { it == "start" } == 1) hang.await() else results.removeFirst()
            },
            stopRadio = { log += "stop" },
            preConnectSettleMs = 500,
            settleMs = 1_000,
        )
    }

    private val busy = StartupState.RadioFailed(ConnectionError.SerialPortBusy())
    private val firmware = StartupState.RadioFailed(ConnectionError.FirmwareMismatch())

    @Test
    fun `setting on closes the stock app before the first start`() = runTest {
        val rig = Rig(this, results = ArrayDeque(listOf(StartupState.Running)))
        rig.connector.connect()
        advanceUntilIdle()
        assertEquals(listOf("close", "start"), rig.log)
    }

    @Test
    fun `setting off never closes the stock app by itself`() = runTest {
        val rig = Rig(this, autoClose = false, results = ArrayDeque(listOf(busy)))
        rig.connector.connect()
        advanceUntilIdle()
        assertEquals(listOf("start"), rig.log)
    }

    @Test
    fun `stock app not installed means nothing is closed`() = runTest {
        val rig = Rig(this, installed = false, results = ArrayDeque(listOf(busy)))
        rig.connector.connect()
        advanceUntilIdle()
        assertEquals(listOf("start"), rig.log)
    }

    @Test
    fun `a busy port closes the stock app once and retries once`() = runTest {
        val rig = Rig(this, results = ArrayDeque(listOf(busy, busy, busy)))
        rig.connector.connect()
        advanceUntilIdle()
        // close before the first start, one more close and one retry, then it gives up.
        assertEquals(listOf("close", "start", "close", "start"), rig.log)
    }

    @Test
    fun `the retry connects after the stock app was closed`() = runTest {
        val rig = Rig(this, results = ArrayDeque(listOf(busy, StartupState.Running)))
        rig.connector.connect()
        advanceUntilIdle()
        assertEquals(listOf("close", "start", "close", "start"), rig.log)
        assertFalse(rig.connector.recovering.value)
    }

    @Test
    fun `errors that are not the stock app are not retried`() = runTest {
        val rig = Rig(this, results = ArrayDeque(listOf(firmware)))
        rig.connector.connect()
        advanceUntilIdle()
        assertEquals(listOf("close", "start"), rig.log)
    }

    @Test
    fun `a failed communication init is retried once`() = runTest {
        val failed = StartupState.RadioFailed(ConnectionError.CommunicationInitFailed())
        val rig = Rig(this, results = ArrayDeque(listOf(failed, failed)))
        rig.connector.connect()
        advanceUntilIdle()
        assertEquals(listOf("close", "start", "close", "start"), rig.log)
    }

    @Test
    fun `a silent radio is not retried`() = runTest {
        val rig = Rig(this, results = ArrayDeque(listOf(StartupState.RadioFailed(ConnectionError.DeviceTimeout()))))
        rig.connector.connect()
        advanceUntilIdle()
        assertEquals(listOf("close", "start"), rig.log)
        assertFalse(rig.connector.recovering.value)
    }

    @Test
    fun `recovering is set while the retry round runs`() = runTest {
        val rig = Rig(this, results = ArrayDeque(listOf(busy, StartupState.Running)))
        rig.connector.connect()
        advanceTimeBy(500 + 1)
        runCurrent()
        assertTrue(rig.connector.recovering.value)
        advanceUntilIdle()
        assertFalse(rig.connector.recovering.value)
    }

    @Test
    fun `connect while an attempt runs does not start a second one`() = runTest {
        val hang = CompletableDeferred<StartupState>()
        val rig = Rig(this, results = ArrayDeque(), hang = hang)
        rig.connector.connect()
        runCurrent()
        advanceTimeBy(600)
        rig.connector.connect()
        runCurrent()
        assertEquals(1, rig.log.count { it == "start" })
        assertEquals(1, rig.connector.attempt.value)
        hang.complete(StartupState.Running)
        advanceUntilIdle()
    }

    @Test
    fun `closing the stock app aborts a hanging attempt and connects again`() = runTest {
        val hang = CompletableDeferred<StartupState>()
        val rig = Rig(this, results = ArrayDeque(listOf(StartupState.Running)), hang = hang)
        rig.connector.connect()
        advanceTimeBy(600)
        runCurrent()
        assertEquals(listOf("close", "start"), rig.log)
        rig.connector.closeStockAndReconnect()
        advanceUntilIdle()
        assertEquals(listOf("close", "start", "stop", "close", "start"), rig.log)
        assertEquals(2, rig.connector.attempt.value)
    }

    @Test
    fun `closing the stock app after an error just closes and connects`() = runTest {
        val rig = Rig(this, autoClose = false, results = ArrayDeque(listOf(busy, StartupState.Running)))
        rig.connector.connect()
        advanceUntilIdle()
        rig.connector.closeStockAndReconnect()
        advanceUntilIdle()
        assertEquals(listOf("start", "close", "start"), rig.log)
    }

    @Test
    fun `closing the stock app does nothing once the app runs`() = runTest {
        val rig = Rig(this, results = ArrayDeque(listOf(StartupState.Running)))
        rig.connector.connect()
        advanceUntilIdle()
        rig.running = true
        rig.log.clear()
        rig.connector.closeStockAndReconnect()
        advanceUntilIdle()
        assertTrue(rig.log.isEmpty())
    }

    @Test
    fun `slow connect hint shows after 20 seconds and only with the stock app installed`() {
        assertFalse(showSlowConnectHint(19_999, stockInstalled = true))
        assertTrue(showSlowConnectHint(20_000, stockInstalled = true))
        assertFalse(showSlowConnectHint(60_000, stockInstalled = false))
    }

    @Test
    fun `stock buttons show for a possible stock conflict when installed`() {
        assertTrue(showStockAppActions(ConnectionError.SerialPortBusy(), stockInstalled = true))
        assertTrue(showStockAppActions(ConnectionError.DeviceTimeout(), stockInstalled = true))
        assertFalse(showStockAppActions(ConnectionError.FirmwareMismatch(), stockInstalled = true))
        assertFalse(showStockAppActions(ConnectionError.SerialPortBusy(), stockInstalled = false))
    }
}
