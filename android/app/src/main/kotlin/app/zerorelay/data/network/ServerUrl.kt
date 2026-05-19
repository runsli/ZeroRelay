package app.zerorelay.data.network

import android.net.Uri

object ServerUrl {
    /** Android 模拟器访问宿主机的地址 */
    const val EMULATOR_DEFAULT = "http://10.0.2.2:8787"

    fun normalize(raw: String): String {
        var s = raw.trim().trimEnd('/')
        if (s.isEmpty()) return ""
        if (!s.contains("://")) {
            val host = s.substringBefore('/').substringBefore(':')
            s = if (isLocalDevHost(host)) "http://$s" else "https://$s"
        }
        return s
    }

    fun isLocalDevUrl(url: String): Boolean {
        val host = Uri.parse(normalize(url)).host.orEmpty()
        return isLocalDevHost(host)
    }

    /** 本地开发 / 模拟器用 HTTP，公网域名默认 HTTPS */
    fun isLocalDevHost(host: String): Boolean {
        val h = host.lowercase()
        return h == "localhost" ||
            h == "10.0.2.2" ||
            h.startsWith("127.") ||
            h.startsWith("192.168.") ||
            h.startsWith("10.") ||
            h.endsWith(".local")
    }

    /** WebSocket 仅连接 /ws；senderId、routeHash 在连接后 auth 帧发送（避免出现在 URL 日志）。 */
    fun webSocketUrl(baseUrl: String): String {
        val uri = Uri.parse(normalize(baseUrl))
        val scheme = if (uri.scheme == "https") "wss" else "ws"
        return uri.buildUpon()
            .scheme(scheme)
            .path("/ws")
            .clearQuery()
            .build()
            .toString()
    }
}
