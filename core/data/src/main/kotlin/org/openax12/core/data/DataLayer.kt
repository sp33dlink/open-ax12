package org.openax12.core.data

import android.content.Context
import android.os.SystemClock
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.openax12.core.data.backup.ZipLibraryBackup
import org.openax12.core.data.library.AndroidPictureProcessor
import org.openax12.core.data.library.AndroidUriAccess
import org.openax12.core.data.library.DefaultModelRepository
import org.openax12.core.data.logging.CsvFlightLogger
import org.openax12.core.data.settings.DataStoreSettingsRepository
import org.openax12.core.data.storage.AndroidStorageLocator
import org.openax12.core.data.telemetry.DefaultTelemetryRepository
import org.openax12.core.data.telemetry.LastPositionStore
import org.openax12.core.model.AppDispatchers
import org.openax12.core.radio.Radio

/** The repositories that depend on the radio, created together by [createDataLayer]. */
class DataLayer(
    val models: ModelRepository,
    val telemetry: TelemetryRepository,
    val flightLogger: FlightLogger,
    val backup: LibraryBackup,
)

/**
 * Creates the [SettingsRepository]. It has no dependency on the radio so that the configured
 * radio backend can be read before the radio is created.
 *
 * @param context application context
 * @param scope application scope
 */
fun createSettingsRepository(
    context: Context,
    scope: CoroutineScope,
    dispatchers: AppDispatchers = AppDispatchers(),
): SettingsRepository =
    DataStoreSettingsRepository(DataStoreSettingsRepository.dataStore(context, scope, dispatchers), scope, dispatchers)

/**
 * Creates the radio-dependent repositories. Nothing is started: `TelemetryRepository.start()` and
 * `ModelRepository.reconcileLibrary()` are called by the app graph during startup.
 *
 * @param context application context
 * @param radio the app's radio (real or simulator)
 * @param settings used for units, logging interval and similar
 * @param scope application scope the repositories launch their workers in
 */
fun createDataLayer(
    context: Context,
    radio: Radio,
    settings: SettingsRepository,
    scope: CoroutineScope,
    dispatchers: AppDispatchers = AppDispatchers(),
): DataLayer {
    val appContext = context.applicationContext
    val privateDir = File(appContext.filesDir, PRIVATE_DIR)
    val uriAccess = AndroidUriAccess(appContext)
    val appVersion = appVersionName(appContext)

    val models = DefaultModelRepository(
        radio = radio,
        locator = AndroidStorageLocator(appContext),
        privateDir = privateDir,
        uriAccess = uriAccess,
        pictures = AndroidPictureProcessor(),
        scope = scope,
        dispatchers = dispatchers,
        appVersion = appVersion,
        importedModelName = { date -> appContext.getString(R.string.library_imported_from_radio, date) },
    )
    val telemetry = DefaultTelemetryRepository(
        radio = radio,
        overrides = models.currentExtras.map { it.sensorOverrides }.distinctUntilChanged(),
        positionStore = LastPositionStore(File(privateDir, "last_position.json")),
        scope = scope,
        dispatchers = dispatchers,
        monotonicClock = SystemClock::elapsedRealtime,
    )
    val flightLogger = CsvFlightLogger(
        radio = radio,
        telemetry = telemetry,
        modelName = { models.currentModel.value?.name.orEmpty() },
        logsDir = { models.storage.value.logsDir.takeIf { it.isNotEmpty() }?.let(::File) },
        intervalMs = { settings.settings.value.logIntervalMs },
        uriAccess = uriAccess,
        scope = scope,
        dispatchers = dispatchers,
    )
    val backup = ZipLibraryBackup(
        models = models,
        settings = settings,
        uriAccess = uriAccess,
        stagingRoot = File(appContext.cacheDir, "restore"),
        dispatchers = dispatchers,
        appVersion = appVersion,
    )
    return DataLayer(models = models, telemetry = telemetry, flightLogger = flightLogger, backup = backup)
}

/** Directory inside the app's private files that holds the sync index, shadow copies and temporary files. */
private const val PRIVATE_DIR = "openax12"

private fun appVersionName(context: Context): String = try {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
} catch (_: Exception) {
    ""
}
