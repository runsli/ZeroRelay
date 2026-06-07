package app.zerorelay.ui.error

import app.zerorelay.data.error.DataError
import app.zerorelay.data.network.ServerHealth
import javax.crypto.AEADBadTagException

object UserErrorMapping {
    fun fromThrowable(t: Throwable): UserError = when (t) {
        is DataError -> t.toUserError()
        is ServerHealth.CertificatePinMismatchException -> UserError(UserErrorKind.TlsChanged)
        is AEADBadTagException -> UserError(UserErrorKind.BackupRestoreFailed)
        else -> UserError(
            UserErrorKind.Generic,
            t.message?.takeIf { it.isNotBlank() },
        )
    }

    private fun DataError.toUserError(): UserError = when (this) {
        DataError.AddSelfAsContact -> UserError(UserErrorKind.AddContactFailed)
        DataError.GroupNameEmpty -> UserError(UserErrorKind.CreateGroup)
        DataError.GroupInviteExpired -> UserError(UserErrorKind.GroupExpired)
        DataError.GroupInviteKeyInvalid -> UserError(UserErrorKind.JoinGroup)
        DataError.GroupNotFound -> UserError(UserErrorKind.Generic)
        DataError.IdentityKeyInvalid -> UserError(UserErrorKind.BackupRestoreFailed)
        DataError.GroupSessionKeyInvalid -> UserError(UserErrorKind.JoinGroup)
        DataError.BackupFormatUnsupported,
        DataError.BackupVersionUnsupported,
        -> UserError(UserErrorKind.BackupRestoreFailed)
        DataError.ServerUrlEmpty -> UserError(UserErrorKind.ServerRequired)
        DataError.ServerResponseInvalid,
        is DataError.ServerHttpError,
        -> UserError(UserErrorKind.ServerUnreachable, message)
        DataError.InvalidPublicKey -> UserError(UserErrorKind.ParseInvite)
        DataError.InvalidPrivateKeyWrap -> UserError(UserErrorKind.BackupRestoreFailed)
        DataError.MessageTooLong -> UserError(UserErrorKind.Generic)
    }
}
