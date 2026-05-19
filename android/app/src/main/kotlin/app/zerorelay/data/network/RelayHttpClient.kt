package app.zerorelay.data.network

import android.content.Context
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object RelayHttpClient {
    fun create(context: Context, baseUrl: String? = null): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(35, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)

        val host = baseUrl?.let {
            runCatching { android.net.Uri.parse(ServerUrl.normalize(it)).host }.getOrNull()
        }
        if (!host.isNullOrBlank()) {
            TlsPinStore.migrateFromLegacyIfNeeded(context)
            val pins = TlsPinStore.loadPins(context, host)
            if (pins.isNotEmpty()) {
                val pinnerBuilder = CertificatePinner.Builder()
                pins.forEach { pinnerBuilder.add(host, it) }
                builder.certificatePinner(pinnerBuilder.build())
            }
        }
        return builder.build()
    }

    fun rememberCertificatePin(context: Context, baseUrl: String, certificates: List<java.security.cert.Certificate>) {
        val host = android.net.Uri.parse(ServerUrl.normalize(baseUrl)).host ?: return
        val eval = TlsPinStore.evaluateHandshake(context, host, certificates)
        if (eval.newPinToTrust != null) {
            // 需用户确认后再 addPin；测试连接成功且 pin 已匹配时无 newPin
        }
    }

    fun trustNewPin(context: Context, baseUrl: String, pinHash: String) {
        val host = android.net.Uri.parse(ServerUrl.normalize(baseUrl)).host ?: return
        TlsPinStore.addPin(context, host, pinHash)
    }

    fun clearPins(context: Context, host: String) {
        TlsPinStore.clearHost(context, host)
    }

    fun hasPin(context: Context, baseUrl: String): Boolean {
        val host = android.net.Uri.parse(ServerUrl.normalize(baseUrl)).host ?: return false
        return TlsPinStore.hasPin(context, host)
    }

    fun evaluatePin(context: Context, baseUrl: String, certificates: List<java.security.cert.Certificate>): TlsPinStore.PinEvaluation {
        val host = android.net.Uri.parse(ServerUrl.normalize(baseUrl)).host ?: return TlsPinStore.PinEvaluation(true, null)
        return TlsPinStore.evaluateHandshake(context, host, certificates)
    }
}
