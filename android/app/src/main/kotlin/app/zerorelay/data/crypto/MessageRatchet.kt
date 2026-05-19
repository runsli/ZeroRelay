package app.zerorelay.data.crypto

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.json.JSONObject

/**
 * 对称链式棘轮：每条消息使用独立消息密钥，链密钥前向推进。
 * 双方 root 相同；A 的 send 链与 B 的 recv 链参数一致。
 */
class MessageRatchet private constructor(
    private var sendChain: ByteArray,
    private var recvChain: ByteArray,
    var sendSeq: Long,
    var recvSeq: Long,
) {
    data class DecryptResult(
        val text: String,
        val usedLegacyStaticKey: Boolean = false,
    )

    fun encryptEnvelope(plaintext: String): Pair<ByteArray, CryptoService.EncryptedPayload> {
        val (messageKey, nextSend) = stepChain(sendChain)
        sendChain = nextSend
        val seq = sendSeq
        sendSeq += 1
        val padded = MessagePadding.pad(plaintext)
        val envelope = JSONObject().apply {
            put("v", PROTOCOL_VERSION)
            put("s", seq)
            put("t", padded)
        }
        val payload = CryptoService.encryptBlocking(envelope.toString(), messageKey)
        return messageKey to payload
    }

    fun decryptEnvelope(
        payload: CryptoService.EncryptedPayload,
        allowLegacyStaticKey: ByteArray?,
        messageTimestamp: Long,
    ): DecryptResult? {
        val fromRecv = tryDecryptWithRecvChain(payload)
        if (fromRecv != null) {
            return DecryptResult(MessagePadding.unwrap(fromRecv), usedLegacyStaticKey = false)
        }
        val legacyKey = if (ProtocolPolicy.allowsLegacyStaticFallback(messageTimestamp)) {
            allowLegacyStaticKey
        } else {
            null
        }
        if (legacyKey != null) {
            val plain = CryptoService.decryptBlocking(
                payload.ciphertext,
                payload.iv,
                payload.tag,
                legacyKey,
            )?.let { parseLegacyPlain(it) }?.let { MessagePadding.unwrap(it) }
            if (plain != null) {
                return DecryptResult(plain, usedLegacyStaticKey = true)
            }
        }
        return null
    }

    private fun tryDecryptWithRecvChain(payload: CryptoService.EncryptedPayload): String? {
        var chain = recvChain
        var rs = recvSeq
        // 允许向前追赶链（对方 seq 领先本地 recvSeq 时，与 CLI 行为对齐并修复换机/重进房间）
        repeat(MAX_RECV_CATCHUP) {
            val (messageKey, nextChain) = stepChain(chain)
            val plain = CryptoService.decryptBlocking(
                payload.ciphertext,
                payload.iv,
                payload.tag,
                messageKey,
            ) ?: return null
            val envelope = parseEnvelope(plain) ?: return null
            val seq = envelope.getLong("s")
            when {
                seq < rs -> {
                    val t = envelope.optString("t").takeIf { it.isNotEmpty() } ?: return null
                    return MessagePadding.unwrap(t)
                }
                seq > rs -> {
                    chain = nextChain
                    rs += 1
                    return@repeat
                }
                else -> {
                    recvChain = nextChain
                    recvSeq = rs + 1
                    val t = envelope.optString("t").takeIf { it.isNotEmpty() } ?: return null
                    return MessagePadding.unwrap(t)
                }
            }
        }
        return null
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("send", android.util.Base64.encodeToString(sendChain, android.util.Base64.NO_WRAP))
        put("recv", android.util.Base64.encodeToString(recvChain, android.util.Base64.NO_WRAP))
        put("ss", sendSeq)
        put("rs", recvSeq)
    }

    companion object {
        const val PROTOCOL_VERSION = 2
        private const val MAX_RECV_CATCHUP = 256

        fun fromRoot(
            rootKey: ByteArray,
            roomId: String,
            localPublicKey: ByteArray,
            peerPublicKey: ByteArray,
        ): MessageRatchet {
            val salt = roomId.toByteArray(Charsets.UTF_8)
            val localFirst = comparePublicKeys(localPublicKey, peerPublicKey) <= 0
            val sendInfo = if (localFirst) "chain-send-v2" else "chain-recv-v2"
            val recvInfo = if (localFirst) "chain-recv-v2" else "chain-send-v2"
            return MessageRatchet(
                sendChain = hkdf(rootKey, salt, sendInfo),
                recvChain = hkdf(rootKey, salt, recvInfo),
                sendSeq = 0,
                recvSeq = 0,
            )
        }

        private fun comparePublicKeys(a: ByteArray, b: ByteArray): Int {
            val n = minOf(a.size, b.size)
            for (i in 0 until n) {
                val diff = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
                if (diff != 0) return diff
            }
            return a.size - b.size
        }

        fun fromJson(json: JSONObject): MessageRatchet? = try {
            MessageRatchet(
                sendChain = android.util.Base64.decode(json.getString("send"), android.util.Base64.NO_WRAP),
                recvChain = android.util.Base64.decode(json.getString("recv"), android.util.Base64.NO_WRAP),
                sendSeq = json.getLong("ss"),
                recvSeq = json.getLong("rs"),
            )
        } catch (_: Exception) {
            null
        }

        private fun stepChain(chainKey: ByteArray): Pair<ByteArray, ByteArray> {
            val messageKey = hkdf(chainKey, chainKey, "message")
            val next = hkdf(chainKey, chainKey, "chain")
            return messageKey to next
        }

        private fun hkdf(ikm: ByteArray, salt: ByteArray, info: String): ByteArray {
            val gen = HKDFBytesGenerator(SHA256Digest())
            gen.init(HKDFParameters(ikm, salt, info.toByteArray(Charsets.UTF_8)))
            val out = ByteArray(32)
            gen.generateBytes(out, 0, out.size)
            return out
        }

        private fun parseEnvelope(plain: String): JSONObject? = try {
            val json = JSONObject(plain)
            if (json.optInt("v") != PROTOCOL_VERSION) null else json
        } catch (_: Exception) {
            null
        }

        private fun parseLegacyPlain(plain: String): String? {
            val json = runCatching { JSONObject(plain) }.getOrNull()
            if (json != null && json.optInt("v") == PROTOCOL_VERSION) {
                return json.optString("t").takeIf { it.isNotEmpty() }
            }
            return plain
        }
    }
}
