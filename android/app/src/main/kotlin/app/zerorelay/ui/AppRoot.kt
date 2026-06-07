package app.zerorelay.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.VerticalDivider
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.zerorelay.ui.components.UserErrorBanner
import app.zerorelay.ui.error.UserError
import app.zerorelay.ui.error.UserErrorBus
import app.zerorelay.ui.snackbar.AppSnackbarBus
import androidx.lifecycle.viewmodel.compose.viewModel
import app.zerorelay.data.model.ChatSession
import app.zerorelay.data.model.Contact
import app.zerorelay.ui.chat.ChatScreen
import app.zerorelay.ui.safety.SafetyNumberScreen
import app.zerorelay.ui.components.AdaptiveChatPlaceholder
import app.zerorelay.ui.home.GroupInviteSheet
import app.zerorelay.ui.home.HomeScreen
import app.zerorelay.ui.home.HomeViewModel
import app.zerorelay.ui.home.ScanHandleResult
import app.zerorelay.ui.home.ScanQrScreen
import app.zerorelay.ui.insets.SyncSystemBarColors
import app.zerorelay.ui.onboarding.OnboardingScreen
import app.zerorelay.ui.settings.SettingsScreen
import app.zerorelay.ui.theme.ZeroRelayTheme
import app.zerorelay.ui.util.rememberAppWidthClass
import app.zerorelay.ui.util.supportsListDetail

enum class SafetyNumberOrigin {
    HomeList,
    ContactMenu,
    ChatBanner,
}

private data class SafetyNumberRequest(
    val contact: Contact,
    val origin: SafetyNumberOrigin,
    val returnChat: ChatSession? = null,
)

private sealed interface RootScreen {
    data object Home : RootScreen
    data object Settings : RootScreen
    data object ScanQr : RootScreen
    data class Chat(val session: ChatSession) : RootScreen
}

@Composable
fun AppRoot(
    pendingNotificationRoomId: String? = null,
    onNotificationRoomConsumed: () -> Unit = {},
) {
    val homeViewModel: HomeViewModel = viewModel()
    val homeState by homeViewModel.uiState.collectAsState()
    val widthClass = rememberAppWidthClass()
    val listDetail = widthClass.supportsListDetail

    ZeroRelayTheme(dynamicColor = homeState.useDynamicColor) {
        SyncSystemBarColors()
        val snackbarHost = remember { SnackbarHostState() }
        var globalUserError by remember { mutableStateOf<UserError?>(null) }
        LaunchedEffect(Unit) {
            AppSnackbarBus.messages.collect { msg ->
                snackbarHost.showSnackbar(msg)
            }
        }
        LaunchedEffect(Unit) {
            UserErrorBus.errors.collect { err ->
                globalUserError = err
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            var screen by remember { mutableStateOf<RootScreen>(RootScreen.Home) }
            var listDetailChat by remember { mutableStateOf<ChatSession?>(null) }
            var safetyNumberRequest by remember { mutableStateOf<SafetyNumberRequest?>(null) }

            val openChat: (ChatSession) -> Unit = { session ->
                if (listDetail) {
                    listDetailChat = session
                    screen = RootScreen.Home
                } else {
                    screen = RootScreen.Chat(session)
                }
            }

            val dismissSafetyNumber: () -> Unit = { safetyNumberRequest = null }

            val finishSafetyNumberLater: (SafetyNumberRequest) -> Unit = { request ->
                when (request.origin) {
                    SafetyNumberOrigin.HomeList -> {
                        homeViewModel.createSession(request.contact)?.let(openChat)
                    }
                    SafetyNumberOrigin.ContactMenu -> Unit
                    SafetyNumberOrigin.ChatBanner -> {
                        request.returnChat?.let { session ->
                            if (listDetail) {
                                listDetailChat = session
                                screen = RootScreen.Home
                            } else {
                                screen = RootScreen.Chat(session)
                            }
                        }
                    }
                }
                dismissSafetyNumber()
            }

            val finishSafetyNumberConfirmed: (SafetyNumberRequest) -> Unit = { request ->
                homeViewModel.markContactVerified(request.contact.id)
                when (request.origin) {
                    SafetyNumberOrigin.HomeList -> {
                        homeViewModel.createSession(request.contact)?.let(openChat)
                    }
                    SafetyNumberOrigin.ContactMenu -> Unit
                    SafetyNumberOrigin.ChatBanner -> {
                        request.returnChat?.let { session ->
                            if (listDetail) {
                                listDetailChat = session
                                screen = RootScreen.Home
                            } else {
                                screen = RootScreen.Chat(session)
                            }
                        }
                    }
                }
                dismissSafetyNumber()
            }

            val openSafetyNumber: (Contact, SafetyNumberOrigin, ChatSession?) -> Unit = { contact, origin, returnChat ->
                safetyNumberRequest = SafetyNumberRequest(contact, origin, returnChat)
            }

            LaunchedEffect(homeState.identity, pendingNotificationRoomId) {
                val roomId = pendingNotificationRoomId ?: return@LaunchedEffect
                if (homeState.identity == null) return@LaunchedEffect
                onNotificationRoomConsumed()
                homeViewModel.findChatSessionForRoom(roomId)?.let(openChat)
            }

            AnimatedContent(
                targetState = screen,
                transitionSpec = {
                    fadeIn(animationSpec = tween(durationMillis = 220)) togetherWith
                        fadeOut(animationSpec = tween(durationMillis = 120))
                },
                label = "root_navigation",
            ) { current ->
                when (current) {
                    RootScreen.Home -> {
                        if (listDetail) {
                            Row(Modifier.fillMaxSize()) {
                                HomeScreen(
                                    modifier = Modifier
                                        .weight(0.42f)
                                        .fillMaxHeight(),
                                    onOpenChat = openChat,
                                    onOpenSafetyNumber = { contact, origin ->
                                        openSafetyNumber(contact, origin, null)
                                    },
                                    onScanQr = { screen = RootScreen.ScanQr },
                                    onOpenSettings = { screen = RootScreen.Settings },
                                    viewModel = homeViewModel,
                                )
                                VerticalDivider()
                                if (listDetailChat != null) {
                                    ChatScreen(
                                        session = listDetailChat!!,
                                        onLeave = { listDetailChat = null },
                                        onOpenSafetyNumber = {
                                            homeViewModel.findContact(listDetailChat!!.peerContactId)
                                                ?.let { contact ->
                                                    openSafetyNumber(
                                                        contact,
                                                        SafetyNumberOrigin.ChatBanner,
                                                        listDetailChat,
                                                    )
                                                }
                                        },
                                        onInviteMembers = {
                                            homeViewModel.showGroupInviteForRoom(listDetailChat!!.roomId)
                                        },
                                        allowScreenshots = homeState.allowScreenshots,
                                        modifier = Modifier
                                            .weight(0.58f)
                                            .fillMaxHeight(),
                                    )
                                } else {
                                    AdaptiveChatPlaceholder(
                                        modifier = Modifier
                                            .weight(0.58f)
                                            .fillMaxHeight(),
                                    )
                                }
                            }
                        } else {
                            HomeScreen(
                                onOpenChat = openChat,
                                onOpenSafetyNumber = { contact, origin ->
                                    openSafetyNumber(contact, origin, null)
                                },
                                onScanQr = { screen = RootScreen.ScanQr },
                                onOpenSettings = { screen = RootScreen.Settings },
                                viewModel = homeViewModel,
                            )
                        }
                    }
                    RootScreen.Settings -> {
                        BackHandler { screen = RootScreen.Home }
                        SettingsScreen(
                            onBack = { screen = RootScreen.Home },
                            viewModel = homeViewModel,
                        )
                    }
                    RootScreen.ScanQr -> {
                        BackHandler { screen = RootScreen.Home }
                        ScanQrScreen(
                            onBack = { screen = RootScreen.Home },
                            onScanned = { raw ->
                                when (val result = homeViewModel.handleScan(raw)) {
                                    is ScanHandleResult.GroupJoined -> openChat(result.session)
                                    is ScanHandleResult.ContactAdded,
                                    is ScanHandleResult.Error,
                                    -> screen = RootScreen.Home
                                }
                            },
                        )
                    }
                    is RootScreen.Chat -> {
                        ChatScreen(
                            session = current.session,
                            onLeave = { screen = RootScreen.Home },
                            onOpenSafetyNumber = {
                                homeViewModel.findContact(current.session.peerContactId)
                                    ?.let { contact ->
                                        openSafetyNumber(
                                            contact,
                                            SafetyNumberOrigin.ChatBanner,
                                            current.session,
                                        )
                                    }
                            },
                            onInviteMembers = {
                                homeViewModel.showGroupInviteForRoom(current.session.roomId)
                            },
                            allowScreenshots = homeState.allowScreenshots,
                        )
                    }
                }
            }

            safetyNumberRequest?.let { request ->
                val identity = homeState.identity
                BackHandler {
                    if (request.origin == SafetyNumberOrigin.ChatBanner) {
                        finishSafetyNumberLater(request)
                    } else {
                        dismissSafetyNumber()
                    }
                }
                if (identity != null) {
                    SafetyNumberScreen(
                        contact = request.contact,
                        myFingerprint = identity.fingerprint,
                        onConfirm = { finishSafetyNumberConfirmed(request) },
                        onLater = { finishSafetyNumberLater(request) },
                        onBack = {
                            if (request.origin == SafetyNumberOrigin.ChatBanner) {
                                finishSafetyNumberLater(request)
                            } else {
                                dismissSafetyNumber()
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    LaunchedEffect(Unit) { dismissSafetyNumber() }
                }
            }

            if (homeState.showOnboarding) {
                OnboardingScreen(
                    state = homeState,
                    viewModel = homeViewModel,
                    onScanQr = { screen = RootScreen.ScanQr },
                    onPasteInvite = { homeViewModel.openPasteDialog() },
                    onFinish = { },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            homeState.inviteGroup?.let { group ->
                val payload = remember(group.id, homeState.serverUrl) {
                    homeViewModel.groupInvitePayload(group)
                }
                GroupInviteSheet(
                    group = group,
                    payload = payload,
                    serverUrl = homeState.serverUrl,
                    highlightRotation = homeState.inviteHighlightRotation,
                    onDismiss = homeViewModel::closeGroupInvite,
                    onRotateKey = { homeViewModel.rotateGroupKey(group.id) },
                )
            }

            globalUserError?.let { err ->
                UserErrorBanner(
                    error = err,
                    onDismiss = { globalUserError = null },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            SnackbarHost(
                hostState = snackbarHost,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding(),
            )
        }
    }
}
