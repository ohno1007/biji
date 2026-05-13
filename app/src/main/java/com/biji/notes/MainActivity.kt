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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.biji.notes.ui.chat.ChatScreen
import com.biji.notes.ui.chat.ChatViewModel
import com.biji.notes.ui.chat.ConversationListScreen
import com.biji.notes.ui.glass.bouncyClickable
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

// Reservation under the screen body to keep the last item from hiding
// behind the floating bottom nav + system gesture inset.
private val NavPillHeight = 64.dp
private val NavFadeHeight = 40.dp

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

    val density = androidx.compose.ui.platform.LocalDensity.current
    val navInset = with(density) {
        WindowInsets.navigationBars.getBottom(density).toDp()
    }
    val reservedBottom = if (showBottomBar)
        NavFadeHeight + NavPillHeight + 16.dp + navInset
    else 0.dp

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
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
                    contentPadding = PaddingValues(bottom = reservedBottom),
                    onOpen = { id -> vm.openConversation(id) },
                    onNew = { vm.newConversation() }
                )
                else -> SettingsScreen(
                    vm = vm,
                    contentPadding = PaddingValues(bottom = reservedBottom)
                )
            }
        }

        // Floating bottom nav with the same gradient fade-out as the chat
        // composer area: the list above scrolls "underneath" the fade and
        // the pill sits visually on top of cream-coloured negative space.
        AnimatedVisibility(
            visible = showBottomBar,
            enter = fadeIn(tween(160)) + slideInVertically { it / 2 },
            exit = fadeOut(tween(120)) + slideOutVertically { it / 2 },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            FloatingBottomNav(
                tab = tab,
                onSelect = { tab = it }
            )
        }
    }
}

@Composable
private fun FloatingBottomNav(
    tab: String,
    onSelect: (String) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val bg = cs.background
    Column(Modifier.fillMaxWidth()) {
        // Fade gradient – content above visually dissolves into the cream
        // background before reaching the pill, the same trick the chat
        // composer uses.
        Spacer(
            Modifier
                .fillMaxWidth()
                .height(NavFadeHeight)
                .background(
                    Brush.verticalGradient(
                        0.0f to bg.copy(alpha = 0f),
                        0.55f to bg.copy(alpha = 0.85f),
                        1.0f to bg
                    )
                )
        )
        Box(
            Modifier
                .fillMaxWidth()
                .background(bg)
                .navigationBarsPadding()
                .padding(horizontal = 48.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(NavPillHeight)
                    .clip(RoundedCornerShape(50))
                    .background(cs.surfaceContainer)
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                NavSlot(
                    selected = tab == TAB_CHATS,
                    activeIcon = Icons.Rounded.ChatBubble,
                    inactiveIcon = Icons.Outlined.ChatBubbleOutline,
                    label = "对话",
                    onClick = { onSelect(TAB_CHATS) },
                    modifier = Modifier.weight(1f)
                )
                NavSlot(
                    selected = tab == TAB_SETTINGS,
                    activeIcon = Icons.Rounded.Tune,
                    inactiveIcon = Icons.Outlined.Tune,
                    label = "设置",
                    onClick = { onSelect(TAB_SETTINGS) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun NavSlot(
    selected: Boolean,
    activeIcon: ImageVector,
    inactiveIcon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .padding(horizontal = 4.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(50))
            .bouncyClickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(cs.primary)
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    activeIcon,
                    contentDescription = label,
                    modifier = Modifier.size(18.dp),
                    tint = cs.onPrimary
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = cs.onPrimary,
                    fontWeight = FontWeight.SemiBold
                )
            }
        } else {
            Box(
                Modifier
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .size(36.dp)
                    .clip(CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    inactiveIcon,
                    contentDescription = label,
                    modifier = Modifier.size(22.dp),
                    tint = cs.onSurfaceVariant
                )
            }
        }
    }
}
