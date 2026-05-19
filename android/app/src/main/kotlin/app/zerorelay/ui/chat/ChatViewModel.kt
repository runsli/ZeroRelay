package app.zerorelay.ui.chat

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import app.zerorelay.R
import androidx.lifecycle.viewModelScope
import app.zerorelay.data.chat.RelayMessagingHub
import app.zerorelay.data.identity.IdentityStore
import app.zerorelay.data.local.UserPreferences
import app.zerorelay.data.crypto.IdentityCrypto
import app.zerorelay.data.model.ChatKind
import app.zerorelay.data.model.ChatMessage
import app.zerorelay.data.model.ChatSession
import app.zerorelay.data.model.ConnectionState
import app.zerorelay.ui.snackbar.AppSnackbarBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatUiState(
    val roomId: String = "",
    val senderId: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val connection: ConnectionState = ConnectionState.Connecting,
    val initError: String? = null,
    val peerNeedsVerification: Boolean = false,
    val peerFingerprint: String = "",
)

class ChatViewModel(
    application: Application,
) : AndroidViewModel(application) {

    private val hub = RelayMessagingHub.get(application)
    private val repository = hub.repository
    private val identityStore = IdentityStore(application)

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var boundSession: ChatSession? = null
    private var collectorsStarted = false

    private fun appStr(@StringRes resId: Int, vararg args: Any): String =
        getApplication<Application>().getString(resId, *args)

    fun bindSession(session: ChatSession) {
        boundSession = session
        hub.activeSession = session
        startCollectorsIfNeeded()
        val needsVerify = session.kind == ChatKind.Direct &&
            identityStore.findContact(session.peerContactId)?.verified != true
        viewModelScope.launch {
            val identity = identityStore.getOrCreateIdentity()
            val senderId = IdentityCrypto.senderIdFromPublicKey(identity.publicKey)
            if (repository.isInRoom(session.roomId)) {
                val conn = repository.connection.value
                if (conn == ConnectionState.Disconnected || conn == ConnectionState.Error) {
                    repository.reconnectTransport()
                }
                _uiState.update {
                    it.copy(
                        roomId = session.roomId,
                        senderId = senderId,
                        messages = hub.messagesFor(session.roomId),
                        connection = repository.connection.value,
                        initError = null,
                        peerNeedsVerification = needsVerify,
                        peerFingerprint = session.peerFingerprint,
                    )
                }
                return@launch
            }
            _uiState.value = ChatUiState(
                roomId = session.roomId,
                senderId = senderId,
                connection = ConnectionState.Connecting,
                peerNeedsVerification = needsVerify,
                peerFingerprint = session.peerFingerprint,
            )
            val ok = repository.joinRoom(
                serverUrl = session.serverUrl,
                room = session.roomId,
                sessionKey = session.sessionKey,
                sender = senderId,
                contactId = session.peerContactId,
                kind = session.kind,
                groupKeys = session.groupKeysByVersion,
                groupKeyVer = session.groupKeyVersion,
                localPublicKey = if (session.kind == ChatKind.Direct) identity.publicKey else null,
                peerPublicKey = session.peerPublicKeyBase64?.let(IdentityCrypto::decodePublicKey),
                identityPrivateKey = identity.privateKey,
                identityPublicKey = identity.publicKey,
            )
            _uiState.update {
                it.copy(
                    initError = if (!ok) appStr(R.string.error_chat_connect) else null,
                    senderId = senderId,
                )
            }
        }
    }

    /** 用户明确退出聊天：断开中继并停止后台拉取。 */
    fun leaveChat() {
        val room = boundSession?.roomId
        boundSession = null
        hub.activeSession = null
        if (room != null) hub.clearMessages(room)
        repository.leaveRoom()
        _uiState.update {
            it.copy(
                messages = emptyList(),
                connection = ConnectionState.Disconnected,
            )
        }
    }

    /** 仅清空当前 ViewModel 展示（返回首页时仍保持房间连接以便通知）。 */
    fun detachUi() {
        boundSession = null
        _uiState.update {
            it.copy(
                messages = emptyList(),
                connection = ConnectionState.Disconnected,
            )
        }
    }

    private fun startCollectorsIfNeeded() {
        if (collectorsStarted) return
        collectorsStarted = true
        viewModelScope.launch {
            repository.messages.collect { msg ->
                val activeRoom = boundSession?.roomId ?: hub.activeSession?.roomId ?: return@collect
                if (msg.roomId.isNotBlank() && msg.roomId != activeRoom) return@collect
                hub.recordMessage(msg)
                if (boundSession != null) {
                    _uiState.update { it.copy(messages = it.messages + msg) }
                }
            }
        }
        viewModelScope.launch {
            repository.connection.collect { conn ->
                _uiState.update { it.copy(connection = conn) }
            }
        }
        viewModelScope.launch {
            repository.errors.collect { err ->
                AppSnackbarBus.show(err)
            }
        }
    }

    fun sendMessage(text: String) {
        val content = text.trim()
        if (content.isEmpty()) return
        if (_uiState.value.peerNeedsVerification) {
            AppSnackbarBus.show(appStr(R.string.chat_snackbar_verify_before_send))
            return
        }
        viewModelScope.launch {
            val ok = repository.sendMessage(content)
            if (!ok) {
                AppSnackbarBus.show(appStr(R.string.chat_snackbar_send_failed))
            }
        }
    }

    fun retry() {
        val session = boundSession ?: return
        viewModelScope.launch {
            val identity = identityStore.getOrCreateIdentity()
            val senderId = IdentityCrypto.senderIdFromPublicKey(identity.publicKey)
            _uiState.update {
                it.copy(
                    connection = ConnectionState.Connecting,
                    initError = null,
                    senderId = senderId,
                )
            }
            val ok = if (repository.isInRoom(session.roomId)) {
                repository.reconnectTransport()
            } else {
                repository.joinRoom(
                    session.serverUrl,
                    session.roomId,
                    session.sessionKey,
                    senderId,
                    session.peerContactId,
                    session.kind,
                    session.groupKeysByVersion,
                    session.groupKeyVersion,
                    if (session.kind == ChatKind.Direct) identity.publicKey else null,
                    session.peerPublicKeyBase64?.let(IdentityCrypto::decodePublicKey),
                    identity.privateKey,
                    identity.publicKey,
                )
            }
            if (!ok) {
                _uiState.update { it.copy(initError = appStr(R.string.error_chat_connect)) }
            }
        }
    }

    fun markPeerVerified() {
        val session = boundSession ?: return
        if (session.isGroup) return
        identityStore.markContactVerified(session.peerContactId)
        _uiState.update { it.copy(peerNeedsVerification = false) }
        AppSnackbarBus.show(appStr(R.string.chat_snackbar_peer_verified))
    }

    override fun onCleared() {
        detachUi()
        super.onCleared()
    }
}
