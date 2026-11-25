package org.openax12.core.engine

import org.openax12.core.model.BeepPattern
import org.openax12.core.model.COUNTER_COUNT
import org.openax12.core.model.CompareOp
import org.openax12.core.model.HapticPattern
import org.openax12.core.model.LOGICAL_SWITCH_COUNT
import org.openax12.core.model.LinkEvent
import org.openax12.core.model.LogicalFunction
import org.openax12.core.model.LogicalSwitch
import org.openax12.core.model.LogicalSwitchStates
import org.openax12.core.model.Repeat
import org.openax12.core.model.SPECIAL_FUNCTION_COUNT
import org.openax12.core.model.SensorAlarm
import org.openax12.core.model.SensorId
import org.openax12.core.model.Sensors
import org.openax12.core.model.SourceId
import org.openax12.core.model.SpecialAction
import org.openax12.core.model.SpecialFunction
import org.openax12.core.model.SpeechPriority
import org.openax12.core.model.SwitchCondition
import org.openax12.core.model.TIMER_COUNT
import org.openax12.core.model.TimerEvent
import org.openax12.core.model.TimerState
import org.openax12.core.model.Trigger
import org.openax12.core.model.VoiceAlert
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Evaluates [Trigger]s. Stateless triggers (switch position, input condition, logical switch,
 * flight mode level, always, never) only read the tick; stateful ones (telemetry hysteresis,
 * periodic, timer and link pulses, flight-mode change) keep state per caller-supplied key.
 *
 * Call [evaluate] at most once per tick and key. Not thread-safe; use from the engine tick only.
 */
class TriggerEvaluator {

    private val states = HashMap<String, State>()

    /**
     * True if [trigger] is active at [tick]. Level triggers return their condition; pulse
     * triggers return true for exactly one tick per event.
     *
     * Pulse triggers never fire on the first evaluation of a key: that call only records the
     * baseline (timer value, flight mode, start of a period), so nothing is announced just
     * because a model was loaded.
     *
     * @param key identity of the trigger's owner (e.g. `"sf:3"`, `"va:<id>"`, `"ls:2:and"`); state
     *   of stateful triggers is kept per key. Changing the trigger under the same key resets its state.
     */
    fun evaluate(key: String, trigger: Trigger, tick: EngineTick, resolver: ValueResolver): Boolean = when (trigger) {
        is Trigger.SwitchAt -> stateless(key) {
            trigger.source.isValid && tick.live.switchPosition(trigger.source) == trigger.position
        }
        is Trigger.Input -> stateless(key) { inputCondition(trigger.condition, tick) }
        is Trigger.Logical -> stateless(key) {
            trigger.index in 0 until LOGICAL_SWITCH_COUNT && tick.logicalSwitches[trigger.index] != trigger.inverted
        }
        is Trigger.Telemetry -> telemetry(stateOf(key, trigger), trigger, tick, resolver)
        is Trigger.Link -> when (trigger.event) {
            LinkEvent.TELEMETRY_LOST -> stateless(key) { tick.events.any { it is EngineEvent.TelemetryLost } }
            LinkEvent.TELEMETRY_RECOVERED -> stateless(key) { tick.events.any { it is EngineEvent.TelemetryRecovered } }
            LinkEvent.LINK_UP -> stateless(key) { tick.linkUp }
            LinkEvent.LINK_DOWN -> stateless(key) { !tick.linkUp }
        }
        is Trigger.Timer -> timer(key, trigger, tick)
        is Trigger.FlightModeIs ->
            if (trigger.mode >= 0) stateless(key) { tick.flightMode == trigger.mode } else flightModeChange(stateOf(key, trigger), tick)
        Trigger.Always -> stateless(key) { true }
        is Trigger.Periodic -> periodic(stateOf(key, trigger), trigger, tick)
        Trigger.Never -> stateless(key) { false }
    }

    /** Forgets the state of every key not in [keys] (after the configuration changed). */
    fun retainOnly(keys: Set<String>) {
        states.keys.retainAll(keys)
    }

    /** Forgets the state of [key] only (its owner was removed, disabled or changed). */
    fun forget(key: String) {
        states.remove(key)
    }

    /** Forgets all state (model switch, reset flight). */
    fun reset() {
        states.clear()
    }

    // ---- implementation ------------------------------------------------------------------

    /** State of one stateful trigger; a different trigger under the same key starts from scratch. */
    private class State(val trigger: Trigger) {
        /** False until the first evaluation recorded the baseline. */
        var initialized = false

        /** Telemetry: the level including hysteresis. */
        var active = false

        /** Periodic: time of the next pulse. */
        var nextFireMs = 0L

        /** Flight-mode change: mode at the previous evaluation. */
        var lastFlightMode = 0

        /** Timer events: values at the previous evaluation. */
        var lastRunning = false
        var lastElapsed = 0
        var lastRemaining = 0
    }

    private fun stateOf(key: String, trigger: Trigger): State {
        val existing = states[key]
        if (existing != null && existing.trigger == trigger) return existing
        return State(trigger).also { states[key] = it }
    }

    private inline fun stateless(key: String, condition: () -> Boolean): Boolean {
        if (states.isNotEmpty()) states.remove(key)
        return condition()
    }

    private fun inputCondition(condition: SwitchCondition, tick: EngineTick): Boolean =
        condition.source.isValid && condition.evaluate(tick.live) == true

    private fun telemetry(state: State, trigger: Trigger.Telemetry, tick: EngineTick, resolver: ValueResolver): Boolean {
        val value = EngineValues.number(trigger.sensor, tick.logicalSwitches, resolver)
        if (value == null) {
            state.active = false
            return false
        }
        state.active = EngineValues.levelWithHysteresis(state.active, value, trigger.op, trigger.value, trigger.hysteresis)
        return state.active
    }

    private fun flightModeChange(state: State, tick: EngineTick): Boolean {
        if (!state.initialized) {
            state.initialized = true
            state.lastFlightMode = tick.flightMode
            return false
        }
        val changed = tick.flightMode != state.lastFlightMode
        state.lastFlightMode = tick.flightMode
        return changed
    }

    private fun periodic(state: State, trigger: Trigger.Periodic, tick: EngineTick): Boolean {
        if (trigger.intervalSec <= 0) return false
        val intervalMs = trigger.intervalSec * 1000L
        if (!state.initialized) {
            state.initialized = true
            state.nextFireMs = tick.nowMs + intervalMs
            return false
        }
        if (tick.nowMs < state.nextFireMs) return false
        // Keep the cadence; after a long gap (app paused) restart it instead of firing a burst.
        state.nextFireMs += intervalMs
        if (state.nextFireMs <= tick.nowMs) state.nextFireMs = tick.nowMs + intervalMs
        return true
    }

    private fun timer(key: String, trigger: Trigger.Timer, tick: EngineTick): Boolean {
        val timer = if (trigger.timer in 0 until TIMER_COUNT) tick.live.timer(trigger.timer) else null
        if (timer == null) return stateless(key) { false }
        if (trigger.event == TimerEvent.RUNNING) return stateless(key) { timer.running }

        val state = stateOf(key, trigger)
        val elapsed = elapsedOf(timer)
        val remaining = remainingOf(timer)
        val pulse = if (!state.initialized) {
            false
        } else {
            when (trigger.event) {
                TimerEvent.RUNNING -> timer.running
                TimerEvent.STARTED -> timer.running && !state.lastRunning
                TimerEvent.STOPPED -> !timer.running && state.lastRunning
                TimerEvent.EXPIRED -> timer.startTime > 0 && state.lastRemaining > 0 && remaining <= 0
                TimerEvent.RESET -> elapsed < state.lastElapsed
                TimerEvent.REMAINING -> {
                    val seconds = trigger.seconds.coerceAtLeast(0)
                    timer.startTime > 0 && state.lastRemaining > seconds && remaining <= seconds
                }
                TimerEvent.EVERY ->
                    trigger.seconds > 0 && elapsed > state.lastElapsed &&
                        Math.floorDiv(elapsed, trigger.seconds) > Math.floorDiv(state.lastElapsed, trigger.seconds)
            }
        }
        state.initialized = true
        state.lastRunning = timer.running
        state.lastElapsed = elapsed
        state.lastRemaining = remaining
        return pulse
    }

    /** Timer time that has passed, whichever way the firmware reports the count. */
    private fun elapsedOf(timer: TimerState): Int = if (timer.showsElapsed) timer.count else timer.startTime - timer.count

    /** Time left of a countdown (meaningless for a count-up timer, whose start time is 0). */
    private fun remainingOf(timer: TimerState): Int = if (timer.showsElapsed) timer.startTime - timer.count else timer.count
}

/** Value helpers shared by the evaluators. */
internal object EngineValues {

    /** Tolerance of an "equal" comparison of two sensor values. */
    private const val EQUAL_EPSILON = 1e-6

    /**
     * Native numeric value of [sensor]; logical switches are read from [switches] (the states the
     * caller is working with) instead of the resolver's tick. Null if unknown, textual or lost.
     */
    fun number(sensor: SensorId, switches: LogicalSwitchStates, resolver: ValueResolver): Double? {
        sensor.logicalSwitchIndex?.let { return if (switches[it]) 1.0 else 0.0 }
        return try {
            resolver.number(sensor)?.takeUnless { it.isNaN() }
        } catch (_: Exception) {
            null
        }
    }

    /** `value <op> threshold`; [CompareOp.DISABLED] is never true. */
    fun compare(value: Double, op: CompareOp, threshold: Double): Boolean = when (op) {
        CompareOp.DISABLED -> false
        CompareOp.GREATER -> value > threshold
        CompareOp.LESS -> value < threshold
        CompareOp.EQUAL -> abs(value - threshold) <= EQUAL_EPSILON
    }

    /**
     * True once [value] moved back past [threshold] by [hysteresis] (the release condition of an
     * active level). Without hysteresis this is simply "the comparison no longer holds".
     */
    fun released(value: Double, op: CompareOp, threshold: Double, hysteresis: Double): Boolean {
        val h = if (hysteresis.isNaN()) 0.0 else abs(hysteresis)
        return when (op) {
            CompareOp.DISABLED -> true
            CompareOp.GREATER -> if (h == 0.0) value <= threshold else value <= threshold - h
            CompareOp.LESS -> if (h == 0.0) value >= threshold else value >= threshold + h
            CompareOp.EQUAL -> abs(value - threshold) > maxOf(h, EQUAL_EPSILON)
        }
    }

    /** New state of a level with hysteresis that was [active] before. */
    fun levelWithHysteresis(active: Boolean, value: Double, op: CompareOp, threshold: Double, hysteresis: Double): Boolean =
        if (active) !released(value, op, threshold, hysteresis) else compare(value, op, threshold)

    /** Sensors whose value does not depend on the receiver link. */
    fun isLinkIndependent(sensor: SensorId): Boolean = when (sensor.namespace) {
        SensorId.NS_INPUT, SensorId.NS_CHANNEL, SensorId.NS_TIMER, SensorId.NS_COUNTER, SensorId.NS_LOGICAL_SWITCH -> true
        else -> sensor == Sensors.TX_BATTERY || sensor == Sensors.FLIGHT_MODE
    }

    /** Input value −100..+100 mapped to 0..100 percent (volume, brightness). */
    fun inputToPercent(logic: Int): Int = ((logic + 100) / 2).coerceIn(0, 100)
}

/**
 * Stateful, tick-driven evaluation of the logical switches L1..L32 with EdgeTX semantics:
 * functions `a>x a<x |a|>x |a|<x a≈x a>b a<b AND OR XOR EDGE STICKY TIMER DELTA |DELTA|`, the
 * optional AND-condition, duration and delay.
 *
 * Switches are evaluated in index order; a switch that references another logical switch sees
 * the value of the previous tick for higher or equal indices and the fresh value for lower ones.
 * Not thread-safe.
 *
 * Details (all times come from [EngineTick.nowMs], never from a clock):
 * - Value functions compare native sensor values; an unknown or lost operand makes the switch
 *   false. `a≈x` tolerates ±2 for inputs and channels (percent) and otherwise compares at the
 *   sensor's display precision.
 * - `EDGE`: a one-tick pulse when `s1` is released after having been on for at least `t1Sec`
 *   and (if `t2Sec` > 0) at most `t2Sec`. A negative `t2Sec` is the EdgeTX "instant" variant:
 *   the pulse comes as soon as `s1` has been on for `t1Sec`, without waiting for the release
 *   (`t1Sec` = 0: rising edge). `s1` being on already at the first evaluation does not produce
 *   an instant pulse. The delay is ignored for `EDGE` (as in EdgeTX).
 * - `STICKY`: set by a rising edge of `s1` while off, reset by a rising edge of `s2` while on.
 * - `TIMER`: on for `t1Sec`, off for `t2Sec` (each at least 0.1 s), starting with the on phase.
 * - `DELTA`: a one-tick pulse when `a` changed by at least `x` (in the direction of the sign of
 *   `x`) since the reference value; the reference follows moves in the other direction and is
 *   renewed with every pulse. `|DELTA|` reacts to both directions.
 * - AND-condition: while it is false the switch is false and `TIMER` / `DELTA` restart; `STICKY`
 *   and `EDGE` keep tracking their inputs.
 * - Delay: the result must be true for `delaySec` before the switch turns on. Duration: once on,
 *   the switch stays on for exactly `durationSec` (even if the condition ends earlier) and then
 *   turns off until the condition was false once.
 */
class LogicalSwitchEvaluator(switches: List<LogicalSwitch> = emptyList()) {

    private val triggers = TriggerEvaluator()
    private val slots = arrayOfNulls<Slot>(LOGICAL_SWITCH_COUNT)
    private var current = LogicalSwitchStates.NONE

    init {
        update(switches)
    }

    /** States after the last [evaluate]. */
    val states: LogicalSwitchStates get() = current

    /**
     * Replaces the configuration. Switches whose definition did not change keep their state
     * (sticky value, timers, delays); changed and removed ones are reset.
     */
    fun update(switches: List<LogicalSwitch>) {
        for (index in 0 until LOGICAL_SWITCH_COUNT) {
            val definition = switches.lastOrNull { it.index == index && it.isUsed }?.copy(name = "")
            val slot = slots[index]
            if (slot?.definition == definition) continue
            slot?.let(::forgetTriggers)
            slots[index] = definition?.let(::Slot)
            current = current.with(index, false)
        }
    }

    /** Advances all switches to [tick] and returns the new states. */
    fun evaluate(tick: EngineTick, resolver: ValueResolver): LogicalSwitchStates {
        var states = current
        var view = tick.withLogicalSwitches(states)
        for (index in 0 until LOGICAL_SWITCH_COUNT) {
            val slot = slots[index] ?: continue
            if (view.logicalSwitches != states) view = tick.withLogicalSwitches(states)
            val raw = rawResult(slot, view, resolver, states)
            states = states.with(index, durationAndDelay(slot, raw, tick.nowMs))
            slot.evaluated = true
        }
        current = states
        return states
    }

    /** Clears all state: every switch off, timers and delays restarted. */
    fun reset() {
        for (index in slots.indices) slots[index] = slots[index]?.let { Slot(it.definition) }
        triggers.reset()
        current = LogicalSwitchStates.NONE
    }

    // ---- implementation ------------------------------------------------------------------

    private enum class Phase { START, DELAY, ENABLE }

    /** One configured switch with its run-time state. */
    private class Slot(val definition: LogicalSwitch) {
        val keyS1 = "ls:${definition.index}:s1"
        val keyS2 = "ls:${definition.index}:s2"
        val keyAnd = "ls:${definition.index}:and"

        /** False until the switch was evaluated once. */
        var evaluated = false

        /** TIMER: the cycle is running; DELTA: [reference] is valid. */
        var initialized = false
        var reference = 0.0
        var cycleStartMs = 0L

        var stickyOn = false
        var stickyLast = false

        /** EDGE: when `s1` turned on; null while it is off. */
        var heldSinceMs: Long? = null
        var instantDone = false

        var phase = Phase.START
        var phaseSinceMs = 0L
    }

    private fun forgetTriggers(slot: Slot) {
        triggers.forget(slot.keyS1)
        triggers.forget(slot.keyS2)
        triggers.forget(slot.keyAnd)
    }

    private fun condition(key: String, trigger: Trigger?, tick: EngineTick, resolver: ValueResolver): Boolean =
        trigger != null && triggers.evaluate(key, trigger, tick, resolver)

    /** Result of the function and the AND-condition, before delay and duration. */
    private fun rawResult(slot: Slot, tick: EngineTick, resolver: ValueResolver, states: LogicalSwitchStates): Boolean {
        val def = slot.definition
        val and = def.andCondition
        val andOk = and == null || triggers.evaluate(slot.keyAnd, and, tick, resolver)
        return when (def.function) {
            LogicalFunction.NONE -> false
            // The AND-condition must not disturb sticky and edge tracking.
            LogicalFunction.STICKY -> {
                stickyStep(slot, tick, resolver)
                andOk && slot.stickyOn
            }
            LogicalFunction.EDGE -> edgeStep(slot, tick, resolver) && andOk
            else -> {
                if (!andOk) {
                    slot.initialized = false
                    false
                } else {
                    functionResult(slot, tick, resolver, states)
                }
            }
        }
    }

    private fun functionResult(slot: Slot, tick: EngineTick, resolver: ValueResolver, states: LogicalSwitchStates): Boolean {
        val def = slot.definition
        return when (def.function) {
            LogicalFunction.AND, LogicalFunction.OR, LogicalFunction.XOR -> {
                // Both sides are always evaluated so that stateful triggers keep ticking.
                val s1 = condition(slot.keyS1, def.s1, tick, resolver)
                val s2 = condition(slot.keyS2, def.s2, tick, resolver)
                when (def.function) {
                    LogicalFunction.AND -> s1 && s2
                    LogicalFunction.OR -> s1 || s2
                    else -> s1 != s2
                }
            }
            LogicalFunction.TIMER -> {
                if (!slot.initialized) {
                    slot.initialized = true
                    slot.cycleStartMs = tick.nowMs
                }
                val onMs = seconds(def.t1Sec).coerceAtLeast(MIN_TIMER_PHASE_MS)
                val offMs = seconds(def.t2Sec).coerceAtLeast(MIN_TIMER_PHASE_MS)
                Math.floorMod(tick.nowMs - slot.cycleStartMs, onMs + offMs) < onMs
            }
            LogicalFunction.A_GREATER_B, LogicalFunction.A_LESS_B -> {
                val a = value(def.a, states, resolver)
                val b = value(def.b, states, resolver)
                if (a == null || b == null) false else if (def.function == LogicalFunction.A_GREATER_B) a > b else a < b
            }
            LogicalFunction.A_GREATER_X, LogicalFunction.A_LESS_X, LogicalFunction.ABS_A_GREATER_X,
            LogicalFunction.ABS_A_LESS_X, LogicalFunction.A_APPROX_X, LogicalFunction.DELTA, LogicalFunction.ABS_DELTA,
            -> {
                val a = value(def.a, states, resolver) ?: return false
                when (def.function) {
                    LogicalFunction.A_GREATER_X -> a > def.x
                    LogicalFunction.A_LESS_X -> a < def.x
                    LogicalFunction.ABS_A_GREATER_X -> abs(a) > def.x
                    LogicalFunction.ABS_A_LESS_X -> abs(a) < def.x
                    LogicalFunction.A_APPROX_X -> approximatelyEqual(def.a, a, def.x, resolver)
                    else -> delta(slot, a)
                }
            }
            LogicalFunction.NONE, LogicalFunction.STICKY, LogicalFunction.EDGE -> false
        }
    }

    private fun value(sensor: SensorId?, states: LogicalSwitchStates, resolver: ValueResolver): Double? =
        sensor?.let { EngineValues.number(it, states, resolver) }

    private fun approximatelyEqual(sensor: SensorId?, a: Double, x: Double, resolver: ValueResolver): Boolean {
        if (sensor == null || sensor.isLive) return abs(a - x) <= SwitchCondition.EQUAL_TOLERANCE
        val precision = try {
            resolver.resolve(sensor)?.precision ?: 0
        } catch (_: Exception) {
            0
        }
        val scale = 10.0.pow(precision.coerceIn(0, TemplateRenderer.MAX_PRECISION))
        return (a * scale).roundToLong() == (x * scale).roundToLong()
    }

    /** EdgeTX `d≥x` / `|d|≥x`. */
    private fun delta(slot: Slot, a: Double): Boolean {
        val def = slot.definition
        if (!slot.initialized) {
            slot.initialized = true
            slot.reference = a
        }
        val diff = a - slot.reference
        val result: Boolean
        var follow = false
        if (def.function == LogicalFunction.ABS_DELTA) {
            result = abs(diff) >= abs(def.x)
        } else if (def.x >= 0) {
            result = diff >= def.x
            follow = diff < 0
        } else {
            result = diff <= def.x
            follow = diff > 0
        }
        if (result || follow) slot.reference = a
        return result
    }

    /** EdgeTX sticky: one shared "last input" bit, so a held input does not retrigger. */
    private fun stickyStep(slot: Slot, tick: EngineTick, resolver: ValueResolver) {
        val def = slot.definition
        val before = slot.stickyLast
        if (slot.stickyOn) {
            val reset = def.s2 ?: return
            val now = triggers.evaluate(slot.keyS2, reset, tick, resolver)
            if (now != before) {
                slot.stickyLast = now
                if (!before) slot.stickyOn = false
            }
        } else {
            val set = def.s1 ?: return
            val now = triggers.evaluate(slot.keyS1, set, tick, resolver)
            if (now != before) {
                slot.stickyLast = now
                if (!before) slot.stickyOn = true
            }
        }
    }

    /** True for the one evaluation in which the edge condition is met. */
    private fun edgeStep(slot: Slot, tick: EngineTick, resolver: ValueResolver): Boolean {
        val def = slot.definition
        val input = def.s1 ?: return false
        val on = triggers.evaluate(slot.keyS1, input, tick, resolver)
        val minMs = seconds(def.t1Sec)
        val instant = def.t2Sec < 0
        val since = slot.heldSinceMs
        if (on) {
            if (since == null) {
                slot.heldSinceMs = tick.nowMs
                // Already on when the switch is evaluated for the first time: not an edge.
                slot.instantDone = !slot.evaluated
            }
            val held = tick.nowMs - (slot.heldSinceMs ?: tick.nowMs)
            if (instant && !slot.instantDone && held >= minMs) {
                slot.instantDone = true
                return true
            }
            return false
        }
        slot.heldSinceMs = null
        if (since == null || instant) return false
        val held = tick.nowMs - since
        val maxMs = seconds(def.t2Sec)
        return held >= minMs && (maxMs <= 0 || held <= maxMs)
    }

    /** EdgeTX delay / duration state machine. */
    private fun durationAndDelay(slot: Slot, raw: Boolean, nowMs: Long): Boolean {
        val def = slot.definition
        val delayMs = if (def.function == LogicalFunction.EDGE) 0L else seconds(def.delaySec)
        val durationMs = seconds(def.durationSec)
        if (delayMs <= 0 && durationMs <= 0) return raw
        var result = raw
        if (raw) {
            if (slot.phase == Phase.START) {
                slot.phase = Phase.DELAY
                slot.phaseSinceMs = nowMs
            }
            if (slot.phase == Phase.DELAY) {
                if (nowMs - slot.phaseSinceMs < delayMs) {
                    result = false
                } else {
                    slot.phase = Phase.ENABLE
                    slot.phaseSinceMs = nowMs
                }
            }
            if (slot.phase == Phase.ENABLE) result = durationMs <= 0 || nowMs - slot.phaseSinceMs < durationMs
        } else if (slot.phase == Phase.ENABLE && durationMs > 0 && nowMs - slot.phaseSinceMs < durationMs) {
            result = true
        } else {
            slot.phase = Phase.START
        }
        return result
    }

    private fun seconds(value: Double): Long = if (value.isNaN() || value <= 0) 0L else (value * 1000).roundToLong()

    private companion object {
        /** Shortest on / off phase of a TIMER switch (EdgeTX: 0.1 s). */
        const val MIN_TIMER_PHASE_MS = 100L
    }
}

/**
 * What the runtime must do as the result of one tick. Produced by [SpecialFunctionEvaluator];
 * executed by `RuntimeEngine` against the audio engine, the radio and the repositories.
 *
 * [origin] names the special function (`"SF3"`) or voice alert (`"va:<id>"`) that caused it.
 */
sealed interface ActionCommand {
    /** The special function (`"SF3"`) or voice alert (`"va:<id>"`) that caused the command. */
    val origin: String

    /** Speak [text] (already rendered). */
    data class Speak(val text: String, val priority: SpeechPriority, override val origin: String) : ActionCommand

    /** Speak the current value of [sensor] with its spoken unit (and its name when [withName]). */
    data class SpeakValue(
        val sensor: SensorId,
        val withUnit: Boolean,
        val withName: Boolean,
        val priority: SpeechPriority,
        override val origin: String,
    ) : ActionCommand

    /** Play a user sound file once. */
    data class PlaySound(val file: String, override val origin: String) : ActionCommand

    /** Start ([file] non-null) or stop ([file] null) background music. */
    data class Music(val file: String?, val loop: Boolean, override val origin: String) : ActionCommand

    /** Turn the vario tone on or off. */
    data class Vario(val enabled: Boolean, override val origin: String) : ActionCommand

    /** Vibrate. */
    data class Haptic(val pattern: HapticPattern, override val origin: String) : ActionCommand

    /** Play a beep. */
    data class Beep(val pattern: BeepPattern, override val origin: String) : ActionCommand

    /** Reset a firmware timer / counter; [deviceIndex] is the device timer number (0..2 timers, 3..5 counters). */
    data class ResetTimer(val deviceIndex: Int, override val origin: String) : ActionCommand

    /** Reset min / max of all sensors. */
    data class ResetTelemetry(override val origin: String) : ActionCommand

    /** Reset flight (timers, telemetry, home position, logical-switch state). */
    data class ResetFlight(override val origin: String) : ActionCommand

    /**
     * Set the media volume to [percent] (0..100). Emitted while a volume function is active and
     * the value changed by at least one percent; `null` = the function ended, restore the settings volume.
     */
    data class SetVolume(val percent: Int?, override val origin: String) : ActionCommand

    /** Start or stop CSV logging. */
    data class Logging(val enabled: Boolean, override val origin: String) : ActionCommand

    /** Take a screenshot. */
    data class Screenshot(override val origin: String) : ActionCommand

    /** Set the screen brightness to [percent] (0..100); `null` = back to the system brightness. */
    data class Brightness(val percent: Int?, override val origin: String) : ActionCommand
}

/**
 * Evaluates special functions (SF1..SF64) and voice alerts: given the trigger states at a tick it
 * emits the [ActionCommand]s to perform *this tick*.
 *
 * - Momentary actions fire on the rising edge of the trigger, and again every
 *   `repeat.intervalSec` while it stays active; with `repeat.skipAtStartup` a trigger that is
 *   already active on a [EngineTick.startup] tick does not fire until it was inactive once.
 * - Continuous actions emit a start command on the rising edge and the matching stop command on
 *   the falling edge (and when the function is disabled, removed or the evaluator is reset).
 *   Several functions driving the same continuous action are OR-ed: the stop command is emitted
 *   when the last one ends.
 * - Voice alerts behave like a momentary "play text" function.
 * - Text templates are rendered at firing time with the tick's resolver in
 *   [RenderMode.SPEECH], using the `speechFormatter` passed to the constructor.
 *
 * Not thread-safe.
 */
class SpecialFunctionEvaluator(
    private val triggers: TriggerEvaluator = TriggerEvaluator(),
    private val speechFormatter: ValueFormatter? = null,
) {

    /** Enabled functions with a valid index, in index order. */
    private var functions: List<SpecialFunction> = emptyList()

    /** Enabled voice alerts in list order. */
    private var alerts: List<VoiceAlert> = emptyList()

    private val momentary = HashMap<String, MomentaryState>()
    private var activeSf: Set<Int> = emptySet()
    private var activeVa: Set<String> = emptySet()
    private var firstEvaluation = true

    // Continuous actions: who started what is running now (null = not running).
    private var varioOrigin: String? = null
    private var loggingOrigin: String? = null
    private var musicOrigin: String? = null
    private var musicAction: SpecialAction.BackgroundMusic? = null
    private var volumeOrigin: String? = null
    private var volumePercent = -1
    private var brightnessOrigin: String? = null
    private var brightnessPercent = -1

    /** Indices of the special functions whose trigger was active at the last [evaluate]. */
    val activeFunctions: Set<Int> get() = activeSf

    /** Ids of the voice alerts whose trigger was active at the last [evaluate]. */
    val activeVoiceAlerts: Set<String> get() = activeVa

    /**
     * Replaces the configuration. Unchanged items keep their state. The returned commands stop
     * continuous actions of functions that were removed, disabled or changed.
     *
     * Disabled functions and alerts are not evaluated at all; functions with an index outside
     * SF1..SF64 are ignored. A changed or new item whose trigger is already active fires on the
     * next tick (it is a new activation).
     */
    fun update(functions: List<SpecialFunction>, voiceAlerts: List<VoiceAlert>): List<ActionCommand> {
        val newFunctions = functions
            .filter { it.enabled && it.index in 0 until SPECIAL_FUNCTION_COUNT }
            .distinctBy { it.index }
            .sortedBy { it.index }
        val newAlerts = voiceAlerts.filter { it.enabled && it.id.isNotEmpty() }.distinctBy { it.id }

        val keptFunctions = HashSet<Int>()
        for (old in this.functions) {
            val replacement = newFunctions.firstOrNull { it.index == old.index }
            if (replacement != null && sameDefinition(old, replacement)) keptFunctions += old.index else forget(sfKey(old.index))
        }
        val keptAlerts = HashSet<String>()
        for (old in alerts) {
            val replacement = newAlerts.firstOrNull { it.id == old.id }
            if (replacement != null && old.copy(name = "") == replacement.copy(name = "")) keptAlerts += old.id else forget(vaKey(old.id))
        }

        // Continuous actions keep running only for functions that survived unchanged.
        val stillDriving = this.functions.filter { it.index in keptFunctions && it.index in activeSf && it.action.isContinuous }
        val commands = ArrayList<ActionCommand>()
        reconcileContinuous(stillDriving, null, commands)

        this.functions = newFunctions
        alerts = newAlerts
        activeSf = activeSf.filterTo(HashSet()) { it in keptFunctions }
        activeVa = activeVa.filterTo(HashSet()) { it in keptAlerts }
        return commands
    }

    /** Evaluates everything at [tick] and returns the commands to perform now (possibly empty). */
    fun evaluate(tick: EngineTick, resolver: ValueResolver): List<ActionCommand> {
        // The very first evaluation counts as start-up even if the caller did not flag it.
        val startup = tick.startup || firstEvaluation
        firstEvaluation = false

        val commands = ArrayList<ActionCommand>()
        val nowActive = HashSet<Int>()
        val driving = ArrayList<SpecialFunction>()
        for (function in functions) {
            val key = sfKey(function.index)
            val active = triggers.evaluate(key, function.trigger, tick, resolver)
            if (active) nowActive += function.index
            if (function.action.isContinuous) {
                if (active) driving += function
            } else if (fires(key, active, function.repeat, startup, tick.nowMs)) {
                momentaryCommand(function, resolver)?.let(commands::add)
            }
        }
        reconcileContinuous(driving, tick, commands)

        val nowActiveAlerts = HashSet<String>()
        for (alert in alerts) {
            val key = vaKey(alert.id)
            val active = triggers.evaluate(key, alert.trigger, tick, resolver)
            if (active) nowActiveAlerts += alert.id
            if (fires(key, active, alert.repeat, startup, tick.nowMs)) {
                speak(alert.text, alert.priority, key, resolver)?.let(commands::add)
            }
        }

        activeSf = nowActive
        activeVa = nowActiveAlerts
        return commands
    }

    /** Clears all state and returns the commands that stop every running continuous action. */
    fun reset(): List<ActionCommand> {
        val commands = ArrayList<ActionCommand>()
        reconcileContinuous(emptyList(), null, commands)
        for (function in functions) triggers.forget(sfKey(function.index))
        for (alert in alerts) triggers.forget(vaKey(alert.id))
        momentary.clear()
        activeSf = emptySet()
        activeVa = emptySet()
        firstEvaluation = true
        return commands
    }

    // ---- implementation ------------------------------------------------------------------

    private class MomentaryState {
        var wasActive = false

        /** Active at start-up with `skipAtStartup`: silent until the trigger was inactive once. */
        var suppressed = false
        var lastFiredMs = 0L
    }

    private fun sfKey(index: Int): String = "sf:$index"

    private fun vaKey(id: String): String = "va:$id"

    private fun sfOrigin(index: Int): String = "SF${index + 1}"

    private fun sameDefinition(a: SpecialFunction, b: SpecialFunction): Boolean = a.copy(name = "") == b.copy(name = "")

    private fun forget(key: String) {
        momentary.remove(key)
        triggers.forget(key)
    }

    /** Once / repeat-every-N bookkeeping of one momentary item; true = fire now. */
    private fun fires(key: String, active: Boolean, repeat: Repeat, startup: Boolean, nowMs: Long): Boolean {
        if (!active) {
            momentary[key]?.let {
                it.wasActive = false
                it.suppressed = false
            }
            return false
        }
        val state = momentary.getOrPut(key) { MomentaryState() }
        if (!state.wasActive) {
            state.wasActive = true
            if (startup && repeat.skipAtStartup) {
                state.suppressed = true
                return false
            }
            state.lastFiredMs = nowMs
            return true
        }
        if (state.suppressed || repeat.isOnce) return false
        if (nowMs - state.lastFiredMs < repeat.intervalSec * 1000L) return false
        state.lastFiredMs = nowMs
        return true
    }

    private fun momentaryCommand(function: SpecialFunction, resolver: ValueResolver): ActionCommand? {
        val origin = sfOrigin(function.index)
        return when (val action = function.action) {
            is SpecialAction.PlayText -> speak(action.text, action.priority, origin, resolver)
            is SpecialAction.PlayValue ->
                ActionCommand.SpeakValue(action.sensor, action.withUnit, action.withName, action.priority, origin)
            is SpecialAction.PlaySound -> if (action.file.isBlank()) null else ActionCommand.PlaySound(action.file, origin)
            is SpecialAction.Haptic -> ActionCommand.Haptic(action.pattern, origin)
            is SpecialAction.Beep -> ActionCommand.Beep(action.pattern, origin)
            is SpecialAction.ResetTimer -> {
                val count = if (action.counter) COUNTER_COUNT else TIMER_COUNT
                if (action.timer !in 0 until count) {
                    null
                } else {
                    ActionCommand.ResetTimer(if (action.counter) action.timer + TIMER_COUNT else action.timer, origin)
                }
            }
            SpecialAction.ResetTelemetry -> ActionCommand.ResetTelemetry(origin)
            SpecialAction.ResetFlight -> ActionCommand.ResetFlight(origin)
            SpecialAction.Screenshot -> ActionCommand.Screenshot(origin)
            // Continuous actions are handled by reconcileContinuous.
            is SpecialAction.BackgroundMusic, SpecialAction.Vario, is SpecialAction.SetVolume,
            SpecialAction.Logging, is SpecialAction.Brightness,
            -> null
        }
    }

    /** Renders [template] for speech; nothing is spoken when the result is blank. */
    private fun speak(template: String, priority: SpeechPriority, origin: String, resolver: ValueResolver): ActionCommand? {
        val text = TemplateRenderer.render(template, resolver, RenderMode.SPEECH, speechFormatter)
            .replace(WHITESPACE, " ").trim()
        return if (text.isEmpty()) null else ActionCommand.Speak(text, priority, origin)
    }

    /**
     * Brings the running continuous actions in line with [driving] (the continuous functions
     * whose trigger is active, in index order; the lowest index governs music, volume and
     * brightness). Without a [tick] (configuration change, reset) input-following values are not
     * refreshed; only starts that need no input and stops are emitted.
     */
    private fun reconcileContinuous(driving: List<SpecialFunction>, tick: EngineTick?, out: MutableList<ActionCommand>) {
        val vario = driving.firstOrNull { it.action is SpecialAction.Vario }
        if (vario != null) {
            if (varioOrigin == null) {
                varioOrigin = sfOrigin(vario.index)
                out += ActionCommand.Vario(true, sfOrigin(vario.index))
            }
        } else {
            varioOrigin?.let { out += ActionCommand.Vario(false, it) }
            varioOrigin = null
        }

        val logging = driving.firstOrNull { it.action is SpecialAction.Logging }
        if (logging != null) {
            if (loggingOrigin == null) {
                loggingOrigin = sfOrigin(logging.index)
                out += ActionCommand.Logging(true, sfOrigin(logging.index))
            }
        } else {
            loggingOrigin?.let { out += ActionCommand.Logging(false, it) }
            loggingOrigin = null
        }

        val music = driving.firstOrNull { (it.action as? SpecialAction.BackgroundMusic)?.file?.isNotBlank() == true }
        val musicWanted = music?.action as? SpecialAction.BackgroundMusic
        if (music != null && musicWanted != null) {
            if (musicAction != musicWanted) {
                musicAction = musicWanted
                musicOrigin = sfOrigin(music.index)
                out += ActionCommand.Music(musicWanted.file, musicWanted.loop, sfOrigin(music.index))
            }
        } else {
            musicOrigin?.let { out += ActionCommand.Music(null, false, it) }
            musicOrigin = null
            musicAction = null
        }

        val volume = driving.firstOrNull { it.action is SpecialAction.SetVolume }
        val volumeAction = volume?.action as? SpecialAction.SetVolume
        if (volume != null && volumeAction != null) {
            if (tick != null) {
                val percent = EngineValues.inputToPercent(tick.live.logic(volumeAction.source))
                if (volumeOrigin == null || percent != volumePercent) {
                    volumePercent = percent
                    volumeOrigin = sfOrigin(volume.index)
                    out += ActionCommand.SetVolume(percent, sfOrigin(volume.index))
                }
            }
        } else {
            volumeOrigin?.let { out += ActionCommand.SetVolume(null, it) }
            volumeOrigin = null
            volumePercent = -1
        }

        val brightness = driving.firstOrNull { it.action is SpecialAction.Brightness }
        val brightnessAction = brightness?.action as? SpecialAction.Brightness
        if (brightness != null && brightnessAction != null) {
            val source: SourceId? = brightnessAction.source
            val percent = when {
                source == null -> brightnessAction.percent.coerceIn(0, 100)
                tick != null -> EngineValues.inputToPercent(tick.live.logic(source))
                else -> null
            }
            if (percent != null && (brightnessOrigin == null || percent != brightnessPercent)) {
                brightnessPercent = percent
                brightnessOrigin = sfOrigin(brightness.index)
                out += ActionCommand.Brightness(percent, sfOrigin(brightness.index))
            }
        } else {
            brightnessOrigin?.let { out += ActionCommand.Brightness(null, it) }
            brightnessOrigin = null
            brightnessPercent = -1
        }
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}

/** Why an [AlarmEvent] is emitted. */
enum class AlarmEventKind {
    /** The alarm condition became true (after the alarm's delay). */
    RAISED,

    /** The alarm is still active and its repeat interval elapsed. */
    REPEATED,

    /** The value moved back past the threshold by the hysteresis, or telemetry was lost. */
    CLEARED,
}

/**
 * An alarm state change to act on.
 *
 * @property value the sensor value that caused the event (native unit)
 * @property voiceText the alarm's voice template rendered at this tick; empty if none
 */
data class AlarmEvent(
    val alarm: SensorAlarm,
    val kind: AlarmEventKind,
    val value: Double,
    val voiceText: String = "",
)

/**
 * Evaluates the app-side sensor alarms with delay, hysteresis and repeat. Not thread-safe.
 */
class AlarmEvaluator(private val speechFormatter: ValueFormatter? = null) {

    /** Enabled alarms in list order. */
    private var alarms: List<SensorAlarm> = emptyList()
    private val states = HashMap<String, AlarmState>()

    /** Ids of the alarms that are currently raised. */
    val activeAlarms: Set<String> get() = states.filterValues { it.raised }.keys.toSet()

    /** Replaces the configuration; unchanged alarms keep their state. */
    fun update(alarms: List<SensorAlarm>) {
        val enabled = alarms.filter { it.enabled && it.id.isNotEmpty() }.distinctBy { it.id }
        val kept = HashSet<String>()
        for (old in this.alarms) {
            val replacement = enabled.firstOrNull { it.id == old.id }
            // Renaming or changing what an alarm does when it fires must not re-raise it.
            if (replacement != null && sameCondition(old, replacement)) kept += old.id
        }
        states.keys.retainAll(kept)
        this.alarms = enabled
    }

    /**
     * Evaluates all enabled alarms at [tick]; returns the events of this tick (possibly empty).
     *
     * Lost sensors: an alarm with `onlyWhenLinked` is cleared (event [AlarmEventKind.CLEARED]
     * with the last known value) when its sensor is lost or the link is down, and cannot be
     * raised until the value is back. Without `onlyWhenLinked` a raised alarm stays raised and
     * keeps repeating with the last known value. Sensors that do not depend on the link
     * (transmitter voltage, inputs, channels, timers, logical switches, flight mode) ignore the
     * link state.
     */
    fun evaluate(tick: EngineTick, resolver: ValueResolver): List<AlarmEvent> {
        if (alarms.isEmpty()) return emptyList()
        var events: ArrayList<AlarmEvent>? = null
        for (alarm in alarms) {
            val kind = step(alarm, tick, resolver) ?: continue
            val state = states.getValue(alarm.id)
            val voice = if (kind == AlarmEventKind.CLEARED || alarm.outputs.voiceText.isBlank()) {
                ""
            } else {
                TemplateRenderer.render(alarm.outputs.voiceText, resolver, RenderMode.SPEECH, speechFormatter).trim()
            }
            val list = events ?: ArrayList<AlarmEvent>().also { events = it }
            list += AlarmEvent(alarm, kind, state.lastValue, voice)
        }
        return events ?: emptyList()
    }

    /** Clears all alarm state without emitting events. */
    fun reset() {
        states.clear()
    }

    // ---- implementation ------------------------------------------------------------------

    private class AlarmState {
        var raised = false

        /** Since when the comparison holds without interruption; null while it does not. */
        var pendingSinceMs: Long? = null
        var lastAnnouncedMs = 0L
        var lastValue = Double.NaN
    }

    private fun sameCondition(a: SensorAlarm, b: SensorAlarm): Boolean =
        a.sensor == b.sensor && a.op == b.op && a.threshold == b.threshold && a.hysteresis == b.hysteresis &&
            a.delaySec == b.delaySec && a.onlyWhenLinked == b.onlyWhenLinked

    /** Advances one alarm; returns the event kind of this tick, or null. */
    private fun step(alarm: SensorAlarm, tick: EngineTick, resolver: ValueResolver): AlarmEventKind? {
        val state = states.getOrPut(alarm.id) { AlarmState() }
        val linkIndependent = EngineValues.isLinkIndependent(alarm.sensor)
        val suppressed = alarm.onlyWhenLinked && !linkIndependent && !tick.linkUp
        val value = if (suppressed) null else EngineValues.number(alarm.sensor, tick.logicalSwitches, resolver)

        if (value == null) {
            state.pendingSinceMs = null
            if (!state.raised) return null
            if (alarm.onlyWhenLinked || suppressed) {
                state.raised = false
                return AlarmEventKind.CLEARED
            }
            return repeatIfDue(alarm, state, tick.nowMs)
        }

        state.lastValue = value
        if (state.raised) {
            if (EngineValues.released(value, alarm.op, alarm.threshold, alarm.hysteresis)) {
                state.raised = false
                state.pendingSinceMs = null
                return AlarmEventKind.CLEARED
            }
            return repeatIfDue(alarm, state, tick.nowMs)
        }

        if (!EngineValues.compare(value, alarm.op, alarm.threshold)) {
            state.pendingSinceMs = null
            return null
        }
        val since = state.pendingSinceMs ?: tick.nowMs.also { state.pendingSinceMs = it }
        val delayMs = if (alarm.delaySec.isNaN() || alarm.delaySec <= 0) 0L else (alarm.delaySec * 1000).roundToLong()
        if (tick.nowMs - since < delayMs) return null
        state.raised = true
        state.lastAnnouncedMs = tick.nowMs
        return AlarmEventKind.RAISED
    }

    private fun repeatIfDue(alarm: SensorAlarm, state: AlarmState, nowMs: Long): AlarmEventKind? {
        if (alarm.repeatSec <= 0 || nowMs - state.lastAnnouncedMs < alarm.repeatSec * 1000L) return null
        state.lastAnnouncedMs = nowMs
        return AlarmEventKind.REPEATED
    }
}
