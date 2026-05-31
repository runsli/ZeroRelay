package app.zerorelay.data.local

import android.content.Context
import androidx.core.content.edit
import app.zerorelay.data.model.ChatKind
import app.zerorelay.data.model.ChatSession
import org.json.JSONObject

/** 持久化 detached 会话元数据（不含 sessionKey），用于进程被杀后恢复 relay 连接。 */
class DetachedSessionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(session: ChatSession) {
        val json = JSONObject().apply {
            put("roomId", session.roomId)
            put("peerContactId", session.peerContactId)
            put("kind", session.kind.name)
            put("serverUrl", session.serverUrl)
        }
        prefs.edit { putString(KEY_RECORD, json.toString()) }
    }

    fun load(): DetachedSessionRecord? {
        val raw = prefs.getString(KEY_RECORD, null) ?: return null
        return try {
            val json = JSONObject(raw)
            DetachedSessionRecord(
                roomId = json.getString("roomId"),
                peerContactId = json.getString("peerContactId"),
                kind = ChatKind.valueOf(json.getString("kind")),
                serverUrl = json.getString("serverUrl"),
            )
        } catch (_: Exception) {
            null
        }
    }

    fun clear() {
        prefs.edit { remove(KEY_RECORD) }
    }

    data class DetachedSessionRecord(
        val roomId: String,
        val peerContactId: String,
        val kind: ChatKind,
        val serverUrl: String,
    )

    companion object {
        private const val PREFS_NAME = "zero_relay_detached"
        private const val KEY_RECORD = "record"
    }
}
