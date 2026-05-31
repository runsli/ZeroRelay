package app.zerorelay.ui.chat

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import app.zerorelay.R
import androidx.lifecycle.viewModelScope
import app.zerorelay.data.chat.RelayMessagingHub
import app.zerorelay.data.identity.IdentityStore
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

    private fun appStr(@StringRes resId: Int, vararg args: Any): String =
        getApplication<Application>().getString(resId, *args)

    init {
        viewModelScope.launch {
            hub.roomMessageEvents.collect { msg ->
                val room = boundSession?.roomId ?: return@collect
                if (msg.roomId != room) return@collect
                _uiState.update { state ->
                    if (state.messages.any { it.id == msg.id }) state
                    else state.copy(messages = state.messages + msg)
                }
            }
        }
        viewModelScope.launch {
            hub.connection.collect { conn ->
                if (boundSession != null) {
                    _uiState.update { it.copy(connection = conn) }
                }
            }
        }
    }

    fun bindSession(session: ChatSession) {
        boundSession = session
        hub.attachSession(session)
        val needsVerify = session.kind == ChatKind.Direct &&
            identityStore.findContact(session.peerContactId)?.verified != true
        viewModelScope.launch {
            val identity = identityStore.getOrCreateIdentity()
            val senderId = IdentityCrypto.senderIdFromPublicKey(identity.publicKey)
            if (repository.isInRoom(session.roomId)) {
                val conn = hub.connection.value
                if (conn == ConnectionState.Disconnected || conn == ConnectionState.Error) {
                    repository.reconnectTransport()
                }
                _uiState.update {
                    it.copy(
                        roomId = session.roomId,
                        senderId = senderId,
                        messages = hub.messagesFor(session.roomId),
                        connection = hub.connection.value,
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
        val room = boundSession?.roomId ?: hub.listeningSession()?.roomId
        boundSession = null
        hub.clearSession()
        if (room != null) hub.clearMessages(room)
        repository.leaveRoom()
        _uiState.update {
            it.copy(
                messages = emptyList(),
                connection = ConnectionState.Disconnected,
            )
        }
    }

    /** 返回首页：保持 relay 连接，会话转入 detached。 */
    fun detachUi() {
        hub.detachSession()
        boundSession = null
        _uiState.update {
            it.copy(
                messages = emptyList(),
                connection = ConnectionState.Disconnected,
            )
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
