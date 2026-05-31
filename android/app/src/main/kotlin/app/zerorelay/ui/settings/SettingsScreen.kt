package app.zerorelay.ui.settings

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.zerorelay.R
import app.zerorelay.data.crypto.RatchetBackupFiles
import app.zerorelay.ui.components.ZeroRelayAppBar
import app.zerorelay.ui.home.HomeViewModel
import app.zerorelay.ui.theme.InputFieldShape

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: HomeViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    var pendingExportPassphrase by remember { mutableStateOf<String?>(null) }

    val exportBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(RatchetBackupFiles.MIME_TYPE),
    ) { uri ->
        val pass = pendingExportPassphrase
        if (uri != null && pass != null) {
            viewModel.exportRatchetBackupToUri(uri, pass)
        }
        pendingExportPassphrase = null
    }

    val importBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            viewModel.importRatchetBackupFromUri(uri, state.ratchetBackupPassphrase)
        }
    }

    state.pendingTlsPin?.let { newPin ->
        AlertDialog(
            onDismissRequest = viewModel::dismissPendingTlsPin,
            title = { Text(stringResource(R.string.tls_pin_changed_title)) },
            text = {
                Text(
                    stringResource(R.string.tls_pin_changed_body, newPin),
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::trustPendingTlsPin) {
                    Text(stringResource(R.string.action_trust_pin))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissPendingTlsPin) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (state.showRatchetBackupDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.showRatchetBackup(false) },
            title = { Text(stringResource(R.string.ratchet_backup_title)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.ratchet_backup_body),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = state.ratchetBackupPassphrase,
                        onValueChange = viewModel::onRatchetPassphraseChange,
                        label = { Text(stringResource(R.string.ratchet_passphrase_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = InputFieldShape,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = { viewModel.exportRatchetBackupToClipboard() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.ratchet_export_clipboard)) }
                    TextButton(
                        onClick = { viewModel.importRatchetBackupFromClipboard() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.ratchet_import_clipboard)) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (viewModel.prepareRatchetExport()) {
                        pendingExportPassphrase = state.ratchetBackupPassphrase
                        exportBackupLauncher.launch(RatchetBackupFiles.DEFAULT_FILENAME)
                    }
                }) { Text(stringResource(R.string.ratchet_export_file)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    importBackupLauncher.launch(arrayOf(RatchetBackupFiles.MIME_TYPE, "application/*"))
                }) { Text(stringResource(R.string.ratchet_import_file)) }
            },
        )
    }

    Scaffold(
        topBar = {
            ZeroRelayAppBar(
                title = stringResource(R.string.settings_title),
                onNavigateBack = onBack,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Text(stringResource(R.string.settings_appearance), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            SettingsSwitchRow(
                title = stringResource(R.string.settings_dynamic_color),
                subtitle = stringResource(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        R.string.settings_dynamic_color_hint_s
                    } else {
                        R.string.settings_dynamic_color_hint_legacy
                    },
                ),
                checked = state.useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                onCheckedChange = viewModel::setUseDynamicColor,
            )

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(24.dp))

            Text(stringResource(R.string.settings_relay), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.serverUrl,
                onValueChange = viewModel::onServerUrlChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.settings_server_label)) },
                placeholder = { Text(stringResource(R.string.settings_server_placeholder)) },
                singleLine = true,
                shape = InputFieldShape,
                supportingText = when (state.serverCheckOk) {
                    true -> ({
                        Text(
                            stringResource(R.string.settings_server_ok),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    })
                    false -> null
                    null -> null
                },
            )
            if (state.tlsPinned) {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.settings_tls_pinned),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = {
                    viewModel.saveServerUrl()
                    viewModel.testServerConnection()
                },
                enabled = !state.serverChecking && state.serverUrl.isNotBlank(),
            ) {
                Text(
                    stringResource(
                        if (state.serverChecking) R.string.settings_testing else R.string.settings_test_connection,
                    ),
                )
            }
            state.error?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(24.dp))

            Text(stringResource(R.string.settings_connections), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            SettingsSwitchRow(
                title = stringResource(R.string.settings_keep_alive_title),
                subtitle = stringResource(R.string.settings_keep_alive_summary),
                checked = state.keepAliveInBackground,
                enabled = true,
                onCheckedChange = viewModel::setKeepAliveInBackground,
            )

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(24.dp))

            Text(stringResource(R.string.settings_privacy), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            SettingsSwitchRow(
                title = stringResource(R.string.settings_allow_screenshots),
                subtitle = stringResource(R.string.settings_allow_screenshots_hint),
                checked = state.allowScreenshots,
                enabled = true,
                onCheckedChange = viewModel::setAllowScreenshots,
            )

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(24.dp))

            Text(stringResource(R.string.settings_security_backup), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { viewModel.showRatchetBackup(true) }) {
                Text(stringResource(R.string.settings_ratchet_backup))
            }
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}
