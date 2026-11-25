package org.openax12.feature.modelsetup.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.openax12.core.designsystem.AxButton
import org.openax12.core.designsystem.AxButtonVariant
import org.openax12.core.designsystem.AxDialog
import org.openax12.core.designsystem.AxDimens
import org.openax12.core.designsystem.AxEmptyState
import org.openax12.core.designsystem.AxIcons
import org.openax12.core.designsystem.AxSegmented
import org.openax12.core.designsystem.AxTheme
import org.openax12.core.designsystem.AxValueBox
import org.openax12.core.designsystem.NumberField
import org.openax12.core.designsystem.PickerDialog
import org.openax12.core.designsystem.PickerOption
import org.openax12.core.designsystem.axRaised
import org.openax12.core.model.CURVE_COUNT
import org.openax12.core.model.CompareOp
import org.openax12.core.model.CurveConfig
import org.openax12.core.model.CurveType
import org.openax12.core.model.NO_CURVE
import org.openax12.core.model.PERCENT_MAX
import org.openax12.core.model.PERCENT_MIN
import org.openax12.core.model.SourceId
import org.openax12.core.model.SourceKind
import org.openax12.core.model.SwitchCondition
import org.openax12.core.model.SwitchPosition
import org.openax12.core.model.WorkingConfig
import org.openax12.core.engine.CurveMath
import org.openax12.feature.modelsetup.R
import org.openax12.feature.modelsetup.logic.ConditionText
import org.openax12.feature.modelsetup.logic.ManagedKind
import org.openax12.feature.modelsetup.logic.conditionFor
import org.openax12.feature.modelsetup.logic.describe

/** Sizes shared by the rows of all tabs. */
internal object SetupDims {
    /** Height of a list row. */
    val Row: Dp = 52.dp

    /** Height of the controls inside a row (touch target). */
    val Field: Dp = 44.dp

    /** Gap between the cells of a row. */
    val Gap: Dp = 6.dp

    /** Horizontal padding of the list inside the tab. */
    val Edge: Dp = 4.dp

    /** Horizontal padding inside a row / the header, so that header cells line up with row cells. */
    val Inner: Dp = 8.dp
}

/**
 * The dark column-header row under the tab row, drawn by the tab itself (the model-setup chrome
 * does not know the columns). Same look as the chrome's own header row; put
 * `SetupHeaderCell`s with the width of the column below into it.
 */
@Composable
internal fun SetupColumnHeaderRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val colors = AxTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = SetupDims.Edge, end = SetupDims.Edge, top = 3.dp)
            .height(AxDimens.SetupHeaderHeight)
            .drawWithCache {
                val brush = Brush.verticalGradient(listOf(colors.chromeRaised, colors.chrome))
                val r = CornerRadius(5.dp.toPx())
                onDrawBehind { drawRoundRect(brush, cornerRadius = r) }
            }
            .padding(horizontal = SetupDims.Inner),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides colors.onChrome) { content() }
    }
}

/** A raised card for one list row, optionally with the accent border of the expanded / selected row. */
@Composable
internal fun SetupRowCard(
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = AxTheme.colors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = SetupDims.Edge, vertical = 3.dp)
            .axRaised(cornerRadius = 10.dp, elevation = 3.dp, color = colors.surface)
            .drawBehind {
                if (highlighted) {
                    val b = 1.5.dp.toPx()
                    drawRoundRect(
                        color = colors.outlineStrong,
                        topLeft = Offset(b / 2f, b / 2f),
                        size = Size(size.width - b, size.height - b),
                        cornerRadius = CornerRadius(10.dp.toPx()),
                        style = Stroke(width = b),
                    )
                }
            },
    ) {
        content()
    }
}

/** Shown by every tab while the radio has not delivered a configuration (not connected). */
@Composable
internal fun SetupNotReady(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AxEmptyState(
            title = stringResource(R.string.setup_not_ready_title),
            message = stringResource(R.string.setup_not_ready_message),
            icon = AxIcons.LinkOff,
        )
    }
}

/** The "managed" badge of a generated item. */
@Composable
internal fun ManagedBadge(kind: ManagedKind, modifier: Modifier = Modifier) {
    val colors = AxTheme.colors
    Text(
        text = stringResource(
            when (kind) {
                ManagedKind.FLIGHT_MODES -> R.string.managed_flight_modes
                ManagedKind.WING -> R.string.managed_wing
                ManagedKind.TEMPLATE -> R.string.managed_template
                ManagedKind.OTHER -> R.string.managed_other
            },
        ),
        style = AxTheme.typography.labelSmall,
        color = colors.onAccent,
        maxLines = 1,
        modifier = modifier
            .background(colors.teal, RoundedCornerShape(50))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

// ---------------------------------------------------------------------------------------------
// Texts
// ---------------------------------------------------------------------------------------------

/** The wording of a switch condition: `SA down`, `Thr > -90%`, or an em dash for none. */
@Composable
internal fun conditionLabel(condition: SwitchCondition, config: WorkingConfig): String =
    when (val d = condition.describe()) {
        ConditionText.Off -> stringResource(R.string.cond_off)
        is ConditionText.SwitchAt -> stringResource(
            when (d.position) {
                SwitchPosition.UP -> R.string.cond_switch_up
                SwitchPosition.MID -> R.string.cond_switch_mid
                SwitchPosition.DOWN -> R.string.cond_switch_down
            },
            config.sourceName(d.source),
        )
        is ConditionText.Compare -> stringResource(R.string.cond_compare, config.sourceName(d.source), opSymbol(d.op), d.value)
    }

@Composable
private fun opSymbol(op: CompareOp): String = stringResource(
    when (op) {
        CompareOp.GREATER -> R.string.op_greater
        CompareOp.LESS -> R.string.op_less
        else -> R.string.op_equal
    },
)

/** `CH01` … `CH16` for a 0-based channel index. */
internal fun channelLabel(channel: Int): String = "CH%02d".format(channel + 1)

/** `C1` … `C18` for a 0-based curve index, an em dash for no curve. */
@Composable
internal fun curveLabel(curve: Int): String =
    if (curve == NO_CURVE) stringResource(R.string.cond_off) else stringResource(R.string.curve_short, curve + 1)

/** The name of a curve type. */
@Composable
internal fun curveTypeLabel(type: CurveType): String = stringResource(
    when (type) {
        CurveType.NONE -> R.string.curve_type_none
        CurveType.DIFF -> R.string.curve_type_diff
        CurveType.EXPO -> R.string.curve_type_expo
        CurveType.FUNC -> R.string.curve_type_func
        CurveType.CUSTOM -> R.string.curve_type_custom
    },
)

/** One line about a curve: `Expo 30%`, `Function |x|`, `Custom · 5 points`, `Free`. */
@Composable
internal fun curveSummary(curve: CurveConfig): String = when (curve.type) {
    CurveType.NONE -> stringResource(R.string.curve_free)
    CurveType.DIFF, CurveType.EXPO -> stringResource(R.string.curve_summary_param, curveTypeLabel(curve.type), curve.parameter)
    CurveType.FUNC -> stringResource(R.string.curve_summary_func, curveTypeLabel(curve.type), funcName(curve.parameter))
    CurveType.CUSTOM -> stringResource(R.string.curve_summary_custom, curve.pointCount)
}

/** Names of the built-in curve functions (`CurveMath.FUNC_*`). */
@Composable
internal fun funcName(number: Int): String = stringResource(
    when (number) {
        CurveMath.FUNC_POSITIVE_HALF -> R.string.func_positive_half
        CurveMath.FUNC_NEGATIVE_HALF -> R.string.func_negative_half
        CurveMath.FUNC_ABS -> R.string.func_abs
        CurveMath.FUNC_STEP_POSITIVE -> R.string.func_step_positive
        CurveMath.FUNC_STEP_NEGATIVE -> R.string.func_step_negative
        CurveMath.FUNC_SIGN -> R.string.func_sign
        else -> R.string.func_unknown
    },
)

// ---------------------------------------------------------------------------------------------
// Pickers
// ---------------------------------------------------------------------------------------------

/**
 * Picker of an input source, grouped by kind (sticks, pots, sliders, switches, counters). Only
 * sources the radio reports as enabled are offered; [disabled] ones are greyed out.
 *
 * @param kinds the kinds to offer (a switch condition may use any, a mix input too)
 */
@Composable
internal fun SourcePickerDialog(
    config: WorkingConfig,
    title: String,
    selected: SourceId?,
    onPick: (SourceId) -> Unit,
    onDismiss: () -> Unit,
    disabled: Set<SourceId> = emptySet(),
    kinds: Set<SourceKind> = SourceKind.entries.toSet(),
) {
    val groupNames = SourceKind.entries.associateWith { stringResource(sourceKindRes(it)) }
    val options = remember(config.sources, disabled, kinds, groupNames) {
        val ids = config.sources.filter { it.enabled }.map { it.id }.ifEmpty { SourceId.all }
        ids.filter { it.kind in kinds }.map { id ->
            PickerOption(
                value = id,
                label = config.sourceName(id),
                group = groupNames.getValue(id.kind),
                enabled = id !in disabled,
            )
        }
    }
    PickerDialog(
        title = title,
        options = options,
        selected = selected,
        onSelect = onPick,
        onDismiss = onDismiss,
        columns = 6,
        searchable = false,
        width = 640.dp,
    )
}

/** Name of a source kind (group chip, row caption). */
internal fun sourceKindRes(kind: SourceKind): Int = when (kind) {
    SourceKind.STICK -> R.string.source_kind_stick
    SourceKind.POT -> R.string.source_kind_pot
    SourceKind.SLIDER -> R.string.source_kind_slider
    SourceKind.SWITCH -> R.string.source_kind_switch
    SourceKind.COUNTER -> R.string.source_kind_counter
}

/**
 * Picker of a curve: "no curve" and the 18 curves with their summary. Free curves are offered
 * too (a mix on a free curve passes its value unchanged) and marked as such.
 */
@Composable
internal fun CurvePickerDialog(
    config: WorkingConfig,
    title: String,
    selected: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
    allowNone: Boolean = true,
) {
    val none = stringResource(R.string.curve_none_option)
    val labels = (0 until CURVE_COUNT).map { stringResource(R.string.curve_short, it + 1) }
    val summaries = (0 until CURVE_COUNT).map { curveSummary(config.curve(it) ?: CurveConfig.empty(it)) }
    val options = buildList {
        if (allowNone) add(PickerOption(NO_CURVE, none))
        for (i in 0 until CURVE_COUNT) add(PickerOption(i, labels[i], description = summaries[i]))
    }
    PickerDialog(
        title = title,
        options = options,
        selected = selected,
        onSelect = onPick,
        onDismiss = onDismiss,
        columns = 3,
        searchable = false,
        width = 600.dp,
    )
}

/**
 * Editor of a switch condition: source, comparison and value. A switch source with "=" offers
 * its three positions; anything else a percentage.
 *
 * @param initial the current condition
 * @param allowOff offers "Off" (no condition)
 * @param onConfirm called with the edited condition
 */
@Composable
internal fun SwitchConditionDialog(
    title: String,
    initial: SwitchCondition,
    config: WorkingConfig,
    onConfirm: (SwitchCondition) -> Unit,
    onDismiss: () -> Unit,
    allowOff: Boolean = true,
) {
    var condition by remember { mutableStateOf(initial) }
    var picking by remember { mutableStateOf(false) }
    if (picking) {
        SourcePickerDialog(
            config = config,
            title = stringResource(R.string.cond_pick_source),
            selected = condition.source,
            onPick = { id ->
                val op = if (condition.isEnabled) condition.op else CompareOp.EQUAL
                condition = conditionFor(id, op, condition)
                picking = false
            },
            onDismiss = { picking = false },
        )
        return
    }
    val ops = if (allowOff) listOf(CompareOp.DISABLED, CompareOp.GREATER, CompareOp.EQUAL, CompareOp.LESS)
    else listOf(CompareOp.GREATER, CompareOp.EQUAL, CompareOp.LESS)
    val opLabels = ops.map {
        when (it) {
            CompareOp.DISABLED -> stringResource(R.string.cond_op_off)
            CompareOp.GREATER -> stringResource(R.string.op_greater)
            CompareOp.EQUAL -> stringResource(R.string.op_equal)
            CompareOp.LESS -> stringResource(R.string.op_less)
        }
    }
    AxDialog(
        onDismissRequest = onDismiss,
        title = title,
        width = 480.dp,
        buttons = {
            AxButton(text = stringResource(org.openax12.core.designsystem.R.string.ax_cancel), onClick = onDismiss, variant = AxButtonVariant.Text)
            AxButton(
                text = stringResource(org.openax12.core.designsystem.R.string.ax_ok),
                onClick = { onConfirm(condition) },
                variant = AxButtonVariant.Primary,
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            DialogRow(stringResource(R.string.cond_source)) {
                AxValueBox(
                    text = config.sourceName(condition.source),
                    onClick = { picking = true },
                    trailingIcon = AxIcons.ExpandMore,
                    modifier = Modifier.width(150.dp),
                    height = SetupDims.Field,
                )
            }
            DialogRow(stringResource(R.string.cond_compare_label)) {
                AxSegmented(
                    options = opLabels,
                    selectedIndex = ops.indexOf(condition.op).coerceAtLeast(0),
                    onSelected = { condition = conditionFor(condition.source, ops[it], condition) },
                    modifier = Modifier.width(260.dp),
                )
            }
            if (condition.isEnabled) {
                DialogRow(stringResource(R.string.cond_value)) {
                    if (condition.source.isSwitch && condition.op == CompareOp.EQUAL) {
                        val positions = listOf(SwitchPosition.UP, SwitchPosition.MID, SwitchPosition.DOWN)
                        AxSegmented(
                            options = listOf(
                                stringResource(R.string.position_up),
                                stringResource(R.string.position_mid),
                                stringResource(R.string.position_down),
                            ),
                            selectedIndex = positions.indexOf(SwitchPosition.fromLogic(condition.value)),
                            onSelected = { condition = condition.copy(value = positions[it].logic) },
                            modifier = Modifier.width(260.dp),
                        )
                    } else {
                        NumberField(
                            value = condition.value,
                            onValueChange = { condition = condition.copy(value = it) },
                            min = PERCENT_MIN,
                            max = PERCENT_MAX,
                            unit = "%",
                            title = stringResource(R.string.cond_value),
                            defaultValue = 0,
                            height = SetupDims.Field,
                            modifier = Modifier.width(110.dp),
                        )
                    }
                }
            }
        }
    }
}

/** A label on the left, a control on the right: one line of a dialog. */
@Composable
internal fun DialogRow(label: String, modifier: Modifier = Modifier, control: @Composable RowScope.() -> Unit) {
    Row(
        modifier = modifier.fillMaxWidth().height(SetupDims.Field),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = AxTheme.typography.body,
            color = AxTheme.colors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        control()
    }
}

/** A value box showing a switch condition; opens the editor via [onClick]. */
@Composable
internal fun ConditionBox(
    condition: SwitchCondition,
    config: WorkingConfig,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    AxValueBox(
        text = conditionLabel(condition, config),
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        height = SetupDims.Field,
    )
}
