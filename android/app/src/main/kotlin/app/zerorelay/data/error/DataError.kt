package app.zerorelay.data.error

/**
 * Typed data-layer failures. Messages are for logs/debug only — map to [app.zerorelay.ui.error.UserError] in UI.
 */
sealed class DataError(
    message: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {
    object AddSelfAsContact : DataError("cannot add own public key")
    object GroupNameEmpty : DataError("group name is empty")
    object GroupInviteExpired : DataError("group invite expired")
    object GroupInviteKeyInvalid : DataError("group invite key invalid")
    object GroupNotFound : DataError("group not found")
    object IdentityKeyInvalid : DataError("identity key invalid")
    object GroupSessionKeyInvalid : DataError("group session key invalid")
    object BackupFormatUnsupported : DataError("backup format unsupported")
    object BackupVersionUnsupported : DataError("backup version unsupported")
    object ServerUrlEmpty : DataError("server url empty")
    object ServerResponseInvalid : DataError("server response invalid")
    data class ServerHttpError(val code: Int) : DataError("server http $code")
    object InvalidPublicKey : DataError("invalid public key length")
    object InvalidPrivateKeyWrap : DataError("invalid wrapped private key")
    object MessageTooLong : DataError("message too long")
}
