package org.openax12.core.data

import kotlinx.coroutines.flow.StateFlow
import org.openax12.core.model.AppSettings

/**
 * System-wide app settings (not per model), persisted in the app's private storage.
 *
 * Main-safe; callable from any dispatcher.
 */
interface SettingsRepository {

    /**
     * Current settings. Holds the defaults until the stored settings were loaded — use
     * [awaitLoaded] where the real values matter at startup (e.g. the radio backend).
     */
    val settings: StateFlow<AppSettings>

    /** Suspends until the stored settings were read once and returns them. */
    suspend fun awaitLoaded(): AppSettings

    /** Atomically applies [transform] to the current settings, persists and returns the result. */
    suspend fun update(transform: (AppSettings) -> AppSettings): AppSettings
}
