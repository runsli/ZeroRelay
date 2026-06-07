package app.zerorelay.ui.onboarding

import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.zerorelay.R
import app.zerorelay.ui.components.ZeroRelayAppBar
import app.zerorelay.ui.home.HomeUiState
import app.zerorelay.ui.home.HomeViewModel
import app.zerorelay.ui.home.OnboardingStep
import app.zerorelay.ui.theme.InputFieldShape
import app.zerorelay.ui.util.generateQrBitmap

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    state: HomeUiState,
    viewModel: HomeViewModel,
    onScanQr: () -> Unit,
    onPasteInvite: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
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

    val stepIndex = when (state.onboardingStep) {
        OnboardingStep.Server -> 0
        OnboardingStep.Identity -> 1
        OnboardingStep.AddContact -> 2
    }
    val progress = (stepIndex + 1) / 3f

    Scaffold(
        modifier = modifier,
        topBar = {
            ZeroRelayAppBar(
                title = stringResource(R.string.onboarding_title),
                actions = {
                    TextButton(onClick = {
                        viewModel.skipOnboarding()
                        onFinish()
                    }) {
                        Text(stringResource(R.string.onboarding_skip))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
            )
            Text(
                text = when (state.onboardingStep) {
                    OnboardingStep.Server -> stringResource(R.string.onboarding_step_server_title)
                    OnboardingStep.Identity -> stringResource(R.string.onboarding_step_identity_title)
                    OnboardingStep.AddContact -> stringResource(R.string.onboarding_step_contact_title)
                },
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = when (state.onboardingStep) {
                    OnboardingStep.Server -> stringResource(R.string.onboarding_step_server_body)
                    OnboardingStep.Identity -> stringResource(R.string.onboarding_step_identity_body)
                    OnboardingStep.AddContact -> stringResource(R.string.onboarding_step_contact_body)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))

            state.error?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }

            when (state.onboardingStep) {
                OnboardingStep.Server -> OnboardingServerStep(state, viewModel)
                OnboardingStep.Identity -> OnboardingIdentityStep(state)
                OnboardingStep.AddContact -> OnboardingAddContactStep(
                    onScanQr = {
                        viewModel.skipOnboarding()
                        onFinish()
                        onScanQr()
                    },
                    onPasteInvite = {
                        viewModel.skipOnboarding()
                        onFinish()
                        onPasteInvite()
                    },
                    onSkip = {
                        viewModel.skipOnboarding()
                        onFinish()
                    },
                )
            }

            Spacer(Modifier.height(24.dp))
            if (state.onboardingStep != OnboardingStep.AddContact) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    val canAdvance = when (state.onboardingStep) {
                        OnboardingStep.Server -> state.serverCheckOk == true
                        OnboardingStep.Identity -> true
                        OnboardingStep.AddContact -> false
                    }
                    Button(
                        onClick = viewModel::advanceOnboardingStep,
                        enabled = canAdvance,
                    ) {
                        Text(stringResource(R.string.onboarding_next))
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun OnboardingServerStep(
    state: HomeUiState,
    viewModel: HomeViewModel,
) {
    OutlinedTextField(
        value = state.serverUrl,
        onValueChange = viewModel::onServerUrlChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.settings_server_label)) },
        placeholder = { Text(stringResource(R.string.settings_server_placeholder)) },
        singleLine = true,
        shape = InputFieldShape,
        supportingText = when (state.serverCheckOk) {
            true -> {
                { Text(stringResource(R.string.settings_server_ok), color = MaterialTheme.colorScheme.primary) }
            }
            false -> null
            null -> null
        },
    )
    Spacer(Modifier.height(12.dp))
    Button(
        onClick = viewModel::testServerConnection,
        enabled = !state.serverChecking && state.serverUrl.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            stringResource(
                if (state.serverChecking) R.string.settings_testing else R.string.settings_test_connection,
            ),
        )
    }
}

@Composable
private fun OnboardingIdentityStep(state: HomeUiState) {
    val identity = state.identity ?: return
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val bitmap = remember(state.myQrPayload) {
            generateQrBitmap(state.myQrPayload, 480)
        }
        Image(
            bitmap = bitmap,
            contentDescription = stringResource(R.string.cd_qr_image),
            modifier = Modifier.size(220.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.my_qr_fingerprint, identity.fingerprint),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.my_qr_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun OnboardingAddContactStep(
    onScanQr: () -> Unit,
    onPasteInvite: () -> Unit,
    onSkip: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            onClick = onScanQr,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                Icons.Default.QrCodeScanner,
                contentDescription = null,
                modifier = Modifier.padding(end = 8.dp),
            )
            Text(stringResource(R.string.home_add_scan))
        }
        OutlinedButton(
            onClick = onPasteInvite,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                Icons.Outlined.ContentPaste,
                contentDescription = null,
                modifier = Modifier.padding(end = 8.dp),
            )
            Text(stringResource(R.string.home_add_paste))
        }
        TextButton(
            onClick = onSkip,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Text(stringResource(R.string.onboarding_skip_add_contact))
        }
    }
}
