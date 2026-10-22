package org.opengamepad.bt

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
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.opengamepad.MainActivity

/**
 * Keeps the app at foreground-service importance while Bluetooth mode is on.
 * Android's HID Device service unregisters apps that leave the foreground, so
 * without this the gamepad vanishes during pairing or when switching apps.
 */
class BluetoothKeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Gamepad Bluetooth", NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("OpenGamepad")
            .setContentText("Gamepad Bluetooth attivo")
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
        )
        return START_STICKY
    }

    companion object {
        private const val CHANNEL = "bluetooth"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, BluetoothKeepAliveService::class.java))

        fun stop(context: Context) {
            context.stopService(Intent(context, BluetoothKeepAliveService::class.java))
        }
    }
}
