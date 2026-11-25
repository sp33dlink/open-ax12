package org.openax12.core.radio.fake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openax12.core.model.CompareOp
import org.openax12.core.model.SOURCE_COUNT
import org.openax12.core.model.SourceId
import org.openax12.core.model.Sources
import org.openax12.core.model.SwitchCondition
import org.openax12.core.model.SwitchPosition
import org.openax12.core.model.TimerAlertMode
import org.openax12.core.model.TimerConfig
import org.openax12.core.model.TimerNotify
import org.openax12.core.model.TimerReport
import org.openax12.core.radio.RadioEvent

class SimTimersTest {

    private val timers = SimTimers()
    private val events = ArrayList<RadioEvent>()
    private val logic = IntArray(SOURCE_COUNT) { SimDefaults.restPosition(it) }

    private val throttleUp = SwitchCondition(SourceId.THR, CompareOp.GREATER, -90)
    private val shDown = SwitchCondition.at(SourceId.SH, SwitchPosition.DOWN)

    private fun withTimer(timer: TimerConfig): SimConfig {
        val base = SimConfig()
        return base.copy(timers = base.timers.toMutableList().also { it[timer.index] = timer })
    }

    private fun withCounter(counter: TimerConfig): SimConfig {
        val base = SimConfig()
        return base.copy(counters = base.counters.toMutableList().also { it[counter.index] = counter })
    }

    /** Runs [ms] of simulated time in 40 ms frames. */
    private fun run(config: SimConfig, ms: Long) {
        var left = ms
        while (left > 0) {
            timers.step(config, logic, 40, events::add)
            left -= 40
        }
    }

    private fun frame(config: SimConfig) = timers.step(config, logic, 40, events::add)

    private fun notifies(): List<TimerNotify> = events.filterIsInstance<RadioEvent.TimerNotified>().map { it.notify }
    private fun timerReports(index: Int): List<TimerReport> =
        events.filterIsInstance<RadioEvent.TimerReported>().map { it.report }.filter { it.index == index }

    private fun counterReports(index: Int): List<TimerReport> =
        events.filterIsInstance<RadioEvent.CounterReported>().map { it.report }.filter { it.index == index }

    // ---- timers ----------------------------------------------------------------------------

    @Test
    fun countUpTimerRunsWhileItsConditionHolds() {
        val config = withTimer(TimerConfig(index = 0, switch = throttleUp))
        run(config, 1000)
        assertEquals(0, timers.timerStates[0].count)
        assertFalse(timers.timerStates[0].running)

        logic[Sources.THR] = 0
        run(config, 3000)
        val state = timers.timerStates[0]
        assertEquals(3, state.count)
        assertTrue(state.running)
        assertTrue(state.showsElapsed)
        assertEquals(0, state.startTime)
        assertEquals(listOf(0, 1, 2, 3), timerReports(0).map { it.count })
        assertEquals(listOf(TimerNotify(0, running = true, code = 0, count = 0)), notifies())

        logic[Sources.THR] = -100
        run(config, 2000)
        assertEquals(3, timers.timerStates[0].count)
        assertFalse(timers.timerStates[0].running)
        assertEquals(TimerNotify(0, running = false, code = 0, count = 3), notifies().last())
    }

    @Test
    fun timerWithoutConditionNeverRuns() {
        val config = withTimer(TimerConfig(index = 1))
        run(config, 5000)
        assertEquals(0, timers.timerStates[1].count)
        assertFalse(timers.timerStates[1].running)
        assertTrue(notifies().isEmpty())
    }

    @Test
    fun countdownAlertsAtIntervalsAndStopsAtZero() {
        val config = withTimer(
            TimerConfig(
                index = 0,
                switch = throttleUp,
                startTime = 5,
                countUp = false,
                alertMode = TimerAlertMode.BEEPS,
                alertIntervalSec = 2,
            ),
        )
        logic[Sources.THR] = 50
        frame(config)
        assertEquals(5, timers.timerStates[0].count)
        assertFalse(timers.timerStates[0].showsElapsed)
        assertEquals(5, timers.timerStates[0].startTime)

        run(config, 7000)
        val state = timers.timerStates[0]
        assertEquals(0, state.count)
        assertFalse(state.running)
        assertEquals(listOf(5, 4, 3, 2, 1, 0), timerReports(0).map { it.count })
        assertTrue(timerReports(0).none { it.showsElapsed })

        val beeps = TimerAlertMode.BEEPS.sdk
        assertEquals(
            listOf(
                TimerNotify(0, running = true, code = 0, count = 5),
                TimerNotify(0, running = true, code = beeps, count = 4),
                TimerNotify(0, running = true, code = beeps, count = 2),
                TimerNotify(0, running = false, code = beeps, count = 0),
            ),
            notifies(),
        )

        // Reset is the only command: it starts the countdown over.
        events.clear()
        assertTrue(timers.reset(config, 0, events::add))
        assertEquals(TimerAlertMode.NOTIFY_RESET, notifies().single().code)
        assertEquals(5, notifies().single().count)
        run(config, 2000)
        assertEquals(3, timers.timerStates[0].count)
        assertTrue(timers.timerStates[0].running)
    }

    @Test
    fun loopingCountdownStartsOver() {
        val config = withTimer(TimerConfig(index = 2, switch = throttleUp, startTime = 2, countUp = false, loopMode = 1))
        logic[Sources.THR] = 50
        run(config, 3000)
        assertEquals(1, timers.timerStates[2].count)
        assertTrue(timers.timerStates[2].running)
        assertEquals(listOf(2, 1, 2, 1), timerReports(2).map { it.count })
    }

    @Test
    fun countUpWithPeriodAlertsOnceAndKeepsCounting() {
        val config = withTimer(
            TimerConfig(index = 0, switch = throttleUp, startTime = 3, countUp = true, alertMode = TimerAlertMode.VOICE),
        )
        logic[Sources.THR] = 50
        run(config, 6000)
        assertEquals(6, timers.timerStates[0].count)
        assertTrue(timers.timerStates[0].showsElapsed)
        val alerts = notifies().filter { it.code == TimerAlertMode.VOICE.sdk }
        assertEquals(listOf(3), alerts.map { it.count })
    }

    @Test
    fun resetConditionResetsOnItsRisingEdge() {
        val config = withTimer(
            TimerConfig(index = 0, switch = throttleUp, resetSwitch = SwitchCondition.at(SourceId.SA, SwitchPosition.DOWN)),
        )
        logic[Sources.THR] = 50
        run(config, 2000)
        assertEquals(2, timers.timerStates[0].count)

        logic[Sources.SA] = 100
        frame(config)
        assertEquals(0, timers.timerStates[0].count)
        assertTrue(notifies().any { it.code == TimerAlertMode.NOTIFY_RESET })

        // Holding the switch does not keep the timer at zero.
        run(config, 2000)
        assertEquals(2, timers.timerStates[0].count)
    }

    @Test
    fun readBackFieldsAndConfigChange() {
        val countdown = TimerConfig(index = 0, switch = throttleUp, startTime = 60, countUp = false)
        val config = withTimer(countdown)
        logic[Sources.THR] = 50
        run(config, 10_000)
        val read = timers.withReadBack(countdown, isCounter = false)
        assertEquals(50, read.count)
        assertEquals(0, read.runningDirection)
        assertEquals(60, read.runningStartTime)

        // A new period starts the timer over; an unrelated change does not.
        val shorter = countdown.copy(startTime = 30)
        timers.configChanged(0, isCounter = false, old = countdown, new = shorter)
        frame(withTimer(shorter))
        assertEquals(30, timers.timerStates[0].count)
        run(withTimer(shorter), 5000)
        val louder = shorter.copy(alertMode = TimerAlertMode.HAPTIC)
        timers.configChanged(0, isCounter = false, old = shorter, new = louder)
        frame(withTimer(louder))
        assertEquals(25, timers.timerStates[0].count)
    }

    @Test
    fun statesListIsReusedWhileNothingChanges() {
        val config = withTimer(TimerConfig(index = 0, switch = throttleUp))
        frame(config)
        val first = timers.timerStates
        frame(config)
        assertSame(first, timers.timerStates)
    }

    // ---- counters --------------------------------------------------------------------------

    private fun click(config: SimConfig) {
        logic[Sources.SH] = 100
        frame(config)
        logic[Sources.SH] = -100
        frame(config)
    }

    @Test
    fun counterCountsRisingEdgesUpToItsMaximum() {
        val config = withCounter(TimerConfig(index = 0, switch = shDown, startTime = 3, outputMin = -100, outputMax = 100))
        frame(config)
        assertEquals(0, timers.counterStates[0].count)
        assertEquals(-100, logic[Sources.CNT1])

        click(config)
        assertEquals(1, timers.counterStates[0].count)
        assertEquals(-33, logic[Sources.CNT1])
        click(config)
        assertEquals(33, logic[Sources.CNT1])
        click(config)
        assertEquals(3, timers.counterStates[0].count)
        assertEquals(100, logic[Sources.CNT1])
        click(config)
        assertEquals(3, timers.counterStates[0].count)

        assertEquals(listOf(0, 1, 2, 3), counterReports(0).map { it.count })
        assertTrue(counterReports(0).all { it.startTime == 3 && it.showsElapsed })

        // Holding the switch counts once.
        events.clear()
        assertTrue(timers.reset(config, 3, events::add))
        assertEquals(TimerNotify(3, running = false, code = TimerAlertMode.NOTIFY_RESET, count = 0), notifies().single())
        logic[Sources.SH] = 100
        run(config, 1000)
        assertEquals(1, timers.counterStates[0].count)
        assertTrue(timers.counterStates[0].running)
    }

    @Test
    fun loopingCounterWraps() {
        val config = withCounter(TimerConfig(index = 1, switch = shDown, startTime = 2, loopMode = 1))
        frame(config)
        repeat(3) { click(config) }
        assertEquals(0, timers.counterStates[1].count)
        click(config)
        assertEquals(1, timers.counterStates[1].count)
    }

    @Test
    fun downCounterStartsAtItsMaximum() {
        val config = withCounter(TimerConfig(index = 2, switch = shDown, startTime = 4, countUp = false))
        frame(config)
        assertEquals(4, timers.counterStates[2].count)
        assertFalse(timers.counterStates[2].showsElapsed)
        click(config)
        click(config)
        assertEquals(2, timers.counterStates[2].count)
        timers.reset(config, 5, events::add)
        assertEquals(4, timers.withReadBack(config.counters[2], isCounter = true).count)
    }

    @Test
    fun switchThatIsAlreadyOnAtStartIsNotAnEdge() {
        val config = withCounter(TimerConfig(index = 0, switch = shDown, startTime = 5))
        logic[Sources.SH] = 100
        run(config, 400)
        assertEquals(0, timers.counterStates[0].count)
    }

    @Test
    fun comparatorOutputMode() {
        val config = withCounter(
            TimerConfig(index = 0, switch = shDown, startTime = 5, outputMode = 1, threshold = 2, outputMin = -50, outputMax = 75),
        )
        frame(config)
        assertEquals(-50, logic[Sources.CNT1])
        click(config)
        assertEquals(-50, logic[Sources.CNT1])
        click(config)
        assertEquals(75, logic[Sources.CNT1])
    }

    @Test
    fun counterSourceFeedsTheMixerInTheSameFrame() {
        val base = withCounter(TimerConfig(index = 0, switch = shDown, startTime = 2, outputMin = 0, outputMax = 100))
        val config = base.copy(
            mixes = base.mixes.toMutableList().also {
                it[6] = listOf(org.openax12.core.model.MixLine(channel = 6, source = SourceId.counter(1)))
            },
        )
        frame(config)
        logic[Sources.SH] = 100
        frame(config)
        assertEquals(50.0, SimMixer.compute(config, logic)[6], 1e-9)
    }

    @Test
    fun invalidDeviceIndexIsRejected() {
        val config = SimConfig()
        assertFalse(timers.reset(config, -1, events::add))
        assertFalse(timers.reset(config, 6, events::add))
        assertTrue(events.isEmpty())
    }
}
