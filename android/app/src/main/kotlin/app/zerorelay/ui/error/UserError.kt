package app.zerorelay.ui.error

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.stringResource
import app.zerorelay.R

enum class UserErrorKind {
    ParseInvite,
    ScanUnrecognized,
    TlsChanged,
    ServerUnreachable,
    ServerRequired,
    ReleaseTlsRequired,
    DecryptFailed,
    JoinGroup,
    ChatConnect,
    SignatureRejected,
    WsAuthFailed,
    AddContactFailed,
    CreateGroup,
    GroupExpired,
    NotReady,
    Generic,
}

data class UserError(
    val kind: UserErrorKind,
    val detail: String? = null,
)

data class UserErrorCopy(
    val title: String,
    val action: String?,
    val detail: String?,
)

@Composable
@ReadOnlyComposable
fun UserError.resolveCopy(): UserErrorCopy {
    val detailText = detail?.takeIf { it.isNotBlank() }
    return when (kind) {
        UserErrorKind.ParseInvite -> UserErrorCopy(
            title = stringResource(R.string.user_error_parse_invite_title),
            action = stringResource(R.string.user_error_parse_invite_action),
            detail = detailText ?: stringResource(R.string.user_error_parse_invite_detail),
        )
        UserErrorKind.ScanUnrecognized -> UserErrorCopy(
            title = stringResource(R.string.user_error_scan_unrecognized_title),
            action = stringResource(R.string.user_error_scan_unrecognized_action),
            detail = detailText,
        )
        UserErrorKind.TlsChanged -> UserErrorCopy(
            title = stringResource(R.string.user_error_tls_changed_title),
            action = stringResource(R.string.user_error_tls_changed_action),
            detail = detailText,
        )
        UserErrorKind.ServerUnreachable -> UserErrorCopy(
            title = stringResource(R.string.user_error_server_unreachable_title),
            action = stringResource(R.string.user_error_server_unreachable_action),
            detail = detailText,
        )
        UserErrorKind.ServerRequired -> UserErrorCopy(
            title = stringResource(R.string.user_error_server_required_title),
            action = stringResource(R.string.user_error_server_required_action),
            detail = detailText,
        )
        UserErrorKind.ReleaseTlsRequired -> UserErrorCopy(
            title = stringResource(R.string.user_error_release_tls_title),
            action = stringResource(R.string.user_error_release_tls_action),
            detail = detailText,
        )
        UserErrorKind.DecryptFailed -> UserErrorCopy(
            title = stringResource(R.string.user_error_decrypt_failed_title),
            action = stringResource(R.string.user_error_decrypt_failed_action),
            detail = detailText,
        )
        UserErrorKind.JoinGroup -> UserErrorCopy(
            title = stringResource(R.string.user_error_join_group_title),
            action = stringResource(R.string.user_error_join_group_action),
            detail = detailText,
        )
        UserErrorKind.ChatConnect -> UserErrorCopy(
            title = stringResource(R.string.user_error_chat_connect_title),
            action = stringResource(R.string.user_error_chat_connect_action),
            detail = detailText,
        )
        UserErrorKind.SignatureRejected -> UserErrorCopy(
            title = stringResource(R.string.user_error_signature_title),
            action = stringResource(R.string.user_error_signature_action),
            detail = detailText,
        )
        UserErrorKind.WsAuthFailed -> UserErrorCopy(
            title = stringResource(R.string.user_error_ws_auth_title),
            action = stringResource(R.string.user_error_ws_auth_action),
            detail = detailText,
        )
        UserErrorKind.AddContactFailed -> UserErrorCopy(
            title = stringResource(R.string.user_error_add_contact_title),
            action = stringResource(R.string.user_error_add_contact_action),
            detail = detailText,
        )
        UserErrorKind.CreateGroup -> UserErrorCopy(
            title = stringResource(R.string.user_error_create_group_title),
            action = stringResource(R.string.user_error_create_group_action),
            detail = detailText,
        )
        UserErrorKind.GroupExpired -> UserErrorCopy(
            title = stringResource(R.string.user_error_group_expired_title),
            action = stringResource(R.string.user_error_group_expired_action),
            detail = detailText,
        )
        UserErrorKind.NotReady -> UserErrorCopy(
            title = stringResource(R.string.user_error_not_ready_title),
            action = stringResource(R.string.user_error_not_ready_action),
            detail = detailText,
        )
        UserErrorKind.Generic -> UserErrorCopy(
            title = detailText ?: stringResource(R.string.user_error_generic_title),
            action = null,
            detail = null,
        )
    }
}

fun UserError.displayTitle(context: Context): String = context.getString(copyRes(kind).titleRes)

private data class ErrorRes(val titleRes: Int)

private fun copyRes(kind: UserErrorKind): ErrorRes = when (kind) {
    UserErrorKind.ParseInvite -> ErrorRes(R.string.user_error_parse_invite_title)
    UserErrorKind.ScanUnrecognized -> ErrorRes(R.string.user_error_scan_unrecognized_title)
    UserErrorKind.TlsChanged -> ErrorRes(R.string.user_error_tls_changed_title)
    UserErrorKind.ServerUnreachable -> ErrorRes(R.string.user_error_server_unreachable_title)
    UserErrorKind.ServerRequired -> ErrorRes(R.string.user_error_server_required_title)
    UserErrorKind.ReleaseTlsRequired -> ErrorRes(R.string.user_error_release_tls_title)
    UserErrorKind.DecryptFailed -> ErrorRes(R.string.user_error_decrypt_failed_title)
    UserErrorKind.JoinGroup -> ErrorRes(R.string.user_error_join_group_title)
    UserErrorKind.ChatConnect -> ErrorRes(R.string.user_error_chat_connect_title)
    UserErrorKind.SignatureRejected -> ErrorRes(R.string.user_error_signature_title)
    UserErrorKind.WsAuthFailed -> ErrorRes(R.string.user_error_ws_auth_title)
    UserErrorKind.AddContactFailed -> ErrorRes(R.string.user_error_add_contact_title)
    UserErrorKind.CreateGroup -> ErrorRes(R.string.user_error_create_group_title)
    UserErrorKind.GroupExpired -> ErrorRes(R.string.user_error_group_expired_title)
    UserErrorKind.NotReady -> ErrorRes(R.string.user_error_not_ready_title)
    UserErrorKind.Generic -> ErrorRes(R.string.user_error_generic_title)
}
