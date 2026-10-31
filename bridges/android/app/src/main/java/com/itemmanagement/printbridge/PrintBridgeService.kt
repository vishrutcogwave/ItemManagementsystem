package com.itemmanagement.printbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class PrintBridgeService : Service() {

    private lateinit var config: AppConfig
    private var server: LocalBridgeServer? = null
    private var scheduler: ScheduledExecutorService? = null

    override fun onCreate() {
        super.onCreate()
        config = AppConfig(this)
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification(config.lastResult))
        isRunning = true
        if (server == null) startServer()
        startSync()
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        server?.stop()
        server = null
        scheduler?.shutdownNow()
        scheduler = null
        config.lastResult = "Bridge stopped."
        stopForeground(true)
        super.onDestroy()
    }

    private fun startServer() {
        val bridge = LocalBridgeServer(config) { message ->
            config.lastResult = message
            updateNotification(message)
        }
        if (bridge.start(config.port)) {
            server = bridge
            config.lastResult = "Listening on 127.0.0.1:${config.port}"
        } else {
            server = null
            config.lastResult = bridge.lastError ?: "The print bridge could not start."
        }
        updateNotification(config.lastResult)
    }

    private fun startSync() {
        if (scheduler != null) return
        scheduler = Executors.newSingleThreadScheduledExecutor()
        scheduler?.scheduleWithFixedDelay({ syncConfig() }, 3, 300, TimeUnit.SECONDS)
    }

    private fun syncConfig() {
        try {
            val current = AppConfig(this)
            if (!current.isPaired || current.serverUrl.isBlank()) return
            val outcome = BackendClient(current).fetchConfig()
            if (!outcome.success) Log.w("PrintBridge", "Config sync: ${outcome.message}")
        } catch (ex: Exception) {
            Log.w("PrintBridge", "Config sync failed", ex)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Keeps the silent receipt bridge available to the store website."
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(result: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, PrintBridgeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val where = config.branchName.ifBlank { "not paired yet" }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_stat_print)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText("127.0.0.1:${config.port} · $where")
            .setSubText(result)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(R.drawable.ic_stat_print, getString(R.string.notification_stop), stop).build())
            .build()
    }

    private fun updateNotification(result: String) {
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, buildNotification(result))
        } catch (_: Exception) {
        }
    }

    companion object {
        const val ACTION_STOP = "com.itemmanagement.printbridge.STOP"
        const val CHANNEL_ID = "print_bridge_service"
        const val NOTIFICATION_ID = 4101

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, PrintBridgeService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (ex: Exception) {
                Log.w("PrintBridge", "Could not start the bridge service", ex)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PrintBridgeService::class.java))
        }
    }
}
