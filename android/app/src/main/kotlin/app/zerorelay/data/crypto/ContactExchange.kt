package app.zerorelay.data.crypto

import android.util.Base64
import org.json.JSONObject

object ContactExchange {
    private const val PREFIX = "zerorelay://v1?d="

    data class Payload(
        val publicKeyBase64: String,
        val displayName: String?,
    )

    fun encodePayload(publicKeyBase64: String, displayName: String?): String {
        val json = JSONObject().apply {
            put("v", 1)
            put("pk", publicKeyBase64)
            if (!displayName.isNullOrBlank()) put("n", displayName.trim())
        }
        val data = Base64.encodeToString(
            json.toString().toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        return PREFIX + data
    }

    fun parse(raw: String): Payload? {
        val trimmed = raw.trim()
        val jsonString = when {
            trimmed.startsWith(PREFIX) -> {
                val data = trimmed.removePrefix(PREFIX)
                String(
                    Base64.decode(data, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),
                    Charsets.UTF_8,
                )
            }
            trimmed.startsWith("{") -> trimmed
            else -> {
                val pk = IdentityCrypto.decodePublicKey(trimmed)
                return Payload(
                    publicKeyBase64 = IdentityCrypto.encodePublicKey(pk),
                    displayName = null,
                )
            }
        }
        return try {
            val json = JSONObject(jsonString)
            if (json.optInt("v", 0) != 1) return null
            val pk = json.optString("pk", "")
            if (pk.isBlank()) return null
            IdentityCrypto.decodePublicKey(pk)
            Payload(
                publicKeyBase64 = pk,
                displayName = json.optString("n", "").takeIf { it.isNotBlank() },
            )
        } catch (_: Exception) {
            null
        }
    }
}
