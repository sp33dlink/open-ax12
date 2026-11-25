package org.openax12.core.radio.fake

import kotlin.math.roundToInt
import org.openax12.core.model.COUNTER_COUNT
import org.openax12.core.model.LiveSnapshot
import org.openax12.core.model.Sources
import org.openax12.core.model.SwitchCondition
import org.openax12.core.model.TIMER_COUNT
import org.openax12.core.model.TimerAlertMode
import org.openax12.core.model.TimerConfig
import org.openax12.core.model.TimerNotify
import org.openax12.core.model.TimerReport
import org.openax12.core.model.TimerState
import org.openax12.core.radio.RadioEvent

/**
 * The simulated firmware timers and counters. Not thread-safe (confined to the simulator worker).
 *
 * The SDK documents the configuration fields but not the firmware's behaviour, so this is the
 * simulator's reading of them (assumption 26):
 *
 * **Timers** (seconds)
 * - A timer runs while its switch condition holds; a disabled condition never runs (there is no
 *   manual start / stop).
 * - `startTime == 0`: counts up without end and reports the elapsed time.
 * - `startTime > 0`, counting down: reports the remainder; at 0 it raises its alert and stops —
 *   or starts over when `loopMode != 0`.
 * - `startTime > 0`, counting up: reports the elapsed time, raises its alert when the period is
 *   reached and keeps counting (starts over when `loopMode != 0`).
 * - With an alert mode other than silent and `alertIntervalSec > 0` an alert is raised every
 *   time the reported value passes a multiple of the interval.
 * - The reset condition resets the timer on its rising edge, like [reset].
 *
 * **Counters**
 * - A counter steps once on every rising edge of its switch condition: up from 0 to its maximum
 *   (`startTime`) or down from the maximum to 0; at the end it stays there, or wraps when
 *   `loopMode != 0`.
 * - Counter *n* drives the input source `CNT<n+1>`: `outputMode == 0` maps the count linearly from
 *   `outputMin` (count 0) to `outputMax` (maximum); any other mode is a comparator that outputs
 *   `outputMax` once the count reached `threshold` and `outputMin` below. `CNT4`–`CNT6` stay 0.
 *
 * **Messages**
 * - A report ([RadioEvent.TimerReported] / [RadioEvent.CounterReported], in-group index) whenever
 *   the reported value changes — once per second for a running timer — and after a reset or a
 *   configuration change.
 * - A notification ([RadioEvent.TimerNotified]) when a timer starts or stops (code 0), for alerts
 *   (code = the alert mode) and for a reset (code [TimerAlertMode.NOTIFY_RESET]). Its index is
 *   the **device** timer number: 0..2 timers, 3..5 counters.
 */
internal class SimTimers {

    private class Slot {
        var elapsedMs = 0L
        var value = 0
        var running = false
        var alerted = false
        var held = false
        var primed = false
        var switchWas = false
        var resetWas = false
        var notifyCode = 0
        var reported = Int.MIN_VALUE
        var reportDue = true
    }

    private val timers = Array(TIMER_COUNT) { Slot() }
    private val counters = Array(COUNTER_COUNT) { Slot() }

    /** Timer states of the latest step; the same list instance while nothing changed. */
    var timerStates: List<TimerState> = LiveSnapshot.DEFAULT_TIMERS
        private set

    /** Counter states of the latest step; the same list instance while nothing changed. */
    var counterStates: List<TimerState> = LiveSnapshot.DEFAULT_COUNTERS
        private set

    /**
     * Advances all timers and counters by [dtMs] for the given inputs and reports through [emit].
     *
     * @param logic logic value per source; the entries `CNT1`–`CNT3` are **written** with the
     *   counters' output values so that the mixer sees them in the same frame
     */
    fun step(config: SimConfig, logic: IntArray, dtMs: Long, emit: (RadioEvent) -> Unit) {
        for (i in 0 until TIMER_COUNT) {
            val cfg = config.timers.getOrNull(i) ?: continue
            stepTimer(i, timers[i], cfg, logic, dtMs, emit)
        }
        for (i in 0 until COUNTER_COUNT) {
            val cfg = config.counters.getOrNull(i) ?: continue
            stepCounter(i, counters[i], cfg, logic, emit)
            val source = Sources.CNT1 + i
            if (source < logic.size) logic[source] = counterOutput(cfg, counters[i].value)
        }
        publishStates(config)
    }

    /**
     * Resets the timer or counter with **device** number [deviceIndex] (0..2 timers, 3..5
     * counters) and announces it with a reset notification. Returns false for an invalid number.
     */
    fun reset(config: SimConfig, deviceIndex: Int, emit: (RadioEvent) -> Unit): Boolean {
        val isCounter = deviceIndex >= TIMER_COUNT
        val index = if (isCounter) deviceIndex - TIMER_COUNT else deviceIndex
        val slot = (if (isCounter) counters else timers).getOrNull(index) ?: return false
        val cfg = (if (isCounter) config.counters else config.timers).getOrNull(index) ?: return false
        clear(slot, cfg, isCounter)
        slot.notifyCode = TimerAlertMode.NOTIFY_RESET
        emit(
            RadioEvent.TimerNotified(
                TimerNotify(
                    index = deviceIndex,
                    running = slot.running,
                    code = TimerAlertMode.NOTIFY_RESET,
                    count = if (isCounter) slot.value else timerDisplay(slot, cfg),
                ),
            ),
        )
        return true
    }

    /** Starts every timer and counter of [config] over without notifications (a model was loaded). */
    fun restart(config: SimConfig) {
        for (i in timers.indices) restartSlot(timers[i], config.timers.getOrNull(i), isCounter = false)
        for (i in counters.indices) restartSlot(counters[i], config.counters.getOrNull(i), isCounter = true)
    }

    /**
     * Tells the timers that the configuration of one of them was rewritten. A changed period or
     * direction starts it over; anything else only forces a new report.
     */
    fun configChanged(index: Int, isCounter: Boolean, old: TimerConfig, new: TimerConfig) {
        val slot = (if (isCounter) counters else timers).getOrNull(index) ?: return
        if (old.startTime != new.startTime || old.countUp != new.countUp) {
            clear(slot, new, isCounter)
        } else {
            slot.reportDue = true
        }
    }

    /** The read-back fields of [config] filled in (`trDirection`, `trStartTime`, `timeCount`, `notifyCode`). */
    fun withReadBack(config: TimerConfig, isCounter: Boolean): TimerConfig {
        val slot = (if (isCounter) counters else timers).getOrNull(config.index) ?: return config
        return config.copy(
            runningDirection = if (isCounter) (if (config.countUp) 1 else 0) else if (isCountdown(config)) 0 else 1,
            runningStartTime = config.startTime,
            count = if (isCounter) slot.value else timerDisplay(slot, config),
            notifyCode = slot.notifyCode,
        )
    }

    private fun stepTimer(index: Int, slot: Slot, cfg: TimerConfig, logic: IntArray, dtMs: Long, emit: (RadioEvent) -> Unit) {
        val resetNow = holds(cfg.resetSwitch, logic)
        if (slot.primed && resetNow && !slot.resetWas) reset(index, slot, cfg, emit)
        slot.resetWas = resetNow
        slot.primed = true

        val period = cfg.startTime.coerceAtLeast(0)
        val periodMs = period * 1000L
        val before = timerDisplay(slot, cfg)
        var run = holds(cfg.switch, logic) && !slot.held
        var alert = false
        if (run && dtMs > 0) {
            slot.elapsedMs += dtMs
            if (period > 0 && slot.elapsedMs >= periodMs) {
                if (cfg.loopMode != 0) {
                    slot.elapsedMs %= periodMs
                    alert = true
                } else if (isCountdown(cfg)) {
                    slot.elapsedMs = periodMs
                    slot.held = true
                    run = false
                    alert = true
                } else if (!slot.alerted) {
                    slot.alerted = true
                    alert = true
                }
            }
        }
        val now = timerDisplay(slot, cfg)
        val runningChanged = run != slot.running
        slot.running = run
        val interval = cfg.alertIntervalSec
        if (!alert && now != before && now > 0 && interval > 0 && cfg.alertMode != TimerAlertMode.SILENT && now % interval == 0) {
            alert = true
        }
        if (alert) {
            slot.notifyCode = cfg.alertMode.sdk
            emit(RadioEvent.TimerNotified(TimerNotify(index, run, cfg.alertMode.sdk, now)))
        } else if (runningChanged) {
            slot.notifyCode = 0
            emit(RadioEvent.TimerNotified(TimerNotify(index, run, 0, now)))
        }
        if (now != slot.reported || slot.reportDue) {
            slot.reported = now
            slot.reportDue = false
            emit(RadioEvent.TimerReported(TimerReport(index, showsElapsed = !isCountdown(cfg), startTime = period, count = now)))
        }
    }

    private fun reset(index: Int, slot: Slot, cfg: TimerConfig, emit: (RadioEvent) -> Unit) {
        clear(slot, cfg, isCounter = false)
        slot.notifyCode = TimerAlertMode.NOTIFY_RESET
        emit(RadioEvent.TimerNotified(TimerNotify(index, slot.running, TimerAlertMode.NOTIFY_RESET, timerDisplay(slot, cfg))))
    }

    private fun stepCounter(index: Int, slot: Slot, cfg: TimerConfig, logic: IntArray, emit: (RadioEvent) -> Unit) {
        val max = counterMax(cfg)
        val on = holds(cfg.switch, logic)
        val resetNow = holds(cfg.resetSwitch, logic)
        if (!slot.primed) {
            // The first frame only records the switch positions: a switch that is already on
            // when the radio starts is not an edge.
            slot.primed = true
            slot.value = if (cfg.countUp) 0 else max
        } else {
            if (resetNow && !slot.resetWas) {
                clear(slot, cfg, isCounter = true)
                slot.notifyCode = TimerAlertMode.NOTIFY_RESET
                emit(RadioEvent.TimerNotified(TimerNotify(index + TIMER_COUNT, on, TimerAlertMode.NOTIFY_RESET, slot.value)))
            }
            if (on && !slot.switchWas) {
                slot.value = if (cfg.countUp) {
                    if (slot.value >= max) (if (cfg.loopMode != 0) 0 else max) else slot.value + 1
                } else {
                    if (slot.value <= 0) (if (cfg.loopMode != 0) max else 0) else slot.value - 1
                }
            }
        }
        slot.switchWas = on
        slot.resetWas = resetNow
        slot.running = on
        slot.value = slot.value.coerceIn(0, max)
        if (slot.value != slot.reported || slot.reportDue) {
            slot.reported = slot.value
            slot.reportDue = false
            emit(RadioEvent.CounterReported(TimerReport(index, showsElapsed = cfg.countUp, startTime = max, count = slot.value)))
        }
    }

    private fun publishStates(config: SimConfig) {
        val newTimers = List(TIMER_COUNT) { i ->
            val cfg = config.timers.getOrNull(i)
            val slot = timers[i]
            TimerState(
                index = i,
                isCounter = false,
                count = if (cfg == null) 0 else timerDisplay(slot, cfg),
                showsElapsed = cfg == null || !isCountdown(cfg),
                startTime = cfg?.startTime?.coerceAtLeast(0) ?: 0,
                running = slot.running,
            )
        }
        if (newTimers != timerStates) timerStates = newTimers
        val newCounters = List(COUNTER_COUNT) { i ->
            val cfg = config.counters.getOrNull(i)
            val slot = counters[i]
            TimerState(
                index = i,
                isCounter = true,
                count = slot.value,
                showsElapsed = cfg?.countUp ?: true,
                startTime = if (cfg == null) 0 else counterMax(cfg),
                running = slot.running,
            )
        }
        if (newCounters != counterStates) counterStates = newCounters
    }

    private fun restartSlot(slot: Slot, cfg: TimerConfig?, isCounter: Boolean) {
        if (cfg != null) clear(slot, cfg, isCounter)
        slot.running = false
        slot.notifyCode = 0
        slot.primed = false
        slot.switchWas = false
        slot.resetWas = false
    }

    private fun clear(slot: Slot, cfg: TimerConfig, isCounter: Boolean) {
        slot.elapsedMs = 0
        slot.alerted = false
        slot.held = false
        slot.value = if (isCounter && !cfg.countUp) counterMax(cfg) else 0
        slot.reportDue = true
    }

    private fun holds(condition: SwitchCondition, logic: IntArray): Boolean =
        condition.test(logic.getOrElse(condition.source.index) { 0 }) == true

    private fun isCountdown(cfg: TimerConfig): Boolean = cfg.startTime > 0 && !cfg.countUp

    private fun timerDisplay(slot: Slot, cfg: TimerConfig): Int {
        val seconds = (slot.elapsedMs / 1000L).toInt()
        return if (isCountdown(cfg)) (cfg.startTime - seconds).coerceAtLeast(0) else seconds
    }

    private fun counterMax(cfg: TimerConfig): Int = cfg.startTime.coerceAtLeast(1)

    private fun counterOutput(cfg: TimerConfig, value: Int): Int {
        val low = cfg.outputMin.coerceIn(-100, 100)
        val high = cfg.outputMax.coerceIn(-100, 100)
        return if (cfg.outputMode == 0) {
            (low + (high - low) * value.toDouble() / counterMax(cfg)).roundToInt()
        } else {
            if (value >= cfg.threshold) high else low
        }
    }
}
