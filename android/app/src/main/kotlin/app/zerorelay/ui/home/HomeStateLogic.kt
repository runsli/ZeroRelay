package app.zerorelay.ui.home

import android.net.Uri
import app.zerorelay.data.local.UserPreferences
import app.zerorelay.data.model.ConnectionState
import app.zerorelay.data.model.Contact
import app.zerorelay.data.network.ServerUrl

object HomeStateLogic {
    fun isSetupIncomplete(prefs: UserPreferences, contacts: List<Contact>): Boolean {
        val configured = !prefs.getServerUrl().isNullOrBlank()
        val tested = prefs.isServerTested()
        return !configured || !tested || contacts.isEmpty()
    }

    fun initialOnboardingStep(prefs: UserPreferences): OnboardingStep =
        if (!prefs.isServerTested()) OnboardingStep.Server else OnboardingStep.AddContact

    fun relayHostLabel(url: String): String {
        if (url.isBlank()) return ""
        return try {
            Uri.parse(ServerUrl.normalize(url)).host?.takeIf { it.isNotBlank() } ?: url
        } catch (_: Exception) {
            url
        }
    }

    fun computeRelayStatusBar(state: HomeUiState): Pair<RelayStatusBarState, String> {
        if (!state.serverConfigured || !state.serverTested) {
            return RelayStatusBarState.NotConfigured to ""
        }
        if (state.serverChecking) {
            return RelayStatusBarState.Checking to relayHostLabel(state.serverUrl)
        }
        val host = relayHostLabel(state.serverUrl)
        if (state.detachedSessionCount > 0) {
            return when (state.detachedConnection) {
                ConnectionState.Connected -> RelayStatusBarState.Online to host
                ConnectionState.Connecting -> RelayStatusBarState.Connecting to host
                ConnectionState.Error -> RelayStatusBarState.Error to host
                ConnectionState.Disconnected -> RelayStatusBarState.Disconnected to host
            }
        }
        return when (state.serverCheckOk) {
            false -> RelayStatusBarState.Error to host
            else -> RelayStatusBarState.Online to host
        }
    }

    fun withSetupFlags(prefs: UserPreferences, state: HomeUiState): HomeUiState {
        val configured = !prefs.getServerUrl().isNullOrBlank()
        val tested = prefs.isServerTested()
        val incomplete = isSetupIncomplete(prefs, state.contacts)
        val (relayBar, host) = computeRelayStatusBar(
            state.copy(serverConfigured = configured, serverTested = tested),
        )
        return state.copy(
            serverConfigured = configured,
            serverTested = tested,
            setupIncomplete = incomplete,
            showSetupContinueBanner = prefs.isOnboardingDismissed() && incomplete,
            relayStatusBar = relayBar,
            relayHostLabel = host,
        )
    }
}
