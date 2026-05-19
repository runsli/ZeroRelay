package app.zerorelay.data.network

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

object ServerHealth {
    data class CheckResult(
        val normalizedUrl: String,
        val pinEvaluation: TlsPinStore.PinEvaluation,
    )

    suspend fun check(context: Context, baseUrl: String): Result<CheckResult> = withContext(Dispatchers.IO) {
        val url = ServerUrl.normalize(baseUrl)
        if (url.isEmpty()) return@withContext Result.failure(IllegalArgumentException("服务器地址为空"))
        val client = RelayHttpClient.create(context, url)
        val request = Request.Builder().url("$url/").get().build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("HTTP ${response.code}")
                }
                val handshake = response.handshake
                val pinEval = if (handshake != null) {
                    RelayHttpClient.evaluatePin(context, url, handshake.peerCertificates)
                } else {
                    TlsPinStore.PinEvaluation(trusted = true, newPinToTrust = null)
                }
                if (!pinEval.trusted && pinEval.newPinToTrust != null) {
                    throw CertificatePinMismatchException(pinEval.newPinToTrust)
                }
                val body = response.body?.string().orEmpty()
                val status = runCatching { org.json.JSONObject(body).optString("status") }.getOrDefault("")
                if (status != "ok") {
                    throw IllegalStateException("服务器响应异常")
                }
                CheckResult(url, pinEval)
            }
        }
    }

    class CertificatePinMismatchException(val newPin: String) : Exception("服务器证书已变更，需确认后信任")
}
