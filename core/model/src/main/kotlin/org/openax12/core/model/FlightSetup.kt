package org.openax12.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Flight modes and the wing / flaps wizard. Both are *compiled into firmware mixes* by
// FlightModeCompiler / WingCompiler in :core:engine (design rule 1); what is stored here is only
// the user's intent.

/** Kind of aircraft; selects the default picture and the applicable templates / wizards. */
@Serializable
enum class ModelType {
    GLIDER,
    PLANE,
    WING,
    MULTIROTOR,
    HELICOPTER,
    OTHER,
}

/**
 * One flight mode.
 *
 * @property name spoken and shown in the status bar
 * @property position the position of the mode switch that selects this mode
 * @property offsets per-channel offset in percent (trim / camber / elevator compensation), key =
 *   0-based channel index. Channels with a non-zero offset in any mode get one managed mix line.
 * @property rates per-channel rate (dual-rate weight in percent), key = 0-based channel index.
 *   Absent = 100 %. Compiled into dual-rate slots on the mode switch.
 * @property transitionMs slow time for moving to this mode's offsets; the compiler uses the
 *   largest value of all modes for a channel's mix line (the firmware has one slow value per line)
 * @property announce speak the name when the mode becomes active
 */
@Serializable
data class FlightModeConfig(
    val name: String = "",
    val position: SwitchPosition = SwitchPosition.UP,
    val offsets: Map<Int, Int> = emptyMap(),
    val rates: Map<Int, Int> = emptyMap(),
    val transitionMs: Int = 0,
    val announce: Boolean = true,
)

/**
 * Flight modes of a model: one 2- or 3-position switch selects the mode. `modes[0]` is the
 * default mode (also active when no switch is assigned or no mode matches the switch position).
 *
 * @property modeSwitch the mode switch; null = flight modes not used
 * @property modes 1..[FLIGHT_MODE_MAX] modes, each bound to a distinct switch position
 */
@Serializable
data class FlightModesConfig(
    val enabled: Boolean = false,
    val modeSwitch: SourceId? = null,
    val modes: List<FlightModeConfig> = emptyList(),
) {
    /** True if there is something to compile and evaluate. */
    val isActive: Boolean get() = enabled && modeSwitch != null && modes.isNotEmpty()

    /** Index of the mode selected by [position]; 0 (default mode) if none matches. */
    fun modeIndexFor(position: SwitchPosition): Int =
        modes.indexOfFirst { it.position == position }.coerceAtLeast(0)

    /** Index of the mode active for [live]; 0 when flight modes are not in use. */
    fun activeModeIndex(live: LiveSnapshot): Int {
        val switch = modeSwitch
        if (!isActive || switch == null) return 0
        return modeIndexFor(live.switchPosition(switch))
    }
}

/** Wing layout handled by the wing wizard. */
@Serializable
enum class WingType {
    /** Wizard not used for the wing. */
    NONE,

    /** One aileron channel (one servo or a Y-lead). */
    ONE_AILERON,

    /** Two aileron channels (2-servo wing). */
    TWO_AILERONS,

    /** Two ailerons + two flaps (4-servo wing). */
    TWO_AILERONS_TWO_FLAPS,

    /** Two ailerons + two flaps + two tip/inner surfaces (6-servo wing). */
    TWO_AILERONS_FOUR_FLAPS,

    /** Tailless delta / flying wing: two elevons. */
    ELEVON,
}

/** Tail layout handled by the wing wizard. */
@Serializable
enum class TailType {
    /** Wizard not used for the tail. */
    NONE,

    /** Cross or T tail: separate elevator and rudder. */
    NORMAL,

    /** V-tail: elevator and rudder mixed onto two surfaces. */
    V_TAIL,
}

/**
 * Output channels (0-based) of the surfaces. Null = the model has no such surface. For a V-tail
 * [elevator] is the left and [rudder] the right surface; for an elevon wing [aileronLeft] /
 * [aileronRight] are the elevons.
 */
@Serializable
data class WingChannels(
    val aileronLeft: Int? = null,
    val aileronRight: Int? = null,
    val flapLeft: Int? = null,
    val flapRight: Int? = null,
    val tipLeft: Int? = null,
    val tipRight: Int? = null,
    val elevator: Int? = null,
    val rudder: Int? = null,
    val throttle: Int? = null,
)

/** How the flaps are commanded. JSON class discriminator `"type"`. */
@Serializable
sealed interface FlapControl {

    /** No flap control. */
    @Serializable
    @SerialName("none")
    data object None : FlapControl

    /**
     * Flap positions on a 2- or 3-position switch. Deflections in percent of flap travel;
     * elevator compensation in percent of elevator travel, per switch position.
     */
    @Serializable
    @SerialName("switch")
    data class OnSwitch(
        val source: SourceId,
        val deflectionUp: Int = 0,
        val deflectionMid: Int = 50,
        val deflectionDown: Int = 100,
        val elevatorUp: Int = 0,
        val elevatorMid: Int = 0,
        val elevatorDown: Int = 0,
    ) : FlapControl

    /**
     * Proportional flaps on a slider / knob: [deflectionMax] percent at full travel, with
     * [elevatorMax] percent elevator compensation at full travel.
     */
    @Serializable
    @SerialName("slider")
    data class OnSlider(
        val source: SourceId,
        val deflectionMax: Int = 100,
        val elevatorMax: Int = 0,
        val reversed: Boolean = false,
    ) : FlapControl
}

/**
 * Flap / camber settings.
 *
 * @property speedMs slow time of the flap movement (mix slow up/down)
 * @property aileronFollow percent of the flap deflection the ailerons follow (flaperon / full-span camber)
 */
@Serializable
data class FlapConfig(
    val control: FlapControl = FlapControl.None,
    val speedMs: Int = 0,
    val aileronFollow: Int = 0,
)

/**
 * Butterfly / crow braking: flaps down, ailerons up, elevator compensation, proportional to
 * [source] (throttle stick or a slider).
 *
 * @property reversed false: no brake at +100 % of [source], full brake at −100 % (throttle stick
 *   pulled back); true: no brake at −100 %, full brake at +100 %
 * @property flapDown flap deflection in percent at full brake
 * @property aileronUp aileron reflex in percent at full brake
 * @property tipUp deflection of the tip surfaces in percent at full brake (6-servo wing)
 * @property elevatorCompensation elevator compensation at 0 / 25 / 50 / 75 / 100 % brake, in
 *   percent (compiled into a custom curve); empty = none
 * @property deadband percent of source travel near "no brake" that does nothing
 * @property activeWhen the brake only works while this condition holds (compiled into the switch
 *   condition of every butterfly mix line); disabled = always. Used by motor gliders that share
 *   the throttle stick between motor (armed) and brake (disarmed).
 */
@Serializable
data class ButterflyConfig(
    val enabled: Boolean = false,
    val source: SourceId = SourceId.THR,
    val reversed: Boolean = false,
    val flapDown: Int = 80,
    val aileronUp: Int = 30,
    val tipUp: Int = 0,
    val elevatorCompensation: List<Int> = emptyList(),
    val deadband: Int = 5,
    val activeWhen: SwitchCondition = SwitchCondition.NONE,
)

/**
 * Wing / flaps wizard settings of a model.
 *
 * @property aileronDifferential percent by which the downward aileron travel is reduced (0 = none)
 * @property aileronToRudder percent of aileron mixed into the rudder (combi mix), 0 = none
 * @property vTailElevatorWeight / [vTailRudderWeight] weights of the V-tail mix
 * @property elevonAileronWeight / [elevonElevatorWeight] weights of the elevon mix
 */
@Serializable
data class WingConfig(
    val enabled: Boolean = false,
    val wingType: WingType = WingType.NONE,
    val tailType: TailType = TailType.NONE,
    val channels: WingChannels = WingChannels(),
    val aileronDifferential: Int = 0,
    val aileronToRudder: Int = 0,
    val flaps: FlapConfig = FlapConfig(),
    val butterfly: ButterflyConfig = ButterflyConfig(),
    val vTailElevatorWeight: Int = 50,
    val vTailRudderWeight: Int = 50,
    val elevonAileronWeight: Int = 50,
    val elevonElevatorWeight: Int = 50,
)
