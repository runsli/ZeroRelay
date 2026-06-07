package app.zerorelay.data.local

import androidx.room.Entity
import androidx.room.Index
import app.zerorelay.data.model.ChatMessage

@Entity(
    tableName = "messages",
    primaryKeys = ["id"],
    indices = [Index(value = ["roomId", "timestamp"])],
)
data class MessageEntity(
    val id: String,
    val roomId: String,
    val content: String,
    val timestamp: Long,
    val senderId: String,
    val isMine: Boolean,
    val legacyDecrypt: Boolean = false,
)

fun ChatMessage.toEntity(): MessageEntity =
    MessageEntity(
        id = id,
        roomId = roomId,
        content = content,
        timestamp = timestamp,
        senderId = senderId,
        isMine = isMine,
        legacyDecrypt = legacyDecrypt,
    )

fun MessageEntity.toChatMessage(): ChatMessage =
    ChatMessage(
        id = id,
        roomId = roomId,
        content = content,
        timestamp = timestamp,
        senderId = senderId,
        isMine = isMine,
        legacyDecrypt = legacyDecrypt,
    )
