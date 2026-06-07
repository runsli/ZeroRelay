package app.zerorelay.data.network

import android.content.Context
import app.zerorelay.data.error.DataError
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
        if (url.isEmpty()) return@withContext Result.failure(DataError.ServerUrlEmpty)
        val client = RelayHttpClient.create(context, url)
        val request = Request.Builder().url("$url/").get().build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw DataError.ServerHttpError(response.code)
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
                    throw DataError.ServerResponseInvalid
                }
                CheckResult(url, pinEval)
            }
        }
    }

    class CertificatePinMismatchException(val newPin: String) : Exception("tls certificate pin mismatch")
}
