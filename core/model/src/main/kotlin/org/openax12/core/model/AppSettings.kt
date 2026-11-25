package org.openax12.core.model

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable

/** App colour theme. */
@Serializable
enum class AppTheme {
    /** Stock-like icy light theme (default). */
    LIGHT,
    DARK,

    /** High-contrast theme for direct sunlight. */
    HIGH_CONTRAST,
}

/** Unit system for displayed and spoken values. */
@Serializable
enum class UnitSystem {
    METRIC,
    IMPERIAL,
}

/** Which `Radio` implementation the app uses. Changing it takes effect after an app restart. */
@Serializable
enum class RadioBackend {
    /** The SDK on the AX12, the simulator everywhere else. */
    AUTO,

    /** Always the real SDK (`SdkRadio`). */
    SDK,

    /** Always the simulator (`FakeRadio`). */
    SIMULATOR,
}

/**
 * Behaviour of the touch lock (bottom bar).
 *
 * @property unlockHoldMs how long the unlock control must be held
 * @property autoLockSec lock automatically after this many seconds without touch; 0 = never
 * @property lockWhenLinked lock automatically when the receiver link comes up
 * @property dimWhileLocked dim the screen while locked
 */
@Serializable
data class TouchLockBehaviour(
    val unlockHoldMs: Int = 1500,
    val autoLockSec: Int = 0,
    val lockWhenLinked: Boolean = false,
    val dimWhileLocked: Boolean = false,
)

/**
 * System-wide (not per-model) settings of the app, persisted by `SettingsRepository`.
 *
 * @property speechLanguage BCP-47 language tag for text-to-speech (`"en-US"`); empty = system default
 * @property speechRate text-to-speech rate, 1.0 = normal
 * @property masterVolume overall app volume in percent
 * @property volumes per-category volume in percent; missing categories count as 100
 * @property haptics master switch for vibration
 * @property logging write a CSV flight log automatically while the link is up
 * @property logIntervalMs sample interval of the flight log
 */
@Serializable
data class AppSettings(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val theme: AppTheme = AppTheme.LIGHT,
    val units: UnitSystem = UnitSystem.METRIC,
    val speechLanguage: String = "",
    val speechRate: Float = 1.0f,
    val masterVolume: Int = 100,
    val volumes: Map<AudioCategory, Int> = emptyMap(),
    val haptics: Boolean = true,
    val keepScreenOn: Boolean = true,
    val touchLock: TouchLockBehaviour = TouchLockBehaviour(),
    val radioBackend: RadioBackend = RadioBackend.AUTO,
    val logging: Boolean = false,
    val logIntervalMs: Int = 200,
) {
    /** Volume of [category] in percent (0..100). */
    fun volumeOf(category: AudioCategory): Int = (volumes[category] ?: 100).coerceIn(0, 100)

    /** Copy with the volume of [category] set. */
    fun withVolume(category: AudioCategory, percent: Int): AppSettings =
        copy(volumes = volumes + (category to percent.coerceIn(0, 100)))

    companion object {
        /** Schema version written by this app version. */
        const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}

/**
 * The coroutine dispatchers used across the app, injectable for tests.
 *
 * @property main UI thread
 * @property default CPU work (engine ticks, compilers)
 * @property io blocking file / SDK work
 */
data class AppDispatchers(
    val main: CoroutineDispatcher = Dispatchers.Main,
    val default: CoroutineDispatcher = Dispatchers.Default,
    val io: CoroutineDispatcher = Dispatchers.IO,
)
