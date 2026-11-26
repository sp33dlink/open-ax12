package org.openax12.app

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log

/** The stock RadioMaster app, which holds the radio's serial port while its process lives. */
internal const val STOCK_APP_PACKAGE: String = "com.Flyshark.RadioMasterAX"

/**
 * What the connection logic needs from the stock app. [StockApp] implements it on Android; tests
 * use a fake.
 */
internal interface StockAppControl {
    /** The stock package is installed (false on the emulator and the simulator backend's hosts). */
    val isInstalled: Boolean

    /** Kills the stock app's background process so that the serial port is free. Never throws. */
    fun close()
}

/**
 * Android side of the stock-app handling: is it installed, close it, open its app-info page.
 *
 * Apps cannot see other processes on the AX12 (`/proc` is mounted with hidepid=2), so whether the
 * stock app is *running* cannot be asked; [close] is simply safe to call at any time. It uses
 * `ActivityManager.killBackgroundProcesses`, which needs the normal permission
 * `KILL_BACKGROUND_PROCESSES` (no prompt) and only works while the target is in the background,
 * which it always is while open-ax12 is in front (docs/ASSUMPTIONS.md, b1).
 */
internal class StockApp(context: Context) : StockAppControl {

    private val appContext: Context = context.applicationContext

    override val isInstalled: Boolean by lazy { queryInstalled() }

    override fun close() {
        try {
            appContext.getSystemService(ActivityManager::class.java).killBackgroundProcesses(STOCK_APP_PACKAGE)
        } catch (e: Exception) {
            Log.w(TAG, "the stock app could not be closed", e)
        }
    }

    /** The intent that opens the system "App info" page of the stock app (Force stop lives there). */
    fun appInfoIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", STOCK_APP_PACKAGE, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Opens the stock app's App info page; does nothing if no settings app answers. */
    fun openAppInfo() {
        try {
            appContext.startActivity(appInfoIntent())
        } catch (e: Exception) {
            Log.w(TAG, "the app info page could not be opened", e)
        }
    }

    @Suppress("DEPRECATION")
    private fun queryInstalled(): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.packageManager.getPackageInfo(STOCK_APP_PACKAGE, PackageManager.PackageInfoFlags.of(0))
        } else {
            appContext.packageManager.getPackageInfo(STOCK_APP_PACKAGE, 0)
        }
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    } catch (e: Exception) {
        Log.w(TAG, "the stock app could not be looked up", e)
        false
    }

    private companion object {
        const val TAG = "StockApp"
    }
}
