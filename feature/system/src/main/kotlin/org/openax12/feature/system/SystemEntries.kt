package org.openax12.feature.system

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import org.openax12.core.navigation.Backup
import org.openax12.core.navigation.Calibration
import org.openax12.core.navigation.Diagnostics
import org.openax12.core.navigation.ElrsMenu
import org.openax12.core.navigation.Help
import org.openax12.core.navigation.PreFlightCheck
import org.openax12.core.navigation.SimulatorPanel
import org.openax12.core.navigation.SystemSettings
import org.openax12.feature.system.backup.BackupRoute
import org.openax12.feature.system.calibration.CalibrationRoute
import org.openax12.feature.system.diagnostics.DiagnosticsRoute
import org.openax12.feature.system.elrs.ElrsRoute
import org.openax12.feature.system.help.HelpRoute
import org.openax12.feature.system.preflight.PreFlightRoute
import org.openax12.feature.system.settings.SystemSettingsRoute
import org.openax12.feature.system.simulator.SimulatorRoute

/**
 * Installs the entries of `:feature:system` (see `FeatureEntries.kt` in `:core:navigation`):
 * [SystemSettings], [ElrsMenu], [Calibration], [PreFlightCheck], [Diagnostics], [SimulatorPanel],
 * [Backup] and [Help]. The status bar and the bottom bar belong to the app shell.
 */
fun EntryProviderScope<NavKey>.systemEntries() {
    entry<SystemSettings> { SystemSettingsRoute() }
    entry<ElrsMenu> { ElrsRoute() }
    entry<Calibration> { CalibrationRoute() }
    entry<PreFlightCheck> { PreFlightRoute() }
    entry<Diagnostics> { DiagnosticsRoute() }
    entry<SimulatorPanel> { SimulatorRoute() }
    entry<Backup> { BackupRoute() }
    entry<Help> { HelpRoute() }
}
