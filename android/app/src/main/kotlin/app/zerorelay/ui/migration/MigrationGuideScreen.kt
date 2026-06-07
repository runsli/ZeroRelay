package app.zerorelay.ui.migration

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.zerorelay.R
import app.zerorelay.ui.components.ZeroRelayAppBar
import java.util.Locale

object MigrationDocs {
    fun securityMigrationUrl(locale: Locale = Locale.getDefault()): String {
        val path = if (locale.language == "zh") {
            "docs/SECURITY.zh-CN.md"
        } else {
            "docs/SECURITY.md"
        }
        return "https://github.com/runsli/ZeroRelay/blob/main/$path#android-account-backup-migration"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MigrationGuideScreen(
    onBack: () -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scroll = rememberScrollState()

    Scaffold(
        modifier = modifier,
        topBar = {
            ZeroRelayAppBar(
                title = stringResource(R.string.migration_guide_title),
                onNavigateBack = onBack,
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
                text = stringResource(R.string.migration_guide_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))

            MigrationStep(
                number = 1,
                title = stringResource(R.string.migration_step_1_title),
                body = stringResource(R.string.migration_step_1_body),
                actionLabel = stringResource(R.string.migration_guide_export_action),
                onAction = onExportBackup,
            )
            MigrationStep(
                number = 2,
                title = stringResource(R.string.migration_step_2_title),
                body = stringResource(R.string.migration_step_2_body),
            )
            MigrationStep(
                number = 3,
                title = stringResource(R.string.migration_step_3_title),
                body = stringResource(R.string.migration_step_3_body),
                actionLabel = stringResource(R.string.migration_guide_import_action),
                onAction = onImportBackup,
            )
            MigrationStep(
                number = 4,
                title = stringResource(R.string.migration_step_4_title),
                body = stringResource(R.string.migration_step_4_body),
            )
            MigrationStep(
                number = 5,
                title = stringResource(R.string.migration_step_5_title),
                body = stringResource(R.string.migration_step_5_body),
            )
            MigrationStep(
                number = 6,
                title = stringResource(R.string.migration_step_6_title),
                body = stringResource(R.string.migration_step_6_body),
            )

            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(MigrationDocs.securityMigrationUrl())),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.migration_guide_security_link))
            }
        }
    }
}

@Composable
private fun MigrationStep(
    number: Int,
    title: String,
    body: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        shape = MaterialTheme.shapes.medium,
        color = cs.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = cs.primaryContainer,
                modifier = Modifier.size(32.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = number.toString(),
                        style = MaterialTheme.typography.labelLarge,
                        color = cs.onPrimaryContainer,
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    body,
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
                if (actionLabel != null && onAction != null) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onAction) {
                        Text(actionLabel)
                    }
                }
            }
        }
    }
}
