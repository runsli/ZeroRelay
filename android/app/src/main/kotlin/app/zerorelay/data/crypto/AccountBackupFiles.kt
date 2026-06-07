package app.zerorelay.data.crypto

import android.content.Context
import android.net.Uri

object AccountBackupFiles {
    const val MIME_TYPE = "application/octet-stream"
    const val DEFAULT_FILENAME = "zerorelay-account-backup.zrab"

    fun write(context: Context, uri: Uri, blob: String): Boolean {
        return try {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(blob.toByteArray(Charsets.UTF_8))
            } != null
        } catch (_: Exception) {
            false
        }
    }

    fun read(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                input.readBytes().decodeToString()
            }?.trim()?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }
}
