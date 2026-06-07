package app.zerorelay.ui.home

import android.app.Application
import app.zerorelay.R
import app.zerorelay.data.chat.RelayMessagingHub
import app.zerorelay.data.crypto.IdentityCrypto
import app.zerorelay.data.identity.IdentityStore
import app.zerorelay.data.local.DetachedSessionStore
import app.zerorelay.data.model.ChatKind
import app.zerorelay.data.model.ChatSession
import app.zerorelay.data.model.ConnectionState
import app.zerorelay.data.model.Identity
import app.zerorelay.data.session.SessionFactory
import app.zerorelay.ui.snackbar.AppSnackbarBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Detached background session restore and home connection status UI.
 */
class DetachedSessionActions(
    private val app: Application,
    private val identityStore: IdentityStore,
    private val hub: RelayMessagingHub,
    private val scope: CoroutineScope,
    private val getState: () -> HomeUiState,
    private val updateState: ((HomeUiState) -> HomeUiState) -> Unit,
    private val withSetupFlags: (HomeUiState) -> HomeUiState,
    private val appStr: (Int, Array<out Any>) -> String,
    private val testServerConnection: () -> Unit,
) {
    fun refreshHomeConnectionUi() {
        val detached = hub.detachedSessionsList
        val count = detached.size
        updateState {
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

    fun retryRelayStatusBar() {
        scope.launch {
            val detached = hub.detachedSessionsList
            if (detached.isNotEmpty()) {
                val identity = getState().identity ?: return@launch
                for (session in detached) {
                    joinChatSession(session, identity)
                }
                refreshHomeConnectionUi()
            } else {
                testServerConnection()
            }
        }
    }

    suspend fun restoreDetachedSessionsIfNeeded(identity: Identity) {
        if (hub.activeSession != null) return
        val records = DetachedSessionStore(app).loadAll()
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
            DetachedSessionStore(app).clear()
            if (failedAny) {
                AppSnackbarBus.show(appStr(R.string.error_restore_detached_session_failed, emptyArray()))
            }
        } else {
            hub.restoreDetachedSessions(restored)
            if (failedAny) {
                AppSnackbarBus.show(appStr(R.string.error_restore_detached_session_partial, emptyArray()))
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
