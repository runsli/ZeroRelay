package app.zerorelay.ui.home

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import app.zerorelay.R
import androidx.lifecycle.viewModelScope
import app.zerorelay.data.chat.RelayMessagingHub
import app.zerorelay.data.chat.RelaySessionCoordinator
import app.zerorelay.data.crypto.AccountBackup
import app.zerorelay.data.crypto.AccountBackupFiles
import app.zerorelay.data.crypto.ContactExchange
import app.zerorelay.data.crypto.GroupExchange
import app.zerorelay.data.crypto.MessageCipher
import app.zerorelay.data.crypto.RatchetBackup
import app.zerorelay.data.crypto.RatchetBackupFiles
import app.zerorelay.data.network.TlsPinStore
import app.zerorelay.data.identity.IdentityStore
import app.zerorelay.data.local.ConversationEntity
import app.zerorelay.data.local.DetachedSessionStore
import app.zerorelay.data.local.UserPreferences
import app.zerorelay.data.model.ChatGroup
import app.zerorelay.data.model.ChatKind
import app.zerorelay.data.model.ChatSession
import app.zerorelay.data.model.ConnectionState
import app.zerorelay.data.model.Contact
import app.zerorelay.data.model.Identity
import app.zerorelay.data.crypto.IdentityCrypto
import app.zerorelay.data.network.RelayHttpClient
import app.zerorelay.data.network.RelaySecurityPolicy
import app.zerorelay.data.network.ServerHealth
import app.zerorelay.data.network.ServerUrl
import app.zerorelay.data.session.SessionFactory
import app.zerorelay.ui.snackbar.AppSnackbarBus
import app.zerorelay.ui.util.BatteryOptimizationHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    data class Error(val message: String) : ScanHandleResult()
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
    val error: String? = null,
    val showMyQr: Boolean = false,
    val showPasteDialog: Boolean = false,
    val pasteText: String = "",
    val showCreateGroup: Boolean = false,
    val createGroupName: String = "",
    val createGroupMemberIds: Set<String> = emptySet(),
    val inviteGroup: ChatGroup? = null,
    val serverChecking: Boolean = false,
    val serverCheckOk: Boolean? = null,
    val tlsPinned: Boolean = false,
    /** 证书轮换：待用户确认的新 pin */
    val pendingTlsPin: String? = null,
    val showAccountBackupDialog: Boolean = false,
    val accountBackupPassphrase: String = "",
    val showAccountImportOverwriteDialog: Boolean = false,
    val showRatchetBackupDialog: Boolean = false,
    val showRatchetAdvanced: Boolean = false,
    val ratchetBackupPassphrase: String = "",
    /** Material You 动态配色；关闭时使用 #0F9D47 品牌主题。 */
    val useDynamicColor: Boolean = true,
    /** 为 true 时允许系统截图/录屏聊天界面。 */
    val allowScreenshots: Boolean = true,
    /** 返回首页后通过 Foreground Service 维持 relay 连接。 */
    val keepAliveInBackground: Boolean = true,
    /** 后台 detached 会话数；大于 0 时在首页显示监听状态。 */
    val detachedSessionCount: Int = 0,
    /** 单个 detached 时显示名称；多个时为 null。 */
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
    /** 添加联系人后或编辑昵称时显示；null 表示关闭。 */
    val nicknameDialogContact: Contact? = null,
    val nicknameDraft: String = "",
    val nicknameIsEdit: Boolean = false,
    val searchQuery: String = "",
    val searchActive: Boolean = false,
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val identityStore = IdentityStore(application)
    private val prefs = UserPreferences(application)
    private val hub = RelayMessagingHub.get(application)

    private fun appStr(@StringRes resId: Int, vararg args: Any): String =
        getApplication<Application>().getString(resId, *args)

    private fun isSetupIncomplete(contacts: List<Contact>): Boolean {
        val configured = !prefs.getServerUrl().isNullOrBlank()
        val tested = prefs.isServerTested()
        return !configured || !tested || contacts.isEmpty()
    }

    private fun initialOnboardingStep(): OnboardingStep = when {
        !prefs.isServerTested() -> OnboardingStep.Server
        else -> OnboardingStep.AddContact
    }

    private fun relayHostLabel(url: String): String {
        if (url.isBlank()) return ""
        return try {
            Uri.parse(ServerUrl.normalize(url)).host?.takeIf { it.isNotBlank() } ?: url
        } catch (_: Exception) {
            url
        }
    }

    private fun computeRelayStatusBar(state: HomeUiState): Pair<RelayStatusBarState, String> {
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

    private fun withSetupFlags(state: HomeUiState): HomeUiState {
        val configured = !prefs.getServerUrl().isNullOrBlank()
        val tested = prefs.isServerTested()
        val incomplete = isSetupIncomplete(state.contacts)
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

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
    private var conversationEntities: List<ConversationEntity> = emptyList()
    private var pendingAccountImportBlob: String? = null

    init {
        RelaySessionCoordinator.start(application)
        viewModelScope.launch {
            hub.conversationStore.observeAll().collect { entities ->
                conversationEntities = entities
                _uiState.update { state ->
                    state.copy(conversations = mapConversationRows(state.contacts, state.groups))
                }
            }
        }
        viewModelScope.launch {
            hub.backfillConversationsFromMessages()
            val identity = identityStore.getOrCreateIdentity()
            val server = prefs.getServerUrl().orEmpty()
            val contacts = identityStore.getContacts()
            val groups = identityStore.getGroups()
            val qr = ContactExchange.encodePayload(identity.publicKeyBase64, null)
            val showOnboarding = !prefs.isOnboardingDismissed() && isSetupIncomplete(contacts)
            hub.syncConversationPeers(identity, contacts, groups)
            _uiState.update {
                withSetupFlags(
                    it.copy(
                        identity = identity,
                        serverUrl = server,
                        contacts = contacts,
                        groups = groups,
                        myQrPayload = qr,
                        useDynamicColor = prefs.getUseDynamicColor(),
                        allowScreenshots = prefs.getAllowScreenshots(),
                        keepAliveInBackground = prefs.getKeepAliveInBackground(),
                        maxBackgroundSessions = prefs.getMaxBackgroundSessions(),
                        tlsPinned = server.isNotBlank() && RelayHttpClient.hasPin(getApplication(), server),
                        batteryOptimizationIgnored = BatteryOptimizationHelper.isIgnoringOptimizations(application),
                        showOnboarding = showOnboarding,
                        onboardingStep = if (showOnboarding) initialOnboardingStep() else OnboardingStep.Server,
                        serverCheckOk = if (prefs.isServerTested()) true else null,
                    ),
                )
            }
            restoreDetachedSessionsIfNeeded(identity)
            refreshHomeConnectionUi()
        }
        viewModelScope.launch {
            hub.detachedSessionsFlow.collect {
                refreshHomeConnectionUi()
            }
        }
        viewModelScope.launch {
            hub.aggregatedDetachedConnectionFlow.collect {
                refreshHomeConnectionUi()
            }
        }
        viewModelScope.launch {
            hub.detachedEvictionEvents.collect { event ->
                AppSnackbarBus.show(appStr(R.string.snackbar_detached_evicted, event.displayName))
                refreshHomeConnectionUi()
            }
        }
    }

    fun onServerUrlChange(value: String) = _uiState.update {
        if (value.trim() != it.serverUrl.trim()) {
            prefs.setServerTested(false)
        }
        withSetupFlags(it.copy(serverUrl = value, error = null, serverCheckOk = null))
    }

    fun selectTab(tab: HomeTab) = _uiState.update { it.copy(selectedTab = tab, error = null) }

    fun setSearchActive(active: Boolean) = _uiState.update { it.copy(searchActive = active) }

    fun onSearchQueryChange(query: String) = _uiState.update { it.copy(searchQuery = query) }

    fun clearSearch() = _uiState.update { it.copy(searchQuery = "", searchActive = false) }

    fun filteredConversations(): List<ConversationRowUi> =
        filterBySearch(_uiState.value.conversations) { it.displayName }

    fun filteredContacts(): List<Contact> =
        filterBySearch(_uiState.value.contacts) { it.displayName }

    fun filteredGroups(): List<ChatGroup> =
        filterBySearch(_uiState.value.groups) { it.displayName }

    fun hasSearchQuery(): Boolean = _uiState.value.searchQuery.isNotBlank()

    private fun <T> filterBySearch(items: List<T>, nameSelector: (T) -> String): List<T> {
        val query = _uiState.value.searchQuery.trim()
        if (query.isEmpty()) return items
        return items.filter { nameSelector(it).contains(query, ignoreCase = true) }
    }

    fun openEditNickname(contact: Contact) {
        _uiState.update {
            it.copy(
                nicknameDialogContact = contact,
                nicknameDraft = contact.displayName,
                nicknameIsEdit = true,
                error = null,
            )
        }
    }

    fun onNicknameDraftChange(value: String) = _uiState.update { it.copy(nicknameDraft = value) }

    fun skipNicknameDialog() = _uiState.update {
        it.copy(nicknameDialogContact = null, nicknameDraft = "", nicknameIsEdit = false)
    }

    fun confirmNicknameDialog() {
        val contact = _uiState.value.nicknameDialogContact ?: return
        saveContactNickname(contact.id, _uiState.value.nicknameDraft)
        skipNicknameDialog()
    }

    private fun promptNicknameDialog(contact: Contact) {
        _uiState.update {
            it.copy(
                nicknameDialogContact = contact,
                nicknameDraft = contact.displayName,
                nicknameIsEdit = false,
                error = null,
            )
        }
    }

    private fun saveContactNickname(contactId: String, displayName: String) {
        val identity = _uiState.value.identity ?: return
        val updated = identityStore.updateContactDisplayName(contactId, displayName) ?: return
        hub.registerContactConversation(identity, updated)
        val peerKey = IdentityCrypto.decodePublicKey(updated.publicKeyBase64)
        val roomId = IdentityCrypto.deriveRoomId(identity.publicKey, peerKey)
        hub.updateSessionDisplayName(roomId, updated.displayName)
        refreshContacts()
        refreshHomeConnectionUi()
    }

    fun saveServerUrl() {
        val url = ServerUrl.normalize(_uiState.value.serverUrl)
        if (url.isEmpty()) {
            _uiState.update { it.copy(error = appStr(R.string.error_server_required)) }
            return
        }
        if (RelaySecurityPolicy.requiresTlsPin(url) && !RelayHttpClient.hasPin(getApplication(), url)) {
            _uiState.update { it.copy(error = appStr(R.string.error_release_tls_pin_required)) }
            return
        }
        prefs.setServerUrl(url)
        _uiState.update { it.copy(serverUrl = url, serverCheckOk = null) }
    }

    fun testServerConnection() {
        viewModelScope.launch {
            val raw = _uiState.value.serverUrl
            _uiState.update { it.copy(serverChecking = true, error = null, serverCheckOk = null) }
            ServerHealth.check(getApplication(), raw)
                .onSuccess { result ->
                    prefs.setServerUrl(result.normalizedUrl)
                    prefs.setServerTested(true)
                    _uiState.update {
                        withSetupFlags(
                            it.copy(
                                serverUrl = result.normalizedUrl,
                                serverChecking = false,
                                serverCheckOk = true,
                                error = null,
                                pendingTlsPin = null,
                                tlsPinned = RelayHttpClient.hasPin(getApplication(), result.normalizedUrl),
                            ),
                        )
                    }
                    AppSnackbarBus.show(
                        if (RelayHttpClient.hasPin(getApplication(), result.normalizedUrl)) {
                            appStr(R.string.snackbar_connection_pinned)
                        } else {
                            appStr(R.string.snackbar_connection_ok)
                        },
                    )
                }
                .onFailure { e ->
                    when (e) {
                        is ServerHealth.CertificatePinMismatchException -> {
                            _uiState.update {
                                it.copy(
                                    serverChecking = false,
                                    serverCheckOk = false,
                                    pendingTlsPin = e.newPin,
                                    error = appStr(R.string.error_tls_changed),
                                )
                            }
                        }
                        else -> {
                            _uiState.update {
                                it.copy(
                                    serverChecking = false,
                                    serverCheckOk = false,
                                    error = appStr(
                                        R.string.error_server_unreachable,
                                        e.message ?: appStr(R.string.error_unknown),
                                    ),
                                )
                            }
                        }
                    }
                }
        }
    }

    fun refreshContacts() {
        _uiState.update { state ->
            val contacts = identityStore.getContacts()
            withSetupFlags(
                state.copy(
                    contacts = contacts,
                    error = null,
                    conversations = mapConversationRows(contacts, state.groups),
                ),
            )
        }
        maybeFinishOnboardingAfterContact()
    }

    fun refreshGroups() {
        _uiState.update { state ->
            val groups = identityStore.getGroups()
            state.copy(
                groups = groups,
                error = null,
                conversations = mapConversationRows(state.contacts, groups),
            )
        }
    }

    private fun mapConversationRows(
        contacts: List<Contact>,
        groups: List<ChatGroup>,
    ): List<ConversationRowUi> =
        conversationEntities.map { it.toRowUi(contacts, groups) }

    fun openConversation(roomId: String): ChatSession? = findChatSessionForRoom(roomId)

    fun contactForConversation(row: ConversationRowUi): Contact? {
        if (row.isGroup) return null
        return _uiState.value.contacts.find { contact ->
            contact.id == conversationEntities.find { it.roomId == row.roomId }?.peerContactId
        }
    }

    fun openMyQr() = _uiState.update { it.copy(showMyQr = true) }

    fun closeMyQr() = _uiState.update { it.copy(showMyQr = false) }

    fun openPasteDialog() = _uiState.update { it.copy(showPasteDialog = true, pasteText = "", error = null) }

    fun closePasteDialog() = _uiState.update { it.copy(showPasteDialog = false, pasteText = "") }

    fun onPasteTextChange(value: String) = _uiState.update { it.copy(pasteText = value) }

    fun openCreateGroup() = _uiState.update {
        it.copy(
            showCreateGroup = true,
            createGroupName = "",
            createGroupMemberIds = emptySet(),
            error = null,
        )
    }

    fun closeCreateGroup() = _uiState.update {
        it.copy(showCreateGroup = false, createGroupName = "", createGroupMemberIds = emptySet())
    }

    fun onCreateGroupNameChange(value: String) = _uiState.update { it.copy(createGroupName = value) }

    fun toggleCreateGroupMember(contactId: String) {
        _uiState.update { state ->
            val next = state.createGroupMemberIds.toMutableSet()
            if (!next.add(contactId)) next.remove(contactId)
            state.copy(createGroupMemberIds = next)
        }
    }

    fun unverifiedCreateGroupMembers(): List<Contact> {
        val ids = _uiState.value.createGroupMemberIds
        return _uiState.value.contacts.filter { it.id in ids && !it.verified }
    }

    fun findContact(id: String): Contact? = identityStore.findContact(id)

    fun confirmCreateGroup(): Boolean {
        val name = _uiState.value.createGroupName
        return try {
            val group = identityStore.createGroup(name, _uiState.value.createGroupMemberIds.toList())
            _uiState.value.identity?.let { hub.registerGroupConversation(group) }
            refreshGroups()
            _uiState.update {
                it.copy(
                    showCreateGroup = false,
                    createGroupName = "",
                    createGroupMemberIds = emptySet(),
                    inviteGroup = group,
                    selectedTab = HomeTab.Groups,
                    error = null,
                )
            }
            true
        } catch (e: Exception) {
            _uiState.update { it.copy(error = e.message ?: appStr(R.string.error_create_group)) }
            false
        }
    }

    fun closeGroupInvite() = _uiState.update { it.copy(inviteGroup = null) }

    fun showGroupInvite(group: ChatGroup) = _uiState.update { it.copy(inviteGroup = group) }

    fun addFromPaste(): PasteResult {
        val raw = _uiState.value.pasteText
        GroupExchange.parse(raw)?.let { payload ->
            return try {
                val session = joinGroupAndCreateSession(payload)
                _uiState.update {
                    it.copy(
                        showPasteDialog = false,
                        pasteText = "",
                        selectedTab = HomeTab.Groups,
                        error = null,
                    )
                }
                PasteResult.GroupJoined(session)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message ?: appStr(R.string.error_join_group)) }
                PasteResult.Failed
            }
        }
        val contactPayload = ContactExchange.parse(raw) ?: run {
            _uiState.update { it.copy(error = appStr(R.string.error_parse_invite)) }
            return PasteResult.Failed
        }
        return if (addContactPayload(contactPayload)) {
            PasteResult.ContactAdded
        } else {
            PasteResult.Failed
        }
    }

    fun handleScan(raw: String): ScanHandleResult {
        if (_uiState.value.identity == null) {
            return ScanHandleResult.Error(appStr(R.string.error_not_ready))
        }
        GroupExchange.parse(raw)?.let { payload ->
            return try {
                val session = joinGroupAndCreateSession(payload)
                _uiState.update { it.copy(selectedTab = HomeTab.Groups, error = null) }
                ScanHandleResult.GroupJoined(session)
            } catch (e: Exception) {
                val msg = e.message ?: appStr(R.string.error_join_group)
                _uiState.update { it.copy(error = msg) }
                ScanHandleResult.Error(msg)
            }
        }
        val contactPayload = ContactExchange.parse(raw)
            ?: run {
                val msg = appStr(R.string.error_scan_unrecognized)
                _uiState.update { it.copy(error = msg) }
                return ScanHandleResult.Error(msg)
            }
        return if (addContactPayload(contactPayload)) {
            ScanHandleResult.ContactAdded
        } else {
            ScanHandleResult.Error(_uiState.value.error ?: appStr(R.string.error_add_failed))
        }
    }

    private fun addContactPayload(payload: ContactExchange.Payload): Boolean {
        return try {
            val identity = identityStore.getOrCreateIdentity()
            val contact = identityStore.addContactFromPayload(payload)
            hub.registerContactConversation(identity, contact)
            refreshContacts()
            _uiState.update { it.copy(showPasteDialog = false, pasteText = "", error = null) }
            promptNicknameDialog(contact)
            true
        } catch (e: Exception) {
            _uiState.update { it.copy(error = e.message ?: appStr(R.string.error_add_failed)) }
            false
        }
    }

    fun markContactVerified(contactId: String) {
        identityStore.markContactVerified(contactId)
        refreshContacts()
        AppSnackbarBus.show(appStr(R.string.snackbar_contact_verified))
    }

    fun deleteContact(id: String) {
        val identity = _uiState.value.identity
        identityStore.findContact(id)?.let { contact ->
            identity?.let { hub.unregisterContactConversation(it, contact) }
        }
        identityStore.deleteContact(id)
        refreshContacts()
    }

    fun deleteGroup(id: String) {
        identityStore.getGroups().find { it.id == id }?.let { group ->
            hub.unregisterGroupConversation(group)
        }
        identityStore.deleteGroup(id)
        refreshGroups()
    }

    fun trustPendingTlsPin() {
        val pin = _uiState.value.pendingTlsPin ?: return
        val url = _uiState.value.serverUrl
        RelayHttpClient.trustNewPin(getApplication(), url, pin)
        prefs.setServerTested(true)
        prefs.setServerUrl(ServerUrl.normalize(url))
        _uiState.update {
            withSetupFlags(
                it.copy(
                    pendingTlsPin = null,
                    tlsPinned = true,
                    serverCheckOk = true,
                    error = null,
                ),
            )
        }
        AppSnackbarBus.show(appStr(R.string.snackbar_tls_trusted))
    }

    fun dismissPendingTlsPin() {
        _uiState.update { it.copy(pendingTlsPin = null, error = null) }
    }

    fun showAccountBackup(show: Boolean) = _uiState.update {
        it.copy(
            showAccountBackupDialog = show,
            accountBackupPassphrase = if (show) it.accountBackupPassphrase else "",
            error = if (show) null else it.error,
        )
    }

    fun onAccountPassphraseChange(value: String) = _uiState.update { it.copy(accountBackupPassphrase = value) }

    fun prepareAccountExport(): Boolean = validateBackupPassphrase(_uiState.value.accountBackupPassphrase)

    fun exportAccountBackupToUri(uri: Uri, passphrase: String): Boolean {
        val blob = buildAccountExportBlob(passphrase) ?: return false
        val ok = AccountBackupFiles.write(getApplication(), uri, blob)
        if (!ok) {
            _uiState.update { it.copy(error = appStr(R.string.error_account_backup_write)) }
            return false
        }
        _uiState.update {
            it.copy(
                showAccountBackupDialog = false,
                accountBackupPassphrase = "",
            )
        }
        AppSnackbarBus.show(appStr(R.string.snackbar_account_backup_exported))
        return true
    }

    fun importAccountBackupFromUri(uri: Uri, passphrase: String): Boolean {
        if (!validateBackupPassphrase(passphrase)) return false
        val blob = AccountBackupFiles.read(getApplication(), uri)
        if (blob == null) {
            _uiState.update { it.copy(error = appStr(R.string.error_account_backup_read)) }
            return false
        }
        return beginAccountImport(blob, passphrase)
    }

    fun dismissAccountImportOverwrite() {
        pendingAccountImportBlob = null
        _uiState.update { it.copy(showAccountImportOverwriteDialog = false) }
    }

    fun confirmAccountImportOverwrite() {
        val blob = pendingAccountImportBlob ?: return
        val pass = _uiState.value.accountBackupPassphrase
        pendingAccountImportBlob = null
        _uiState.update { it.copy(showAccountImportOverwriteDialog = false) }
        applyAccountImport(blob, pass)
    }

    fun showRatchetBackup(show: Boolean) = _uiState.update {
        it.copy(
            showRatchetBackupDialog = show,
            ratchetBackupPassphrase = if (show) it.ratchetBackupPassphrase else "",
            error = if (show) null else it.error,
        )
    }

    fun setRatchetAdvanced(show: Boolean) = _uiState.update { it.copy(showRatchetAdvanced = show) }

    fun onRatchetPassphraseChange(value: String) = _uiState.update { it.copy(ratchetBackupPassphrase = value) }

    fun prepareRatchetExport(): Boolean = validateBackupPassphrase(_uiState.value.ratchetBackupPassphrase)

    private fun validateBackupPassphrase(pass: String): Boolean {
        if (pass.length < 8) {
            _uiState.update { it.copy(error = appStr(R.string.error_backup_passphrase)) }
            return false
        }
        return true
    }

    private fun shouldConfirmAccountOverwrite(): Boolean {
        if (identityStore.hasAccountData()) return true
        if (prefs.getServerUrl() != null) return true
        return MessageCipher(getApplication()).exportAllRatchetsJson().length() > 0
    }

    private fun buildAccountExportPayload(): org.json.JSONObject {
        val server = _uiState.value.serverUrl.takeIf { it.isNotBlank() }
            ?: prefs.getServerUrl()
            .orEmpty()
        return org.json.JSONObject().apply {
            put("identity", identityStore.exportSnapshotJson())
            put("ratchets", MessageCipher(getApplication()).exportAllRatchetsJson())
            if (server.isNotBlank()) put("serverUrl", ServerUrl.normalize(server))
            put("tlsPins", TlsPinStore.exportAll(getApplication()))
        }
    }

    private fun buildAccountExportBlob(pass: String): String? {
        if (!validateBackupPassphrase(pass)) return null
        return AccountBackup.encryptExport(pass.toCharArray(), buildAccountExportPayload())
    }

    private fun beginAccountImport(blob: String, passphrase: String): Boolean {
        return try {
            AccountBackup.decryptImport(passphrase.toCharArray(), blob)
            if (shouldConfirmAccountOverwrite()) {
                pendingAccountImportBlob = blob
                _uiState.update { it.copy(showAccountImportOverwriteDialog = true) }
                true
            } else {
                applyAccountImport(blob, passphrase)
                true
            }
        } catch (e: Exception) {
            _uiState.update {
                it.copy(error = appStr(R.string.error_account_backup_restore, e.message ?: appStr(R.string.error_unknown)))
            }
            false
        }
    }

    private fun applyAccountImport(blob: String, passphrase: String) {
        try {
            val payload = AccountBackup.decryptImport(passphrase.toCharArray(), blob)
            identityStore.importSnapshotJson(payload.getJSONObject("identity"))
            MessageCipher(getApplication()).importAllRatchetsJson(payload.getJSONObject("ratchets"))
            payload.optString("serverUrl").takeIf { it.isNotBlank() }?.let { url ->
                val normalized = ServerUrl.normalize(url)
                prefs.setServerUrl(normalized)
                prefs.setServerTested(true)
                _uiState.update { it.copy(serverUrl = normalized) }
            }
            if (payload.has("tlsPins")) {
                TlsPinStore.importAll(getApplication(), payload.getJSONObject("tlsPins"))
            }
            if (!isSetupIncomplete(identityStore.getContacts())) {
                prefs.setOnboardingDismissed(true)
            }
            reloadAccountUi()
            _uiState.update {
                it.copy(
                    showAccountBackupDialog = false,
                    accountBackupPassphrase = "",
                    tlsPinned = RelayHttpClient.hasPin(getApplication(), _uiState.value.serverUrl),
                )
            }
            AppSnackbarBus.show(appStr(R.string.snackbar_account_backup_restored))
        } catch (e: Exception) {
            _uiState.update {
                it.copy(error = appStr(R.string.error_account_backup_restore, e.message ?: appStr(R.string.error_unknown)))
            }
        }
    }

    private suspend fun reloadAccountUi() {
        hub.backfillConversationsFromMessages()
        val identity = identityStore.getOrCreateIdentity()
        val server = prefs.getServerUrl().orEmpty()
        val contacts = identityStore.getContacts()
        val qr = ContactExchange.encodePayload(identity.publicKeyBase64, null)
        val groups = identityStore.getGroups()
        hub.syncConversationPeers(identity, contacts, groups)
        _uiState.update {
            withSetupFlags(
                it.copy(
                    identity = identity,
                    serverUrl = server,
                    contacts = contacts,
                    groups = groups,
                    myQrPayload = qr,
                    conversations = mapConversationRows(contacts, groups),
                    serverCheckOk = if (prefs.isServerTested()) true else null,
                ),
            )
        }
    }

    fun skipOnboarding() {
        prefs.setOnboardingDismissed(true)
        _uiState.update {
            withSetupFlags(it.copy(showOnboarding = false))
        }
    }

    fun reopenOnboarding() {
        _uiState.update {
            it.copy(
                showOnboarding = true,
                onboardingStep = initialOnboardingStep(),
                error = null,
            )
        }
    }

    fun advanceOnboardingStep() {
        val next = when (_uiState.value.onboardingStep) {
            OnboardingStep.Server -> OnboardingStep.Identity
            OnboardingStep.Identity -> OnboardingStep.AddContact
            OnboardingStep.AddContact -> return
        }
        _uiState.update { it.copy(onboardingStep = next, error = null) }
    }

    fun finishOnboardingFromAddContact() {
        prefs.setOnboardingDismissed(true)
        _uiState.update {
            withSetupFlags(it.copy(showOnboarding = false))
        }
    }

    private fun maybeFinishOnboardingAfterContact() {
        if (_uiState.value.showOnboarding && _uiState.value.contacts.isNotEmpty()) {
            finishOnboardingFromAddContact()
        } else if (_uiState.value.contacts.isNotEmpty()) {
            _uiState.update { withSetupFlags(it) }
        }
    }

    fun resolveServerUrlForSession(): String {
        val saved = prefs.getServerUrl()?.takeIf { it.isNotBlank() }
        if (saved != null) return ServerUrl.normalize(saved)
        val draft = _uiState.value.serverUrl.takeIf { it.isNotBlank() }
        if (draft != null) return ServerUrl.normalize(draft)
        return ""
    }

    private fun buildRatchetExportBlob(pass: String): String? {
        if (!validateRatchetPassphrase(pass)) return null
        val cipher = MessageCipher(getApplication())
        return RatchetBackup.encryptExport(pass.toCharArray(), cipher.exportAllRatchetsJson())
    }

    fun exportRatchetBackupToUri(uri: Uri, passphrase: String): Boolean {
        val blob = buildRatchetExportBlob(passphrase) ?: return false
        val ok = RatchetBackupFiles.write(getApplication(), uri, blob)
        if (!ok) {
            _uiState.update { it.copy(error = appStr(R.string.error_ratchet_write)) }
            return false
        }
        _uiState.update {
            it.copy(
                showRatchetBackupDialog = false,
                ratchetBackupPassphrase = "",
            )
        }
        AppSnackbarBus.show(appStr(R.string.snackbar_ratchet_exported))
        return true
    }

    fun importRatchetBackupFromUri(uri: Uri, passphrase: String): Boolean {
        if (!validateRatchetPassphrase(passphrase)) return false
        val blob = RatchetBackupFiles.read(getApplication(), uri)
        if (blob == null) {
            _uiState.update { it.copy(error = appStr(R.string.error_ratchet_read)) }
            return false
        }
        return importRatchetBackup(blob)
    }

    fun exportRatchetBackupToClipboard(): Boolean {
        val pass = _uiState.value.ratchetBackupPassphrase
        val blob = buildRatchetExportBlob(pass) ?: return false
        val cm = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("zerorelay-ratchet-backup", blob))
        _uiState.update {
            it.copy(
                showRatchetBackupDialog = false,
                ratchetBackupPassphrase = "",
            )
        }
        AppSnackbarBus.show(appStr(R.string.snackbar_ratchet_clipboard))
        return true
    }

    fun importRatchetBackupFromClipboard(): Boolean {
        val cm = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val blob = cm.primaryClip?.getItemAt(0)?.text?.toString()?.trim().orEmpty()
        if (blob.isEmpty()) {
            _uiState.update { it.copy(error = appStr(R.string.error_ratchet_clipboard_empty)) }
            return false
        }
        return importRatchetBackup(blob)
    }

    fun importRatchetBackup(blob: String): Boolean {
        val pass = _uiState.value.ratchetBackupPassphrase
        if (!validateBackupPassphrase(pass)) return false
        return try {
            val json = RatchetBackup.decryptImport(pass.toCharArray(), blob.trim())
            MessageCipher(getApplication()).importAllRatchetsJson(json)
            _uiState.update {
                it.copy(
                    showRatchetBackupDialog = false,
                    ratchetBackupPassphrase = "",
                )
            }
            AppSnackbarBus.show(appStr(R.string.snackbar_ratchet_restored))
            true
        } catch (e: Exception) {
            _uiState.update {
                it.copy(error = appStr(R.string.error_ratchet_restore, e.message ?: appStr(R.string.error_unknown)))
            }
            false
        }
    }

    fun rotateGroupKey(groupId: String) {
        try {
            val group = identityStore.rotateGroupKey(groupId)
            refreshGroups()
            _uiState.update { it.copy(inviteGroup = group) }
            AppSnackbarBus.show(appStr(R.string.snackbar_group_key_rotated))
        } catch (e: Exception) {
            _uiState.update { it.copy(error = e.message ?: appStr(R.string.error_rotate_key)) }
        }
    }

    fun findChatSessionForRoom(roomId: String): ChatSession? {
        val hub = RelayMessagingHub.get(getApplication())
        hub.sessionForRoom(roomId)?.let { return it }
        val identity = _uiState.value.identity ?: return null
        val server = resolveServerUrlForSession()
        if (server.isEmpty()) return null
        for (contact in _uiState.value.contacts) {
            SessionFactory.create(server, identity, contact)
                ?.takeIf { it.roomId == roomId }
                ?.let { return it }
        }
        for (group in _uiState.value.groups) {
            SessionFactory.createForGroup(server, identity, group)
                ?.takeIf { it.roomId == roomId }
                ?.let { return it }
        }
        return null
    }

    fun createSession(contact: Contact): ChatSession? {
        val identity = _uiState.value.identity ?: return null
        if (!ensureServerReadyForChat()) return null
        saveServerUrl()
        return SessionFactory.create(resolveServerUrlForSession(), identity, contact)
    }

    fun createGroupSession(group: ChatGroup): ChatSession? {
        if (group.isInviteExpired()) {
            _uiState.update { it.copy(error = appStr(R.string.error_group_expired)) }
            return null
        }
        val identity = _uiState.value.identity ?: return null
        if (!ensureServerReadyForChat()) return null
        saveServerUrl()
        return SessionFactory.createForGroup(resolveServerUrlForSession(), identity, group)
    }

    fun groupInvitePayload(group: ChatGroup): String =
        GroupExchange.encodeInvite(group, resolveServerUrlForSession().ifBlank { _uiState.value.serverUrl })

    private fun ensureServerReadyForChat(): Boolean {
        val url = resolveServerUrlForSession()
        if (url.isNotEmpty()) return true
        _uiState.update { it.copy(error = appStr(R.string.error_server_required)) }
        return false
    }

    private fun joinGroupAndCreateSession(payload: GroupExchange.InvitePayload): ChatSession {
        applyServerFromInvite(payload.serverUrl)
        val group = identityStore.joinGroupFromInvite(payload)
        val identity = _uiState.value.identity ?: error(appStr(R.string.error_identity_not_ready))
        hub.registerGroupConversation(group)
        refreshGroups()
        return SessionFactory.createForGroup(_uiState.value.serverUrl, identity, group)
    }

    private fun applyServerFromInvite(serverUrl: String?) {
        val relay = serverUrl?.let { ServerUrl.normalize(it) }?.takeIf { it.isNotEmpty() } ?: return
        prefs.setServerUrl(relay)
        _uiState.update { it.copy(serverUrl = relay) }
    }

    fun clearError() = _uiState.update { it.copy(error = null) }

    fun setUseDynamicColor(enabled: Boolean) {
        prefs.setUseDynamicColor(enabled)
        _uiState.update { it.copy(useDynamicColor = enabled) }
    }

    fun setAllowScreenshots(allow: Boolean) {
        prefs.setAllowScreenshots(allow)
        _uiState.update { it.copy(allowScreenshots = allow) }
    }

    fun clearAllLocalMessages() {
        viewModelScope.launch {
            hub.clearAllPersistedMessages()
            AppSnackbarBus.show(appStr(R.string.snackbar_messages_cleared))
        }
    }

    fun setKeepAliveInBackground(enabled: Boolean) {
        prefs.setKeepAliveInBackground(enabled)
        _uiState.update { it.copy(keepAliveInBackground = enabled) }
        val hub = RelayMessagingHub.get(getApplication())
        if (!enabled) {
            hub.syncForegroundService()
            if (hub.detachedSessionsList.isNotEmpty()) {
                AppSnackbarBus.show(appStr(R.string.settings_keep_alive_disabled_hint))
            }
        } else {
            hub.syncForegroundService()
        }
    }

    fun refreshBatteryOptimizationStatus() {
        _uiState.update {
            it.copy(
                batteryOptimizationIgnored = BatteryOptimizationHelper.isIgnoringOptimizations(getApplication()),
            )
        }
    }

    fun detachedSessionForChat(): ChatSession? = hub.detachedSession

    fun setMaxBackgroundSessions(count: Int) {
        prefs.setMaxBackgroundSessions(count)
        hub.trimDetachedToCap()
        _uiState.update { it.copy(maxBackgroundSessions = prefs.getMaxBackgroundSessions()) }
        refreshHomeConnectionUi()
    }

    fun retryRelayStatusBar() {
        viewModelScope.launch {
            val detached = hub.detachedSessionsList
            if (detached.isNotEmpty()) {
                val identity = _uiState.value.identity ?: return@launch
                for (session in detached) {
                    joinChatSession(session, identity)
                }
                refreshHomeConnectionUi()
            } else {
                testServerConnection()
            }
        }
    }

    private fun refreshHomeConnectionUi() {
        val detached = hub.detachedSessionsList
        val count = detached.size
        _uiState.update {
            withSetupFlags(
                it.copy(
                    detachedSessionCount = count,
                    detachedChatName = detached.singleOrNull()?.peerDisplayName,
                    detachedConnection = if (count > 0) {
                        hub.aggregatedDetachedConnection()
                    } else {
                        ConnectionState.Disconnected
                    },
                ),
            )
        }
    }

    private suspend fun restoreDetachedSessionsIfNeeded(identity: Identity) {
        if (hub.activeSession != null) return
        val records = DetachedSessionStore(getApplication()).loadAll()
        if (records.isEmpty()) return
        var failedAny = false
        val restored = mutableListOf<ChatSession>()
        for (record in records) {
            val session = sessionFromRecord(record, identity)
            if (session == null) {
                failedAny = true
                continue
            }
            val repo = hub.getOrCreateRepository(session.roomId)
            if (!repo.isInRoom(session.roomId)) {
                if (!joinChatSession(session, identity)) {
                    hub.leaveRoom(session.roomId)
                    failedAny = true
                    continue
                }
            }
            restored += session
            hub.registerConversation(session)
        }
        if (restored.isEmpty()) {
            DetachedSessionStore(getApplication()).clear()
            if (failedAny) {
                AppSnackbarBus.show(appStr(R.string.error_restore_detached_session_failed))
            }
        } else {
            hub.restoreDetachedSessions(restored)
            if (failedAny) {
                AppSnackbarBus.show(appStr(R.string.error_restore_detached_session_partial))
            }
        }
        refreshHomeConnectionUi()
    }

    private fun sessionFromRecord(
        record: DetachedSessionStore.DetachedSessionRecord,
        identity: Identity,
    ): ChatSession? {
        val session = when (record.kind) {
            ChatKind.Direct -> {
                val contact = identityStore.findContact(record.peerContactId) ?: return null
                SessionFactory.create(record.serverUrl, identity, contact)
            }
            ChatKind.Group -> {
                val group = identityStore.findGroup(record.peerContactId) ?: return null
                SessionFactory.createForGroup(record.serverUrl, identity, group)
            }
        }
        return session.takeIf { it.roomId == record.roomId }
    }

    private suspend fun joinChatSession(session: ChatSession, identity: Identity): Boolean {
        val senderId = IdentityCrypto.senderIdFromPublicKey(identity.publicKey)
        return hub.getOrCreateRepository(session.roomId).joinRoom(
            serverUrl = session.serverUrl,
            room = session.roomId,
            sessionKey = session.sessionKey,
            sender = senderId,
            contactId = session.peerContactId,
            kind = session.kind,
            groupKeys = session.groupKeysByVersion,
            groupKeyVer = session.groupKeyVersion,
            localPublicKey = if (session.kind == ChatKind.Direct) identity.publicKey else null,
            peerPublicKey = session.peerPublicKeyBase64?.let(IdentityCrypto::decodePublicKey),
            identityPrivateKey = identity.privateKey,
            identityPublicKey = identity.publicKey,
        )
    }
}
