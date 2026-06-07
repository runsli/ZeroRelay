package app.zerorelay.ui.home

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import app.zerorelay.R
import app.zerorelay.data.crypto.AccountBackup
import app.zerorelay.data.crypto.AccountBackupFiles
import app.zerorelay.data.crypto.MessageCipher
import app.zerorelay.data.crypto.RatchetBackup
import app.zerorelay.data.crypto.RatchetBackupFiles
import app.zerorelay.data.identity.IdentityStore
import app.zerorelay.data.local.UserPreferences
import app.zerorelay.data.network.RelayHttpClient
import app.zerorelay.data.network.ServerUrl
import app.zerorelay.data.network.TlsPinStore
import app.zerorelay.ui.error.UserError
import app.zerorelay.ui.error.UserErrorKind
import app.zerorelay.ui.error.UserErrorMapping
import app.zerorelay.ui.snackbar.AppSnackbarBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Account and ratchet backup import/export.
 */
class BackupActions(
    private val app: Application,
    private val identityStore: IdentityStore,
    private val prefs: UserPreferences,
    private val scope: CoroutineScope,
    private val getState: () -> HomeUiState,
    private val updateState: ((HomeUiState) -> HomeUiState) -> Unit,
    private val setUserError: (UserError) -> Unit,
    private val setUserErrorKind: (UserErrorKind, String?) -> Unit,
    private val appStr: (Int, Array<out Any>) -> String,
    private val reloadAccountUi: suspend () -> Unit,
    private val migrationActions: MigrationActions,
    private val isSetupIncomplete: (List<app.zerorelay.data.model.Contact>) -> Boolean,
) {
    private var pendingAccountImportBlob: String? = null

    fun showAccountBackup(show: Boolean) = updateState {
        it.copy(
            showAccountBackupDialog = show,
            accountBackupPassphrase = if (show) it.accountBackupPassphrase else "",
            userError = if (show) null else it.userError,
        )
    }

    fun onAccountPassphraseChange(value: String) = updateState {
        it.copy(accountBackupPassphrase = value)
    }

    fun prepareAccountExport(): Boolean = validateBackupPassphrase(getState().accountBackupPassphrase)

    fun exportAccountBackupToUri(uri: Uri, passphrase: String): Boolean {
        val blob = buildAccountExportBlob(passphrase) ?: return false
        val ok = AccountBackupFiles.write(app, uri, blob)
        if (!ok) {
            setUserErrorKind(UserErrorKind.Generic, appStr(R.string.error_account_backup_write, emptyArray()))
            return false
        }
        updateState {
            it.copy(
                showAccountBackupDialog = false,
                accountBackupPassphrase = "",
            )
        }
        AppSnackbarBus.show(appStr(R.string.snackbar_account_backup_exported_secure, emptyArray()))
        return true
    }

    fun importAccountBackupFromUri(uri: Uri, passphrase: String): Boolean {
        if (!validateBackupPassphrase(passphrase)) return false
        val blob = AccountBackupFiles.read(app, uri)
        if (blob == null) {
            setUserErrorKind(UserErrorKind.Generic, appStr(R.string.error_account_backup_read, emptyArray()))
            return false
        }
        return beginAccountImport(blob, passphrase)
    }

    fun dismissAccountImportOverwrite() {
        pendingAccountImportBlob = null
        updateState { it.copy(showAccountImportOverwriteDialog = false) }
    }

    fun confirmAccountImportOverwrite() {
        val blob = pendingAccountImportBlob ?: return
        val pass = getState().accountBackupPassphrase
        pendingAccountImportBlob = null
        updateState { it.copy(showAccountImportOverwriteDialog = false) }
        applyAccountImport(blob, pass)
    }

    fun showRatchetBackup(show: Boolean) = updateState {
        it.copy(
            showRatchetBackupDialog = show,
            ratchetBackupPassphrase = if (show) it.ratchetBackupPassphrase else "",
            userError = if (show) null else it.userError,
        )
    }

    fun setRatchetAdvanced(show: Boolean) = updateState { it.copy(showRatchetAdvanced = show) }

    fun onRatchetPassphraseChange(value: String) = updateState { it.copy(ratchetBackupPassphrase = value) }

    fun prepareRatchetExport(): Boolean = validateBackupPassphrase(getState().ratchetBackupPassphrase)

    fun exportRatchetBackupToUri(uri: Uri, passphrase: String): Boolean {
        val blob = buildRatchetExportBlob(passphrase) ?: return false
        val ok = RatchetBackupFiles.write(app, uri, blob)
        if (!ok) {
            setUserErrorKind(UserErrorKind.Generic, appStr(R.string.error_ratchet_write, emptyArray()))
            return false
        }
        updateState {
            it.copy(
                showRatchetBackupDialog = false,
                ratchetBackupPassphrase = "",
            )
        }
        AppSnackbarBus.show(appStr(R.string.snackbar_ratchet_exported, emptyArray()))
        return true
    }

    fun importRatchetBackupFromUri(uri: Uri, passphrase: String): Boolean {
        if (!validateBackupPassphrase(passphrase)) return false
        val blob = RatchetBackupFiles.read(app, uri)
        if (blob == null) {
            setUserErrorKind(UserErrorKind.Generic, appStr(R.string.error_ratchet_read, emptyArray()))
            return false
        }
        return importRatchetBackup(blob)
    }

    fun exportRatchetBackupToClipboard(): Boolean {
        val pass = getState().ratchetBackupPassphrase
        val blob = buildRatchetExportBlob(pass) ?: return false
        val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("zerorelay-ratchet-backup", blob))
        updateState {
            it.copy(
                showRatchetBackupDialog = false,
                ratchetBackupPassphrase = "",
            )
        }
        AppSnackbarBus.show(appStr(R.string.snackbar_ratchet_clipboard, emptyArray()))
        return true
    }

    fun importRatchetBackupFromClipboard(): Boolean {
        val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val blob = cm.primaryClip?.getItemAt(0)?.text?.toString()?.trim().orEmpty()
        if (blob.isEmpty()) {
            setUserErrorKind(UserErrorKind.Generic, appStr(R.string.error_ratchet_clipboard_empty, emptyArray()))
            return false
        }
        return importRatchetBackup(blob)
    }

    fun importRatchetBackup(blob: String): Boolean {
        val pass = getState().ratchetBackupPassphrase
        if (!validateBackupPassphrase(pass)) return false
        return try {
            val json = RatchetBackup.decryptImport(pass.toCharArray(), blob.trim())
            MessageCipher(app).importAllRatchetsJson(json)
            updateState {
                it.copy(
                    showRatchetBackupDialog = false,
                    ratchetBackupPassphrase = "",
                )
            }
            AppSnackbarBus.show(appStr(R.string.snackbar_ratchet_restored, emptyArray()))
            true
        } catch (e: Exception) {
            setUserError(UserErrorMapping.fromThrowable(e))
            false
        }
    }

    private fun validateBackupPassphrase(pass: String): Boolean {
        if (!isPassphraseValid(pass)) {
            setUserErrorKind(UserErrorKind.Generic, appStr(R.string.error_backup_passphrase, emptyArray()))
            return false
        }
        return true
    }

    private fun shouldConfirmAccountOverwrite(): Boolean {
        if (identityStore.hasAccountData()) return true
        if (prefs.getServerUrl() != null) return true
        return MessageCipher(app).exportAllRatchetsJson().length() > 0
    }

    private fun buildAccountExportPayload(): org.json.JSONObject {
        val server = getState().serverUrl.takeIf { it.isNotBlank() }
            ?: prefs.getServerUrl()
            .orEmpty()
        return org.json.JSONObject().apply {
            put("identity", identityStore.exportSnapshotJson())
            put("ratchets", MessageCipher(app).exportAllRatchetsJson())
            if (server.isNotBlank()) put("serverUrl", ServerUrl.normalize(server))
            put("tlsPins", TlsPinStore.exportAll(app))
        }
    }

    private fun buildAccountExportBlob(pass: String): String? {
        if (!validateBackupPassphrase(pass)) return null
        return AccountBackup.encryptExport(pass.toCharArray(), buildAccountExportPayload())
    }

    private fun beginAccountImport(blob: String, passphrase: String): Boolean {
        return try {
            AccountBackup.decryptImport(passphrase.toCharArray(), blob)
            if (shouldConfirmAccountOverwrite()) {
                pendingAccountImportBlob = blob
                updateState { it.copy(showAccountImportOverwriteDialog = true) }
                true
            } else {
                applyAccountImport(blob, passphrase)
                true
            }
        } catch (e: Exception) {
            setUserError(UserErrorMapping.fromThrowable(e))
            false
        }
    }

    private fun applyAccountImport(blob: String, passphrase: String) {
        scope.launch {
            try {
                val payload = AccountBackup.decryptImport(passphrase.toCharArray(), blob)
                identityStore.importSnapshotJson(payload.getJSONObject("identity"))
                MessageCipher(app).importAllRatchetsJson(payload.getJSONObject("ratchets"))
                payload.optString("serverUrl").takeIf { it.isNotBlank() }?.let { url ->
                    val normalized = ServerUrl.normalize(url)
                    prefs.setServerUrl(normalized)
                    prefs.setServerTested(true)
                    updateState { it.copy(serverUrl = normalized) }
                }
                if (payload.has("tlsPins")) {
                    TlsPinStore.importAll(app, payload.getJSONObject("tlsPins"))
                }
                if (!isSetupIncomplete(identityStore.getContacts())) {
                    prefs.setOnboardingDismissed(true)
                }
                reloadAccountUi()
                updateState {
                    it.copy(
                        showAccountBackupDialog = false,
                        accountBackupPassphrase = "",
                        tlsPinned = RelayHttpClient.hasPin(app, getState().serverUrl),
                    )
                }
                migrationActions.showMigrationImportChecklist()
            } catch (e: Exception) {
                setUserError(UserErrorMapping.fromThrowable(e))
            }
        }
    }

    private fun buildRatchetExportBlob(pass: String): String? {
        if (!validateBackupPassphrase(pass)) return null
        val cipher = MessageCipher(app)
        return RatchetBackup.encryptExport(pass.toCharArray(), cipher.exportAllRatchetsJson())
    }

    companion object {
        const val MIN_PASSPHRASE_LENGTH = 8

        fun isPassphraseValid(pass: String): Boolean = pass.length >= MIN_PASSPHRASE_LENGTH
    }
}
