package app.zerorelay.data.session

import android.util.Base64
import app.zerorelay.data.crypto.IdentityCrypto
import app.zerorelay.data.error.DataError
import app.zerorelay.data.model.ChatGroup
import app.zerorelay.data.model.ChatKind
import app.zerorelay.data.model.ChatSession
import app.zerorelay.data.model.Contact
import app.zerorelay.data.model.Identity
import app.zerorelay.data.network.ServerUrl

object SessionFactory {
    fun create(serverUrl: String, identity: Identity, contact: Contact): ChatSession {
        val peerPublicKey = IdentityCrypto.decodePublicKey(contact.publicKeyBase64)
        val roomId = IdentityCrypto.deriveRoomId(identity.publicKey, peerPublicKey)
        val sessionKey = IdentityCrypto.deriveSessionKey(
            identity.privateKey,
            peerPublicKey,
            roomId,
        )
        return ChatSession(
            serverUrl = ServerUrl.normalize(serverUrl),
            roomId = roomId,
            sessionKey = sessionKey,
            senderId = IdentityCrypto.senderIdFromPublicKey(identity.publicKey),
            peerDisplayName = contact.displayName,
            peerContactId = contact.id,
            peerFingerprint = IdentityCrypto.fingerprint(peerPublicKey),
            peerPublicKeyBase64 = contact.publicKeyBase64,
            kind = ChatKind.Direct,
        )
    }

    fun createForGroup(serverUrl: String, identity: Identity, group: ChatGroup): ChatSession {
        val sessionKey = Base64.decode(group.groupKeyBase64, Base64.NO_WRAP)
        if (sessionKey.size != 32) throw DataError.GroupSessionKeyInvalid
        return ChatSession(
            serverUrl = ServerUrl.normalize(serverUrl),
            roomId = group.roomId,
            sessionKey = sessionKey,
            senderId = IdentityCrypto.senderIdFromPublicKey(identity.publicKey),
            peerDisplayName = group.displayName,
            peerContactId = group.id,
            peerFingerprint = app.zerorelay.data.crypto.GroupCrypto.fingerprint(group.id),
            kind = ChatKind.Group,
            memberCount = group.memberCount,
            groupKeyVersion = group.keyVersion,
            groupKeysByVersion = group.keysByVersion(),
        )
    }
}
