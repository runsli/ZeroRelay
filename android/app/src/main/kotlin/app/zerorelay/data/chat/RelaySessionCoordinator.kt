package app.zerorelay.data.chat

import android.content.Context
import app.zerorelay.ui.notification.ChatNotificationHelper
import app.zerorelay.ui.notification.MessageNotificationController
import app.zerorelay.ui.snackbar.AppSnackbarBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/** 应用级 relay 事件泵：消息落 hub、连接状态、错误提示、通知（生命周期独立于 ChatViewModel）。 */
object RelaySessionCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false

    fun start(context: Context) {
        if (started) return
        started = true
        val appContext = context.applicationContext
        val hub = RelayMessagingHub.get(appContext)
        ChatNotificationHelper.ensureChannel(appContext)

        hub.repository.messages
            .onEach { msg ->
                hub.recordMessage(msg)
                MessageNotificationController.maybeNotify(appContext, msg)
            }
            .launchIn(scope)

        hub.repository.connection
            .onEach { hub.updateConnection(it) }
            .launchIn(scope)

        hub.repository.errors
            .onEach { AppSnackbarBus.show(it) }
            .launchIn(scope)
    }
}
