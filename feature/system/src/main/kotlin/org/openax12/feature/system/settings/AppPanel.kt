package org.openax12.feature.system.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.openax12.core.designsystem.AxDivider
import org.openax12.core.designsystem.AxIcons
import org.openax12.core.designsystem.AxSegmented
import org.openax12.core.designsystem.AxSettingRow
import org.openax12.core.designsystem.AxSwitch
import org.openax12.core.designsystem.NumberField
import org.openax12.core.model.AppTheme
import org.openax12.core.model.RadioBackend
import org.openax12.core.model.UnitSystem
import org.openax12.core.navigation.FlightLogs
import org.openax12.feature.system.R
import org.openax12.feature.system.common.NoticeBox
import org.openax12.feature.system.common.NoticeKind
import org.openax12.feature.system.common.PanelColumn
import org.openax12.feature.system.common.SettingsGroup

/** Largest auto-lock delay offered, in seconds. */
internal const val AUTO_LOCK_MAX_SEC = 600

/** App category: appearance, units, screen, touch lock, radio backend, logging. */
@Composable
internal fun AppPanel(state: SettingsUiState, onEvent: (SettingsEvent) -> Unit) {
    val app = state.app
    PanelColumn {
        SettingsGroup(stringResource(R.string.group_appearance)) {
            AxSettingRow(title = stringResource(R.string.app_theme), description = stringResource(R.string.app_theme_desc), icon = AxIcons.Theme) {
                AxSegmented(
                    options = listOf(
                        stringResource(R.string.app_theme_light),
                        stringResource(R.string.app_theme_dark),
                        stringResource(R.string.app_theme_sun),
                    ),
                    selectedIndex = AppTheme.entries.indexOf(app.theme).coerceAtLeast(0),
                    onSelected = { index -> onEvent(SettingsEvent.Update { it.copy(theme = AppTheme.entries[index]) }) },
                    modifier = Modifier.width(360.dp),
                )
            }
            AxDivider()
            AxSettingRow(title = stringResource(R.string.app_units), description = stringResource(R.string.app_units_desc), icon = AxIcons.Altitude) {
                AxSegmented(
                    options = listOf(stringResource(R.string.app_units_metric), stringResource(R.string.app_units_imperial)),
                    selectedIndex = UnitSystem.entries.indexOf(app.units).coerceAtLeast(0),
                    onSelected = { index -> onEvent(SettingsEvent.Update { it.copy(units = UnitSystem.entries[index]) }) },
                    modifier = Modifier.width(260.dp),
                )
            }
            AxDivider()
            AxSettingRow(title = stringResource(R.string.app_keep_screen_on), description = stringResource(R.string.app_keep_screen_on_desc), icon = AxIcons.Visibility) {
                AxSwitch(app.keepScreenOn, { value -> onEvent(SettingsEvent.Update { it.copy(keepScreenOn = value) }) })
            }
        }

        SettingsGroup(stringResource(R.string.group_touch_lock)) {
            val lock = app.touchLock
            AxSettingRow(title = stringResource(R.string.lock_hold), description = stringResource(R.string.lock_hold_desc), icon = AxIcons.TouchLock) {
                NumberField(
                    value = lock.unlockHoldMs / 100,
                    onValueChange = { value -> onEvent(SettingsEvent.Update { it.copy(touchLock = it.touchLock.copy(unlockHoldMs = value * 100)) }) },
                    min = 5,
                    max = 50,
                    decimals = 1,
                    unit = " s",
                    title = stringResource(R.string.lock_hold),
                    steppers = true,
                    modifier = Modifier.width(180.dp),
                )
            }
            AxDivider()
            AxSettingRow(title = stringResource(R.string.lock_auto), description = stringResource(R.string.lock_auto_desc), icon = AxIcons.TouchLock) {
                val off = stringResource(R.string.value_off)
                NumberField(
                    value = lock.autoLockSec,
                    onValueChange = { value -> onEvent(SettingsEvent.Update { it.copy(touchLock = it.touchLock.copy(autoLockSec = value)) }) },
                    min = 0,
                    max = AUTO_LOCK_MAX_SEC,
                    step = 5,
                    unit = " s",
                    title = stringResource(R.string.lock_auto),
                    steppers = true,
                    format = { if (it == 0) off else "$it s" },
                    modifier = Modifier.width(180.dp),
                )
            }
            AxDivider()
            AxSettingRow(title = stringResource(R.string.lock_when_linked), description = stringResource(R.string.lock_when_linked_desc), icon = AxIcons.Link) {
                AxSwitch(lock.lockWhenLinked, { value -> onEvent(SettingsEvent.Update { it.copy(touchLock = it.touchLock.copy(lockWhenLinked = value)) }) })
            }
            AxDivider()
            AxSettingRow(title = stringResource(R.string.lock_dim), description = stringResource(R.string.lock_dim_desc), icon = AxIcons.Visibility) {
                AxSwitch(lock.dimWhileLocked, { value -> onEvent(SettingsEvent.Update { it.copy(touchLock = it.touchLock.copy(dimWhileLocked = value)) }) })
            }
        }

        SettingsGroup(stringResource(R.string.group_backend)) {
            AxSettingRow(title = stringResource(R.string.app_backend), description = stringResource(R.string.app_backend_desc), icon = AxIcons.Transmitter) {
                AxSegmented(
                    options = listOf(
                        stringResource(R.string.backend_auto),
                        stringResource(R.string.backend_sdk),
                        stringResource(R.string.backend_simulator),
                    ),
                    selectedIndex = RadioBackend.entries.indexOf(app.radioBackend).coerceAtLeast(0),
                    onSelected = { index -> onEvent(SettingsEvent.Update { it.copy(radioBackend = RadioBackend.entries[index]) }) },
                    modifier = Modifier.width(360.dp),
                )
            }
            val active = if (state.activeBackend == RadioBackend.SIMULATOR) stringResource(R.string.backend_simulator) else stringResource(R.string.backend_sdk)
            val configuredResolves = app.radioBackend == RadioBackend.AUTO || app.radioBackend == state.activeBackend
            NoticeBox(
                text = if (configuredResolves) {
                    stringResource(R.string.backend_active, active)
                } else {
                    stringResource(R.string.backend_restart, active)
                },
                modifier = Modifier.padding(4.dp),
                kind = if (configuredResolves) NoticeKind.Info else NoticeKind.Warning,
            )
        }

        SettingsGroup(stringResource(R.string.group_logging)) {
            AxSettingRow(title = stringResource(R.string.log_enabled), description = stringResource(R.string.log_enabled_desc), icon = AxIcons.History) {
                AxSwitch(app.logging, { value -> onEvent(SettingsEvent.Update { it.copy(logging = value) }) })
            }
            AxDivider()
            AxSettingRow(title = stringResource(R.string.log_interval), enabled = app.logging, icon = AxIcons.Timer) {
                NumberField(
                    value = app.logIntervalMs,
                    onValueChange = { value -> onEvent(SettingsEvent.Update { it.copy(logIntervalMs = value) }) },
                    min = 100,
                    max = 5_000,
                    step = 100,
                    unit = " ms",
                    title = stringResource(R.string.log_interval),
                    enabled = app.logging,
                    steppers = true,
                    modifier = Modifier.width(200.dp),
                )
            }
            AxDivider()
            NavigationRow(stringResource(R.string.log_open), stringResource(R.string.log_open_desc), AxIcons.Folder) {
                onEvent(SettingsEvent.Open(FlightLogs))
            }
        }
    }
}
