package com.biji.notes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.biji.notes.ui.chat.ChatScreen
import com.biji.notes.ui.chat.ChatViewModel
import com.biji.notes.ui.chat.ConversationListScreen
import com.biji.notes.ui.glass.AnimatedAuroraBackground
import com.biji.notes.ui.glass.LiquidGlassScaffold
import com.biji.notes.ui.glass.LocalLiquidGlass
import com.biji.notes.ui.nav.BouncyTabBar
import com.biji.notes.ui.nav.TabItem
import com.biji.notes.ui.settings.SettingsScreen
import com.biji.notes.ui.theme.BijiTheme

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
                        client = app.deepSeekClient
                    )
                )
                AppRoot(vm)
            }
        }
    }
}

@Composable
private fun AppRoot(vm: ChatViewModel) {
    var tab by remember { mutableStateOf(TAB_CHATS) }
    val activeConvo by vm.activeConvoId.collectAsState()

    LiquidGlassScaffold(
        background = { AnimatedAuroraBackground() },
        content = {
            val glass = LocalLiquidGlass.current

            Box(Modifier.fillMaxSize()) {
                AnimatedContent(
                    targetState = Pair(tab, activeConvo),
                    transitionSpec = {
                        (fadeIn(tween(180)) + scaleIn(
                            initialScale = 0.97f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessMediumLow
                            )
                        )) togetherWith fadeOut(tween(120))
                    },
                    label = "screen",
                    modifier = Modifier.fillMaxSize()
                ) { (currentTab, convoId) ->
                    when {
                        currentTab == TAB_CHATS && convoId != null -> {
                            BackHandler { vm.clearActive() }
                            ChatScreen(
                                vm = vm,
                                glass = glass,
                                onBack = { vm.clearActive() }
                            )
                        }
                        currentTab == TAB_CHATS -> {
                            ConversationListScreen(
                                vm = vm,
                                glass = glass,
                                onOpen = { id -> vm.openConversation(id) },
                                onNew = { vm.newConversation() }
                            )
                        }
                        else -> {
                            SettingsScreen(vm = vm, glass = glass)
                        }
                    }
                }

                if (activeConvo == null) {
                    BouncyTabBar(
                        items = listOf(
                            TabItem(TAB_CHATS, "对话", Icons.Rounded.ChatBubble),
                            TabItem(TAB_SETTINGS, "设置", Icons.Rounded.Settings)
                        ),
                        selected = tab,
                        onSelect = { tab = it },
                        glass = glass,
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                }
            }
        }
    )
}
