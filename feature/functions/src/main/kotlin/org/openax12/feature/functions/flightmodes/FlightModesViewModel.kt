package org.openax12.feature.functions.flightmodes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.openax12.core.engine.FlightModeCompiler
import org.openax12.core.model.FlightModeConfig
import org.openax12.core.model.FlightModesConfig
import org.openax12.core.model.MixPlan
import org.openax12.core.model.ModelType
import org.openax12.core.model.PlanConflict
import org.openax12.core.model.PlanDiff
import org.openax12.core.model.SourceId
import org.openax12.core.model.SwitchPosition
import org.openax12.core.runtime.SessionResult
import org.openax12.feature.functions.FunctionsDeps
import org.openax12.feature.functions.common.Outcome
import org.openax12.feature.functions.common.PickerData

/** What the flight-modes tab shows. */
internal data class FlightModesState(
    val draft: FlightModesConfig = FlightModesConfig(),
    val applied: FlightModesConfig = FlightModesConfig(),
    val modelType: ModelType = ModelType.GLIDER,
    val problems: List<PlanConflict> = emptyList(),
    val activeMode: Int = 0,
    val pickers: PickerData = PickerData(),
    val pendingDiff: PlanDiff? = null,
    val applying: Boolean = false,
    val outcome: Outcome? = null,
) {
    /** The draft differs from what is compiled into the firmware. */
    val dirty: Boolean get() = draft != applied

    /** Channels (0-based) with a row in the channel table. */
    val rows: List<Int> get() = FlightModeEdits.channelRows(draft)

    /** The draft can be compiled. */
    val valid: Boolean get() = problems.none { it.isBlocking }
}

/** Everything the user can do on the flight-modes tab. */
internal sealed interface FlightModesAction {
    data class SetEnabled(val enabled: Boolean) : FlightModesAction
    data class SetSwitch(val source: SourceId) : FlightModesAction
    data class Rename(val mode: Int, val name: String) : FlightModesAction
    data class SetAnnounce(val mode: Int, val announce: Boolean) : FlightModesAction
    data class SetPosition(val mode: Int, val position: SwitchPosition) : FlightModesAction
    data class SetTransition(val mode: Int, val ms: Int) : FlightModesAction
    data object AddMode : FlightModesAction
    data class RemoveMode(val mode: Int) : FlightModesAction
    data class AddChannel(val channel: Int) : FlightModesAction
    data class RemoveChannel(val channel: Int) : FlightModesAction
    data class SetOffset(val mode: Int, val channel: Int, val value: Int) : FlightModesAction
    data class SetRate(val mode: Int, val channel: Int, val value: Int) : FlightModesAction
    data class ApplyPreset(val names: PresetNames) : FlightModesAction
    data object Revert : FlightModesAction
    data object RequestApply : FlightModesAction
    data object ConfirmApply : FlightModesAction
    data object DismissDiff : FlightModesAction
    data object ConsumeOutcome : FlightModesAction
}

/**
 * Draft of the flight modes with the compile / apply flow of `ConfigSession`: edits change the
 * draft only; "Apply" compiles it, shows the dry-run diff and stages plan and settings together.
 * Names and announce flags are not compiled and are staged at once while nothing else is pending.
 */
internal class FlightModesViewModel(private val deps: FunctionsDeps) : ViewModel() {

    private class Pending(val plan: MixPlan, val diff: PlanDiff, val draft: FlightModesConfig)

    private data class Local(
        val draft: FlightModesConfig,
        val applied: FlightModesConfig,
        val pending: Pending? = null,
        val applying: Boolean = false,
        val outcome: Outcome? = null,
    )

    private val local = MutableStateFlow(
        deps.extras.value.flightModes.let { Local(draft = it, applied = it) },
    )

    /** UI state. */
    val state: StateFlow<FlightModesState> = combine(
        local,
        deps.extras.map { it.modelType }.distinctUntilChanged(),
        deps.workingConfig,
        deps.pickerData,
        deps.flightMode,
    ) { local, type, config, pickers, active ->
        FlightModesState(
            draft = local.draft,
            applied = local.applied,
            modelType = type,
            problems = FlightModeCompiler.validate(local.draft, config),
            activeMode = active.index,
            pickers = pickers,
            pendingDiff = local.pending?.diff,
            applying = local.applying,
            outcome = local.outcome,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), FlightModesState(draft = local.value.draft, applied = local.value.applied))

    init {
        // Follow the model: after Discard, a model switch or a restore the staged settings change under us.
        viewModelScope.launch {
            deps.extras.map { it.flightModes }.distinctUntilChanged().collect { applied ->
                local.update { current ->
                    if (current.draft == current.applied) current.copy(draft = applied, applied = applied) else current.copy(applied = applied)
                }
            }
        }
    }

    /** Handles a user action. */
    fun onAction(action: FlightModesAction) {
        when (action) {
            is FlightModesAction.SetEnabled -> setEnabled(action.enabled)
            is FlightModesAction.SetSwitch -> edit { it.copy(modeSwitch = action.source) }
            is FlightModesAction.Rename -> editNonCompiled(action.mode) { it.copy(name = action.name) }
            is FlightModesAction.SetAnnounce -> editNonCompiled(action.mode) { it.copy(announce = action.announce) }
            is FlightModesAction.SetPosition -> edit { FlightModeEdits.setPosition(it, action.mode, action.position) }
            is FlightModesAction.SetTransition ->
                edit { FlightModeEdits.updateMode(it, action.mode) { mode -> mode.copy(transitionMs = action.ms.coerceIn(0, FlightModeCompiler.MAX_SLOW_MS)) } }
            FlightModesAction.AddMode -> edit(FlightModeEdits::addMode)
            is FlightModesAction.RemoveMode -> edit { FlightModeEdits.removeMode(it, action.mode) }
            is FlightModesAction.AddChannel -> edit { FlightModeEdits.addChannel(it, action.channel) }
            is FlightModesAction.RemoveChannel -> edit { FlightModeEdits.removeChannel(it, action.channel) }
            is FlightModesAction.SetOffset -> edit { FlightModeEdits.setOffset(it, action.mode, action.channel, action.value) }
            is FlightModesAction.SetRate -> edit { FlightModeEdits.setRate(it, action.mode, action.channel, action.value) }
            is FlightModesAction.ApplyPreset -> edit {
                FlightModeEdits.preset(deps.extras.value.modelType, it.modeSwitch ?: firstSwitch(), action.names)
            }
            FlightModesAction.Revert -> local.update { it.copy(draft = it.applied) }
            FlightModesAction.RequestApply -> requestApply()
            FlightModesAction.ConfirmApply -> confirmApply()
            FlightModesAction.DismissDiff -> local.update { if (it.applying) it else it.copy(pending = null) }
            FlightModesAction.ConsumeOutcome -> local.update { it.copy(outcome = null) }
        }
    }

    private fun firstSwitch(): SourceId? = deps.pickerData.value.switches.firstOrNull()?.id

    private fun setEnabled(enabled: Boolean) = edit { draft ->
        when {
            !enabled -> draft.copy(enabled = false)
            draft.modes.isEmpty() -> draft.copy(enabled = true, modes = listOf(FlightModeConfig(position = SwitchPosition.UP)), modeSwitch = draft.modeSwitch ?: firstSwitch())
            else -> draft.copy(enabled = true, modeSwitch = draft.modeSwitch ?: firstSwitch())
        }
    }

    private fun edit(transform: (FlightModesConfig) -> FlightModesConfig) {
        local.update { if (it.applying) it else it.copy(draft = transform(it.draft)) }
    }

    /**
     * A name or announce flag: part of the draft, and also staged at once (outside the compile
     * flow) when the compiled parts of draft and applied settings are equal, so that renaming never
     * needs an "Apply".
     */
    private fun editNonCompiled(index: Int, transform: (FlightModeConfig) -> FlightModeConfig) {
        val before = local.value
        if (before.applying) return
        val newDraft = FlightModeEdits.updateMode(before.draft, index, transform)
        local.update { it.copy(draft = newDraft) }
        if (FlightModeEdits.compiledEquals(before.draft, before.applied)) {
            viewModelScope.launch {
                deps.session.updateExtras { extras ->
                    extras.copy(flightModes = FlightModeEdits.updateMode(extras.flightModes, index, transform))
                }
            }
        }
    }

    private fun requestApply() {
        val draft = local.value.draft
        viewModelScope.launch {
            val (plan, diff) = deps.session.compileFlightModes(draft)
            local.update { it.copy(pending = Pending(plan, diff, draft)) }
        }
    }

    private fun confirmApply() {
        val pending = local.value.pending ?: return
        if (local.value.applying) return
        local.update { it.copy(applying = true) }
        viewModelScope.launch {
            val result = deps.session.applyPlan(pending.plan, pending.diff, stage = { extras -> extras.copy(flightModes = pending.draft) })
            local.update {
                it.copy(
                    applying = false,
                    pending = null,
                    outcome = when (result) {
                        is SessionResult.Success -> Outcome.Applied
                        is SessionResult.Failure -> Outcome.Failed(result.error)
                    },
                )
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
