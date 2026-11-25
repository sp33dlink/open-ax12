package org.openax12.core.radio

import android.content.Context
import android.os.SystemClock
import java.io.File
import kotlinx.coroutines.CoroutineScope
import org.openax12.core.model.AppDispatchers
import org.openax12.core.radio.fake.FakeRadio

/**
 * Creates the simulator backend (`FakeRadio`): a complete in-memory radio with a default model,
 * the reference mixer, timers, simulated telemetry sent as CRSF frames, and
 * [Radio.simulator] controls. Its models and working configuration are persisted in the app's
 * private files so that they survive restarts, like the real SDK's data directory.
 *
 * It reproduces the SDK behaviour the flows depend on, so that they are testable off the radio:
 * a completed device read overwrites the current model file with the (simulated) device
 * configuration; `loadModelFile` changes only the working config until `saveConfigToDevice`;
 * `saveWorkingConfigToModel` stores a copy of the working config; `deleteDualRate` does nothing;
 * setters ignore read-only fields ("Read-modify-write" in the [Radio] KDoc).
 *
 * The real backend is `org.openax12.sdk.SdkRadio(context, scope, dispatchers)` in `:sdk`; `:app`
 * chooses between the two in the `radioFactory` it passes to `DefaultAppGraph`.
 *
 * @param context application context (private storage for the simulated model files)
 * @param scope application scope the simulator runs its 40 ms tick in
 */
fun createFakeRadio(
    context: Context,
    scope: CoroutineScope,
    dispatchers: AppDispatchers = AppDispatchers(),
): Radio = FakeRadio(
    scope = scope,
    directory = File(context.applicationContext.filesDir, SIMULATOR_DIRECTORY),
    dispatcher = dispatchers.io,
    clock = SystemClock::elapsedRealtime,
)

/** Folder inside the app's private files that holds the simulator's models and state. */
private const val SIMULATOR_DIRECTORY = "simulator"
