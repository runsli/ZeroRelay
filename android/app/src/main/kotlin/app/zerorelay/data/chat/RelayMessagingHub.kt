package app.zerorelay.data.chat

import android.content.Context
import app.zerorelay.data.local.UserPreferences
import app.zerorelay.data.model.ChatMessage
import app.zerorelay.data.model.ChatSession
import app.zerorelay.data.model.ConnectionState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** 应用级单例：维持当前房间连接，供聊天 UI 与本地通知共用。 */
class RelayMessagingHub private constructor(context: Context) {
    val repository: ChatRepository = ChatRepository(context, UserPreferences(context))

    /** 用户正在查看的聊天（前台 UI 绑定）。 */
    var activeSession: ChatSession? = null
        private set

    /** 已返回首页但仍保持中继连接的会话。 */
    var detachedSession: ChatSession? = null
        private set

    private val messagesByRoom = ConcurrentHashMap<String, CopyOnWriteArrayList<ChatMessage>>()

    private val _roomMessageEvents = MutableSharedFlow<ChatMessage>(extraBufferCapacity = 64)
    val roomMessageEvents: SharedFlow<ChatMessage> = _roomMessageEvents.asSharedFlow()

    private val _connection = MutableStateFlow(ConnectionState.Disconnected)
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    internal fun updateConnection(state: ConnectionState) {
        _connection.value = state
    }

    fun listeningSession(): ChatSession? = activeSession ?: detachedSession

    /** 进入聊天页：前台绑定会话。 */
    fun attachSession(session: ChatSession) {
        if (detachedSession?.roomId == session.roomId) {
            detachedSession = null
        } else if (detachedSession != null) {
            detachedSession = null
        }
        activeSession = session
    }

    /** 返回首页：保持 transport，会话转入 detached。 */
    fun detachSession() {
        val session = activeSession ?: return
        detachedSession = session
        activeSession = null
    }

    /** 用户明确离开：清除前台与 detached 会话引用（不断开 transport，由 leaveRoom 负责）。 */
    fun clearSession() {
        activeSession = null
        detachedSession = null
    }

    fun recordMessage(msg: ChatMessage) {
        if (msg.roomId.isBlank()) return
        messagesByRoom.getOrPut(msg.roomId) { CopyOnWriteArrayList() }.add(msg)
        _roomMessageEvents.tryEmit(msg)
    }

    fun messagesFor(roomId: String): List<ChatMessage> =
        messagesByRoom[roomId]?.toList().orEmpty()

    fun clearMessages(roomId: String) {
        messagesByRoom.remove(roomId)
    }

    fun hasActiveRoom(): Boolean {
        val session = listeningSession() ?: return false
        return repository.isInRoom(session.roomId)
    }

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
