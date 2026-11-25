package org.openax12.core.radio.fake

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import org.openax12.core.model.SOURCE_COUNT
import org.openax12.core.model.SensorId
import org.openax12.core.model.SourceId
import org.openax12.core.model.Sources
import org.openax12.core.model.SwitchPosition
import org.openax12.core.radio.SimulatedFault

/** Something the simulator panel asked for that the simulator applies with its next frame. */
internal sealed interface SimCommand {
    /** Set the trim that belongs to stick axis [source]. */
    data class Trim(val source: Int, val percent: Int) : SimCommand

    /** Pin a telemetry value. */
    data class Telemetry(val sensor: SensorId, val value: Double) : SimCommand

    /** Set the text of the CRSF flight-mode frame. */
    data class FlightMode(val text: String) : SimCommand

    /** Drop / restore the receiver link. */
    data class Link(val up: Boolean) : SimCommand

    /** Trigger a fault. */
    data class Fault(val fault: SimulatedFault) : SimCommand

    /** Demo motion was switched on: telemetry follows the flight model again. */
    data object DemoRestarted : SimCommand

    /** Reset telemetry, link and pending faults. */
    data object Reset : SimCommand
}

/**
 * The physical controls of the simulated transmitter (sticks, knobs, sliders, switches) plus the
 * queue of [SimCommand]s. This is the only simulator state that is touched from outside the
 * simulator's worker, so it carries its own lock; everything else is confined to the worker.
 */
internal class SimInputs(demoMotion: Boolean) {

    private val lock = Any()
    private val values = IntArray(SOURCE_COUNT) { SimDefaults.restPosition(it) }

    /** Sources the user set by hand since the demo motion was (re)started: the demo leaves them alone. */
    private val manual = BooleanArray(SOURCE_COUNT)
    private val commands = ArrayList<SimCommand>()

    /** True while the demo motion drives the sticks. */
    @Volatile
    var demoMotion: Boolean = demoMotion
        private set

    /** Switches the demo motion; switching it on hands all controls back to the demo. */
    fun setDemoMotion(enabled: Boolean) = synchronized(lock) {
        if (enabled && !demoMotion) {
            manual.fill(false)
            commands += SimCommand.DemoRestarted
        }
        demoMotion = enabled
    }

    /**
     * Sets a physical control by hand. Sticks, knobs and sliders are coerced to ±100; switches
     * snap to the nearest position (a 2-position switch has no middle and treats it as up).
     * Counter sources are computed by the firmware and cannot be set.
     */
    fun set(source: SourceId, percent: Int) {
        if (!source.isValid || source.isCounter) return
        val value = if (source.isSwitch) snap(source.index, percent) else percent.coerceIn(-100, 100)
        synchronized(lock) {
            values[source.index] = value
            manual[source.index] = true
        }
    }

    /** Current value of a physical control (counters read 0 here). */
    fun get(source: SourceId): Int = synchronized(lock) { values.getOrElse(source.index) { 0 } }

    /** Copy of all control values, taken once per frame. */
    fun snapshot(): IntArray = synchronized(lock) { values.copyOf() }

    /** Queues [command] for the next frame. */
    fun post(command: SimCommand) = synchronized(lock) {
        commands += command
        Unit
    }

    /** Takes all queued commands in the order they were posted. */
    fun drain(): List<SimCommand> = synchronized(lock) {
        if (commands.isEmpty()) return emptyList()
        val copy = ArrayList(commands)
        commands.clear()
        copy
    }

    /** All controls back to their rest positions, pending commands dropped, then [SimCommand.Reset] queued. */
    fun reset() = synchronized(lock) {
        for (i in values.indices) values[i] = SimDefaults.restPosition(i)
        manual.fill(false)
        commands.clear()
        commands += SimCommand.Reset
    }

    /**
     * Moves the controls nobody touched by hand along the demo flight: gentle aileron / elevator /
     * rudder movement, a throttle that stays at idle for the first seconds (so the pre-flight
     * check passes), then climbs and comes back to idle about every 36 s, and slider S1 drifting
     * slowly. Switches are left alone.
     *
     * @param timeMs simulated time since the simulator started
     */
    fun applyDemo(timeMs: Long) {
        if (!demoMotion) return
        val t = timeMs / 1000.0
        val aileron = 35.0 * sin(TWO_PI * t / 7.3) + 10.0 * sin(TWO_PI * t / 2.9)
        val elevator = 25.0 * sin(TWO_PI * t / 5.1 + 1.0)
        val rudder = 20.0 * sin(TWO_PI * t / 9.7 + 2.0)
        val throttle = if (t < THROTTLE_IDLE_SEC) -100.0 else -100.0 + 85.0 * (1.0 - cos(TWO_PI * (t - THROTTLE_IDLE_SEC) / 36.0))
        val slider = 60.0 * sin(TWO_PI * t / 23.0)
        synchronized(lock) {
            demo(Sources.AIL, aileron)
            demo(Sources.ELE, elevator)
            demo(Sources.RUD, rudder)
            demo(Sources.THR, throttle)
            demo(Sources.S1, slider)
        }
    }

    private fun demo(index: Int, value: Double) {
        if (!manual[index]) values[index] = value.roundToInt().coerceIn(-100, 100)
    }

    private fun snap(index: Int, percent: Int): Int {
        val position = SwitchPosition.fromLogic(percent)
        return if (position == SwitchPosition.MID && SimDefaults.isTwoPosition(index)) SwitchPosition.UP.logic else position.logic
    }

    private companion object {
        const val TWO_PI = 2.0 * PI
        const val THROTTLE_IDLE_SEC = 4.0
    }
}
