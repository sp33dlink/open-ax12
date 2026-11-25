package org.openax12.feature.system.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.openax12.core.designsystem.AxButton
import org.openax12.core.designsystem.AxButtonVariant
import org.openax12.core.designsystem.AxDivider
import org.openax12.core.designsystem.AxIcons
import org.openax12.core.designsystem.AxListRow
import org.openax12.core.designsystem.AxSettingRow
import org.openax12.core.designsystem.AxTheme
import org.openax12.core.model.RadioBackend
import org.openax12.core.navigation.Backup
import org.openax12.core.navigation.Diagnostics
import org.openax12.core.navigation.Help
import org.openax12.core.navigation.SimulatorPanel
import org.openax12.core.radio.ConnectionState
import org.openax12.feature.system.R
import org.openax12.feature.system.common.NoticeBox
import org.openax12.feature.system.common.NoticeKind
import org.openax12.feature.system.common.PanelColumn
import org.openax12.feature.system.common.SettingsGroup
import org.openax12.feature.system.common.ValueText
import org.openax12.feature.system.common.formatBytes
import org.openax12.feature.system.common.formatDuration
import org.openax12.feature.system.common.formatFirmwareVersion
import org.openax12.feature.system.common.formatHardwareVersion

/** Storage category: where the library lives, permission, free space, backup. */
@Composable
internal fun StoragePanel(state: SettingsUiState, onEvent: (SettingsEvent) -> Unit) {
    val storage = state.storage
    PanelColumn {
        if (!storage.permissionGranted) {
            NoticeBox(
                text = stringResource(R.string.storage_permission_missing),
                kind = NoticeKind.Warning,
                actionLabel = stringResource(R.string.storage_grant),
                onAction = { onEvent(SettingsEvent.RequestStoragePermission) },
            )
        }
        SettingsGroup(stringResource(R.string.group_library)) {
            val none = stringResource(R.string.value_unknown)
            InfoRow(stringResource(R.string.storage_root), storage.libraryRoot.ifEmpty { none })
            AxDivider()
            InfoRow(stringResource(R.string.storage_models), storage.modelsDir.ifEmpty { none })
            AxDivider()
            InfoRow(stringResource(R.string.storage_sounds), storage.soundsDir.ifEmpty { none })
            AxDivider()
            InfoRow(stringResource(R.string.storage_logs), storage.logsDir.ifEmpty { none })
            AxDivider()
            InfoRow(
                stringResource(R.string.storage_location),
                if (storage.usesPublicStorage) stringResource(R.string.storage_public) else stringResource(R.string.storage_private),
            )
            AxDivider()
            InfoRow(stringResource(R.string.storage_free), if (storage.isAvailable) formatBytes(storage.freeBytes) else none)
        }
        SettingsGroup(stringResource(R.string.group_backup)) {
            NavigationRow(stringResource(R.string.storage_backup), stringResource(R.string.storage_backup_desc), AxIcons.Save) {
                onEvent(SettingsEvent.Open(Backup))
            }
        }
    }
}

/** About category: versions, firmware info (read-only), links to diagnostics, help and the simulator panel. */
@Composable
internal fun AboutPanel(state: SettingsUiState, appVersion: String, onEvent: (SettingsEvent) -> Unit) {
    PanelColumn {
        NoticeBox(stringResource(R.string.about_firmware_hint), kind = NoticeKind.Info)
        SettingsGroup(stringResource(R.string.group_app)) {
            InfoRow(stringResource(R.string.about_app_version), appVersion)
            AxDivider()
            InfoRow(stringResource(R.string.about_sdk_version), state.sdkVersion.ifEmpty { stringResource(R.string.value_unknown) })
            AxDivider()
            InfoRow(
                stringResource(R.string.about_backend),
                if (state.activeBackend == RadioBackend.SIMULATOR) stringResource(R.string.backend_simulator) else stringResource(R.string.backend_sdk),
            )
            AxDivider()
            InfoRow(stringResource(R.string.about_connection), connectionText(state.connection))
        }
        SettingsGroup(stringResource(R.string.group_firmware)) {
            val info = state.firmware
            val none = stringResource(R.string.about_not_reported)
            if (info == null || info.isEmpty) {
                InfoRow(stringResource(R.string.about_firmware), none)
            } else {
                InfoRow(stringResource(R.string.about_device), info.deviceText.ifEmpty { none })
                AxDivider()
                InfoRow(
                    stringResource(R.string.about_firmware),
                    info.firmwareVersionText.ifEmpty { formatFirmwareVersion(info.firmwareVersion) },
                )
                AxDivider()
                InfoRow(stringResource(R.string.about_hardware), formatHardwareVersion(info.hardwareVersion))
                AxDivider()
                InfoRow(stringResource(R.string.about_build), info.buildTimeText.ifEmpty { info.buildDate.toString() })
                AxDivider()
                InfoRow(stringResource(R.string.about_flight_time), formatDuration(info.flightTimeSec))
            }
            AxDivider()
            AxSettingRow(title = stringResource(R.string.about_request_info), enabled = state.radioReady) {
                AxButton(
                    text = stringResource(R.string.about_request_info_button),
                    onClick = { onEvent(SettingsEvent.RequestDeviceInfo) },
                    variant = AxButtonVariant.Outlined,
                    enabled = state.radioReady,
                )
            }
        }
        SettingsGroup(stringResource(R.string.group_more)) {
            NavigationRow(stringResource(R.string.about_diagnostics), stringResource(R.string.about_diagnostics_desc), AxIcons.Diagnostics) {
                onEvent(SettingsEvent.Open(Diagnostics))
            }
            AxDivider()
            NavigationRow(stringResource(R.string.about_help), null, AxIcons.Help) { onEvent(SettingsEvent.Open(Help)) }
            if (state.activeBackend == RadioBackend.SIMULATOR) {
                AxDivider()
                NavigationRow(stringResource(R.string.about_simulator), stringResource(R.string.about_simulator_desc), AxIcons.Transmitter) {
                    onEvent(SettingsEvent.Open(SimulatorPanel))
                }
            }
        }
    }
}

/** Short connection state text. */
@Composable
internal fun connectionText(connection: ConnectionState): String = when (connection) {
    ConnectionState.Stopped -> stringResource(R.string.conn_stopped)
    ConnectionState.Initializing -> stringResource(R.string.conn_initializing)
    ConnectionState.WaitingForDevice -> stringResource(R.string.conn_waiting)
    ConnectionState.Ready -> stringResource(R.string.conn_ready)
    is ConnectionState.Error -> stringResource(R.string.conn_error_short, connection.reason.message)
}

/** Label with a read-only value at the end. */
@Composable
internal fun InfoRow(label: String, value: String) {
    AxListRow(
        title = label,
        minHeight = 40.dp,
        trailing = { ValueText(value, color = AxTheme.colors.text) },
    )
}
