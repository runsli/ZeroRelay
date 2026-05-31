package app.zerorelay.ui.notification

import android.content.Context
import app.zerorelay.data.chat.RelayMessagingHub
import app.zerorelay.data.model.ChatMessage

object MessageNotificationController {
    fun maybeNotify(context: Context, msg: ChatMessage) {
        if (msg.isMine) return
        if (!ActiveChatTracker.shouldNotify(msg.roomId)) return
        val session = RelayMessagingHub.get(context).listeningSession()
        if (session == null || session.roomId != msg.roomId) return
        ChatNotificationHelper.show(
            context = context,
            roomId = msg.roomId,
            session = session,
        )
    }
}
