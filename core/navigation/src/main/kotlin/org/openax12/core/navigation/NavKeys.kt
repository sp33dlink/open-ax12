package org.openax12.core.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * Every destination of the app. All keys are `@Serializable` so the back stack survives
 * configuration changes and process death (`rememberNavBackStack`).
 *
 * Which module installs the entry for which key is fixed in `FeatureEntries.kt`.
 */
@Serializable
sealed interface Ax12Key : NavKey

/** Home screen (`:feature:home`). Always the root of the back stack. */
@Serializable
data object Home : Ax12Key

/**
 * Model setup with its tab row (`:feature:modelsetup` owns the chrome; tabs come from
 * `:feature:modelsetup` and `:feature:functions`).
 *
 * @property initialTabId id of the tab to show when the entry is **first composed** (see
 *   [SetupTabIds]); null = the last used / first tab. It is not a way to switch tabs later: once
 *   model setup is on the back stack, tab requests arrive through `Navigator.openSetupTab` /
 *   `Navigator.setupTabRequest`, which the chrome observes (`Navigator.navigate(ModelSetup(id))`
 *   is routed there automatically).
 */
@Serializable
data class ModelSetup(val initialTabId: String? = null) : Ax12Key

/** Full-screen editor of one curve (`:feature:modelsetup`). [curveIndex] is 0..17. */
@Serializable
data class CurveEditor(val curveIndex: Int) : Ax12Key

/** Model list / grid (`:feature:models`). */
@Serializable
data object Models : Ax12Key

/**
 * Details of one model: rename, picture, type, notes, snapshots, export (`:feature:models`).
 * [modelId] = `ModelEntry.id` — any model, current or not: type / notes / checklist are written
 * with `ModelRepository.updateExtrasOf`, the picture with `ModelRepository.setPicture`.
 */
@Serializable
data class ModelDetail(val modelId: String) : Ax12Key

/** "New model" flow: name, type, template and template options (`:feature:models`). */
@Serializable
data object ModelCreate : Ax12Key

/**
 * Import of a model archive (`:feature:models`). [uri] is the content / file URI as a string
 * (from `ACTION_VIEW` / `ACTION_SEND` or the document picker); null = open the picker first.
 */
@Serializable
data class ModelImport(val uri: String? = null) : Ax12Key

/**
 * Crop a picked image to the model-picture aspect `MODEL_PICTURE_ASPECT` (`:core:model`, 4:3) and
 * hand the rectangle to `ModelRepository.setPicture` (`:feature:models`). [sourceUri] is the
 * picked image.
 */
@Serializable
data class PictureCrop(val modelId: String, val sourceUri: String) : Ax12Key

/** Telemetry pages with widgets (`:feature:telemetry`). */
@Serializable
data object Telemetry : Ax12Key

/**
 * Configure one widget (`:feature:telemetry`).
 *
 * @property pageId id of the telemetry page
 * @property widgetId id of the widget to edit; null = add a new widget to the page
 */
@Serializable
data class TelemetryWidgetEditor(val pageId: String, val widgetId: String? = null) : Ax12Key

/** List of all sensors with rename / unit / precision overrides (`:feature:telemetry`). */
@Serializable
data object SensorList : Ax12Key

/** List and editor of app-side telemetry alarms (`:feature:telemetry`). [alarmId] opens one alarm directly. */
@Serializable
data class AlarmEditor(val alarmId: String? = null) : Ax12Key

/** "Last known position" / lost-model finder (`:feature:telemetry`). */
@Serializable
data object LastPosition : Ax12Key

/** List of CSV flight logs (`:feature:telemetry`). */
@Serializable
data object FlightLogs : Ax12Key

/**
 * System settings: radio, app, about (`:feature:system`). Named `SystemSettings` rather than
 * `System` so that importing it never shadows `java.lang.System`.
 */
@Serializable
data object SystemSettings : Ax12Key

/** ELRS parameter menu browser and link status (`:feature:system`). */
@Serializable
data object ElrsMenu : Ax12Key

/** Stick calibration (`:feature:system`). */
@Serializable
data object Calibration : Ax12Key

/**
 * Pre-flight check overview and per-model checklist (`:feature:system`). Reads the retained
 * `RuntimeEngine.preFlightCheck` state and skips with `RuntimeEngine.skipPreFlightCheck`.
 */
@Serializable
data object PreFlightCheck : Ax12Key

/** Diagnostics: SDK / firmware info, connection, raw monitors, CRSF counters, assumption checks (`:feature:system`). */
@Serializable
data object Diagnostics : Ax12Key

/** Simulator panel; only reachable with the simulator backend (`:feature:system`). */
@Serializable
data object SimulatorPanel : Ax12Key

/** Backup / restore of the whole library (`:feature:system`). */
@Serializable
data object Backup : Ax12Key

/** Help (`:feature:system`). */
@Serializable
data object Help : Ax12Key

/** Editor of one logical switch (`:feature:functions`). [index] is 0-based. */
@Serializable
data class LogicalSwitchEditor(val index: Int) : Ax12Key

/** Editor of one special function (`:feature:functions`). [index] is 0-based. */
@Serializable
data class SpecialFunctionEditor(val index: Int) : Ax12Key

/** Editor of one voice alert (`:feature:functions`). [alertId] null = create a new alert. */
@Serializable
data class VoiceAlertEditor(val alertId: String? = null) : Ax12Key

/**
 * Destinations of the bottom bar, in bar order (SPEC section 1):
 * System · Model setup · Models · **Home** · Telemetry · *Touch lock* · Help.
 *
 * The touch-lock button sits between [TELEMETRY] and [HELP]; it is an action handled by the app
 * shell, not a destination, and therefore not part of this enum.
 *
 * @property key the key pushed when the destination is selected
 */
enum class TopLevelDestination(val key: Ax12Key) {
    SYSTEM(SystemSettings),
    MODEL_SETUP(ModelSetup()),
    MODELS(Models),
    HOME(Home),
    TELEMETRY(Telemetry),
    HELP(Help);

    /** True if [candidate] is this destination's root entry (any [ModelSetup] matches [MODEL_SETUP]). */
    fun matches(candidate: NavKey): Boolean = when (this) {
        MODEL_SETUP -> candidate is ModelSetup
        else -> candidate == key
    }

    companion object {
        /** The top-level destination whose root entry is [key], or null. */
        fun of(key: NavKey): TopLevelDestination? = entries.firstOrNull { it.matches(key) }
    }
}
