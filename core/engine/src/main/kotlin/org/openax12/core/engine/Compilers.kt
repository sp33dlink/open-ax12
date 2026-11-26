package org.openax12.core.engine

import org.openax12.core.model.CHANNEL_COUNT
import org.openax12.core.model.CURVE_MAX_POINTS
import org.openax12.core.model.CurvePoint
import org.openax12.core.model.CurveRef
import org.openax12.core.model.CurveType
import org.openax12.core.model.FLIGHT_MODE_MAX
import org.openax12.core.model.FlapControl
import org.openax12.core.model.FlightModeConfig
import org.openax12.core.model.FlightModesConfig
import org.openax12.core.model.ManagedItems
import org.openax12.core.model.MixMode
import org.openax12.core.model.MixPlan
import org.openax12.core.model.PERCENT_MAX
import org.openax12.core.model.PERCENT_MIN
import org.openax12.core.model.PlanConflict
import org.openax12.core.model.PlanConflictKind
import org.openax12.core.model.PlanDiff
import org.openax12.core.model.PlannedCurve
import org.openax12.core.model.PlannedDualRate
import org.openax12.core.model.PlannedMix
import org.openax12.core.model.SourceId
import org.openax12.core.model.SwitchCondition
import org.openax12.core.model.SwitchPosition
import org.openax12.core.model.TailType
import org.openax12.core.model.WingConfig
import org.openax12.core.model.WingType
import org.openax12.core.model.WorkingConfig
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Compiles the flight-mode settings of a model into firmware items (SPEC section 7), so that
 * flight modes keep working when the app is not running.
 *
 * - For every channel with a non-zero offset in any mode: one mix line `channel += modeSwitch`
 *   through a planned **custom 3-point curve** (x = −100 / 0 / +100 = switch down / mid / up)
 *   whose y values are the offsets of the modes bound to those positions, with slow up / down =
 *   the largest transition time of the modes. Identical curves are shared (same curve key).
 * - For every channel with a rate ≠ 100 in a mode: one dual rate on that channel with the
 *   condition "mode switch at the mode's position" and the rate as weight.
 * - A disabled or empty configuration compiles to an empty plan (which removes everything this
 *   compiler generated before).
 */
object FlightModeCompiler {

    /** Generator name recorded in `ManagedItems` and `MixPlan.generator`. */
    const val GENERATOR: String = ManagedItems.GENERATOR_FLIGHT_MODES

    /** Longest transition / flap speed (mix slow time) the compilers accept, in ms. */
    const val MAX_SLOW_MS: Int = 30_000

    /** Lowest per-mode rate (dual-rate weight) in percent. */
    const val RATE_MIN: Int = 0

    /** Highest per-mode rate in percent; a mode with this rate needs no dual rate. */
    const val RATE_MAX: Int = 100

    /**
     * A switch position without a mode of its own selects the default mode (`modes[0]`), exactly
     * like `FlightModesConfig.modeIndexFor`: its offsets fill that point of the curve and its
     * rate gets a dual rate for that position too. Values out of range are clamped here and
     * reported by [validate].
     *
     * @param current the working config, used to validate channels and to read the mode switch's kind
     * @return the plan; never throws. Problems that can be detected without diffing are reported by [validate].
     */
    fun compile(config: FlightModesConfig, current: WorkingConfig): MixPlan {
        val switch = config.modeSwitch
        if (!config.isActive || switch == null) return MixPlan.empty(GENERATOR)
        val modes = config.modes.take(FLIGHT_MODE_MAX)
        val byPosition = SwitchPosition.entries.map { position -> modes.firstOrNull { it.position == position } ?: modes[0] }
        val slow = modes.maxOf { it.transitionMs }.coerceIn(0, MAX_SLOW_MS)

        val curves = LinkedHashMap<String, PlannedCurve>()
        val mixes = ArrayList<PlannedMix>()
        for (channel in modes.flatMap { it.offsets.keys }.toSortedSet()) {
            val offsets = byPosition.map { (it.offsets[channel] ?: 0).coerceIn(PERCENT_MIN, PERCENT_MAX) }
            if (offsets.all { it == 0 }) continue
            val key = offsetCurveKey(offsets)
            curves.getOrPut(key) {
                PlannedCurve(
                    key = key,
                    type = CurveType.CUSTOM,
                    points = SwitchPosition.byLogic.map { position -> CurvePoint(position.logic, offsets[position.ordinal]) },
                    comment = "Flight mode offsets (up / mid / down)",
                )
            }
            mixes += PlannedMix(
                channel = channel,
                source = switch,
                mode = MixMode.ADD,
                curve = CurveRef.Planned(key),
                slowUpMs = slow,
                slowDownMs = slow,
                comment = "Flight mode offset",
            )
        }

        val dualRates = ArrayList<PlannedDualRate>()
        for (channel in modes.flatMap { it.rates.keys }.toSortedSet()) {
            SwitchPosition.entries.forEachIndexed { i, position ->
                val mode = byPosition[i]
                val rate = (mode.rates[channel] ?: RATE_MAX).coerceIn(RATE_MIN, RATE_MAX)
                if (rate != RATE_MAX) {
                    dualRates += PlannedDualRate(channel, SwitchCondition.at(switch, position), rate, "Rate of ${modeLabel(mode, modes)}")
                }
            }
        }
        return MixPlan(
            generator = GENERATOR,
            mixes = mixes,
            curves = curves.values.toList(),
            dualRates = dualRates,
            description = "Flight modes on ${current.sourceName(switch)}",
        )
    }

    /**
     * Problems of [config] itself: no / invalid mode switch, two modes on the same position,
     * invalid channel indices, values out of range. Empty = compilable.
     *
     * Every entry is blocking and uses `PlanConflictKind.INVALID_SETTINGS`, `INVALID_CHANNEL` or
     * `INVALID_SOURCE`. `ConfigSession.compileFlightModes` appends them to the returned diff
     * (`PlanDiff.withConflicts`), so an invalid configuration can never be applied.
     */
    fun validate(config: FlightModesConfig, current: WorkingConfig): List<PlanConflict> {
        if (!config.enabled) return emptyList()
        val problems = ArrayList<PlanConflict>()
        val switch = config.modeSwitch
        when {
            switch == null ->
                problems += PlanConflict(PlanConflictKind.INVALID_SETTINGS, detail = "No flight-mode switch is selected")
            !switch.isValid || !switch.isSwitch ->
                problems += PlanConflict(
                    PlanConflictKind.INVALID_SOURCE,
                    source = switch,
                    detail = "The flight-mode switch must be one of the switches SA..SP",
                )
            current.source(switch)?.enabled != true ->
                problems += PlanConflict(
                    PlanConflictKind.INVALID_SOURCE,
                    source = switch,
                    detail = "Switch ${switch.defaultName} does not exist on this radio",
                )
        }
        if (config.modes.isEmpty()) {
            problems += PlanConflict(PlanConflictKind.INVALID_SETTINGS, detail = "No flight mode is defined")
        }
        if (config.modes.size > FLIGHT_MODE_MAX) {
            problems += PlanConflict(
                PlanConflictKind.INVALID_SETTINGS,
                detail = "${config.modes.size} flight modes are defined, at most $FLIGHT_MODE_MAX fit on one switch",
            )
        }
        for ((position, sharing) in config.modes.groupBy { it.position }) {
            if (sharing.size > 1) {
                problems += PlanConflict(
                    PlanConflictKind.INVALID_SETTINGS,
                    source = switch,
                    detail = "${sharing.size} flight modes use the switch position $position: " +
                        sharing.joinToString { modeLabel(it, config.modes) },
                )
            }
        }
        val badChannels = sortedSetOf<Int>()
        for (mode in config.modes) {
            val label = modeLabel(mode, config.modes)
            for ((channel, offset) in mode.offsets) {
                if (!isChannel(channel, current)) {
                    badChannels += channel
                } else if (offset !in PERCENT_MIN..PERCENT_MAX) {
                    problems += PlanConflict(
                        PlanConflictKind.INVALID_SETTINGS,
                        channel = channel,
                        detail = "Offset $offset of $label on CH${channel + 1} is outside $PERCENT_MIN..$PERCENT_MAX",
                    )
                }
            }
            for ((channel, rate) in mode.rates) {
                if (!isChannel(channel, current)) {
                    badChannels += channel
                } else if (rate !in RATE_MIN..RATE_MAX) {
                    problems += PlanConflict(
                        PlanConflictKind.INVALID_SETTINGS,
                        channel = channel,
                        detail = "Rate $rate of $label on CH${channel + 1} is outside $RATE_MIN..$RATE_MAX",
                    )
                }
            }
            if (mode.transitionMs !in 0..MAX_SLOW_MS) {
                problems += PlanConflict(
                    PlanConflictKind.INVALID_SETTINGS,
                    detail = "Transition time ${mode.transitionMs} ms of $label is outside 0..$MAX_SLOW_MS",
                )
            }
        }
        for (channel in badChannels) {
            problems += PlanConflict(
                PlanConflictKind.INVALID_CHANNEL,
                channel = channel,
                detail = "Flight modes use output channel ${channel + 1}, which does not exist",
            )
        }
        return problems
    }

    /** Key of the shared offset curve: channels with the same three offsets use one curve. */
    private fun offsetCurveKey(offsets: List<Int>): String = "fm:" + offsets.joinToString(":")

    private fun modeLabel(mode: FlightModeConfig, modes: List<FlightModeConfig>): String =
        mode.name.ifBlank { "mode ${modes.indexOf(mode)}" }
}

/** True if [channel] is an output channel of [current]. */
internal fun isChannel(channel: Int, current: WorkingConfig): Boolean =
    channel in 0 until CHANNEL_COUNT && current.channel(channel) != null

/** True if [source] exists and is enabled on the radio [current] was read from. */
internal fun isUsableSource(source: SourceId, current: WorkingConfig): Boolean =
    source.isValid && current.source(source)?.enabled == true

/**
 * Compiles the wing / flaps wizard settings into firmware items (SPEC section 7): aileron mixes
 * with differential, flaperons, flap positions (switch through a custom curve, or slider) with
 * speed and elevator compensation, butterfly / crow with an elevator compensation curve, V-tail
 * and elevon mixes.
 *
 * A disabled configuration compiles to an empty plan.
 *
 * ## Sign convention (what the generated lines assume)
 * Every line is an `ADD` line, so their order on a channel does not matter. On the wing surfaces
 * (ailerons, flaps, tips, elevons) a **positive channel value means "trailing edge up"**, with a
 * positive aileron stick meaning "roll right": the right aileron gets `+Ail`, the left one `−Ail`.
 * Flap deflections, camber and butterfly are therefore negative (down) on flaps and positive (up)
 * on ailerons. The servo direction itself is set on the Output tab (reverse) so that the surface
 * really moves that way. Elevator and rudder channels follow their sticks (`+Ele`, `+Rud`);
 * elevator compensation values are added in the direction of a positive elevator stick.
 *
 * ## What is generated
 * - Ailerons: `Ail` on the aileron channels (and on the tips of a 6-servo wing). Differential
 *   reduces the downward travel through the up / down weights of the line (no curve needed); a
 *   negative differential reduces the upward travel instead. A single aileron channel
 *   ([WingType.ONE_AILERON], `aileronLeft` or else `aileronRight`) gets `+Ail` without differential.
 * - Flaps on a switch: one custom 3-point curve with the deflection per position, used by the
 *   flap channels (weight 100) and, scaled by `aileronFollow`, by ailerons and tips (flaperons /
 *   full-span camber). Elevator compensation per position is a second 3-point curve on the
 *   elevator. Flaps on a slider need no curve: weight and offset map "retracted" to 0 and full
 *   travel to the deflection (rounded to whole percent, at most 1 % short at full travel).
 * - Butterfly: a shared "brake" curve (0 at no brake, with the dead band, 100 at full brake) on
 *   flaps (down), ailerons and tips (up), plus a compensation curve on the elevator; all gated
 *   by `ButterflyConfig.activeWhen`.
 * - Tail: [TailType.NORMAL] `Ele` → elevator, `Rud` → rudder, optional aileron → rudder;
 *   [TailType.V_TAIL] left (`channels.elevator`) = `Ele` + `Rud`, right (`channels.rudder`) =
 *   `Ele` − `Rud` with the V-tail weights. [WingType.ELEVON] left = `Ele` − `Ail`, right =
 *   `Ele` + `Ail` with the elevon weights. Elevator compensation goes to whatever carries the
 *   elevator function, scaled by its elevator weight.
 * - The throttle channel is never touched; it only takes part in the "assigned twice" check.
 */
object WingCompiler {

    /** Generator name recorded in `ManagedItems` and `MixPlan.generator`. */
    const val GENERATOR: String = ManagedItems.GENERATOR_WING

    /** Largest butterfly dead band in percent of the source travel. */
    const val MAX_DEADBAND: Int = 50

    /** Most elevator compensation points of the butterfly (the curve needs one more for the dead band). */
    const val MAX_COMPENSATION_POINTS: Int = CURVE_MAX_POINTS - 1

    /** Key of the planned flap-position curve (flaps on a switch). */
    const val CURVE_FLAP: String = "wing:flap"

    /** Key of the planned elevator-compensation curve of the flap positions. */
    const val CURVE_FLAP_ELEVATOR: String = "wing:flap.ele"

    /** Key of the planned butterfly brake curve. */
    const val CURVE_BUTTERFLY: String = "wing:bfly"

    /** Key of the planned butterfly elevator-compensation curve. */
    const val CURVE_BUTTERFLY_ELEVATOR: String = "wing:bfly.ele"

    /** @return the plan; never throws. See [validate] for configuration problems. */
    fun compile(config: WingConfig, current: WorkingConfig): MixPlan {
        if (!config.enabled) return MixPlan.empty(GENERATOR)
        return WingPlanner(config).build()
    }

    /**
     * Problems of [config] itself: required channels missing for the chosen wing / tail type, the
     * same channel assigned twice, invalid sources, values out of range. Empty = compilable.
     * Same conventions as [FlightModeCompiler.validate]; appended to the diff by
     * `ConfigSession.compileWing`.
     */
    fun validate(config: WingConfig, current: WorkingConfig): List<PlanConflict> {
        if (!config.enabled) return emptyList()
        val problems = ArrayList<PlanConflict>()
        val planner = WingPlanner(config)

        for (name in planner.missingRoles()) {
            problems += PlanConflict(PlanConflictKind.INVALID_SETTINGS, detail = "The channel of the $name is not set")
        }
        val roles = planner.assignedRoles()
        for ((name, channel) in roles) {
            if (!isChannel(channel, current)) {
                problems += PlanConflict(
                    PlanConflictKind.INVALID_CHANNEL,
                    channel = channel,
                    detail = "The $name uses output channel ${channel + 1}, which does not exist",
                )
            }
        }
        for ((channel, sharing) in roles.groupBy({ it.second }, { it.first })) {
            if (sharing.size > 1) {
                problems += PlanConflict(
                    PlanConflictKind.INVALID_SETTINGS,
                    channel = channel,
                    detail = "CH${channel + 1} is assigned twice: ${sharing.joinToString()}",
                )
            }
        }

        fun checkRange(value: Int, range: IntRange, what: String) {
            if (value !in range) {
                problems += PlanConflict(
                    PlanConflictKind.INVALID_SETTINGS,
                    detail = "$what is $value, allowed is ${range.first}..${range.last}",
                )
            }
        }

        fun checkSource(source: SourceId, what: String, switchWanted: Boolean?) {
            val problem = when {
                !isUsableSource(source, current) -> "$what ${source.defaultName} does not exist on this radio"
                source.isCounter -> "$what cannot be a counter"
                switchWanted == true && !source.isSwitch -> "$what must be a switch"
                switchWanted == false && source.isSwitch -> "$what must be a stick, knob or slider"
                else -> null
            }
            if (problem != null) problems += PlanConflict(PlanConflictKind.INVALID_SOURCE, source = source, detail = problem)
        }

        val percent = PERCENT_MIN..PERCENT_MAX
        checkRange(config.aileronDifferential, percent, "Aileron differential")
        checkRange(config.aileronToRudder, percent, "Aileron to rudder")
        if (config.tailType == TailType.V_TAIL) {
            checkRange(config.vTailElevatorWeight, percent, "V-tail elevator weight")
            checkRange(config.vTailRudderWeight, percent, "V-tail rudder weight")
        }
        if (config.wingType == WingType.ELEVON) {
            checkRange(config.elevonAileronWeight, percent, "Elevon aileron weight")
            checkRange(config.elevonElevatorWeight, percent, "Elevon elevator weight")
        }

        if (planner.usesFlaps) {
            checkRange(config.flaps.speedMs, 0..FlightModeCompiler.MAX_SLOW_MS, "Flap speed")
            checkRange(config.flaps.aileronFollow, percent, "Aileron follow")
            when (val control = config.flaps.control) {
                FlapControl.None -> Unit
                is FlapControl.OnSwitch -> {
                    checkSource(control.source, "The flap switch", switchWanted = true)
                    checkRange(control.deflectionUp, percent, "Flap deflection (up)")
                    checkRange(control.deflectionMid, percent, "Flap deflection (mid)")
                    checkRange(control.deflectionDown, percent, "Flap deflection (down)")
                    checkRange(control.elevatorUp, percent, "Flap elevator compensation (up)")
                    checkRange(control.elevatorMid, percent, "Flap elevator compensation (mid)")
                    checkRange(control.elevatorDown, percent, "Flap elevator compensation (down)")
                }
                is FlapControl.OnSlider -> {
                    checkSource(control.source, "The flap slider", switchWanted = false)
                    checkRange(control.deflectionMax, percent, "Flap deflection")
                    checkRange(control.elevatorMax, percent, "Flap elevator compensation")
                }
            }
        }

        if (planner.usesButterfly) {
            val butterfly = config.butterfly
            checkSource(butterfly.source, "The butterfly source", switchWanted = false)
            if (butterfly.activeWhen.isEnabled) checkSource(butterfly.activeWhen.source, "The butterfly condition source", switchWanted = null)
            checkRange(butterfly.flapDown, percent, "Butterfly flap deflection")
            checkRange(butterfly.aileronUp, percent, "Butterfly aileron reflex")
            checkRange(butterfly.tipUp, percent, "Butterfly tip deflection")
            checkRange(butterfly.deadband, 0..MAX_DEADBAND, "Butterfly dead band")
            checkRange(butterfly.elevatorCompensation.size, 0..MAX_COMPENSATION_POINTS, "Number of butterfly compensation points")
            butterfly.elevatorCompensation.forEachIndexed { i, value -> checkRange(value, percent, "Butterfly elevator compensation ${i + 1}") }
        }

        // Two functions that need the same (channel, source) line cannot both exist on the radio.
        for ((key, lines) in planner.build().mixes.groupBy { it.key }) {
            if (lines.size > 1) {
                problems += PlanConflict(
                    PlanConflictKind.INVALID_SETTINGS,
                    channel = key.channel,
                    source = key.sourceId,
                    detail = "CH${key.channel + 1} would need ${lines.size} lines from ${key.sourceId.defaultName} " +
                        "(${lines.joinToString { it.comment }}); choose different sources",
                )
            }
        }
        return problems
    }
}

/** Builds the wing plan; all the geometry of [WingCompiler] lives here. */
private class WingPlanner(private val config: WingConfig) {

    /** An aileron-like surface: [side] +1 = right (or the only one), −1 = left. */
    private class Surface(val channel: Int, val side: Int)

    /** A channel that carries the elevator function with [weight] percent. */
    private class ElevatorTarget(val channel: Int, val weight: Int)

    private val channels = config.channels
    private val mixes = ArrayList<PlannedMix>()
    private val curves = LinkedHashMap<String, PlannedCurve>()

    private val isElevon = config.wingType == WingType.ELEVON
    private val hasFlapChannels =
        config.wingType == WingType.TWO_AILERONS_TWO_FLAPS || config.wingType == WingType.TWO_AILERONS_FOUR_FLAPS
    private val hasTips = config.wingType == WingType.TWO_AILERONS_FOUR_FLAPS

    private val ailerons: List<Surface> = when (config.wingType) {
        WingType.NONE -> emptyList()
        WingType.ONE_AILERON -> listOfNotNull((channels.aileronLeft ?: channels.aileronRight)?.let { Surface(it, 1) })
        else -> listOfNotNull(channels.aileronLeft?.let { Surface(it, -1) }, channels.aileronRight?.let { Surface(it, 1) })
    }
    private val tips: List<Surface> =
        if (hasTips) listOfNotNull(channels.tipLeft?.let { Surface(it, -1) }, channels.tipRight?.let { Surface(it, 1) }) else emptyList()
    private val flaps: List<Int> = if (hasFlapChannels) listOfNotNull(channels.flapLeft, channels.flapRight) else emptyList()

    private val elevatorTargets: List<ElevatorTarget> = buildList {
        when (config.tailType) {
            TailType.NONE -> Unit
            TailType.NORMAL -> channels.elevator?.let { add(ElevatorTarget(it, 100)) }
            TailType.V_TAIL -> {
                val weight = config.vTailElevatorWeight.clampPercent()
                channels.elevator?.let { add(ElevatorTarget(it, weight)) }
                channels.rudder?.let { add(ElevatorTarget(it, weight)) }
            }
        }
        if (isElevon) {
            val weight = config.elevonElevatorWeight.clampPercent()
            channels.aileronLeft?.let { add(ElevatorTarget(it, weight)) }
            channels.aileronRight?.let { add(ElevatorTarget(it, weight)) }
        }
    }

    private val hasWing = ailerons.isNotEmpty() || flaps.isNotEmpty() || tips.isNotEmpty()

    /** True if the flap settings produce lines (and are therefore validated). */
    val usesFlaps: Boolean get() = hasWing && config.flaps.control != FlapControl.None

    /** True if the butterfly settings produce lines. */
    val usesButterfly: Boolean get() = hasWing && config.butterfly.enabled

    /** Names of the surfaces the chosen wing / tail type needs but that have no channel. */
    fun missingRoles(): List<String> = buildList {
        when (config.wingType) {
            WingType.NONE -> Unit
            WingType.ONE_AILERON -> if (channels.aileronLeft == null && channels.aileronRight == null) add("aileron")
            WingType.ELEVON -> {
                if (channels.aileronLeft == null) add("left elevon")
                if (channels.aileronRight == null) add("right elevon")
            }
            else -> {
                if (channels.aileronLeft == null) add("left aileron")
                if (channels.aileronRight == null) add("right aileron")
                if (hasFlapChannels && channels.flapLeft == null && channels.flapRight == null) add("flaps")
                if (hasTips && channels.tipLeft == null && channels.tipRight == null) add("tip surfaces")
            }
        }
        when (config.tailType) {
            TailType.NONE -> Unit
            TailType.NORMAL -> if (channels.elevator == null && channels.rudder == null) add("elevator or rudder")
            TailType.V_TAIL -> {
                if (channels.elevator == null) add("left V-tail surface")
                if (channels.rudder == null) add("right V-tail surface")
            }
        }
    }

    /** Every surface in use with its channel, for the "exists" and "assigned twice" checks. */
    fun assignedRoles(): List<Pair<String, Int>> = buildList {
        val elevon = if (isElevon) "elevon" else "aileron"
        when (config.wingType) {
            WingType.NONE -> Unit
            WingType.ONE_AILERON -> (channels.aileronLeft ?: channels.aileronRight)?.let { add("aileron" to it) }
            else -> {
                channels.aileronLeft?.let { add("left $elevon" to it) }
                channels.aileronRight?.let { add("right $elevon" to it) }
            }
        }
        if (hasFlapChannels) {
            channels.flapLeft?.let { add("left flap" to it) }
            channels.flapRight?.let { add("right flap" to it) }
        }
        if (hasTips) {
            channels.tipLeft?.let { add("left tip" to it) }
            channels.tipRight?.let { add("right tip" to it) }
        }
        when (config.tailType) {
            TailType.NONE -> Unit
            TailType.NORMAL -> {
                channels.elevator?.let { add("elevator" to it) }
                channels.rudder?.let { add("rudder" to it) }
            }
            TailType.V_TAIL -> {
                channels.elevator?.let { add("left V-tail surface" to it) }
                channels.rudder?.let { add("right V-tail surface" to it) }
            }
        }
        if (isNotEmpty()) channels.throttle?.let { add("throttle" to it) }
    }

    fun build(): MixPlan {
        mixes.clear()
        curves.clear()
        aileronLines()
        tailLines()
        flapLines()
        butterflyLines()
        return MixPlan(
            generator = WingCompiler.GENERATOR,
            mixes = mixes.toList(),
            curves = curves.values.toList(),
            description = "Wing ${config.wingType}, tail ${config.tailType}",
        )
    }

    // ---- ailerons, tail --------------------------------------------------------------------

    private fun aileronLines() {
        val differential = if (config.wingType == WingType.ONE_AILERON) 0 else config.aileronDifferential.clampPercent()
        val weight = if (isElevon) config.elevonAileronWeight.clampPercent() else 100
        for (surface in ailerons) aileronLine(surface, weight, differential, if (isElevon) "Elevon (aileron)" else "Aileron")
        for (surface in tips) aileronLine(surface, 100, differential, "Tip (aileron)")
    }

    /** `Ail` on one surface; the differential shortens the travel in one direction. */
    private fun aileronLine(surface: Surface, weight: Int, differential: Int, comment: String) {
        val up = scale(weight, 100 + min(differential, 0))
        val down = scale(weight, 100 - max(differential, 0))
        // A positive stick moves the right surface up and the left one down.
        val (weightUp, weightDown) = if (surface.side > 0) up to down else -down to -up
        mix(surface.channel, SourceId.AIL, weightUp, weightDown, comment = comment)
    }

    private fun tailLines() {
        val combi = config.aileronToRudder.clampPercent()
        when (config.tailType) {
            TailType.NONE -> Unit
            TailType.NORMAL -> {
                channels.elevator?.let { mix(it, SourceId.ELE, 100, 100, comment = "Elevator") }
                channels.rudder?.let { rudder ->
                    mix(rudder, SourceId.RUD, 100, 100, comment = "Rudder")
                    if (combi != 0) mix(rudder, SourceId.AIL, combi, combi, comment = "Aileron to rudder")
                }
            }
            TailType.V_TAIL -> {
                val elevator = config.vTailElevatorWeight.clampPercent()
                val rudder = config.vTailRudderWeight.clampPercent()
                val combiWeight = scale(combi, rudder)
                channels.elevator?.let { left ->
                    mix(left, SourceId.ELE, elevator, elevator, comment = "V-tail left (elevator)")
                    mix(left, SourceId.RUD, rudder, rudder, comment = "V-tail left (rudder)")
                    if (combiWeight != 0) mix(left, SourceId.AIL, combiWeight, combiWeight, comment = "Aileron to rudder")
                }
                channels.rudder?.let { right ->
                    mix(right, SourceId.ELE, elevator, elevator, comment = "V-tail right (elevator)")
                    mix(right, SourceId.RUD, -rudder, -rudder, comment = "V-tail right (rudder)")
                    if (combiWeight != 0) mix(right, SourceId.AIL, -combiWeight, -combiWeight, comment = "Aileron to rudder")
                }
            }
        }
        if (isElevon) {
            val weight = config.elevonElevatorWeight.clampPercent()
            for (surface in ailerons) mix(surface.channel, SourceId.ELE, weight, weight, comment = "Elevon (elevator)")
        }
    }

    // ---- flaps -------------------------------------------------------------------------------

    private fun flapLines() {
        if (!usesFlaps) return
        val speed = config.flaps.speedMs.coerceIn(0, FlightModeCompiler.MAX_SLOW_MS)
        val follow = config.flaps.aileronFollow.clampPercent()
        when (val control = config.flaps.control) {
            FlapControl.None -> Unit
            is FlapControl.OnSwitch -> {
                // Down is negative on the wing surfaces.
                val deflection = listOf(control.deflectionUp, control.deflectionMid, control.deflectionDown).map { -it.clampPercent() }
                if (deflection.any { it != 0 }) {
                    val curve = positionCurve(WingCompiler.CURVE_FLAP, deflection, "Flap deflection (up / mid / down)")
                    for (flap in flaps) mix(flap, control.source, 100, 100, curve = curve, slowMs = speed, comment = "Flap")
                    if (follow != 0) {
                        for (surface in ailerons + tips) {
                            mix(surface.channel, control.source, follow, follow, curve = curve, slowMs = speed, comment = "Camber (flap follow)")
                        }
                    }
                }
                val compensation = listOf(control.elevatorUp, control.elevatorMid, control.elevatorDown).map { it.clampPercent() }
                if (compensation.any { it != 0 }) {
                    val curve = positionCurve(WingCompiler.CURVE_FLAP_ELEVATOR, compensation, "Flap elevator compensation (up / mid / down)")
                    for (target in elevatorTargets) {
                        mix(target.channel, control.source, target.weight, target.weight, curve = curve, slowMs = speed, comment = "Flap compensation")
                    }
                }
            }
            is FlapControl.OnSlider -> {
                val deflection = -control.deflectionMax.clampPercent()
                for (flap in flaps) proportional(flap, control.source, deflection, control.reversed, speed, "Flap")
                for (surface in ailerons + tips) {
                    proportional(surface.channel, control.source, scale(deflection, follow), control.reversed, speed, "Camber (flap follow)")
                }
                for (target in elevatorTargets) {
                    val full = scale(control.elevatorMax.clampPercent(), target.weight)
                    proportional(target.channel, control.source, full, control.reversed, speed, "Flap compensation")
                }
            }
        }
    }

    /** A 3-point curve over the switch positions; [values] are in the order up / mid / down, the points ascend in x (down first). */
    private fun positionCurve(key: String, values: List<Int>, comment: String): CurveRef {
        curves[key] = PlannedCurve(
            key = key,
            type = CurveType.CUSTOM,
            points = SwitchPosition.byLogic.map { position -> CurvePoint(position.logic, values[position.ordinal]) },
            comment = comment,
        )
        return CurveRef.Planned(key)
    }

    /**
     * A line without curve that is 0 at the rest end of [source] (−100, or +100 when [reversed])
     * and [full] at the other end.
     */
    private fun proportional(channel: Int, source: SourceId, full: Int, reversed: Boolean, slowMs: Int, comment: String) {
        val half = full / 2 // towards zero: never more than asked for
        if (half == 0) return
        val weight = if (reversed) -half else half
        mix(channel, source, weight, weight, offset = half, slowMs = slowMs, comment = comment)
    }

    // ---- butterfly ---------------------------------------------------------------------------

    private fun butterflyLines() {
        if (!usesButterfly) return
        val butterfly = config.butterfly
        val deadband = butterfly.deadband.coerceIn(0, WingCompiler.MAX_DEADBAND)
        // Source value at which the brake starts, seen from the "no brake" end at +100.
        val start = 100 - 2 * deadband
        val gate = butterfly.activeWhen

        val flapDown = -butterfly.flapDown.clampPercent()
        val aileronUp = butterfly.aileronUp.clampPercent()
        val tipUp = butterfly.tipUp.clampPercent()
        val wanted = (flaps.isNotEmpty() && flapDown != 0) || (ailerons.isNotEmpty() && aileronUp != 0) || (tips.isNotEmpty() && tipUp != 0)
        if (wanted) {
            // Brake amount 0..100 over the source travel, drawn for "full brake at −100".
            val shape = buildList {
                add(CurvePoint(-100, 100))
                add(CurvePoint(start, 0))
                if (start < 100) add(CurvePoint(100, 0))
            }
            curves[WingCompiler.CURVE_BUTTERFLY] = PlannedCurve(
                key = WingCompiler.CURVE_BUTTERFLY,
                type = CurveType.CUSTOM,
                points = orient(shape, butterfly.reversed),
                comment = "Butterfly brake amount",
            )
            val curve = CurveRef.Planned(WingCompiler.CURVE_BUTTERFLY)
            if (flapDown != 0) {
                for (flap in flaps) mix(flap, butterfly.source, flapDown, flapDown, curve = curve, switch = gate, comment = "Butterfly (flap)")
            }
            if (aileronUp != 0) {
                for (surface in ailerons) {
                    mix(surface.channel, butterfly.source, aileronUp, aileronUp, curve = curve, switch = gate, comment = "Butterfly (aileron)")
                }
            }
            if (tipUp != 0) {
                for (surface in tips) {
                    mix(surface.channel, butterfly.source, tipUp, tipUp, curve = curve, switch = gate, comment = "Butterfly (tip)")
                }
            }
        }

        val compensation = butterfly.elevatorCompensation.take(WingCompiler.MAX_COMPENSATION_POINTS).map { it.clampPercent() }
            .let { if (it.size == 1) listOf(0, it[0]) else it }
        if (compensation.any { it != 0 } && elevatorTargets.isNotEmpty()) {
            val last = compensation.size - 1
            val shape = ArrayList<CurvePoint>()
            // From full brake (x = −100) to no brake (x = start); values are listed the other way round.
            for (i in last downTo 0) {
                val x = (start - (start + 100.0) * i / last).roundToInt()
                if (shape.isEmpty() || x > shape.last().x) shape += CurvePoint(x, compensation[i])
            }
            if (shape.last().x < 100) shape += CurvePoint(100, compensation[0])
            curves[WingCompiler.CURVE_BUTTERFLY_ELEVATOR] = PlannedCurve(
                key = WingCompiler.CURVE_BUTTERFLY_ELEVATOR,
                type = CurveType.CUSTOM,
                points = orient(shape, butterfly.reversed),
                comment = "Butterfly elevator compensation",
            )
            val curve = CurveRef.Planned(WingCompiler.CURVE_BUTTERFLY_ELEVATOR)
            for (target in elevatorTargets) {
                mix(target.channel, butterfly.source, target.weight, target.weight, curve = curve, switch = gate, comment = "Butterfly compensation")
            }
        }
    }

    /** Mirrors a curve drawn for "full brake at −100" when the source works the other way round. */
    private fun orient(points: List<CurvePoint>, reversed: Boolean): List<CurvePoint> =
        if (reversed) points.asReversed().map { CurvePoint(-it.x, it.y) } else points

    // ---- helpers -----------------------------------------------------------------------------

    private fun mix(
        channel: Int,
        source: SourceId,
        weightUp: Int,
        weightDown: Int,
        offset: Int = 0,
        curve: CurveRef = CurveRef.None,
        switch: SwitchCondition = SwitchCondition.NONE,
        slowMs: Int = 0,
        comment: String = "",
    ) {
        mixes += PlannedMix(
            channel = channel,
            source = source,
            mode = MixMode.ADD,
            weightUp = weightUp,
            weightDown = weightDown,
            offset = offset,
            curve = curve,
            switch = switch,
            slowUpMs = slowMs,
            slowDownMs = slowMs,
            comment = comment,
        )
    }

    private fun Int.clampPercent(): Int = coerceIn(PERCENT_MIN, PERCENT_MAX)

    /** [value] × [percent] / 100, rounded. */
    private fun scale(value: Int, percent: Int): Int = (value * percent / 100.0).roundToInt()
}

/**
 * Turns a [MixPlan] into concrete operations against the current configuration (a dry run).
 *
 * Rules:
 * - Managed items of the plan's generator that the plan no longer needs are removed (mixes
 *   deleted, curves reset to `NONE` unless anything that remains — a user mix, another
 *   generator's mix, an output channel — still references them, dual-rate slots disabled).
 *   Items of other generators and user-made items are never touched — unless
 *   `plan.replaceAllMixes`, which removes every mix line not in the plan, or `plan.resetConfig`,
 *   which additionally neutralises curves, dual rates, channels and timers as described at
 *   `MixPlan.resetConfig` (the recorded managed items are then ignored and `managedAfter` holds
 *   only what this plan created).
 * - A planned mix whose `(channel, source)` exists as a managed mix of the same generator is
 *   updated in place; if it exists as a user-made mix or as another generator's managed mix that
 *   is a blocking `MIX_KEY_IN_USE` conflict. (This is why a model template must leave everything
 *   the wing wizard and the flight-mode compiler can express to *their* generators, see
 *   `ModelBlueprint`.)
 * - Planned curves reuse the generator's managed curve indices first, then an existing managed
 *   curve of identical shape (`CurveConfig.sameShapeAs`; recorded for this generator as well),
 *   then the highest free index (unused = type `NONE` and not referenced). None left = blocking
 *   `NO_FREE_CURVE`.
 * - Planned dual rates reuse the generator's managed slots first, then free slots — free means
 *   **disabled** (`CompareOp.DISABLED`), lowest slot first; `Radio.findFreeDualRateSlot` is never
 *   used. None left = blocking `NO_FREE_DUAL_RATE_SLOT`.
 * - Hand edits: a managed item of the plan's generator whose recorded fingerprint differs from
 *   the item's current one (`ManagedItems.isModified`) produces a `MANAGED_ITEM_MODIFIED` warning
 *   and is overwritten with the planned values — editing a managed item keeps it managed (see
 *   `ManagedItems`). A record without fingerprint never warns. Recorded items that vanished
 *   (mix key gone, curve `NONE`, slot disabled) produce a `MANAGED_ITEM_MISSING` warning and are
 *   recreated.
 * - `managedAfter` carries the fingerprints of the items as planned
 *   (`MixLine.managedFingerprint()` etc. of the `after` values); `ConfigSession.applyPlan`
 *   re-stamps them from the configuration read back after applying.
 * - Operations that would not change anything are omitted, so diffing an already applied plan
 *   yields `isNoOp`.
 * - `ops` are ordered: mix removals, curves, mix adds / updates, dual rates, channels, timers,
 *   counters.
 * - `AddMix.mix`, `UpdateMix.after`, `SetChannel.after` and `SetTimer.after` only need correct
 *   *writable* fields: the `Radio` setters never write the read-only ones.
 */
object PlanDiffer {

    /**
     * @param managed all managed items currently recorded in the model extras (all generators)
     * @return the diff; `managedAfter` is the full replacement for the extras' managed items
     */
    fun diff(plan: MixPlan, current: WorkingConfig, managed: ManagedItems): PlanDiff =
        PlanDiffEngine(plan, current, managed).run()

    /**
     * The configuration as it will look after the operations of [diff] were executed on
     * [current] — a prediction for previews (feed it to [MixerMath]) and tests; nothing is sent
     * to the radio. Added mixes are appended to their channel; `mixIndex` / `mixCount` are
     * renumbered. Conflicts are ignored: the operations are applied as they are.
     */
    fun preview(diff: PlanDiff, current: WorkingConfig): WorkingConfig = PlanPreview.apply(diff, current)
}
