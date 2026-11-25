package org.openax12.feature.functions.logical

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.openax12.core.engine.ValueResolver
import org.openax12.core.model.LOGICAL_SWITCH_COUNT
import org.openax12.core.model.LogicalFunction
import org.openax12.core.model.LogicalSwitch
import org.openax12.core.model.SensorId
import org.openax12.feature.functions.FunctionsDeps
import org.openax12.feature.functions.common.PickerData

/** The list tab: all 32 slots. */
internal data class LogicalSwitchesState(
    val slots: List<LogicalSwitch> = LogicalSwitchRules.slots(emptyList(), LOGICAL_SWITCH_COUNT),
    val pickers: PickerData = PickerData(),
) {
    /** Number of configured switches. */
    val used: Int get() = slots.count { it.isUsed }
}

/** ViewModel of the logical-switches tab. */
internal class LogicalSwitchesViewModel(deps: FunctionsDeps) : ViewModel() {
    /** UI state. */
    val state: StateFlow<LogicalSwitchesState> = combine(deps.extras, deps.pickerData) { extras, pickers ->
        LogicalSwitchesState(LogicalSwitchRules.slots(extras.logicalSwitches, LOGICAL_SWITCH_COUNT), pickers)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        LogicalSwitchesState(LogicalSwitchRules.slots(deps.extras.value.logicalSwitches, LOGICAL_SWITCH_COUNT), deps.pickerData.value),
    )

    /** State of the 32 switches for the live dots. */
    val live = deps.logicalSwitches

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/** What the logical-switch editor shows. */
internal data class LogicalSwitchEditorState(
    val draft: LogicalSwitch,
    val original: LogicalSwitch,
    val pickers: PickerData = PickerData(),
    val problems: List<LogicalProblem> = emptyList(),
    /** Live value of operand a / b as text, null when unknown. */
    val liveA: String? = null,
    val liveB: String? = null,
    /** The switch is on right now (per the saved configuration the runtime evaluates). */
    val isOn: Boolean = false,
    /** True after Done / Clear: the screen should close. */
    val finished: Boolean = false,
) {
    /** The draft differs from the stored switch. */
    val dirty: Boolean get() = draft != original

    /** Done is allowed. */
    val canFinish: Boolean get() = !LogicalSwitchRules.hasBlocking(problems)
}

/** Everything the user can do in the logical-switch editor. */
internal sealed interface LogicalSwitchEditorAction {
    /** Replace the draft by `transform(draft)`. */
    data class Edit(val transform: (LogicalSwitch) -> LogicalSwitch) : LogicalSwitchEditorAction
    data class SetFunction(val function: LogicalFunction) : LogicalSwitchEditorAction
    data object Done : LogicalSwitchEditorAction
    data object Clear : LogicalSwitchEditorAction
}

/** ViewModel of the editor of logical switch [index] (0-based). */
internal class LogicalSwitchEditorViewModel(private val index: Int, private val deps: FunctionsDeps) : ViewModel() {

    private val original = deps.extras.value.logicalSwitch(index)

    private data class Local(val draft: LogicalSwitch, val liveA: String? = null, val liveB: String? = null, val finished: Boolean = false)

    private val local = MutableStateFlow(Local(draft = original))

    /** UI state. */
    val state: StateFlow<LogicalSwitchEditorState> = combine(local, deps.pickerData, deps.logicalSwitches) { local, pickers, states ->
        LogicalSwitchEditorState(
            draft = local.draft,
            original = original,
            pickers = pickers,
            problems = LogicalSwitchRules.problems(local.draft, pickers),
            liveA = local.liveA,
            liveB = local.liveB,
            isOn = states[index],
            finished = local.finished,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), LogicalSwitchEditorState(draft = original, original = original))

    init {
        // Live values of the operands, refreshed twice a second while the editor is open.
        viewModelScope.launch {
            while (true) {
                val draft = local.value.draft
                val resolver = deps.services.resolver() ?: break
                val a = draft.a?.takeIf { draft.function.usesA }?.let { describeLive(resolver, it) }
                val b = draft.b?.takeIf { draft.function.usesB }?.let { describeLive(resolver, it) }
                local.update { it.copy(liveA = a, liveB = b) }
                delay(LIVE_PERIOD_MS)
            }
        }
    }

    private fun describeLive(resolver: ValueResolver?, sensor: SensorId): String? {
        val value = resolver?.resolve(sensor) ?: return null
        if (value.lost) return null
        val unit = value.unit.symbol
        return if (unit.isEmpty()) value.text else "${value.text} $unit"
    }

    /** Handles a user action. */
    fun onAction(action: LogicalSwitchEditorAction) {
        when (action) {
            is LogicalSwitchEditorAction.Edit -> local.update { it.copy(draft = action.transform(it.draft).copy(index = index)) }
            is LogicalSwitchEditorAction.SetFunction ->
                local.update { it.copy(draft = LogicalSwitchRules.withFunction(it.draft, action.function, deps.pickerData.value)) }
            LogicalSwitchEditorAction.Done -> store(local.value.draft)
            LogicalSwitchEditorAction.Clear -> store(LogicalSwitch(index))
        }
    }

    private fun store(value: LogicalSwitch) {
        if (value.isUsed && LogicalSwitchRules.hasBlocking(LogicalSwitchRules.problems(value, deps.pickerData.value))) return
        viewModelScope.launch {
            if (value != original) deps.session.updateExtras { it.withLogicalSwitch(value) }
            local.update { it.copy(finished = true) }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val LIVE_PERIOD_MS = 500L
    }
}
