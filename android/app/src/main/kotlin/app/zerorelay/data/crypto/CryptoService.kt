package app.zerorelay.data.crypto

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object CryptoService {
    private const val IV_LENGTH = 12
    private const val TAG_LENGTH = 16

    data class EncryptedPayload(
        val ciphertext: String,
        val iv: String,
        val tag: String,
    )

    fun encryptBlocking(plaintext: String, key: ByteArray): EncryptedPayload =
        encryptSync(plaintext, key)

    suspend fun encrypt(plaintext: String, key: ByteArray): EncryptedPayload = withContext(Dispatchers.Default) {
        encryptSync(plaintext, key)
    }

    private fun encryptSync(plaintext: String, key: ByteArray): EncryptedPayload {
        CryptoLog.plaintextPreview("加密输入", plaintext)
        val iv = ByteArray(IV_LENGTH).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val combined = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val ciphertext = combined.copyOf(combined.size - TAG_LENGTH)
        val tag = combined.copyOfRange(combined.size - TAG_LENGTH, combined.size)
        val payload = EncryptedPayload(
            ciphertext = Base64.encodeToString(ciphertext, Base64.NO_WRAP),
            iv = Base64.encodeToString(iv, Base64.NO_WRAP),
            tag = Base64.encodeToString(tag, Base64.NO_WRAP),
        )
        CryptoLog.encrypt(plaintext.length, ciphertext.size, iv.size)
        return payload
    }

    fun decryptBlocking(
        ciphertextB64: String,
        ivB64: String,
        tagB64: String,
        key: ByteArray,
        senderId: String? = null,
    ): String? = decryptSync(ciphertextB64, ivB64, tagB64, key, senderId)

    suspend fun decrypt(
        ciphertextB64: String,
        ivB64: String,
        tagB64: String,
        key: ByteArray,
        senderId: String? = null,
    ): String? = withContext(Dispatchers.Default) {
        decryptSync(ciphertextB64, ivB64, tagB64, key, senderId)
    }

    private fun decryptSync(
        ciphertextB64: String,
        ivB64: String,
        tagB64: String,
        key: ByteArray,
        senderId: String? = null,
    ): String? {
        return try {
            val ciphertext = Base64.decode(ciphertextB64, Base64.NO_WRAP)
            val iv = Base64.decode(ivB64, Base64.NO_WRAP)
            val tag = Base64.decode(tagB64, Base64.NO_WRAP)
            val combined = ByteArray(ciphertext.size + tag.size)
            System.arraycopy(ciphertext, 0, combined, 0, ciphertext.size)
            System.arraycopy(tag, 0, combined, ciphertext.size, tag.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            val plain = String(cipher.doFinal(combined), Charsets.UTF_8)
            CryptoLog.decryptOk(senderId ?: "?", plain.length, ciphertext.size)
            CryptoLog.plaintextPreview("解密输出", plain)
            plain
        } catch (e: Exception) {
            CryptoLog.decryptFail(senderId ?: "?", e.javaClass.simpleName)
            null
        }
    }
}
