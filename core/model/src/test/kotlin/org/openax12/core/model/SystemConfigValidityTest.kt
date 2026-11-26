package org.openax12.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SystemConfigValidityTest {

    private fun fields(
        fsMode: Int = 0,
        stick: Int = 1,
        rf: Int = 0,
        alarm: Int = 33,
        baud: Int = 170,
        match: Int = 0,
        beep: Int = 0,
    ) = RawSystemFields(
        failsafeMode = fsMode,
        failsafeValues = List(FAILSAFE_CHANNEL_COUNT) { 0 },
        stickMode = stick,
        preFlightCheck = List(PREFLIGHT_SOURCE_COUNT) { PREFLIGHT_NO_CHECK },
        rfModuleMode = rf,
        alarmVoltageDeciVolt = alarm,
        stickBeep = beep,
        modelMatchId = match,
        crsfBaudRate = baud,
    )

    @Test
    fun `the values the AX12 really has are plausible`() {
        assertNull(SystemConfigValidity.problem(fields()))
    }

    @Test
    fun `the garbage the SDK getter returned on the AX12 is not plausible`() {
        // rf = 2 would be believable, but fsMode 192, baud 206 and match 181 are not.
        assertNotNull(SystemConfigValidity.problem(fields(fsMode = 192, rf = 2, alarm = 0, baud = 206, match = 181)))
        assertNotNull(SystemConfigValidity.problem(fields(baud = 206)))
        assertNotNull(SystemConfigValidity.problem(fields(fsMode = 192)))
    }

    @Test
    fun `failsafe mode must be 0 to 3`() {
        for (code in 0..3) assertNull(SystemConfigValidity.problem(fields(fsMode = code)))
        assertNotNull(SystemConfigValidity.problem(fields(fsMode = 4)))
        assertNotNull(SystemConfigValidity.problem(fields(fsMode = -1)))
    }

    @Test
    fun `rf module mode must be 0, 1, 2 or 255`() {
        for (code in listOf(0, 1, 2, 255)) assertNull(SystemConfigValidity.problem(fields(rf = code)))
        for (code in listOf(3, 4, 128, 254, 256, -1)) assertNotNull(SystemConfigValidity.problem(fields(rf = code)))
    }

    @Test
    fun `stick mode must be 0 or 1`() {
        assertNull(SystemConfigValidity.problem(fields(stick = 0)))
        assertNull(SystemConfigValidity.problem(fields(stick = 1)))
        assertNotNull(SystemConfigValidity.problem(fields(stick = 2)))
        assertNotNull(SystemConfigValidity.problem(fields(stick = -1)))
    }

    @Test
    fun `alarm voltage must fit a byte`() {
        assertNull(SystemConfigValidity.problem(fields(alarm = 0)))
        assertNull(SystemConfigValidity.problem(fields(alarm = 255)))
        assertNotNull(SystemConfigValidity.problem(fields(alarm = 256)))
        assertNotNull(SystemConfigValidity.problem(fields(alarm = -1)))
    }

    @Test
    fun `baud codes are 1, 2, 3 and the uninitialised 170`() {
        for (code in listOf(1, 2, 3, 170)) assertNull(SystemConfigValidity.problem(fields(baud = code)))
        for (code in listOf(0, 4, 169, 171, 206, 255)) assertNotNull(SystemConfigValidity.problem(fields(baud = code)))
        assertEquals(0xAA, SystemConfigValidity.BAUD_UNINITIALISED)
    }

    @Test
    fun `array lengths and ranges are checked`() {
        assertNotNull(SystemConfigValidity.problem(fields().copy(failsafeValues = List(15) { 0 })))
        assertNotNull(SystemConfigValidity.problem(fields().copy(failsafeValues = List(16) { 200 })))
        assertNotNull(SystemConfigValidity.problem(fields().copy(preFlightCheck = List(35) { 127 })))
        assertNotNull(SystemConfigValidity.problem(fields().copy(preFlightCheck = List(36) { 101 })))
        assertNull(SystemConfigValidity.problem(fields().copy(preFlightCheck = List(36) { -100 })))
        assertNotNull(SystemConfigValidity.problem(fields(match = 181 + 100)))
        assertNotNull(SystemConfigValidity.problem(fields(beep = -1)))
    }

    @Test
    fun `the mapped check accepts the simulator and rejects out-of-range numbers`() {
        assertNull(SystemConfigValidity.problem(SystemConfig(alarmVoltageDeciVolt = 33)))
        assertNull(SystemConfigValidity.problem(SystemConfig(alarmVoltageDeciVolt = 33, crsfBaudRate = 170)))
        assertNotNull(SystemConfigValidity.problem(SystemConfig(alarmVoltageDeciVolt = 300)))
        assertNotNull(SystemConfigValidity.problem(SystemConfig(alarmVoltageDeciVolt = 33, crsfBaudRate = 206)))
        assertNotNull(SystemConfigValidity.problem(SystemConfig(alarmVoltageDeciVolt = 33, modelMatchId = 181 + 100)))
        assertNotNull(SystemConfigValidity.problem(SystemConfig(alarmVoltageDeciVolt = 33, failsafeValues = List(3) { 0 })))
    }
}
