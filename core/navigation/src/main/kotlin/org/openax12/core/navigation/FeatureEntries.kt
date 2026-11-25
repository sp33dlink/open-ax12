package org.openax12.core.navigation

import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey

/**
 * The shape of every feature module's entry installer: an extension on the Navigation 3
 * `entryProvider { }` builder scope that registers the feature's `entry<Key> { … }` blocks.
 *
 * ## Feature entry points (binding contract between the features and `:app`)
 *
 * Each feature module exposes exactly the public top-level functions below, in the file
 * `<Feature>Entries.kt` of its root package. Nothing else of a feature is public API.
 *
 * | Module | Function | Installs entries for |
 * |---|---|---|
 * | `:feature:home` | `fun EntryProviderScope<NavKey>.homeEntries()` in `org.openax12.feature.home` | [Home] |
 * | `:feature:modelsetup` | `fun EntryProviderScope<NavKey>.modelSetupEntries(tabs: List<SetupTab>)` in `org.openax12.feature.modelsetup` | [ModelSetup] (chrome showing [tabs][SetupTab] sorted by order), [CurveEditor] |
 * | `:feature:modelsetup` | `fun modelSetupTabs(): List<SetupTab>` in `org.openax12.feature.modelsetup` | tabs [SetupTabIds.INPUT_CHECK] … [SetupTabIds.TIMERS] |
 * | `:feature:functions` | `fun EntryProviderScope<NavKey>.functionsEntries()` in `org.openax12.feature.functions` | [LogicalSwitchEditor], [SpecialFunctionEditor], [VoiceAlertEditor] |
 * | `:feature:functions` | `fun functionsSetupTabs(): List<SetupTab>` in `org.openax12.feature.functions` | tabs [SetupTabIds.FLIGHT_MODES] … [SetupTabIds.SOUNDS] |
 * | `:feature:models` | `fun EntryProviderScope<NavKey>.modelsEntries()` in `org.openax12.feature.models` | [Models], [ModelDetail], [ModelCreate], [ModelImport], [PictureCrop] |
 * | `:feature:telemetry` | `fun EntryProviderScope<NavKey>.telemetryEntries()` in `org.openax12.feature.telemetry` | [Telemetry], [TelemetryWidgetEditor], [SensorList], [AlarmEditor], [LastPosition], [FlightLogs] |
 * | `:feature:system` | `fun EntryProviderScope<NavKey>.systemEntries()` in `org.openax12.feature.system` | [SystemSettings], [ElrsMenu], [Calibration], [PreFlightCheck], [Diagnostics], [SimulatorPanel], [Backup], [Help] |
 *
 * The app shell wires them like this:
 *
 * ```kotlin
 * val setupTabs = remember { (modelSetupTabs() + functionsSetupTabs()).sortedBy { it.order } }
 * NavDisplay(
 *     backStack = navigatorState.backStack,
 *     onBack = { navigator.back() },
 *     entryProvider = entryProvider {
 *         homeEntries()
 *         modelSetupEntries(setupTabs)
 *         functionsEntries()
 *         modelsEntries()
 *         telemetryEntries()
 *         systemEntries()
 *     },
 * )
 * ```
 *
 * ## What the app shell owns (features must not duplicate it)
 * - **Status bar and bottom bar** around the `NavDisplay`.
 * - **The unsaved-changes guard and its Save / Discard / Cancel dialog**: registered with
 *   `Navigator.guardEntries({ it is ModelSetup })` while `ConfigSession.dirty` is true (see
 *   [Navigator]). Feature screens never call `Navigator.setGuard` / `guardEntries` — a guard
 *   registered from a screen disappears as soon as a sub-editor covers the screen.
 * - **The storage permission** (API ≤ 29): `MainActivity` requests it on first start and calls
 *   `ModelRepository.refreshStorage()` followed by `reconcileLibrary()` on the result. Feature
 *   screens only display `StorageInfo`; to ask again they call
 *   `LocalShellActions.current.requestStoragePermission()`.
 * - **Runtime UI requests** (`RuntimeEngine.uiRequests`: alarm popups, screenshot, messages) and
 *   the pre-flight check popup (from the retained `RuntimeEngine.preFlightCheck`).
 * - **Applying settings**: theme, keep-screen-on, touch lock and `RuntimeEngine.brightnessOverride`
 *   in the activity; audio and logging settings are applied by the `AppGraph`. Screens only
 *   write `SettingsRepository`.
 *
 * Rules for entries:
 * - An entry's content gets the key as its argument; everything else comes from `LocalAppGraph`
 *   (`:core:runtime`) and [LocalNavigator]. Features never reference each other — they navigate by
 *   pushing keys of this module.
 * - The app shell draws the status bar and the bottom bar around the `NavDisplay`; entries fill
 *   the area in between and draw no bars of their own (model setup draws its own top chrome
 *   *inside* that area).
 * - Screens must be renderable from plain state for screenshot tests: keep a stateless
 *   `XxxScreen(state, onAction)` composable and a thin stateful wrapper in the entry.
 */
typealias EntryInstaller = EntryProviderScope<NavKey>.() -> Unit

/**
 * Things only the activity can do, offered to feature screens by the app shell.
 *
 * @property requestStoragePermission shows the system dialog for the storage permission the
 *   public library needs on API ≤ 29 (no-op when it is granted or not needed). The shell calls
 *   `ModelRepository.refreshStorage()` and `reconcileLibrary()` when the user answered; the
 *   screen just observes `ModelRepository.storage`.
 * @property openAppSettings opens the system's settings page of this app (for a permanently
 *   denied permission)
 */
@Stable
class ShellActions(
    val requestStoragePermission: () -> Unit = {},
    val openAppSettings: () -> Unit = {},
)

/**
 * The [ShellActions] of the app shell. Defaults to no-ops so that previews and screenshot tests
 * need not provide it.
 */
val LocalShellActions = staticCompositionLocalOf { ShellActions() }
