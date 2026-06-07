package app.zerorelay.data.chat

import android.content.Context
import app.zerorelay.ui.notification.ChatNotificationHelper

/** 应用级 relay 事件泵启动入口（各房间事件由 [RelayMessagingHub] 在创建 repository 时接线）。 */
object RelaySessionCoordinator {
    private var started = false

    fun start(context: Context) {
        if (started) return
        started = true
        val appContext = context.applicationContext
        RelayMessagingHub.get(appContext)
        ChatNotificationHelper.ensureChannel(appContext)
    }
}
