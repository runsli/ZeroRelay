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
import kotlinx.coroutines.Job
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
    private val identityStore = IdentityStore(application)

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var boundSession: ChatSession? = null
    private var connectionJob: Job? = null

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
    }

    fun bindSession(session: ChatSession) {
        boundSession = session
        hub.attachSession(session)
        hub.registerConversation(session)
        val needsVerify = session.kind == ChatKind.Direct &&
            identityStore.findContact(session.peerContactId)?.verified != true
        val repository = hub.getOrCreateRepository(session.roomId)
        connectionJob?.cancel()
        connectionJob = viewModelScope.launch {
            hub.connectionFor(session.roomId).collect { conn ->
                if (boundSession?.roomId == session.roomId) {
                    _uiState.update { it.copy(connection = conn) }
                }
            }
        }
        viewModelScope.launch {
            val identity = identityStore.getOrCreateIdentity()
            val senderId = IdentityCrypto.senderIdFromPublicKey(identity.publicKey)
            val persisted = hub.loadPersistedMessages(session.roomId)
            if (persisted.isNotEmpty()) {
                hub.seedMessages(session.roomId, persisted)
            }
            if (repository.isInRoom(session.roomId)) {
                val conn = hub.connectionFor(session.roomId).value
                if (conn == ConnectionState.Disconnected || conn == ConnectionState.Error) {
                    repository.reconnectTransport()
                }
                _uiState.update {
                    it.copy(
                        roomId = session.roomId,
                        senderId = senderId,
                        messages = hub.messagesFor(session.roomId),
                        connection = hub.connectionFor(session.roomId).value,
                        initError = null,
                        peerNeedsVerification = needsVerify,
                        peerFingerprint = session.peerFingerprint,
                    )
                }
                return@launch
            }
            hub.ensureCapacityForNewRoom(session.roomId)
            _uiState.value = ChatUiState(
                roomId = session.roomId,
                senderId = senderId,
                messages = persisted,
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

    /** 用户明确退出聊天：断开该房间中继；本地历史保留在加密数据库中。 */
    fun leaveChat() {
        val room = boundSession?.roomId ?: return
        boundSession = null
        hub.leaveRoom(room)
        hub.clearMessages(room)
        _uiState.update {
            it.copy(
                messages = emptyList(),
                connection = ConnectionState.Disconnected,
            )
        }
    }

    /** 返回首页：保持该房间 relay 连接，会话转入 detached。 */
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
        val session = boundSession ?: return
        val repository = hub.repositoryFor(session.roomId) ?: return
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
        val repository = hub.repositoryFor(session.roomId) ?: return
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
                hub.ensureCapacityForNewRoom(session.roomId)
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
