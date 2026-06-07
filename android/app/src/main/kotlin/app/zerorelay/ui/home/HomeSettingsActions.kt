package app.zerorelay.ui.home

import android.app.Application
import app.zerorelay.R
import app.zerorelay.data.chat.RelayMessagingHub
import app.zerorelay.data.local.UserPreferences
import app.zerorelay.ui.snackbar.AppSnackbarBus
import app.zerorelay.ui.util.BatteryOptimizationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * App settings toggles from the home screen.
 */
class HomeSettingsActions(
    private val app: Application,
    private val prefs: UserPreferences,
    private val hub: RelayMessagingHub,
    private val scope: CoroutineScope,
    private val updateState: ((HomeUiState) -> HomeUiState) -> Unit,
    private val appStr: (Int, Array<out Any>) -> String,
    private val refreshHomeConnectionUi: () -> Unit,
) {
    fun setUseDynamicColor(enabled: Boolean) {
        prefs.setUseDynamicColor(enabled)
        updateState { it.copy(useDynamicColor = enabled) }
    }

    fun setAllowScreenshots(allow: Boolean) {
        prefs.setAllowScreenshots(allow)
        updateState { it.copy(allowScreenshots = allow) }
    }

    fun clearAllLocalMessages() {
        scope.launch {
            hub.clearAllPersistedMessages()
            AppSnackbarBus.show(appStr(R.string.snackbar_messages_cleared, emptyArray()))
        }
    }

    fun setKeepAliveInBackground(enabled: Boolean) {
        prefs.setKeepAliveInBackground(enabled)
        updateState { it.copy(keepAliveInBackground = enabled) }
        hub.syncForegroundService()
        if (!enabled && hub.detachedSessionsList.isNotEmpty()) {
            AppSnackbarBus.show(appStr(R.string.settings_keep_alive_disabled_hint, emptyArray()))
        }
    }

    fun refreshBatteryOptimizationStatus() {
        updateState {
            it.copy(
                batteryOptimizationIgnored = BatteryOptimizationHelper.isIgnoringOptimizations(app),
            )
        }
    }

    fun setMaxBackgroundSessions(count: Int) {
        prefs.setMaxBackgroundSessions(count)
        hub.trimDetachedToCap()
        updateState { it.copy(maxBackgroundSessions = prefs.getMaxBackgroundSessions()) }
        refreshHomeConnectionUi()
    }
}
