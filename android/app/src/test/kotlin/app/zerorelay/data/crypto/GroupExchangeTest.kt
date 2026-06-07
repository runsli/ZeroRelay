package app.zerorelay.data.crypto

import android.util.Base64
import app.zerorelay.data.model.ChatGroup
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupExchangeTest {
    private val groupKeyBase64: String =
        Base64.encodeToString(ByteArray(32) { 0x42 }, Base64.NO_WRAP)

    private fun sampleGroup(
        inviteExpiresAt: Long? = null,
        members: List<String> = listOf("alice", "bob"),
    ) = ChatGroup(
        id = "group-1",
        displayName = "Test Group",
        roomId = "room-abc",
        groupKeyBase64 = groupKeyBase64,
        memberContactIds = members,
        keyVersion = 2,
        inviteExpiresAt = inviteExpiresAt,
    )

    @Test
    fun encodeInvite_roundTrip() {
        val invite = GroupExchange.encodeInvite(sampleGroup())
        val parsed = GroupExchange.parse(invite)

        requireNotNull(parsed)
        assertEquals("group-1", parsed.groupId)
        assertEquals("Test Group", parsed.displayName)
        assertEquals(groupKeyBase64, parsed.groupKeyBase64)
        assertEquals(listOf("alice", "bob"), parsed.memberContactIds)
        assertEquals(2, parsed.keyVersion)
        assertNull(parsed.serverUrl)
        assertNull(parsed.expiresAt)
    }

    @Test
    fun encodeInvite_preservesExpiry() {
        val expiresAt = 1_700_000_000_000L
        val parsed = GroupExchange.parse(GroupExchange.encodeInvite(sampleGroup(inviteExpiresAt = expiresAt)))

        requireNotNull(parsed)
        assertEquals(expiresAt, parsed.expiresAt)
        assertFalse(parsed.isExpired(now = expiresAt))
        assertFalse(parsed.isExpired(now = expiresAt - 1))
        assertTrue(parsed.isExpired(now = expiresAt + 1))
    }

    @Test
    fun invitePayload_isExpired_whenNoExpiry() {
        val payload =
            GroupExchange.InvitePayload(
                groupId = "g",
                displayName = "n",
                groupKeyBase64 = groupKeyBase64,
                memberContactIds = emptyList(),
                expiresAt = null,
            )
        assertFalse(payload.isExpired(now = Long.MAX_VALUE))
    }

    @Test
    fun parse_returnsNull_forInvalidInput() {
        assertNull(GroupExchange.parse(""))
        assertNull(GroupExchange.parse("zerorelay://contact?v=1"))
        assertNull(GroupExchange.parse("zerorelay://group?v=1&d=%%%"))
    }

    @Test
    fun parse_returnsNull_forUnsupportedVersion() {
        assertNull(GroupExchange.parse(inviteUrl(jsonWithVersion(3))))
    }

    @Test
    fun parse_returnsNull_whenKeyLengthInvalid() {
        val shortKey = Base64.encodeToString(ByteArray(16), Base64.NO_WRAP)
        val json =
            JSONObject().apply {
                put("v", 2)
                put("gid", "g1")
                put("n", "name")
                put("k", shortKey)
            }
        assertNull(GroupExchange.parse(inviteUrl(json)))
    }

    @Test
    fun parse_acceptsVersionOne() {
        val json =
            JSONObject().apply {
                put("v", 1)
                put("gid", "legacy")
                put("n", "Legacy")
                put("k", groupKeyBase64)
                put("m", JSONArray(listOf("u1")))
            }
        val parsed = GroupExchange.parse(inviteUrl(json))

        requireNotNull(parsed)
        assertEquals("legacy", parsed.groupId)
        assertEquals(listOf("u1"), parsed.memberContactIds)
        assertEquals(1, parsed.keyVersion)
    }

    private fun jsonWithVersion(version: Int): JSONObject =
        JSONObject().apply {
            put("v", version)
            put("gid", "g1")
            put("n", "n1")
            put("k", groupKeyBase64)
        }

    private fun inviteUrl(json: JSONObject): String {
        val data =
            Base64.encodeToString(
                json.toString().toByteArray(Charsets.UTF_8),
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
            )
        return "zerorelay://group?v=1&d=$data"
    }
}
