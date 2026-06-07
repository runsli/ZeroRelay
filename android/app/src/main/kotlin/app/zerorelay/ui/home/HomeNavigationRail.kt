package app.zerorelay.ui.home

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.zerorelay.R

@Composable
fun HomeNavigationRail(
    selectedTab: HomeTab,
    onSelectTab: (HomeTab) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationRail(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        NavigationRailItem(
            selected = selectedTab == HomeTab.Conversations,
            onClick = { onSelectTab(HomeTab.Conversations) },
            icon = {
                Icon(
                    Icons.Default.Forum,
                    contentDescription = stringResource(R.string.home_tab_conversations),
                )
            },
            label = { Text(stringResource(R.string.home_tab_conversations)) },
        )
        NavigationRailItem(
            selected = selectedTab == HomeTab.Contacts,
            onClick = { onSelectTab(HomeTab.Contacts) },
            icon = {
                Icon(
                    Icons.Default.Person,
                    contentDescription = stringResource(R.string.home_tab_contacts),
                )
            },
            label = { Text(stringResource(R.string.home_tab_contacts)) },
        )
        NavigationRailItem(
            selected = selectedTab == HomeTab.Groups,
            onClick = { onSelectTab(HomeTab.Groups) },
            icon = {
                Icon(
                    Icons.Default.Group,
                    contentDescription = stringResource(R.string.home_tab_groups),
                )
            },
            label = { Text(stringResource(R.string.home_tab_groups)) },
        )
        NavigationRailItem(
            selected = false,
            onClick = onOpenSettings,
            icon = {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = stringResource(R.string.cd_settings),
                )
            },
            label = { Text(stringResource(R.string.settings_title)) },
        )
    }
}
