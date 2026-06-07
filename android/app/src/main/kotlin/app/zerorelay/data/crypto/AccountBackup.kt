package app.zerorelay.data.crypto

import org.json.JSONObject

/** 口令保护的完整账户备份（格式标识：zero-relay-account-backup-v1）。 */
object AccountBackup {
    const val FORMAT = "zero-relay-account-backup-v1"

    fun encryptExport(passphrase: CharArray, payload: JSONObject): String {
        val encrypted = JSONObject(RatchetBackup.encryptExport(passphrase, payload))
        return JSONObject().apply {
            put("format", FORMAT)
            put("v", encrypted.getInt("v"))
            put("salt", encrypted.getString("salt"))
            put("iv", encrypted.getString("iv"))
            put("data", encrypted.getString("data"))
        }.toString()
    }

    fun decryptImport(passphrase: CharArray, blob: String): JSONObject {
        val outer = JSONObject(blob.trim())
        require(outer.optString("format") == FORMAT) { "备份格式不支持" }
        val inner = JSONObject().apply {
            put("v", outer.getInt("v"))
            put("salt", outer.getString("salt"))
            put("iv", outer.getString("iv"))
            put("data", outer.getString("data"))
        }.toString()
        return RatchetBackup.decryptImport(passphrase, inner)
    }
}
