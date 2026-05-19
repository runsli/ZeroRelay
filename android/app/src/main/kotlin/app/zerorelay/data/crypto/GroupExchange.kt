package app.zerorelay.data.crypto

import android.util.Base64
import app.zerorelay.data.network.ServerUrl
import org.json.JSONArray
import org.json.JSONObject

object GroupExchange {
    private const val PREFIX = "zerorelay://group?v=1&d="

    data class InvitePayload(
        val groupId: String,
        val displayName: String,
        val groupKeyBase64: String,
        val memberContactIds: List<String>,
        val serverUrl: String? = null,
        val keyVersion: Int = 1,
        val expiresAt: Long? = null,
    ) {
        fun isExpired(now: Long = System.currentTimeMillis()): Boolean =
            expiresAt != null && now > expiresAt
    }

    fun encodeInvite(group: app.zerorelay.data.model.ChatGroup, serverUrl: String? = null): String {
        val json = JSONObject().apply {
            put("v", 2)
            put("gid", group.id)
            put("n", group.displayName)
            put("k", group.groupKeyBase64)
            put("kv", group.keyVersion)
            group.inviteExpiresAt?.let { put("exp", it) }
            if (group.memberContactIds.isNotEmpty()) {
                put("m", JSONArray(group.memberContactIds))
            }
            val relay = serverUrl?.let { ServerUrl.normalize(it) }.orEmpty()
            if (relay.isNotEmpty() && !ServerUrl.isLocalDevUrl(relay)) {
                put("s", relay)
            }
        }
        val data = Base64.encodeToString(
            json.toString().toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        return PREFIX + data
    }

    fun parse(raw: String): InvitePayload? {
        val trimmed = raw.trim()
        val dataPart = when {
            trimmed.startsWith(PREFIX) -> trimmed.removePrefix(PREFIX)
            trimmed.contains("zerorelay://group") && "d=" in trimmed ->
                trimmed.substringAfter("d=", "")
            else -> return null
        }
        return try {
            val jsonString = String(decodeBase64Url(dataPart), Charsets.UTF_8)
            val json = JSONObject(jsonString)
            val ver = json.optInt("v", 0)
            if (ver != 1 && ver != 2) return null
            val gid = json.optString("gid", "").trim()
            val name = json.optString("n", "").trim()
            val key = json.optString("k", "").trim()
            if (gid.isBlank() || name.isBlank() || key.isBlank()) return null
            val keyBytes = Base64.decode(key, Base64.NO_WRAP)
            require(keyBytes.size == 32) { "invalid key" }
            val members = buildList {
                val arr = json.optJSONArray("m") ?: return@buildList
                for (i in 0 until arr.length()) {
                    val id = arr.optString(i, "").trim()
                    if (id.isNotBlank()) add(id)
                }
            }
            val server = json.optString("s", "").trim().takeIf { it.isNotBlank() }
            InvitePayload(
                groupId = gid,
                displayName = name,
                groupKeyBase64 = key,
                memberContactIds = members,
                serverUrl = server?.let { ServerUrl.normalize(it) },
                keyVersion = json.optInt("kv", 1),
                expiresAt = json.optLong("exp", 0L).takeIf { it > 0L },
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeBase64Url(data: String): ByteArray {
        var padded = data.trim()
        val rem = padded.length % 4
        if (rem > 0) padded += "=".repeat(4 - rem)
        return Base64.decode(padded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}
