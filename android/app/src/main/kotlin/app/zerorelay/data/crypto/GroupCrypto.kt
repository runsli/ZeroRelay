package app.zerorelay.data.crypto

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

object GroupCrypto {
    private const val ROOM_SALT = "zero-relay-group-v1"
    /** 默认群邀请有效期：7 天 */
    const val DEFAULT_INVITE_TTL_MS = 7L * 24 * 60 * 60 * 1000

    fun generateGroupId(): String {
        val bytes = ByteArray(12).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    fun generateGroupKey(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

    fun deriveRoomId(groupId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(ROOM_SALT.toByteArray(Charsets.UTF_8))
        digest.update(groupId.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(
            digest.digest(),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
    }

    fun fingerprint(groupId: String): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(groupId.toByteArray(Charsets.UTF_8))
        return hash.take(8).joinToString("-") { b -> "%02X".format(b) }
    }
}
