package org.openax12.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.openax12.core.model.LiveSnapshot
import org.openax12.core.model.UnitConversion
import org.openax12.core.runtime.AppGraph
import org.openax12.core.runtime.StartupState

/**
 * Foreground service that keeps the radio connection, the runtime engine and the audio engine
 * alive while other apps are in front (SPEC section 9, design rule 2). It runs
 * `AppGraph.start()` and shows a notification with model, link and running timers.
 *
 * **Service type: `mediaPlayback`.** The service exists to play voice, vario tones, beeps and sound
 * files; that is what the type is for. It is declared in the manifest (needed from API 34, where
 * `FOREGROUND_SERVICE_MEDIA_PLAYBACK` is the matching permission), passed to `startForeground`
 * (API 29+) and ignored on API 28 where no types exist. `connectedDevice` was rejected because on
 * API 34+ it demands a Bluetooth / USB / network permission the serial link does not involve, and
 * `specialUse` needs a declared subtype and a store review. See docs/ASSUMPTIONS.md (a1).
 *
 * The notification's Quit action shuts the graph down (radio, audio, runtime) and ends the
 * process, which also frees the serial port for the stock app and avoids restarting the native
 * SDK inside a live process (docs/ASSUMPTIONS.md, a5).
 */
class RadioService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var graph: AppGraph
    private var quitting = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        graph = applicationContext.appGraph
        createChannel()
        enterForeground(buildNotification(NotificationModel()))
        graph.scope.launch { graph.start() }
        observeAndNotify()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_QUIT) quit()
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        if (!quitting) {
            // The service went away without the Quit action (stopService): stop what it kept alive.
            graph.scope.launch { graph.shutdown() }
        }
        super.onDestroy()
    }

    private fun enterForeground(notification: Notification) {
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } catch (e: Exception) {
            // Not allowed to be a foreground service right now (API 31+ background start): the
            // service still runs and the graph starts; only the process protection is weaker.
            Log.w(TAG, "startForeground failed", e)
        }
    }

    @OptIn(FlowPreview::class)
    private fun observeAndNotify() {
        val live = graph.radio.live
            // Link quality flickers by a percent several times per second: show it in 10 % steps.
            .map { LiveSummary(roundToTen(it.status.linkQuality), runningTimers(it)) }
            .distinctUntilChanged()
        scope.launch {
            combine(graph.models.currentModel, graph.startup, live) { model, startup, summary ->
                NotificationModel(
                    modelName = model?.name.orEmpty(),
                    startup = startup,
                    linkQuality = summary.linkQuality,
                    timers = summary.timers,
                )
            }.distinctUntilChanged()
                // At most one notification update per second (running timers tick once per second).
                .sample(NOTIFICATION_MIN_INTERVAL_MS)
                .collect { model ->
                val manager = getSystemService(NotificationManager::class.java)
                manager.notify(NOTIFICATION_ID, buildNotification(model))
            }
        }
    }

    private fun roundToTen(percent: Int): Int = (percent + 5) / 10 * 10

    /** "T1 3:20  T2 0:05" for the timers that run right now. */
    private fun runningTimers(live: LiveSnapshot): String =
        live.timers.filter { it.running }
            .joinToString("  ") { "T${it.index + 1} ${UnitConversion.formatTime(it.count)}" }

    private fun quit() {
        if (quitting) return
        quitting = true
        graph.scope.launch {
            withTimeoutOrNull(SHUTDOWN_TIMEOUT_MS) { graph.shutdown() }
            withContext(Dispatchers.Main) {
                ServiceCompat.stopForeground(this@RadioService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            Process.killProcess(Process.myPid())
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW)
        channel.description = getString(R.string.notification_channel_description)
        channel.setShowBadge(false)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(model: NotificationModel): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val quit = PendingIntent.getService(
            this,
            1,
            Intent(this, RadioService::class.java).setAction(ACTION_QUIT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = model.modelName.ifEmpty { getString(R.string.app_name) }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_radio)
            .setContentTitle(title)
            .setContentText(statusText(model))
            .setContentIntent(open)
            .addAction(0, getString(R.string.notification_action_quit), quit)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun statusText(model: NotificationModel): String = when (model.startup) {
        StartupState.NotStarted -> getString(R.string.notification_text_starting)
        StartupState.ConnectingRadio, StartupState.LoadingLibrary -> getString(R.string.notification_text_connecting)
        is StartupState.RadioFailed -> getString(R.string.notification_text_failed)
        StartupState.Running -> {
            val link = if (model.linkQuality > 0) getString(R.string.notification_text_link, model.linkQuality) else getString(R.string.notification_text_no_link)
            if (model.timers.isEmpty()) link else "$link  ${model.timers}"
        }
    }

    private data class LiveSummary(val linkQuality: Int, val timers: String)

    private data class NotificationModel(
        val modelName: String = "",
        val startup: StartupState = StartupState.NotStarted,
        val linkQuality: Int = 0,
        val timers: String = "",
    )

    companion object {
        private const val NOTIFICATION_MIN_INTERVAL_MS = 1_000L
        private const val TAG = "RadioService"
        private const val CHANNEL_ID = "radio_connection"
        private const val NOTIFICATION_ID = 1
        private const val SHUTDOWN_TIMEOUT_MS = 4_000L
        private const val ACTION_QUIT = "org.openax12.app.action.QUIT"

        /**
         * Starts the service as a foreground service.
         *
         * @return false if the system refused (the caller then starts the graph itself)
         */
        fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, RadioService::class.java))
            true
        } catch (e: Exception) {
            Log.w(TAG, "the service could not be started", e)
            false
        }
    }
}
