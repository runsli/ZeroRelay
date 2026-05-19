package app.zerorelay.data.crypto

import android.util.Base64
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 中继全隧道 v2：帧内仅 routeHash(32)，密钥 HKDF(routeHash)。
 */
object RelayTunnel {
    private const val VERSION: Byte = 0x02
    private const val HEADER_LEN = 1 + 32 + 12

    private fun routeHashFromToken(tokenB64: String): ByteArray {
        val tokenRaw = Base64.decode(tokenB64, Base64.NO_WRAP)
        require(tokenRaw.size == 32) { "invalid room token" }
        return MessageDigest.getInstance("SHA-256").digest(tokenRaw)
    }

    fun encode(tokenB64: String, inner: JSONObject): ByteArray {
        val routeHash = routeHashFromToken(tokenB64)
        val key = RelayCrypto.tunnelKeyFromRouteHash(routeHash)
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val plain = TunnelPadding.pad(inner).toString().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val ct = cipher.doFinal(plain)
        val frame = ByteArray(HEADER_LEN + ct.size)
        frame[0] = VERSION
        System.arraycopy(routeHash, 0, frame, 1, 32)
        System.arraycopy(iv, 0, frame, 33, 12)
        System.arraycopy(ct, 0, frame, 45, ct.size)
        return frame
    }

    fun encodeSend(
        tokenB64: String,
        ciphertext: String,
        iv: String,
        tag: String,
        senderPk: String,
        signPk: String,
        sig: String,
        timestamp: Long,
    ): ByteArray = encode(
        tokenB64,
        JSONObject().apply {
            put("op", "send")
            put("ciphertext", ciphertext)
            put("iv", iv)
            put("tag", tag)
            put("senderPk", senderPk)
            put("signPk", signPk)
            put("sig", sig)
            put("timestamp", timestamp)
        },
    )

    fun encodePoll(tokenB64: String, since: Long, timeout: Int): ByteArray = encode(
        tokenB64,
        JSONObject().apply {
            put("op", "poll")
            put("since", since)
            put("timeout", timeout)
        },
    )
}
