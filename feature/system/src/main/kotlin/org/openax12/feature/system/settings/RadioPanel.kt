package org.openax12.feature.system.settings

import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.openax12.core.designsystem.AxButton
import org.openax12.core.designsystem.AxButtonVariant
import org.openax12.core.designsystem.AxDivider
import org.openax12.core.designsystem.AxIcon
import org.openax12.core.designsystem.AxIcons
import org.openax12.core.designsystem.AxListRow
import org.openax12.core.designsystem.AxSegmented
import org.openax12.core.designsystem.AxSettingRow
import org.openax12.core.designsystem.AxSwitch
import org.openax12.core.designsystem.AxTheme
import org.openax12.core.designsystem.NumberField
import org.openax12.core.model.RfModuleMode
import org.openax12.core.model.StickMode
import org.openax12.core.navigation.Calibration
import org.openax12.core.navigation.ElrsMenu
import org.openax12.core.navigation.PreFlightCheck
import org.openax12.feature.system.R
import org.openax12.feature.system.common.ConnectionNotice
import org.openax12.feature.system.common.NoticeBox
import org.openax12.feature.system.common.NoticeKind
import org.openax12.feature.system.common.PanelColumn
import org.openax12.feature.system.common.SettingsGroup

/** Radio category: stick mode, alarm voltage, RF module, calibration, ELRS, maintenance, restore defaults. */
@Composable
internal fun RadioPanel(state: SettingsUiState, onEvent: (SettingsEvent) -> Unit) {
    val system = state.system
    val editable = state.canEditDevice
    PanelColumn {
        ConnectionNotice(state.connection)
        if (state.systemUnavailable) {
            NoticeBox(stringResource(R.string.radio_system_unavailable), kind = NoticeKind.Warning)
        }
        if (state.radioReady && state.dirty) {
            NoticeBox(stringResource(R.string.radio_unsaved_notice), kind = NoticeKind.Warning)
        } else if (state.radioReady && !state.deviceInSync) {
            NoticeBox(stringResource(R.string.radio_not_in_sync_notice), kind = NoticeKind.Warning)
        }

        SettingsGroup(stringResource(R.string.group_transmitter)) {
            val mode = system?.stickMode
            AxSettingRow(
                title = stringResource(R.string.radio_stick_mode),
                description = if (mode == null) {
                    stringResource(R.string.radio_value_unavailable)
                } else {
                    stringResource(
                        R.string.radio_stick_mode_desc,
                        mode.leftVertical.defaultName,
                        mode.leftHorizontal.defaultName,
                        mode.rightVertical.defaultName,
                        mode.rightHorizontal.defaultName,
                    )
                },
                icon = AxIcons.Stick,
                enabled = editable,
            ) {
                AxSegmented(
                    options = listOf(stringResource(R.string.radio_mode_1), stringResource(R.string.radio_mode_2)),
                    selectedIndex = when (mode) {
                        null -> NOTHING_SELECTED
                        StickMode.MODE_1 -> 0
                        StickMode.MODE_2 -> 1
                    },
                    onSelected = { onEvent(SettingsEvent.Radio(RadioAction.SetStickMode(if (it == 0) StickMode.MODE_1 else StickMode.MODE_2))) },
                    enabled = editable,
                    modifier = Modifier.width(200.dp),
                )
            }
            AxDivider()
            AxSettingRow(
                title = stringResource(R.string.radio_alarm_voltage),
                description = stringResource(R.string.radio_alarm_voltage_desc),
                icon = AxIcons.Battery,
                enabled = editable,
            ) {
                if (system == null) {
                    Text(
                        text = stringResource(R.string.radio_value_unavailable),
                        style = AxTheme.typography.value,
                        color = AxTheme.colors.textMuted,
                    )
                } else {
                    NumberField(
                        value = system.alarmVoltageDeciVolt,
                        onValueChange = { onEvent(SettingsEvent.Radio(RadioAction.SetAlarmVoltage(it))) },
                        min = 0,
                        max = 150,
                        step = 1,
                        decimals = 1,
                        unit = " V",
                        title = stringResource(R.string.radio_alarm_voltage),
                        enabled = editable,
                        steppers = true,
                        modifier = Modifier.width(180.dp),
                    )
                }
            }
            AxDivider()
            AxSettingRow(
                title = stringResource(R.string.radio_rf_module),
                description = stringResource(R.string.radio_rf_module_desc),
                icon = AxIcons.Antenna,
                enabled = editable,
            ) {
                val selected = when (system?.rfModuleMode) {
                    null -> NOTHING_SELECTED
                    RfModuleMode.INTERNAL -> 0
                    RfModuleMode.EXTERNAL -> 1
                    RfModuleMode.OFF -> 2
                }
                AxSegmented(
                    options = listOf(
                        stringResource(R.string.radio_rf_internal),
                        stringResource(R.string.radio_rf_external),
                        stringResource(R.string.radio_rf_off),
                    ),
                    selectedIndex = selected,
                    onSelected = {
                        val target = when (it) {
                            0 -> RfModuleMode.INTERNAL
                            1 -> RfModuleMode.EXTERNAL
                            else -> RfModuleMode.OFF
                        }
                        onEvent(SettingsEvent.Radio(RadioAction.SetRfMode(target)))
                    },
                    enabled = editable,
                    modifier = Modifier.width(300.dp),
                )
            }
        }

        SettingsGroup(stringResource(R.string.group_tools)) {
            NavigationRow(stringResource(R.string.radio_calibration), stringResource(R.string.radio_calibration_desc), AxIcons.Tune) {
                onEvent(SettingsEvent.Open(Calibration))
            }
            AxDivider()
            NavigationRow(stringResource(R.string.radio_elrs), stringResource(R.string.radio_elrs_desc), AxIcons.Antenna) {
                onEvent(SettingsEvent.Open(ElrsMenu))
            }
            AxDivider()
            NavigationRow(stringResource(R.string.radio_preflight), stringResource(R.string.radio_preflight_desc), AxIcons.Checklist) {
                onEvent(SettingsEvent.Open(PreFlightCheck))
            }
            AxDivider()
            AxSettingRow(
                title = stringResource(R.string.radio_reboot_rf),
                description = stringResource(R.string.radio_reboot_rf_desc),
                icon = AxIcons.Refresh,
                enabled = state.radioReady && !state.busy,
            ) {
                AxButton(
                    text = stringResource(R.string.radio_reboot_rf_button),
                    onClick = { onEvent(SettingsEvent.Radio(RadioAction.RebootRf)) },
                    enabled = state.radioReady && !state.busy,
                )
            }
            AxDivider()
            AxSettingRow(
                title = stringResource(R.string.radio_usb),
                description = stringResource(R.string.radio_usb_desc),
                icon = AxIcons.Usb,
                enabled = state.radioReady && !state.busy,
            ) {
                AxSwitch(
                    checked = state.usbPassthrough,
                    onCheckedChange = { onEvent(SettingsEvent.Radio(RadioAction.UsbPassthrough(it))) },
                    enabled = state.radioReady && !state.busy,
                )
            }
        }

        SettingsGroup(stringResource(R.string.group_danger)) {
            AxSettingRow(
                title = stringResource(R.string.radio_restore),
                description = stringResource(R.string.radio_restore_desc),
                icon = AxIcons.Warning,
                enabled = state.radioReady && !state.dirty && state.deviceInSync && !state.busy,
            ) {
                AxButton(
                    text = stringResource(R.string.radio_restore_button),
                    onClick = { onEvent(SettingsEvent.Radio(RadioAction.RestoreDefaults)) },
                    variant = AxButtonVariant.Danger,
                    enabled = state.radioReady && !state.dirty && state.deviceInSync && !state.busy,
                )
            }
        }
    }
}

/** `AxSegmented` index meaning "no option selected" (the value is unavailable). */
private const val NOTHING_SELECTED: Int = -1

/** A list row that opens a sub-screen: icon, title, description and a chevron. */
@Composable
internal fun NavigationRow(
    title: String,
    description: String?,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    AxListRow(
        title = title,
        subtitle = description,
        leadingIcon = icon,
        onClick = onClick,
        enabled = enabled,
        trailing = {
            AxIcon(icon = AxIcons.ChevronRight, contentDescription = null, tint = AxTheme.colors.textMuted)
        },
    )
}
