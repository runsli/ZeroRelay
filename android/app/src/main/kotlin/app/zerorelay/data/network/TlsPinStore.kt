package app.zerorelay.data.network

import android.content.Context
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import okhttp3.CertificatePinner.Companion.pin
import java.security.cert.X509Certificate

/**
 * TLS SPKI pin 存入 EncryptedSharedPreferences；支持多 pin 并存（证书轮换）。
 */
object TlsPinStore {
    private const val PREFS = "zero_relay_tls_pins_enc"
    private const val LEGACY_PREFS = "zero_relay_tls_pins"

    private fun prefs(context: Context) = EncryptedSharedPreferences.create(
        context.applicationContext,
        PREFS,
        MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun migrateFromLegacyIfNeeded(context: Context) {
        val app = context.applicationContext
        val legacy = app.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        val enc = prefs(app)
        legacy.all.forEach { (host, value) ->
            if (value is Set<*>) {
                val pins = value.filterIsInstance<String>().toSet()
                if (pins.isNotEmpty() && enc.getStringSet(host, null).isNullOrEmpty()) {
                    enc.edit { putStringSet(host, pins) }
                }
            }
        }
        legacy.edit { clear() }
    }

    fun loadPins(context: Context, host: String): Set<String> {
        migrateFromLegacyIfNeeded(context)
        return prefs(context).getStringSet(host, null).orEmpty()
    }

    fun addPin(context: Context, host: String, pinHash: String) {
        migrateFromLegacyIfNeeded(context)
        val p = prefs(context)
        val set = p.getStringSet(host, emptySet())?.toMutableSet() ?: mutableSetOf()
        set.add(pinHash)
        p.edit { putStringSet(host, set) }
    }

    fun clearHost(context: Context, host: String) {
        prefs(context).edit { remove(host) }
    }

    fun hasPin(context: Context, host: String): Boolean = loadPins(context, host).isNotEmpty()

    /** 握手证书 pin；若与已存 pin 集合不交集且已有 pin，返回新 pin 供用户确认 */
    fun evaluateHandshake(
        context: Context,
        host: String,
        certificates: List<java.security.cert.Certificate>,
    ): PinEvaluation {
        val cert = certificates.firstOrNull() as? X509Certificate
            ?: return PinEvaluation(trusted = true, newPinToTrust = null)
        val newPin = pin(cert)
        val existing = loadPins(context, host)
        if (existing.isEmpty()) {
            addPin(context, host, newPin)
            return PinEvaluation(trusted = true, newPinToTrust = null)
        }
        if (existing.contains(newPin)) {
            return PinEvaluation(trusted = true, newPinToTrust = null)
        }
        return PinEvaluation(trusted = false, newPinToTrust = newPin)
    }

    data class PinEvaluation(
        val trusted: Boolean,
        val newPinToTrust: String?,
    )
}
