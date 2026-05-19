package app.zerorelay.data.crypto

import android.util.Base64
import org.json.JSONObject
import java.security.SecureRandom

/** 隧道内层 JSON 填充到固定档位，降低流量分析（与 server/cli 桶一致） */
object TunnelPadding {
    private val buckets = intArrayOf(256, 512, 1024, 2048)
    private val rng = SecureRandom()

    fun pad(inner: JSONObject): JSONObject {
        val out = JSONObject(inner.toString())
        for (target in buckets) {
            var len = out.toString().length
            if (len >= target) return out
            val need = target - len - 12
            if (need <= 0) continue
            val bytes = ByteArray(need.coerceAtMost(target))
            rng.nextBytes(bytes)
            out.put("_p", Base64.encodeToString(bytes, Base64.NO_WRAP))
            len = out.toString().length
            if (len <= target) return out
        }
        return out
    }
}
