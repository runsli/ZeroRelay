package app.zerorelay.data.crypto

import android.util.Base64
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.params.HKDFParameters
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 中继层：房间访问令牌 + Ed25519 消息签名（与 server/relay-security.ts、cli.js 对齐）
 */
object RelayCrypto {
    private const val ROOM_ACCESS_INFO = "zero-relay-room-access-v1"
    private const val SIGN_SEED_INFO = "ed25519-seed"
    private const val SIGN_HKDF_SALT = "zero-relay-sign-v1"

    data class SigningKeys(
        val signPrivate: ByteArray,
        val signPublic: ByteArray,
    )

    fun routeHashFromToken(tokenB64: String): ByteArray? {
        val raw = try {
            Base64.decode(tokenB64, Base64.NO_WRAP)
        } catch (_: Exception) {
            return null
        }
        if (raw.size != 32) return null
        return java.security.MessageDigest.getInstance("SHA-256").digest(raw)
    }

    fun routeHashB64FromToken(tokenB64: String): String? {
        val hash = routeHashFromToken(tokenB64) ?: return null
        return Base64.encodeToString(hash, Base64.NO_WRAP)
    }

    fun routeIdFromToken(tokenB64: String): String? {
        val hash = routeHashFromToken(tokenB64) ?: return null
        return Base64.encodeToString(hash, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    fun deriveRoomAccessToken(roomSecret: ByteArray, roomId: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(roomSecret, "HmacSHA256"))
        mac.update(ROOM_ACCESS_INFO.toByteArray(Charsets.UTF_8))
        mac.update(roomId.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(mac.doFinal(), Base64.NO_WRAP)
    }

    fun deriveSigningKeys(identityPrivateKey: ByteArray): SigningKeys {
        val seed = hkdf(identityPrivateKey, SIGN_HKDF_SALT.toByteArray(), SIGN_SEED_INFO.toByteArray())
        val privateParams = Ed25519PrivateKeyParameters(seed, 0)
        val publicParams = privateParams.generatePublicKey()
        return SigningKeys(
            signPrivate = privateParams.encoded,
            signPublic = publicParams.encoded,
        )
    }

    fun encodeSignPublicKey(signPublic: ByteArray): String =
        Base64.encodeToString(signPublic, Base64.NO_WRAP)

    fun buildSignPayload(
        routeId: String,
        senderId: String,
        timestamp: Long,
        ciphertext: String,
        iv: String,
        tag: String,
    ): ByteArray {
        val line = listOf(routeId, senderId, timestamp.toString(), ciphertext, iv, tag).joinToString("\n")
        return line.toByteArray(Charsets.UTF_8)
    }

    fun signMessage(
        signingKeys: SigningKeys,
        routeId: String,
        senderId: String,
        timestamp: Long,
        ciphertext: String,
        iv: String,
        tag: String,
    ): String {
        val payload = buildSignPayload(routeId, senderId, timestamp, ciphertext, iv, tag)
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(signingKeys.signPrivate, 0))
        signer.update(payload, 0, payload.size)
        return Base64.encodeToString(signer.generateSignature(), Base64.NO_WRAP)
    }

    fun verifyMessage(
        senderPk: ByteArray,
        signPk: ByteArray,
        sig: ByteArray,
        routeId: String,
        senderId: String,
        timestamp: Long,
        ciphertext: String,
        iv: String,
        tag: String,
    ): Boolean {
        if (IdentityCrypto.senderIdFromPublicKey(senderPk) != senderId) return false
        val payload = buildSignPayload(routeId, senderId, timestamp, ciphertext, iv, tag)
        val verifier = Ed25519Signer()
        verifier.init(false, Ed25519PublicKeyParameters(signPk, 0))
        verifier.update(payload, 0, payload.size)
        return verifier.verifySignature(sig)
    }

    /** 隧道 AES 密钥：HKDF(SHA256(roomToken))，与帧内 routeHash 一致 */
    fun tunnelKeyFromRouteHash(routeHash: ByteArray): ByteArray {
        require(routeHash.size == 32)
        return hkdf(routeHash, ByteArray(0), "zero-relay-tunnel-v1".toByteArray(Charsets.UTF_8))
    }

    private fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray): ByteArray {
        val gen = HKDFBytesGenerator(SHA256Digest())
        gen.init(HKDFParameters(ikm, salt, info))
        val out = ByteArray(32)
        gen.generateBytes(out, 0, out.size)
        return out
    }
}
