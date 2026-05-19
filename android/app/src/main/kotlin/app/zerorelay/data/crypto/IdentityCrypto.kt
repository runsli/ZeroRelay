package app.zerorelay.data.crypto

import android.util.Base64
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.MessageDigest
import java.security.SecureRandom

object IdentityCrypto {
    const val PUBLIC_KEY_LENGTH = 32
    private const val HKDF_INFO = "zero-relay-v1"

    data class KeyPair(
        val privateKey: ByteArray,
        val publicKey: ByteArray,
    )

    fun generateKeyPair(): KeyPair {
        val gen = X25519KeyPairGenerator()
        gen.init(X25519KeyGenerationParameters(SecureRandom()))
        val kp = gen.generateKeyPair()
        val privateKey = (kp.private as X25519PrivateKeyParameters).encoded
        val publicKey = (kp.public as X25519PublicKeyParameters).encoded
        return KeyPair(privateKey, publicKey)
    }

    fun encodePublicKey(publicKey: ByteArray): String =
        Base64.encodeToString(publicKey, Base64.NO_WRAP)

    fun decodePublicKey(encoded: String): ByteArray {
        val bytes = Base64.decode(encoded.trim(), Base64.NO_WRAP)
        require(bytes.size == PUBLIC_KEY_LENGTH) { "公钥长度无效" }
        return bytes
    }

    fun deriveRoomId(localPublicKey: ByteArray, peerPublicKey: ByteArray): String {
        val sorted = listOf(localPublicKey, peerPublicKey).sortedWith(::comparePublicKeys)
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(sorted[0])
        digest.update(sorted[1])
        return Base64.encodeToString(
            digest.digest(),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
    }

    fun deriveSessionKey(
        privateKey: ByteArray,
        peerPublicKey: ByteArray,
        roomId: String,
    ): ByteArray {
        val agreement = X25519Agreement()
        agreement.init(X25519PrivateKeyParameters(privateKey))
        val shared = ByteArray(PUBLIC_KEY_LENGTH)
        agreement.calculateAgreement(X25519PublicKeyParameters(peerPublicKey), shared, 0)

        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(shared, roomId.toByteArray(Charsets.UTF_8), HKDF_INFO.toByteArray()))
        val key = ByteArray(32)
        hkdf.generateBytes(key, 0, key.size)
        CryptoLog.sessionDerived(roomId, key)
        return key
    }

    fun senderIdFromPublicKey(publicKey: ByteArray): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(publicKey)
        val suffix = Base64.encodeToString(
            hash,
            0,
            6,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        ).replace("-", "x").replace("_", "y")
        return "id_$suffix"
    }

    fun contactIdFromPublicKey(publicKey: ByteArray): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(publicKey)
        return hash.take(8).joinToString("") { "%02x".format(it) }
    }

    fun fingerprint(publicKey: ByteArray): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(publicKey)
        return hash.take(8).joinToString("-") { b -> "%02X".format(b) }
    }

    private fun comparePublicKeys(a: ByteArray, b: ByteArray): Int {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            val diff = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
            if (diff != 0) return diff
        }
        return a.size - b.size
    }
}
