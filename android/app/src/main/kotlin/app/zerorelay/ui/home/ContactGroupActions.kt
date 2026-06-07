package app.zerorelay.ui.home

import android.app.Application
import app.zerorelay.R
import app.zerorelay.data.chat.RelayMessagingHub
import app.zerorelay.data.crypto.ContactExchange
import app.zerorelay.data.crypto.GroupExchange
import app.zerorelay.data.crypto.IdentityCrypto
import app.zerorelay.data.identity.IdentityStore
import app.zerorelay.data.local.ConversationEntity
import app.zerorelay.data.local.UserPreferences
import app.zerorelay.data.model.ChatGroup
import app.zerorelay.data.model.ChatSession
import app.zerorelay.data.model.Contact
import app.zerorelay.data.model.Identity
import app.zerorelay.data.network.ServerUrl
import app.zerorelay.data.session.SessionFactory
import app.zerorelay.ui.error.UserError
import app.zerorelay.ui.error.UserErrorKind
import app.zerorelay.ui.error.UserErrorMapping
import app.zerorelay.ui.snackbar.AppSnackbarBus

/**
 * Contacts, groups, invites, scan/paste, and chat session helpers.
 */
class ContactGroupActions(
    private val app: Application,
    private val identityStore: IdentityStore,
    private val prefs: UserPreferences,
    private val hub: RelayMessagingHub,
    private val getState: () -> HomeUiState,
    private val updateState: ((HomeUiState) -> HomeUiState) -> Unit,
    private val withSetupFlags: (HomeUiState) -> HomeUiState,
    private val setUserError: (UserError) -> Unit,
    private val setUserErrorKind: (UserErrorKind, String?) -> Unit,
    private val appStr: (Int, Array<out Any>) -> String,
    private val getConversationEntities: () -> List<ConversationEntity>,
    private val resolveServerUrlForSession: () -> String,
    private val saveServerUrl: () -> Unit,
    private val refreshHomeConnectionUi: () -> Unit,
    private val maybeFinishOnboardingAfterContact: () -> Unit,
) {
    fun openEditNickname(contact: Contact) = updateState {
        it.copy(
            nicknameDialogContact = contact,
            nicknameDraft = contact.displayName,
            nicknameIsEdit = true,
            userError = null,
        )
    }

    fun onNicknameDraftChange(value: String) = updateState { it.copy(nicknameDraft = value) }

    fun skipNicknameDialog() = updateState {
        it.copy(nicknameDialogContact = null, nicknameDraft = "", nicknameIsEdit = false)
    }

    fun confirmNicknameDialog() {
        val contact = getState().nicknameDialogContact ?: return
        saveContactNickname(contact.id, getState().nicknameDraft)
        skipNicknameDialog()
    }

    fun refreshContacts() {
        updateState { state ->
            val contacts = identityStore.getContacts()
            withSetupFlags(
                state.copy(
                    contacts = contacts,
                    userError = null,
                    conversations = mapConversationRows(contacts, state.groups),
                ),
            )
        }
        maybeFinishOnboardingAfterContact()
    }

    fun refreshGroups() {
        updateState { state ->
            val groups = identityStore.getGroups()
            state.copy(
                groups = groups,
                userError = null,
                conversations = mapConversationRows(state.contacts, groups),
            )
        }
    }

    fun openConversation(roomId: String): ChatSession? = findChatSessionForRoom(roomId)

    fun contactForConversation(row: ConversationRowUi): Contact? {
        if (row.isGroup) return null
        return getState().contacts.find { contact ->
            contact.id == getConversationEntities().find { it.roomId == row.roomId }?.peerContactId
        }
    }

    fun openMyQr() = updateState { it.copy(showMyQr = true) }

    fun closeMyQr() = updateState { it.copy(showMyQr = false) }

    fun openPasteDialog() = updateState { it.copy(showPasteDialog = true, pasteText = "", userError = null) }

    fun closePasteDialog() = updateState { it.copy(showPasteDialog = false, pasteText = "") }

    fun onPasteTextChange(value: String) = updateState { it.copy(pasteText = value) }

    fun openCreateGroup() = updateState {
        it.copy(
            showCreateGroup = true,
            createGroupName = "",
            createGroupMemberIds = emptySet(),
            userError = null,
        )
    }

    fun closeCreateGroup() = updateState {
        it.copy(showCreateGroup = false, createGroupName = "", createGroupMemberIds = emptySet())
    }

    fun onCreateGroupNameChange(value: String) = updateState { it.copy(createGroupName = value) }

    fun toggleCreateGroupMember(contactId: String) {
        updateState { state ->
            val next = state.createGroupMemberIds.toMutableSet()
            if (!next.add(contactId)) next.remove(contactId)
            state.copy(createGroupMemberIds = next)
        }
    }

    fun unverifiedCreateGroupMembers(): List<Contact> {
        val ids = getState().createGroupMemberIds
        return getState().contacts.filter { it.id in ids && !it.verified }
    }

    fun findContact(id: String): Contact? = identityStore.findContact(id)

    fun confirmCreateGroup(): Boolean {
        val name = getState().createGroupName
        return try {
            val group = identityStore.createGroup(name, getState().createGroupMemberIds.toList())
            getState().identity?.let { hub.registerGroupConversation(group) }
            refreshGroups()
            updateState {
                it.copy(
                    showCreateGroup = false,
                    createGroupName = "",
                    createGroupMemberIds = emptySet(),
                    inviteGroup = group,
                    selectedTab = HomeTab.Groups,
                    userError = null,
                )
            }
            true
        } catch (e: Exception) {
            setUserError(UserErrorMapping.fromThrowable(e))
            false
        }
    }

    fun closeGroupInvite() = updateState { it.copy(inviteGroup = null, inviteHighlightRotation = false) }

    fun showGroupInvite(group: ChatGroup) = updateState {
        it.copy(inviteGroup = group, inviteHighlightRotation = false)
    }

    fun showGroupInviteForRoom(roomId: String) {
        getState().groups.find { it.roomId == roomId }?.let { showGroupInvite(it) }
    }

    fun addFromPaste(): PasteResult {
        val raw = getState().pasteText
        GroupExchange.parse(raw)?.let { payload ->
            return try {
                val session = joinGroupAndCreateSession(payload)
                updateState {
                    it.copy(
                        showPasteDialog = false,
                        pasteText = "",
                        selectedTab = HomeTab.Groups,
                        userError = null,
                    )
                }
                PasteResult.GroupJoined(session)
            } catch (e: Exception) {
                setUserError(UserErrorMapping.fromThrowable(e))
                return PasteResult.Failed
            }
        }
        val contactPayload = ContactExchange.parse(raw) ?: run {
            setUserErrorKind(UserErrorKind.ParseInvite, null)
            return PasteResult.Failed
        }
        return if (addContactPayload(contactPayload)) {
            PasteResult.ContactAdded
        } else {
            PasteResult.Failed
        }
    }

    fun handleScan(raw: String): ScanHandleResult {
        if (getState().identity == null) {
            return ScanHandleResult.Error(UserError(UserErrorKind.NotReady))
        }
        GroupExchange.parse(raw)?.let { payload ->
            return try {
                val session = joinGroupAndCreateSession(payload)
                updateState { it.copy(selectedTab = HomeTab.Groups, userError = null) }
                ScanHandleResult.GroupJoined(session)
            } catch (e: Exception) {
                val err = UserErrorMapping.fromThrowable(e)
                updateState { it.copy(userError = err) }
                return ScanHandleResult.Error(err)
            }
        }
        val contactPayload = ContactExchange.parse(raw)
            ?: run {
                val err = UserError(UserErrorKind.ScanUnrecognized)
                updateState { it.copy(userError = err) }
                return ScanHandleResult.Error(err)
            }
        return if (addContactPayload(contactPayload)) {
            ScanHandleResult.ContactAdded
        } else {
            ScanHandleResult.Error(
                getState().userError ?: UserError(UserErrorKind.AddContactFailed),
            )
        }
    }

    fun markContactVerified(contactId: String) {
        identityStore.markContactVerified(contactId)
        refreshContacts()
        AppSnackbarBus.show(appStr(R.string.snackbar_contact_verified, emptyArray()))
    }

    fun deleteContact(id: String) {
        val identity = getState().identity
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

    fun rotateGroupKey(groupId: String) {
        try {
            val group = identityStore.rotateGroupKey(groupId)
            refreshGroups()
            updateState { it.copy(inviteGroup = group, inviteHighlightRotation = true) }
        } catch (e: Exception) {
            setUserError(UserErrorMapping.fromThrowable(e))
        }
    }

    fun findChatSessionForRoom(roomId: String): ChatSession? {
        hub.sessionForRoom(roomId)?.let { return it }
        val identity = getState().identity ?: return null
        val server = resolveServerUrlForSession()
        if (server.isEmpty()) return null
        for (contact in getState().contacts) {
            SessionFactory.create(server, identity, contact)
                ?.takeIf { it.roomId == roomId }
                ?.let { return it }
        }
        for (group in getState().groups) {
            SessionFactory.createForGroup(server, identity, group)
                ?.takeIf { it.roomId == roomId }
                ?.let { return it }
        }
        return null
    }

    fun createSession(contact: Contact): ChatSession? {
        val identity = getState().identity ?: return null
        if (!ensureServerReadyForChat()) return null
        saveServerUrl()
        return SessionFactory.create(resolveServerUrlForSession(), identity, contact)
    }

    fun createGroupSession(group: ChatGroup): ChatSession? {
        if (group.isInviteExpired()) {
            setUserErrorKind(UserErrorKind.GroupExpired, null)
            return null
        }
        val identity = getState().identity ?: return null
        if (!ensureServerReadyForChat()) return null
        saveServerUrl()
        return SessionFactory.createForGroup(resolveServerUrlForSession(), identity, group)
    }

    fun groupInvitePayload(group: ChatGroup): String =
        GroupExchange.encodeInvite(
            group,
            resolveServerUrlForSession().ifBlank { getState().serverUrl },
        )

    private fun mapConversationRows(
        contacts: List<Contact>,
        groups: List<ChatGroup>,
    ): List<ConversationRowUi> =
        getConversationEntities().map { it.toRowUi(contacts, groups) }

    private fun promptNicknameDialog(contact: Contact) {
        updateState {
            it.copy(
                nicknameDialogContact = contact,
                nicknameDraft = contact.displayName,
                nicknameIsEdit = false,
                userError = null,
            )
        }
    }

    private fun saveContactNickname(contactId: String, displayName: String) {
        val identity = getState().identity ?: return
        val updated = identityStore.updateContactDisplayName(contactId, displayName) ?: return
        hub.registerContactConversation(identity, updated)
        val peerKey = IdentityCrypto.decodePublicKey(updated.publicKeyBase64)
        val roomId = IdentityCrypto.deriveRoomId(identity.publicKey, peerKey)
        hub.updateSessionDisplayName(roomId, updated.displayName)
        refreshContacts()
        refreshHomeConnectionUi()
    }

    private fun addContactPayload(payload: ContactExchange.Payload): Boolean {
        return try {
            val identity = identityStore.getOrCreateIdentity()
            val contact = identityStore.addContactFromPayload(payload)
            hub.registerContactConversation(identity, contact)
            refreshContacts()
            updateState { it.copy(showPasteDialog = false, pasteText = "", userError = null) }
            promptNicknameDialog(contact)
            true
        } catch (e: Exception) {
            setUserError(UserErrorMapping.fromThrowable(e))
            false
        }
    }

    private fun ensureServerReadyForChat(): Boolean {
        val url = resolveServerUrlForSession()
        if (url.isNotEmpty()) return true
        setUserErrorKind(UserErrorKind.ServerRequired, null)
        return false
    }

    private fun joinGroupAndCreateSession(payload: GroupExchange.InvitePayload): ChatSession {
        applyServerFromInvite(payload.serverUrl)
        val group = identityStore.joinGroupFromInvite(payload)
        val identity = getState().identity ?: error(appStr(R.string.error_identity_not_ready, emptyArray()))
        hub.registerGroupConversation(group)
        refreshGroups()
        return SessionFactory.createForGroup(getState().serverUrl, identity, group)
    }

    private fun applyServerFromInvite(serverUrl: String?) {
        val relay = serverUrl?.let { ServerUrl.normalize(it) }?.takeIf { it.isNotEmpty() } ?: return
        prefs.setServerUrl(relay)
        updateState { it.copy(serverUrl = relay) }
    }
}
