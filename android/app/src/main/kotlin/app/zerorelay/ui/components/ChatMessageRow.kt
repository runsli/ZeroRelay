package app.zerorelay.ui.components

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.zerorelay.R
import app.zerorelay.data.model.ChatMessage
import app.zerorelay.data.model.DeliveryStatus
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
    onRetryFailed: ((messageId: String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val message = ui.message
    val cs = MaterialTheme.colorScheme

    if (message.isMine) {
        val failed = message.deliveryStatus == DeliveryStatus.FAILED
        val retryLabel = stringResource(R.string.chat_message_tap_retry)
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.Bottom,
        ) {
            OutgoingDeliveryStatus(
                status = message.deliveryStatus,
                modifier = Modifier.padding(end = 6.dp, bottom = 10.dp),
            )
            MessageBubble(
                text = message.content,
                time = message.formattedTime,
                background = if (failed) cs.errorContainer else cs.primary,
                contentColor = if (failed) cs.onErrorContainer else cs.onPrimary,
                shape = BubbleShapeMe,
                modifier = if (failed && onRetryFailed != null) {
                    Modifier.clickable { onRetryFailed(message.id) }
                        .semantics { contentDescription = retryLabel }
                } else {
                    Modifier
                },
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
private fun OutgoingDeliveryStatus(
    status: DeliveryStatus,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    when (status) {
        DeliveryStatus.SENDING -> {
            CircularProgressIndicator(
                modifier = modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = cs.onSurfaceVariant,
            )
        }
        DeliveryStatus.SENT -> {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = stringResource(R.string.chat_message_status_sent),
                modifier = modifier.size(16.dp),
                tint = cs.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
        DeliveryStatus.FAILED -> {
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = stringResource(R.string.chat_message_status_failed),
                modifier = modifier.size(18.dp),
                tint = cs.error,
            )
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
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
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
