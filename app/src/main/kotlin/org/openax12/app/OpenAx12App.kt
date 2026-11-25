package org.openax12.app

import android.app.Application
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.openax12.core.model.RadioBackend
import org.openax12.core.radio.Radio
import org.openax12.core.radio.createFakeRadio
import org.openax12.core.runtime.AppGraph
import org.openax12.core.runtime.DefaultAppGraph
import org.openax12.sdk.SdkRadio

/**
 * Application entry point. Builds the one [AppGraph] of the process; the radio backend is chosen
 * by [createRadio] (the only place in `:app` that touches `SdkRadio`, design rule 3).
 *
 * Nothing is started here: `RadioService` runs `AppGraph.start()`.
 */
class OpenAx12App : Application() {

    /** The service locator of the process. Available from [onCreate] on. */
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = DefaultAppGraph(this, ::createRadio)
    }

    /**
     * The `radioFactory` of [DefaultAppGraph]: resolves `AUTO` with [BackendPolicy] and creates the
     * radio once. If the SDK backend cannot even be constructed the simulator is used instead (the
     * "SIM" badge shows it and the diagnostics screen reports the backend), so that the app never
     * crashes at start; see docs/ASSUMPTIONS.md (a3).
     */
    private fun createRadio(configured: RadioBackend): Radio {
        val backend = BackendPolicy.resolve(configured, Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        if (backend == RadioBackend.SDK) {
            try {
                return SdkRadio(this, scope)
            } catch (t: Throwable) {
                Log.e(TAG, "SdkRadio could not be created, using the simulator", t)
            }
        }
        return createFakeRadio(this, scope)
    }

    private companion object {
        const val TAG = "OpenAx12App"
    }
}

/** The [AppGraph] of the application this context belongs to. */
internal val android.content.Context.appGraph: AppGraph
    get() = (applicationContext as OpenAx12App).graph
