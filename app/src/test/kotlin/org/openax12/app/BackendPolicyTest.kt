package org.openax12.app

import org.junit.Assert.assertEquals
import org.junit.Test
import org.openax12.core.model.RadioBackend

class BackendPolicyTest {

    private val arm64 = arrayOf("arm64-v8a", "armeabi-v7a")

    @Test
    fun autoOnAx12UsesTheSdk() {
        assertEquals(RadioBackend.SDK, BackendPolicy.resolve(RadioBackend.AUTO, 28, arm64))
    }

    @Test
    fun autoOnTheEmulatorUsesTheSimulator() {
        assertEquals(RadioBackend.SIMULATOR, BackendPolicy.resolve(RadioBackend.AUTO, 35, arm64))
    }

    @Test
    fun autoOnApi28WithoutArm64UsesTheSimulator() {
        assertEquals(RadioBackend.SIMULATOR, BackendPolicy.resolve(RadioBackend.AUTO, 28, arrayOf("x86_64")))
    }

    @Test
    fun explicitChoicesAreHonoured() {
        assertEquals(RadioBackend.SDK, BackendPolicy.resolve(RadioBackend.SDK, 35, arm64))
        assertEquals(RadioBackend.SIMULATOR, BackendPolicy.resolve(RadioBackend.SIMULATOR, 28, arm64))
    }
}
