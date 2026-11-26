# open-ax12 contracts

Map of the public API every module builds against. KDoc in the sources is authoritative; this
file is the index. Paths are relative to `core/<module>/src/main/kotlin/org/openax12/core/<module>/`.

Conventions used everywhere:

- Channel, curve, slot, timer, mix, logical-switch and special-function indices are **0-based**
  (CH01 = 0, L1 = 0, SF1 = 0). Percentages are −100..+100.
- Source indices are `SourceId` (0 Rud … 41 CNT6). Switch logic values are +100 / 0 / −100 (up / mid / down).
- Telemetry hashes are `Long`. Timestamps named `…Ms` are monotonic (`elapsedRealtime` base)
  unless documented as wall clock (`timeMs`, `atMs`, `lastUsedMs`, `startedMs`).
- Nothing throws for expected failures: `Radio` returns `null` / `false` / empty, the data layer
  returns `DataResult`, the config session `SessionResult`.
- Bodies marked `TODO("implemented by <module> agent")` are the only stubs; signatures are fixed.

## Cross-cutting rules (frozen)

These are spelled out in the KDoc named in brackets; every implementer must know them.

1. **Setters overlay onto a fresh read** [`Radio`, "Read-modify-write"]. Every `Radio.set*` reads
   the native struct itself right before writing and overlays only the writable fields. Read-only
   fields of the argument are ignored (structs built from scratch are safe), and an enum / Boolean
   mapped field is written only if it really changed, so unknown device codes survive
   (`SdkEnums.kt` header, assumption 25).
2. **Device reads overwrite the current model file** [`Radio`]. After a model file was loaded, no
   device read may happen until the device confirmed the write. `ModelRepository.deviceInSync` /
   `ConfigSession.deviceInSync` track that window; only `ConfigSession` reads from or writes to
   the device.
3. **Indices are validated against what the SDK returned** [`Radio`, "Invalid index"]; the
   `isValid…` helpers are UI pre-checks only.
4. **Generators own their items** [`ModelBlueprint`, `PlanDiffer`, `ManagedItems`]. A template's
   plan contains only what the wizards cannot express; wing and flight-mode settings are compiled
   under their own generators, also at model creation. Editing a managed item keeps it managed
   (warning on recompile, detected by fingerprint); deleting it removes the record.
5. **Compiled settings are staged with their plan** [`ConfigSession.applyPlan`]. Tabs compile a
   local draft and stage it only through `applyPlan(plan, diff, stage = …)`.
6. **The app shell owns** the unsaved-changes guard and dialog, the storage permission, runtime UI
   requests and the pre-flight popup; **the app graph applies settings** to audio and logging
   [`FeatureEntries.kt`, `Navigator`, `AppGraph`]. Feature screens never register navigation
   guards and never call `Radio.saveConfigToDevice`.
7. **A new model is a neutralised copy** [`ModelRepository.createModel`, `MixPlan.resetConfig`,
   assumption 20].
8. **Timers cannot be started or stopped by hand** [`TimerState`, `RuntimeEngine.resetTimer`]:
   reset only.

## Feature entry points (features ↔ `:app`)

Each feature exposes only these top-level functions (file `<Feature>Entries.kt`, root package of the module):

| Module | Function | Keys / tabs |
|---|---|---|
| `:feature:home` | `fun EntryProviderScope<NavKey>.homeEntries()` | `Home` |
| `:feature:modelsetup` | `fun EntryProviderScope<NavKey>.modelSetupEntries(tabs: List<SetupTab>)` | `ModelSetup` (chrome + given tabs), `CurveEditor` |
| `:feature:modelsetup` | `fun modelSetupTabs(): List<SetupTab>` | `inputs`, `output`, `mixes`, `dualrate`, `chlock`, `curves`, `timers` |
| `:feature:functions` | `fun EntryProviderScope<NavKey>.functionsEntries()` | `LogicalSwitchEditor`, `SpecialFunctionEditor`, `VoiceAlertEditor` |
| `:feature:functions` | `fun functionsSetupTabs(): List<SetupTab>` | `flightmodes`, `wing`, `logicalswitches`, `specialfunctions`, `sounds` |
| `:feature:models` | `fun EntryProviderScope<NavKey>.modelsEntries()` | `Models`, `ModelDetail`, `ModelCreate`, `ModelImport`, `PictureCrop` |
| `:feature:telemetry` | `fun EntryProviderScope<NavKey>.telemetryEntries()` | `Telemetry`, `TelemetryWidgetEditor`, `SensorList`, `AlarmEditor`, `LastPosition`, `FlightLogs` |
| `:feature:system` | `fun EntryProviderScope<NavKey>.systemEntries()` | `SystemSettings`, `ElrsMenu`, `Calibration`, `PreFlightCheck`, `Diagnostics`, `SimulatorPanel`, `Backup`, `Help` |

App shell: `entryProvider { homeEntries(); modelSetupEntries((modelSetupTabs() + functionsSetupTabs()).sortedBy { it.order }); functionsEntries(); modelsEntries(); telemetryEntries(); systemEntries() }`.
The shell draws status bar + bottom bar; entries fill the area between. Tab ids / orders: `SetupTabIds`.
What else the shell owns is listed in `FeatureEntries.kt` (guard + dialog, storage permission,
runtime UI requests, applying settings).

## `:core:model` — `org.openax12.core.model` (pure Kotlin)

| File | Type | Purpose |
|---|---|---|
| Sources.kt | constants `CHANNEL_COUNT` 16, `CURVE_COUNT` 18, `CURVE_MAX_POINTS` 12, `NO_CURVE` 0xFF, `DR_SLOTS` 3, `TIMER_COUNT` 3, `COUNTER_COUNT` 3, `SOURCE_COUNT` 42, `PREFLIGHT_SOURCE_COUNT` 36, `PREFLIGHT_NO_CHECK` 127, `FAILSAFE_CHANNEL_COUNT`, `LOGICAL_SWITCH_COUNT` 32, `SPECIAL_FUNCTION_COUNT` 64, `FLIGHT_MODE_MAX` 3, `ELRS_TX_ADDRESS`, `PERCENT_MIN/MAX` | Fixed firmware / app sizes |
| Sources.kt | `SourceKind` | STICK, POT, SLIDER, SWITCH, COUNTER |
| Sources.kt | `Sources` | Int constants `RUD`=0 … `CNT6`=41, ranges, default names |
| Sources.kt | `SourceId` | Value class over the SDK source index; kind, default name, `fromName`, `pot/slider/switch/counter` |
| Sources.kt | `SwitchPosition` | UP (+100) / MID (0) / DOWN (−100), `fromLogic`, `byLogic` (ascending, for curves) |
| SdkEnums.kt | `CompareOp` | DISABLED 0, GREATER 1, EQUAL 2, LESS 3 (`sdk`, `fromSdk`) |
| SdkEnums.kt | `MixMode` | REPLACE 0, ADD 1, MULTIPLY 2 |
| SdkEnums.kt | `CurveType` | NONE, DIFF, EXPO, FUNC, CUSTOM |
| SdkEnums.kt | `RfModuleMode` | INTERNAL, EXTERNAL, OFF |
| SdkEnums.kt | `StickMode` | MODE_1 / MODE_2 + which source is on which stick axis (other device codes are preserved by the bridge) |
| SdkEnums.kt | `TimerAlertMode` | Firmware timer alert (silent … voice+haptic), `NOTIFY_RESET` |
| SdkEnums.kt | `FailsafeMode` | NONE, HOLD, NO_PULSES, CUSTOM |
| RadioConfig.kt | `SourceInfo` | Mirror of `RcSrcCfgUI` |
| RadioConfig.kt | `Trims` | Mirror of `RcTrimmingTopKeyUI` |
| RadioConfig.kt | `ChannelDualRate` | Read-only DR summary embedded in a channel |
| RadioConfig.kt | `OutputChannelConfig` | Mirror of `RcChOutCfgUI` (limits, curve, weight, channel lock, live value) |
| RadioConfig.kt | `MixKey` | `(channel, source)` identity of a mix |
| RadioConfig.kt | `MixLine` | Mirror of `RcMixCfgDataUI` |
| RadioConfig.kt | `CurvePoint` | One custom-curve point |
| RadioConfig.kt | `CurveConfig` | Mirror of `RcCurveCfgDataUI`; `custom()`, `parametric()`, `points`, `sameShapeAs` (parametric types: `x[0]` and `y[0]` must match) |
| RadioConfig.kt | `DualRateSlot` | Mirror of `RcChCfgDrUI` |
| RadioConfig.kt | `TimerConfig` | Mirror of `RcTimeCfgDataUI` (timer or counter) |
| RadioConfig.kt | `TimerReport` | Mirror of `RcTimeReportUI` |
| RadioConfig.kt | `TimerNotify` | Mirror of `RcTimeNotifyUI` |
| RadioConfig.kt | `SystemConfig` | Mirror of `RcSysCfgDataUI`; pre-flight preset helpers |
| RadioConfig.kt | `ModelInfo` | Mirror of `RcModelDataUI` |
| RadioConfig.kt | `WorkingConfig` | Complete copy of the working config (sources, channels, mixes, curves, DRs, timers, system) with lookup helpers; `isEmpty` while the radio is not usable (never holds made-up defaults) |
| Live.kt | `RadioStatus` | Mirror of `RadioStatusUI`; `isLinkUp` (LQ > 0) |
| Live.kt | `TimerState` | Latest state of a timer / counter inside a live frame |
| Live.kt | `LiveSnapshot` | One immutable live frame: `raw/logic/output(source)`, `channel(i)`, trims, status, timers, sequence, timestamp; `EMPTY`, `of()` |
| Live.kt | `SwitchCondition` | `(source, op, value)`; `test`, `evaluate(live)`, `isActive(live)`, `at(source, position)` |
| Live.kt | `LogicalSwitchStates` | 32 logical-switch states packed in a Long |
| SdkTelemetry.kt | `TelemetryItem` | Entry of the SDK telemetry list (64-bit hash) |
| SdkTelemetry.kt | `TelemetryAlarm` | Full mirror of `TelemetryNotifyUI` (SDK-side notification config / triggered item); `hash` is always the watched item's hash |
| SdkTelemetry.kt | `ElrsMenuItem`, `ElrsItemType` | Mirror of `ElrsSetItemUI`; CRSF parameter type codes |
| SdkTelemetry.kt | `ElrsAck` | Mirror of `ElrsSetEndAckUI` |
| SdkTelemetry.kt | `ElrsStatus` | Mirror of `ElrsStatusInfoUI` |
| SdkTelemetry.kt | `CrsfTelemetrySnapshot` | Mirror of `TelemetryDataUI` |
| SdkTelemetry.kt | `FirmwareInfo` | Mirror of `FirmwareInfUI` (read-only) |
| Sensors.kt | `SensorId` | Stable string key (`crsf.vspd`, `sdk.<hash>`, `tx.batt`, `in.SA`, `ch.3`, `timer.1`, `cnt.1`, `ls.4`, `fm`) with factories / parsers |
| Sensors.kt | `Sensors` | Well-known sensor ids + EdgeTX-style template names (`RxBt`, `Alt`, `VSpd`, `LQ`, …) |
| Sensors.kt | `SensorUnit` | Units with display symbol |
| Sensors.kt | `SensorOrigin` | SDK / CRSF / INTERNAL |
| Sensors.kt | `SensorValue` | value, text, unit, precision, min, max, timestamp, lost |
| Sensors.kt | `SensorInfo` | Registry metadata of a sensor (name, unit, precision, template name) |
| Sensors.kt | `GpsFix` | Position for home / last known position |
| Units.kt | `DisplayValue`, `UnitConversion` | Metric ↔ imperial, `formatNumber`, `format(SensorValue)`, `formatTime` |
| AudioTypes.kt | `SpeechPriority` | LOW, NORMAL, HIGH, ALARM |
| AudioTypes.kt | `AudioCategory` | VOICE, ALARM, VARIO, BEEP, SOUND, MUSIC (per-category volume) |
| AudioTypes.kt | `BeepPattern`, `HapticPattern`, `ToneStep` | Built-in beep / vibration patterns, custom tone step |
| AudioTypes.kt | `VarioTone` | Pitch + cadence for the vario generator |
| Functions.kt | `Trigger` (sealed) | `SwitchAt`, `Input`, `Logical`, `Telemetry`, `Link`, `Timer`, `FlightModeIs`, `Always`, `Periodic`, `Never` |
| Functions.kt | `LinkEvent`, `TimerEvent` | Event kinds for `Trigger.Link` / `Trigger.Timer` |
| Functions.kt | `Repeat` | Once per activation / every N s, skip at startup |
| Functions.kt | `LogicalFunction`, `LogicalSwitch` | L1–L32 definition (EdgeTX function set, AND-condition, duration, delay) |
| Functions.kt | `SpecialAction` (sealed) | `PlayText`, `PlayValue`, `PlaySound`, `BackgroundMusic`, `Vario`, `Haptic`, `Beep`, `ResetTimer`, `ResetTelemetry`, `ResetFlight`, `SetVolume`, `Logging`, `Screenshot`, `Brightness` |
| Functions.kt | `SpecialFunction` | SF1–SF64: trigger → action (+ repeat, enabled) |
| Functions.kt | `VoiceAlert` | Text template + trigger + repeat + priority |
| Functions.kt | `VarioConfig` | EdgeTX-style vario parameters |
| Functions.kt | `TimerVoiceOptions`, `CountdownStyle` | App-side timer voice options |
| Functions.kt | `Announcements` | Built-in announcements on/off |
| Functions.kt | `newItemId()` | Random id for list items in extras |
| FlightSetup.kt | `ModelType` | GLIDER, PLANE, WING, MULTIROTOR, HELICOPTER, OTHER |
| FlightSetup.kt | `FlightModeConfig`, `FlightModesConfig` | Flight modes on one switch; `activeModeIndex(live)` |
| FlightSetup.kt | `WingType`, `TailType`, `WingChannels`, `FlapControl` (sealed), `FlapConfig`, `ButterflyConfig`, `WingConfig` | Wing / flaps wizard settings |
| TelemetryLayout.kt | `WidgetType`, `WidgetOptions`, `WidgetConfig` | Widget type, option keys, one widget (grid position, sensors, options, pinned) |
| TelemetryLayout.kt | `TelemetryPage`, `TelemetryLayout` | Pages of widgets; `pinnedWidgets` |
| TelemetryLayout.kt | `SensorOverride` | Per-model sensor rename / unit / precision |
| TelemetryLayout.kt | `AlarmOutputs`, `SensorAlarm` | App-side threshold alarm (hysteresis, delay, repeat, outputs) |
| ModelExtras.kt | `ChecklistItem`, `HomeCenterCard`, `HomePreferences` | Checklist line; home card state |
| ModelExtras.kt | `ManagedMix`, `ManagedCurve`, `ManagedDualRate`, `ManagedItems` | Bookkeeping of generated firmware items by generator, each with a `fingerprint`; `withoutMix` / `withoutCurve` / `withoutDualRate`, `withFingerprintsFrom(config)`, `isModified(record, config)`; KDoc holds the hand-edit rules |
| ModelExtras.kt | `MixLine.managedFingerprint()`, `CurveConfig.managedFingerprint()`, `DualRateSlot.managedFingerprint()` | Stable hashes of the generated fields (stored in `extras.json`) |
| ModelExtras.kt | `MODEL_PICTURE_ASPECT` 4:3, `MODEL_PICTURE_WIDTH_PX` 800, `MODEL_PICTURE_HEIGHT_PX` 600, `MODEL_PICTURE_FILE_NAME` | The one model-picture format (crop, storage, cards) |
| ModelExtras.kt | `ModelExtras` | Root of `extras.json` (`schemaVersion`, all per-model app data) with list helpers |
| ModelExtras.kt | `Ax12Json` | The one `Json` config (ignores unknown keys, coerces unknown enums) |
| ModelExtras.kt | `ModelExtrasCodec` | `encode`, `decode` → `Decoded`, `migrate` hook |
| AppSettings.kt | `AppTheme`, `UnitSystem`, `RadioBackend`, `TouchLockBehaviour`, `AppSettings` | System-wide settings |
| AppSettings.kt | `AppDispatchers` | main / default / io dispatchers |
| MixPlan.kt | `CurveRef` (sealed), `PlannedCurve`, `PlannedMix`, `PlannedDualRate`, `PlannedChannel`, `MixPlan` | Declarative compiler / template output; `replaceAllMixes`, `resetConfig` (neutralise a copied configuration), `timers`, `counters` |
| MixPlan.kt | `PlanConflictKind`, `PlanConflictSeverity`, `PlanConflict` | Why a plan cannot be applied (incl. `INVALID_SETTINGS` from the compilers' `validate`) |
| MixPlan.kt | `PlanOp` (sealed), `PlanDiff` | Dry-run diff: ordered operations, conflicts, `managedAfter`; `withConflicts` |
| MixPlan.kt | `ModelBlueprint` | Model creation input: base plan (`effectivePlan`, `BLANK_PLAN`) + default extras; KDoc holds the ownership rule |

## `:core:radio` — `org.openax12.core.radio`

| File | Type | Purpose |
|---|---|---|
| Radio.kt | `Radio` | The only gateway to the radio: flows `connection`, `live`, `events`, `configRevision`; `start/stop`; suspend mirror of every SDK function (no firmware / app update, no `postMessage`); `simulator`. Class KDoc: data locations, device reads overwrite the current model file, failure semantics, index validation, read-modify-write |
| RadioTypes.kt | `ConnectionState` (sealed), `ConnectionError` (sealed) | Stopped, Initializing, WaitingForDevice, Ready, Error(reason). `ConnectionError.mayBeStockAppConflict` decides the "close the stock app" hint (true for `SerialPortBusy`, `SerialPortUnavailable`, `CommunicationInitFailed`, `DeviceTimeout`); `ConnectionLost` for a broken running connection |
| RadioTypes.kt | `TaskResult` | COMPLETED, TIMED_OUT, NOT_READY, BUSY for `readConfigFromDevice` / `saveConfigToDevice` |
| RadioTypes.kt | `CrsfFrame` | Raw CRSF frame + type constants |
| RadioTypes.kt | `RadioEvent` (sealed) | LowVoltage, PreFlightCheck, TimerReported, CounterReported, TimerNotified, TrimChanged, ConfigWritten, ConfigRead, TelemetryNotificationQueued, FirmwareVersionMismatch, FirmwareInfoUpdated, ElrsAcknowledged, ElrsStatusReceived, ElrsTelemetry, ElrsDeviceInfo, ElrsMenuItemReceived, CrsfReceived, TelemetryUpdated, TelemetryLost, TelemetryRecovered |
| SimulatorControl.kt | `SimulatorControl`, `SimulatedFault` | Simulator inputs, telemetry, link, fault injection |
| RadioFactories.kt | `createFakeRadio(context, scope, dispatchers): Radio` | Factory of the simulator backend (body by the radio agent) |
| RadioExtensions.kt | `Radio.readWorkingConfig()`, `getMixes`, `findMix`, `getDualRates`, `findDisabledDualRateSlot`, `getTimerConfigs`, `getCurrentModel`, `addTelemetryAlarm`, `updateTelemetryAlarm`, `drainTelemetryNotifications`, `isValid…` (UI pre-checks) | Helpers built on the interface (implemented) |

The real backend lives in `:sdk` and is expected as
`class SdkRadio(context: Context, scope: CoroutineScope, dispatchers: AppDispatchers = AppDispatchers()) : Radio`
in `org.openax12.sdk`. `:app` builds the `radioFactory` for `DefaultAppGraph` from `SdkRadio` and
`createFakeRadio` and resolves `RadioBackend.AUTO` there (SDK on the AX12, simulator elsewhere).

## `:core:navigation` — `org.openax12.core.navigation`

| File | Type | Purpose |
|---|---|---|
| NavKeys.kt | `Ax12Key` (sealed) + keys `Home`, `ModelSetup(initialTabId)`, `CurveEditor`, `Models`, `ModelDetail(modelId)`, `ModelCreate`, `ModelImport(uri)`, `PictureCrop`, `Telemetry`, `TelemetryWidgetEditor`, `SensorList`, `AlarmEditor`, `LastPosition`, `FlightLogs`, `SystemSettings`, `ElrsMenu`, `Calibration`, `PreFlightCheck`, `Diagnostics`, `SimulatorPanel`, `Backup`, `Help`, `LogicalSwitchEditor`, `SpecialFunctionEditor`, `VoiceAlertEditor` | Serializable Navigation 3 keys |
| NavKeys.kt | `TopLevelDestination` | Bottom bar: SYSTEM, MODEL_SETUP, MODELS, HOME, TELEMETRY, HELP (touch lock is an action) |
| Navigator.kt | `Navigator` | `navigate`, `back`, `switchTopLevel` (single-top), `replace`, `popTo`; `openSetupTab` / `setupTabRequest` / `consumeSetupTabRequest` (switch the model-setup tab from anywhere); `guardEntries` / `clearGuard` (shell-owned, composition-independent), `setGuard` (one-shot, key-bound) (implemented + tested) |
| Navigator.kt | `NavigationGuard` | Veto leaving protected entries (unsaved changes); registered by the app shell only |
| Navigator.kt | `LocalNavigator`, `rememberNavigator()`, `NavigatorState` | CompositionLocal; saveable back stack for `NavDisplay` |
| SetupTab.kt | `SetupTab`, `SetupTabIds` | Model-setup tab (id, titleRes, order, content); ids and orders |
| FeatureEntries.kt | `EntryInstaller` | Type of the feature installers; KDoc holds the entry-point contract and what the app shell owns |
| FeatureEntries.kt | `ShellActions`, `LocalShellActions` | Activity-only actions offered to screens (`requestStoragePermission`, `openAppSettings`) |

## `:core:engine` — `org.openax12.core.engine` (pure Kotlin; bodies by the engine agent)

| File | Type | Purpose |
|---|---|---|
| EngineTick.kt | `EngineTick`, `EngineEvent` | Everything known at one tick; events since the last tick |
| ValueResolver.kt | `ValueResolver`, `ResolvedValue`, `EngineValueResolver` | Resolve template names / sensor ids / sources / channels to values |
| TemplateRenderer.kt | `TemplateRenderer`, `ParsedTemplate`, `TemplateToken`, `TemplateError`, `TemplateErrorKind`, `RenderMode`, `ValueFormatter` | Parse / validate / render `{name:precision}` templates; errors point at the bad token |
| Evaluators.kt | `TriggerEvaluator` | Stateful trigger evaluation keyed by owner |
| Evaluators.kt | `LogicalSwitchEvaluator` | Tick-driven L1–L32 with EdgeTX semantics |
| Evaluators.kt | `SpecialFunctionEvaluator`, `ActionCommand` (sealed) | SF + voice alerts → commands for this tick (once / repeat, continuous start / stop) |
| Evaluators.kt | `AlarmEvaluator`, `AlarmEvent`, `AlarmEventKind` | Sensor alarms with delay / hysteresis / repeat |
| VarioMath.kt | `VarioMath`, `VarioMath.AltitudeDerivative` | Vertical speed → tone; altitude derivative filter |
| CurveMath.kt | `CurveMath`, `CurveIssue` | Evaluate / sample / validate every curve type |
| MixerMath.kt | `MixerMath`, `MixerState`, `ChannelExplanation`, `MixContribution` | Reference mixer (simulator) and "explain" data |
| Compilers.kt | `FlightModeCompiler`, `WingCompiler` | Config + working config → `MixPlan` (+ `validate`, blocking conflicts merged into the diff by `ConfigSession.compile…`) |
| Compilers.kt | `PlanDiffer` | `MixPlan` + working config + `ManagedItems` → `PlanDiff` (rules for ownership, `resetConfig`, hand edits via fingerprints, slot / curve allocation) |
| ModelTemplates.kt | `ModelTemplates`, `ModelTemplate`, `TemplateOptions`, `TemplateOptionKey` | Built-in templates → `ModelBlueprint` (base plan only; wing / flight modes go into the extras and are compiled by `ConfigSession.createModel`) |

## `:core:audio` — `org.openax12.core.audio` (bodies by the audio agent)

| File | Type | Purpose |
|---|---|---|
| AudioEngine.kt | `AudioEngine` | speak / speakValue / spokenValue, beeps, tones, vario tone, sound files, music, haptics, volumes, flush / stopAll, `ttsStatus`, `listSoundFiles` |
| AudioEngine.kt | `TtsStatus` (sealed) | Initializing, Ready, NoEngine, LanguageMissing, Error |
| AudioEngine.kt | `SoundFile`, `SpokenText` | User sound file; last spoken text |
| AudioEngine.kt | `createAudioEngine(context, scope, dispatchers, soundsDirectory)` | Factory (no settings parameter: the app graph pushes volumes / speech / haptics) |

## `:core:data` — `org.openax12.core.data` (bodies by the data agent)

| File | Type | Purpose |
|---|---|---|
| DataResult.kt | `DataResult`, `DataError`, `map` | Result type of the data layer |
| ModelRepository.kt | `ModelRepository` | `models`, `currentModel` (= loaded in the working config), `deviceInSync` (persisted), `currentExtras`, update / stage / revert extras, `updateExtrasOf(modelId)`, create (copy of the working config) / duplicate / rename / delete / select, `reloadCurrentModel`, `onDeviceConfigSaved` (save hook: extras + export + snapshot), `onDeviceConfigWritten` (write hook without snapshot), picture, export / import, snapshots, `reconcileLibrary` (repairs from the folder's `model.json`), `storage`. Never reads from or writes to the device |
| ModelRepository.kt | `ModelEntry`, `ModelSnapshot`, `SnapshotReason` | Library model; snapshot |
| ModelRepository.kt | `ImportValidation` (sealed), `ImportKind`, `ImportProblem` | Import validation result |
| ModelRepository.kt | `ExternalChange`, `ReconcileReport`, `StorageInfo`, `PictureCropRect` | Library sync and storage info; crop rectangle |
| SettingsRepository.kt | `SettingsRepository` | `settings`, `awaitLoaded`, `update` |
| TelemetryRepository.kt | `TelemetryRepository`, `HistoryPoint`, `CrsfStats` | `sensors` map, per-sensor `sensor(id)` flow, `history`, `resetMinMax`, `lastKnownPosition`, `homePosition`, `crsfStats`, `linkLost`, `publish` |
| FlightLogger.kt | `FlightLogger`, `FlightLogState` (sealed), `FlightLogFile` | CSV flight log start / stop / list / share |
| LibraryBackup.kt | `LibraryBackup`, `BackupInfo`, `RestoreMode`, `RestoreReport` | Backup / restore zip; `RestoreReport.currentModelReplaced` → caller runs `ConfigSession.reloadCurrentModel` |
| DataLayer.kt | `DataLayer`, `createSettingsRepository(...)`, `createDataLayer(...)` | Factories |

## `:core:runtime` — `org.openax12.core.runtime` (bodies by the runtime agent)

| File | Type | Purpose |
|---|---|---|
| RuntimeEngine.kt | `RuntimeEngine` | start / stop; flows `flightMode`, `logicalSwitches`, `activeSpecialFunctions`, `activeAlarms`, `varioActive`, `lastSpoken`, `brightnessOverride`, `preFlightCheck` (retained), `uiRequests` (one-shot); `valueResolver()`; `resetFlight`, `resetTelemetry`, `resetTimer` (no start / stop exists), `skipPreFlightCheck`, previews |
| RuntimeEngine.kt | `ActiveFlightMode`, `ActiveAlarm`, `RuntimeUiRequest` (sealed), `RuntimeMessages` | Runtime state / requests to the UI layer |
| ConfigSession.kt | `ConfigSession` | `dirty`, `deviceInSync`, `saveState`, `workingConfig`, `edit(refresh) { radio -> }`, `updateExtras` (staged), `save`, `discard`, `previewPlan`, `applyPlan(plan, expected, stage)`, `compileFlightModes(config)`, `compileWing(config)`, `updateSystemConfig`, `resetToDefaults`, `selectModel`, `createModel` (all or nothing), `restoreSnapshot`, `reloadCurrentModel`, `acceptExternalChange`, `reloadFromDevice`. Class KDoc: staged vs. immediate extras, dirty vs. deviceInSync, behaviour while the radio is not ready |
| ConfigSession.kt | `SaveState` (sealed), `SessionError` (incl. `DEVICE_WRITE_PENDING`), `SessionResult` | Save progress and results |
| DiagnosticsLog.kt | `DiagnosticsLog`, `DiagnosticEntry` | Ring buffer (200) of errors and notable events for the diagnostics screen / export |
| AppGraph.kt | `AppGraph`, `StartupState` (sealed) | Service locator: radio, repositories, audio, runtime, configSession, diagnostics, scope, dispatchers, `start()` / `shutdown()`; the graph applies audio / logging settings; `RadioFailed` also when the running connection breaks |
| AppGraph.kt | `LocalAppGraph` | CompositionLocal |
| AppGraph.kt | `DefaultAppGraph(context, radioFactory: (RadioBackend) -> Radio)` | Production graph; the factory resolves `AUTO` |

## Shared UI contracts (`:core:designsystem`, `:core:widgets`)

Two or more features depend on these; the owning modules must provide exactly these signatures
(they are not created by the contract step, see `docs/NOT_DONE.md`).

`:core:widgets` — `org.openax12.core.widgets`

```kotlin
/** Grid of telemetry widgets (Telemetry pages, pinned widgets in the home centre card). */
@Composable
fun WidgetGrid(
    widgets: List<WidgetConfig>,
    columns: Int,
    rows: Int,
    modifier: Modifier = Modifier,
    editMode: Boolean = false,
    onWidgetClick: (WidgetConfig) -> Unit = {},
)

/** One widget of any `WidgetType`, drawn into the space it is given. */
@Composable
fun WidgetContent(config: WidgetConfig, modifier: Modifier = Modifier)
```

- Both read their values themselves from `LocalAppGraph` (`telemetry.sensor(id)`, `radio.live`,
  `runtime` flows) in the smallest composable; callers pass configuration only.
- Home shows the pinned widgets with `WidgetGrid(layout.pinnedWidgets, columns, rows)`; the
  telemetry screen owns move / resize / add in edit mode and uses `WidgetGrid` for placement and
  rendering (`editMode = true` draws the grid cell outlines).
- Stateless renderers for screenshot tests take plain values and are `internal` to the module.

`:core:designsystem` — `org.openax12.core.designsystem`

```kotlin
/** Default picture of a model type (drawable in :core:designsystem), aspect MODEL_PICTURE_ASPECT. */
@DrawableRes
fun defaultModelPicture(type: ModelType): Int

/** A model's picture: the custom file (`picturePath`, cache key `pictureVersion`) or the default of `type`. */
@Composable
fun ModelPicture(picturePath: String?, pictureVersion: Long, type: ModelType, modifier: Modifier = Modifier)

/** The Save / Discard / Cancel dialog the app shell shows when leaving model setup with unsaved changes. */
@Composable
fun UnsavedChangesDialog(saving: Boolean, onSave: () -> Unit, onDiscard: () -> Unit, onCancel: () -> Unit)
```

- Picture format everywhere: `MODEL_PICTURE_ASPECT` (4:3), stored as `MODEL_PICTURE_WIDTH_PX` ×
  `MODEL_PICTURE_HEIGHT_PX` JPEG named `MODEL_PICTURE_FILE_NAME` (constants in `:core:model`).
  Home, the model cards and the crop screen all use `ModelPicture` / that aspect.

## Typical flows

- **Edit a mix:** `configSession.edit { radio -> radio.getMix(ch, i)?.let { radio.setMix(it.copy(weightUp = 80)) } }` → `dirty` becomes true (the revision advanced) → `configSession.save()`.
- **Drag a curve point / slider:** keep the value in local state, send steps with `configSession.edit(refresh = false) { … }`, finish with a normal `edit { … }`.
- **Edit a logical switch:** `configSession.updateExtras { it.withLogicalSwitch(ls) }` (staged) → `save()`.
- **Edit the telemetry layout / flip the home card:** `models.updateExtras { … }` (persisted at once, not dirty).
- **Type / notes of a model that is not loaded:** `models.updateExtrasOf(modelId) { … }`.
- **Flight modes / wing:** keep a local draft → `compileFlightModes(draft)` → show `PlanDiff` (validation problems are blocking conflicts) → `applyPlan(plan, diff, stage = { it.copy(flightModes = draft) })` → `save()`. Renaming a mode: `updateExtras`.
- **Hand-edit a managed mix:** warn, then edit as usual (it stays managed). **Delete** it: `edit { radio.deleteMix(…) }` + `updateExtras { it.copy(managed = it.managed.withoutMix(ch, src)) }`.
- **New model from template:** `ModelTemplates.build(template, options, configSession.workingConfig.value)` → `configSession.createModel(name, blueprint)` (base plan with `resetConfig`, then wing and flight modes under their own generators, then save; rolled back completely on failure).
- **Switch model:** confirm if linked → `configSession.selectModel(id)`; on `DEVICE_WRITE_PENDING` the model is loaded but not on the radio: show the badge, `save()` retries.
- **Re-import / restore over the current model:** `configSession.acceptExternalChange(change)` / after `backup.restore(…)` with `currentModelReplaced`: `configSession.reloadCurrentModel(discardChanges = true)`.
- **System settings (stick mode, alarm voltage, RF module):** `configSession.updateSystemConfig { it.copy(…) }`; restore defaults: `configSession.resetToDefaults()`.
- **Jump to a setup tab from anywhere:** `navigator.openSetupTab(SetupTabIds.MIXES)`.
- **Leaving model setup with unsaved changes:** nothing to do in the feature; the shell's guard asks.
- **Pre-flight check:** read `runtime.preFlightCheck`, skip with `runtime.skipPreFlightCheck()`.
- **Timers on Home:** tap → `navigator.openSetupTab(SetupTabIds.TIMERS)`, long-press → `runtime.resetTimer(i)`; there is no start / stop.
- **Stock-app hint:** shown when `ConnectionError.mayBeStockAppConflict` (from `radio.connection` / `StartupState.RadioFailed`).
- **Live value in UI:** collect `radio.live` in the smallest composable / draw lambda; sensors via `telemetry.sensor(id)`.
