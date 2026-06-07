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
import app.zerorelay.ui.snackbar.AppSnackbarBus
import androidx.lifecycle.viewmodel.compose.viewModel
import app.zerorelay.data.model.ChatSession
import app.zerorelay.ui.chat.ChatScreen
import app.zerorelay.ui.components.AdaptiveChatPlaceholder
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
        LaunchedEffect(Unit) {
            AppSnackbarBus.messages.collect { msg ->
                snackbarHost.showSnackbar(msg)
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            var screen by remember { mutableStateOf<RootScreen>(RootScreen.Home) }
            var listDetailChat by remember { mutableStateOf<ChatSession?>(null) }

            val openChat: (ChatSession) -> Unit = { session ->
                if (listDetail) {
                    listDetailChat = session
                    screen = RootScreen.Home
                } else {
                    screen = RootScreen.Chat(session)
                }
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
                                    onScanQr = { screen = RootScreen.ScanQr },
                                    onOpenSettings = { screen = RootScreen.Settings },
                                    viewModel = homeViewModel,
                                )
                                VerticalDivider()
                                if (listDetailChat != null) {
                                    ChatScreen(
                                        session = listDetailChat!!,
                                        onLeave = { listDetailChat = null },
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
                            allowScreenshots = homeState.allowScreenshots,
                        )
                    }
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

            SnackbarHost(
                hostState = snackbarHost,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding(),
            )
        }
    }
}
