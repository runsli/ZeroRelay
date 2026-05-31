package app.zerorelay.service

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
import androidx.core.app.ServiceCompat
import app.zerorelay.MainActivity
import app.zerorelay.R
import app.zerorelay.data.model.ChatSession

/** 返回首页后维持 relay WebSocket / 轮询，降低进程被系统回收的概率。 */
class RelayForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val peerName = intent?.getStringExtra(EXTRA_PEER_NAME).orEmpty()
        val roomId = intent?.getStringExtra(EXTRA_ROOM_ID).orEmpty()
        ensureChannel()
        val notification = buildNotification(peerName, roomId)
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
        return START_STICKY
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.foreground_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.foreground_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(peerName: String, roomId: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (roomId.isNotBlank()) putExtra(MainActivity.EXTRA_ROOM_ID, roomId)
        }
        val pending = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val body = if (peerName.isNotBlank()) {
            getString(R.string.foreground_notification_body, peerName)
        } else {
            getString(R.string.foreground_notification_body_generic)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(getString(R.string.foreground_notification_title))
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pending)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "zerorelay_relay"

        private const val EXTRA_PEER_NAME = "extra_peer_name"
        private const val EXTRA_ROOM_ID = "extra_room_id"

        fun start(context: Context, session: ChatSession) {
            val intent = Intent(context, RelayForegroundService::class.java).apply {
                putExtra(EXTRA_PEER_NAME, session.peerDisplayName)
                putExtra(EXTRA_ROOM_ID, session.roomId)
            }
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RelayForegroundService::class.java))
        }
    }
}
