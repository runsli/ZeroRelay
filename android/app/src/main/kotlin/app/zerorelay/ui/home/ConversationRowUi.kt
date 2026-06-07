package app.zerorelay.ui.home

import app.zerorelay.data.local.ConversationEntity
import app.zerorelay.data.local.chatKind
import app.zerorelay.data.model.ChatGroup
import app.zerorelay.data.model.ChatKind
import app.zerorelay.data.model.Contact
import java.util.Calendar

data class ConversationRowUi(
    val roomId: String,
    val displayName: String,
    val lastMessagePreview: String,
    val lastMessageIsMine: Boolean,
    val lastMessageTimestamp: Long,
    val unreadCount: Int,
    val isGroup: Boolean,
    val peerNeedsVerification: Boolean,
)

fun ConversationEntity.toRowUi(
    contacts: List<Contact>,
    groups: List<ChatGroup>,
): ConversationRowUi {
    val kind = chatKind()
    val contact = contacts.find { it.id == peerContactId }
    val group = groups.find { it.id == peerContactId }
    val resolvedName = when (kind) {
        ChatKind.Direct -> contact?.displayName?.takeIf { it.isNotBlank() } ?: displayName
        ChatKind.Group -> group?.displayName?.takeIf { it.isNotBlank() } ?: displayName
    }
    return ConversationRowUi(
        roomId = roomId,
        displayName = resolvedName,
        lastMessagePreview = lastMessagePreview,
        lastMessageIsMine = lastMessageIsMine,
        lastMessageTimestamp = lastMessageTimestamp,
        unreadCount = unreadCount,
        isGroup = kind == ChatKind.Group,
        peerNeedsVerification = kind == ChatKind.Direct && contact?.verified != true,
    )
}

fun ConversationRowUi.formattedTime(): String {
    if (lastMessageTimestamp <= 0L) return ""
    val now = Calendar.getInstance()
    val msg = Calendar.getInstance().apply { timeInMillis = lastMessageTimestamp }
    return when {
        now.get(Calendar.YEAR) == msg.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == msg.get(Calendar.DAY_OF_YEAR) ->
            "%02d:%02d".format(
                msg.get(Calendar.HOUR_OF_DAY),
                msg.get(Calendar.MINUTE),
            )
        now.get(Calendar.YEAR) == msg.get(Calendar.YEAR) ->
            "%d/%d".format(
                msg.get(Calendar.MONTH) + 1,
                msg.get(Calendar.DAY_OF_MONTH),
            )
        else ->
            "%d/%d/%d".format(
                msg.get(Calendar.YEAR),
                msg.get(Calendar.MONTH) + 1,
                msg.get(Calendar.DAY_OF_MONTH),
            )
    }
}
