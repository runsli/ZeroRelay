package app.zerorelay.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.zerorelay.R
import app.zerorelay.data.model.ConnectionState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZeroRelayTopBar(
    roomName: String,
    connection: ConnectionState,
    onLeave: () -> Unit,
    onCopyRoomId: () -> Unit,
    onCopyServerUrl: () -> Unit,
    onRetry: (() -> Unit)?,
    detailLine: String? = null,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    val subtitle = compactTopBarSubtitle(detailLine, connection)

    CenterAlignedTopAppBar(
        modifier = modifier,
        navigationIcon = {
            IconButton(onClick = onLeave) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_leave_chat))
            }
        },
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                Text(
                    text = roomName,
                    style = MaterialTheme.typography.titleMedium,
                    color = cs.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = subtitleColor(connection, cs),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        actions = {
            when (connection) {
                ConnectionState.Connected -> {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = stringResource(R.string.cd_e2ee),
                        tint = cs.primary,
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .size(20.dp)
                            .align(Alignment.CenterVertically),
                    )
                }
                ConnectionState.Connecting -> {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .size(20.dp)
                            .align(Alignment.CenterVertically),
                        strokeWidth = 2.dp,
                        color = cs.primary,
                    )
                }
                ConnectionState.Disconnected, ConnectionState.Error -> {
                    if (onRetry != null) {
                        IconButton(onClick = onRetry) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.cd_reconnect))
                        }
                    }
                }
            }

            IconButton(onClick = { menuExpanded = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.cd_more))
            }

            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_menu_copy_fingerprint)) },
                    onClick = {
                        menuExpanded = false
                        onCopyRoomId()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_menu_copy_server)) },
                    onClick = {
                        menuExpanded = false
                        onCopyServerUrl()
                    },
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_menu_leave), color = cs.error) },
                    onClick = {
                        menuExpanded = false
                        onLeave()
                    },
                )
            }
        },
        colors = ZeroRelayTopBarDefaults.colors(),
    )
}

@Composable
private fun compactTopBarSubtitle(detailLine: String?, connection: ConnectionState): String {
    val connectionText = when (connection) {
        ConnectionState.Connected -> stringResource(R.string.chat_connection_connected)
        ConnectionState.Connecting -> stringResource(R.string.chat_connection_connecting)
        ConnectionState.Disconnected -> stringResource(R.string.chat_connection_disconnected)
        ConnectionState.Error -> stringResource(R.string.chat_connection_error)
    }
    return if (!detailLine.isNullOrBlank()) {
        "$detailLine · $connectionText"
    } else {
        connectionText
    }
}

@Composable
private fun subtitleColor(connection: ConnectionState, cs: ColorScheme): Color = when (connection) {
    ConnectionState.Connected, ConnectionState.Connecting -> cs.onSurfaceVariant
    ConnectionState.Disconnected, ConnectionState.Error -> cs.error
}
