package org.openax12.feature.functions.wing

import org.openax12.core.model.ButterflyConfig
import org.openax12.core.model.CHANNEL_COUNT
import org.openax12.core.model.FlapControl
import org.openax12.core.model.SourceId
import org.openax12.core.model.TailType
import org.openax12.core.model.WingChannels
import org.openax12.core.model.WingConfig
import org.openax12.core.model.WingType

/** A surface of the wing wizard that needs an output channel. */
internal enum class WingRole {
    AILERON_LEFT, AILERON_RIGHT, FLAP_LEFT, FLAP_RIGHT, TIP_LEFT, TIP_RIGHT, ELEVATOR, RUDDER, THROTTLE;

    /** The channel assigned to this role in [channels], or null. */
    fun get(channels: WingChannels): Int? = when (this) {
        AILERON_LEFT -> channels.aileronLeft
        AILERON_RIGHT -> channels.aileronRight
        FLAP_LEFT -> channels.flapLeft
        FLAP_RIGHT -> channels.flapRight
        TIP_LEFT -> channels.tipLeft
        TIP_RIGHT -> channels.tipRight
        ELEVATOR -> channels.elevator
        RUDDER -> channels.rudder
        THROTTLE -> channels.throttle
    }

    /** Copy of [channels] with this role set to [channel]. */
    fun with(channels: WingChannels, channel: Int?): WingChannels = when (this) {
        AILERON_LEFT -> channels.copy(aileronLeft = channel)
        AILERON_RIGHT -> channels.copy(aileronRight = channel)
        FLAP_LEFT -> channels.copy(flapLeft = channel)
        FLAP_RIGHT -> channels.copy(flapRight = channel)
        TIP_LEFT -> channels.copy(tipLeft = channel)
        TIP_RIGHT -> channels.copy(tipRight = channel)
        ELEVATOR -> channels.copy(elevator = channel)
        RUDDER -> channels.copy(rudder = channel)
        THROTTLE -> channels.copy(throttle = channel)
    }
}

/** How the flaps are commanded, without the parameters of [FlapControl]. */
internal enum class FlapKind { NONE, SWITCH, SLIDER }

/** Pure edits of a [WingConfig] draft. */
internal object WingEdits {

    /** The roles the wizard needs a channel for with this layout (throttle is optional and not listed). */
    fun requiredRoles(config: WingConfig): List<WingRole> = buildList {
        when (config.wingType) {
            WingType.NONE -> Unit
            WingType.ONE_AILERON -> add(WingRole.AILERON_LEFT)
            WingType.TWO_AILERONS, WingType.ELEVON -> {
                add(WingRole.AILERON_LEFT)
                add(WingRole.AILERON_RIGHT)
            }
            WingType.TWO_AILERONS_TWO_FLAPS -> addAll(listOf(WingRole.AILERON_LEFT, WingRole.AILERON_RIGHT, WingRole.FLAP_LEFT, WingRole.FLAP_RIGHT))
            WingType.TWO_AILERONS_FOUR_FLAPS -> addAll(
                listOf(
                    WingRole.AILERON_LEFT, WingRole.AILERON_RIGHT, WingRole.FLAP_LEFT, WingRole.FLAP_RIGHT,
                    WingRole.TIP_LEFT, WingRole.TIP_RIGHT,
                ),
            )
        }
        if (config.tailType != TailType.NONE) {
            add(WingRole.ELEVATOR)
            add(WingRole.RUDDER)
        }
    }

    /** True if the layout has servos of its own for the flaps. */
    fun hasFlapServos(config: WingConfig): Boolean =
        config.wingType == WingType.TWO_AILERONS_TWO_FLAPS || config.wingType == WingType.TWO_AILERONS_FOUR_FLAPS

    /** True if the layout has tip / inner surfaces (6-servo wing). */
    fun hasTips(config: WingConfig): Boolean = config.wingType == WingType.TWO_AILERONS_FOUR_FLAPS

    /** True if there are two ailerons the differential applies to. */
    fun hasTwoAilerons(config: WingConfig): Boolean = config.wingType in setOf(
        WingType.TWO_AILERONS, WingType.TWO_AILERONS_TWO_FLAPS, WingType.TWO_AILERONS_FOUR_FLAPS,
    )

    /**
     * Gives every required role without a channel the lowest output channel that no role uses
     * yet. Existing assignments are kept; channels beyond CH16 are never handed out.
     */
    fun autoAssign(config: WingConfig, channelCount: Int = CHANNEL_COUNT): WingConfig {
        var channels = config.channels
        val used = WingRole.entries.mapNotNull { it.get(channels) }.toMutableSet()
        for (role in requiredRoles(config)) {
            if (role.get(channels) != null) continue
            val free = (0 until channelCount).firstOrNull { it !in used } ?: break
            used += free
            channels = role.with(channels, free)
        }
        return config.copy(channels = channels)
    }

    /** The [FlapKind] of the draft. */
    fun flapKind(config: WingConfig): FlapKind = when (config.flaps.control) {
        FlapControl.None -> FlapKind.NONE
        is FlapControl.OnSwitch -> FlapKind.SWITCH
        is FlapControl.OnSlider -> FlapKind.SLIDER
    }

    /**
     * Switches the flap control to [kind] with starting values: a switch takes [switch] and
     * the positions 0 / 50 / 100 %, a slider takes [slider] and 100 % at full travel.
     */
    fun setFlapKind(config: WingConfig, kind: FlapKind, switch: SourceId, slider: SourceId): WingConfig {
        if (flapKind(config) == kind) return config
        val control = when (kind) {
            FlapKind.NONE -> FlapControl.None
            FlapKind.SWITCH -> FlapControl.OnSwitch(switch)
            FlapKind.SLIDER -> FlapControl.OnSlider(slider)
        }
        return config.copy(flaps = config.flaps.copy(control = control))
    }

    /** The five butterfly compensation points (0 / 25 / 50 / 75 / 100 % brake) the UI edits. */
    const val COMPENSATION_POINTS: Int = 5

    /** Compensation list of exactly [COMPENSATION_POINTS] entries (padded with 0), or empty when none is set. */
    fun compensationPoints(butterfly: ButterflyConfig): List<Int> =
        if (butterfly.elevatorCompensation.isEmpty()) {
            emptyList()
        } else {
            List(COMPENSATION_POINTS) { butterfly.elevatorCompensation.getOrElse(it) { 0 } }
        }

    /** Sets point [index] of the compensation curve, creating the five-point list when it was empty. */
    fun setCompensation(butterfly: ButterflyConfig, index: Int, value: Int): ButterflyConfig {
        if (index !in 0 until COMPENSATION_POINTS) return butterfly
        val points = compensationPoints(butterfly).ifEmpty { List(COMPENSATION_POINTS) { 0 } }.toMutableList()
        points[index] = value.coerceIn(-100, 100)
        return butterfly.copy(elevatorCompensation = points)
    }
}
