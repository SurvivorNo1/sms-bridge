package io.github.survivorno1.smsbridge

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

/** 前台服务：常驻通知栏，开着就监听端口；关掉就停端口并清空缓存。 */
class BridgeService : Service() {
    companion object {
        @Volatile var running = false
        private const val CHANNEL = "bridge"
        private const val NOTIF_ID = 1

        fun start(c: Context) = c.startForegroundService(Intent(c, BridgeService::class.java))
        fun stop(c: Context) = c.stopService(Intent(c, BridgeService::class.java))
    }

    private var http: HttpServer? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "SMS Bridge", NotificationManager.IMPORTANCE_LOW)
        )
        Store.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val port = Prefs.port(this)
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("SMS Bridge 运行中")
            .setContentText("局域网 :$port 监听中，进 app 关开关即停止")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }

        if (http == null) {
            try {
                http = HttpServer(port) { Prefs.secret(this) }.also { it.start() }
                running = true
            } catch (e: Exception) {
                running = false
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        http?.stop()
        http = null
        Store.clear()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
