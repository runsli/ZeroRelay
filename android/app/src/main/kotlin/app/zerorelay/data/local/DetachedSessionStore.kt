package app.zerorelay.data.local

import android.content.Context
import androidx.core.content.edit
import app.zerorelay.data.model.ChatKind
import app.zerorelay.data.model.ChatSession
import org.json.JSONArray
import org.json.JSONObject

/** 持久化 detached 会话元数据（不含 sessionKey），用于进程被杀后恢复 relay 连接。 */
class DetachedSessionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveAll(sessions: Collection<ChatSession>) {
        if (sessions.isEmpty()) {
            clear()
            return
        }
        val arr = JSONArray()
        sessions.forEach { session ->
            arr.put(
                JSONObject().apply {
                    put("roomId", session.roomId)
                    put("peerContactId", session.peerContactId)
                    put("kind", session.kind.name)
                    put("serverUrl", session.serverUrl)
                },
            )
        }
        prefs.edit { putString(KEY_RECORDS, arr.toString()) }
    }

    fun loadAll(): List<DetachedSessionRecord> {
        val raw = prefs.getString(KEY_RECORDS, null)
        if (raw != null) {
            parseRecords(raw)?.let { return it }
        }
        val legacy = prefs.getString(KEY_LEGACY_RECORD, null) ?: return emptyList()
        return parseLegacyRecord(legacy)?.let { listOf(it) }.orEmpty()
    }

    private fun parseRecords(raw: String): List<DetachedSessionRecord>? =
        try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    add(parseRecord(arr.getJSONObject(i)))
                }
            }
        } catch (_: Exception) {
            null
        }

    private fun parseLegacyRecord(raw: String): DetachedSessionRecord? =
        try {
            parseRecord(JSONObject(raw))
        } catch (_: Exception) {
            null
        }

    private fun parseRecord(json: JSONObject): DetachedSessionRecord =
        DetachedSessionRecord(
            roomId = json.getString("roomId"),
            peerContactId = json.getString("peerContactId"),
            kind = ChatKind.valueOf(json.getString("kind")),
            serverUrl = json.getString("serverUrl"),
        )

    fun clear() {
        prefs.edit { remove(KEY_RECORDS) }
    }

    data class DetachedSessionRecord(
        val roomId: String,
        val peerContactId: String,
        val kind: ChatKind,
        val serverUrl: String,
    )

    companion object {
        private const val PREFS_NAME = "zero_relay_detached"
        private const val KEY_RECORDS = "records"
        private const val KEY_LEGACY_RECORD = "record"
    }
}
