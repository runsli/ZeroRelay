package app.zerorelay.data.crypto

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountBackupTest {
    private val passphrase = "test-passphrase".toCharArray()

    @Test
    fun encryptDecrypt_roundTrip() {
        val payload =
            JSONObject().apply {
                put("identity", "alice")
                put(
                    "ratchets",
                    JSONObject().apply {
                        put("bob", JSONObject().apply { put("send", 1) })
                    },
                )
            }

        val blob = AccountBackup.encryptExport(passphrase, payload)
        val restored = AccountBackup.decryptImport(passphrase, blob)

        assertEquals(payload.getString("identity"), restored.getString("identity"))
        assertEquals(
            payload.getJSONObject("ratchets").getJSONObject("bob").getInt("send"),
            restored.getJSONObject("ratchets").getJSONObject("bob").getInt("send"),
        )
        assertTrue(blob.contains(AccountBackup.FORMAT))
    }

    @Test(expected = IllegalArgumentException::class)
    fun decryptImport_rejectsUnsupportedFormat() {
        val blob =
            AccountBackup.encryptExport(passphrase, JSONObject().put("x", 1))
                .replace(AccountBackup.FORMAT, "other-format")

        AccountBackup.decryptImport(passphrase, blob)
    }

    @Test(expected = Exception::class)
    fun decryptImport_rejectsWrongPassphrase() {
        val blob = AccountBackup.encryptExport(passphrase, JSONObject().put("ok", true))
        AccountBackup.decryptImport("wrong-pass".toCharArray(), blob)
    }

    @Test(expected = Exception::class)
    fun decryptImport_rejectsMalformedJson() {
        AccountBackup.decryptImport(passphrase, "not-json")
    }
}
