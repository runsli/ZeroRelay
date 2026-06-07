package app.zerorelay.data.identity

import android.content.Context
import android.util.Base64
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import app.zerorelay.data.crypto.ContactExchange
import app.zerorelay.data.crypto.GroupCrypto
import app.zerorelay.data.crypto.GroupExchange
import app.zerorelay.data.crypto.IdentityCrypto
import app.zerorelay.data.model.ChatGroup
import app.zerorelay.data.model.Contact
import app.zerorelay.data.error.DataError
import app.zerorelay.data.model.Identity
import org.json.JSONArray
import org.json.JSONObject

class IdentityStore(context: Context) {
    private val appContext = context.applicationContext

    private val securePrefs by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            PREFS_SECURE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private val prefs by lazy {
        appContext.getSharedPreferences(PREFS_CONTACTS, Context.MODE_PRIVATE)
    }

    fun getOrCreateIdentity(): Identity {
        val pub = securePrefs.getString(KEY_PUBLIC, null)
        val privWrapped = securePrefs.getString(KEY_PRIVATE_WRAPPED, null)
        val privLegacy = securePrefs.getString(KEY_PRIVATE, null)
        if (pub != null && (privWrapped != null || privLegacy != null)) {
            val privateKey = when {
                privWrapped != null -> SecureKeyStorage.unwrapPrivateKey(privWrapped)
                else -> Base64.decode(privLegacy!!, Base64.NO_WRAP)
            }
            if (privLegacy != null && privWrapped == null) {
                migratePrivateKeyToKeystore(privateKey)
            }
            return Identity(
                publicKey = Base64.decode(pub, Base64.NO_WRAP),
                privateKey = privateKey,
            )
        }
        val pair = IdentityCrypto.generateKeyPair()
        securePrefs.edit {
            putString(KEY_PUBLIC, Base64.encodeToString(pair.publicKey, Base64.NO_WRAP))
            remove(KEY_PRIVATE)
            putString(KEY_PRIVATE_WRAPPED, SecureKeyStorage.wrapPrivateKey(pair.privateKey))
        }
        return Identity(pair.privateKey, pair.publicKey)
    }

    private fun migratePrivateKeyToKeystore(privateKey: ByteArray) {
        securePrefs.edit {
            remove(KEY_PRIVATE)
            putString(KEY_PRIVATE_WRAPPED, SecureKeyStorage.wrapPrivateKey(privateKey))
        }
    }

    fun getContacts(): List<Contact> {
        val json = prefs.getString(KEY_CONTACTS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        Contact(
                            id = o.getString("id"),
                            displayName = o.getString("name"),
                            publicKeyBase64 = o.getString("pk"),
                            addedAt = o.optLong("at", 0L),
                            verified = o.optBoolean("vf", false),
                            verifiedAt = o.optLong("vfa", 0L).takeIf { it > 0L },
                        ),
                    )
                }
            }.sortedByDescending { it.addedAt }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addContactFromPayload(payload: ContactExchange.Payload): Contact {
        val publicKey = IdentityCrypto.decodePublicKey(payload.publicKeyBase64)
        val own = getOrCreateIdentity()
        if (publicKey.contentEquals(own.publicKey)) throw DataError.AddSelfAsContact

        val id = IdentityCrypto.contactIdFromPublicKey(publicKey)
        val name = payload.displayName?.trim().takeUnless { it.isNullOrBlank() }
            ?: IdentityCrypto.fingerprint(publicKey)
        val contact = Contact(
            id = id,
            displayName = name,
            publicKeyBase64 = IdentityCrypto.encodePublicKey(publicKey),
            verified = false,
        )
        val list = getContacts().filter { it.id != id }.toMutableList()
        list.add(0, contact)
        saveContacts(list)
        return contact
    }

    fun deleteContact(id: String) {
        saveContacts(getContacts().filter { it.id != id })
    }

    fun updateContactDisplayName(id: String, displayName: String): Contact? {
        val existing = findContact(id) ?: return null
        val trimmed = displayName.trim()
        val name = trimmed.ifBlank { existing.fingerprint }
        var updated: Contact? = null
        saveContacts(
            getContacts().map { contact ->
                if (contact.id == id) {
                    contact.copy(displayName = name).also { updated = it }
                } else {
                    contact
                }
            },
        )
        return updated
    }

    fun findContact(id: String): Contact? = getContacts().firstOrNull { it.id == id }

    fun markContactVerified(id: String) {
        val now = System.currentTimeMillis()
        saveContacts(
            getContacts().map { c ->
                if (c.id == id) c.copy(verified = true, verifiedAt = now) else c
            },
        )
    }

    fun getGroups(): List<ChatGroup> {
        val json = prefs.getString(KEY_GROUPS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val members = buildList {
                        val m = o.optJSONArray("m") ?: return@buildList
                        for (j in 0 until m.length()) {
                            val id = m.optString(j, "").trim()
                            if (id.isNotBlank()) add(id)
                        }
                    }
                    val prevKeys = buildMap {
                        val pk = o.optJSONObject("pk") ?: return@buildMap
                        pk.keys().forEach { k ->
                            val ver = k.toIntOrNull() ?: return@forEach
                            put(ver, pk.getString(k))
                        }
                    }
                    add(
                        ChatGroup(
                            id = o.getString("id"),
                            displayName = o.getString("name"),
                            roomId = o.getString("room"),
                            groupKeyBase64 = o.getString("key"),
                            memberContactIds = members,
                            createdAt = o.optLong("at", 0L),
                            keyVersion = o.optInt("kv", 1),
                            inviteExpiresAt = o.optLong("exp", 0L).takeIf { it > 0L },
                            previousKeysBase64 = prevKeys,
                        ),
                    )
                }
            }.sortedByDescending { it.createdAt }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun createGroup(displayName: String, memberContactIds: List<String>): ChatGroup {
        val name = displayName.trim()
        if (name.isBlank()) throw DataError.GroupNameEmpty
        val groupId = GroupCrypto.generateGroupId()
        val groupKey = GroupCrypto.generateGroupKey()
        val roomId = GroupCrypto.deriveRoomId(groupId)
        val now = System.currentTimeMillis()
        val group = ChatGroup(
            id = groupId,
            displayName = name,
            roomId = roomId,
            groupKeyBase64 = Base64.encodeToString(groupKey, Base64.NO_WRAP),
            memberContactIds = memberContactIds.distinct(),
            keyVersion = 1,
            inviteExpiresAt = now + GroupCrypto.DEFAULT_INVITE_TTL_MS,
        )
        val list = getGroups().filter { it.id != groupId }.toMutableList()
        list.add(0, group)
        saveGroups(list)
        return group
    }

    fun joinGroupFromInvite(payload: GroupExchange.InvitePayload): ChatGroup {
        if (payload.isExpired()) throw DataError.GroupInviteExpired
        val keyBytes = Base64.decode(payload.groupKeyBase64, Base64.NO_WRAP)
        if (keyBytes.size != 32) throw DataError.GroupInviteKeyInvalid
        val roomId = GroupCrypto.deriveRoomId(payload.groupId)
        val group = ChatGroup(
            id = payload.groupId,
            displayName = payload.displayName.trim(),
            roomId = roomId,
            groupKeyBase64 = payload.groupKeyBase64,
            memberContactIds = payload.memberContactIds,
            keyVersion = payload.keyVersion,
            inviteExpiresAt = payload.expiresAt,
        )
        val list = getGroups().filter { it.id != payload.groupId }.toMutableList()
        list.add(0, group)
        saveGroups(list)
        return group
    }

    fun deleteGroup(id: String) {
        saveGroups(getGroups().filter { it.id != id })
    }

    fun findGroup(id: String): ChatGroup? = getGroups().firstOrNull { it.id == id }

    fun rotateGroupKey(groupId: String): ChatGroup {
        val existing = findGroup(groupId) ?: throw DataError.GroupNotFound
        val newKey = GroupCrypto.generateGroupKey()
        val newVersion = existing.keyVersion + 1
        val now = System.currentTimeMillis()
        val previous = existing.previousKeysBase64.toMutableMap()
        previous[existing.keyVersion] = existing.groupKeyBase64
        val updated = existing.copy(
            groupKeyBase64 = Base64.encodeToString(newKey, Base64.NO_WRAP),
            keyVersion = newVersion,
            inviteExpiresAt = now + GroupCrypto.DEFAULT_INVITE_TTL_MS,
            previousKeysBase64 = previous,
        )
        val list = getGroups().filter { it.id != groupId }.toMutableList()
        list.add(0, updated)
        saveGroups(list)
        return updated
    }

    private fun saveContacts(contacts: List<Contact>) {
        val arr = JSONArray()
        contacts.forEach { c ->
            arr.put(
                JSONObject().apply {
                    put("id", c.id)
                    put("name", c.displayName)
                    put("pk", c.publicKeyBase64)
                    put("at", c.addedAt)
                    put("vf", c.verified)
                    c.verifiedAt?.let { put("vfa", it) }
                },
            )
        }
        prefs.edit { putString(KEY_CONTACTS, arr.toString()) }
    }

    fun hasAccountData(): Boolean = getContacts().isNotEmpty() || getGroups().isNotEmpty()

    fun exportSnapshotJson(): JSONObject {
        val identity = getOrCreateIdentity()
        return JSONObject().apply {
            put("publicKey", Base64.encodeToString(identity.publicKey, Base64.NO_WRAP))
            put("privateKey", Base64.encodeToString(identity.privateKey, Base64.NO_WRAP))
            put("contacts", JSONArray(prefs.getString(KEY_CONTACTS, "[]")))
            put("groups", JSONArray(prefs.getString(KEY_GROUPS, "[]")))
        }
    }

    fun importSnapshotJson(snapshot: JSONObject) {
        val publicKey = Base64.decode(snapshot.getString("publicKey"), Base64.NO_WRAP)
        val privateKey = Base64.decode(snapshot.getString("privateKey"), Base64.NO_WRAP)
        if (publicKey.isEmpty() || privateKey.isEmpty()) throw DataError.IdentityKeyInvalid
        securePrefs.edit {
            putString(KEY_PUBLIC, Base64.encodeToString(publicKey, Base64.NO_WRAP))
            remove(KEY_PRIVATE)
            putString(KEY_PRIVATE_WRAPPED, SecureKeyStorage.wrapPrivateKey(privateKey))
        }
        val contacts = snapshot.getJSONArray("contacts").toString()
        val groups = snapshot.getJSONArray("groups").toString()
        prefs.edit {
            putString(KEY_CONTACTS, contacts)
            putString(KEY_GROUPS, groups)
        }
    }

    private fun saveGroups(groups: List<ChatGroup>) {
        val arr = JSONArray()
        groups.forEach { g ->
            arr.put(
                JSONObject().apply {
                    put("id", g.id)
                    put("name", g.displayName)
                    put("room", g.roomId)
                    put("key", g.groupKeyBase64)
                    put("at", g.createdAt)
                    put("kv", g.keyVersion)
                    g.inviteExpiresAt?.let { put("exp", it) }
                    if (g.previousKeysBase64.isNotEmpty()) {
                        put(
                            "pk",
                            JSONObject().apply {
                                g.previousKeysBase64.forEach { (ver, b64) ->
                                    put(ver.toString(), b64)
                                }
                            },
                        )
                    }
                    if (g.memberContactIds.isNotEmpty()) {
                        put("m", JSONArray(g.memberContactIds))
                    }
                },
            )
        }
        prefs.edit { putString(KEY_GROUPS, arr.toString()) }
    }

    companion object {
        private const val PREFS_SECURE = "zero_relay_identity"
        private const val PREFS_CONTACTS = "zero_relay_contacts"
        private const val KEY_PUBLIC = "public_key"
        private const val KEY_PRIVATE = "private_key"
        private const val KEY_PRIVATE_WRAPPED = "private_key_wrapped"
        private const val KEY_CONTACTS = "contacts"
        private const val KEY_GROUPS = "groups"
    }
}
