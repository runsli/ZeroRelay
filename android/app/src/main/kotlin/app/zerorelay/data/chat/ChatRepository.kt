package app.zerorelay.data.chat

import android.content.Context
import androidx.annotation.StringRes
import android.util.Base64
import app.zerorelay.R
import app.zerorelay.data.crypto.CryptoService
import app.zerorelay.data.crypto.IdentityCrypto
import app.zerorelay.data.crypto.MessageCipher
import app.zerorelay.data.crypto.ProtocolPolicy
import app.zerorelay.data.crypto.RelayCrypto
import app.zerorelay.data.crypto.RelayTunnel
import kotlin.math.min
import kotlin.random.Random
import app.zerorelay.data.local.UserPreferences
import app.zerorelay.data.model.ChatKind
import app.zerorelay.data.model.ChatMessage
import app.zerorelay.data.model.ConnectionState
import app.zerorelay.data.model.EncryptedMessage
import app.zerorelay.data.network.RelayHttpClient
import app.zerorelay.data.network.RelaySecurityPolicy
import app.zerorelay.data.network.ServerUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class ChatRepository(
    context: Context,
    private val preferences: UserPreferences,
    client: OkHttpClient? = null,
) {
    private val appContext = context.applicationContext
    private val messageCipher = MessageCipher(appContext)
    private var httpClient: OkHttpClient = client ?: RelayHttpClient.create(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var webSocket: WebSocket? = null
    private var encryptionKey: ByteArray? = null
    private var roomId: String? = null
    private var senderId: String? = null
    private var baseUrl: String? = null
    private var chatKind: ChatKind = ChatKind.Direct
    private var groupKeysByVersion: Map<Int, ByteArray> = emptyMap()
    private var groupKeyVersion: Int = 1
    private var directLocalPublicKey: ByteArray? = null
    private var directPeerPublicKey: ByteArray? = null
    private var roomAccessToken: String? = null
    private var signingKeys: RelayCrypto.SigningKeys? = null
    private var identityPublicKey: ByteArray? = null

    private val pendingFingerprints = ConcurrentHashMap<String, Long>()
    private val seenMessageIds = ConcurrentHashMap.newKeySet<String>()
    private var lastPollTimestamp = 0L
    private var pollJob: Job? = null
    private var reconnectJob: Job? = null
    private var legacyDecryptNotified = false
    private var pollBackoffMs = ProtocolPolicy.POLL_BASE_MS
    private var pollingEnabled = true

    private val _messages = MutableSharedFlow<ChatMessage>(extraBufferCapacity = 64)
    val messages: SharedFlow<ChatMessage> = _messages.asSharedFlow()

    private val _connection = MutableStateFlow(ConnectionState.Disconnected)
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    private fun emitError(@StringRes resId: Int, vararg formatArgs: Any) {
        _errors.tryEmit(appContext.getString(resId, *formatArgs))
    }

    private fun throwableDetail(e: Throwable): String =
        e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName

    private fun legacyCutoffLabel(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ProtocolPolicy.LEGACY_STATIC_CUTOFF_MS))

    private fun resolveSenderId(requested: String, identityPublicKey: ByteArray?): String {
        if (identityPublicKey == null) return requested.ifBlank { "anonymous" }
        return IdentityCrypto.senderIdFromPublicKey(identityPublicKey)
    }

    fun isInRoom(room: String): Boolean = roomId == room && senderId != null

    /** 仅重建 WebSocket / 轮询，保留 senderId、棘轮与去重状态。 */
    suspend fun reconnectTransport(): Boolean {
        if (roomId == null || senderId == null || encryptionKey == null) return false
        return try {
            disconnectTransport()
            connectWebSocket()
            startMessagePolling()
            true
        } catch (e: Exception) {
            emitError(R.string.error_relay_connect_failed, throwableDetail(e))
            false
        }
    }

    suspend fun joinRoom(
        serverUrl: String,
        room: String,
        sessionKey: ByteArray,
        sender: String,
        contactId: String,
        kind: ChatKind = ChatKind.Direct,
        groupKeys: Map<Int, ByteArray> = emptyMap(),
        groupKeyVer: Int = 1,
        localPublicKey: ByteArray? = null,
        peerPublicKey: ByteArray? = null,
        identityPrivateKey: ByteArray? = null,
        identityPublicKey: ByteArray? = null,
    ): Boolean {
        val normalizedUrl = ServerUrl.normalize(serverUrl)
        val stableSender = resolveSenderId(sender, identityPublicKey)
        val sameSession = roomId == room &&
            senderId == stableSender &&
            baseUrl == normalizedUrl &&
            encryptionKey?.contentEquals(sessionKey) == true
        if (!sameSession) {
            leaveRoom()
        } else {
            disconnectTransport()
        }
        return try {
            encryptionKey = sessionKey
            roomId = room
            senderId = stableSender
            chatKind = kind
            groupKeysByVersion = groupKeys
            groupKeyVersion = groupKeyVer
            directLocalPublicKey = if (kind == ChatKind.Direct) localPublicKey else null
            directPeerPublicKey = if (kind == ChatKind.Direct) peerPublicKey else null
            this.identityPublicKey = identityPublicKey
            signingKeys = identityPrivateKey?.let { RelayCrypto.deriveSigningKeys(it) }
            if (kind == ChatKind.Direct && !sameSession) {
                messageCipher.clearRoom(room)
            }
            if (RelaySecurityPolicy.ensureRelayReady(appContext, normalizedUrl) != null) {
                emitError(R.string.error_release_tls_pin_required)
                return false
            }
            roomAccessToken = RelayCrypto.deriveRoomAccessToken(sessionKey, room)
            baseUrl = normalizedUrl
            httpClient = RelayHttpClient.create(appContext, baseUrl)
            preferences.setServerUrl(baseUrl!!)
            if (!sameSession) {
                seenMessageIds.clear()
                lastPollTimestamp = 0L
                legacyDecryptNotified = false
                pollBackoffMs = ProtocolPolicy.POLL_BASE_MS
            }
            connectWebSocket()
            startMessagePolling()
            true
        } catch (e: Exception) {
            emitError(R.string.error_relay_connect_failed, throwableDetail(e))
            false
        }
    }

    fun setPollingEnabled(enabled: Boolean) {
        if (pollingEnabled == enabled) return
        pollingEnabled = enabled
        if (!enabled) {
            pollJob?.cancel()
            pollJob = null
        } else if (roomId != null && encryptionKey != null) {
            startMessagePolling()
        }
    }

    fun leaveRoom() {
        reconnectJob?.cancel()
        reconnectJob = null
        disconnectTransport()
        pollBackoffMs = ProtocolPolicy.POLL_BASE_MS
        encryptionKey = null
        roomId = null
        senderId = null
        baseUrl = null
        chatKind = ChatKind.Direct
        groupKeysByVersion = emptyMap()
        groupKeyVersion = 1
        directLocalPublicKey = null
        directPeerPublicKey = null
        roomAccessToken = null
        signingKeys = null
        identityPublicKey = null
        pendingFingerprints.clear()
        seenMessageIds.clear()
        lastPollTimestamp = 0L
        _connection.value = ConnectionState.Disconnected
    }

    private fun disconnectTransport() {
        reconnectJob?.cancel()
        reconnectJob = null
        pollJob?.cancel()
        pollJob = null
        webSocket?.close(1000, null)
        webSocket = null
        if (roomId != null) {
            _connection.value = ConnectionState.Disconnected
        }
    }

    private fun scheduleWebSocketReconnect() {
        if (roomId == null || senderId == null) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(WS_RECONNECT_DELAY_MS)
            if (roomId == null || senderId == null) return@launch
            reconnectTransport()
        }
    }

    fun currentSenderId(): String = senderId ?: "anonymous"

    suspend fun sendMessage(content: String): Boolean {
        val key = encryptionKey ?: return false
        val room = roomId ?: return false
        val sender = senderId ?: return false
        val base = baseUrl ?: return false
        return try {
            withContext(Dispatchers.IO) {
                val encrypted = when (chatKind) {
                    ChatKind.Direct -> {
                        val local = directLocalPublicKey ?: return@withContext false
                        val peer = directPeerPublicKey ?: return@withContext false
                        messageCipher.encryptDirect(room, key, local, peer, content)
                    }
                    ChatKind.Group -> messageCipher.encryptGroup(content, key, groupKeyVersion)
                }
                val keys = signingKeys ?: return@withContext false
                val pub = identityPublicKey ?: return@withContext false
                val token = roomAccessToken ?: return@withContext false
                val routeId = RelayCrypto.routeIdFromToken(token) ?: return@withContext false
                val now = System.currentTimeMillis()
                val senderPk = IdentityCrypto.encodePublicKey(pub)
                val signPk = RelayCrypto.encodeSignPublicKey(keys.signPublic)
                val sig = RelayCrypto.signMessage(
                    keys,
                    routeId,
                    sender,
                    now,
                    encrypted.ciphertext,
                    encrypted.iv,
                    encrypted.tag,
                )
                val frame = RelayTunnel.encodeSend(
                    token,
                    encrypted.ciphertext,
                    encrypted.iv,
                    encrypted.tag,
                    senderPk,
                    signPk,
                    sig,
                    now,
                )
                val request = Request.Builder()
                    .url("$base/t")
                    .post(frame.toRequestBody("application/octet-stream".toMediaType()))
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val errBody = response.body?.string().orEmpty()
                        val detail = runCatching {
                            JSONObject(errBody).optString("error")
                        }.getOrNull()?.takeIf { it.isNotBlank() } ?: errBody.take(120)
                        if (detail.isNotBlank()) {
                            emitError(R.string.error_relay_send_failed_detail, response.code, detail)
                        } else {
                            emitError(R.string.error_relay_send_failed_http, response.code)
                        }
                        return@withContext false
                    }
                }

                val fp = fingerprint(encrypted.ciphertext, encrypted.iv, encrypted.tag)
                pendingFingerprints[fp] = now

                _messages.emit(
                    ChatMessage(
                        id = "local-$now",
                        roomId = room,
                        content = content,
                        timestamp = now,
                        senderId = sender,
                        isMine = true,
                    ),
                )
                true
            }
        } catch (e: Exception) {
            emitError(R.string.error_relay_send_message, throwableDetail(e))
            false
        }
    }

    /**
     * Cloudflare Workers 的 WebSocket 广播只在同一 isolate 内有效；
     * 与 CLI 相同，用 HTTP 长轮询从 KV 拉取对方消息。
     */
    private fun startMessagePolling() {
        if (!pollingEnabled) return
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive && pollingEnabled) {
                val room = roomId ?: break
                val base = baseUrl ?: break
                var receivedMessages = false
                try {
                    val token = roomAccessToken ?: break
                    val timeoutMs = ProtocolPolicy.POLL_TIMEOUT_MS
                    val frame = RelayTunnel.encodePoll(token, lastPollTimestamp, timeoutMs.toInt())
                    val request = Request.Builder()
                        .url("$base/t")
                        .post(frame.toRequestBody("application/octet-stream".toMediaType()))
                        .build()
                    val batch = mutableListOf<JSONObject>()
                    httpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            val errBody = response.body?.string().orEmpty()
                            val detail = runCatching {
                                JSONObject(errBody).optString("error")
                            }.getOrNull()?.takeIf { it.isNotBlank() } ?: errBody.take(80)
                            if (detail.isNotBlank()) {
                                emitError(R.string.error_relay_poll_failed_detail, response.code, detail)
                            } else {
                                emitError(R.string.error_relay_poll_failed_http, response.code)
                            }
                            return@use
                        }
                        val body = response.body?.string().orEmpty()
                        val data = JSONObject(body)
                        val list = data.optJSONArray("messages") ?: JSONArray()
                        for (i in 0 until list.length()) {
                            list.optJSONObject(i)?.let { batch.add(it) }
                        }
                    }
                    receivedMessages = batch.isNotEmpty()
                    var maxTs = lastPollTimestamp
                    for (obj in batch) {
                        val ts = obj.optLong("timestamp", 0L)
                        if (ts > maxTs) maxTs = ts
                        processIncoming(obj)
                    }
                    if (maxTs > lastPollTimestamp) {
                        lastPollTimestamp = maxTs
                    }
                } catch (e: Exception) {
                    emitError(R.string.error_relay_poll_error, throwableDetail(e))
                }
                if (receivedMessages) {
                    pollBackoffMs = ProtocolPolicy.POLL_BASE_MS
                } else {
                    pollBackoffMs = min(
                        (pollBackoffMs * 3) / 2,
                        ProtocolPolicy.POLL_MAX_BACKOFF_MS,
                    )
                }
                delay(nextPollDelayMs())
            }
        }
    }

    private fun nextPollDelayMs(): Long {
        if (_connection.value == ConnectionState.Connected) {
            return ProtocolPolicy.POLL_WS_CONNECTED_MS +
                Random.nextLong(ProtocolPolicy.POLL_JITTER_MS + 1)
        }
        return pollBackoffMs + Random.nextLong(ProtocolPolicy.POLL_JITTER_MS + 1)
    }

    private fun connectWebSocket() {
        val room = roomId ?: return
        val sender = senderId ?: return
        val url = baseUrl ?: return
        _connection.value = ConnectionState.Connecting

        val token = roomAccessToken ?: return
        webSocket?.cancel()
        webSocket = null
        val request = Request.Builder()
            .url(ServerUrl.webSocketUrl(url))
            .build()

        webSocket = httpClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val routeHash = RelayCrypto.routeHashB64FromToken(token) ?: return
                    val auth = JSONObject().apply {
                        put("type", "auth")
                        put("routeHash", routeHash)
                        put("senderId", sender)
                    }
                    webSocket.send(auth.toString())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    scope.launch { handleRawMessage(text) }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (this@ChatRepository.webSocket != webSocket) return
                    _connection.value = ConnectionState.Error
                    val detail = t.message?.takeIf { it.isNotBlank() }
                        ?: appContext.getString(R.string.error_unknown)
                    emitError(R.string.error_relay_ws_error, detail)
                    scheduleWebSocketReconnect()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (this@ChatRepository.webSocket != webSocket) return
                    _connection.value = ConnectionState.Disconnected
                    if (code != 1000 && roomId != null) {
                        scheduleWebSocketReconnect()
                    }
                }
            },
        )
    }

    private suspend fun handleRawMessage(raw: String) {
        try {
            val data = JSONObject(raw)
            when (data.optString("type")) {
                "auth_ok", "joined" -> {
                    _connection.value = ConnectionState.Connected
                }
                "auth_required" -> {
                    val s = senderId
                    roomAccessToken?.let { token ->
                        val routeHash = RelayCrypto.routeHashB64FromToken(token)
                        if (s != null && routeHash != null) {
                            webSocket?.send(
                                JSONObject().apply {
                                    put("type", "auth")
                                    put("routeHash", routeHash)
                                    put("senderId", s)
                                }.toString(),
                            )
                        }
                    }
                }
                "auth_error" -> {
                    _connection.value = ConnectionState.Error
                    val serverError = data.optString("error").takeIf { it.isNotBlank() }
                        ?: appContext.getString(R.string.error_unknown)
                    emitError(R.string.error_relay_ws_auth_failed, serverError)
                }
                "message" -> {
                    val msg = data.optJSONObject("message") ?: return
                    processIncoming(msg)
                }
                "history" -> {
                    val list = data.optJSONArray("messages") ?: return
                    processHistory(list)
                    if (data.optBoolean("truncated")) {
                        val returned = data.optInt("returnedCount")
                        val total = data.optInt("totalCount")
                        if (total > returned) {
                            emitError(R.string.error_relay_history_truncated, returned, total)
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private suspend fun processHistory(list: JSONArray) {
        for (i in 0 until list.length()) {
            val obj = list.optJSONObject(i) ?: continue
            processIncoming(obj)
        }
    }

    private suspend fun processIncoming(msg: JSONObject) {
        val key = encryptionKey ?: return
        val encrypted = parseEncrypted(msg)
        val dedupKey = if (encrypted.id.isNotEmpty()) {
            encrypted.id
        } else {
            "${encrypted.senderId}-${encrypted.timestamp}"
        }
        if (!seenMessageIds.add(dedupKey)) return
        if (consumePendingEcho(encrypted)) return
        if (!verifyIncomingSignature(encrypted)) {
            emitError(R.string.error_relay_signature_rejected)
            return
        }
        if (!emitDecrypted(encrypted, key)) {
            if (encrypted.senderId != senderId) {
                emitError(R.string.error_relay_decrypt_failed)
            }
        } else if (encrypted.timestamp > lastPollTimestamp) {
            lastPollTimestamp = encrypted.timestamp
        }
    }

    private fun verifyIncomingSignature(message: EncryptedMessage): Boolean {
        if (message.sig.isBlank() || message.signPk.isBlank() || message.senderPk.isBlank()) {
            return false
        }
        return runCatching {
            val senderPk = IdentityCrypto.decodePublicKey(message.senderPk)
            val signPk = Base64.decode(message.signPk, Base64.NO_WRAP)
            val sig = Base64.decode(message.sig, Base64.NO_WRAP)
            val routeId = roomAccessToken?.let { RelayCrypto.routeIdFromToken(it) }
                ?: return@runCatching false
            RelayCrypto.verifyMessage(
                senderPk = senderPk,
                signPk = signPk,
                sig = sig,
                routeId = routeId,
                senderId = message.senderId,
                timestamp = message.timestamp,
                ciphertext = message.ciphertext,
                iv = message.iv,
                tag = message.tag,
            )
        }.getOrDefault(false)
    }

    private suspend fun emitDecrypted(encrypted: EncryptedMessage, key: ByteArray): Boolean {
        val activeRoom = roomId ?: return false
        if (encrypted.roomId.isNotBlank() && encrypted.roomId != activeRoom) return false
        val payload = CryptoService.EncryptedPayload(encrypted.ciphertext, encrypted.iv, encrypted.tag)
        var legacyDecrypt = false
        val content = when (chatKind) {
            ChatKind.Direct -> {
                val room = roomId ?: return false
                val local = directLocalPublicKey ?: return false
                val peer = directPeerPublicKey ?: return false
                val result = messageCipher.decryptDirect(
                    room,
                    key,
                    local,
                    peer,
                    payload,
                    encrypted.timestamp,
                ) ?: return false
                legacyDecrypt = result.usedLegacyStaticKey
                result.text
            }
            ChatKind.Group -> {
                val keys = if (groupKeysByVersion.isNotEmpty()) {
                    groupKeysByVersion
                } else {
                    mapOf(groupKeyVersion to key)
                }
                messageCipher.decryptGroup(payload, keys)
            }
        } ?: return false
        if (legacyDecrypt && !legacyDecryptNotified) {
            legacyDecryptNotified = true
            emitError(R.string.error_relay_legacy_decrypt, legacyCutoffLabel())
        }
        _messages.emit(
            ChatMessage(
                id = encrypted.id,
                roomId = activeRoom,
                content = content,
                timestamp = encrypted.timestamp,
                senderId = encrypted.senderId,
                isMine = encrypted.senderId == senderId,
                legacyDecrypt = legacyDecrypt,
            ),
        )
        return true
    }

    private fun consumePendingEcho(message: EncryptedMessage): Boolean {
        if (message.senderId != senderId) return false
        val now = System.currentTimeMillis()
        pendingFingerprints.entries.removeIf { now - it.value > 60_000 }
        val fp = fingerprint(message.ciphertext, message.iv, message.tag)
        return pendingFingerprints.remove(fp) != null
    }

    private fun parseEncrypted(json: JSONObject): EncryptedMessage = EncryptedMessage(
        id = json.optString("id", ""),
        roomId = json.optString("roomId", ""),
        ciphertext = json.optString("ciphertext", ""),
        iv = json.optString("iv", ""),
        tag = json.optString("tag", ""),
        timestamp = json.optLong("timestamp", 0L),
        senderId = json.optString("senderId", "anonymous"),
        senderPk = json.optString("senderPk", ""),
        signPk = json.optString("signPk", ""),
        sig = json.optString("sig", ""),
    )

    private fun fingerprint(ciphertext: String, iv: String, tag: String): String =
        "$ciphertext|$iv|$tag"

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private const val WS_RECONNECT_DELAY_MS = 2_000L
    }
}
