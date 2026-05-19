package app.zerorelay.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.zerorelay.data.model.ChatMessage
import app.zerorelay.ui.theme.BubbleShapeMe
import app.zerorelay.ui.theme.BubbleShapeOther

data class MessageRowUi(
    val message: ChatMessage,
    val showAuthorHeader: Boolean,
    val showAvatar: Boolean,
)

fun buildMessageRows(messages: List<ChatMessage>): List<MessageRowUi> =
    messages.mapIndexed { index, message ->
        val prev = messages.getOrNull(index - 1)
        val next = messages.getOrNull(index + 1)
        MessageRowUi(
            message = message,
            showAuthorHeader = !message.isMine && prev?.senderId != message.senderId,
            showAvatar = !message.isMine && next?.senderId != message.senderId,
        )
    }

@Composable
fun ChatMessageRow(
    ui: MessageRowUi,
    modifier: Modifier = Modifier,
) {
    val message = ui.message
    val cs = MaterialTheme.colorScheme

    if (message.isMine) {
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            MessageBubble(
                text = message.content,
                time = message.formattedTime,
                background = cs.primary,
                contentColor = cs.onPrimary,
                shape = BubbleShapeMe,
            )
        }
    } else {
        Row(
            modifier = modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            if (ui.showAvatar) {
                SenderAvatar(
                    senderId = message.senderId,
                    modifier = Modifier.padding(end = 8.dp),
                )
            } else {
                Spacer(Modifier.width(42.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                if (ui.showAuthorHeader) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = message.senderId,
                            style = MaterialTheme.typography.labelLarge,
                            color = cs.primary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = message.formattedTime,
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                }
                MessageBubble(
                    text = message.content,
                    time = if (ui.showAuthorHeader) null else message.formattedTime,
                    background = cs.secondaryContainer,
                    contentColor = cs.onSecondaryContainer,
                    shape = BubbleShapeOther,
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(
    text: String,
    time: String?,
    background: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color,
    shape: androidx.compose.foundation.shape.RoundedCornerShape,
) {
    Surface(
        color = background,
        shape = shape,
        tonalElevation = 1.dp,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = contentColor,
            )
            if (time != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = time,
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = 0.70f),
                    modifier = Modifier.align(Alignment.End),
                )
            }
        }
    }
}

@Composable
private fun SenderAvatar(
    senderId: String,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val initial = senderId.removePrefix("user_").take(1).uppercase().ifEmpty { "?" }
    Surface(
        modifier = modifier
            .size(36.dp)
            .clip(CircleShape),
        color = cs.tertiaryContainer,
        shape = CircleShape,
    ) {
        Text(
            text = initial,
            modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.titleMedium,
            color = cs.onTertiaryContainer,
            textAlign = TextAlign.Center,
        )
    }
}
