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
        val sessionCount = intent?.getIntExtra(EXTRA_SESSION_COUNT, 0) ?: 0
        val summary = intent?.getStringExtra(EXTRA_SUMMARY).orEmpty()
        val roomId = intent?.getStringExtra(EXTRA_ROOM_ID).orEmpty()
        ensureChannel()
        val notification = buildNotification(sessionCount, summary, roomId)
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

    private fun buildNotification(sessionCount: Int, summary: String, roomId: String): Notification {
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
        val body = when {
            sessionCount > 1 ->
                getString(R.string.foreground_notification_body_multi, sessionCount)
            summary.isNotBlank() ->
                getString(R.string.foreground_notification_body, summary)
            else ->
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

        private const val EXTRA_SESSION_COUNT = "extra_session_count"
        private const val EXTRA_SUMMARY = "extra_summary"
        private const val EXTRA_ROOM_ID = "extra_room_id"

        fun start(context: Context, sessions: List<ChatSession>) {
            if (sessions.isEmpty()) {
                stop(context)
                return
            }
            val primary = sessions.last()
            val summary = when (sessions.size) {
                1 -> primary.peerDisplayName
                else -> sessions.joinToString("、") { it.peerDisplayName }.take(80)
            }
            val intent = Intent(context, RelayForegroundService::class.java).apply {
                putExtra(EXTRA_SESSION_COUNT, sessions.size)
                putExtra(EXTRA_SUMMARY, summary)
                putExtra(EXTRA_ROOM_ID, primary.roomId)
            }
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RelayForegroundService::class.java))
        }
    }
}
