package org.openax12.app

import org.openax12.core.model.AppSettings

/**
 * How long the service waits after `BOOT_COMPLETED` before it brings open-ax12 up. The stock
 * RadioMaster app also starts at boot (it holds `RECEIVE_BOOT_COMPLETED`) and may open its UI and
 * the serial port; open-ax12 has to come up *after* it, otherwise the stock app would take the
 * screen and the port from us. Raise it if the stock app is seen starting later than this.
 */
internal const val BOOT_START_DELAY_MS: Long = 15_000L

/**
 * Pause between bringing the activity to the front and the first close of the stock app, then the
 * connection. Closing is repeated by the connector's retry round when the stock app survived it:
 * on the AX12 the first kill right after the stock app lost the screen did not end it, the retry
 * about 30 s later did (docs/ASSUMPTIONS.md, a11).
 */
internal const val BOOT_FRONT_SETTLE_MS: Long = 1_500L

/** The onboarding is shown until it was finished once. */
internal fun shouldShowOnboarding(settings: AppSettings): Boolean = !settings.onboardingDone

/** The onboarding offers the "close the stock app" choice only when there is a stock app. */
internal fun onboardingOffersStockSwitch(stockInstalled: Boolean): Boolean = stockInstalled

/**
 * What a boot start does.
 *
 * @property delayMs wait before anything is touched (the stock app comes up first)
 * @property closeStock close the stock app before connecting (setting on and stock installed)
 */
internal data class BootPlan(val delayMs: Long, val closeStock: Boolean)

/**
 * The boot actions for the current settings, or null when nothing is to be done: the broadcast is
 * not `BOOT_COMPLETED` or "Start open-ax12 when the radio boots" is off.
 */
internal fun bootPlan(isBootCompleted: Boolean, settings: AppSettings, stockInstalled: Boolean): BootPlan? =
    if (isBootCompleted && settings.startOnBoot) {
        BootPlan(delayMs = BOOT_START_DELAY_MS, closeStock = settings.closeStockApp && stockInstalled)
    } else {
        null
    }
