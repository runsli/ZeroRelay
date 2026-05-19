package app.zerorelay.data.model

data class EncryptedMessage(
    val id: String,
    val roomId: String,
    val ciphertext: String,
    val iv: String,
    val tag: String,
    val timestamp: Long,
    val senderId: String,
    val senderPk: String = "",
    val signPk: String = "",
    val sig: String = "",
)

data class ChatMessage(
    val id: String,
    val roomId: String,
    val content: String,
    val timestamp: Long,
    val senderId: String,
    val isMine: Boolean,
    /** 使用静态会话密钥兼容层解密（旧协议） */
    val legacyDecrypt: Boolean = false,
) {
    val formattedTime: String
        get() {
            val cal = java.util.Calendar.getInstance().apply {
                timeInMillis = timestamp
            }
            return "%02d:%02d".format(cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
        }
}

enum class ConnectionState {
    Disconnected,
    Connecting,
    Connected,
    Error,
}

enum class ChatKind {
    Direct,
    Group,
}

data class ChatSession(
    val serverUrl: String,
    val roomId: String,
    val sessionKey: ByteArray,
    val senderId: String,
    val peerDisplayName: String,
    val peerContactId: String,
    val peerFingerprint: String,
    /** 私聊棘轮：对方公钥（群聊为 null） */
    val peerPublicKeyBase64: String? = null,
    val kind: ChatKind = ChatKind.Direct,
    val memberCount: Int = 1,
    val groupKeyVersion: Int = 1,
    val groupKeysByVersion: Map<Int, ByteArray> = emptyMap(),
) {
    val isGroup: Boolean get() = kind == ChatKind.Group
}
