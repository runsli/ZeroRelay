package app.zerorelay.data.local

import android.content.Context
import androidx.core.content.edit
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

    /** 后台同时保持 relay 连接的 detached 会话上限（默认 3）。 */
    fun getMaxBackgroundSessions(): Int =
        prefs.getInt(KEY_MAX_BACKGROUND_SESSIONS, DEFAULT_MAX_BACKGROUND_SESSIONS)
            .coerceIn(MIN_BACKGROUND_SESSIONS, MAX_BACKGROUND_SESSIONS)

    fun setMaxBackgroundSessions(count: Int) {
        prefs.edit {
            putInt(
                KEY_MAX_BACKGROUND_SESSIONS,
                count.coerceIn(MIN_BACKGROUND_SESSIONS, MAX_BACKGROUND_SESSIONS),
            )
        }
    }

    companion object {
        private const val PREFS_NAME = "zero_relay_prefs"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_USE_DYNAMIC_COLOR = "use_dynamic_color"
        private const val KEY_ALLOW_SCREENSHOTS = "allow_screenshots"
        private const val KEY_KEEP_ALIVE = "keep_alive_in_background"
        private const val KEY_MAX_BACKGROUND_SESSIONS = "max_background_sessions"
        private const val DEFAULT_MAX_BACKGROUND_SESSIONS = 3
        private const val MIN_BACKGROUND_SESSIONS = 1
        private const val MAX_BACKGROUND_SESSIONS = 5
    }
}
