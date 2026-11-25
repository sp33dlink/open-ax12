package org.openax12.feature.functions

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import org.openax12.core.navigation.LogicalSwitchEditor
import org.openax12.core.navigation.SetupTab
import org.openax12.core.navigation.SetupTabIds
import org.openax12.core.navigation.SpecialFunctionEditor
import org.openax12.core.navigation.VoiceAlertEditor

/**
 * Installs the full-screen editors of this module: [LogicalSwitchEditor], [SpecialFunctionEditor]
 * and [VoiceAlertEditor]. The app shell calls this inside its `entryProvider { }`.
 */
fun EntryProviderScope<NavKey>.functionsEntries() {
    entry<LogicalSwitchEditor> { key -> LogicalSwitchEditorEntry(key.index) }
    entry<SpecialFunctionEditor> { key -> SpecialFunctionEditorEntry(key.index) }
    entry<VoiceAlertEditor> { key -> VoiceAlertEditorEntry(key.alertId) }
}

/**
 * The model-setup tabs of this module, to be merged with the tabs of `:feature:modelsetup` and
 * sorted by [SetupTab.order]: flight modes, flaps / wing, logical switches, special functions and
 * sounds.
 */
fun functionsSetupTabs(): List<SetupTab> = listOf(
    SetupTab(SetupTabIds.FLIGHT_MODES, R.string.fn_tab_flight_modes, SetupTabIds.orderOf(SetupTabIds.FLIGHT_MODES)) { FlightModesTab() },
    SetupTab(SetupTabIds.WING, R.string.fn_tab_wing, SetupTabIds.orderOf(SetupTabIds.WING)) { WingTab() },
    SetupTab(SetupTabIds.LOGICAL_SWITCHES, R.string.fn_tab_logical_switches, SetupTabIds.orderOf(SetupTabIds.LOGICAL_SWITCHES)) { LogicalSwitchesTab() },
    SetupTab(SetupTabIds.SPECIAL_FUNCTIONS, R.string.fn_tab_special_functions, SetupTabIds.orderOf(SetupTabIds.SPECIAL_FUNCTIONS)) { SpecialFunctionsTab() },
    SetupTab(SetupTabIds.SOUNDS, R.string.fn_tab_sounds, SetupTabIds.orderOf(SetupTabIds.SOUNDS)) { SoundsTab() },
)
