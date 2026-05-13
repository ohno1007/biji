package com.biji.notes.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.biji.notes.data.Conversation
import com.biji.notes.data.MODEL_REASONER
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.ui.graphics.Brush
import com.biji.notes.ui.glass.bouncyClickable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationListScreen(
    vm: ChatViewModel,
    contentPadding: PaddingValues,
    onOpen: (Long) -> Unit,
    onNew: () -> Unit
) {
    val conversations by vm.conversations.collectAsState()
    val settings by vm.settings.collectAsState()
    val modelsState by vm.models.collectAsState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var modelSheetOpen by remember { mutableStateOf(false) }

    LaunchedEffect(settings.apiKey, settings.baseUrl) { vm.ensureModelsLoaded() }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(
                        "对话",
                        style = MaterialTheme.typography.displayLarge.copy(fontWeight = FontWeight.Bold)
                    )
                },
                actions = {
                    ThinkingToggleChip(
                        active = settings.defaultThinking,
                        onToggle = { vm.setDefaultThinking(!settings.defaultThinking) }
                    )
                    Spacer(Modifier.width(6.dp))
                    ClickableModelTag(
                        model = settings.model,
                        onClick = { modelSheetOpen = true }
                    )
                    Spacer(Modifier.width(8.dp))
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { inner ->
        val topFadeHeight = 28.dp
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp,
                    // Leave room for the soft fade overlay so the first card
                    // never sits behind the cream gradient at rest.
                    top = inner.calculateTopPadding() + topFadeHeight,
                    bottom = inner.calculateBottomPadding() +
                        contentPadding.calculateBottomPadding() + 24.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item { NewChatCard(onClick = onNew) }
                if (conversations.isEmpty()) {
                    item { EmptyHint() }
                } else {
                    items(conversations, key = { it.id }) { c ->
                        ConversationRow(
                            convo = c,
                            onClick = { onOpen(c.id) },
                            onDelete = { vm.deleteConversation(c.id) }
                        )
                    }
                }
            }
            ConvoTopEdgeFade(
                height = topFadeHeight,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = inner.calculateTopPadding())
            )
        }
    }

    if (modelSheetOpen) {
        ModelPickerSheet(
            models = modelsState.list.map { it.id },
            loading = modelsState.loading,
            error = modelsState.error,
            currentModel = settings.model,
            convoThinking = false,
            showThinkingToggle = false,
            onPick = { vm.setModel(it); modelSheetOpen = false },
            onToggleThinking = { /* no-op outside chat */ },
            onRefresh = vm::refreshModels,
            onDismiss = { modelSheetOpen = false }
        )
    }
}

@Composable
private fun ThinkingToggleChip(active: Boolean, onToggle: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val bg = if (active) cs.primaryContainer else cs.surfaceContainer
    val fg = if (active) cs.onPrimaryContainer else cs.onSurfaceVariant
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .bouncyClickable(pressedScale = 0.95f, onClick = onToggle)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.compose.material3.Icon(
            androidx.compose.material.icons.Icons.Outlined.AutoAwesome,
            contentDescription = "深度思考",
            modifier = Modifier.size(14.dp),
            tint = fg
        )
        Spacer(Modifier.width(4.dp))
        Text(
            "深度思考",
            style = MaterialTheme.typography.labelLarge,
            color = fg,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun ClickableModelTag(model: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(cs.surfaceContainer)
            .bouncyClickable(onClick = onClick)
            .padding(start = 12.dp, end = 8.dp)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            model.removePrefix("deepseek-"),
            style = MaterialTheme.typography.labelLarge,
            color = cs.onSurface,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.width(2.dp))
        Icon(
            Icons.Rounded.ExpandMore,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = cs.onSurfaceVariant
        )
    }
}

@Composable
private fun NewChatCard(onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(cs.tertiary)
            .bouncyClickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(cs.onTertiary),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Rounded.Add,
                contentDescription = null,
                tint = cs.tertiary,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                "新建对话",
                style = MaterialTheme.typography.titleMedium,
                color = cs.onTertiary,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "随时和 DeepSeek 聊点什么",
                style = MaterialTheme.typography.labelLarge,
                color = cs.onTertiary.copy(alpha = 0.75f)
            )
        }
    }
}

@Composable
private fun ConversationRow(
    convo: Conversation,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(cs.surfaceContainer)
            .bouncyClickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(cs.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Outlined.ChatBubbleOutline,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = cs.onSurface
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                convo.title,
                style = MaterialTheme.typography.titleMedium,
                color = cs.onSurface,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                formatDate(convo.updatedAt),
                style = MaterialTheme.typography.labelLarge,
                color = cs.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        ModelPill(model = convo.model)
        Spacer(Modifier.width(6.dp))
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .bouncyClickable(onClick = onDelete),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Rounded.DeleteOutline,
                contentDescription = "删除",
                modifier = Modifier.size(18.dp),
                tint = cs.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ModelPill(model: String) {
    val cs = MaterialTheme.colorScheme
    val isReasoner = model == MODEL_REASONER
    val bg = if (isReasoner) cs.primaryContainer else cs.surfaceContainerHigh
    val fg = if (isReasoner) cs.onPrimaryContainer else cs.onSurface
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isReasoner) {
            Icon(
                Icons.Outlined.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = fg
            )
            Spacer(Modifier.width(4.dp))
        }
        Text(
            model.removePrefix("deepseek-"),
            style = MaterialTheme.typography.labelLarge,
            color = fg,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

@Composable
private fun EmptyHint() {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(cs.surfaceContainer)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "点上面新建一个对话，或者去「设置」填一下 DeepSeek 的 API Key。",
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant
        )
    }
}

private fun formatDate(ts: Long): String =
    SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date(ts))

@Composable
private fun ConvoTopEdgeFade(modifier: Modifier = Modifier, height: androidx.compose.ui.unit.Dp = 28.dp) {
    val bg = MaterialTheme.colorScheme.background
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .background(
                Brush.verticalGradient(
                    0.0f to bg,
                    0.55f to bg.copy(alpha = 0.92f),
                    1.0f to bg.copy(alpha = 0f)
                )
            )
    )
}
