package app.zerorelay.data.chat

import android.content.Context
import app.zerorelay.data.local.UserPreferences
import app.zerorelay.data.model.ChatMessage
import app.zerorelay.data.model.ChatSession
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** 应用级单例：维持当前房间连接，供聊天 UI 与本地通知共用。 */
class RelayMessagingHub private constructor(context: Context) {
    val repository: ChatRepository = ChatRepository(context, UserPreferences(context))
    var activeSession: ChatSession? = null

    private val messagesByRoom = ConcurrentHashMap<String, CopyOnWriteArrayList<ChatMessage>>()

    fun recordMessage(msg: ChatMessage) {
        if (msg.roomId.isBlank()) return
        messagesByRoom.getOrPut(msg.roomId) { CopyOnWriteArrayList() }.add(msg)
    }

    fun messagesFor(roomId: String): List<ChatMessage> =
        messagesByRoom[roomId]?.toList().orEmpty()

    fun clearMessages(roomId: String) {
        messagesByRoom.remove(roomId)
    }

    fun hasActiveRoom(): Boolean = activeSession != null && repository.isInRoom(activeSession!!.roomId)

    companion object {
        @Volatile
        private var instance: RelayMessagingHub? = null

        fun get(context: Context): RelayMessagingHub {
            return instance ?: synchronized(this) {
                instance ?: RelayMessagingHub(context.applicationContext).also { instance = it }
            }
        }
    }
}
