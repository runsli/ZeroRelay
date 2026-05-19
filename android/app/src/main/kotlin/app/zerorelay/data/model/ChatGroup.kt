package app.zerorelay.data.model

data class ChatGroup(
    val id: String,
    val displayName: String,
    val roomId: String,
    val groupKeyBase64: String,
    val memberContactIds: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val keyVersion: Int = 1,
    /** 当前邀请/密钥过期时间（毫秒），null 表示不过期 */
    val inviteExpiresAt: Long? = null,
    /** 历史群密钥版本，用于解密轮换前的消息 */
    val previousKeysBase64: Map<Int, String> = emptyMap(),
) {
    val memberCount: Int get() = memberContactIds.size.coerceAtLeast(1)

    fun keysByVersion(): Map<Int, ByteArray> {
        val map = mutableMapOf<Int, ByteArray>()
        map[keyVersion] = android.util.Base64.decode(groupKeyBase64, android.util.Base64.NO_WRAP)
        previousKeysBase64.forEach { (ver, b64) ->
            map[ver] = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
        }
        return map
    }

    fun isInviteExpired(now: Long = System.currentTimeMillis()): Boolean =
        inviteExpiresAt != null && now > inviteExpiresAt
}
