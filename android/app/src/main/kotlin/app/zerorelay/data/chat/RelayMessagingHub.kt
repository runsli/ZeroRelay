package app.zerorelay.data.chat

import android.content.Context
import app.zerorelay.data.local.UserPreferences
import app.zerorelay.data.local.DetachedSessionStore
import app.zerorelay.data.model.ChatMessage
import app.zerorelay.data.model.ChatSession
import app.zerorelay.data.model.ConnectionState
import app.zerorelay.service.RelayForegroundService
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
    private val appContext = context.applicationContext
    private val preferences = UserPreferences(appContext)
    val repository: ChatRepository = ChatRepository(appContext, preferences)

    /** 用户正在查看的聊天（前台 UI 绑定）。 */
    var activeSession: ChatSession? = null
        private set

    /** 已返回首页但仍保持中继连接的会话。 */
    var detachedSession: ChatSession? = null
        private set

    private val _detachedSession = MutableStateFlow<ChatSession?>(null)
    val detachedSessionFlow: StateFlow<ChatSession?> = _detachedSession.asStateFlow()

    private fun setDetachedSession(session: ChatSession?) {
        detachedSession = session
        _detachedSession.value = session
    }

    private val messagesByRoom = ConcurrentHashMap<String, CopyOnWriteArrayList<ChatMessage>>()

    private val _roomMessageEvents = MutableSharedFlow<ChatMessage>(extraBufferCapacity = 64)
    val roomMessageEvents: SharedFlow<ChatMessage> = _roomMessageEvents.asSharedFlow()

    private val _connection = MutableStateFlow(ConnectionState.Disconnected)
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    internal fun updateConnection(state: ConnectionState) {
        _connection.value = state
    }

    fun listeningSession(): ChatSession? = activeSession ?: detachedSession

    /** 进入聊天页：前台绑定会话。切换至不同 room 时由 [ChatRepository.joinRoom] 断开旧 transport（单房间模型，多房间见 #13）。 */
    fun attachSession(session: ChatSession) {
        if (detachedSession?.roomId == session.roomId) {
            setDetachedSession(null)
        } else if (detachedSession != null) {
            setDetachedSession(null)
        }
        activeSession = session
        DetachedSessionStore(appContext).clear()
        RelayForegroundService.stop(appContext)
    }

    /** 返回首页：保持 transport，会话转入 detached。 */
    fun detachSession() {
        val session = activeSession ?: return
        setDetachedSession(session)
        activeSession = null
        DetachedSessionStore(appContext).save(session)
        syncForegroundService()
    }

    /** 冷启动或恢复：重建 detached 会话引用（transport 需由调用方 joinRoom）。 */
    fun restoreDetachedSession(session: ChatSession) {
        setDetachedSession(session)
        activeSession = null
        DetachedSessionStore(appContext).save(session)
        syncForegroundService()
    }

    /** 用户明确离开：清除前台与 detached 会话引用（不断开 transport，由 leaveRoom 负责）。 */
    fun clearSession() {
        activeSession = null
        setDetachedSession(null)
        DetachedSessionStore(appContext).clear()
        RelayForegroundService.stop(appContext)
    }

    /** 设置页切换后台保活开关时同步 Foreground Service。 */
    fun syncForegroundService() {
        val session = detachedSession
        if (session != null && preferences.getKeepAliveInBackground()) {
            RelayForegroundService.start(appContext, session)
        } else {
            RelayForegroundService.stop(appContext)
        }
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
