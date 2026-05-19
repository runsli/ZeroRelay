package app.zerorelay.data.crypto

/**
 * 协议生命周期策略（中优先级安全项）
 */
object ProtocolPolicy {
    /** v2 棘轮协议版本 */
    const val PROTOCOL_VERSION = MessageRatchet.PROTOCOL_VERSION

    /**
     * 此时间戳（毫秒）之后的消息不再使用静态会话密钥回退解密。
     * 仅影响无 v2 信封的旧密文；v2 棘轮不受影响。
     */
    const val LEGACY_STATIC_CUTOFF_MS: Long = 1_767_225_600_000L // 2026-01-01T00:00:00Z

    fun allowsLegacyStaticFallback(messageTimestamp: Long): Boolean =
        messageTimestamp < LEGACY_STATIC_CUTOFF_MS

    /** 长轮询 HTTP 超时（毫秒），与 relay /messages 默认一致 */
    const val POLL_TIMEOUT_MS: Long = 8_000L

    /** 长轮询基础间隔（毫秒），空结果时指数退避 */
    const val POLL_BASE_MS: Long = 1_000L

    const val POLL_JITTER_MS: Long = 500L

    /** 空轮询退避上限（毫秒） */
    const val POLL_MAX_BACKOFF_MS: Long = 15_000L

    /** WebSocket 已连接时 HTTP 轮询间隔（降低双通道指纹） */
    const val POLL_WS_CONNECTED_MS: Long = 30_000L
}
