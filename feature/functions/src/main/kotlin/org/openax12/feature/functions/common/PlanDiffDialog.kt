package org.openax12.feature.functions.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.openax12.core.designsystem.AxButton
import org.openax12.core.designsystem.AxButtonVariant
import org.openax12.core.designsystem.AxDialog
import org.openax12.core.designsystem.AxIcons
import org.openax12.core.designsystem.AxTheme
import org.openax12.core.model.CurveType
import org.openax12.core.model.PlanConflict
import org.openax12.core.model.PlanConflictKind
import org.openax12.core.model.PlanDiff
import org.openax12.core.model.PlanOp
import org.openax12.feature.functions.R

/** Counts of what a [PlanDiff] would do to the firmware configuration. */
internal data class PlanSummary(
    val mixesAdded: Int,
    val mixesChanged: Int,
    val mixesRemoved: Int,
    val curvesWritten: Int,
    val curvesFreed: Int,
    val dualRates: Int,
    val channels: Int,
) {
    /** Nothing to do. */
    val isEmpty: Boolean
        get() = mixesAdded + mixesChanged + mixesRemoved + curvesWritten + curvesFreed + dualRates + channels == 0
}

/** Counts the operations of [diff] by kind. Timer operations are not produced by the compilers and not counted. */
internal fun summarize(diff: PlanDiff): PlanSummary {
    var added = 0
    var changed = 0
    var removed = 0
    var curves = 0
    var freed = 0
    var dualRates = 0
    var channels = 0
    for (op in diff.ops) {
        when (op) {
            is PlanOp.AddMix -> added++
            is PlanOp.UpdateMix -> changed++
            is PlanOp.RemoveMix -> removed++
            is PlanOp.SetCurve -> if (op.after.type == CurveType.NONE) freed++ else curves++
            is PlanOp.SetDualRate -> dualRates++
            is PlanOp.SetChannel -> channels++
            is PlanOp.SetTimer -> Unit
        }
    }
    return PlanSummary(added, changed, removed, curves, freed, dualRates, channels)
}

/** Localised text of a plan conflict; compiler validation problems keep their English detail. */
@Composable
internal fun conflictText(conflict: PlanConflict): String {
    val channel = conflict.channel?.let { "CH${it + 1}" } ?: "?"
    val source = conflict.source?.let { if (it.isValid) it.defaultName else "?" } ?: "?"
    return when (conflict.kind) {
        PlanConflictKind.MIX_KEY_IN_USE -> stringResource(R.string.fn_conflict_mix_key, channel, source)
        PlanConflictKind.NO_FREE_CURVE -> stringResource(R.string.fn_conflict_no_curve)
        PlanConflictKind.NO_FREE_DUAL_RATE_SLOT -> stringResource(R.string.fn_conflict_no_dr, channel)
        PlanConflictKind.INVALID_CHANNEL -> stringResource(R.string.fn_conflict_channel, channel)
        PlanConflictKind.INVALID_SOURCE -> stringResource(R.string.fn_conflict_source, source)
        PlanConflictKind.INVALID_CURVE -> stringResource(R.string.fn_conflict_curve)
        PlanConflictKind.MANAGED_ITEM_MODIFIED -> stringResource(R.string.fn_conflict_modified, channel, source)
        PlanConflictKind.MANAGED_ITEM_MISSING -> stringResource(R.string.fn_conflict_missing)
        PlanConflictKind.CURVE_SHARED_WITH_USER -> stringResource(R.string.fn_conflict_shared, (conflict.curveIndex ?: -1) + 1)
        PlanConflictKind.CHANNEL_SETTING_CHANGED -> stringResource(R.string.fn_conflict_channel_setting, channel)
        PlanConflictKind.INVALID_SETTINGS -> conflict.detail.ifBlank { stringResource(R.string.fn_conflict_invalid) }
    }
}

/**
 * The dry-run result of a compile, shown before anything is changed: what the radio configuration
 * gets, and what stands in the way. Blocking conflicts disable Apply.
 *
 * @param busy true while the plan is being applied
 * @param onApply stages the plan in the working config (it still has to be saved)
 */
@Composable
internal fun PlanDiffDialog(
    diff: PlanDiff,
    busy: Boolean,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    val summary = summarize(diff)
    AxDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = stringResource(R.string.fn_diff_title),
        icon = AxIcons.Mix,
        width = 520.dp,
        buttons = {
            AxButton(text = stringResource(R.string.fn_cancel), onClick = onDismiss, variant = AxButtonVariant.Text, enabled = !busy)
            AxButton(
                text = stringResource(if (busy) R.string.fn_applying else R.string.fn_apply),
                onClick = onApply,
                variant = AxButtonVariant.Primary,
                enabled = diff.canApply && !busy,
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 280.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (summary.isEmpty) {
                Text(text = stringResource(R.string.fn_diff_nothing), style = AxTheme.typography.body, color = AxTheme.colors.text)
            } else {
                SummaryLine(R.string.fn_diff_mixes_added, summary.mixesAdded)
                SummaryLine(R.string.fn_diff_mixes_changed, summary.mixesChanged)
                SummaryLine(R.string.fn_diff_mixes_removed, summary.mixesRemoved)
                SummaryLine(R.string.fn_diff_curves, summary.curvesWritten)
                SummaryLine(R.string.fn_diff_curves_freed, summary.curvesFreed)
                SummaryLine(R.string.fn_diff_dual_rates, summary.dualRates)
                SummaryLine(R.string.fn_diff_channels, summary.channels)
            }
            diff.conflicts.forEach { conflict ->
                InfoBanner(
                    text = conflictText(conflict),
                    tone = if (conflict.isBlocking) BannerTone.ERROR else BannerTone.WARNING,
                )
            }
            Text(
                text = stringResource(R.string.fn_diff_staged_note),
                style = AxTheme.typography.label,
                color = AxTheme.colors.textMuted,
            )
        }
    }
}

@Composable
private fun SummaryLine(textRes: Int, count: Int) {
    if (count == 0) return
    Text(
        text = stringResource(textRes, count),
        style = AxTheme.typography.body,
        color = AxTheme.colors.text,
    )
}
