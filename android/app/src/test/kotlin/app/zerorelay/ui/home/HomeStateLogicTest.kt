package app.zerorelay.ui.home

import app.zerorelay.data.model.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeStateLogicTest {
    @Test
    fun computeRelayStatusBar_notConfiguredWhenServerUntested() {
        val state = HomeUiState(serverConfigured = true, serverTested = false)
        val (bar, host) = HomeStateLogic.computeRelayStatusBar(state)
        assertEquals(RelayStatusBarState.NotConfigured, bar)
        assertEquals("", host)
    }

    @Test
    fun computeRelayStatusBar_onlineFromDetachedConnection() {
        val state = HomeUiState(
            serverConfigured = true,
            serverTested = true,
            serverUrl = "https://relay.example.com",
            detachedSessionCount = 1,
            detachedConnection = ConnectionState.Connected,
        )
        val (bar, host) = HomeStateLogic.computeRelayStatusBar(state)
        assertEquals(RelayStatusBarState.Online, bar)
        assertEquals("relay.example.com", host)
    }
}
