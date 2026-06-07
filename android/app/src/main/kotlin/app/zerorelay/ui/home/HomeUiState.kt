package app.zerorelay.ui.home

import app.zerorelay.data.model.ChatGroup
import app.zerorelay.data.model.ChatSession
import app.zerorelay.data.model.ConnectionState
import app.zerorelay.data.model.Contact
import app.zerorelay.data.model.Identity
import app.zerorelay.ui.error.UserError

enum class HomeTab {
    Conversations,
    Contacts,
    Groups,
}

enum class OnboardingStep {
    Server,
    Identity,
    AddContact,
}

enum class RelayStatusBarState {
    NotConfigured,
    Checking,
    Online,
    Connecting,
    Disconnected,
    Error,
}

sealed class ScanHandleResult {
    data object ContactAdded : ScanHandleResult()
    data class GroupJoined(val session: ChatSession) : ScanHandleResult()
    data class Error(val userError: UserError) : ScanHandleResult()
}

sealed class PasteResult {
    data object ContactAdded : PasteResult()
    data class GroupJoined(val session: ChatSession) : PasteResult()
    data object Failed : PasteResult()
}

data class HomeUiState(
    val serverUrl: String = "",
    val identity: Identity? = null,
    val contacts: List<Contact> = emptyList(),
    val groups: List<ChatGroup> = emptyList(),
    val selectedTab: HomeTab = HomeTab.Conversations,
    val conversations: List<ConversationRowUi> = emptyList(),
    val myQrPayload: String = "",
    val userError: UserError? = null,
    val showMyQr: Boolean = false,
    val showPasteDialog: Boolean = false,
    val pasteText: String = "",
    val showCreateGroup: Boolean = false,
    val createGroupName: String = "",
    val createGroupMemberIds: Set<String> = emptySet(),
    val inviteGroup: ChatGroup? = null,
    val inviteHighlightRotation: Boolean = false,
    val serverChecking: Boolean = false,
    val serverCheckOk: Boolean? = null,
    val tlsPinned: Boolean = false,
    val pendingTlsPin: String? = null,
    val showMigrationGuide: Boolean = false,
    val showMigrationImportChecklist: Boolean = false,
    val showAccountBackupDialog: Boolean = false,
    val accountBackupPassphrase: String = "",
    val showAccountImportOverwriteDialog: Boolean = false,
    val showRatchetBackupDialog: Boolean = false,
    val showRatchetAdvanced: Boolean = false,
    val ratchetBackupPassphrase: String = "",
    val useDynamicColor: Boolean = true,
    val allowScreenshots: Boolean = true,
    val keepAliveInBackground: Boolean = true,
    val detachedSessionCount: Int = 0,
    val detachedChatName: String? = null,
    val detachedConnection: ConnectionState = ConnectionState.Disconnected,
    val maxBackgroundSessions: Int = 3,
    val batteryOptimizationIgnored: Boolean = true,
    val showOnboarding: Boolean = false,
    val onboardingStep: OnboardingStep = OnboardingStep.Server,
    val serverConfigured: Boolean = false,
    val serverTested: Boolean = false,
    val setupIncomplete: Boolean = true,
    val showSetupContinueBanner: Boolean = false,
    val relayStatusBar: RelayStatusBarState = RelayStatusBarState.NotConfigured,
    val relayHostLabel: String = "",
    val nicknameDialogContact: Contact? = null,
    val nicknameDraft: String = "",
    val nicknameIsEdit: Boolean = false,
    val searchQuery: String = "",
    val searchActive: Boolean = false,
)
