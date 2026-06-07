package app.zerorelay.ui.migration

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.zerorelay.R
import app.zerorelay.ui.components.ZeroRelayAppBar
import app.zerorelay.ui.home.HomeUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MigrationImportChecklistScreen(
    state: HomeUiState,
    onDismiss: () -> Unit,
    onTestConnection: () -> Unit,
    onTrustTls: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val contactCount = state.contacts.size
    val groupCount = state.groups.size
    val unverifiedCount = state.contacts.count { !it.verified }
    val connectionOk = state.serverCheckOk == true
    val connectionFailed = state.serverCheckOk == false
    val tlsPending = state.pendingTlsPin != null
    val scroll = rememberScrollState()

    Scaffold(
        modifier = modifier,
        topBar = {
            ZeroRelayAppBar(
                title = stringResource(R.string.migration_checklist_title),
                onNavigateBack = onDismiss,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scroll)
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.migration_checklist_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            if (tlsPending) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            stringResource(R.string.migration_checklist_tls_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = onTrustTls, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.action_trust_pin))
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            ChecklistRow(
                done = connectionOk && !tlsPending,
                warning = connectionFailed || tlsPending,
                title = when {
                    state.serverChecking -> stringResource(R.string.migration_checklist_connection_pending)
                    connectionOk && !tlsPending -> stringResource(R.string.migration_checklist_connection_ok)
                    connectionFailed -> stringResource(R.string.migration_checklist_connection_failed)
                    else -> stringResource(R.string.migration_checklist_connection)
                },
                subtitle = state.serverUrl.takeIf { it.isNotBlank() },
            )
            OutlinedButton(
                onClick = onTestConnection,
                enabled = !state.serverChecking && state.serverUrl.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        if (state.serverChecking) {
                            R.string.settings_testing
                        } else {
                            R.string.migration_checklist_test_connection
                        },
                    ),
                )
            }

            Spacer(Modifier.height(12.dp))
            ChecklistRow(
                done = contactCount > 0,
                title = stringResource(R.string.migration_checklist_contacts, contactCount),
            )
            ChecklistRow(
                done = true,
                title = stringResource(R.string.migration_checklist_groups, groupCount),
            )

            if (unverifiedCount > 0) {
                ChecklistRow(
                    done = false,
                    warning = true,
                    title = stringResource(R.string.migration_checklist_unverified, unverifiedCount),
                    subtitle = stringResource(R.string.migration_checklist_unverified_hint),
                )
            }

            Spacer(Modifier.height(8.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        stringResource(R.string.migration_checklist_test_message),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.migration_checklist_test_message_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.migration_checklist_done))
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.migration_checklist_later))
            }
        }
    }
}

@Composable
private fun ChecklistRow(
    done: Boolean,
    title: String,
    subtitle: String? = null,
    warning: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    val icon = when {
        done -> Icons.Default.CheckCircle
        warning -> Icons.Default.Warning
        else -> Icons.Default.RadioButtonUnchecked
    }
    val tint = when {
        done -> cs.primary
        warning -> cs.error
        else -> cs.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, contentDescription = null, tint = tint)
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
            }
        }
    }
}
