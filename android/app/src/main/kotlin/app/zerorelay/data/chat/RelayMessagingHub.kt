package app.zerorelay.data.chat

import android.content.Context
import app.zerorelay.data.local.ConversationStore
import app.zerorelay.data.local.DetachedSessionStore
import app.zerorelay.data.local.MessageStore
import app.zerorelay.data.local.UserPreferences
import app.zerorelay.data.model.ChatMessage
import app.zerorelay.data.model.ChatSession
import app.zerorelay.data.model.ConnectionState
import app.zerorelay.service.RelayForegroundService
import app.zerorelay.ui.notification.ActiveChatTracker
import app.zerorelay.ui.notification.MessageNotificationController
import app.zerorelay.ui.snackbar.AppSnackbarBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** 应用级单例：多房间 relay 连接（最多 K 个后台 detached + 1 个前台 active）。 */
class RelayMessagingHub private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = UserPreferences(appContext)
    private val messageStore = MessageStore(appContext)
    val conversationStore = ConversationStore(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val repositories = ConcurrentHashMap<String, ChatRepository>()
    private val wiredRooms = ConcurrentHashMap.newKeySet<String>()
    private val roomConnections = ConcurrentHashMap<String, MutableStateFlow<ConnectionState>>()

    /** 用户正在查看的聊天（前台 UI 绑定）。 */
    var activeSession: ChatSession? = null
        private set

    private val detachedSessions =
        Collections.synchronizedMap(linkedMapOf<String, ChatSession>())

    private val _detachedSessionsFlow = MutableStateFlow<List<ChatSession>>(emptyList())
    val detachedSessionsFlow: StateFlow<List<ChatSession>> = _detachedSessionsFlow.asStateFlow()

    /** 最近一个 detached 会话（兼容单房间 UI）。 */
    val detachedSession: ChatSession?
        get() = detachedSessions.values.lastOrNull()

    val detachedSessionsList: List<ChatSession>
        get() = synchronized(detachedSessions) { detachedSessions.values.toList() }

    private val messagesByRoom = ConcurrentHashMap<String, CopyOnWriteArrayList<ChatMessage>>()

    private val _roomMessageEvents = MutableSharedFlow<ChatMessage>(extraBufferCapacity = 64)
    val roomMessageEvents: SharedFlow<ChatMessage> = _roomMessageEvents.asSharedFlow()

    private val _connection = MutableStateFlow(ConnectionState.Disconnected)
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    fun sessionForRoom(roomId: String): ChatSession? =
        activeSession?.takeIf { it.roomId == roomId }
            ?: detachedSessions[roomId]

    fun listeningSession(): ChatSession? = activeSession ?: detachedSession

    fun repositoryFor(roomId: String): ChatRepository? = repositories[roomId]

    fun getOrCreateRepository(roomId: String): ChatRepository =
        repositories.getOrPut(roomId) {
            ChatRepository(appContext, preferences)
        }.also { wireRepository(it, roomId) }

    /** 打开新房间前确保未超过「K 个 detached + 1 个 active」上限。 */
    fun ensureCapacityForNewRoom(roomId: String) {
        if (repositories.containsKey(roomId)) return
        val maxDetached = preferences.getMaxBackgroundSessions()
        val maxRepos = maxDetached + 1
        while (repositories.size >= maxRepos) {
            val victim = synchronized(detachedSessions) { detachedSessions.keys.firstOrNull() }
                ?: break
            leaveRoom(victim)
        }
    }

    /** 进入聊天页：绑定前台会话，不断开其他 detached 房间的 transport。 */
    fun attachSession(session: ChatSession) {
        synchronized(detachedSessions) {
            detachedSessions.remove(session.roomId)
        }
        activeSession = session
        publishDetachedSessions()
        persistDetachedSessions()
        syncForegroundService()
        refreshActiveConnection()
    }

    /** 返回首页：当前会话转入 detached，超出 K 时淘汰最久未用的 detached 房间。 */
    fun detachSession() {
        val session = activeSession ?: return
        activeSession = null
        synchronized(detachedSessions) {
            detachedSessions.remove(session.roomId)
            val maxDetached = preferences.getMaxBackgroundSessions()
            while (detachedSessions.size >= maxDetached) {
                val eldest = detachedSessions.keys.firstOrNull() ?: break
                detachedSessions.remove(eldest)
                evictRepository(eldest)
            }
            detachedSessions[session.roomId] = session
        }
        publishDetachedSessions()
        persistDetachedSessions()
        syncForegroundService()
        _connection.value = ConnectionState.Disconnected
    }

    fun restoreDetachedSession(session: ChatSession) {
        restoreDetachedSessions(listOf(session))
    }

    fun restoreDetachedSessions(sessions: Collection<ChatSession>) {
        synchronized(detachedSessions) {
            sessions.forEach { detachedSessions[it.roomId] = it }
        }
        activeSession = null
        trimDetachedToCap()
    }

    /** 断开指定房间并移除会话引用。 */
    fun leaveRoom(roomId: String) {
        if (activeSession?.roomId == roomId) {
            activeSession = null
        }
        synchronized(detachedSessions) {
            detachedSessions.remove(roomId)
        }
        evictRepository(roomId)
        publishDetachedSessions()
        persistDetachedSessions()
        syncForegroundService()
        refreshActiveConnection()
    }

    /** 用户明确离开当前前台聊天（兼容旧 API）。 */
    fun clearSession() {
        val room = activeSession?.roomId
        activeSession = null
        if (room != null) {
            synchronized(detachedSessions) { detachedSessions.remove(room) }
            evictRepository(room)
        }
        publishDetachedSessions()
        persistDetachedSessions()
        syncForegroundService()
        _connection.value = ConnectionState.Disconnected
    }

    fun connectionFor(roomId: String): StateFlow<ConnectionState> =
        roomConnections.getOrPut(roomId) { MutableStateFlow(ConnectionState.Disconnected) }

    internal fun updateRoomConnection(roomId: String, state: ConnectionState) {
        connectionFor(roomId).value = state
        if (activeSession?.roomId == roomId) {
            _connection.value = state
        }
    }

    fun aggregatedDetachedConnection(): ConnectionState {
        val states = synchronized(detachedSessions) {
            detachedSessions.keys.mapNotNull { roomConnections[it]?.value }
        }
        return when {
            states.isEmpty() -> ConnectionState.Disconnected
            states.any { it == ConnectionState.Connected } -> ConnectionState.Connected
            states.any { it == ConnectionState.Connecting } -> ConnectionState.Connecting
            states.any { it == ConnectionState.Error } -> ConnectionState.Error
            else -> ConnectionState.Disconnected
        }
    }

    fun trimDetachedToCap() {
        synchronized(detachedSessions) {
            val maxDetached = preferences.getMaxBackgroundSessions()
            while (detachedSessions.size > maxDetached) {
                val eldest = detachedSessions.keys.firstOrNull() ?: break
                detachedSessions.remove(eldest)
                evictRepository(eldest)
            }
        }
        publishDetachedSessions()
        persistDetachedSessions()
        syncForegroundService()
    }

    fun syncForegroundService() {
        val sessions = detachedSessionsList
        if (sessions.isNotEmpty() && preferences.getKeepAliveInBackground()) {
            RelayForegroundService.start(appContext, sessions)
        } else {
            RelayForegroundService.stop(appContext)
        }
    }

    fun recordMessage(msg: ChatMessage) {
        if (msg.roomId.isBlank()) return
        val list = messagesByRoom.getOrPut(msg.roomId) { CopyOnWriteArrayList() }
        if (list.none { it.id == msg.id }) {
            list.add(msg)
            _roomMessageEvents.tryEmit(msg)
        }
        messageStore.persist(msg)
    }

    fun messagesFor(roomId: String): List<ChatMessage> =
        messagesByRoom[roomId]?.toList().orEmpty()

    fun seedMessages(roomId: String, messages: List<ChatMessage>) {
        if (messages.isEmpty()) return
        val list = messagesByRoom.getOrPut(roomId) { CopyOnWriteArrayList() }
        val existingIds = list.mapTo(mutableSetOf()) { it.id }
        messages.forEach { msg ->
            if (msg.id !in existingIds) list.add(msg)
        }
    }

    fun clearMessages(roomId: String) {
        messagesByRoom.remove(roomId)
    }

    fun clearAllInMemoryMessages() {
        messagesByRoom.clear()
    }

    suspend fun loadPersistedMessages(roomId: String): List<ChatMessage> =
        messageStore.loadForRoom(roomId)

    fun registerConversation(session: ChatSession) {
        conversationStore.registerSession(session)
    }

    fun updateConversationOnMessage(msg: ChatMessage, incrementUnread: Boolean) {
        conversationStore.updateOnMessage(msg, incrementUnread)
    }

    suspend fun backfillConversationsFromMessages() {
        conversationStore.backfillFromMessagesIfNeeded()
    }

    suspend fun clearAllPersistedMessages() {
        messageStore.deleteAll()
        conversationStore.deleteAll()
        clearAllInMemoryMessages()
    }

    fun hasActiveRoom(): Boolean =
        repositories.values.any { repo ->
            repo.connection.value != ConnectionState.Disconnected
        }

    private fun evictRepository(roomId: String) {
        repositories.remove(roomId)?.leaveRoom()
        wiredRooms.remove(roomId)
        roomConnections.remove(roomId)
    }

    private fun wireRepository(repo: ChatRepository, roomId: String) {
        if (!wiredRooms.add(roomId)) return
        repo.messages
            .onEach { msg ->
                recordMessage(msg)
                updateConversationOnMessage(
                    msg,
                    incrementUnread = ActiveChatTracker.shouldNotify(msg.roomId) && !msg.isMine,
                )
                MessageNotificationController.maybeNotify(appContext, msg)
            }
            .launchIn(scope)
        repo.connection
            .onEach { updateRoomConnection(roomId, it) }
            .launchIn(scope)
        repo.errors
            .onEach { AppSnackbarBus.show(it) }
            .launchIn(scope)
    }

    private fun publishDetachedSessions() {
        _detachedSessionsFlow.value = detachedSessionsList
    }

    private fun persistDetachedSessions() {
        DetachedSessionStore(appContext).saveAll(detachedSessionsList)
    }

    private fun refreshActiveConnection() {
        val roomId = activeSession?.roomId
        _connection.value = if (roomId != null) {
            connectionFor(roomId).value
        } else {
            ConnectionState.Disconnected
        }
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
