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
import app.zerorelay.data.model.DeliveryStatus
import app.zerorelay.ui.snackbar.AppSnackbarBus
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

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
    private val sendJobs = mutableMapOf<String, Job>()

    companion object {
        private const val SEND_TIMEOUT_MS = 15_000L
    }

    private fun appStr(@StringRes resId: Int, vararg args: Any): String =
        getApplication<Application>().getString(resId, *args)

    init {
        viewModelScope.launch {
            hub.roomMessageEvents.collect { msg ->
                val room = boundSession?.roomId ?: return@collect
                if (msg.roomId != room) return@collect
                _uiState.update { state ->
                    val index = state.messages.indexOfFirst { it.id == msg.id }
                    val messages = if (index >= 0) {
                        state.messages.toMutableList().apply { this[index] = msg }
                    } else {
                        state.messages + msg
                    }
                    state.copy(messages = messages)
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
            val rawPersisted = hub.loadPersistedMessages(session.roomId)
            val persisted = rawPersisted.map(::normalizeStaleSending)
            if (persisted.isNotEmpty()) {
                hub.seedMessages(session.roomId, persisted)
                rawPersisted.zip(persisted).forEach { (original, fixed) ->
                    if (original.deliveryStatus != fixed.deliveryStatus) {
                        hub.upsertMessage(fixed)
                    }
                }
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
        sendJobs.values.forEach { it.cancel() }
        sendJobs.clear()
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
        if (hub.repositoryFor(session.roomId) == null) return
        if (_uiState.value.peerNeedsVerification) {
            AppSnackbarBus.show(appStr(R.string.chat_snackbar_verify_before_send))
            return
        }
        val senderId = _uiState.value.senderId.ifBlank {
            identityStore.getOrCreateIdentity().let { IdentityCrypto.senderIdFromPublicKey(it.publicKey) }
        }
        val now = System.currentTimeMillis()
        val messageId = "local-$now"
        val outgoing = ChatMessage(
            id = messageId,
            roomId = session.roomId,
            content = content,
            timestamp = now,
            senderId = senderId,
            isMine = true,
            deliveryStatus = DeliveryStatus.SENDING,
        )
        hub.upsertMessage(outgoing)
        dispatchSend(session.roomId, messageId, content)
    }

    fun retryFailedMessage(messageId: String) {
        val session = boundSession ?: return
        val message = _uiState.value.messages.find { it.id == messageId } ?: return
        if (!message.isMine || message.deliveryStatus != DeliveryStatus.FAILED) return
        if (hub.repositoryFor(session.roomId) == null) return
        hub.updateDeliveryStatus(session.roomId, messageId, DeliveryStatus.SENDING)
        dispatchSend(session.roomId, messageId, message.content)
    }

    private fun dispatchSend(roomId: String, messageId: String, content: String) {
        sendJobs.remove(messageId)?.cancel()
        sendJobs[messageId] = viewModelScope.launch {
            val repository = hub.repositoryFor(roomId) ?: run {
                hub.updateDeliveryStatus(roomId, messageId, DeliveryStatus.FAILED)
                return@launch
            }
            val ok = try {
                withTimeout(SEND_TIMEOUT_MS) {
                    repository.sendMessage(content)
                }
            } catch (_: TimeoutCancellationException) {
                false
            }
            hub.updateDeliveryStatus(
                roomId,
                messageId,
                if (ok) DeliveryStatus.SENT else DeliveryStatus.FAILED,
            )
            sendJobs.remove(messageId)
        }
    }

    private fun normalizeStaleSending(message: ChatMessage): ChatMessage =
        if (message.isMine && message.deliveryStatus == DeliveryStatus.SENDING) {
            message.copy(deliveryStatus = DeliveryStatus.FAILED)
        } else {
            message
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
        sendJobs.values.forEach { it.cancel() }
        sendJobs.clear()
        detachUi()
        super.onCleared()
    }
}
