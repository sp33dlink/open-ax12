package org.openax12.feature.system.calibration

import org.openax12.core.model.LiveSnapshot
import org.openax12.core.model.SourceId
import org.openax12.core.model.StickMode
import org.openax12.core.radio.ConnectionState

/** Smallest and largest value seen on one stick axis, in percent. */
internal data class AxisExtent(val min: Int = 0, val max: Int = 0) {
    /** The axis reached both ends (|value| >= [threshold]). */
    fun reachedBothEnds(threshold: Int = REACHED_PERCENT): Boolean = min <= -threshold && max >= threshold

    /** The extent including [value]. */
    fun including(value: Int): AxisExtent = AxisExtent(minOf(min, value), maxOf(max, value))

    companion object {
        /** A stick axis counts as swept to its end from this deflection on. */
        const val REACHED_PERCENT = 95
    }
}

/**
 * The extremes seen on the four stick axes while a calibration runs: the screen shows which
 * axes still have to be moved to their ends.
 */
internal data class StickExtents(val axes: Map<Int, AxisExtent> = emptyMap()) {

    /** The extent of [source] (zero extent when nothing was observed). */
    fun of(source: SourceId): AxisExtent = axes[source.index] ?: AxisExtent()

    /** Folds one live frame in; returns `this` (same instance) when nothing changed. */
    fun observe(live: LiveSnapshot): StickExtents {
        var next = axes
        for (index in STICK_SOURCES) {
            val value = live.logicAt(index)
            val old = next[index]
            val updated = (old ?: AxisExtent(value, value)).including(value)
            if (updated != old) next = next + (index to updated)
        }
        return if (next === axes) this else StickExtents(next)
    }

    /** True when every axis was swept to both ends. */
    val isComplete: Boolean
        get() = STICK_SOURCES.all { of(SourceId(it)).reachedBothEnds() }

    companion object {
        /** Rud, Ele, Thr, Ail. */
        val STICK_SOURCES: IntRange = 0..3
    }
}

/** Everything the calibration screen shows besides the live stick values. */
internal data class CalibrationUiState(
    val connection: ConnectionState = ConnectionState.Stopped,
    val linkUp: Boolean = false,
    /** `RadioStatus.calibrationState`; 0 = not calibrating. */
    val calibrationState: Int = 0,
    val extents: StickExtents = StickExtents(),
    val stickMode: StickMode = StickMode.MODE_2,
    val busy: Boolean = false,
    val confirmStart: Boolean = false,
) {
    /** The radio is calibrating right now. */
    val running: Boolean get() = calibrationState != 0

    /**
     * The radio's calibration phase (observed in the stock app, which drives the same firmware):
     * 1 = preparing, 2 = capturing the centre (sticks must stay centred and still),
     * 3 = collecting the end points (move the sticks). Finishing is only valid in phase 3;
     * finishing earlier stores a wrong centre.
     */
    val phase: CalibrationPhase get() = CalibrationPhase.of(calibrationState)

    /** Finish may be pressed: the radio has the centre and is collecting the end points. */
    val canFinish: Boolean get() = phase == CalibrationPhase.MOVE_STICKS

    /** The radio can be told to calibrate. */
    val ready: Boolean get() = connection.isReady
}

/** Phases of the radio's stick calibration (`RadioStatus.calibrationState`). */
internal enum class CalibrationPhase {
    IDLE, PREPARING, CAPTURING_CENTRE, MOVE_STICKS;

    companion object {
        fun of(state: Int): CalibrationPhase = when (state) {
            0 -> IDLE
            1 -> PREPARING
            2 -> CAPTURING_CENTRE
            3 -> MOVE_STICKS
            else -> PREPARING
        }
    }
}
