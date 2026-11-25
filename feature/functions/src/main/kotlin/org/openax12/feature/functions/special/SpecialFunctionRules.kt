package org.openax12.feature.functions.special

import org.openax12.core.model.BeepPattern
import org.openax12.core.model.HapticPattern
import org.openax12.core.model.Repeat
import org.openax12.core.model.Sensors
import org.openax12.core.model.SourceId
import org.openax12.core.model.TIMER_COUNT
import org.openax12.core.model.SpecialAction
import org.openax12.core.model.SpecialFunction
import org.openax12.core.model.Trigger
import org.openax12.feature.functions.common.ActionKind
import org.openax12.feature.functions.common.MAX_TEMPLATE_LENGTH
import org.openax12.feature.functions.common.PickerData
import org.openax12.feature.functions.common.SensorGroup
import org.openax12.feature.functions.common.firstTemplateError
import org.openax12.feature.functions.common.kind

/** What is wrong with a special function as edited. */
internal enum class SpecialProblem(val blocking: Boolean) {
    /** The trigger is "never": the function would do nothing. */
    TRIGGER_NEVER(false),

    /** A text to speak is empty. */
    TEXT_EMPTY(true),

    /** The text template has a syntax error. */
    TEXT_INVALID(true),

    /** No sound file is chosen. */
    FILE_MISSING(true),

    /** The chosen sound file is not in the sounds folder (any more). */
    FILE_NOT_FOUND(false),

    /** The sensor to speak is not known to the model yet. */
    SENSOR_UNKNOWN(false),

    /** The timer to reset does not exist. */
    TIMER_INVALID(true),
}

/** Pure rules of the special-function editor and list. */
internal object SpecialFunctionRules {

    /** All action kinds in the order of the picker. */
    val KINDS: List<ActionKind> = ActionKind.entries

    /** A fresh action of [kind] with starting values taken from [data]. */
    fun defaultAction(kind: ActionKind, data: PickerData): SpecialAction = when (kind) {
        ActionKind.PLAY_TEXT -> SpecialAction.PlayText()
        ActionKind.PLAY_VALUE -> SpecialAction.PlayValue(
            sensor = data.sensors.firstOrNull { it.group == SensorGroup.TELEMETRY }?.id ?: Sensors.BATTERY_VOLTAGE,
        )
        ActionKind.PLAY_SOUND -> SpecialAction.PlaySound(file = data.soundFiles.firstOrNull().orEmpty())
        ActionKind.MUSIC -> SpecialAction.BackgroundMusic(file = data.soundFiles.firstOrNull().orEmpty())
        ActionKind.VARIO -> SpecialAction.Vario
        ActionKind.HAPTIC -> SpecialAction.Haptic(HapticPattern.SHORT)
        ActionKind.BEEP -> SpecialAction.Beep(BeepPattern.SHORT)
        ActionKind.RESET_TIMER -> SpecialAction.ResetTimer(0, counter = false)
        ActionKind.RESET_TELEMETRY -> SpecialAction.ResetTelemetry
        ActionKind.RESET_FLIGHT -> SpecialAction.ResetFlight
        ActionKind.VOLUME -> SpecialAction.SetVolume(data.knobs.firstOrNull()?.id ?: SourceId.pot(1))
        ActionKind.LOGGING -> SpecialAction.Logging
        ActionKind.SCREENSHOT -> SpecialAction.Screenshot
        ActionKind.BRIGHTNESS -> SpecialAction.Brightness(source = null, percent = 100)
    }

    /**
     * Changes the action of [function] to one of [kind]. The same kind keeps its parameters; a
     * new kind starts from [defaultAction]. A continuous action ignores the repeat setting, so
     * the repeat is reset to "once" then (it is hidden in the editor).
     */
    fun withKind(function: SpecialFunction, kind: ActionKind, data: PickerData): SpecialFunction {
        if (function.action.kind() == kind) return function
        val action = defaultAction(kind, data)
        return function.copy(action = action, repeat = if (action.isContinuous) Repeat.ONCE else function.repeat)
    }

    /** The first free special-function index below [count], or null when all slots are used. */
    fun nextFreeIndex(used: List<SpecialFunction>, count: Int): Int? {
        val taken = used.map { it.index }.toSet()
        return (0 until count).firstOrNull { it !in taken }
    }

    /** The problems of [function], blocking ones first. */
    fun problems(function: SpecialFunction, data: PickerData): List<SpecialProblem> {
        val found = LinkedHashSet<SpecialProblem>()
        if (function.trigger == Trigger.Never) found += SpecialProblem.TRIGGER_NEVER
        when (val action = function.action) {
            is SpecialAction.PlayText -> when {
                action.text.isBlank() -> found += SpecialProblem.TEXT_EMPTY
                action.text.length > MAX_TEMPLATE_LENGTH || firstTemplateError(action.text) != null -> found += SpecialProblem.TEXT_INVALID
            }
            is SpecialAction.PlayValue -> if (data.sensor(action.sensor) == null) found += SpecialProblem.SENSOR_UNKNOWN
            is SpecialAction.PlaySound -> fileProblem(action.file, data)?.let { found += it }
            is SpecialAction.BackgroundMusic -> fileProblem(action.file, data)?.let { found += it }
            is SpecialAction.ResetTimer -> if (action.timer !in 0 until TIMER_COUNT) found += SpecialProblem.TIMER_INVALID
            else -> Unit
        }
        return found.sortedBy { !it.blocking }
    }

    private fun fileProblem(file: String, data: PickerData): SpecialProblem? = when {
        file.isBlank() -> SpecialProblem.FILE_MISSING
        data.soundFiles.isNotEmpty() && file !in data.soundFiles -> SpecialProblem.FILE_NOT_FOUND
        data.soundFiles.isEmpty() -> SpecialProblem.FILE_NOT_FOUND
        else -> null
    }

    /** True if any of [problems] forbids storing the function. */
    fun hasBlocking(problems: List<SpecialProblem>): Boolean = problems.any { it.blocking }
}
