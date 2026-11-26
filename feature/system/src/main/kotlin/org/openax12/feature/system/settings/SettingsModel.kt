package org.openax12.feature.system.settings

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import org.openax12.core.audio.TtsStatus
import org.openax12.core.data.StorageInfo
import org.openax12.core.designsystem.AxIcons
import org.openax12.core.model.AppSettings
import org.openax12.core.model.AudioCategory
import org.openax12.core.model.FirmwareInfo
import org.openax12.core.model.HapticPattern
import org.openax12.core.model.RadioBackend
import org.openax12.core.model.RfModuleMode
import org.openax12.core.model.StickMode
import org.openax12.core.model.SystemConfig
import org.openax12.core.navigation.Ax12Key
import org.openax12.core.radio.ConnectionState
import org.openax12.feature.system.R

/** The categories of the system settings screen (left rail). */
internal enum class SettingsTab(@param:StringRes val title: Int, val icon: ImageVector) {
    RADIO(R.string.tab_radio, AxIcons.Transmitter),
    AUDIO(R.string.tab_audio, AxIcons.Volume),
    APP(R.string.tab_app, AxIcons.Tune),
    STORAGE(R.string.tab_storage, AxIcons.Storage),
    ABOUT(R.string.tab_about, AxIcons.Info),
}

/** Things the Radio panel can do to the transmitter. */
internal sealed interface RadioAction {
    /** Stick mode 1 or 2. */
    data class SetStickMode(val mode: StickMode) : RadioAction

    /** Low-voltage alarm threshold in 0.1 V. */
    data class SetAlarmVoltage(val deciVolt: Int) : RadioAction

    /** Internal / external / off. */
    data class SetRfMode(val mode: RfModuleMode) : RadioAction

    /** Reboot the RF module. */
    data object RebootRf : RadioAction

    /** USB serial passthrough on / off. */
    data class UsbPassthrough(val enabled: Boolean) : RadioAction

    /** Restore the default configuration of the radio. */
    data object RestoreDefaults : RadioAction
}

/**
 * Whether [this] must be confirmed by the user before it runs. Restoring defaults is always
 * confirmed (double); everything else that touches the radio asks while the receiver link is up
 * (SPEC section 9: "writing to the device while linked asks for confirmation").
 */
internal fun RadioAction.needsConfirmation(linkUp: Boolean): Boolean =
    this is RadioAction.RestoreDefaults || this.turnsRfOff || linkUp

/**
 * True for the action that switches the RF module off. It is **always** confirmed, link or not:
 * the radio stops transmitting ("Turn the RF module off? The radio stops transmitting").
 */
internal val RadioAction.turnsRfOff: Boolean
    get() = this is RadioAction.SetRfMode && mode == RfModuleMode.OFF

/** Value of the USB passthrough mode that switches it on (device-protocol defined, assumption 34). */
internal const val USB_PASSTHROUGH_ON: Int = 1

/** Value of the USB passthrough mode that switches it off. */
internal const val USB_PASSTHROUGH_OFF: Int = 0

/** Everything the settings screen shows. */
internal data class SettingsUiState(
    val app: AppSettings = AppSettings(),
    /** The system configuration as read from the radio; null while the radio is not usable. */
    val system: SystemConfig? = null,
    val connection: ConnectionState = ConnectionState.Stopped,
    val linkUp: Boolean = false,
    val dirty: Boolean = false,
    val deviceInSync: Boolean = true,
    val tts: TtsStatus = TtsStatus.Initializing,
    val languages: List<String> = emptyList(),
    val storage: StorageInfo = StorageInfo(),
    val firmware: FirmwareInfo? = null,
    val sdkVersion: String = "",
    val activeBackend: RadioBackend = RadioBackend.SDK,
    val musicPlaying: Boolean = false,
    val usbPassthrough: Boolean = false,
    val busy: Boolean = false,
    val pending: RadioAction? = null,
) {
    /** The radio is connected and its system configuration was read. */
    val radioReady: Boolean get() = connection.isReady && system != null

    /**
     * The radio is connected but gave no trustworthy radio-wide settings (the SDK getter is
     * unreliable, see assumption r5): the Radio panel shows "unavailable" and changes nothing.
     */
    val systemUnavailable: Boolean get() = connection.isReady && system == null

    /**
     * Device-writing controls are enabled: `ConfigSession.updateSystemConfig` refuses while there
     * are unsaved model edits or a model that is not on the device yet.
     */
    val canEditDevice: Boolean get() = radioReady && !dirty && deviceInSync && !busy
}

/** Everything the settings screen can ask for. */
internal sealed interface SettingsEvent {
    /** Change an app setting (persisted by the settings repository; the graph applies it). */
    data class Update(val transform: (AppSettings) -> AppSettings) : SettingsEvent

    /** Ask for a radio action (may open a confirmation first). */
    data class Radio(val action: RadioAction) : SettingsEvent

    /** The user confirmed the pending radio action. */
    data object ConfirmPending : SettingsEvent

    /** The user cancelled the pending radio action. */
    data object DismissPending : SettingsEvent

    /**
     * Plays a sample of [category]. [speech] is the localized sample sentence for the voice and
     * alarm categories; [afterSettingChange] waits briefly so the new volume has been applied.
     */
    data class TestSound(
        val category: AudioCategory,
        val speech: String = "",
        val afterSettingChange: Boolean = false,
    ) : SettingsEvent

    /** Plays a haptic pattern. */
    data class TestHaptic(val pattern: HapticPattern) : SettingsEvent

    /** Asks the device for its info (firmware version etc.). */
    data object RequestDeviceInfo : SettingsEvent

    /** Navigate to a sub-screen. */
    data class Open(val key: Ax12Key) : SettingsEvent

    /** Show the system dialog for the storage permission. */
    data object RequestStoragePermission : SettingsEvent
}
