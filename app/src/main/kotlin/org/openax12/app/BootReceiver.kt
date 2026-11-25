package org.openax12.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Starts open-ax12 after the radio booted ("Start open-ax12 when the radio boots").
 *
 * The receiver is always registered; it checks the setting itself ([bootPlan]) instead of the
 * manifest component being toggled with `PackageManager`, so that the setting needs no
 * bookkeeping that could drift. The settings are already read when the application object exists
 * (`DefaultAppGraph` waits for them), so the check is a field read and nothing blocks the
 * receiver thread; the waiting for the stock app happens in [RadioService], not here.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val settings = app.appGraph.settings.settings.value
        bootPlan(intent.action == Intent.ACTION_BOOT_COMPLETED, settings, app.stockApp.isInstalled) ?: return
        RadioService.start(app, afterBoot = true)
    }
}
