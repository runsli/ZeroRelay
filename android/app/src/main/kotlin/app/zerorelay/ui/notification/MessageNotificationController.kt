package app.zerorelay.ui.notification

import android.content.Context
import app.zerorelay.R
import app.zerorelay.data.chat.RelayMessagingHub
import app.zerorelay.data.model.ChatMessage
import app.zerorelay.data.model.ChatSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

object MessageNotificationController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false

    fun start(context: Context) {
        if (started) return
        started = true
        val appContext = context.applicationContext
        ChatNotificationHelper.ensureChannel(appContext)
        RelayMessagingHub.get(appContext).repository.messages
            .onEach { msg -> maybeNotify(appContext, msg) }
            .launchIn(scope)
    }

    private fun maybeNotify(context: Context, msg: ChatMessage) {
        if (msg.isMine) return
        if (!ActiveChatTracker.shouldNotify(msg.roomId)) return
        val session = RelayMessagingHub.get(context).activeSession
        if (session == null || session.roomId != msg.roomId) return
        ChatNotificationHelper.show(
            context = context,
            roomId = msg.roomId,
            session = session,
        )
    }
}
