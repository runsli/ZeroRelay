package app.zerorelay.data.crypto

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MessageCipherTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun encryptGroup_roundTrip() {
        val cipher = MessageCipher(context)
        val groupKey = ByteArray(32) { 0x11 }
        val payload = cipher.encryptGroup("hello group", groupKey, keyVersion = 1)
        val plain = cipher.decryptGroup(payload, mapOf(1 to groupKey))

        assertEquals("hello group", plain)
    }

    @Test
    fun decryptGroup_usesMatchingKeyVersion() {
        val cipher = MessageCipher(context)
        val v1Key = ByteArray(32) { 0x01 }
        val v2Key = ByteArray(32) { 0x02 }
        val payload = cipher.encryptGroup("rotated", v2Key, keyVersion = 2)

        assertNull(cipher.decryptGroup(payload, mapOf(1 to v1Key)))
        assertEquals("rotated", cipher.decryptGroup(payload, mapOf(1 to v1Key, 2 to v2Key)))
    }

    @Test
    fun decryptGroup_fixedTestVector() {
        val cipher = MessageCipher(context)
        val groupKey = ByteArray(32) { 0x42 }
        val envelope =
            JSONObject().apply {
                put("v", MessageRatchet.PROTOCOL_VERSION)
                put("kv", 1)
                put("t", MessagePadding.pad("vector"))
            }.toString()
        val iv = ByteArray(12) { 0x09 }
        val payload = encryptAesGcmPayload(envelope, groupKey, iv)

        assertEquals("vector", cipher.decryptGroup(payload, mapOf(1 to groupKey)))
    }

    @Test
    fun encryptDirect_decryptDirect_roundTrip() {
        val alice = IdentityCrypto.generateKeyPair()
        val bob = IdentityCrypto.generateKeyPair()
        val roomId = IdentityCrypto.deriveRoomId(alice.publicKey, bob.publicKey)
        val aliceRoot =
            IdentityCrypto.deriveSessionKey(alice.privateKey, bob.publicKey, roomId)
        val bobRoot =
            IdentityCrypto.deriveSessionKey(bob.privateKey, alice.publicKey, roomId)

        val aliceCipher = MessageCipher(context)
        val payload =
            aliceCipher.encryptDirect(
                roomId = roomId,
                rootSessionKey = aliceRoot,
                localPublicKey = alice.publicKey,
                peerPublicKey = bob.publicKey,
                plaintext = "direct hi",
            )

        val bobCipher = MessageCipher(context)
        val result =
            bobCipher.decryptDirect(
                roomId = roomId,
                rootSessionKey = bobRoot,
                localPublicKey = bob.publicKey,
                peerPublicKey = alice.publicKey,
                payload = payload,
                messageTimestamp = System.currentTimeMillis(),
            )

        requireNotNull(result)
        assertEquals("direct hi", result.text)
        assertFalse(result.usedLegacyStaticKey)
    }

    @Test
    fun encryptDirect_persistsRatchetForNextMessage() {
        val alice = IdentityCrypto.generateKeyPair()
        val bob = IdentityCrypto.generateKeyPair()
        val roomId = IdentityCrypto.deriveRoomId(alice.publicKey, bob.publicKey)
        val aliceRoot =
            IdentityCrypto.deriveSessionKey(alice.privateKey, bob.publicKey, roomId)
        val bobRoot =
            IdentityCrypto.deriveSessionKey(bob.privateKey, alice.publicKey, roomId)

        val aliceCipher = MessageCipher(context)
        val first =
            aliceCipher.encryptDirect(
                roomId,
                aliceRoot,
                alice.publicKey,
                bob.publicKey,
                "one",
            )
        val second =
            aliceCipher.encryptDirect(
                roomId,
                aliceRoot,
                alice.publicKey,
                bob.publicKey,
                "two",
            )

        val bobCipher = MessageCipher(context)
        val now = System.currentTimeMillis()
        assertEquals(
            "one",
            bobCipher.decryptDirect(
                roomId,
                bobRoot,
                bob.publicKey,
                alice.publicKey,
                first,
                now,
            )?.text,
        )
        assertEquals(
            "two",
            bobCipher.decryptDirect(
                roomId,
                bobRoot,
                bob.publicKey,
                alice.publicKey,
                second,
                now,
            )?.text,
        )
    }

    private fun encryptAesGcmPayload(
        plaintext: String,
        key: ByteArray,
        iv: ByteArray,
    ): CryptoService.EncryptedPayload {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val combined = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val tagLength = 16
        val ciphertext = combined.copyOf(combined.size - tagLength)
        val tag = combined.copyOfRange(combined.size - tagLength, combined.size)
        return CryptoService.EncryptedPayload(
            ciphertext = Base64.encodeToString(ciphertext, Base64.NO_WRAP),
            iv = Base64.encodeToString(iv, Base64.NO_WRAP),
            tag = Base64.encodeToString(tag, Base64.NO_WRAP),
        )
    }
}
