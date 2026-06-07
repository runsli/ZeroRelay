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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import app.zerorelay.R
import app.zerorelay.ui.components.ZeroRelayAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import app.zerorelay.ui.SafetyNumberOrigin
import app.zerorelay.ui.notification.ChatNotificationHelper
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import app.zerorelay.data.model.ChatGroup
import app.zerorelay.data.network.ServerUrl
import app.zerorelay.data.model.ChatSession
import app.zerorelay.ui.home.RelayStatusBarState
import app.zerorelay.data.model.Contact
import app.zerorelay.ui.safety.SafetyNumberFormat
import app.zerorelay.ui.theme.CardShape
import app.zerorelay.ui.util.BatteryOptimizationHelper
import app.zerorelay.ui.theme.InputFieldShape
import app.zerorelay.ui.util.generateQrBitmap
import app.zerorelay.ui.util.rememberAppWidthClass
import app.zerorelay.ui.util.useNavigationRail

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenChat: (ChatSession) -> Unit,
    onOpenSafetyNumber: (Contact, SafetyNumberOrigin) -> Unit,
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
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshBatteryOptimizationStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var showAddSheet by remember { mutableStateOf(false) }
    val addSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun openContactChat(contact: Contact) {
        if (!contact.verified) {
            onOpenSafetyNumber(contact, SafetyNumberOrigin.HomeList)
        } else {
            viewModel.createSession(contact)?.let(onOpenChat)
        }
    }

    fun openConversation(row: ConversationRowUi) {
        if (row.peerNeedsVerification) {
            viewModel.contactForConversation(row)?.let { contact ->
                onOpenSafetyNumber(contact, SafetyNumberOrigin.HomeList)
            } ?: viewModel.openConversation(row.roomId)?.let(onOpenChat)
        } else {
            viewModel.openConversation(row.roomId)?.let(onOpenChat)
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
                        text = stringResource(
                            R.string.my_qr_fingerprint,
                            SafetyNumberFormat.displayText(identity.fingerprint),
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
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

    state.nicknameDialogContact?.let { contact ->
        AlertDialog(
            onDismissRequest = viewModel::skipNicknameDialog,
            title = {
                Text(
                    if (state.nicknameIsEdit) {
                        stringResource(R.string.contact_nickname_dialog_title_edit)
                    } else {
                        stringResource(R.string.contact_nickname_dialog_title)
                    },
                )
            },
            text = {
                Column {
                    if (!state.nicknameIsEdit) {
                        Text(
                            stringResource(R.string.contact_nickname_dialog_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                    OutlinedTextField(
                        value = state.nicknameDraft,
                        onValueChange = viewModel::onNicknameDraftChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.contact_nickname_hint)) },
                        singleLine = true,
                        shape = InputFieldShape,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmNicknameDialog) {
                    Text(stringResource(R.string.contact_nickname_save))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::skipNicknameDialog) {
                    Text(
                        if (state.nicknameIsEdit) {
                            stringResource(R.string.action_cancel)
                        } else {
                            stringResource(R.string.contact_nickname_skip)
                        },
                    )
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
                    onOpenConversation = { openConversation(it) },
                    onOpenChat = onOpenChat,
                    onOpenSafetyNumber = onOpenSafetyNumber,
                    viewModel = viewModel,
                    onScanQr = onScanQr,
                    onOpenSettings = onOpenSettings,
                    onShowAddSheet = { showAddSheet = true },
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
                onOpenConversation = { openConversation(it) },
                onOpenChat = onOpenChat,
                onOpenSafetyNumber = onOpenSafetyNumber,
                viewModel = viewModel,
                onScanQr = onScanQr,
                onOpenSettings = onOpenSettings,
                onShowAddSheet = { showAddSheet = true },
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
    onOpenConversation: (ConversationRowUi) -> Unit,
    onOpenChat: (ChatSession) -> Unit,
    onOpenSafetyNumber: (Contact, SafetyNumberOrigin) -> Unit,
    viewModel: HomeViewModel,
    onScanQr: () -> Unit,
    onOpenSettings: () -> Unit,
    onShowAddSheet: () -> Unit,
    modifier: Modifier = Modifier,
    showTitle: Boolean = false,
    showTabs: Boolean = false,
    onSelectTab: ((HomeTab) -> Unit)? = null,
) {
    val context = LocalContext.current
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
        RelayStatusBar(
            status = state.relayStatusBar,
            hostLabel = state.relayHostLabel,
            onConfigure = {
                if (state.showSetupContinueBanner) {
                    viewModel.reopenOnboarding()
                } else {
                    onOpenSettings()
                }
            },
            onRetry = viewModel::retryRelayStatusBar,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        if (state.showSetupContinueBanner) {
            SetupContinueBanner(
                onContinue = viewModel::reopenOnboarding,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }
        if (state.detachedSessionCount > 0) {
            DetachedConnectionBanner(
                sessionCount = state.detachedSessionCount,
                chatName = state.detachedChatName,
                showBatteryHint = !state.batteryOptimizationIgnored,
                onOpenChat = {
                    if (state.detachedSessionCount == 1) {
                        viewModel.detachedSessionForChat()?.let(onOpenChat)
                    } else {
                        viewModel.selectTab(HomeTab.Conversations)
                    }
                },
                onBatterySettings = {
                    BatteryOptimizationHelper.createRequestIntent(context)?.let { context.startActivity(it) }
                        ?: context.startActivity(BatteryOptimizationHelper.createSettingsIntent())
                },
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }
        HomeSearchBar(
            query = state.searchQuery,
            active = state.searchActive,
            onQueryChange = viewModel::onSearchQueryChange,
            onActiveChange = viewModel::setSearchActive,
            onClear = viewModel::clearSearch,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        if (showTabs && onSelectTab != null && !viewModel.hasSearchQuery()) {
            PrimaryTabRow(selectedTabIndex = state.selectedTab.ordinal) {
                Tab(
                    selected = state.selectedTab == HomeTab.Conversations,
                    onClick = { onSelectTab(HomeTab.Conversations) },
                    text = { Text(stringResource(R.string.home_tab_conversations)) },
                )
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
        if (viewModel.hasSearchQuery()) {
            HomeSearchResults(
                query = state.searchQuery.trim(),
                conversations = viewModel.filteredConversations(),
                contacts = viewModel.filteredContacts(),
                groups = viewModel.filteredGroups(),
                allContacts = state.contacts,
                openContactChat = openContactChat,
                onOpenConversation = onOpenConversation,
                onOpenGroupChat = { viewModel.createGroupSession(it)?.let(onOpenChat) },
                onEditNickname = viewModel::openEditNickname,
                onDeleteContact = viewModel::deleteContact,
                onOpenSafetyNumber = { contact ->
                    onOpenSafetyNumber(contact, SafetyNumberOrigin.ContactMenu)
                },
                onDeleteGroup = viewModel::deleteGroup,
                onShowInvite = viewModel::showGroupInvite,
            )
        } else {
            when (state.selectedTab) {
                HomeTab.Conversations -> ConversationsTab(
                    conversations = state.conversations,
                    serverConfigured = state.serverConfigured && state.serverTested,
                    onOpenConversation = onOpenConversation,
                    onShowAddSheet = onShowAddSheet,
                    onShowMyQr = viewModel::openMyQr,
                    onConfigureRelay = {
                        if (state.showSetupContinueBanner) {
                            viewModel.reopenOnboarding()
                        } else {
                            onOpenSettings()
                        }
                    },
                )
                HomeTab.Contacts -> ContactsTab(
                    contacts = state.contacts,
                    onOpenChat = { openContactChat(it) },
                    onDelete = viewModel::deleteContact,
                    onOpenSafetyNumber = { contact ->
                        onOpenSafetyNumber(contact, SafetyNumberOrigin.ContactMenu)
                    },
                    onEditNickname = viewModel::openEditNickname,
                    onScanQr = onScanQr,
                    onPasteInvite = viewModel::openPasteDialog,
                )
                HomeTab.Groups -> GroupsTab(
                    groups = state.groups,
                    contacts = state.contacts,
                    onOpenChat = { viewModel.createGroupSession(it)?.let(onOpenChat) },
                    onDelete = viewModel::deleteGroup,
                    onShowInvite = viewModel::showGroupInvite,
                    onCreateGroup = viewModel::openCreateGroup,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeSearchBar(
    query: String,
    active: Boolean,
    onQueryChange: (String) -> Unit,
    onActiveChange: (Boolean) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SearchBar(
        modifier = modifier.fillMaxWidth(),
        inputField = {
            SearchBarDefaults.InputField(
                query = query,
                onQueryChange = onQueryChange,
                onSearch = { onActiveChange(false) },
                expanded = active,
                onExpandedChange = onActiveChange,
                placeholder = { Text(stringResource(R.string.home_search_placeholder)) },
                leadingIcon = {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = stringResource(R.string.cd_search),
                    )
                },
                trailingIcon = if (query.isNotEmpty()) {
                    {
                        IconButton(onClick = onClear) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.cd_clear_search),
                            )
                        }
                    }
                } else {
                    null
                },
            )
        },
        expanded = active,
        onExpandedChange = onActiveChange,
    ) {}
}

@Composable
private fun HomeSearchResults(
    query: String,
    conversations: List<ConversationRowUi>,
    contacts: List<Contact>,
    groups: List<ChatGroup>,
    allContacts: List<Contact>,
    openContactChat: (Contact) -> Unit,
    onOpenConversation: (ConversationRowUi) -> Unit,
    onOpenGroupChat: (ChatGroup) -> Unit,
    onEditNickname: (Contact) -> Unit,
    onDeleteContact: (String) -> Unit,
    onOpenSafetyNumber: (Contact) -> Unit,
    onDeleteGroup: (String) -> Unit,
    onShowInvite: (ChatGroup) -> Unit,
) {
    if (conversations.isEmpty() && contacts.isEmpty() && groups.isEmpty()) {
        EmptyHint(
            title = stringResource(R.string.home_search_empty, query),
            subtitle = stringResource(R.string.home_search_empty_subtitle),
        )
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (conversations.isNotEmpty()) {
            item {
                Text(
                    stringResource(R.string.home_search_section_conversations),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                )
            }
            items(conversations, key = { "conv-${it.roomId}" }) { row ->
                ConversationRow(row = row, onClick = { onOpenConversation(row) })
            }
        }
        if (contacts.isNotEmpty()) {
            item {
                Text(
                    stringResource(R.string.home_search_section_contacts),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                )
            }
            items(contacts, key = { "contact-${it.id}" }) { contact ->
                ContactRow(
                    contact = contact,
                    onClick = { openContactChat(contact) },
                    onDelete = { onDeleteContact(contact.id) },
                    onOpenSafetyNumber = { onOpenSafetyNumber(contact) },
                    onEditNickname = { onEditNickname(contact) },
                )
            }
        }
        if (groups.isNotEmpty()) {
            item {
                Text(
                    stringResource(R.string.home_search_section_groups),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                )
            }
            items(groups, key = { "group-${it.id}" }) { group ->
                GroupRow(
                    group = group,
                    contacts = allContacts,
                    onClick = { onOpenGroupChat(group) },
                    onInvite = { onShowInvite(group) },
                    onDelete = { onDeleteGroup(group.id) },
                )
            }
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
private fun ConversationsTab(
    conversations: List<ConversationRowUi>,
    serverConfigured: Boolean,
    onOpenConversation: (ConversationRowUi) -> Unit,
    onShowAddSheet: () -> Unit,
    onShowMyQr: () -> Unit,
    onConfigureRelay: () -> Unit,
) {
    if (!serverConfigured) {
        EmptyHint(
            title = stringResource(R.string.home_empty_server_required_title),
            subtitle = stringResource(R.string.home_empty_server_required_subtitle),
            primaryLabel = stringResource(R.string.empty_action_configure_relay),
            onPrimary = onConfigureRelay,
        )
    } else if (conversations.isEmpty()) {
        EmptyHint(
            title = stringResource(R.string.home_empty_conversations_title),
            subtitle = stringResource(R.string.home_empty_conversations_subtitle),
            primaryLabel = stringResource(R.string.empty_action_add_contact),
            onPrimary = onShowAddSheet,
            secondaryLabel = stringResource(R.string.empty_action_show_my_qr),
            onSecondary = onShowMyQr,
        )
    } else {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(conversations, key = { it.roomId }) { row ->
                ConversationRow(
                    row = row,
                    onClick = { onOpenConversation(row) },
                )
            }
        }
    }
}

@Composable
private fun ConversationRow(
    row: ConversationRowUi,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val preview = when {
        row.lastMessagePreview.isBlank() -> stringResource(R.string.home_conversation_no_preview)
        row.lastMessageIsMine -> stringResource(R.string.home_conversation_preview_you, row.lastMessagePreview)
        else -> row.lastMessagePreview
    }
    val timeLabel = row.formattedTime()
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
                        text = row.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (timeLabel.isNotBlank()) {
                        Spacer(Modifier.size(8.dp))
                        Text(
                            text = timeLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.onSurfaceVariant,
                        )
                    }
                }
            },
            supportingContent = {
                Text(
                    text = preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            leadingContent = {
                Icon(
                    if (row.isGroup) Icons.Default.Group else Icons.Default.Forum,
                    contentDescription = null,
                    tint = cs.primary,
                )
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (row.peerNeedsVerification) {
                        Badge(containerColor = cs.errorContainer) {
                            Text(
                                stringResource(R.string.home_badge_unverified),
                                style = MaterialTheme.typography.labelSmall,
                                color = cs.onErrorContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                        Spacer(Modifier.size(8.dp))
                    }
                    if (row.unreadCount > 0) {
                        Badge(containerColor = cs.primary) {
                            Text(
                                text = if (row.unreadCount > 99) "99+" else row.unreadCount.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = cs.onPrimary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            },
            colors = ListItemDefaults.colors(containerColor = cs.surfaceContainerLow),
        )
    }
}

@Composable
private fun ContactsTab(
    contacts: List<Contact>,
    onOpenChat: (Contact) -> Unit,
    onDelete: (String) -> Unit,
    onOpenSafetyNumber: (Contact) -> Unit,
    onEditNickname: (Contact) -> Unit,
    onScanQr: () -> Unit,
    onPasteInvite: () -> Unit,
) {
    if (contacts.isEmpty()) {
        EmptyHint(
            title = stringResource(R.string.home_empty_contacts_title),
            subtitle = stringResource(R.string.home_empty_contacts_subtitle),
            primaryLabel = stringResource(R.string.empty_action_scan_qr),
            onPrimary = onScanQr,
            secondaryLabel = stringResource(R.string.empty_action_paste_invite),
            onSecondary = onPasteInvite,
        )
    } else {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(contacts, key = { it.id }) { contact ->
                ContactRow(
                    contact = contact,
                    onClick = { onOpenChat(contact) },
                    onDelete = { onDelete(contact.id) },
                    onOpenSafetyNumber = { onOpenSafetyNumber(contact) },
                    onEditNickname = { onEditNickname(contact) },
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
    onCreateGroup: () -> Unit,
) {
    if (groups.isEmpty()) {
        EmptyHint(
            title = stringResource(R.string.home_empty_groups_title),
            subtitle = stringResource(R.string.home_empty_groups_subtitle),
            primaryLabel = stringResource(R.string.empty_action_create_group),
            onPrimary = onCreateGroup,
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
private fun EmptyHint(
    title: String,
    subtitle: String,
    primaryLabel: String? = null,
    onPrimary: (() -> Unit)? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
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
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (primaryLabel != null && onPrimary != null) {
            Spacer(Modifier.height(20.dp))
            Button(onClick = onPrimary) {
                Text(primaryLabel)
            }
        }
        if (secondaryLabel != null && onSecondary != null) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onSecondary) {
                Text(secondaryLabel)
            }
        }
    }
}

@Composable
private fun RelayStatusBar(
    status: RelayStatusBarState,
    hostLabel: String,
    onConfigure: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val notConfigured = stringResource(R.string.relay_status_not_configured)
    val checking = stringResource(R.string.relay_status_checking)
    val online = stringResource(R.string.relay_status_online, hostLabel)
    val connecting = stringResource(R.string.relay_status_connecting)
    val disconnected = stringResource(R.string.relay_status_disconnected, hostLabel)
    val error = stringResource(R.string.relay_status_error, hostLabel)
    val configure = stringResource(R.string.relay_status_action_configure)
    val retry = stringResource(R.string.relay_status_action_retry)

    val label: String
    val actionLabel: String?
    val onAction: (() -> Unit)?
    val containerColor: androidx.compose.ui.graphics.Color
    val contentColor: androidx.compose.ui.graphics.Color
    when (status) {
        RelayStatusBarState.NotConfigured -> {
            label = notConfigured
            actionLabel = configure
            onAction = onConfigure
            containerColor = cs.errorContainer
            contentColor = cs.onErrorContainer
        }
        RelayStatusBarState.Checking -> {
            label = checking
            actionLabel = null
            onAction = null
            containerColor = cs.surfaceContainerHigh
            contentColor = cs.onSurfaceVariant
        }
        RelayStatusBarState.Online -> {
            label = online
            actionLabel = null
            onAction = null
            containerColor = cs.primaryContainer
            contentColor = cs.onPrimaryContainer
        }
        RelayStatusBarState.Connecting -> {
            label = connecting
            actionLabel = null
            onAction = null
            containerColor = cs.surfaceContainerHigh
            contentColor = cs.onSurfaceVariant
        }
        RelayStatusBarState.Disconnected -> {
            label = disconnected
            actionLabel = retry
            onAction = onRetry
            containerColor = cs.errorContainer
            contentColor = cs.onErrorContainer
        }
        RelayStatusBarState.Error -> {
            label = error
            actionLabel = retry
            onAction = onRetry
            containerColor = cs.errorContainer
            contentColor = cs.onErrorContainer
        }
    }
    if (onAction != null) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = CardShape,
            color = containerColor,
            onClick = onAction,
        ) {
            RelayStatusBarContent(label, actionLabel, contentColor, cs.primary)
        }
    } else {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = CardShape,
            color = containerColor,
        ) {
            RelayStatusBarContent(label, actionLabel, contentColor, cs.primary)
        }
    }
}

@Composable
private fun RelayStatusBarContent(
    label: String,
    actionLabel: String?,
    contentColor: androidx.compose.ui.graphics.Color,
    actionColor: androidx.compose.ui.graphics.Color,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (actionLabel != null) {
            Text(
                text = actionLabel,
                style = MaterialTheme.typography.labelLarge,
                color = actionColor,
            )
        }
    }
}

@Composable
private fun SetupContinueBanner(
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = CardShape,
        color = cs.secondaryContainer,
        onClick = onContinue,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                stringResource(R.string.setup_continue_banner_title),
                style = MaterialTheme.typography.titleSmall,
                color = cs.onSecondaryContainer,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.setup_continue_banner_body),
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSecondaryContainer,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.setup_continue_banner_action),
                style = MaterialTheme.typography.labelLarge,
                color = cs.primary,
            )
        }
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
    onOpenSafetyNumber: () -> Unit,
    onEditNickname: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val verifySafetyNumberLabel = stringResource(R.string.home_menu_verify_safety_number)
    val editNicknameLabel = stringResource(R.string.home_menu_edit_nickname)
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
                        add(editNicknameLabel to onEditNickname)
                        if (!contact.verified) add(verifySafetyNumberLabel to onOpenSafetyNumber)
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

@Composable
private fun DetachedConnectionBanner(
    sessionCount: Int,
    chatName: String?,
    showBatteryHint: Boolean,
    onOpenChat: () -> Unit,
    onBatterySettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = CardShape,
        color = cs.secondaryContainer,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenChat),
            ) {
                Text(
                    text = if (sessionCount > 1) {
                        stringResource(R.string.home_detached_listening_multi, sessionCount)
                    } else {
                        stringResource(R.string.home_detached_listening, chatName.orEmpty())
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = cs.onSecondaryContainer,
                )
            }
            if (showBatteryHint) {
                TextButton(onClick = onBatterySettings) {
                    Text(stringResource(R.string.home_detached_battery_hint))
                }
            }
        }
    }
}
