package com.biji.notes

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.biji.notes.ui.chat.ChatScreen
import com.biji.notes.ui.chat.ChatViewModel
import com.biji.notes.ui.chat.ConversationListScreen
import com.biji.notes.ui.settings.SettingsScreen
import com.biji.notes.ui.theme.BijiTheme
import com.biji.notes.ui.webview.WebViewScreen

private const val TAB_CHATS = "chats"
private const val TAB_SETTINGS = "settings"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val app = application as BijiApp
        setContent {
            BijiTheme {
                val vm: ChatViewModel = viewModel(
                    factory = ChatViewModel.factory(
                        chat = app.chatRepository,
                        settings = app.settingsRepository,
                        client = app.deepSeekClient,
                        memory = app.memoryService,
                        toolExec = app.toolExecutor,
                        notifier = app.chatNotifier,
                        voice = app.voiceRecognizer,
                        isForeground = app::isForeground
                    )
                )
                AppRoot(vm)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot(vm: ChatViewModel) {
    var tab by remember { mutableStateOf(TAB_CHATS) }
    val activeConvo by vm.activeConvoId.collectAsState()
    val openWebUrl by vm.openWebUrl.collectAsState()
    val showBottomBar = activeConvo == null && openWebUrl == null

    // POST_NOTIFICATIONS – ask once on launch on API 33+.
    val notifLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { /* user choice persisted by system */ }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            AnimatedContent(
                targetState = showBottomBar,
                transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) },
                label = "bottomBar"
            ) { visible ->
                if (visible) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        tonalElevation = 0.dp
                    ) {
                        NavigationBarItem(
                            selected = tab == TAB_CHATS,
                            onClick = { tab = TAB_CHATS },
                            icon = {
                                Icon(
                                    if (tab == TAB_CHATS) Icons.Rounded.ChatBubble
                                    else Icons.Outlined.ChatBubbleOutline,
                                    contentDescription = null
                                )
                            },
                            label = { Text("对话") },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                indicatorColor = MaterialTheme.colorScheme.primary,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                        NavigationBarItem(
                            selected = tab == TAB_SETTINGS,
                            onClick = { tab = TAB_SETTINGS },
                            icon = {
                                Icon(
                                    if (tab == TAB_SETTINGS) Icons.Rounded.Tune
                                    else Icons.Outlined.Tune,
                                    contentDescription = null
                                )
                            },
                            label = { Text("设置") },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                indicatorColor = MaterialTheme.colorScheme.primary,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                }
            }
        }
    ) { outerPadding ->
        AnimatedContent(
            targetState = Triple(tab, activeConvo, openWebUrl),
            transitionSpec = {
                val spec = spring<Float>(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
                (fadeIn(tween(220)) + scaleIn(initialScale = 0.98f, animationSpec = spec))
                    .togetherWith(fadeOut(tween(140)))
                    .using(SizeTransform(clip = false))
            },
            label = "screen",
            modifier = Modifier.fillMaxSize()
        ) { (currentTab, convoId, webUrl) ->
            when {
                webUrl != null -> {
                    BackHandler { vm.closeWebUrl() }
                    WebViewScreen(initialUrl = webUrl, onClose = { vm.closeWebUrl() })
                }
                currentTab == TAB_CHATS && convoId != null -> {
                    BackHandler { vm.clearActive() }
                    ChatScreen(vm = vm, onBack = { vm.clearActive() })
                }
                currentTab == TAB_CHATS -> ConversationListScreen(
                    vm = vm,
                    contentPadding = outerPadding,
                    onOpen = { id -> vm.openConversation(id) },
                    onNew = { vm.newConversation() }
                )
                else -> SettingsScreen(vm = vm, contentPadding = outerPadding)
            }
        }
    }
}
