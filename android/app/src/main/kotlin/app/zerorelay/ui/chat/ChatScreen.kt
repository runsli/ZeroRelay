package app.zerorelay.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.zerorelay.R
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import android.view.WindowManager
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import app.zerorelay.data.model.ChatSession
import app.zerorelay.data.model.ConnectionState
import app.zerorelay.ui.components.ChatMessageRow
import app.zerorelay.ui.components.UserInputBar
import app.zerorelay.ui.components.ZeroRelayTopBar
import app.zerorelay.ui.components.buildMessageRows
import app.zerorelay.ui.notification.ActiveChatTracker
import app.zerorelay.ui.notification.ChatNotificationHelper
import app.zerorelay.ui.snackbar.AppSnackbarBus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    session: ChatSession,
    onLeave: () -> Unit,
    allowScreenshots: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val vm: ChatViewModel = viewModel(
        key = "chat-${session.kind.name}-${session.roomId}",
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ChatViewModel(context.applicationContext as android.app.Application) as T
        },
    )
    LaunchedEffect(session.roomId, session.kind) {
        vm.bindSession(session)
    }
    DisposableEffect(session.roomId) {
        ActiveChatTracker.visibleRoomId = session.roomId
        ChatNotificationHelper.cancel(context, session.roomId)
        onDispose {
            ActiveChatTracker.visibleRoomId = null
        }
    }
    val activity = LocalContext.current as? ComponentActivity
    DisposableEffect(activity, allowScreenshots) {
        val window = activity?.window
        if (!allowScreenshots) {
            window?.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE,
            )
        }
        onDispose {
            if (!allowScreenshots) {
                window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }
    val state by vm.uiState.collectAsState()
    var draft by rememberSaveable { mutableStateOf("") }
    var showDisconnectDialog by remember { mutableStateOf(false) }

    fun backToList() {
        vm.detachUi()
        onLeave()
    }

    BackHandler(onBack = ::backToList)
    val clipboard = remember {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }
    val listState = rememberLazyListState()
    val messageRows = buildMessageRows(state.messages)

    fun copyText(label: String, text: String, message: String) {
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        AppSnackbarBus.show(message)
    }

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    val copiedGroupIdMsg = stringResource(R.string.snackbar_copied_group_id)
    val copiedFingerprintMsg = stringResource(R.string.snackbar_copied_fingerprint)
    val copiedServerMsg = stringResource(R.string.snackbar_copied_server)

    if (showDisconnectDialog) {
        AlertDialog(
            onDismissRequest = { showDisconnectDialog = false },
            title = { Text(stringResource(R.string.chat_disconnect_dialog_title), style = MaterialTheme.typography.headlineMedium) },
            text = {
                Text(
                    stringResource(R.string.chat_disconnect_dialog_body),
                    style = MaterialTheme.typography.bodyLarge,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDisconnectDialog = false
                    vm.leaveChat()
                    onLeave()
                }) { Text(stringResource(R.string.action_disconnect)) }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnectDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            ZeroRelayTopBar(
                roomName = session.peerDisplayName,
                detailLine = if (session.isGroup) {
                    stringResource(R.string.chat_group_meta, session.memberCount)
                } else {
                    null
                },
                connection = state.connection,
                onBack = ::backToList,
                onDisconnect = { showDisconnectDialog = true },
                onCopyRoomId = {
                    val label = if (session.isGroup) "groupId" else "fingerprint"
                    val msg = if (session.isGroup) copiedGroupIdMsg else copiedFingerprintMsg
                    copyText(label, session.peerFingerprint, msg)
                },
                onCopyServerUrl = {
                    copyText("serverUrl", session.serverUrl, copiedServerMsg)
                },
                onRetry = if (state.connection != ConnectionState.Connected) vm::retry else null,
            )
        },
        bottomBar = {
            UserInputBar(
                value = draft,
                onValueChange = { draft = it },
                onSend = {
                    vm.sendMessage(draft)
                    draft = ""
                },
                enabled = state.connection == ConnectionState.Connected && !state.peerNeedsVerification,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets.exclude(WindowInsets.ime),
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            AnimatedVisibility(
                visible = state.peerNeedsVerification,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            stringResource(R.string.chat_unverified_block_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Text(
                            stringResource(R.string.chat_unverified_block_body, state.peerFingerprint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        TextButton(onClick = vm::markPeerVerified) {
                            Text(stringResource(R.string.chat_verify_mark_safe))
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = state.initError != null,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                state.initError?.let { error ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                if (state.messages.isEmpty()) {
                    EmptyConversation(modifier = Modifier.align(Alignment.Center))
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(messageRows, key = { it.message.id }) { row ->
                            ChatMessageRow(
                                ui = row,
                                modifier = Modifier.padding(vertical = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyConversation(modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = modifier.padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = cs.secondaryContainer,
            modifier = Modifier.size(72.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Forum,
                contentDescription = stringResource(R.string.cd_empty_chat),
                modifier = Modifier.padding(18.dp),
                tint = cs.onSecondaryContainer,
            )
        }
        Text(
            text = stringResource(R.string.chat_empty_title),
            style = MaterialTheme.typography.headlineMedium,
            color = cs.onSurface,
        )
        Text(
            text = stringResource(R.string.chat_empty_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = cs.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
