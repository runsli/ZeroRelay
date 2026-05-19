package app.zerorelay.data.crypto

import android.util.Base64
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 棘轮状态加密备份（口令保护），便于同账号换机恢复链状态。
 */
object RatchetBackup {
    private const val VERSION = 1
    private const val SCRYPT_ITERATIONS = 120_000

    fun encryptExport(passphrase: CharArray, ratchetsJson: JSONObject): String {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(passphrase, salt)
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val plain = ratchetsJson.toString().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val enc = cipher.doFinal(plain)
        return JSONObject().apply {
            put("v", VERSION)
            put("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
            put("data", Base64.encodeToString(enc, Base64.NO_WRAP))
        }.toString()
    }

    fun decryptImport(passphrase: CharArray, blob: String): JSONObject {
        val json = JSONObject(blob)
        require(json.optInt("v") == VERSION) { "备份版本不支持" }
        val salt = Base64.decode(json.getString("salt"), Base64.NO_WRAP)
        val iv = Base64.decode(json.getString("iv"), Base64.NO_WRAP)
        val data = Base64.decode(json.getString("data"), Base64.NO_WRAP)
        val key = deriveKey(passphrase, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val plain = String(cipher.doFinal(data), Charsets.UTF_8)
        return JSONObject(plain)
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(passphrase, salt, SCRYPT_ITERATIONS, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }
}
