package app.zerorelay.data.network

import android.content.Context
import app.zerorelay.BuildConfig

/**
 * Release 构建：公网 HTTPS 中继须先完成 TLS pin（测试连接并信任证书）。
 * Debug / 局域网 HTTP 不受限。
 */
object RelaySecurityPolicy {
    fun requiresTlsPin(serverUrl: String): Boolean =
        !BuildConfig.DEBUG && !ServerUrl.isLocalDevUrl(serverUrl)

    fun hasTlsPin(context: Context, serverUrl: String): Boolean =
        RelayHttpClient.hasPin(context, serverUrl)

    fun ensureRelayReady(context: Context, serverUrl: String): String? {
        if (!requiresTlsPin(serverUrl)) return null
        if (hasTlsPin(context, serverUrl)) return null
        return "release_tls_pin_required"
    }
}
