package org.openax12.feature.functions.common

import org.openax12.core.model.CompareOp
import org.openax12.core.model.LinkEvent
import org.openax12.core.model.SensorId
import org.openax12.core.model.Sensors
import org.openax12.core.model.SourceId
import org.openax12.core.model.SwitchCondition
import org.openax12.core.model.SwitchPosition
import org.openax12.core.model.TimerEvent
import org.openax12.core.model.Trigger

/** The ten shapes a [Trigger] can have, as offered by the trigger editor's first dropdown. */
internal enum class TriggerKind {
    SWITCH, INPUT, LOGICAL, TELEMETRY, LINK, TIMER, FLIGHT_MODE, ALWAYS, PERIODIC, NEVER;

    companion object {
        /** Kinds that make sense as the condition of a logical switch (no periodic pulses). */
        val CONDITIONS: List<TriggerKind> = entries - PERIODIC
    }
}

/** The [TriggerKind] of this trigger. */
internal fun Trigger.kind(): TriggerKind = when (this) {
    is Trigger.SwitchAt -> TriggerKind.SWITCH
    is Trigger.Input -> TriggerKind.INPUT
    is Trigger.Logical -> TriggerKind.LOGICAL
    is Trigger.Telemetry -> TriggerKind.TELEMETRY
    is Trigger.Link -> TriggerKind.LINK
    is Trigger.Timer -> TriggerKind.TIMER
    is Trigger.FlightModeIs -> TriggerKind.FLIGHT_MODE
    Trigger.Always -> TriggerKind.ALWAYS
    is Trigger.Periodic -> TriggerKind.PERIODIC
    Trigger.Never -> TriggerKind.NEVER
}

/**
 * A usable trigger of [kind] with sensible starting values taken from what the radio has: the
 * first switch, the first stick / knob, the battery voltage sensor, the first timer.
 */
internal fun defaultTrigger(kind: TriggerKind, data: PickerData): Trigger = when (kind) {
    TriggerKind.SWITCH -> Trigger.SwitchAt(data.switches.firstOrNull()?.id ?: SourceId.SA, SwitchPosition.DOWN)
    TriggerKind.INPUT -> Trigger.Input(
        SwitchCondition(data.analogSources.firstOrNull()?.id ?: SourceId.THR, CompareOp.GREATER, 0),
    )
    TriggerKind.LOGICAL -> Trigger.Logical(0)
    TriggerKind.TELEMETRY -> Trigger.Telemetry(
        sensor = data.sensors.firstOrNull { it.group == SensorGroup.TELEMETRY }?.id ?: Sensors.BATTERY_VOLTAGE,
        op = CompareOp.LESS,
    )
    TriggerKind.LINK -> Trigger.Link(LinkEvent.TELEMETRY_LOST)
    TriggerKind.TIMER -> Trigger.Timer(0, TimerEvent.EXPIRED)
    TriggerKind.FLIGHT_MODE -> Trigger.FlightModeIs(Trigger.ANY_MODE)
    TriggerKind.ALWAYS -> Trigger.Always
    TriggerKind.PERIODIC -> Trigger.Periodic(60)
    TriggerKind.NEVER -> Trigger.Never
}

/** What is wrong with a trigger as the user configured it. */
internal enum class TriggerProblem {
    /** The trigger is "never": the item does nothing. */
    NEVER,

    /** A logical switch's condition refers to the switch itself. */
    SELF_REFERENCE,

    /** A timer event that needs a number of seconds has none. */
    NO_SECONDS,

    /** The trigger reads a sensor that this model does not know. */
    UNKNOWN_SENSOR,

    /** The trigger refers to a flight mode that does not exist. */
    UNKNOWN_MODE,
}

/**
 * The first problem of [trigger], or null. [selfLogical] is the 0-based index of the logical
 * switch the trigger belongs to (a switch must not depend on itself); [data] is used to check
 * sensors and flight modes.
 */
internal fun triggerProblem(trigger: Trigger, data: PickerData, selfLogical: Int? = null): TriggerProblem? = when (trigger) {
    Trigger.Never -> TriggerProblem.NEVER
    is Trigger.Logical -> if (selfLogical != null && trigger.index == selfLogical) TriggerProblem.SELF_REFERENCE else null
    is Trigger.Timer ->
        if ((trigger.event == TimerEvent.REMAINING || trigger.event == TimerEvent.EVERY) && trigger.seconds <= 0) {
            TriggerProblem.NO_SECONDS
        } else {
            null
        }
    is Trigger.Telemetry -> if (data.sensor(trigger.sensor) == null) TriggerProblem.UNKNOWN_SENSOR else null
    is Trigger.FlightModeIs ->
        if (trigger.mode != Trigger.ANY_MODE && data.flightModes.isNotEmpty() && trigger.mode !in data.flightModes.indices) {
            TriggerProblem.UNKNOWN_MODE
        } else {
            null
        }
    else -> null
}

/** True if [sensor] is a sensor id the pickers know (used to validate logical-switch operands). */
internal fun isKnownSensor(sensor: SensorId?, data: PickerData): Boolean = sensor != null && data.sensor(sensor) != null
