package com.cma.kreels.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.content.ContextCompat

/** Does no work itself: being a foreground service keeps the process (and the export coroutine) alive in the background. */
class KeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CH, "Reel export", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, CH).setContentTitle("Rendering your reel")
            .setContentText("Export is running").setSmallIcon(android.R.drawable.stat_sys_download).setOngoing(true).build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        return START_NOT_STICKY
    }

    companion object {
        private const val CH = "export"
        fun start(ctx: Context) = ContextCompat.startForegroundService(ctx, Intent(ctx, KeepAliveService::class.java))
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, KeepAliveService::class.java)) }
    }
}
