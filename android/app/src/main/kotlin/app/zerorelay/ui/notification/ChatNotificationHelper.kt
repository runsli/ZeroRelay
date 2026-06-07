package app.zerorelay.ui.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.zerorelay.MainActivity
import app.zerorelay.R
import app.zerorelay.data.model.ChatSession

object ChatNotificationHelper {
    const val CHANNEL_ID = "zerorelay_messages"
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notification_channel_desc)
        }
        manager.createNotificationChannel(channel)
    }

    fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** 不含消息正文；可展示本地联系人/群名称。 */
    fun show(context: Context, roomId: String, session: ChatSession?) {
        if (!canPost(context)) return
        ensureChannel(context)
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_ROOM_ID, roomId)
        }
        val pending = PendingIntent.getActivity(
            context,
            roomId.hashCode(),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val peerName = session?.peerDisplayName?.trim().orEmpty()
        val title = if (peerName.isNotEmpty()) {
            context.getString(R.string.notification_message_title, peerName)
        } else {
            context.getString(R.string.notification_generic_title)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.notification_generic_body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        NotificationManagerCompat.from(context).notify(roomId.hashCode(), notification)
    }

    fun cancel(context: Context, roomId: String) {
        NotificationManagerCompat.from(context).cancel(roomId.hashCode())
    }
}
