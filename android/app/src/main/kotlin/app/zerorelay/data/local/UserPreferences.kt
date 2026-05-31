package app.zerorelay.data.local

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray

class UserPreferences(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getServerUrl(): String? = prefs.getString(KEY_SERVER_URL, null)

    fun setServerUrl(url: String) {
        prefs.edit { putString(KEY_SERVER_URL, url) }
    }

    /** Material You 动态配色（Android 12+）；关闭时使用品牌绿静态主题。 */
    fun getUseDynamicColor(): Boolean = prefs.getBoolean(KEY_USE_DYNAMIC_COLOR, true)

    fun setUseDynamicColor(use: Boolean) {
        prefs.edit { putBoolean(KEY_USE_DYNAMIC_COLOR, use) }
    }

    /** 为 true 时不拦截截图/录屏；为 false 时聊天页启用 FLAG_SECURE。 */
    fun getAllowScreenshots(): Boolean = prefs.getBoolean(KEY_ALLOW_SCREENSHOTS, true)

    fun setAllowScreenshots(allow: Boolean) {
        prefs.edit { putBoolean(KEY_ALLOW_SCREENSHOTS, allow) }
    }

    /** 返回首页后是否启动 Foreground Service 维持 relay 连接。 */
    fun getKeepAliveInBackground(): Boolean = prefs.getBoolean(KEY_KEEP_ALIVE, true)

    fun setKeepAliveInBackground(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_KEEP_ALIVE, enabled) }
    }

    fun getRecentRooms(): List<String> {
        val json = prefs.getString(KEY_RECENT_ROOMS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            buildList {
                for (i in 0 until arr.length()) {
                    add(arr.getString(i))
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addRecentContact(contactId: String) {
        val ids = getRecentContactIds().toMutableList()
        ids.remove(contactId)
        ids.add(0, contactId)
        prefs.edit {
            putString(KEY_RECENT_CONTACTS, JSONArray(ids.take(10)).toString())
        }
    }

    fun getRecentContactIds(): List<String> {
        val json = prefs.getString(KEY_RECENT_CONTACTS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            buildList {
                for (i in 0 until arr.length()) {
                    add(arr.getString(i))
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    companion object {
        private const val PREFS_NAME = "zero_relay_prefs"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_USE_DYNAMIC_COLOR = "use_dynamic_color"
        private const val KEY_ALLOW_SCREENSHOTS = "allow_screenshots"
        private const val KEY_KEEP_ALIVE = "keep_alive_in_background"
        private const val KEY_RECENT_ROOMS = "recent_rooms"
        private const val KEY_RECENT_CONTACTS = "recent_contacts"
    }
}
