package app.zerorelay.ui.home

import android.app.Application
import app.zerorelay.R
import app.zerorelay.data.local.UserPreferences
import app.zerorelay.data.network.RelayHttpClient
import app.zerorelay.data.network.RelaySecurityPolicy
import app.zerorelay.data.network.ServerHealth
import app.zerorelay.data.network.ServerUrl
import app.zerorelay.ui.error.UserError
import app.zerorelay.ui.error.UserErrorKind
import app.zerorelay.ui.error.UserErrorMapping
import app.zerorelay.ui.snackbar.AppSnackbarBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Relay server URL, connection test, and TLS pin confirmation.
 */
class RelaySettingsActions(
    private val app: Application,
    private val prefs: UserPreferences,
    private val scope: CoroutineScope,
    private val getState: () -> HomeUiState,
    private val updateState: ((HomeUiState) -> HomeUiState) -> Unit,
    private val withSetupFlags: (HomeUiState) -> HomeUiState,
    private val setUserError: (UserError) -> Unit,
    private val appStr: (Int, Array<out Any>) -> String,
) {
    fun onServerUrlChange(value: String) = updateState {
        if (value.trim() != it.serverUrl.trim()) {
            prefs.setServerTested(false)
        }
        withSetupFlags(it.copy(serverUrl = value, userError = null, serverCheckOk = null))
    }

    fun saveServerUrl() {
        val url = ServerUrl.normalize(getState().serverUrl)
        if (url.isEmpty()) {
            setUserError(UserError(UserErrorKind.ServerRequired))
            return
        }
        if (RelaySecurityPolicy.requiresTlsPin(url) && !RelayHttpClient.hasPin(app, url)) {
            setUserError(UserError(UserErrorKind.ReleaseTlsRequired))
            return
        }
        prefs.setServerUrl(url)
        updateState { it.copy(serverUrl = url, serverCheckOk = null) }
    }

    fun testServerConnection() {
        scope.launch {
            val raw = getState().serverUrl
            updateState { it.copy(serverChecking = true, userError = null, serverCheckOk = null) }
            ServerHealth.check(app, raw)
                .onSuccess { result ->
                    prefs.setServerUrl(result.normalizedUrl)
                    prefs.setServerTested(true)
                    updateState {
                        withSetupFlags(
                            it.copy(
                                serverUrl = result.normalizedUrl,
                                serverChecking = false,
                                serverCheckOk = true,
                                userError = null,
                                pendingTlsPin = null,
                                tlsPinned = RelayHttpClient.hasPin(app, result.normalizedUrl),
                            ),
                        )
                    }
                    AppSnackbarBus.show(
                        if (RelayHttpClient.hasPin(app, result.normalizedUrl)) {
                            appStr(R.string.snackbar_connection_pinned, emptyArray())
                        } else {
                            appStr(R.string.snackbar_connection_ok, emptyArray())
                        },
                    )
                }
                .onFailure { e ->
                    when (e) {
                        is ServerHealth.CertificatePinMismatchException -> {
                            updateState {
                                it.copy(
                                    serverChecking = false,
                                    serverCheckOk = false,
                                    pendingTlsPin = e.newPin,
                                    userError = UserError(UserErrorKind.TlsChanged),
                                )
                            }
                        }
                        else -> {
                            updateState {
                                it.copy(
                                    serverChecking = false,
                                    serverCheckOk = false,
                                    userError = UserErrorMapping.fromThrowable(e),
                                )
                            }
                        }
                    }
                }
        }
    }

    fun trustPendingTlsPin() {
        val pin = getState().pendingTlsPin ?: return
        val url = getState().serverUrl
        RelayHttpClient.trustNewPin(app, url, pin)
        prefs.setServerTested(true)
        prefs.setServerUrl(ServerUrl.normalize(url))
        updateState {
            withSetupFlags(
                it.copy(
                    pendingTlsPin = null,
                    tlsPinned = true,
                    serverCheckOk = true,
                    userError = null,
                ),
            )
        }
        AppSnackbarBus.show(appStr(R.string.snackbar_tls_trusted, emptyArray()))
    }

    fun dismissPendingTlsPin() = updateState {
        it.copy(pendingTlsPin = null, userError = null)
    }

    fun resolveServerUrlForSession(): String {
        val saved = prefs.getServerUrl()?.takeIf { it.isNotBlank() }
        if (saved != null) return ServerUrl.normalize(saved)
        val draft = getState().serverUrl.takeIf { it.isNotBlank() }
        if (draft != null) return ServerUrl.normalize(draft)
        return ""
    }
}
