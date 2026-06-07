package app.zerorelay.ui.settings

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.zerorelay.R
import app.zerorelay.data.crypto.AccountBackupFiles
import app.zerorelay.data.crypto.RatchetBackupFiles
import app.zerorelay.ui.components.ZeroRelayAppBar
import app.zerorelay.ui.home.HomeViewModel
import app.zerorelay.ui.theme.InputFieldShape
import app.zerorelay.ui.util.BatteryOptimizationHelper

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: HomeViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var pendingAccountExportPassphrase by remember { mutableStateOf<String?>(null) }
    var pendingRatchetExportPassphrase by remember { mutableStateOf<String?>(null) }
    var showClearMessagesDialog by remember { mutableStateOf(false) }

    val batteryOptLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        viewModel.refreshBatteryOptimizationStatus()
    }

    val exportAccountLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(AccountBackupFiles.MIME_TYPE),
    ) { uri ->
        val pass = pendingAccountExportPassphrase
        if (uri != null && pass != null) {
            viewModel.exportAccountBackupToUri(uri, pass)
        }
        pendingAccountExportPassphrase = null
    }

    val importAccountLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            viewModel.importAccountBackupFromUri(uri, state.accountBackupPassphrase)
        }
    }

    val exportRatchetLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(RatchetBackupFiles.MIME_TYPE),
    ) { uri ->
        val pass = pendingRatchetExportPassphrase
        if (uri != null && pass != null) {
            viewModel.exportRatchetBackupToUri(uri, pass)
        }
        pendingRatchetExportPassphrase = null
    }

    val importRatchetLauncher = rememberLauncherForActivityResult(
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

    if (state.showAccountImportOverwriteDialog) {
        AlertDialog(
            onDismissRequest = viewModel::dismissAccountImportOverwrite,
            title = { Text(stringResource(R.string.account_backup_overwrite_title)) },
            text = { Text(stringResource(R.string.account_backup_overwrite_body)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmAccountImportOverwrite) {
                    Text(stringResource(R.string.action_import))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissAccountImportOverwrite) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (state.showAccountBackupDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.showAccountBackup(false) },
            title = { Text(stringResource(R.string.account_backup_title)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.account_backup_body),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = state.accountBackupPassphrase,
                        onValueChange = viewModel::onAccountPassphraseChange,
                        label = { Text(stringResource(R.string.account_backup_passphrase_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = InputFieldShape,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (viewModel.prepareAccountExport()) {
                        pendingAccountExportPassphrase = state.accountBackupPassphrase
                        exportAccountLauncher.launch(AccountBackupFiles.DEFAULT_FILENAME)
                    }
                }) { Text(stringResource(R.string.account_backup_export_file)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    importAccountLauncher.launch(arrayOf(AccountBackupFiles.MIME_TYPE, "application/*"))
                }) { Text(stringResource(R.string.account_backup_import_file)) }
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
                    if (state.showRatchetAdvanced) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.settings_ratchet_advanced_warning),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(4.dp))
                        TextButton(
                            onClick = { viewModel.exportRatchetBackupToClipboard() },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.ratchet_export_clipboard)) }
                        TextButton(
                            onClick = { viewModel.importRatchetBackupFromClipboard() },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.ratchet_import_clipboard)) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (viewModel.prepareRatchetExport()) {
                        pendingRatchetExportPassphrase = state.ratchetBackupPassphrase
                        exportRatchetLauncher.launch(RatchetBackupFiles.DEFAULT_FILENAME)
                    }
                }) { Text(stringResource(R.string.ratchet_export_file)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    importRatchetLauncher.launch(arrayOf(RatchetBackupFiles.MIME_TYPE, "application/*"))
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
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.settings_max_background_sessions_summary, state.maxBackgroundSessions),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (count in 1..5) {
                    TextButton(
                        onClick = { viewModel.setMaxBackgroundSessions(count) },
                        enabled = state.maxBackgroundSessions != count,
                    ) {
                        Text(stringResource(R.string.settings_max_background_sessions_value, count))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            if (state.batteryOptimizationIgnored) {
                Text(
                    stringResource(R.string.settings_battery_optimization_done),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Column {
                    Text(
                        stringResource(R.string.settings_battery_optimization_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick = {
                            val intent = BatteryOptimizationHelper.createRequestIntent(context)
                                ?: BatteryOptimizationHelper.createSettingsIntent()
                            batteryOptLauncher.launch(intent)
                        },
                    ) {
                        Text(stringResource(R.string.settings_battery_optimization_action))
                    }
                }
            }

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

            Text(stringResource(R.string.settings_data), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.settings_clear_messages_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { showClearMessagesDialog = true }) {
                Text(stringResource(R.string.settings_clear_messages))
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(24.dp))

            Text(stringResource(R.string.settings_security_backup), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.settings_account_backup_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { viewModel.showAccountBackup(true) }) {
                Text(stringResource(R.string.settings_account_backup))
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = {
                viewModel.setRatchetAdvanced(false)
                viewModel.showRatchetBackup(true)
            }) {
                Text(stringResource(R.string.settings_ratchet_backup))
            }
            TextButton(onClick = { viewModel.setRatchetAdvanced(true); viewModel.showRatchetBackup(true) }) {
                Text(stringResource(R.string.ratchet_export_clipboard))
            }
        }
    }

    if (showClearMessagesDialog) {
        AlertDialog(
            onDismissRequest = { showClearMessagesDialog = false },
            title = { Text(stringResource(R.string.settings_clear_messages_confirm_title)) },
            text = { Text(stringResource(R.string.settings_clear_messages_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearMessagesDialog = false
                        viewModel.clearAllLocalMessages()
                    },
                ) {
                    Text(stringResource(R.string.action_clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearMessagesDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
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
