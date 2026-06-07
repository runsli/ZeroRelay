package app.zerorelay.ui.notification

import android.content.Context
import app.zerorelay.data.chat.RelayMessagingHub
import app.zerorelay.data.model.ChatMessage

object MessageNotificationController {
    fun maybeNotify(context: Context, msg: ChatMessage) {
        if (msg.isMine) return
        if (!ActiveChatTracker.shouldNotify(msg.roomId)) return
        val hub = RelayMessagingHub.get(context)
        val session = hub.sessionForRoom(msg.roomId) ?: return
        if (!hub.repositoryFor(msg.roomId)?.isInRoom(msg.roomId).orFalse()) return
        ChatNotificationHelper.show(
            context = context,
            roomId = msg.roomId,
            session = session,
        )
    }

    private fun Boolean?.orFalse(): Boolean = this == true
}
