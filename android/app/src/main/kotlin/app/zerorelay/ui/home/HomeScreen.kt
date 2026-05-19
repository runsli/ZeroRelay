package app.zerorelay.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import app.zerorelay.R
import app.zerorelay.ui.components.ZeroRelayAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import app.zerorelay.BuildConfig
import app.zerorelay.ui.notification.ChatNotificationHelper
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.zerorelay.data.model.ChatGroup
import app.zerorelay.data.network.ServerUrl
import app.zerorelay.data.model.ChatSession
import app.zerorelay.data.model.Contact
import app.zerorelay.ui.theme.CardShape
import app.zerorelay.ui.theme.InputFieldShape
import app.zerorelay.ui.util.generateQrBitmap
import app.zerorelay.ui.util.rememberAppWidthClass
import app.zerorelay.ui.util.useNavigationRail

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenChat: (ChatSession) -> Unit,
    onScanQr: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val widthClass = rememberAppWidthClass()
    val useRail = widthClass.useNavigationRail
    val context = LocalContext.current
    val notifyPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    LaunchedEffect(state.identity) {
        if (state.identity == null) return@LaunchedEffect
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !ChatNotificationHelper.canPost(context)
        ) {
            notifyPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    var showAddSheet by remember { mutableStateOf(false) }
    val addSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun openContactChat(contact: Contact) {
        if (!contact.verified) {
            viewModel.showVerifyContactDialog(contact)
        } else {
            viewModel.createSession(contact)?.let(onOpenChat)
        }
    }

    val identity = state.identity
    if (state.showMyQr && identity != null) {
        AlertDialog(
            onDismissRequest = viewModel::closeMyQr,
            title = { Text(stringResource(R.string.my_qr_title)) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val bitmap = remember(state.myQrPayload) {
                        generateQrBitmap(state.myQrPayload, 480)
                    }
                    Image(
                        bitmap = bitmap,
                        contentDescription = stringResource(R.string.cd_qr_image),
                        modifier = Modifier.size(240.dp),
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
            },
            confirmButton = {
                TextButton(onClick = viewModel::closeMyQr) {
                    Text(stringResource(R.string.action_close))
                }
            },
        )
    }

    state.inviteGroup?.let { group ->
        val payload = remember(group.id, state.serverUrl) { viewModel.groupInvitePayload(group) }
        val clipboard = LocalClipboardManager.current
        val context = LocalContext.current
        AlertDialog(
            onDismissRequest = viewModel::closeGroupInvite,
            title = { Text(stringResource(R.string.group_invite_title)) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = group.displayName,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    val bitmap = remember(payload) { generateQrBitmap(payload, 480) }
                    Image(
                        bitmap = bitmap,
                        contentDescription = stringResource(R.string.cd_qr_image),
                        modifier = Modifier.size(240.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.group_invite_scan_hint, group.displayName),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (ServerUrl.isLocalDevUrl(state.serverUrl)) {
                        Text(
                            text = stringResource(R.string.group_invite_need_https),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.group_invite_server, state.serverUrl),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = stringResource(R.string.group_invite_note),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = { viewModel.rotateGroupKey(group.id) }) {
                        Text(stringResource(R.string.action_rotate_key))
                    }
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(payload))
                    }) { Text(stringResource(R.string.action_copy_link)) }
                    TextButton(onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, payload)
                            }.let {
                                Intent.createChooser(it, context.getString(R.string.share_group_invite_chooser))
                            },
                        )
                    }) { Text(stringResource(R.string.action_share)) }
                    TextButton(onClick = viewModel::closeGroupInvite) {
                        Text(stringResource(R.string.action_close))
                    }
                }
            },
        )
    }

    if (state.showCreateGroup) {
        AlertDialog(
            onDismissRequest = viewModel::closeCreateGroup,
            title = { Text(stringResource(R.string.create_group_title)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = state.createGroupName,
                        onValueChange = viewModel::onCreateGroupNameChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.create_group_name_label)) },
                        singleLine = true,
                        shape = InputFieldShape,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.create_group_members_hint),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(
                        modifier = Modifier
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        if (state.contacts.isEmpty()) {
                            Text(
                                text = stringResource(R.string.create_group_no_contacts),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            state.contacts.forEach { contact ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { viewModel.toggleCreateGroupMember(contact.id) },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(
                                        checked = contact.id in state.createGroupMemberIds,
                                        onCheckedChange = { viewModel.toggleCreateGroupMember(contact.id) },
                                    )
                                    Column {
                                        Text(contact.displayName, style = MaterialTheme.typography.bodyLarge)
                                        if (!contact.verified) {
                                            Text(
                                                stringResource(R.string.home_label_unverified_short),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.error,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    val unverifiedMembers = viewModel.unverifiedCreateGroupMembers()
                    if (unverifiedMembers.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(
                                R.string.home_create_group_warning,
                                unverifiedMembers.size,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmCreateGroup() }) {
                    Text(stringResource(R.string.action_create))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::closeCreateGroup) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    state.verifyContactDialog?.let { contact ->
        AlertDialog(
            onDismissRequest = viewModel::dismissVerifyContactDialog,
            title = { Text(stringResource(R.string.verify_contact_title)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.verify_contact_body),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.verify_contact_fingerprint_label),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        contact.fingerprint,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (!contact.displayName.equals(contact.fingerprint, ignoreCase = true)) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.verify_contact_display_name, contact.displayName),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.markVerifiedAndCreateSession(contact.id)?.let(onOpenChat)
                }) { Text(stringResource(R.string.verify_contact_confirm)) }
            },
            dismissButton = if (BuildConfig.DEBUG) {
                {
                    TextButton(onClick = {
                        viewModel.createSessionAllowingUnverified(contact)?.let(onOpenChat)
                    }) { Text(stringResource(R.string.verify_contact_later)) }
                }
            } else {
                {
                    TextButton(onClick = viewModel::dismissVerifyContactDialog) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            },
        )
    }

    if (state.showPasteDialog) {
        AlertDialog(
            onDismissRequest = viewModel::closePasteDialog,
            title = { Text(stringResource(R.string.paste_invite_title)) },
            text = {
                OutlinedTextField(
                    value = state.pasteText,
                    onValueChange = viewModel::onPasteTextChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.paste_invite_placeholder)) },
                    minLines = 3,
                    shape = InputFieldShape,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    when (val result = viewModel.addFromPaste()) {
                        is PasteResult.GroupJoined -> onOpenChat(result.session)
                        is PasteResult.ContactAdded -> viewModel.closePasteDialog()
                        is PasteResult.Failed -> Unit
                    }
                }) { Text(stringResource(R.string.action_add)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::closePasteDialog) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (showAddSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAddSheet = false },
            sheetState = addSheetState,
        ) {
            HomeAddSheetItem(
                icon = { Icon(Icons.Default.QrCode, contentDescription = stringResource(R.string.cd_my_qr)) },
                label = stringResource(R.string.home_add_my_qr),
                onClick = {
                    showAddSheet = false
                    viewModel.openMyQr()
                },
            )
            HomeAddSheetItem(
                icon = { Icon(Icons.Default.QrCodeScanner, contentDescription = stringResource(R.string.cd_scan_qr)) },
                label = stringResource(R.string.home_add_scan),
                onClick = {
                    showAddSheet = false
                    onScanQr()
                },
            )
            HomeAddSheetItem(
                icon = { Icon(Icons.Outlined.ContentPaste, contentDescription = stringResource(R.string.cd_paste_invite)) },
                label = stringResource(R.string.home_add_paste),
                onClick = {
                    showAddSheet = false
                    viewModel.openPasteDialog()
                },
            )
            HomeAddSheetItem(
                icon = { Icon(Icons.Default.Add, contentDescription = stringResource(R.string.cd_create_group)) },
                label = stringResource(R.string.home_add_create_group),
                onClick = {
                    showAddSheet = false
                    viewModel.openCreateGroup()
                },
            )
            Spacer(Modifier.height(16.dp))
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            if (!useRail) {
                ZeroRelayAppBar(
                    title = stringResource(R.string.app_name),
                    actions = {
                        IconButton(onClick = onOpenSettings) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = stringResource(R.string.cd_settings),
                            )
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddSheet = true }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.cd_add))
            }
        },
    ) { padding ->
        if (useRail) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                HomeNavigationRail(
                    selectedTab = state.selectedTab,
                    onSelectTab = viewModel::selectTab,
                    onOpenSettings = onOpenSettings,
                )
                HomeMainList(
                    state = state,
                    openContactChat = { openContactChat(it) },
                    onOpenChat = onOpenChat,
                    viewModel = viewModel,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(horizontal = 20.dp),
                    showTitle = true,
                )
            }
        } else {
            HomeMainList(
                state = state,
                openContactChat = { openContactChat(it) },
                onOpenChat = onOpenChat,
                viewModel = viewModel,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 20.dp),
                showTitle = false,
                showTabs = true,
                onSelectTab = viewModel::selectTab,
            )
        }
    }
}

@Composable
private fun HomeMainList(
    state: HomeUiState,
    openContactChat: (Contact) -> Unit,
    onOpenChat: (ChatSession) -> Unit,
    viewModel: HomeViewModel,
    modifier: Modifier = Modifier,
    showTitle: Boolean = false,
    showTabs: Boolean = false,
    onSelectTab: ((HomeTab) -> Unit)? = null,
) {
    Column(modifier = modifier) {
        if (showTitle) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
            )
        }
        state.error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        if (showTabs && onSelectTab != null) {
            PrimaryTabRow(selectedTabIndex = state.selectedTab.ordinal) {
                Tab(
                    selected = state.selectedTab == HomeTab.Contacts,
                    onClick = { onSelectTab(HomeTab.Contacts) },
                    text = { Text(stringResource(R.string.home_tab_contacts)) },
                )
                Tab(
                    selected = state.selectedTab == HomeTab.Groups,
                    onClick = { onSelectTab(HomeTab.Groups) },
                    text = { Text(stringResource(R.string.home_tab_groups)) },
                )
            }
            Spacer(Modifier.height(12.dp))
        }
        when (state.selectedTab) {
            HomeTab.Contacts -> ContactsTab(
                contacts = state.contacts,
                onOpenChat = { openContactChat(it) },
                onDelete = viewModel::deleteContact,
                onVerify = viewModel::markContactVerified,
            )
            HomeTab.Groups -> GroupsTab(
                groups = state.groups,
                contacts = state.contacts,
                onOpenChat = { viewModel.createGroupSession(it)?.let(onOpenChat) },
                onDelete = viewModel::deleteGroup,
                onShowInvite = viewModel::showGroupInvite,
            )
        }
    }
}

@Composable
private fun HomeAddSheetItem(
    icon: @Composable () -> Unit,
    label: String,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = icon,
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun ContactsTab(
    contacts: List<Contact>,
    onOpenChat: (Contact) -> Unit,
    onDelete: (String) -> Unit,
    onVerify: (String) -> Unit,
) {
    if (contacts.isEmpty()) {
        EmptyHint(
            title = stringResource(R.string.home_empty_contacts_title),
            subtitle = stringResource(R.string.home_empty_contacts_subtitle),
        )
    } else {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(contacts, key = { it.id }) { contact ->
                ContactRow(
                    contact = contact,
                    onClick = { onOpenChat(contact) },
                    onDelete = { onDelete(contact.id) },
                    onVerify = { onVerify(contact.id) },
                )
            }
        }
    }
}

@Composable
private fun GroupsTab(
    groups: List<ChatGroup>,
    contacts: List<Contact>,
    onOpenChat: (ChatGroup) -> Unit,
    onDelete: (String) -> Unit,
    onShowInvite: (ChatGroup) -> Unit,
) {
    if (groups.isEmpty()) {
        EmptyHint(
            title = stringResource(R.string.home_empty_groups_title),
            subtitle = stringResource(R.string.home_empty_groups_subtitle),
        )
    } else {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(groups, key = { it.id }) { group ->
                GroupRow(
                    group = group,
                    contacts = contacts,
                    onClick = { onOpenChat(group) },
                    onInvite = { onShowInvite(group) },
                    onDelete = { onDelete(group.id) },
                )
            }
        }
    }
}

@Composable
private fun EmptyHint(title: String, subtitle: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HomeListOverflowMenu(
    items: List<Pair<String, () -> Unit>>,
    destructiveLast: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.cd_more))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            items.forEachIndexed { index, (label, action) ->
                val isDestructive = destructiveLast && index == items.lastIndex
                DropdownMenuItem(
                    text = {
                        Text(
                            text = label,
                            color = if (isDestructive) cs.error else cs.onSurface,
                        )
                    },
                    onClick = {
                        expanded = false
                        action()
                    },
                )
            }
        }
    }
}

@Composable
private fun ContactRow(
    contact: Contact,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onVerify: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val markVerifiedLabel = stringResource(R.string.home_menu_mark_verified)
    val deleteContactLabel = stringResource(R.string.home_menu_delete_contact)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = CardShape,
        color = cs.surfaceContainerLow,
    ) {
        ListItem(
            headlineContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = contact.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (!contact.verified) {
                        Spacer(Modifier.size(8.dp))
                        Badge(containerColor = cs.errorContainer) {
                            Text(
                                stringResource(R.string.home_badge_unverified),
                                style = MaterialTheme.typography.labelSmall,
                                color = cs.onErrorContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            },
            supportingContent = {
                Text(
                    text = if (contact.verified) {
                        stringResource(R.string.home_contact_verified)
                    } else {
                        stringResource(R.string.home_contact_unverified_hint)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (contact.verified) cs.primary else cs.onSurfaceVariant,
                )
            },
            trailingContent = {
                HomeListOverflowMenu(
                    items = buildList {
                        if (!contact.verified) add(markVerifiedLabel to onVerify)
                        add(deleteContactLabel to onDelete)
                    },
                    destructiveLast = true,
                )
            },
            colors = ListItemDefaults.colors(containerColor = cs.surfaceContainerLow),
        )
    }
}

@Composable
private fun GroupRow(
    group: ChatGroup,
    contacts: List<Contact>,
    onClick: () -> Unit,
    onInvite: () -> Unit,
    onDelete: () -> Unit,
) {
    val memberLabel = if (group.memberContactIds.isEmpty()) {
        stringResource(R.string.home_group_members_join)
    } else {
        group.memberContactIds.mapNotNull { id -> contacts.find { it.id == id }?.displayName }
            .joinToString("、")
            .ifBlank { stringResource(R.string.home_group_member_count, group.memberContactIds.size) }
    }
    val cs = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = CardShape,
        color = cs.surfaceContainerLow,
    ) {
        ListItem(
            headlineContent = {
                Text(
                    text = group.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            supportingContent = {
                Text(
                    text = memberLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            leadingContent = {
                Icon(
                    Icons.Default.Group,
                    contentDescription = stringResource(R.string.home_tab_groups),
                    tint = cs.primary,
                )
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onInvite) {
                        Icon(Icons.Default.QrCode, contentDescription = stringResource(R.string.cd_group_invite_qr))
                    }
                    HomeListOverflowMenu(
                        items = listOf(stringResource(R.string.home_menu_delete_group) to onDelete),
                        destructiveLast = true,
                    )
                }
            },
            colors = ListItemDefaults.colors(containerColor = cs.surfaceContainerLow),
        )
    }
}
