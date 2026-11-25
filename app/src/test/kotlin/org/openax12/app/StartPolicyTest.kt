package org.openax12.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openax12.core.model.AppSettings

class StartPolicyTest {

    @Test
    fun `a fresh install and an upgraded install see the onboarding`() {
        assertTrue(shouldShowOnboarding(AppSettings()))
    }

    @Test
    fun `a finished onboarding is not shown again`() {
        assertFalse(shouldShowOnboarding(AppSettings(onboardingDone = true)))
    }

    @Test
    fun `the stock switch is offered only when the stock app is installed`() {
        assertTrue(onboardingOffersStockSwitch(stockInstalled = true))
        assertFalse(onboardingOffersStockSwitch(stockInstalled = false))
    }

    @Test
    fun `boot with the defaults waits for the stock app and then closes it`() {
        val plan = bootPlan(isBootCompleted = true, settings = AppSettings(), stockInstalled = true)
        assertEquals(BootPlan(delayMs = BOOT_START_DELAY_MS, closeStock = true), plan)
    }

    @Test
    fun `boot with the start switch off does nothing`() {
        assertNull(bootPlan(true, AppSettings(startOnBoot = false), stockInstalled = true))
    }

    @Test
    fun `other broadcasts do nothing`() {
        assertNull(bootPlan(false, AppSettings(), stockInstalled = true))
    }

    @Test
    fun `boot with closing off starts without closing the stock app`() {
        val plan = bootPlan(true, AppSettings(closeStockApp = false), stockInstalled = true)
        assertNotNull(plan)
        assertFalse(plan!!.closeStock)
    }

    @Test
    fun `boot without a stock app has nothing to close`() {
        val plan = bootPlan(true, AppSettings(), stockInstalled = false)
        assertNotNull(plan)
        assertFalse(plan!!.closeStock)
    }

    @Test
    fun `the boot delay is a real wait`() {
        assertTrue(BOOT_START_DELAY_MS >= 10_000L)
    }
}
