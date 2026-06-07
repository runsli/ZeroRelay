package app.zerorelay.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import app.zerorelay.data.model.ChatKind
import app.zerorelay.data.model.ChatSession

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val roomId: String,
    val displayName: String,
    val peerContactId: String,
    val kind: String,
    val lastMessagePreview: String,
    val lastMessageTimestamp: Long,
    val lastMessageIsMine: Boolean = false,
    val unreadCount: Int = 0,
)

fun ChatSession.toConversationEntity(
    existing: ConversationEntity? = null,
): ConversationEntity =
    ConversationEntity(
        roomId = roomId,
        displayName = peerDisplayName,
        peerContactId = peerContactId,
        kind = kind.name,
        lastMessagePreview = existing?.lastMessagePreview.orEmpty(),
        lastMessageTimestamp = existing?.lastMessageTimestamp ?: 0L,
        lastMessageIsMine = existing?.lastMessageIsMine ?: false,
        unreadCount = existing?.unreadCount ?: 0,
    )

fun ConversationEntity.chatKind(): ChatKind =
    runCatching { ChatKind.valueOf(kind) }.getOrDefault(ChatKind.Direct)
