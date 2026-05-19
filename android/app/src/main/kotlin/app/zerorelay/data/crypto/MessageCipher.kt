package app.zerorelay.data.crypto

import android.content.Context
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject

/**
 * 统一加解密：私聊棘轮 v2；群聊静态密钥 + 可选密钥版本 kv。
 */
class MessageCipher(context: Context) {
    private val appContext = context.applicationContext
    private val ratchetPrefs by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            PREFS_RATCHET,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun prepareDirect(
        roomId: String,
        rootSessionKey: ByteArray,
        localPublicKey: ByteArray,
        peerPublicKey: ByteArray,
    ): MessageRatchet {
        val existing = loadRatchet(roomId)
        if (existing != null) return existing
        val ratchet = MessageRatchet.fromRoot(rootSessionKey, roomId, localPublicKey, peerPublicKey)
        saveRatchet(roomId, ratchet)
        return ratchet
    }

    fun encryptDirect(
        roomId: String,
        rootSessionKey: ByteArray,
        localPublicKey: ByteArray,
        peerPublicKey: ByteArray,
        plaintext: String,
    ): CryptoService.EncryptedPayload {
        val ratchet = prepareDirect(roomId, rootSessionKey, localPublicKey, peerPublicKey)
        val (_, payload) = ratchet.encryptEnvelope(plaintext) // padding inside ratchet
        saveRatchet(roomId, ratchet)
        return payload
    }

    data class DirectDecryptResult(
        val text: String,
        val usedLegacyStaticKey: Boolean,
    )

    fun decryptDirect(
        roomId: String,
        rootSessionKey: ByteArray,
        localPublicKey: ByteArray,
        peerPublicKey: ByteArray,
        payload: CryptoService.EncryptedPayload,
        messageTimestamp: Long,
    ): DirectDecryptResult? {
        var ratchet = loadRatchet(roomId)
            ?: MessageRatchet.fromRoot(rootSessionKey, roomId, localPublicKey, peerPublicKey)
        var result = ratchet.decryptEnvelope(payload, rootSessionKey, messageTimestamp)
        if (result == null) {
            ratchet = MessageRatchet.fromRoot(rootSessionKey, roomId, localPublicKey, peerPublicKey)
            result = ratchet.decryptEnvelope(payload, rootSessionKey, messageTimestamp)
        }
        if (result == null) return null
        saveRatchet(roomId, ratchet)
        return DirectDecryptResult(result.text, result.usedLegacyStaticKey)
    }

    fun encryptGroup(
        plaintext: String,
        groupKey: ByteArray,
        keyVersion: Int,
    ): CryptoService.EncryptedPayload {
        val envelope = JSONObject().apply {
            put("v", MessageRatchet.PROTOCOL_VERSION)
            put("kv", keyVersion)
            put("t", MessagePadding.pad(plaintext))
        }
        return CryptoService.encryptBlocking(envelope.toString(), groupKey)
    }

    fun decryptGroup(
        payload: CryptoService.EncryptedPayload,
        keysByVersion: Map<Int, ByteArray>,
    ): String? {
        for (key in keysByVersion.values) {
            val plain = CryptoService.decryptBlocking(
                payload.ciphertext,
                payload.iv,
                payload.tag,
                key,
            ) ?: continue
            val json = runCatching { JSONObject(plain) }.getOrNull()
            if (json != null && json.optInt("v") == MessageRatchet.PROTOCOL_VERSION) {
                val kv = json.optInt("kv", 1)
                if (keysByVersion.containsKey(kv)) {
                    val t = json.optString("t").takeIf { it.isNotEmpty() }
                    if (t != null) return MessagePadding.unwrap(t)
                }
            } else if (plain.isNotEmpty()) {
                return MessagePadding.unwrap(plain)
            }
        }
        return null
    }

    fun clearRoom(roomId: String) {
        ratchetPrefs.edit { remove(ratchetKey(roomId)) }
    }

    fun exportAllRatchetsJson(): JSONObject {
        val out = JSONObject()
        ratchetPrefs.all.forEach { (key, value) ->
            if (key.startsWith("ratchet_") && value is String) {
                out.put(key.removePrefix("ratchet_"), JSONObject(value))
            }
        }
        return out
    }

    fun importAllRatchetsJson(export: JSONObject) {
        val edit = ratchetPrefs.edit()
        export.keys().forEach { roomId ->
            val raw = export.optJSONObject(roomId) ?: return@forEach
            edit.putString(ratchetKey(roomId), raw.toString())
        }
        edit.apply()
    }

    private fun loadRatchet(roomId: String): MessageRatchet? {
        val raw = ratchetPrefs.getString(ratchetKey(roomId), null) ?: return null
        return MessageRatchet.fromJson(JSONObject(raw))
    }

    private fun saveRatchet(roomId: String, ratchet: MessageRatchet) {
        ratchetPrefs.edit { putString(ratchetKey(roomId), ratchet.toJson().toString()) }
    }

    private fun ratchetKey(roomId: String) = "ratchet_$roomId"

    companion object {
        private const val PREFS_RATCHET = "zero_relay_ratchet"
    }
}
