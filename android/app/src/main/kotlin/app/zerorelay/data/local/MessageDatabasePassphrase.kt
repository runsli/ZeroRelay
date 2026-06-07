package app.zerorelay.data.local

import android.content.Context
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

/** SQLCipher 口令：随机生成后存入 EncryptedSharedPreferences，不落明文 SharedPreferences。 */
internal object MessageDatabasePassphrase {
    const val PREFS_NAME = "zero_relay_db_key"
    private const val KEY_PASSPHRASE = "db_passphrase"
    private const val PASSPHRASE_BYTES = 32

    fun get(context: Context): ByteArray {
        val prefs = encryptedPrefs(context.applicationContext)
        val existing = prefs.getString(KEY_PASSPHRASE, null)
        if (existing != null) {
            return existing.toByteArray(Charsets.ISO_8859_1)
        }
        val generated = ByteArray(PASSPHRASE_BYTES).also { SecureRandom().nextBytes(it) }
        prefs.edit {
            putString(KEY_PASSPHRASE, String(generated, Charsets.ISO_8859_1))
        }
        return generated
    }

    private fun encryptedPrefs(context: Context) =
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
}
