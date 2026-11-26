# open-ax12

Native Android app that replaces the stock RadioMaster app on the **RadioMaster AX12** transmitter.
Stock look & feel, EdgeTX-style functionality on top. Full feature spec: `docs/SPEC.md`.
Unverified device assumptions: `docs/ASSUMPTIONS.md` (append to it whenever you rely on something not proven on the device).

## Target device (fixed)

- Android 9 (API 28, never updated), 5.5" 1280×720 **landscape only**, arm64-v8a, 4 GB RAM.
- `minSdk 28`. Anything newer than API 28 must be guarded; behaviour on API 28 is what matters.
- The app is also run on an arm64 emulator (API 35) against the simulator backend for development.

## Radio SDK facts (read `docs/sdk/SDK_User_Manual.en.md` before touching `:sdk`)

- `rmAxSdk1` is a prebuilt C++ library (Qt 6.8.3 inside). Headers: `sdk/src/main/cpp/include/`. Libs: `sdk/src/main/jniLibs/arm64-v8a/` — **never modify or rename them** (checksums in `sdk/SDK_CHECKSUMS.sha256`). None of the SDK files are committed: `scripts/fetch-sdk.sh` downloads the pinned upstream commit and verifies `sdk/SDK_FETCH.sha256`; Gradle runs it (`:sdk:fetchSdk`) when a file is missing. `scripts/fetch-sdk.sh --docs` also fetches the manuals into `docs/sdk/` (git-ignored, not redistributable).
- It talks to the radio MCU over a serial port. **Mixing runs in the MCU.** The app reads live state (~every 40 ms) and edits configuration. There is no way to inject channel values.
- SDK has: inputs, output channels, mixes (one switch condition, slow, curve, replace/add/multiply), 18 curves, 3 dual-rate slots per channel, channel lock, 3 timers + 3 counters, trims, pre-flight check, telemetry list + alarms, ELRS menu, raw CRSF frames, model files/templates.
- SDK lacks: logical switches, special functions, flight modes, audio, widgets. `rcDelDrData` is a no-op. Failsafe fields are not persisted. Deleting a mix is keyed by `(chIdx, srcIdx)`.
- Source indices: 0 Rud, 1 Ele, 2 Thr, 3 Ail, 4–11 P1–P8, 12–19 S1–S8, 20–35 SA–SP, 36–41 CNT1–CNT6.
- `rcSet*` only changes the working config / live preview. Persisting = `taskSaveConfigToDevice()` and waiting for `RC_MSGID_CFGTODEV_END`.

## Design rules (non-negotiable)

1. **Flight-critical logic lives in the firmware.** Anything that changes channel outputs (flight modes, flaps, throttle cut, rates) is compiled into SDK mixes / curves / dual rates / channel locks. It must keep working if the app dies.
2. **Convenience logic lives in the app** (voice, vario tones, haptics, widgets, logging, logical switches as triggers). It runs from the foreground service, not from a screen.
3. **Only `:sdk` and `FakeRadio` implement `Radio`.** Nothing else touches JNI. UI and engine code depend on the `Radio` interface only.
4. **Never call the SDK firmware-update or app-update APIs.** Firmware updates stay in the stock app.
5. Everything is per model except system settings. Per-model data we add lives in `ModelExtras` (`extras.json`), versioned with `schemaVersion`.
6. Read-modify-write for every SDK struct. Validate every index before it reaches the SDK (out-of-range is undefined behaviour there).

## Modules

| Module | Kind | Contents |
|---|---|---|
| `:core:model` | Kotlin/JVM | Domain types, `ModelExtras` schema, kotlinx.serialization. No Android. |
| `:core:radio` | Android lib | `Radio` interface, events, `FakeRadio` simulator (with mixer simulation). |
| `:sdk` | Android lib + C++ | JNI bridge, `SdkRadio : Radio`. |
| `:core:engine` | Kotlin/JVM | Logical switches, special-function evaluation, text templates, vario maths, flight-mode/flaps compiler, model templates. Pure and unit-tested. |
| `:core:audio` | Android lib | `AudioEngine`: text-to-speech, tone synth, sound files, queue, haptics. |
| `:core:data` | Android lib | `ModelRepository`, `SettingsRepository`, `TelemetryRepository`, CRSF decoding, import/export, logging. |
| `:core:runtime` | Android lib | `RuntimeEngine` (ties radio+engine+audio+data together), `ConfigSession`, `AppGraph` service locator. |
| `:core:navigation` | Android lib | Navigation 3 keys, `Navigator`, `SetupTab`. |
| `:core:designsystem` | Android lib (Compose) | Theme, `FixedCanvas`, shared components. |
| `:core:widgets` | Android lib (Compose) | Telemetry widget renderers shared by home and telemetry. |
| `:feature:home` `:feature:modelsetup` `:feature:models` `:feature:telemetry` `:feature:functions` `:feature:system` | Android lib (Compose) | Screens. Features never depend on each other. |
| `:app` | Application | `AppGraph` impl, `MainActivity`, Nav3 shell, `RadioService`, intents. |

Package root: `org.openax12` (e.g. `org.openax12.core.model`, `org.openax12.feature.home`). Application id `org.openax12.app`.

## Conventions

- Kotlin, Jetpack Compose, **Navigation 3** (`androidx.navigation3`), coroutines/Flow, kotlinx.serialization. Versions only in `gradle/libs.versions.toml`.
- No DI framework: dependencies come from `AppGraph` (`LocalAppGraph.current` in Compose, constructor parameters elsewhere). ViewModels take their dependencies as constructor args via a factory.
- State: `StateFlow` in ViewModels, `collectAsStateWithLifecycle()` in UI. Live radio data is conflated; never rebuild a whole screen per 40 ms tick — read live values in the smallest composable or in a draw lambda.
- UI is laid out on a fixed virtual canvas of **853×480 dp** via `FixedCanvas` (density override). Do not use window size classes. Minimum touch target 44 dp.
- All user-visible strings in `strings.xml` of the owning module (English). No hard-coded UI text in composables.
- Comments in English. Public API gets KDoc. No TODO stubs left behind: if something cannot be done, document it in `docs/ASSUMPTIONS.md` or `docs/NOT_DONE.md`.
- Do not add dependencies without adding them to the version catalog. Do not introduce Hilt/Koin/Room.

## Building

- **Always build through `scripts/gw`** (never `./gradlew` directly): it serialises Gradle across parallel agents. Run it with a 10-minute timeout. Exit code 75 means "lock busy, retry".
- Build only what you own while working: e.g. `scripts/gw :core:engine:test`, `scripts/gw :feature:home:compileDebugKotlin`.
- Full check: `scripts/gw assembleDebug testDebugUnitTest :core:model:test :core:engine:test`.
- Emulator: only ever use `adb -s emulator-5554`. **Never install on or send commands to any other attached device.**
- Commit or push only when the user asks. The repository is public on GitHub (AGPL-3.0 + SDK linking exception, `LICENSE-EXCEPTION.md`); never commit SDK files, keystores or device-specific data.
- Versioning: `versionCode` = git commit count, `versionName` = `<baseVersionName>-dev.<count>+<sha>` (plain `<baseVersionName>` with `-Popenax12.release=true`); `baseVersionName` is in `app/build.gradle.kts`. CI: `.github/workflows/android.yml` — a push to `master` with a version that has no tag `v<version>` creates the tag and the GitHub release with the signed APK (signing key from repository secrets). Never create release tags or releases by hand (releases are immutable).

## Visual checks

UI modules can render composables to PNG on the host (no emulator) with the official Compose
Preview Screenshot Testing plugin (`com.android.compose.screenshot`). It is already applied to
every module that uses `openax12.android.ui` / `openax12.android.feature` and to `:app`.

1. Put screenshot functions in **`<module>/src/screenshotTest/kotlin/<package path>/…Screenshots.kt`**
   (a separate source set; it sees the module's `internal` declarations and its resources).
2. Annotate each function with `@PreviewTest` (`com.android.tools.screenshot.PreviewTest`), the
   AX12 preview annotation `@Ax12Preview` (`org.openax12.core.designsystem.Ax12Preview`, in
   `:core:designsystem` — do not delete it) and `@Composable`. Top-level, public, no parameters:

   ```kotlin
   @PreviewTest
   @Ax12Preview
   @Composable
   fun HomeScreenScreenshot() {
       Ax12Theme { HomeScreen(state = previewState(), onAction = {}) }
   }
   ```

   `@Ax12Preview` renders at 1280×720 px, density 1.5 = the **853×480 dp** canvas. Inside
   `:core:designsystem` itself the same annotation is used. Plain `@Preview(...)` annotations
   (several per function, `uiMode`, `fontScale`, …) work too; each one produces its own PNG.
3. Render: **`scripts/gw :feature:home:updateDebugScreenshotTest`** (replace the module path).
4. Look at the PNGs (Read tool) in
   **`<module>/src/screenshotTestDebug/reference/<package path>/<FileName>Kt/<function>_<previewName>_<hash>_0.png`**,
   e.g. `core/designsystem/src/screenshotTestDebug/reference/org/openax12/core/designsystem/ScaffoldScreenshotsKt/ScaffoldCanvasScreenshot_AX12_f2759852_0.png`.
   A copy of the last run is in `<module>/build/outputs/screenshotTest-results/preview/debug/rendered/`.

Notes:

- The image is cropped to the content. Give the root `Modifier.fillMaxSize()` (or wrap it in
  `FixedCanvas`) to get the full 1280×720 frame.
- Screens must be renderable from plain state: pass state and lambdas into a stateless
  composable and screenshot that, or provide a fake `AppGraph` through `LocalAppGraph`. No
  ViewModel factories, services, radio or file access in screenshot functions.
- Only the first frame is rendered: no animations, no `LaunchedEffect` results, no Coil network
  or file loads (use a `painterResource`/placeholder).
- After renaming or deleting a function, check `src/screenshotTestDebug/reference/` for a stale
  PNG of the old name and delete it by hand.
- `scripts/gw :<module>:validateDebugScreenshotTest` compares against the reference PNGs (HTML
  report in `build/reports/screenshotTest/`). It is not part of the full check.
- Sample: `core/designsystem/src/screenshotTest/kotlin/org/openax12/core/designsystem/ScaffoldScreenshots.kt`.

## Toolchain

| What | Version |
|---|---|
| Gradle (wrapper) | 9.9.1, runs on JBR 21 (`JAVA_HOME`) |
| Android Gradle Plugin | 9.5.1 (built-in Kotlin, new DSL) |
| Kotlin / Compose compiler plugin / serialization plugin | 2.4.21 |
| compileSdk / targetSdk / minSdk | 37.0 (`android-37.0`) / 36 / 28 |
| Java & Kotlin bytecode target | 17 |
| NDK / CMake | 29.0.14206865 / 3.22.1 |
| Compose BOM | 2026.09.00 (Material 3 from the BOM) |
| material-icons-extended | 1.7.8 (last release; `api` of `:core:designsystem`) |
| Navigation 3 runtime + ui | 1.2.1 |
| Lifecycle | 2.11.0 |
| activity-compose / core-ktx / datastore-preferences | 1.13.0 / 1.19.1 / 1.3.0 |
| kotlinx-serialization-json / kotlinx-coroutines | 1.13.0 / 1.11.0 |
| Coil 3 (`coil-compose`, `api` of `:core:designsystem`) | 3.6.3 |
| JUnit 4 / Turbine | 4.13.2 / 1.2.1 |
| Screenshot plugin / validation API | 0.0.1-alpha16 / 0.0.1-alpha17 |

Build layout:

- All versions: `gradle/libs.versions.toml`. All shared build logic: `build-logic/src/main/kotlin/`
  (precompiled script plugins). A module build file applies exactly one of
  `openax12.jvm.library`, `openax12.android.library`, `openax12.android.compose` (compiler +
  runtime only), `openax12.android.ui` (Compose UI + Material 3 + screenshots),
  `openax12.android.feature` (ui + the four core UI modules + lifecycle/activity compose),
  `openax12.android.application`, optionally plus `openax12.serialization`, and then only lists
  its own dependencies as `libs.…` accessors.
- Namespaces are derived from the project path (`:feature:home` → `org.openax12.feature.home`).
  Sources go in `src/main/kotlin`, unit tests in `src/test/kotlin`. Library modules need no
  `AndroidManifest.xml`.
- Every module except `:app` and `:sdk` contains a `ScaffoldPlaceholder.kt`; delete it once the
  module has real sources. `:sdk` has `SdkNative.kt` + `openax12_jni.cpp` (version call only),
  `:app` a placeholder `MainActivity`.

Gotchas (AGP 9 and this setup):

- **Built-in Kotlin:** never apply `org.jetbrains.kotlin.android` / `kotlin-android` (it fails).
  `kotlin { compilerOptions { … } }` is available in every Android module without it. The Kotlin
  version is whatever `kotlin-gradle-plugin` build-logic puts on the classpath (2.4.21), not the
  2.2.10 AGP asks for.
- **Plugins are not declared with versions anywhere.** They come from build-logic's classpath.
  A third-party plugin id cannot be applied directly in a module's `plugins {}` block (it is not
  resolvable there); wrap it in a convention plugin like `openax12.serialization`.
- Inside build-logic the generated `libs` accessors do not exist: use `catalog.lib("alias")`.
  Do not name anything `libs` in build-logic, it shadows the accessor in every module script.
- **compileSdk DSL:** `compileSdk { version = release(37) { minorApiLevel = 0 } }`. 37 is forced
  by Compose 1.12 / Lifecycle 2.11 (AAR metadata check); 36.1 does not build.
- AGP 9 defaults: `BuildConfig`, `resValues`, AIDL are off (enable per module with
  `buildFeatures { buildConfig = true }` if needed); R classes are non-transitive (use the `R`
  of the module that owns the resource).
- **Compose Preview tasks:** AGP 9.5 registers `generate<Variant>ComposePreviewRunfiles` in every
  Compose module and fails to create it unless unit tests exist for that variant with Android
  resources. The conventions therefore enable unit tests for all variants and set
  `testOptions.unitTests.isIncludeAndroidResources = true`; removing either breaks `tasks --all`.
  Use `testDebugUnitTest` (Android modules) and `test` (`:core:model`, `:core:engine`).
- The screenshot plugin is applied with `apply(plugin = …)`, not in `plugins {}`: it refuses to
  load without `android.experimental.enableScreenshotTest=true`, which is absent while Gradle
  compiles the convention plugins. The "option is experimental" warning on every build is expected.
- Configuration cache and parallel execution are on. The user-level Gradle config stops the
  daemon right after each build, so every `scripts/gw` call pays a few seconds of start-up:
  put all tasks into one invocation.
- **Native build (`:sdk`):**
  - CMake 3.22.1 was auto-provisioned by AGP into `~/Library/Android/sdk/cmake/3.22.1` on the
    first native build (SDK licence already accepted). Nothing to install by hand.
  - `ANDROID_STL=c++_shared` is set in `sdk/src/main/cpp/CMakeLists.txt` before `project()`, **not**
    as a Gradle `arguments` entry: if Gradle knows the STL it copies the NDK's own
    `libc++_shared.so` (NDK 29, LLVM 21) into the build and it collides with the vendor's
    (NDK r25, LLVM 14) from `jniLibs`. The vendor file is the only C++ runtime in the APK.
  - `openax12jni` links with `-nostdlib++` against that vendor `libc++_shared.so`, so a libc++
    symbol the old runtime lacks is a **link error** instead of an `UnsatisfiedLinkError` on the
    radio. If that happens, avoid the offending std feature; do not swap the runtime.
  - `libopenax12jni.so` **must define `JNI_OnLoad`**. Otherwise `System.loadLibrary` finds the one
    in `libQt6Core` through `DT_NEEDED`, which returns `JNI_ERR` ("JNI_ERR returned from
    JNI_OnLoad"). Never `System.loadLibrary` a Qt library directly.
  - `:app` uses `jniLibs.useLegacyPackaging = true`; `:sdk` and `:app` keep debug symbols for all
    `.so` files so nothing is stripped. `assembleDebug`/`assembleRelease` run
    `:app:verify<Variant>NativeLibs`, which fails the build unless all six prebuilt libraries in
    the APK match `sdk/SDK_CHECKSUMS.sha256` and `libopenax12jni.so` is present
    (report: `app/build/reports/nativeLibs/`).
  - `rmaxSdk::version()` returns a build timestamp (`Oct  8 2026 16:07:03`), not a semantic version.
- The debug APK is about 70 MB because `material-icons-extended` is not shrunk (R8 is off).
- On the emulator the first launch shows the system "full screen" hint over the app; tap OK once.
