package app.zerorelay.data.crypto

import android.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContactExchangeTest {
    private val keyPair = IdentityCrypto.generateKeyPair()
    private val publicKeyBase64 = IdentityCrypto.encodePublicKey(keyPair.publicKey)

    @Test
    fun encodeParse_roundTripWithDisplayName() {
        val invite = ContactExchange.encodePayload(publicKeyBase64, "Alice")
        val parsed = ContactExchange.parse(invite)

        requireNotNull(parsed)
        assertEquals(publicKeyBase64, parsed.publicKeyBase64)
        assertEquals("Alice", parsed.displayName)
    }

    @Test
    fun encodeParse_roundTripWithoutDisplayName() {
        val invite = ContactExchange.encodePayload(publicKeyBase64, null)
        val parsed = ContactExchange.parse(invite)

        requireNotNull(parsed)
        assertEquals(publicKeyBase64, parsed.publicKeyBase64)
        assertNull(parsed.displayName)
    }

    @Test
    fun parse_acceptsRawPublicKeyOnly() {
        val parsed = ContactExchange.parse(publicKeyBase64)

        requireNotNull(parsed)
        assertEquals(publicKeyBase64, parsed.publicKeyBase64)
        assertNull(parsed.displayName)
    }

    @Test
    fun parse_returnsNull_forInvalidInput() {
        assertNull(ContactExchange.parse(""))
        assertNull(ContactExchange.parse("not-a-key"))
        assertNull(ContactExchange.parse("zerorelay://group?v=1&d=abc"))
    }

    @Test
    fun parse_returnsNull_forUnsupportedVersion() {
        val json =
            JSONObject().apply {
                put("v", 2)
                put("pk", publicKeyBase64)
            }
        val data =
            Base64.encodeToString(
                json.toString().toByteArray(Charsets.UTF_8),
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
            )
        assertNull(ContactExchange.parse("zerorelay://v1?d=$data"))
    }

    @Test
    fun parse_returnsNull_whenPublicKeyMissing() {
        val json = JSONObject().apply { put("v", 1) }
        val data =
            Base64.encodeToString(
                json.toString().toByteArray(Charsets.UTF_8),
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
            )
        assertNull(ContactExchange.parse("zerorelay://v1?d=$data"))
    }
}
