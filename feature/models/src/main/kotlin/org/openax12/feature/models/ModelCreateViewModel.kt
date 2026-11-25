package org.openax12.feature.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import org.openax12.core.data.ModelRepository
import org.openax12.core.engine.ModelTemplate
import org.openax12.core.engine.ModelTemplates
import org.openax12.core.engine.TemplateOptionKey
import org.openax12.core.engine.TemplateOptions
import org.openax12.core.model.ModelBlueprint
import org.openax12.core.model.ModelType
import org.openax12.core.model.SourceId
import org.openax12.core.model.TailType
import org.openax12.core.model.WorkingConfig
import org.openax12.core.runtime.ConfigSession

/** Id of the "blank model" choice in the template list (no built-in template). */
internal const val BLANK_TEMPLATE_ID: String = "blank"

/** A switch the user can pick in a template option. */
internal data class SwitchChoice(val id: SourceId, val name: String)

/**
 * State of the "new model" screen.
 *
 * @property templateId id of the selected template, or [BLANK_TEMPLATE_ID]
 * @property blankType model type of a blank model
 * @property name the name typed (or suggested) for the new model
 * @property switches the switches of the radio for the template options
 * @property existingNames names of the models that already exist
 */
internal data class CreateUiState(
    val templateId: String = ModelTemplates.GLIDER,
    val blankType: ModelType = ModelType.OTHER,
    val name: String = "",
    val options: TemplateOptions = TemplateOptions(),
    val switches: List<SwitchChoice> = emptyList(),
    val existingNames: List<String> = emptyList(),
    val busy: Boolean = false,
    val guard: DeviceWriteGuard.GuardState = DeviceWriteGuard.GuardState(),
) {
    /** The selected built-in template, null for a blank model. */
    val template: ModelTemplate? get() = ModelTemplates.byId(templateId)

    /** Why [name] cannot be used, or null. */
    val nameProblem: NameProblem? get() = ModelNameRules.check(name, existingNames)

    /** The model type that will be created. */
    val modelType: ModelType get() = template?.modelType ?: blankType

    /** True if the Create button is enabled. */
    val canCreate: Boolean get() = nameProblem == null && !busy
}

/** One-shot results of the create view model. */
internal sealed interface CreateEvent {
    /** Show a toast. */
    data class Message(val message: UiMessage) : CreateEvent

    /** The model [name] was created and is now the current model: leave the screen. */
    data class Created(val name: String) : CreateEvent
}

/**
 * Builds the blueprint of a new model: a built-in template with the user's options, or a blank
 * model of [blankType].
 */
internal fun buildBlueprint(
    templateId: String,
    blankType: ModelType,
    options: TemplateOptions,
    working: WorkingConfig,
): ModelBlueprint {
    val template = ModelTemplates.byId(templateId) ?: return ModelBlueprint.blank(blankType)
    return ModelTemplates.build(template, options, working)
}

/** The switches offered for template options: the enabled switches of the radio, SA..SH if unknown. */
internal fun switchChoices(working: WorkingConfig): List<SwitchChoice> {
    val fromRadio = working.sources
        .filter { it.id.isSwitch && it.enabled }
        .map { SwitchChoice(it.id, working.sourceName(it.id)) }
    if (fromRadio.isNotEmpty()) return fromRadio
    return (SourceId.SA.index..SourceId.SH.index).map { SourceId(it) }.map { SwitchChoice(it, it.defaultName) }
}

/** The options of a template with [key] set from the UI controls. */
internal fun TemplateOptions.with(key: TemplateOptionKey, switch: SourceId): TemplateOptions = when (key) {
    TemplateOptionKey.MODE_SWITCH -> copy(modeSwitch = switch)
    TemplateOptionKey.ARM_SWITCH -> copy(armSwitch = switch)
    TemplateOptionKey.BEEPER_SWITCH -> copy(beeperSwitch = switch)
    TemplateOptionKey.THROTTLE_HOLD_SWITCH -> copy(throttleHoldSwitch = switch)
    TemplateOptionKey.FLAP_SWITCH -> copy(flapSwitch = switch)
    else -> this
}

/** The switch currently chosen for [key], or null for keys that are not switch options. */
internal fun TemplateOptions.switchOf(key: TemplateOptionKey): SourceId? = when (key) {
    TemplateOptionKey.MODE_SWITCH -> modeSwitch
    TemplateOptionKey.ARM_SWITCH -> armSwitch
    TemplateOptionKey.BEEPER_SWITCH -> beeperSwitch
    TemplateOptionKey.THROTTLE_HOLD_SWITCH -> throttleHoldSwitch
    TemplateOptionKey.FLAP_SWITCH -> flapSwitch
    else -> null
}

/**
 * View model of the "new model" flow (SPEC section 4): choose a template (or blank), name it,
 * set the template's options, create. Creating switches the radio to the new model, so the
 * unsaved-changes and radio-link questions come first.
 *
 * @param defaultName suggested base name for a template id (or [BLANK_TEMPLATE_ID]), localized
 */
internal class ModelCreateViewModel(
    models: ModelRepository,
    private val configSession: ConfigSession,
    linkUp: () -> Boolean,
    private val defaultName: (String) -> String,
) : ViewModel() {

    private data class Local(
        val templateId: String = ModelTemplates.GLIDER,
        val blankType: ModelType = ModelType.OTHER,
        val name: String = "",
        val nameTouched: Boolean = false,
        val options: TemplateOptions = TemplateOptions(),
        val busy: Boolean = false,
    )

    private val local = MutableStateFlow(
        Local(name = ModelNameRules.unique(defaultName(ModelTemplates.GLIDER), models.models.value.map { it.name })),
    )
    private val eventChannel = Channel<CreateEvent>(Channel.BUFFERED)
    private val existing = models.models

    /** One-shot events. */
    val events: Flow<CreateEvent> = eventChannel.receiveAsFlow()

    private val guard = DeviceWriteGuard(viewModelScope, configSession, linkUp) { eventChannel.trySend(CreateEvent.Message(it)) }

    /** The screen state. */
    val state: StateFlow<CreateUiState> = combine(local, existing, configSession.workingConfig, guard.state) { loc, list, working, g ->
        CreateUiState(
            templateId = loc.templateId,
            blankType = loc.blankType,
            name = loc.name,
            options = loc.options,
            switches = switchChoices(working),
            existingNames = list.map { it.name },
            busy = loc.busy,
            guard = g,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CreateUiState(name = local.value.name))

    /** Picks a template; an untouched name follows the template. */
    fun selectTemplate(id: String) {
        local.update {
            val name = if (it.nameTouched) it.name else ModelNameRules.unique(defaultName(id), existing.value.map { m -> m.name })
            it.copy(templateId = id, name = name)
        }
    }

    /** Sets the type of a blank model. */
    fun setBlankType(type: ModelType) = local.update { it.copy(blankType = type) }

    /** Sets the name typed by the user. */
    fun setName(name: String) = local.update { it.copy(name = name, nameTouched = true) }

    /** Changes the template options. */
    fun updateOptions(transform: (TemplateOptions) -> TemplateOptions) = local.update { it.copy(options = transform(it.options)) }

    /** Convenience for the tail option. */
    fun setTail(tail: TailType) = updateOptions { it.copy(tail = tail) }

    /** Creates the model and makes it current (after the unsaved-changes / link questions). */
    fun create() {
        val s = state.value
        if (!s.canCreate) return
        val name = ModelNameRules.normalize(s.name)
        guard.request { discard ->
            local.update { it.copy(busy = true) }
            try {
                if (discard) {
                    val dropped = configSession.discard().failureMessage()
                    if (dropped != null) {
                        eventChannel.trySend(CreateEvent.Message(dropped))
                        return@request
                    }
                }
                val blueprint = buildBlueprint(s.templateId, s.blankType, s.options, configSession.workingConfig.value)
                val failure = configSession.createModel(name, blueprint).failureMessage()
                eventChannel.trySend(if (failure != null) CreateEvent.Message(failure) else CreateEvent.Created(name))
            } finally {
                local.update { it.copy(busy = false) }
            }
        }
    }

    /** "Save" of the unsaved-changes dialog. */
    fun guardSave() = guard.saveThenContinue()

    /** "Discard" of the unsaved-changes dialog. */
    fun guardDiscard() = guard.discardThenContinue()

    /** The user confirmed the link question. */
    fun guardConfirmLink() = guard.confirmLink()

    /** The user cancelled a guard dialog. */
    fun guardCancel() = guard.cancel()
}
