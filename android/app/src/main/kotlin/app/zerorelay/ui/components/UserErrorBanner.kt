package app.zerorelay.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.zerorelay.R
import app.zerorelay.ui.error.UserError
import app.zerorelay.ui.error.resolveCopy

@Composable
fun UserErrorBanner(
    error: UserError,
    onDismiss: () -> Unit,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val copy = error.resolveCopy()
    var expanded by rememberSaveable(error) { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    val hasDetail = !copy.detail.isNullOrBlank()

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = cs.errorContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(
                text = copy.title,
                style = MaterialTheme.typography.titleSmall,
                color = cs.onErrorContainer,
            )
            if (copy.action != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = copy.action,
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onErrorContainer,
                )
            }
            if (hasDetail) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded }
                        .padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.user_error_show_details),
                        style = MaterialTheme.typography.labelMedium,
                        color = cs.onErrorContainer,
                    )
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = cs.onErrorContainer,
                    )
                }
                AnimatedVisibility(
                    visible = expanded,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    Text(
                        text = copy.detail.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onErrorContainer.copy(alpha = 0.85f),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onAction != null && copy.action != null) {
                    TextButton(onClick = onAction) {
                        Text(stringResource(R.string.user_error_try_action))
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_dismiss))
                }
            }
        }
    }
}
