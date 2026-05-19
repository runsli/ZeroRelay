package app.zerorelay.data.model

data class Contact(
    val id: String,
    val displayName: String,
    val publicKeyBase64: String,
    val addedAt: Long = System.currentTimeMillis(),
    /** 用户已核对安全码（防中间人） */
    val verified: Boolean = false,
    val verifiedAt: Long? = null,
) {
    val fingerprint: String
        get() = app.zerorelay.data.crypto.IdentityCrypto.fingerprint(
            app.zerorelay.data.crypto.IdentityCrypto.decodePublicKey(publicKeyBase64),
        )
}

data class Identity(
    val publicKey: ByteArray,
    val privateKey: ByteArray,
) {
    val publicKeyBase64: String
        get() = app.zerorelay.data.crypto.IdentityCrypto.encodePublicKey(publicKey)

    val fingerprint: String
        get() = app.zerorelay.data.crypto.IdentityCrypto.fingerprint(publicKey)
}
