package org.openax12.app

import org.openax12.core.model.RadioBackend

/**
 * Decides which `Radio` implementation a configured [RadioBackend] means on this device.
 * Pure so that it can be unit tested.
 */
internal object BackendPolicy {

    /** The AX12 runs Android 9 and never gets an update (CLAUDE.md, "Target device"). */
    const val AX12_API_LEVEL: Int = 28

    /** The only ABI the prebuilt SDK libraries exist for. */
    const val AX12_ABI: String = "arm64-v8a"

    /**
     * Resolves [configured] to [RadioBackend.SDK] or [RadioBackend.SIMULATOR] (never AUTO).
     *
     * `AUTO` selects the SDK on API 28 with an arm64 ABI (the AX12) and the simulator everywhere
     * else, including the API 35 arm64 emulator. `SDK` and `SIMULATOR` are honoured as they are:
     * a forced SDK on a device without the library ends in `ConnectionError.NativeLibraryUnavailable`
     * at start, which the startup error screen shows.
     */
    fun resolve(configured: RadioBackend, sdkInt: Int, supportedAbis: Array<String>): RadioBackend = when (configured) {
        RadioBackend.SDK -> RadioBackend.SDK
        RadioBackend.SIMULATOR -> RadioBackend.SIMULATOR
        RadioBackend.AUTO ->
            if (sdkInt == AX12_API_LEVEL && supportedAbis.contains(AX12_ABI)) RadioBackend.SDK else RadioBackend.SIMULATOR
    }
}
