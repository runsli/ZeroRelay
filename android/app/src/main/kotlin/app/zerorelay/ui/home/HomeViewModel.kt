package app.zerorelay.ui.home

import android.app.Application
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.zerorelay.R
import app.zerorelay.data.chat.RelayMessagingHub
import app.zerorelay.data.chat.RelaySessionCoordinator
import app.zerorelay.data.crypto.ContactExchange
import app.zerorelay.data.identity.IdentityStore
import app.zerorelay.data.local.ConversationEntity
import app.zerorelay.data.local.UserPreferences
import app.zerorelay.data.model.ChatGroup
import app.zerorelay.data.model.ChatSession
import app.zerorelay.data.model.Contact
import app.zerorelay.data.network.RelayHttpClient
import app.zerorelay.ui.error.UserError
import app.zerorelay.ui.error.UserErrorKind
import app.zerorelay.ui.snackbar.AppSnackbarBus
import app.zerorelay.ui.util.BatteryOptimizationHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val identityStore = IdentityStore(application)
    private val prefs = UserPreferences(application)
    private val hub = RelayMessagingHub.get(application)

    private fun appStr(@StringRes resId: Int, vararg args: Any): String =
        getApplication<Application>().getString(resId, *args)

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
    private var conversationEntities: List<ConversationEntity> = emptyList()

    private fun updateState(block: (HomeUiState) -> HomeUiState) = _uiState.update(block)

    private fun withSetupFlags(state: HomeUiState): HomeUiState =
        HomeStateLogic.withSetupFlags(prefs, state)

    private fun setUserError(error: UserError) = updateState { it.copy(userError = error) }

    private fun setUserError(kind: UserErrorKind, detail: String? = null) {
        setUserError(UserError(kind, detail))
    }

    private val migrationActions = MigrationActions(::updateState)

    private val relaySettings = RelaySettingsActions(
        app = application,
        prefs = prefs,
        scope = viewModelScope,
        getState = { _uiState.value },
        updateState = ::updateState,
        withSetupFlags = ::withSetupFlags,
        setUserError = ::setUserError,
        appStr = { res, args -> appStr(res, *args) },
    )

    private val detachedSessions = DetachedSessionActions(
        app = application,
        identityStore = identityStore,
        hub = hub,
        scope = viewModelScope,
        getState = { _uiState.value },
        updateState = ::updateState,
        withSetupFlags = ::withSetupFlags,
        appStr = { res, args -> appStr(res, *args) },
        testServerConnection = relaySettings::testServerConnection,
    )

    private val onboarding = OnboardingActions(
        prefs = prefs,
        getState = { _uiState.value },
        updateState = ::updateState,
        withSetupFlags = ::withSetupFlags,
    )

    private val settings = HomeSettingsActions(
        app = application,
        prefs = prefs,
        hub = hub,
        scope = viewModelScope,
        updateState = ::updateState,
        appStr = { res, args -> appStr(res, *args) },
        refreshHomeConnectionUi = detachedSessions::refreshHomeConnectionUi,
    )

    private val backupActions = BackupActions(
        app = application,
        identityStore = identityStore,
        prefs = prefs,
        scope = viewModelScope,
        getState = { _uiState.value },
        updateState = ::updateState,
        setUserError = ::setUserError,
        setUserErrorKind = ::setUserError,
        appStr = { res, args -> appStr(res, *args) },
        reloadAccountUi = ::reloadAccountUi,
        migrationActions = migrationActions,
        isSetupIncomplete = { contacts ->
            HomeStateLogic.isSetupIncomplete(prefs, contacts)
        },
    )

    private val contactGroup = ContactGroupActions(
        app = application,
        identityStore = identityStore,
        prefs = prefs,
        hub = hub,
        getState = { _uiState.value },
        updateState = ::updateState,
        withSetupFlags = ::withSetupFlags,
        setUserError = ::setUserError,
        setUserErrorKind = ::setUserError,
        appStr = { res, args -> appStr(res, *args) },
        getConversationEntities = { conversationEntities },
        resolveServerUrlForSession = relaySettings::resolveServerUrlForSession,
        saveServerUrl = relaySettings::saveServerUrl,
        refreshHomeConnectionUi = detachedSessions::refreshHomeConnectionUi,
        maybeFinishOnboardingAfterContact = onboarding::maybeFinishOnboardingAfterContact,
    )

    init {
        RelaySessionCoordinator.start(application)
        viewModelScope.launch {
            hub.conversationStore.observeAll().collect { entities ->
                conversationEntities = entities
                updateState { state ->
                    state.copy(
                        conversations = entities.map {
                            it.toRowUi(state.contacts, state.groups)
                        },
                    )
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
            val showOnboarding = !prefs.isOnboardingDismissed() &&
                HomeStateLogic.isSetupIncomplete(prefs, contacts)
            hub.syncConversationPeers(identity, contacts, groups)
            updateState {
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
                        tlsPinned = server.isNotBlank() && RelayHttpClient.hasPin(application, server),
                        batteryOptimizationIgnored = BatteryOptimizationHelper.isIgnoringOptimizations(application),
                        showOnboarding = showOnboarding,
                        onboardingStep = if (showOnboarding) {
                            HomeStateLogic.initialOnboardingStep(prefs)
                        } else {
                            OnboardingStep.Server
                        },
                        serverCheckOk = if (prefs.isServerTested()) true else null,
                    ),
                )
            }
            detachedSessions.restoreDetachedSessionsIfNeeded(identity)
            detachedSessions.refreshHomeConnectionUi()
        }
        viewModelScope.launch {
            hub.detachedSessionsFlow.collect { detachedSessions.refreshHomeConnectionUi() }
        }
        viewModelScope.launch {
            hub.aggregatedDetachedConnectionFlow.collect { detachedSessions.refreshHomeConnectionUi() }
        }
        viewModelScope.launch {
            hub.detachedEvictionEvents.collect { event ->
                AppSnackbarBus.show(appStr(R.string.snackbar_detached_evicted, event.displayName))
                detachedSessions.refreshHomeConnectionUi()
            }
        }
    }

    fun onServerUrlChange(value: String) = relaySettings.onServerUrlChange(value)
    fun saveServerUrl() = relaySettings.saveServerUrl()
    fun testServerConnection() = relaySettings.testServerConnection()
    fun trustPendingTlsPin() = relaySettings.trustPendingTlsPin()
    fun dismissPendingTlsPin() = relaySettings.dismissPendingTlsPin()
    fun resolveServerUrlForSession(): String = relaySettings.resolveServerUrlForSession()

    fun selectTab(tab: HomeTab) = updateState { it.copy(selectedTab = tab, userError = null) }
    fun setSearchActive(active: Boolean) = updateState { it.copy(searchActive = active) }
    fun onSearchQueryChange(query: String) = updateState { it.copy(searchQuery = query) }
    fun clearSearch() = updateState { it.copy(searchQuery = "", searchActive = false) }

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

    fun openEditNickname(contact: Contact) = contactGroup.openEditNickname(contact)
    fun onNicknameDraftChange(value: String) = contactGroup.onNicknameDraftChange(value)
    fun skipNicknameDialog() = contactGroup.skipNicknameDialog()
    fun confirmNicknameDialog() = contactGroup.confirmNicknameDialog()
    fun refreshContacts() = contactGroup.refreshContacts()
    fun refreshGroups() = contactGroup.refreshGroups()
    fun openConversation(roomId: String): ChatSession? = contactGroup.openConversation(roomId)
    fun contactForConversation(row: ConversationRowUi): Contact? = contactGroup.contactForConversation(row)
    fun openMyQr() = contactGroup.openMyQr()
    fun closeMyQr() = contactGroup.closeMyQr()
    fun openPasteDialog() = contactGroup.openPasteDialog()
    fun closePasteDialog() = contactGroup.closePasteDialog()
    fun onPasteTextChange(value: String) = contactGroup.onPasteTextChange(value)
    fun openCreateGroup() = contactGroup.openCreateGroup()
    fun closeCreateGroup() = contactGroup.closeCreateGroup()
    fun onCreateGroupNameChange(value: String) = contactGroup.onCreateGroupNameChange(value)
    fun toggleCreateGroupMember(contactId: String) = contactGroup.toggleCreateGroupMember(contactId)
    fun unverifiedCreateGroupMembers(): List<Contact> = contactGroup.unverifiedCreateGroupMembers()
    fun findContact(id: String): Contact? = contactGroup.findContact(id)
    fun confirmCreateGroup(): Boolean = contactGroup.confirmCreateGroup()
    fun closeGroupInvite() = contactGroup.closeGroupInvite()
    fun showGroupInvite(group: ChatGroup) = contactGroup.showGroupInvite(group)
    fun showGroupInviteForRoom(roomId: String) = contactGroup.showGroupInviteForRoom(roomId)
    fun addFromPaste(): PasteResult = contactGroup.addFromPaste()
    fun handleScan(raw: String): ScanHandleResult = contactGroup.handleScan(raw)
    fun markContactVerified(contactId: String) = contactGroup.markContactVerified(contactId)
    fun deleteContact(id: String) = contactGroup.deleteContact(id)
    fun deleteGroup(id: String) = contactGroup.deleteGroup(id)
    fun rotateGroupKey(groupId: String) = contactGroup.rotateGroupKey(groupId)
    fun findChatSessionForRoom(roomId: String): ChatSession? = contactGroup.findChatSessionForRoom(roomId)
    fun createSession(contact: Contact): ChatSession? = contactGroup.createSession(contact)
    fun createGroupSession(group: ChatGroup): ChatSession? = contactGroup.createGroupSession(group)
    fun groupInvitePayload(group: ChatGroup): String = contactGroup.groupInvitePayload(group)

    fun showMigrationGuide(show: Boolean) = migrationActions.showMigrationGuide(show)
    fun dismissMigrationImportChecklist() = migrationActions.dismissMigrationImportChecklist()

    fun showAccountBackup(show: Boolean) = backupActions.showAccountBackup(show)
    fun onAccountPassphraseChange(value: String) = backupActions.onAccountPassphraseChange(value)
    fun prepareAccountExport(): Boolean = backupActions.prepareAccountExport()
    fun exportAccountBackupToUri(uri: Uri, passphrase: String): Boolean =
        backupActions.exportAccountBackupToUri(uri, passphrase)
    fun importAccountBackupFromUri(uri: Uri, passphrase: String): Boolean =
        backupActions.importAccountBackupFromUri(uri, passphrase)
    fun dismissAccountImportOverwrite() = backupActions.dismissAccountImportOverwrite()
    fun confirmAccountImportOverwrite() = backupActions.confirmAccountImportOverwrite()
    fun showRatchetBackup(show: Boolean) = backupActions.showRatchetBackup(show)
    fun setRatchetAdvanced(show: Boolean) = backupActions.setRatchetAdvanced(show)
    fun onRatchetPassphraseChange(value: String) = backupActions.onRatchetPassphraseChange(value)
    fun prepareRatchetExport(): Boolean = backupActions.prepareRatchetExport()
    fun exportRatchetBackupToUri(uri: Uri, passphrase: String): Boolean =
        backupActions.exportRatchetBackupToUri(uri, passphrase)
    fun importRatchetBackupFromUri(uri: Uri, passphrase: String): Boolean =
        backupActions.importRatchetBackupFromUri(uri, passphrase)
    fun exportRatchetBackupToClipboard(): Boolean = backupActions.exportRatchetBackupToClipboard()
    fun importRatchetBackupFromClipboard(): Boolean = backupActions.importRatchetBackupFromClipboard()
    fun importRatchetBackup(blob: String): Boolean = backupActions.importRatchetBackup(blob)

    fun skipOnboarding() = onboarding.skipOnboarding()
    fun reopenOnboarding() = onboarding.reopenOnboarding()
    fun advanceOnboardingStep() = onboarding.advanceOnboardingStep()
    fun finishOnboardingFromAddContact() = onboarding.finishOnboardingFromAddContact()

    fun clearUserError() = updateState { it.copy(userError = null) }

    fun setUseDynamicColor(enabled: Boolean) = settings.setUseDynamicColor(enabled)
    fun setAllowScreenshots(allow: Boolean) = settings.setAllowScreenshots(allow)
    fun clearAllLocalMessages() = settings.clearAllLocalMessages()
    fun setKeepAliveInBackground(enabled: Boolean) = settings.setKeepAliveInBackground(enabled)
    fun refreshBatteryOptimizationStatus() = settings.refreshBatteryOptimizationStatus()
    fun detachedSessionForChat(): ChatSession? = hub.detachedSession
    fun setMaxBackgroundSessions(count: Int) = settings.setMaxBackgroundSessions(count)
    fun retryRelayStatusBar() = detachedSessions.retryRelayStatusBar()

    private suspend fun reloadAccountUi() {
        hub.backfillConversationsFromMessages()
        val identity = identityStore.getOrCreateIdentity()
        val server = prefs.getServerUrl().orEmpty()
        val contacts = identityStore.getContacts()
        val qr = ContactExchange.encodePayload(identity.publicKeyBase64, null)
        val groups = identityStore.getGroups()
        hub.syncConversationPeers(identity, contacts, groups)
        updateState {
            withSetupFlags(
                it.copy(
                    identity = identity,
                    serverUrl = server,
                    contacts = contacts,
                    groups = groups,
                    myQrPayload = qr,
                    conversations = conversationEntities.map { e -> e.toRowUi(contacts, groups) },
                    serverCheckOk = if (prefs.isServerTested()) true else null,
                ),
            )
        }
    }
}
