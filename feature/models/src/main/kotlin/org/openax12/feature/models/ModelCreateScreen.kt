package org.openax12.feature.models

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.openax12.core.designsystem.AxButton
import org.openax12.core.designsystem.AxButtonVariant
import org.openax12.core.designsystem.AxDivider
import org.openax12.core.designsystem.AxDropdown
import org.openax12.core.designsystem.AxIcons
import org.openax12.core.designsystem.AxListGroup
import org.openax12.core.designsystem.AxListRow
import org.openax12.core.designsystem.AxSegmented
import org.openax12.core.designsystem.AxSettingRow
import org.openax12.core.designsystem.AxSwitch
import org.openax12.core.designsystem.AxValueBox
import org.openax12.core.designsystem.NumberField
import org.openax12.core.designsystem.TextEntryDialog
import org.openax12.core.engine.ModelTemplates
import org.openax12.core.engine.TemplateOptionKey
import org.openax12.core.engine.TemplateOptions
import org.openax12.core.model.ModelType
import org.openax12.core.model.TailType

/** Everything the create screen can do; defaults are no-ops for previews. */
internal class CreateActions(
    val onBack: () -> Unit = {},
    val onSelectTemplate: (String) -> Unit = {},
    val onBlankType: (ModelType) -> Unit = {},
    val onName: (String) -> Unit = {},
    val onOptions: ((TemplateOptions) -> TemplateOptions) -> Unit = {},
    val onCreate: () -> Unit = {},
    val onGuardSave: () -> Unit = {},
    val onGuardDiscard: () -> Unit = {},
    val onGuardConfirmLink: () -> Unit = {},
    val onGuardCancel: () -> Unit = {},
)

/** One entry of the template list. */
private data class TemplateChoice(val id: String, val type: ModelType, @StringRes val name: Int, @StringRes val description: Int)

private val templateChoices = listOf(
    TemplateChoice(ModelTemplates.GLIDER, ModelType.GLIDER, R.string.models_template_glider, R.string.models_template_glider_desc),
    TemplateChoice(ModelTemplates.PLANE, ModelType.PLANE, R.string.models_template_plane, R.string.models_template_plane_desc),
    TemplateChoice(ModelTemplates.FLYING_WING, ModelType.WING, R.string.models_template_wing, R.string.models_template_wing_desc),
    TemplateChoice(ModelTemplates.MULTIROTOR, ModelType.MULTIROTOR, R.string.models_template_multirotor, R.string.models_template_multirotor_desc),
    TemplateChoice(ModelTemplates.HELICOPTER, ModelType.HELICOPTER, R.string.models_template_helicopter, R.string.models_template_helicopter_desc),
    TemplateChoice(BLANK_TEMPLATE_ID, ModelType.OTHER, R.string.models_template_blank, R.string.models_template_blank_desc),
)

/**
 * The "new model" screen: template list on the left, name and the options of the selected
 * template on the right, **Create** in the header.
 */
@Composable
internal fun ModelCreateScreen(state: CreateUiState, actions: CreateActions, modifier: Modifier = Modifier) {
    var nameDialog by rememberSaveable { mutableStateOf(false) }
    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            ScreenHeader(title = stringResource(R.string.models_create_title), onBack = actions.onBack) {
                AxButton(
                    text = stringResource(R.string.models_create),
                    onClick = actions.onCreate,
                    variant = AxButtonVariant.Primary,
                    icon = AxIcons.Check,
                    enabled = state.canCreate,
                )
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(modifier = Modifier.width(290.dp).verticalScroll(rememberScrollState())) {
                    AxListGroup {
                        templateChoices.forEachIndexed { index, choice ->
                            if (index > 0) AxDivider()
                            AxListRow(
                                title = stringResource(choice.name),
                                subtitle = stringResource(choice.description),
                                leadingIcon = choice.type.icon(),
                                selected = choice.id == state.templateId,
                                onClick = { actions.onSelectTemplate(choice.id) },
                            )
                        }
                    }
                }
                Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    AxListGroup {
                        AxSettingRow(
                            title = stringResource(R.string.models_field_name),
                            description = state.nameProblem?.let { stringResource(it.messageRes()) },
                        ) {
                            AxValueBox(
                                text = state.name.ifBlank { stringResource(R.string.models_name_placeholder) },
                                onClick = { nameDialog = true },
                                trailingIcon = AxIcons.Edit,
                                textAlign = TextAlign.Start,
                                highlighted = state.nameProblem != null,
                                modifier = Modifier.width(RowControlWidth),
                            )
                        }
                        val template = state.template
                        if (template == null) {
                            AxDivider()
                            AxSettingRow(title = stringResource(R.string.models_field_type)) {
                                AxDropdown(
                                    options = ModelTypes.map { stringResource(it.labelRes()) },
                                    selectedIndex = ModelTypes.indexOf(state.blankType),
                                    onSelected = { actions.onBlankType(ModelTypes[it]) },
                                    title = stringResource(R.string.models_field_type),
                                    columns = 2,
                                    modifier = Modifier.width(RowControlWidth),
                                )
                            }
                        } else {
                            template.options.forEach { key ->
                                AxDivider()
                                OptionRow(key, state, actions)
                            }
                        }
                    }
                }
            }
        }
        GuardDialogs(
            state = state.guard,
            onSave = actions.onGuardSave,
            onDiscard = actions.onGuardDiscard,
            onConfirmLink = actions.onGuardConfirmLink,
            onCancel = actions.onGuardCancel,
        )
        if (state.busy) BusyOverlay(text = stringResource(R.string.models_busy_creating))
        if (nameDialog) {
            val problems = NameProblem.entries.associateWith { stringResource(it.messageRes()) }
            TextEntryDialog(
                title = stringResource(R.string.models_name_title),
                initialValue = state.name,
                maxLength = ModelNameRules.MAX_LENGTH,
                validate = { ModelNameRules.check(it, state.existingNames)?.let(problems::getValue) },
                onConfirm = {
                    nameDialog = false
                    actions.onName(it)
                },
                onDismiss = { nameDialog = false },
            )
        }
    }
}

@Composable
private fun OptionRow(key: TemplateOptionKey, state: CreateUiState, actions: CreateActions) {
    val options = state.options
    when (key) {
        TemplateOptionKey.WING_SERVOS -> {
            val counts = listOf(2, 4, 6)
            AxSettingRow(
                title = stringResource(R.string.models_opt_wing_servos),
                description = stringResource(R.string.models_opt_wing_servos_hint),
            ) {
                AxSegmented(
                    options = counts.map { it.toString() },
                    selectedIndex = counts.indexOf(options.wingServos).coerceAtLeast(0),
                    onSelected = { i -> actions.onOptions { it.copy(wingServos = counts[i]) } },
                    modifier = Modifier.width(RowControlWidth),
                )
            }
        }
        TemplateOptionKey.TAIL -> {
            val tails = listOf(TailType.NORMAL, TailType.V_TAIL)
            AxSettingRow(title = stringResource(R.string.models_opt_tail)) {
                AxSegmented(
                    options = listOf(stringResource(R.string.models_opt_tail_normal), stringResource(R.string.models_opt_tail_v)),
                    selectedIndex = tails.indexOf(options.tail).coerceAtLeast(0),
                    onSelected = { i -> actions.onOptions { it.copy(tail = tails[i]) } },
                    modifier = Modifier.width(RowControlWidth),
                )
            }
        }
        TemplateOptionKey.MOTOR -> SwitchRow(R.string.models_opt_motor, R.string.models_opt_motor_hint, options.hasMotor) { v ->
            actions.onOptions { it.copy(hasMotor = v) }
        }
        TemplateOptionKey.FLAPS -> SwitchRow(R.string.models_opt_flaps, null, options.hasFlaps) { v ->
            actions.onOptions { it.copy(hasFlaps = v) }
        }
        TemplateOptionKey.RUDDER -> SwitchRow(R.string.models_opt_rudder, null, options.hasRudder) { v ->
            actions.onOptions { it.copy(hasRudder = v) }
        }
        TemplateOptionKey.DIFFERENTIAL -> AxSettingRow(title = stringResource(R.string.models_opt_differential)) {
            NumberField(
                value = options.aileronDifferential,
                onValueChange = { v -> actions.onOptions { it.copy(aileronDifferential = v) } },
                min = 0,
                max = 100,
                unit = "%",
                title = stringResource(R.string.models_opt_differential),
                defaultValue = 0,
                modifier = Modifier.width(120.dp),
            )
        }
        TemplateOptionKey.MODE_SWITCH,
        TemplateOptionKey.ARM_SWITCH,
        TemplateOptionKey.BEEPER_SWITCH,
        TemplateOptionKey.THROTTLE_HOLD_SWITCH,
        TemplateOptionKey.FLAP_SWITCH,
        -> {
            val current = options.switchOf(key)
            AxSettingRow(
                title = stringResource(key.titleRes()),
                description = key.hintRes()?.let { stringResource(it) },
            ) {
                AxDropdown(
                    options = state.switches.map { it.name },
                    selectedIndex = state.switches.indexOfFirst { it.id == current },
                    onSelected = { i -> actions.onOptions { it.with(key, state.switches[i].id) } },
                    title = stringResource(key.titleRes()),
                    columns = 2,
                    modifier = Modifier.width(RowControlWidth),
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(@StringRes title: Int, @StringRes hint: Int?, checked: Boolean, onChange: (Boolean) -> Unit) {
    AxSettingRow(title = stringResource(title), description = hint?.let { stringResource(it) }) {
        AxSwitch(checked = checked, onCheckedChange = onChange)
    }
}

@StringRes
private fun TemplateOptionKey.titleRes(): Int = when (this) {
    TemplateOptionKey.MODE_SWITCH -> R.string.models_opt_mode_switch
    TemplateOptionKey.ARM_SWITCH -> R.string.models_opt_arm_switch
    TemplateOptionKey.BEEPER_SWITCH -> R.string.models_opt_beeper_switch
    TemplateOptionKey.THROTTLE_HOLD_SWITCH -> R.string.models_opt_hold_switch
    TemplateOptionKey.FLAP_SWITCH -> R.string.models_opt_flap_switch
    else -> R.string.models_opt_mode_switch
}

@StringRes
private fun TemplateOptionKey.hintRes(): Int? = when (this) {
    TemplateOptionKey.ARM_SWITCH -> R.string.models_opt_arm_switch_hint
    TemplateOptionKey.THROTTLE_HOLD_SWITCH -> R.string.models_opt_hold_switch_hint
    else -> null
}
