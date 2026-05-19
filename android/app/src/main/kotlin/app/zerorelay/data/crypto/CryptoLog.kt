package app.zerorelay.data.crypto

import android.util.Log
import java.security.MessageDigest

/**
 * 客户端加解密调试日志（服务端无密钥，无法记录此类日志）
 * Debug 安装包默认开启；可用 [enabled] 手动关闭
 */
object CryptoLog {
    private const val TAG = "ZeroRelay.Crypto"

    var enabled: Boolean = false

    var logPlaintext: Boolean = false

    private fun keyFingerprint(key: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key)
        return digest.take(4).joinToString("") { b -> "%02x".format(b) }
    }

    fun sessionDerived(roomId: String, sessionKey: ByteArray) {
        if (!enabled) return
        Log.i(TAG, "会话密钥派生  room=${short(roomId)}  key#=${keyFingerprint(sessionKey)}  alg=X25519+HKDF AES-256-GCM")
    }

    fun encrypt(plainLen: Int, cipherLen: Int, ivLen: Int) {
        if (!enabled) return
        Log.i(TAG, "加密  plain=${plainLen}B → cipher=${cipherLen}B  iv=${ivLen}B  (AES-GCM)")
    }

    fun decryptOk(senderId: String, plainLen: Int, cipherLen: Int) {
        if (!enabled) return
        Log.i(TAG, "解密成功  from=$senderId  cipher=${cipherLen}B → plain=${plainLen}B")
    }

    fun decryptFail(senderId: String, reason: String) {
        if (!enabled) return
        Log.w(TAG, "解密失败  from=$senderId  reason=$reason")
    }

    fun plaintextPreview(label: String, text: String) {
        if (!enabled || !logPlaintext) return
        val preview = if (text.length <= 40) text else text.take(40) + "…"
        Log.d(TAG, "$label  \"$preview\"")
    }

    private fun short(s: String, max: Int = 16): String =
        if (s.length <= max) s else s.take(max) + "…"
}
