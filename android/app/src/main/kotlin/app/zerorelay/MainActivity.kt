package app.zerorelay

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.os.Bundle
import app.zerorelay.data.crypto.CryptoLog
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.zerorelay.ui.AppRoot
import app.zerorelay.ui.notification.AppForegroundTracker

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_ROOM_ID = "extra_room_id"
    }

    private var pendingNotificationRoomId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppForegroundTracker.attach(application)
        pendingNotificationRoomId = intent.getStringExtra(EXTRA_ROOM_ID)
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            CryptoLog.enabled = true
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        setContent {
            AppRoot(
                pendingNotificationRoomId = pendingNotificationRoomId,
                onNotificationRoomConsumed = { pendingNotificationRoomId = null },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingNotificationRoomId = intent.getStringExtra(EXTRA_ROOM_ID)
    }
}
