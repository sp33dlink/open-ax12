package org.openax12.feature.functions.wing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.openax12.core.designsystem.AxButton
import org.openax12.core.designsystem.AxButtonVariant
import org.openax12.core.designsystem.AxEmptyState
import org.openax12.core.designsystem.AxIcons
import org.openax12.core.designsystem.AxSegmented
import org.openax12.core.designsystem.AxSwitch
import org.openax12.core.designsystem.AxTheme
import org.openax12.core.designsystem.NumberField
import org.openax12.core.designsystem.PercentField
import org.openax12.core.model.ButterflyConfig
import org.openax12.core.model.CompareOp
import org.openax12.core.model.FlapControl
import org.openax12.core.model.ModelType
import org.openax12.core.model.SourceId
import org.openax12.core.model.SwitchCondition
import org.openax12.core.model.TailType
import org.openax12.core.model.WingConfig
import org.openax12.core.model.WingType
import org.openax12.feature.functions.R
import org.openax12.feature.functions.common.BannerTone
import org.openax12.feature.functions.common.Caption
import org.openax12.feature.functions.common.ChannelField
import org.openax12.feature.functions.common.ControlFlow
import org.openax12.feature.functions.common.FormCard
import org.openax12.feature.functions.common.FormRow
import org.openax12.feature.functions.common.InfoBanner
import org.openax12.feature.functions.common.LabelDropdown
import org.openax12.feature.functions.common.NumberWidth
import org.openax12.feature.functions.common.PickerData
import org.openax12.feature.functions.common.PlanDiffDialog
import org.openax12.feature.functions.common.ScreenPadding
import org.openax12.feature.functions.common.SourceField
import org.openax12.feature.functions.common.SwitchConditionFields
import org.openax12.feature.functions.common.conflictText
import org.openax12.feature.functions.common.modelTypeLabel

private enum class WingPage { LAYOUT, FLAPS, BUTTERFLY, MIXING }

/** The "Flaps / Wing" tab: layout, flaps, butterfly and mixing of the wing wizard. */
@Composable
internal fun WingScreen(
    state: WingState,
    onAction: (WingAction) -> Unit,
    modifier: Modifier = Modifier,
    initialPage: Int = 0,
) {
    var pageIndex by rememberSaveable { mutableStateOf(initialPage) }
    val page = WingPage.entries[pageIndex.coerceIn(0, WingPage.entries.lastIndex)]
    val draft = state.draft
    fun edit(transform: (WingConfig) -> WingConfig) = onAction(WingAction.Edit(transform))
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = ScreenPadding, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AxSwitch(checked = draft.enabled, onCheckedChange = { enabled -> edit { it.copy(enabled = enabled) } })
            Text(
                text = stringResource(R.string.fn_wing_title),
                style = AxTheme.typography.bodyStrong,
                color = AxTheme.colors.text,
                maxLines = 1,
            )
            if (draft.enabled) {
                AxSegmented(
                    options = listOf(
                        stringResource(R.string.fn_wing_page_layout),
                        stringResource(R.string.fn_wing_page_flaps),
                        stringResource(R.string.fn_wing_page_butterfly),
                        stringResource(R.string.fn_wing_page_mixing),
                    ),
                    selectedIndex = page.ordinal,
                    onSelected = { pageIndex = it },
                    modifier = Modifier.width(380.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            if (state.dirty) {
                AxButton(
                    text = stringResource(R.string.fn_revert),
                    onClick = { onAction(WingAction.Revert) },
                    variant = AxButtonVariant.Text,
                    enabled = !state.applying,
                )
            }
            AxButton(
                text = stringResource(R.string.fn_apply),
                onClick = { onAction(WingAction.RequestApply) },
                variant = AxButtonVariant.Primary,
                enabled = state.dirty && state.valid && !state.applying,
            )
        }
        val problem = state.problems.firstOrNull()
        if (draft.enabled && problem != null) {
            val more = state.problems.size - 1
            InfoBanner(
                text = conflictText(problem) + if (more > 0) "  " + stringResource(R.string.fn_more_problems, more) else "",
                tone = BannerTone.WARNING,
            )
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (!draft.enabled) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    AxEmptyState(
                        title = stringResource(R.string.fn_wing_off_title),
                        message = stringResource(R.string.fn_wing_off_message),
                        icon = AxIcons.Glider,
                        actionLabel = stringResource(R.string.fn_wing_turn_on),
                        onAction = { edit { it.copy(enabled = true) } },
                    )
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (state.modelType == ModelType.MULTIROTOR || state.modelType == ModelType.HELICOPTER) {
                        InfoBanner(text = stringResource(R.string.fn_wing_wrong_type, modelTypeLabel(state.modelType)))
                    }
                    when (page) {
                        WingPage.LAYOUT -> LayoutPage(draft, ::edit, onAutoAssign = { onAction(WingAction.AutoAssign) })
                        WingPage.FLAPS -> FlapsPage(draft, state.pickers, ::edit)
                        WingPage.BUTTERFLY -> ButterflyPage(draft, state.pickers, ::edit)
                        WingPage.MIXING -> MixingPage(draft, ::edit)
                    }
                }
            }
        }
    }
    val diff = state.pendingDiff
    if (diff != null) {
        PlanDiffDialog(
            diff = diff,
            busy = state.applying,
            onApply = { onAction(WingAction.ConfirmApply) },
            onDismiss = { onAction(WingAction.DismissDiff) },
        )
    }
}

// -- Layout ---------------------------------------------------------------------------------------

@Composable
private fun wingTypeLabel(type: WingType): String = stringResource(
    when (type) {
        WingType.NONE -> R.string.fn_wing_none
        WingType.ONE_AILERON -> R.string.fn_wing_one_aileron
        WingType.TWO_AILERONS -> R.string.fn_wing_two_ailerons
        WingType.TWO_AILERONS_TWO_FLAPS -> R.string.fn_wing_four_servo
        WingType.TWO_AILERONS_FOUR_FLAPS -> R.string.fn_wing_six_servo
        WingType.ELEVON -> R.string.fn_wing_elevon
    },
)

@Composable
private fun tailTypeLabel(type: TailType): String = stringResource(
    when (type) {
        TailType.NONE -> R.string.fn_tail_none
        TailType.NORMAL -> R.string.fn_tail_normal
        TailType.V_TAIL -> R.string.fn_tail_v
    },
)

@Composable
private fun roleLabel(role: WingRole, config: WingConfig): String = stringResource(
    when (role) {
        WingRole.AILERON_LEFT -> when (config.wingType) {
            WingType.ONE_AILERON -> R.string.fn_role_aileron
            WingType.ELEVON -> R.string.fn_role_elevon_left
            else -> R.string.fn_role_aileron_left
        }
        WingRole.AILERON_RIGHT -> if (config.wingType == WingType.ELEVON) R.string.fn_role_elevon_right else R.string.fn_role_aileron_right
        WingRole.FLAP_LEFT -> R.string.fn_role_flap_left
        WingRole.FLAP_RIGHT -> R.string.fn_role_flap_right
        WingRole.TIP_LEFT -> R.string.fn_role_tip_left
        WingRole.TIP_RIGHT -> R.string.fn_role_tip_right
        WingRole.ELEVATOR -> if (config.tailType == TailType.V_TAIL) R.string.fn_role_vtail_left else R.string.fn_role_elevator
        WingRole.RUDDER -> if (config.tailType == TailType.V_TAIL) R.string.fn_role_vtail_right else R.string.fn_role_rudder
        WingRole.THROTTLE -> R.string.fn_role_throttle
    },
)

@Composable
private fun LayoutPage(draft: WingConfig, edit: ((WingConfig) -> WingConfig) -> Unit, onAutoAssign: () -> Unit) {
    FormCard(title = stringResource(R.string.fn_wing_layout)) {
        ControlFlow {
            Caption(stringResource(R.string.fn_wing_wing))
            val wings = WingType.entries
            LabelDropdown(
                labels = wings.map { wingTypeLabel(it) },
                selectedIndex = wings.indexOf(draft.wingType),
                onSelected = { index -> edit { it.copy(wingType = wings[index]) } },
                width = 250.dp,
            )
            Caption(stringResource(R.string.fn_wing_tail))
            val tails = TailType.entries
            LabelDropdown(
                labels = tails.map { tailTypeLabel(it) },
                selectedIndex = tails.indexOf(draft.tailType),
                onSelected = { index -> edit { it.copy(tailType = tails[index]) } },
                width = 170.dp,
            )
        }
    }
    FormCard(
        title = stringResource(R.string.fn_wing_channels),
        trailing = {
            AxButton(
                text = stringResource(R.string.fn_wing_auto_assign),
                onClick = onAutoAssign,
                variant = AxButtonVariant.Text,
                icon = AxIcons.Restore,
                height = 36.dp,
            )
        },
    ) {
        val roles = WingEdits.requiredRoles(draft) + WingRole.THROTTLE
        if (roles.size == 1) {
            Caption(stringResource(R.string.fn_wing_no_channels))
        }
        ControlFlow {
            roles.forEach { role ->
                Row(
                    modifier = Modifier.width(264.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = roleLabel(role, draft),
                        style = AxTheme.typography.body,
                        color = AxTheme.colors.text,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    ChannelField(
                        selected = role.get(draft.channels),
                        onSelected = { channel -> edit { it.copy(channels = role.with(it.channels, channel)) } },
                        allowNone = true,
                    )
                }
            }
        }
        Caption(stringResource(R.string.fn_wing_direction_note))
    }
}

// -- Flaps ----------------------------------------------------------------------------------------

@Composable
private fun PercentBox(
    value: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    min: Int = -100,
    max: Int = 100,
    title: String? = null,
    defaultValue: Int? = 0,
    width: Dp = NumberWidth,
) {
    PercentField(
        value = value,
        onValueChange = onChange,
        modifier = modifier.width(width),
        min = min,
        max = max,
        title = title,
        defaultValue = defaultValue,
    )
}

@Composable
private fun FlapsPage(draft: WingConfig, pickers: PickerData, edit: ((WingConfig) -> WingConfig) -> Unit) {
    val flaps = draft.flaps
    FormCard(title = stringResource(R.string.fn_flaps_control)) {
        if (!WingEdits.hasFlapServos(draft)) {
            Caption(stringResource(R.string.fn_flaps_no_servos))
        }
        val kinds = FlapKind.entries
        AxSegmented(
            options = listOf(
                stringResource(R.string.fn_flaps_none),
                stringResource(R.string.fn_flaps_switch),
                stringResource(R.string.fn_flaps_slider),
            ),
            selectedIndex = kinds.indexOf(WingEdits.flapKind(draft)),
            onSelected = { index ->
                edit {
                    WingEdits.setFlapKind(
                        it,
                        kinds[index],
                        switch = pickers.switches.firstOrNull()?.id ?: SourceId.SB,
                        slider = pickers.knobs.firstOrNull()?.id ?: SourceId.slider(1),
                    )
                }
            },
            modifier = Modifier.width(360.dp),
        )
        when (val control = flaps.control) {
            FlapControl.None -> Unit
            is FlapControl.OnSwitch -> SwitchFlaps(control, pickers) { new -> edit { it.copy(flaps = it.flaps.copy(control = new)) } }
            is FlapControl.OnSlider -> SliderFlaps(control, pickers) { new -> edit { it.copy(flaps = it.flaps.copy(control = new)) } }
        }
        if (flaps.control != FlapControl.None) {
            FormRow(label = stringResource(R.string.fn_flaps_speed), description = stringResource(R.string.fn_flaps_speed_help)) {
                NumberField(
                    value = flaps.speedMs / 100,
                    onValueChange = { v -> edit { it.copy(flaps = it.flaps.copy(speedMs = v * 100)) } },
                    min = 0,
                    max = 300,
                    decimals = 1,
                    unit = " s",
                    defaultValue = 0,
                    title = stringResource(R.string.fn_flaps_speed),
                    modifier = Modifier.width(NumberWidth),
                )
            }
            FormRow(label = stringResource(R.string.fn_flaps_follow), description = stringResource(R.string.fn_flaps_follow_help)) {
                PercentBox(
                    value = flaps.aileronFollow,
                    onChange = { v -> edit { it.copy(flaps = it.flaps.copy(aileronFollow = v)) } },
                    title = stringResource(R.string.fn_flaps_follow),
                )
            }
        }
    }
}

@Composable
private fun SwitchFlaps(control: FlapControl.OnSwitch, pickers: PickerData, onChange: (FlapControl) -> Unit) {
    ControlFlow {
        Caption(stringResource(R.string.fn_flaps_switch_source))
        SourceField(selected = control.source, options = pickers.switches, onSelected = { onChange(control.copy(source = it)) })
    }
    PositionHeader()
    PositionRow(
        label = stringResource(R.string.fn_flaps_deflection),
        up = control.deflectionUp,
        mid = control.deflectionMid,
        down = control.deflectionDown,
        onUp = { onChange(control.copy(deflectionUp = it)) },
        onMid = { onChange(control.copy(deflectionMid = it)) },
        onDown = { onChange(control.copy(deflectionDown = it)) },
    )
    PositionRow(
        label = stringResource(R.string.fn_flaps_elevator),
        up = control.elevatorUp,
        mid = control.elevatorMid,
        down = control.elevatorDown,
        onUp = { onChange(control.copy(elevatorUp = it)) },
        onMid = { onChange(control.copy(elevatorMid = it)) },
        onDown = { onChange(control.copy(elevatorDown = it)) },
    )
}

@Composable
private fun PositionHeader() {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.weight(1f))
        listOf(R.string.fn_pos_up, R.string.fn_pos_mid, R.string.fn_pos_down).forEach { res ->
            Text(
                text = stringResource(res),
                style = AxTheme.typography.label,
                color = AxTheme.colors.textMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(NumberWidth + 8.dp),
            )
        }
    }
}

@Composable
private fun PositionRow(
    label: String,
    up: Int,
    mid: Int,
    down: Int,
    onUp: (Int) -> Unit,
    onMid: (Int) -> Unit,
    onDown: (Int) -> Unit,
) {
    FormRow(label = label) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PercentBox(up, onUp, title = label)
            PercentBox(mid, onMid, title = label)
            PercentBox(down, onDown, title = label)
        }
    }
}

@Composable
private fun SliderFlaps(control: FlapControl.OnSlider, pickers: PickerData, onChange: (FlapControl) -> Unit) {
    ControlFlow {
        Caption(stringResource(R.string.fn_flaps_slider_source))
        SourceField(selected = control.source, options = pickers.knobs, onSelected = { onChange(control.copy(source = it)) })
        Caption(stringResource(R.string.fn_reversed))
        AxSwitch(checked = control.reversed, onCheckedChange = { onChange(control.copy(reversed = it)) })
    }
    FormRow(label = stringResource(R.string.fn_flaps_deflection_max)) {
        PercentBox(control.deflectionMax, { onChange(control.copy(deflectionMax = it)) }, title = stringResource(R.string.fn_flaps_deflection_max))
    }
    FormRow(label = stringResource(R.string.fn_flaps_elevator_max)) {
        PercentBox(control.elevatorMax, { onChange(control.copy(elevatorMax = it)) }, title = stringResource(R.string.fn_flaps_elevator_max))
    }
}

// -- Butterfly ------------------------------------------------------------------------------------

@Composable
private fun ButterflyPage(draft: WingConfig, pickers: PickerData, edit: ((WingConfig) -> WingConfig) -> Unit) {
    val butterfly = draft.butterfly
    fun editButterfly(transform: (ButterflyConfig) -> ButterflyConfig) =
        edit { it.copy(butterfly = transform(it.butterfly)) }
    FormCard(title = stringResource(R.string.fn_bfly_title)) {
        FormRow(label = stringResource(R.string.fn_bfly_use), description = stringResource(R.string.fn_bfly_use_help)) {
            AxSwitch(checked = butterfly.enabled, onCheckedChange = { value -> editButterfly { it.copy(enabled = value) } })
        }
        if (butterfly.enabled) {
            ControlFlow {
                Caption(stringResource(R.string.fn_bfly_source))
                SourceField(
                    selected = butterfly.source,
                    options = pickers.analogSources,
                    onSelected = { source -> editButterfly { it.copy(source = source) } },
                )
                Caption(stringResource(R.string.fn_reversed))
                AxSwitch(checked = butterfly.reversed, onCheckedChange = { value -> editButterfly { it.copy(reversed = value) } })
                Caption(stringResource(R.string.fn_bfly_deadband))
                PercentBox(
                    butterfly.deadband,
                    { value -> editButterfly { it.copy(deadband = value) } },
                    min = 0,
                    max = 50,
                    title = stringResource(R.string.fn_bfly_deadband),
                )
            }
            ControlFlow {
                Caption(stringResource(R.string.fn_bfly_flaps))
                PercentBox(butterfly.flapDown, { value -> editButterfly { it.copy(flapDown = value) } }, title = stringResource(R.string.fn_bfly_flaps))
                Caption(stringResource(R.string.fn_bfly_ailerons))
                PercentBox(butterfly.aileronUp, { value -> editButterfly { it.copy(aileronUp = value) } }, title = stringResource(R.string.fn_bfly_ailerons))
                if (WingEdits.hasTips(draft)) {
                    Caption(stringResource(R.string.fn_bfly_tips))
                    PercentBox(butterfly.tipUp, { value -> editButterfly { it.copy(tipUp = value) } }, title = stringResource(R.string.fn_bfly_tips))
                }
            }
        }
    }
    if (butterfly.enabled) {
        FormCard(title = stringResource(R.string.fn_bfly_compensation)) {
            val points = WingEdits.compensationPoints(butterfly)
            FormRow(label = stringResource(R.string.fn_bfly_comp_use), description = stringResource(R.string.fn_bfly_comp_help)) {
                AxSwitch(
                    checked = points.isNotEmpty(),
                    onCheckedChange = { on ->
                        editButterfly { it.copy(elevatorCompensation = if (on) List(WingEdits.COMPENSATION_POINTS) { 0 } else emptyList()) }
                    },
                )
            }
            if (points.isNotEmpty()) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    points.forEachIndexed { index, value ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                            Caption(stringResource(R.string.fn_bfly_brake_at, index * 100 / (WingEdits.COMPENSATION_POINTS - 1)))
                            PercentBox(
                                value = value,
                                onChange = { v -> editButterfly { WingEdits.setCompensation(it, index, v) } },
                                title = stringResource(R.string.fn_bfly_compensation),
                                width = 96.dp,
                            )
                        }
                    }
                }
            }
        }
        FormCard(title = stringResource(R.string.fn_bfly_gate)) {
            val gate = butterfly.activeWhen
            FormRow(label = stringResource(R.string.fn_bfly_gate_use), description = stringResource(R.string.fn_bfly_gate_help)) {
                AxSwitch(
                    checked = gate.isEnabled,
                    onCheckedChange = { on ->
                        editButterfly {
                            it.copy(
                                activeWhen = if (on) {
                                    SwitchCondition(pickers.switches.firstOrNull()?.id ?: SourceId.SA, CompareOp.EQUAL, 100)
                                } else {
                                    SwitchCondition.NONE
                                },
                            )
                        }
                    },
                )
            }
            if (gate.isEnabled) {
                ControlFlow {
                    SwitchConditionFields(
                        condition = gate,
                        onChange = { value -> editButterfly { it.copy(activeWhen = value) } },
                        data = pickers,
                    )
                }
            }
        }
    }
}

// -- Mixing ---------------------------------------------------------------------------------------

@Composable
private fun MixingPage(draft: WingConfig, edit: ((WingConfig) -> WingConfig) -> Unit) {
    var shown = false
    if (WingEdits.hasTwoAilerons(draft)) {
        shown = true
        FormCard(title = stringResource(R.string.fn_mix_ailerons)) {
            FormRow(label = stringResource(R.string.fn_mix_differential), description = stringResource(R.string.fn_mix_differential_help)) {
                PercentBox(draft.aileronDifferential, { v -> edit { it.copy(aileronDifferential = v) } }, title = stringResource(R.string.fn_mix_differential))
            }
        }
    }
    if (draft.tailType != TailType.NONE) {
        shown = true
        FormCard(title = stringResource(R.string.fn_mix_tail)) {
            FormRow(label = stringResource(R.string.fn_mix_combi), description = stringResource(R.string.fn_mix_combi_help)) {
                PercentBox(draft.aileronToRudder, { v -> edit { it.copy(aileronToRudder = v) } }, title = stringResource(R.string.fn_mix_combi))
            }
            if (draft.tailType == TailType.V_TAIL) {
                FormRow(label = stringResource(R.string.fn_mix_vtail_elevator)) {
                    PercentBox(draft.vTailElevatorWeight, { v -> edit { it.copy(vTailElevatorWeight = v) } }, title = stringResource(R.string.fn_mix_vtail_elevator), defaultValue = 50)
                }
                FormRow(label = stringResource(R.string.fn_mix_vtail_rudder)) {
                    PercentBox(draft.vTailRudderWeight, { v -> edit { it.copy(vTailRudderWeight = v) } }, title = stringResource(R.string.fn_mix_vtail_rudder), defaultValue = 50)
                }
            }
        }
    }
    if (draft.wingType == WingType.ELEVON) {
        shown = true
        FormCard(title = stringResource(R.string.fn_mix_elevon)) {
            FormRow(label = stringResource(R.string.fn_mix_elevon_aileron)) {
                PercentBox(draft.elevonAileronWeight, { v -> edit { it.copy(elevonAileronWeight = v) } }, title = stringResource(R.string.fn_mix_elevon_aileron), defaultValue = 50)
            }
            FormRow(label = stringResource(R.string.fn_mix_elevon_elevator)) {
                PercentBox(draft.elevonElevatorWeight, { v -> edit { it.copy(elevonElevatorWeight = v) } }, title = stringResource(R.string.fn_mix_elevon_elevator), defaultValue = 50)
            }
        }
    }
    if (!shown) {
        Caption(stringResource(R.string.fn_mix_nothing))
    }
}
