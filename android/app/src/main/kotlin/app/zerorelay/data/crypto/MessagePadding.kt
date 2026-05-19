package app.zerorelay.data.crypto

import android.util.Base64
import java.security.SecureRandom

/**
 * 固定块 PKCS 风格填充，降低密文长度泄露（在写入 v2 信封 plaintext 前使用）。
 */
object MessagePadding {
    private const val BLOCK_SIZE = 256
    private const val MAX_CONTENT_BYTES = BLOCK_SIZE - 5 // 1 pad byte + 4 length

    fun pad(plaintext: String): String {
        val content = plaintext.toByteArray(Charsets.UTF_8)
        require(content.size <= MAX_CONTENT_BYTES) { "消息过长" }
        val totalBlocks = ((content.size + 5 + BLOCK_SIZE - 1) / BLOCK_SIZE).coerceAtLeast(1)
        val out = ByteArray(totalBlocks * BLOCK_SIZE)
        out[0] = 0x01
        out[1] = ((content.size shr 24) and 0xff).toByte()
        out[2] = ((content.size shr 16) and 0xff).toByte()
        out[3] = ((content.size shr 8) and 0xff).toByte()
        out[4] = (content.size and 0xff).toByte()
        System.arraycopy(content, 0, out, 5, content.size)
        val rest = out.size - 5 - content.size
        if (rest > 0) {
            val noise = ByteArray(rest).also { SecureRandom().nextBytes(it) }
            System.arraycopy(noise, 0, out, 5 + content.size, rest)
        }
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    fun unpad(paddedB64: String): String? {
        return try {
            val buf = Base64.decode(paddedB64, Base64.NO_WRAP)
            if (buf.isEmpty() || buf[0] != 0x01.toByte()) return null
            val len = ((buf[1].toInt() and 0xff) shl 24) or
                ((buf[2].toInt() and 0xff) shl 16) or
                ((buf[3].toInt() and 0xff) shl 8) or
                (buf[4].toInt() and 0xff)
            if (len < 0 || 5 + len > buf.size) return null
            String(buf, 5, len, Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    /** 已是 padding 则解包，否则原样（兼容旧消息） */
    fun unwrap(maybePadded: String): String = unpad(maybePadded) ?: maybePadded
}
