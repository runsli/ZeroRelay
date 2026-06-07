package app.zerorelay.data.local

import android.content.Context
import app.zerorelay.data.crypto.IdentityCrypto
import app.zerorelay.data.model.ChatGroup
import app.zerorelay.data.model.ChatKind
import app.zerorelay.data.model.ChatMessage
import app.zerorelay.data.model.ChatSession
import app.zerorelay.data.model.Contact
import app.zerorelay.data.model.Identity
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

    fun registerContact(identity: Identity, contact: Contact) {
        scope.launch {
            upsertContactConversation(identity, contact)
        }
    }

    fun registerGroup(group: ChatGroup) {
        scope.launch {
            upsertGroupConversation(group)
        }
    }

    fun syncPeers(identity: Identity, contacts: List<Contact>, groups: List<ChatGroup>) {
        scope.launch {
            contacts.forEach { upsertContactConversation(identity, it) }
            groups.forEach { upsertGroupConversation(it) }
        }
    }

    fun unregisterContact(identity: Identity, contact: Contact) {
        scope.launch {
            val roomId = roomIdForContact(identity, contact)
            conversationDao.deleteByRoom(roomId)
        }
    }

    fun unregisterGroup(group: ChatGroup) {
        scope.launch {
            conversationDao.deleteByRoom(group.roomId)
        }
    }

    private suspend fun upsertContactConversation(identity: Identity, contact: Contact) {
        val roomId = roomIdForContact(identity, contact)
        val existing = conversationDao.get(roomId)
        if (existing != null) {
            conversationDao.upsert(
                existing.copy(
                    displayName = contact.displayName,
                    peerContactId = contact.id,
                    kind = ChatKind.Direct.name,
                ),
            )
            return
        }
        conversationDao.upsert(
            ConversationEntity(
                roomId = roomId,
                displayName = contact.displayName,
                peerContactId = contact.id,
                kind = ChatKind.Direct.name,
                lastMessagePreview = "",
                lastMessageTimestamp = contact.addedAt,
                lastMessageIsMine = false,
                unreadCount = 0,
            ),
        )
    }

    private suspend fun upsertGroupConversation(group: ChatGroup) {
        val existing = conversationDao.get(group.roomId)
        if (existing != null) {
            conversationDao.upsert(
                existing.copy(
                    displayName = group.displayName,
                    peerContactId = group.id,
                    kind = ChatKind.Group.name,
                ),
            )
            return
        }
        conversationDao.upsert(
            ConversationEntity(
                roomId = group.roomId,
                displayName = group.displayName,
                peerContactId = group.id,
                kind = ChatKind.Group.name,
                lastMessagePreview = "",
                lastMessageTimestamp = group.createdAt,
                lastMessageIsMine = false,
                unreadCount = 0,
            ),
        )
    }

    private fun roomIdForContact(identity: Identity, contact: Contact): String {
        val peerPublicKey = IdentityCrypto.decodePublicKey(contact.publicKeyBase64)
        return IdentityCrypto.deriveRoomId(identity.publicKey, peerPublicKey)
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
