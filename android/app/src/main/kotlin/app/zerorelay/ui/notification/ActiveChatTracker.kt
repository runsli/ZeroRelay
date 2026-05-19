package app.zerorelay.ui.notification

/** 当前 UI 是否应抑制该房间的消息通知。 */
object ActiveChatTracker {
    @Volatile
    var visibleRoomId: String? = null

    @Volatile
    var appInForeground: Boolean = true

    fun shouldNotify(roomId: String): Boolean {
        if (!appInForeground) return true
        return visibleRoomId != roomId
    }
}
