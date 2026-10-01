package com.hakim3691.bta

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Keeps the scanner process alive while the user does something else with the
 * phone.
 *
 * A backgrounded app has no execution guarantee: the OS freezes the process,
 * the websocket dies, and REST calls get throttled - which is exactly the
 * "feed dead after switching apps" failure. A *foreground service* is the
 * platform's supported way to opt into continued execution: the process keeps
 * running, so the socket stays up and scanning never stops.
 *
 * The service owns no logic. It exists purely as a process-lifetime anchor:
 * the scanner lives in [ArbApplication], which outlives any activity, and this
 * service simply holds the foreground notification that stops the system from
 * freezing that process. Starting it is idempotent, so screens can call it
 * whenever the scanner starts.
 */
class ScannerForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        // Phase 5: START_REDELIVERED_INTENT with an explicit state restore.
        // The service is the only process-lifetime anchor this app has, so it
        // is also the only place that knows "the OS restarted me" - which
        // means the scanner the user left running was killed with it. The
        // restore is idempotent (start() refuses a second run), a deliberate
        // stop clears the service so nothing is redelivered, and if the
        // restarted process cannot reach Binance the controller surfaces its
        // own failure state instead of a dead process wearing a running badge.
        if (intent != null) {
            restoreRunningState(this)
        }
        return Service.START_REDELIVER_INTENT
    }

    /**
     * Re-launches the scanner if the application was restarted underneath it.
     * Called from [onStartCommand] when the system redelivered a start intent,
     * i.e. the process died while a scan session was live.
     */
    private fun restoreRunningState(context: Context) {
        val app = context.applicationContext as? ArbApplication ?: return
        val controller = app.scannerController
        if (controller.state.value != com.hakim3691.bta.scanner.ScannerState.STOPPED) return
        android.util.Log.i(TAG, "Process restored by the system; resuming the scan session")
        com.hakim3691.bta.log.LogRepository.warn(
            "main",
            "Process was killed in the background - resuming the scan session"
        )
        controller.requestStart()
    }

    private fun buildNotification(): Notification {
        val tapIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(tapIntent)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "scanner_service"
        private const val NOTIFICATION_ID = 0xB7A
        private const val TAG = "ScannerForegroundService"

        /** Idempotently promotes the process to a foreground service. */
        fun start(context: Context) {
            val intent = Intent(context, ScannerForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ScannerForegroundService::class.java))
        }

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }
}
