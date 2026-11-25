package org.openax12.core.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.openax12.core.data.SettingsRepository
import org.openax12.core.model.AppDispatchers
import org.openax12.core.model.AppSettings
import org.openax12.core.model.Ax12Json

/** Encoding of [AppSettings] as stored in the preferences file and in library backups. */
internal object AppSettingsCodec {

    /** Serializes [settings] with the current schema version. */
    fun encode(settings: AppSettings): String =
        Ax12Json.encodeToString(AppSettings.serializer(), settings.copy(schemaVersion = AppSettings.CURRENT_SCHEMA_VERSION))

    /**
     * Parses stored settings. Unknown keys are ignored and unknown enum values fall back to
     * their defaults; values are clamped to their valid ranges.
     *
     * @return null if [text] is not readable at all
     */
    fun decodeOrNull(text: String?): AppSettings? {
        if (text.isNullOrBlank()) return null
        return try {
            sanitize(Ax12Json.decodeFromString(AppSettings.serializer(), text))
        } catch (_: Exception) {
            null
        }
    }

    /** Clamps every numeric setting to the range the app can work with. */
    fun sanitize(settings: AppSettings): AppSettings = settings.copy(
        schemaVersion = AppSettings.CURRENT_SCHEMA_VERSION,
        speechRate = if (settings.speechRate.isNaN()) 1.0f else settings.speechRate.coerceIn(0.25f, 4.0f),
        masterVolume = settings.masterVolume.coerceIn(0, 100),
        volumes = settings.volumes.mapValues { it.value.coerceIn(0, 100) },
        logIntervalMs = settings.logIntervalMs.coerceIn(MIN_LOG_INTERVAL_MS, MAX_LOG_INTERVAL_MS),
        touchLock = settings.touchLock.copy(
            unlockHoldMs = settings.touchLock.unlockHoldMs.coerceIn(200, 10_000),
            autoLockSec = settings.touchLock.autoLockSec.coerceIn(0, 3600),
        ),
    )

    const val MIN_LOG_INTERVAL_MS: Int = 50
    const val MAX_LOG_INTERVAL_MS: Int = 60_000
}

/**
 * [SettingsRepository] on a Preferences DataStore. The whole [AppSettings] object is stored as
 * one JSON string, so adding a setting needs no key and no migration (missing fields take their
 * defaults).
 */
internal class DataStoreSettingsRepository(
    private val store: DataStore<Preferences>,
    scope: CoroutineScope,
    private val dispatchers: AppDispatchers,
) : SettingsRepository {

    private val _settings = MutableStateFlow(AppSettings())
    private val loaded = CompletableDeferred<Unit>()
    private val writeLock = Mutex()

    override val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    init {
        scope.launch(dispatchers.io) {
            store.data
                .catch { error ->
                    // An unreadable file must not keep the app from starting: run on defaults.
                    if (error is IOException) emit(emptyPreferences()) else throw error
                }
                .collect { preferences ->
                    _settings.value = AppSettingsCodec.decodeOrNull(preferences[KEY]) ?: AppSettings()
                    loaded.complete(Unit)
                }
        }
    }

    override suspend fun awaitLoaded(): AppSettings {
        loaded.await()
        return _settings.value
    }

    override suspend fun update(transform: (AppSettings) -> AppSettings): AppSettings = withContext(dispatchers.io) {
        writeLock.withLock {
            var result = _settings.value
            try {
                store.edit { preferences ->
                    val current = AppSettingsCodec.decodeOrNull(preferences[KEY]) ?: AppSettings()
                    result = AppSettingsCodec.sanitize(transform(current))
                    preferences[KEY] = AppSettingsCodec.encode(result)
                }
            } catch (_: IOException) {
                // Not persisted (storage full): the new value still applies for this run.
                result = AppSettingsCodec.sanitize(transform(_settings.value))
            }
            _settings.value = result
            loaded.complete(Unit)
            result
        }
    }

    companion object {
        private val KEY = stringPreferencesKey("app_settings_json")
        private const val FILE_NAME = "openax12_settings"

        // DataStore allows one instance per file and process.
        private val stores = HashMap<String, DataStore<Preferences>>()

        /** The process-wide DataStore of the settings file. */
        fun dataStore(context: Context, scope: CoroutineScope, dispatchers: AppDispatchers): DataStore<Preferences> {
            val appContext = context.applicationContext
            val file: File = appContext.preferencesDataStoreFile(FILE_NAME)
            return synchronized(stores) {
                stores.getOrPut(file.absolutePath) {
                    PreferenceDataStoreFactory.create(
                        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                        scope = CoroutineScope(dispatchers.io + SupervisorJob(scope.coroutineContext[Job])),
                        produceFile = { file },
                    )
                }
            }
        }
    }
}
