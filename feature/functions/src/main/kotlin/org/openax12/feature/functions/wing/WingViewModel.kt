package org.openax12.feature.functions.wing

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
import org.openax12.core.engine.WingCompiler
import org.openax12.core.model.MixPlan
import org.openax12.core.model.ModelType
import org.openax12.core.model.PlanConflict
import org.openax12.core.model.PlanDiff
import org.openax12.core.model.WingConfig
import org.openax12.core.runtime.SessionResult
import org.openax12.feature.functions.FunctionsDeps
import org.openax12.feature.functions.common.Outcome
import org.openax12.feature.functions.common.PickerData

/** What the flaps / wing tab shows. */
internal data class WingState(
    val draft: WingConfig = WingConfig(),
    val applied: WingConfig = WingConfig(),
    val modelType: ModelType = ModelType.GLIDER,
    val problems: List<PlanConflict> = emptyList(),
    val pickers: PickerData = PickerData(),
    val pendingDiff: PlanDiff? = null,
    val applying: Boolean = false,
    val outcome: Outcome? = null,
) {
    /** The draft differs from what is compiled into the firmware. */
    val dirty: Boolean get() = draft != applied

    /** The draft can be compiled. */
    val valid: Boolean get() = problems.none { it.isBlocking }
}

/** Everything the user can do on the flaps / wing tab. */
internal sealed interface WingAction {
    /** Replaces the draft by `transform(draft)`; the UI composes the edit, [WingEdits] holds the non-trivial ones. */
    data class Edit(val transform: (WingConfig) -> WingConfig) : WingAction
    data object AutoAssign : WingAction
    data object Revert : WingAction
    data object RequestApply : WingAction
    data object ConfirmApply : WingAction
    data object DismissDiff : WingAction
    data object ConsumeOutcome : WingAction
}

/**
 * Draft of the wing / flaps wizard with the compile / apply flow of `ConfigSession`. The whole
 * [WingConfig] is compiled, so every edit goes to the draft and reaches the firmware only through
 * "Apply" (diff first, then staged; the Save button of the chrome writes it to the radio).
 */
internal class WingViewModel(private val deps: FunctionsDeps) : ViewModel() {

    private class Pending(val plan: MixPlan, val diff: PlanDiff, val draft: WingConfig)

    private data class Local(
        val draft: WingConfig,
        val applied: WingConfig,
        val pending: Pending? = null,
        val applying: Boolean = false,
        val outcome: Outcome? = null,
    )

    private val local = MutableStateFlow(deps.extras.value.wing.let { Local(draft = it, applied = it) })

    /** UI state. */
    val state: StateFlow<WingState> = combine(
        local,
        deps.extras.map { it.modelType }.distinctUntilChanged(),
        deps.workingConfig,
        deps.pickerData,
    ) { local, type, config, pickers ->
        WingState(
            draft = local.draft,
            applied = local.applied,
            modelType = type,
            problems = WingCompiler.validate(local.draft, config),
            pickers = pickers,
            pendingDiff = local.pending?.diff,
            applying = local.applying,
            outcome = local.outcome,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), WingState(draft = local.value.draft, applied = local.value.applied))

    init {
        viewModelScope.launch {
            deps.extras.map { it.wing }.distinctUntilChanged().collect { applied ->
                local.update { current ->
                    if (current.draft == current.applied) current.copy(draft = applied, applied = applied) else current.copy(applied = applied)
                }
            }
        }
    }

    /** Handles a user action. */
    fun onAction(action: WingAction) {
        when (action) {
            is WingAction.Edit -> edit(action.transform)
            WingAction.AutoAssign -> edit { WingEdits.autoAssign(it) }
            WingAction.Revert -> local.update { it.copy(draft = it.applied) }
            WingAction.RequestApply -> requestApply()
            WingAction.ConfirmApply -> confirmApply()
            WingAction.DismissDiff -> local.update { if (it.applying) it else it.copy(pending = null) }
            WingAction.ConsumeOutcome -> local.update { it.copy(outcome = null) }
        }
    }

    private fun edit(transform: (WingConfig) -> WingConfig) {
        local.update { if (it.applying) it else it.copy(draft = transform(it.draft)) }
    }

    private fun requestApply() {
        val draft = local.value.draft
        viewModelScope.launch {
            val (plan, diff) = deps.session.compileWing(draft)
            local.update { it.copy(pending = Pending(plan, diff, draft)) }
        }
    }

    private fun confirmApply() {
        val pending = local.value.pending ?: return
        if (local.value.applying) return
        local.update { it.copy(applying = true) }
        viewModelScope.launch {
            val result = deps.session.applyPlan(pending.plan, pending.diff, stage = { extras -> extras.copy(wing = pending.draft) })
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
