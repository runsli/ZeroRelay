package app.zerorelay.data.local

import android.content.Context
import app.zerorelay.data.model.ChatKind
import app.zerorelay.data.model.ChatMessage
import app.zerorelay.data.model.ChatSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ConversationStore(context: Context) {
    private val db = MessageDatabase.get(context.applicationContext)
    private val conversationDao = db.conversationDao()
    private val messageDao = db.messageDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun observeAll(): Flow<List<ConversationEntity>> = conversationDao.observeAll()

    fun registerSession(session: ChatSession) {
        scope.launch {
            val existing = conversationDao.get(session.roomId)
            conversationDao.upsert(
                session.toConversationEntity(existing).copy(unreadCount = 0),
            )
        }
    }

    fun updateOnMessage(message: ChatMessage, incrementUnread: Boolean) {
        if (message.roomId.isBlank()) return
        scope.launch {
            val existing = conversationDao.get(message.roomId)
            val preview = message.content.lineSequence().first().take(120)
            val unread = (existing?.unreadCount ?: 0) +
                if (incrementUnread && !message.isMine) 1 else 0
            conversationDao.upsert(
                ConversationEntity(
                    roomId = message.roomId,
                    displayName = existing?.displayName ?: message.roomId.take(12),
                    peerContactId = existing?.peerContactId.orEmpty(),
                    kind = existing?.kind ?: ChatKind.Direct.name,
                    lastMessagePreview = preview,
                    lastMessageTimestamp = message.timestamp,
                    lastMessageIsMine = message.isMine,
                    unreadCount = unread,
                ),
            )
        }
    }

    fun markRead(roomId: String) {
        scope.launch {
            conversationDao.markRead(roomId)
        }
    }

    suspend fun backfillFromMessagesIfNeeded() = withContext(Dispatchers.IO) {
        for (roomId in messageDao.distinctRoomIds()) {
            if (conversationDao.get(roomId) != null) continue
            val latest = messageDao.latestByRoom(roomId) ?: continue
            conversationDao.upsert(
                ConversationEntity(
                    roomId = roomId,
                    displayName = roomId.take(12),
                    peerContactId = "",
                    kind = ChatKind.Direct.name,
                    lastMessagePreview = latest.content.take(120),
                    lastMessageTimestamp = latest.timestamp,
                    lastMessageIsMine = latest.isMine,
                    unreadCount = 0,
                ),
            )
        }
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        conversationDao.deleteAll()
    }

    suspend fun deleteByRoom(roomId: String) = withContext(Dispatchers.IO) {
        conversationDao.deleteByRoom(roomId)
    }
}
