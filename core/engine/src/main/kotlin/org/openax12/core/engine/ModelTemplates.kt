package org.openax12.core.engine

import org.openax12.core.model.ButterflyConfig
import org.openax12.core.model.CompareOp
import org.openax12.core.model.CurvePoint
import org.openax12.core.model.CurveRef
import org.openax12.core.model.CurveType
import org.openax12.core.model.FlapConfig
import org.openax12.core.model.FlapControl
import org.openax12.core.model.FlightModeConfig
import org.openax12.core.model.FlightModesConfig
import org.openax12.core.model.ManagedItems
import org.openax12.core.model.MixPlan
import org.openax12.core.model.ModelBlueprint
import org.openax12.core.model.ModelExtras
import org.openax12.core.model.ModelType
import org.openax12.core.model.PlannedChannel
import org.openax12.core.model.PlannedCurve
import org.openax12.core.model.PlannedMix
import org.openax12.core.model.Repeat
import org.openax12.core.model.SensorId
import org.openax12.core.model.Sensors
import org.openax12.core.model.SourceId
import org.openax12.core.model.SpeechPriority
import org.openax12.core.model.SwitchCondition
import org.openax12.core.model.SwitchPosition
import org.openax12.core.model.TailType
import org.openax12.core.model.TelemetryLayout
import org.openax12.core.model.TelemetryPage
import org.openax12.core.model.TimerConfig
import org.openax12.core.model.Trigger
import org.openax12.core.model.VarioConfig
import org.openax12.core.model.VoiceAlert
import org.openax12.core.model.WidgetConfig
import org.openax12.core.model.WidgetOptions
import org.openax12.core.model.WidgetType
import org.openax12.core.model.WingChannels
import org.openax12.core.model.WingConfig
import org.openax12.core.model.WingType
import org.openax12.core.model.WorkingConfig

/** The options a template may offer in the "new model" flow. */
enum class TemplateOptionKey {
    /** [TemplateOptions.wingServos]: 2, 4 or 6. */
    WING_SERVOS,

    /** [TemplateOptions.tail]. */
    TAIL,

    /** [TemplateOptions.hasMotor]. */
    MOTOR,

    /** [TemplateOptions.hasFlaps]. */
    FLAPS,

    /** [TemplateOptions.hasRudder]. */
    RUDDER,

    /** [TemplateOptions.aileronDifferential]. */
    DIFFERENTIAL,

    /** [TemplateOptions.modeSwitch]. */
    MODE_SWITCH,

    /** [TemplateOptions.armSwitch]. */
    ARM_SWITCH,

    /** [TemplateOptions.beeperSwitch]. */
    BEEPER_SWITCH,

    /** [TemplateOptions.throttleHoldSwitch]. */
    THROTTLE_HOLD_SWITCH,

    /** [TemplateOptions.flapSwitch]. */
    FLAP_SWITCH,
}

/**
 * User choices for building a template. Every template reads only the options listed in its
 * [ModelTemplate.options]; the rest is ignored.
 *
 * @property wingServos number of wing servos of a glider: 2 (ailerons), 4 (+ flaps) or 6 (+ tips)
 * @property tail cross / T tail or V-tail
 * @property hasMotor the model has a motor on the throttle channel (adds throttle cut on [armSwitch])
 * @property hasFlaps plane: add flaps on [flapSwitch]
 * @property hasRudder plane: the model has a rudder
 * @property aileronDifferential differential in percent
 * @property modeSwitch flight-mode switch (glider camber modes, multirotor / helicopter flight mode channel)
 * @property armSwitch arm / throttle-cut switch
 * @property beeperSwitch multirotor beeper switch
 * @property throttleHoldSwitch helicopter throttle hold switch
 * @property flapSwitch flap / butterfly selector switch
 */
data class TemplateOptions(
    val wingServos: Int = 2,
    val tail: TailType = TailType.NORMAL,
    val hasMotor: Boolean = false,
    val hasFlaps: Boolean = false,
    val hasRudder: Boolean = true,
    val aileronDifferential: Int = 0,
    val modeSwitch: SourceId = SourceId.SA,
    val armSwitch: SourceId = SourceId.SF,
    val beeperSwitch: SourceId = SourceId.SD,
    val throttleHoldSwitch: SourceId = SourceId.SF,
    val flapSwitch: SourceId = SourceId.SB,
)

/**
 * A built-in model template (SPEC section 4). Names and descriptions are string resources of
 * `:feature:models`, keyed by [id].
 *
 * @property id stable id, also stored in `ModelExtras.templateId`
 * @property options which [TemplateOptions] fields the template uses, in the order to ask for them
 */
data class ModelTemplate(
    val id: String,
    val modelType: ModelType,
    val options: List<TemplateOptionKey>,
)

/**
 * The built-in templates. Each one produces a [ModelBlueprint]: a base mix plan for the firmware
 * side and default [org.openax12.core.model.ModelExtras] (model type, flight modes, wing wizard
 * settings, a starter telemetry page and voice alerts).
 *
 * ## Ownership rule (see [ModelBlueprint])
 * - `blueprint.plan` has the generator `"template:<id>"`, `replaceAllMixes = true` and
 *   `resetConfig = true`, and contains **only** what the wizards cannot express: base stick →
 *   channel mixes that are not part of the wing / tail wizard, switch → channel mixes of
 *   multirotor / helicopter, channel limits, channel locks (throttle cut / hold), timers.
 * - Everything a [org.openax12.core.model.WingConfig] or
 *   [org.openax12.core.model.FlightModesConfig] can express (aileron / flap / butterfly / V-tail /
 *   elevon mixes, differential, camber modes, per-mode rates) goes **only** into
 *   `blueprint.extras.wing` / `blueprint.extras.flightModes`. `ConfigSession.createModel`
 *   compiles those with [WingCompiler] / [FlightModeCompiler] after applying the base plan, so
 *   the generated lines belong to the generators `"wing"` / `"flightModes"` and the user can
 *   recompile them from the Flaps / Flight modes tabs.
 * - The base plan must not contain a `(channel, source)` key, curve or dual rate that those two
 *   compilations produce (it would be a blocking `MIX_KEY_IN_USE`). The engine tests check this
 *   for every template and option combination.
 *
 * ## Channel maps (0-based indices in the constants below)
 * - [GLIDER]: CH1 right aileron, CH2 elevator (V-tail: left surface), CH3 motor (only with
 *   `hasMotor`), CH4 rudder (V-tail: right surface), CH5 left aileron, CH6 / CH7 right / left
 *   flap (4 and 6 servos), CH8 / CH9 right / left tip (6 servos). Camber flight modes Cruise
 *   (mid, default) / Speed (up) / Thermal (down) on the mode switch, butterfly on the throttle
 *   stick. With a motor the throttle stick drives the motor while the arm switch is down
 *   (armed) and the brake while it is not (`ButterflyConfig.activeWhen`); the motor channel is
 *   locked to −100 while disarmed.
 * - [PLANE]: CH1 (right) aileron, CH2 elevator, CH3 throttle, CH4 rudder, CH5 left aileron (with
 *   differential or flaps), CH6 / CH7 right / left flap on the flap switch. Throttle cut on the
 *   arm switch.
 * - [FLYING_WING]: CH1 right elevon, CH2 left elevon, CH3 throttle with throttle cut.
 * - [MULTIROTOR]: AETR on CH1–4, arm switch on CH5, mode switch on CH6, beeper on CH7.
 * - [HELICOPTER]: flybarless — CH1 aileron, CH2 elevator, CH3 throttle, CH4 rudder, CH5 arm
 *   (the throttle-hold switch: up = hold = −100), CH6 collective pitch (throttle stick through an
 *   editable pitch curve), CH7 mode switch. Throttle: in the normal mode (mode switch up) the
 *   stick through an editable throttle curve, in idle-up 1 / 2 (mid / down) a fixed value from a
 *   3-point curve on the mode switch (a channel can hold only one line per source, so idle-up
 *   cannot have its own stick curve). Throttle hold locks CH3 to −100.
 *
 * Every arm / hold switch is "down = live": the throttle is cut in every other position.
 */
object ModelTemplates {
    /** Template id of the glider template. */
    const val GLIDER = "glider"
    /** Template id of the powered plane template. */
    const val PLANE = "plane"
    /** Template id of the flying-wing (elevon) template. */
    const val FLYING_WING = "wing"
    /** Template id of the multirotor template. */
    const val MULTIROTOR = "multirotor"
    /** Template id of the flybarless helicopter template. */
    const val HELICOPTER = "helicopter"

    /** Channel (0-based) of the right / only aileron, roll, or the right elevon. */
    const val CH_AILERON: Int = 0
    /** Channel of the elevator, pitch, the left V-tail surface, or the left elevon. */
    const val CH_ELEVATOR: Int = 1
    /** Channel of the throttle / motor. */
    const val CH_THROTTLE: Int = 2
    /** Channel of the rudder, yaw, or the right V-tail surface. */
    const val CH_RUDDER: Int = 3
    /** Channel of the left aileron (fixed wing) or of the arm switch (multirotor, helicopter). */
    const val CH_AUX1: Int = 4
    /** Channel of the right flap, the multirotor mode switch, or the collective pitch. */
    const val CH_AUX2: Int = 5
    /** Channel of the left flap, the beeper, or the helicopter mode switch. */
    const val CH_AUX3: Int = 6
    /** Channel of the right tip surface of a 6-servo wing. */
    const val CH_TIP_RIGHT: Int = 7
    /** Channel of the left tip surface of a 6-servo wing. */
    const val CH_TIP_LEFT: Int = 8

    /** An arm / hold switch counts as "live" below this logic value (= switch down, which reports −100). */
    const val ARMED_BELOW: Int = -50

    /** Planned curve key of the helicopter's normal-mode throttle curve. */
    const val CURVE_HELI_THROTTLE: String = "heli:throttle"
    /** Planned curve key of the helicopter's idle-up throttle values per mode-switch position. */
    const val CURVE_HELI_IDLE_UP: String = "heli:idleup"
    /** Planned curve key of the helicopter's collective pitch curve. */
    const val CURVE_HELI_PITCH: String = "heli:pitch"

    private val templates: List<ModelTemplate> = listOf(
        ModelTemplate(
            GLIDER,
            ModelType.GLIDER,
            listOf(
                TemplateOptionKey.WING_SERVOS, TemplateOptionKey.TAIL, TemplateOptionKey.MOTOR,
                TemplateOptionKey.DIFFERENTIAL, TemplateOptionKey.MODE_SWITCH, TemplateOptionKey.ARM_SWITCH,
            ),
        ),
        ModelTemplate(
            PLANE,
            ModelType.PLANE,
            listOf(
                TemplateOptionKey.RUDDER, TemplateOptionKey.FLAPS, TemplateOptionKey.DIFFERENTIAL,
                TemplateOptionKey.FLAP_SWITCH, TemplateOptionKey.ARM_SWITCH,
            ),
        ),
        ModelTemplate(FLYING_WING, ModelType.WING, listOf(TemplateOptionKey.DIFFERENTIAL, TemplateOptionKey.ARM_SWITCH)),
        ModelTemplate(
            MULTIROTOR,
            ModelType.MULTIROTOR,
            listOf(TemplateOptionKey.ARM_SWITCH, TemplateOptionKey.MODE_SWITCH, TemplateOptionKey.BEEPER_SWITCH),
        ),
        ModelTemplate(HELICOPTER, ModelType.HELICOPTER, listOf(TemplateOptionKey.MODE_SWITCH, TemplateOptionKey.THROTTLE_HOLD_SWITCH)),
    )

    /** All built-in templates in display order. */
    val all: List<ModelTemplate> get() = templates

    /** The template with [id], or null. */
    fun byId(id: String): ModelTemplate? = templates.firstOrNull { it.id == id }

    /** Generator name of the base plan of template [id] (`"template:<id>"`). */
    fun generatorOf(id: String): String = ManagedItems.GENERATOR_TEMPLATE_PREFIX + id

    /**
     * Builds the blueprint of [template] for the given [options], following the ownership rule
     * of the class documentation. Options that make no sense are replaced by the defaults (a
     * switch option that is not a switch, a servo count other than 2 / 4 / 6); an unknown
     * template gives a blank model of its type.
     *
     * @param current any working config of this radio (in practice the currently loaded model);
     *   used only for what is the same for every model — which channels and sources exist. The
     *   plan must not depend on its mixes, curves or dual rates: it is applied with
     *   `resetConfig` to a model that starts as a copy of some other configuration.
     */
    fun build(template: ModelTemplate, options: TemplateOptions, current: WorkingConfig): ModelBlueprint {
        val defaults = TemplateOptions()
        val safe = options.copy(
            wingServos = when {
                options.wingServos >= 6 -> 6
                options.wingServos >= 4 -> 4
                else -> 2
            },
            aileronDifferential = options.aileronDifferential.coerceIn(-100, 100),
            modeSwitch = options.modeSwitch.orSwitch(defaults.modeSwitch),
            armSwitch = options.armSwitch.orSwitch(defaults.armSwitch),
            beeperSwitch = options.beeperSwitch.orSwitch(defaults.beeperSwitch),
            throttleHoldSwitch = options.throttleHoldSwitch.orSwitch(defaults.throttleHoldSwitch),
            flapSwitch = options.flapSwitch.orSwitch(defaults.flapSwitch),
        )
        return when (template.id) {
            GLIDER -> glider(safe)
            PLANE -> plane(safe)
            FLYING_WING -> flyingWing(safe)
            MULTIROTOR -> multirotor(safe)
            HELICOPTER -> helicopter(safe)
            else -> ModelBlueprint.blank(template.modelType).copy(templateId = template.id)
        }
    }

    // ---- templates ---------------------------------------------------------------------------

    private fun glider(options: TemplateOptions): ModelBlueprint {
        val servos = options.wingServos
        val hasFlaps = servos >= 4
        val hasTips = servos >= 6
        val vTail = options.tail == TailType.V_TAIL
        val channels = WingChannels(
            aileronRight = CH_AILERON,
            aileronLeft = CH_AUX1,
            flapRight = if (hasFlaps) CH_AUX2 else null,
            flapLeft = if (hasFlaps) CH_AUX3 else null,
            tipRight = if (hasTips) CH_TIP_RIGHT else null,
            tipLeft = if (hasTips) CH_TIP_LEFT else null,
            elevator = CH_ELEVATOR,
            rudder = CH_RUDDER,
            throttle = if (options.hasMotor) CH_THROTTLE else null,
        )
        val wing = WingConfig(
            enabled = true,
            wingType = when {
                hasTips -> WingType.TWO_AILERONS_FOUR_FLAPS
                hasFlaps -> WingType.TWO_AILERONS_TWO_FLAPS
                else -> WingType.TWO_AILERONS
            },
            tailType = if (vTail) TailType.V_TAIL else TailType.NORMAL,
            channels = channels,
            aileronDifferential = options.aileronDifferential,
            flaps = FlapConfig(),
            butterfly = ButterflyConfig(
                enabled = true,
                source = SourceId.THR,
                reversed = false,
                // Ailerons alone act as spoilerons; with flaps the classic crow.
                flapDown = if (hasFlaps) 80 else 0,
                aileronUp = if (hasFlaps) 30 else 50,
                tipUp = if (hasTips) 20 else 0,
                elevatorCompensation = emptyList(),
                deadband = 5,
                // With a motor the stick is the throttle while armed and the brake while disarmed.
                activeWhen = if (options.hasMotor) disarmed(options.armSwitch) else SwitchCondition.NONE,
            ),
        )

        // Camber: positive = trailing edge up (see WingCompiler).
        fun camber(aileron: Int, flap: Int): Map<Int, Int> = buildMap {
            put(CH_AILERON, aileron)
            put(CH_AUX1, aileron)
            if (hasFlaps) {
                put(CH_AUX2, flap)
                put(CH_AUX3, flap)
            }
            if (hasTips) {
                put(CH_TIP_RIGHT, aileron)
                put(CH_TIP_LEFT, aileron)
            }
        }
        val flightModes = FlightModesConfig(
            enabled = true,
            modeSwitch = options.modeSwitch,
            modes = listOf(
                FlightModeConfig(name = "Cruise", position = SwitchPosition.MID, transitionMs = CAMBER_TRANSITION_MS),
                FlightModeConfig(name = "Speed", position = SwitchPosition.UP, offsets = camber(aileron = 2, flap = 3), transitionMs = CAMBER_TRANSITION_MS),
                FlightModeConfig(name = "Thermal", position = SwitchPosition.DOWN, offsets = camber(aileron = -4, flap = -6), transitionMs = CAMBER_TRANSITION_MS),
            ),
        )

        val plan = if (options.hasMotor) {
            basePlan(
                GLIDER,
                description = "Glider with motor",
                mixes = listOf(PlannedMix(CH_THROTTLE, SourceId.THR, comment = "Motor")),
                channels = listOf(throttleCut(CH_THROTTLE, options.armSwitch, "Motor off while disarmed")),
                timers = listOf(TimerConfig(index = 0, switch = armed(options.armSwitch))),
            )
        } else {
            basePlan(GLIDER, description = "Glider")
        }
        return blueprint(
            GLIDER,
            ModelType.GLIDER,
            plan,
            wing = wing,
            flightModes = flightModes,
            vario = VarioConfig(alwaysOn = true),
            telemetry = starterPage(
                "Glider",
                widget(WidgetType.VARIO, 0, 0, 2, 2, Sensors.VERTICAL_SPEED, pin = 0),
                widget(WidgetType.VALUE_MIN_MAX, 2, 0, 2, 2, Sensors.BARO_ALTITUDE, pin = 1),
                widget(WidgetType.BATTERY, 4, 0, 2, 2, Sensors.BATTERY_VOLTAGE, pin = 2),
                widget(WidgetType.LINK_QUALITY, 0, 2, 2, 1, Sensors.TX_LINK_QUALITY, pin = 3),
                timerWidget(2, 2, pin = 4),
                widget(WidgetType.FLIGHT_MODE, 4, 2, 2, 1, Sensors.FLIGHT_MODE, pin = 5),
                widget(WidgetType.GRAPH, 0, 3, 6, 1, Sensors.BARO_ALTITUDE),
            ),
            lowBatteryAlert = false,
        )
    }

    private fun plane(options: TemplateOptions): ModelBlueprint {
        val twoAilerons = options.hasFlaps || options.aileronDifferential != 0
        val wing = WingConfig(
            enabled = true,
            wingType = when {
                options.hasFlaps -> WingType.TWO_AILERONS_TWO_FLAPS
                twoAilerons -> WingType.TWO_AILERONS
                else -> WingType.ONE_AILERON
            },
            tailType = TailType.NORMAL,
            channels = WingChannels(
                aileronRight = CH_AILERON,
                aileronLeft = if (twoAilerons) CH_AUX1 else null,
                flapRight = if (options.hasFlaps) CH_AUX2 else null,
                flapLeft = if (options.hasFlaps) CH_AUX3 else null,
                elevator = CH_ELEVATOR,
                rudder = if (options.hasRudder) CH_RUDDER else null,
                throttle = CH_THROTTLE,
            ),
            aileronDifferential = if (twoAilerons) options.aileronDifferential else 0,
            flaps = if (options.hasFlaps) {
                FlapConfig(
                    control = FlapControl.OnSwitch(source = options.flapSwitch, deflectionUp = 0, deflectionMid = 50, deflectionDown = 100),
                    speedMs = FLAP_SPEED_MS,
                )
            } else {
                FlapConfig()
            },
        )
        return blueprint(
            PLANE,
            ModelType.PLANE,
            poweredBasePlan(PLANE, "Plane", options.armSwitch),
            wing = wing,
            telemetry = starterPage(
                "Plane",
                widget(WidgetType.BATTERY, 0, 0, 2, 2, Sensors.BATTERY_VOLTAGE, pin = 0),
                widget(WidgetType.VALUE, 2, 0, 2, 1, Sensors.BATTERY_CURRENT, pin = 1),
                widget(WidgetType.VALUE, 2, 1, 2, 1, Sensors.BATTERY_CAPACITY, pin = 2),
                widget(WidgetType.LINK_QUALITY, 4, 0, 2, 1, Sensors.TX_LINK_QUALITY, pin = 3),
                timerWidget(4, 1, pin = 4),
                widget(WidgetType.VALUE_MIN_MAX, 0, 2, 2, 2, Sensors.BARO_ALTITUDE, pin = 5),
                widget(WidgetType.VALUE_MIN_MAX, 2, 2, 2, 2, Sensors.GPS_GROUND_SPEED),
                widget(WidgetType.GPS, 4, 2, 2, 2, Sensors.GPS_LATITUDE, Sensors.GPS_LONGITUDE),
            ),
            lowBatteryAlert = false,
        )
    }

    private fun flyingWing(options: TemplateOptions): ModelBlueprint {
        val wing = WingConfig(
            enabled = true,
            wingType = WingType.ELEVON,
            tailType = TailType.NONE,
            channels = WingChannels(aileronRight = CH_AILERON, aileronLeft = CH_ELEVATOR, throttle = CH_THROTTLE),
            aileronDifferential = options.aileronDifferential,
        )
        return blueprint(
            FLYING_WING,
            ModelType.WING,
            poweredBasePlan(FLYING_WING, "Flying wing", options.armSwitch),
            wing = wing,
            telemetry = starterPage(
                "Wing",
                widget(WidgetType.BATTERY, 0, 0, 2, 2, Sensors.BATTERY_VOLTAGE, pin = 0),
                widget(WidgetType.VALUE, 2, 0, 2, 1, Sensors.BATTERY_CURRENT, pin = 1),
                widget(WidgetType.VALUE, 2, 1, 2, 1, Sensors.BATTERY_CAPACITY, pin = 2),
                widget(WidgetType.LINK_QUALITY, 4, 0, 2, 1, Sensors.TX_LINK_QUALITY, pin = 3),
                timerWidget(4, 1, pin = 4),
                widget(WidgetType.VALUE_MIN_MAX, 0, 2, 2, 2, Sensors.BARO_ALTITUDE, pin = 5),
                widget(WidgetType.VALUE_MIN_MAX, 2, 2, 2, 2, Sensors.GPS_GROUND_SPEED),
                widget(WidgetType.GPS, 4, 2, 2, 2, Sensors.GPS_LATITUDE, Sensors.GPS_LONGITUDE),
            ),
            lowBatteryAlert = false,
        )
    }

    private fun multirotor(options: TemplateOptions): ModelBlueprint {
        val plan = basePlan(
            MULTIROTOR,
            description = "Multirotor (AETR, arm on CH5, mode on CH6, beeper on CH7)",
            mixes = listOf(
                PlannedMix(CH_AILERON, SourceId.AIL, comment = "Roll"),
                PlannedMix(CH_ELEVATOR, SourceId.ELE, comment = "Pitch"),
                PlannedMix(CH_THROTTLE, SourceId.THR, comment = "Throttle"),
                PlannedMix(CH_RUDDER, SourceId.RUD, comment = "Yaw"),
                PlannedMix(CH_AUX1, options.armSwitch, comment = "Arm"),
                PlannedMix(CH_AUX2, options.modeSwitch, comment = "Flight mode"),
                PlannedMix(CH_AUX3, options.beeperSwitch, comment = "Beeper"),
            ),
            timers = listOf(TimerConfig(index = 0, switch = armed(options.armSwitch))),
        )
        // The names are only announced and displayed: the flight controller owns the modes.
        val flightModes = FlightModesConfig(
            enabled = true,
            modeSwitch = options.modeSwitch,
            modes = listOf(
                FlightModeConfig(name = "Angle", position = SwitchPosition.UP),
                FlightModeConfig(name = "Horizon", position = SwitchPosition.MID),
                FlightModeConfig(name = "Acro", position = SwitchPosition.DOWN),
            ),
        )
        return blueprint(
            MULTIROTOR,
            ModelType.MULTIROTOR,
            plan,
            flightModes = flightModes,
            telemetry = starterPage(
                "Multirotor",
                widget(WidgetType.BATTERY, 0, 0, 2, 2, Sensors.BATTERY_VOLTAGE, Sensors.BATTERY_CURRENT, Sensors.BATTERY_CAPACITY, pin = 0),
                widget(WidgetType.HORIZON, 2, 0, 2, 2, Sensors.ATTITUDE_PITCH, Sensors.ATTITUDE_ROLL, pin = 1),
                widget(WidgetType.LINK_QUALITY, 4, 0, 2, 1, Sensors.TX_LINK_QUALITY, pin = 2),
                timerWidget(4, 1, pin = 3),
                widget(WidgetType.VALUE, 0, 2, 2, 1, Sensors.FC_FLIGHT_MODE, pin = 4),
                widget(WidgetType.VALUE, 2, 2, 2, 1, Sensors.BATTERY_REMAINING, pin = 5),
                widget(WidgetType.VALUE, 4, 2, 2, 1, Sensors.GPS_SATELLITES),
                widget(WidgetType.GPS, 0, 3, 6, 1, Sensors.GPS_LATITUDE, Sensors.GPS_LONGITUDE),
            ),
            lowBatteryAlert = true,
        )
    }

    private fun helicopter(options: TemplateOptions): ModelBlueprint {
        val linear = listOf(-100, -50, 0, 50, 100).map { CurvePoint(it, it) }
        val plan = basePlan(
            HELICOPTER,
            description = "Flybarless helicopter (collective on CH6, idle-up on the mode switch, throttle hold)",
            mixes = listOf(
                PlannedMix(CH_AILERON, SourceId.AIL, comment = "Aileron"),
                PlannedMix(CH_ELEVATOR, SourceId.ELE, comment = "Elevator"),
                PlannedMix(CH_RUDDER, SourceId.RUD, comment = "Rudder"),
                PlannedMix(
                    CH_THROTTLE,
                    SourceId.THR,
                    curve = CurveRef.Planned(CURVE_HELI_THROTTLE),
                    switch = SwitchCondition.at(options.modeSwitch, SwitchPosition.UP),
                    comment = "Throttle curve (normal mode)",
                ),
                PlannedMix(CH_THROTTLE, options.modeSwitch, curve = CurveRef.Planned(CURVE_HELI_IDLE_UP), comment = "Idle-up throttle"),
                PlannedMix(CH_AUX1, options.throttleHoldSwitch, comment = "Arm (throttle hold off)"),
                PlannedMix(CH_AUX2, SourceId.THR, curve = CurveRef.Planned(CURVE_HELI_PITCH), comment = "Collective pitch"),
                PlannedMix(CH_AUX3, options.modeSwitch, comment = "Flight mode"),
            ),
            curves = listOf(
                PlannedCurve(CURVE_HELI_THROTTLE, CurveType.CUSTOM, linear, comment = "Throttle curve (normal mode)"),
                PlannedCurve(
                    CURVE_HELI_IDLE_UP,
                    CurveType.CUSTOM,
                    listOf(CurvePoint(-100, IDLE_UP_2), CurvePoint(0, IDLE_UP_1), CurvePoint(100, 0)),
                    comment = "Idle-up throttle (normal / idle-up 1 / idle-up 2)",
                ),
                PlannedCurve(CURVE_HELI_PITCH, CurveType.CUSTOM, linear, comment = "Collective pitch curve"),
            ),
            channels = listOf(throttleCut(CH_THROTTLE, options.throttleHoldSwitch, "Throttle hold")),
            timers = listOf(TimerConfig(index = 0, switch = armed(options.throttleHoldSwitch))),
        )
        val flightModes = FlightModesConfig(
            enabled = true,
            modeSwitch = options.modeSwitch,
            modes = listOf(
                FlightModeConfig(name = "Normal", position = SwitchPosition.UP),
                FlightModeConfig(name = "Idle up 1", position = SwitchPosition.MID),
                FlightModeConfig(name = "Idle up 2", position = SwitchPosition.DOWN),
            ),
        )
        return blueprint(
            HELICOPTER,
            ModelType.HELICOPTER,
            plan,
            flightModes = flightModes,
            telemetry = starterPage(
                "Helicopter",
                widget(WidgetType.BATTERY, 0, 0, 2, 2, Sensors.BATTERY_VOLTAGE, Sensors.BATTERY_CURRENT, Sensors.BATTERY_CAPACITY, pin = 0),
                widget(WidgetType.VALUE, 2, 0, 2, 1, Sensors.BATTERY_CURRENT, pin = 1),
                widget(WidgetType.VALUE, 2, 1, 2, 1, Sensors.BATTERY_CAPACITY, pin = 2),
                widget(WidgetType.LINK_QUALITY, 4, 0, 2, 1, Sensors.TX_LINK_QUALITY, pin = 3),
                timerWidget(4, 1, pin = 4),
                widget(WidgetType.FLIGHT_MODE, 0, 2, 2, 1, Sensors.FLIGHT_MODE, pin = 5),
                widget(WidgetType.VALUE, 2, 2, 2, 1, Sensors.BATTERY_REMAINING),
                widget(WidgetType.VALUE, 4, 2, 2, 1, Sensors.FC_FLIGHT_MODE),
            ),
            lowBatteryAlert = true,
        )
    }

    // ---- building blocks ---------------------------------------------------------------------

    /** Slow time of the glider's camber modes. */
    private const val CAMBER_TRANSITION_MS = 1000

    /** Slow time of the plane's flaps. */
    private const val FLAP_SPEED_MS = 1500

    /** Throttle of idle-up 1 / 2 as channel values (−100..+100): 70 % and 85 %. */
    private const val IDLE_UP_1 = 40
    private const val IDLE_UP_2 = 70

    /** Low-battery alert: below this remaining capacity in percent. */
    private const val LOW_BATTERY_PERCENT = 25.0

    private fun SourceId.orSwitch(fallback: SourceId): SourceId = if (isSwitch) this else fallback

    /** "The arm switch is down." */
    private fun armed(switch: SourceId): SwitchCondition = SwitchCondition(switch, CompareOp.LESS, ARMED_BELOW)

    /** "The arm switch is in any position but down." */
    private fun disarmed(switch: SourceId): SwitchCondition = SwitchCondition(switch, CompareOp.GREATER, ARMED_BELOW)

    /** Channel lock that holds [channel] at −100 unless [switch] is down. */
    private fun throttleCut(channel: Int, switch: SourceId, comment: String): PlannedChannel =
        PlannedChannel(channel = channel, lock = disarmed(switch), lockValue = -100, comment = comment)

    private fun basePlan(
        id: String,
        description: String,
        mixes: List<PlannedMix> = emptyList(),
        curves: List<PlannedCurve> = emptyList(),
        channels: List<PlannedChannel> = emptyList(),
        timers: List<TimerConfig> = emptyList(),
    ): MixPlan = MixPlan(
        generator = generatorOf(id),
        mixes = mixes,
        curves = curves,
        channels = channels,
        timers = timers,
        replaceAllMixes = true,
        description = description,
        resetConfig = true,
    )

    /** Base plan of a powered fixed-wing model: throttle with throttle cut and a motor-run timer. */
    private fun poweredBasePlan(id: String, description: String, armSwitch: SourceId): MixPlan = basePlan(
        id,
        description = description,
        mixes = listOf(PlannedMix(CH_THROTTLE, SourceId.THR, comment = "Throttle")),
        channels = listOf(throttleCut(CH_THROTTLE, armSwitch, "Throttle cut")),
        timers = listOf(TimerConfig(index = 0, switch = SwitchCondition(SourceId.THR, CompareOp.GREATER, -90))),
    )

    private fun blueprint(
        id: String,
        type: ModelType,
        plan: MixPlan,
        wing: WingConfig = WingConfig(),
        flightModes: FlightModesConfig = FlightModesConfig(),
        vario: VarioConfig = VarioConfig(),
        telemetry: TelemetryLayout,
        lowBatteryAlert: Boolean,
    ): ModelBlueprint = ModelBlueprint(
        templateId = id,
        modelType = type,
        plan = plan,
        extras = ModelExtras(
            modelType = type,
            flightModes = flightModes,
            wing = wing,
            voiceAlerts = batteryAlerts(lowBatteryAlert),
            vario = vario,
            telemetry = telemetry,
            templateId = id,
        ),
    )

    /**
     * The battery voice alerts every template starts with: the voltage once when the sensor
     * appears and then every minute, and a low-capacity warning. The warning is switched on only
     * for models with a flight controller: a plain receiver reports 0 % remaining all the time.
     */
    private fun batteryAlerts(lowBatteryEnabled: Boolean): List<VoiceAlert> = listOf(
        VoiceAlert(
            id = "tpl-battery",
            name = "Battery voltage",
            text = "Battery {RxBt} volts",
            trigger = Trigger.Telemetry(Sensors.BATTERY_VOLTAGE, CompareOp.GREATER, 1.0),
            repeat = Repeat(intervalSec = 60, skipAtStartup = false),
        ),
        VoiceAlert(
            id = "tpl-battery-low",
            name = "Battery low",
            enabled = lowBatteryEnabled,
            text = "Battery low, {Bat%} percent",
            trigger = Trigger.Telemetry(Sensors.BATTERY_REMAINING, CompareOp.LESS, LOW_BATTERY_PERCENT, hysteresis = 5.0),
            repeat = Repeat(intervalSec = 30, skipAtStartup = false),
            priority = SpeechPriority.HIGH,
        ),
    )

    private fun starterPage(name: String, vararg widgets: WidgetConfig): TelemetryLayout = TelemetryLayout(
        pages = listOf(
            TelemetryPage(
                id = "tpl-page-1",
                name = name,
                widgets = widgets.mapIndexed { i, widget -> widget.copy(id = "tpl-widget-${i + 1}") },
            ),
        ),
    )

    /** A starter widget; [pin] ≥ 0 also shows it in the home centre card, in that order. */
    private fun widget(type: WidgetType, x: Int, y: Int, w: Int, h: Int, s1: SensorId, s2: SensorId? = null, s3: SensorId? = null, pin: Int = -1): WidgetConfig {
        val sensors = listOfNotNull(s1, s2, s3)
        return WidgetConfig(id = "", type = type, x = x, y = y, w = w, h = h, sensors = sensors, pinned = pin >= 0, pinOrder = pin.coerceAtLeast(0))
    }

    private fun timerWidget(x: Int, y: Int, pin: Int): WidgetConfig =
        widget(WidgetType.TIMER, x, y, 2, 1, SensorId.timer(0), pin = pin).withOption(WidgetOptions.TIMER, "0")
}
