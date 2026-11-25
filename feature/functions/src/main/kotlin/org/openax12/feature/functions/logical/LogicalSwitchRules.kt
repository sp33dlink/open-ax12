package org.openax12.feature.functions.logical

import org.openax12.core.model.LogicalFunction
import org.openax12.core.model.LogicalSwitch
import org.openax12.core.model.SensorId
import org.openax12.core.model.SourceId
import org.openax12.core.model.SwitchPosition
import org.openax12.core.model.Trigger
import org.openax12.feature.functions.common.PickerData
import org.openax12.feature.functions.common.TriggerProblem
import org.openax12.feature.functions.common.triggerProblem

/** What is wrong with a logical switch as edited. Blocking problems disable "Done". */
internal enum class LogicalProblem(val blocking: Boolean) {
    /** The function needs operand `a` and none is chosen. */
    MISSING_A(true),

    /** The function needs operand `b` and none is chosen. */
    MISSING_B(true),

    /** The function needs condition 1 and none is set. */
    MISSING_S1(true),

    /** The function needs condition 2 and none is set. */
    MISSING_S2(true),

    /** A condition (or the AND-condition) refers to the switch itself. */
    SELF_REFERENCE(true),

    /** A timer function with both times zero would flicker every tick. */
    TIMER_ZERO(false),

    /** An operand or condition reads a sensor the model does not know yet. */
    UNKNOWN_SENSOR(false),
}

/** Pure rules of the logical-switch editor. */
internal object LogicalSwitchRules {

    /** Functions that need the second condition `s2`. */
    private val NEEDS_S2 = setOf(LogicalFunction.AND, LogicalFunction.OR, LogicalFunction.XOR, LogicalFunction.STICKY)

    /** True if [function] uses the second condition. */
    fun usesS2(function: LogicalFunction): Boolean = function in NEEDS_S2

    /**
     * Changes the function of [ls] and fills the operands the new function needs and that are
     * still empty (operand a: the throttle stick, b: the elevator stick, conditions: the first
     * switch down / up, times: 1 s on / 1 s off for the timer function). Operands the user
     * already chose are never replaced, so switching between related functions keeps them.
     */
    fun withFunction(ls: LogicalSwitch, function: LogicalFunction, data: PickerData): LogicalSwitch {
        var result = ls.copy(function = function)
        if (function.usesA && result.a == null) result = result.copy(a = SensorId.input(SourceId.THR))
        if (function.usesB && result.b == null) result = result.copy(b = SensorId.input(SourceId.ELE))
        val switch = data.switches.firstOrNull()?.id ?: SourceId.SA
        if (function.usesConditions && result.s1 == null) result = result.copy(s1 = Trigger.SwitchAt(switch, SwitchPosition.DOWN))
        if (function in NEEDS_S2 && result.s2 == null) result = result.copy(s2 = Trigger.SwitchAt(switch, SwitchPosition.UP))
        if (function == LogicalFunction.TIMER && result.t1Sec == 0.0 && result.t2Sec == 0.0) result = result.copy(t1Sec = 1.0, t2Sec = 1.0)
        return result
    }

    /** The problems of [ls], blocking ones first. Unused switches have none. */
    fun problems(ls: LogicalSwitch, data: PickerData): List<LogicalProblem> {
        if (!ls.isUsed) return emptyList()
        val found = LinkedHashSet<LogicalProblem>()
        val function = ls.function
        if (function.usesA && ls.a == null) found += LogicalProblem.MISSING_A
        if (function.usesB && ls.b == null) found += LogicalProblem.MISSING_B
        if (function.usesConditions && ls.s1 == null) found += LogicalProblem.MISSING_S1
        if (function in NEEDS_S2 && ls.s2 == null) found += LogicalProblem.MISSING_S2
        val conditions = listOfNotNull(
            ls.s1.takeIf { function.usesConditions },
            ls.s2.takeIf { function in NEEDS_S2 },
            ls.andCondition,
        )
        if (conditions.any { triggerProblem(it, data, ls.index) == TriggerProblem.SELF_REFERENCE }) found += LogicalProblem.SELF_REFERENCE
        if (function == LogicalFunction.TIMER && ls.t1Sec <= 0.0 && ls.t2Sec <= 0.0) found += LogicalProblem.TIMER_ZERO
        val unknownOperand = listOfNotNull(ls.a.takeIf { function.usesA }, ls.b.takeIf { function.usesB })
            .any { data.sensor(it) == null }
        val unknownTrigger = conditions.any { triggerProblem(it, data, ls.index) == TriggerProblem.UNKNOWN_SENSOR }
        if (unknownOperand || unknownTrigger) found += LogicalProblem.UNKNOWN_SENSOR
        return found.sortedBy { !it.blocking }
    }

    /** True if [problems] contains one that forbids saving. */
    fun hasBlocking(problems: List<LogicalProblem>): Boolean = problems.any { it.blocking }

    /**
     * The 32 slots of the list: the configured switches at their index, an unused switch
     * everywhere else. [switches] may be sparse and unordered.
     */
    fun slots(switches: List<LogicalSwitch>, count: Int): List<LogicalSwitch> {
        val byIndex = switches.associateBy { it.index }
        return List(count) { byIndex[it] ?: LogicalSwitch(it) }
    }
}
