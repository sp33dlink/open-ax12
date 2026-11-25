package org.openax12.feature.modelsetup

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import org.openax12.core.navigation.CurveEditor
import org.openax12.core.navigation.ModelSetup
import org.openax12.core.navigation.SetupTab
import org.openax12.core.navigation.SetupTabIds
import org.openax12.feature.modelsetup.chlock.ChLockTab
import org.openax12.feature.modelsetup.chrome.ModelSetupRoute
import org.openax12.feature.modelsetup.curves.CurveEditorRoute
import org.openax12.feature.modelsetup.curves.CurvesTab
import org.openax12.feature.modelsetup.dualrate.DualRateTab
import org.openax12.feature.modelsetup.inputs.InputCheckTab
import org.openax12.feature.modelsetup.mixes.MixesTab
import org.openax12.feature.modelsetup.output.OutputTab
import org.openax12.feature.modelsetup.timers.TimersTab

/**
 * Installs the model-setup screen and the curve editor.
 *
 * - [ModelSetup]: the chrome (back, tab row, Save with its "unsaved" dot, the "link is up"
 *   question before writing) around the selected tab of [tabs], which `:app` assembles from
 *   [modelSetupTabs] and `:feature:functions`' tabs.
 * - [CurveEditor]: the full-screen editor of one curve.
 *
 * The unsaved-changes guard and its dialog belong to the app shell.
 */
fun EntryProviderScope<NavKey>.modelSetupEntries(tabs: List<SetupTab>) {
    entry<ModelSetup> { key -> ModelSetupRoute(key, tabs) }
    entry<CurveEditor> { key -> CurveEditorRoute(key.curveIndex) }
}

/**
 * The tabs this module contributes to model setup, in [SetupTabIds] order: Input check, Output,
 * Mixes, DualRate, ChLock, Curves, Timers.
 */
fun modelSetupTabs(): List<SetupTab> = listOf(
    SetupTab(SetupTabIds.INPUT_CHECK, R.string.tab_inputs, SetupTabIds.orderOf(SetupTabIds.INPUT_CHECK)) { InputCheckTab() },
    SetupTab(SetupTabIds.OUTPUT, R.string.tab_output, SetupTabIds.orderOf(SetupTabIds.OUTPUT)) { OutputTab() },
    SetupTab(SetupTabIds.MIXES, R.string.tab_mixes, SetupTabIds.orderOf(SetupTabIds.MIXES)) { MixesTab() },
    SetupTab(SetupTabIds.DUAL_RATE, R.string.tab_dualrate, SetupTabIds.orderOf(SetupTabIds.DUAL_RATE)) { DualRateTab() },
    SetupTab(SetupTabIds.CH_LOCK, R.string.tab_chlock, SetupTabIds.orderOf(SetupTabIds.CH_LOCK)) { ChLockTab() },
    SetupTab(SetupTabIds.CURVES, R.string.tab_curves, SetupTabIds.orderOf(SetupTabIds.CURVES)) { CurvesTab() },
    SetupTab(SetupTabIds.TIMERS, R.string.tab_timers, SetupTabIds.orderOf(SetupTabIds.TIMERS)) { TimersTab() },
)
