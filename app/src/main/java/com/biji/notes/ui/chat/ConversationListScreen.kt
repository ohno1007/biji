package com.biji.notes.ui.chat

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.biji.notes.data.Conversation
import com.biji.notes.ui.glass.LiquidGlassState
import com.biji.notes.ui.glass.bouncyPress
import com.biji.notes.ui.glass.liquidGlass
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ConversationListScreen(
    vm: ChatViewModel,
    glass: LiquidGlassState?,
    onOpen: (Long) -> Unit,
    onNew: () -> Unit
) {
    val conversations by vm.conversations.collectAsState()
    val settings by vm.settings.collectAsState()

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = 0.dp, bottom = 160.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Spacer(Modifier.statusBarsPadding().height(8.dp))
                Header(
                    count = conversations.size,
                    thinking = settings.thinking,
                    glass = glass
                )
                Spacer(Modifier.height(16.dp))
            }
            item {
                NewChatButton(glass = glass, onClick = onNew)
            }
            if (conversations.isEmpty()) {
                item { EmptyHint(glass = glass) }
            } else {
                items(conversations, key = { it.id }) { convo ->
                    ConversationCard(
                        convo = convo,
                        glass = glass,
                        onClick = { onOpen(convo.id) },
                        onDelete = { vm.deleteConversation(convo.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(count: Int, thinking: Boolean, glass: LiquidGlassState?) {
    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Text(
            text = "对话",
            style = MaterialTheme.typography.displayLarge.copy(fontWeight = FontWeight.Bold),
            color = LocalContentColor.current
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (count == 0) "和 DeepSeek 开始第一次对话" else "$count 个对话",
                style = MaterialTheme.typography.bodyMedium,
                color = LocalContentColor.current.copy(alpha = 0.65f)
            )
            Spacer(Modifier.height(0.dp))
            if (thinking) {
                Spacer(Modifier.size(8.dp))
                ThinkingPill(glass = glass)
            }
        }
    }
}

@Composable
private fun ThinkingPill(glass: LiquidGlassState?) {
    Row(
        Modifier
            .liquidGlass(
                glass,
                shape = RoundedCornerShape(50),
                cornerRadius = 50.dp,
                blurRadius = 22.dp,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
            )
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Rounded.AutoAwesome,
            contentDescription = null,
            modifier = Modifier.size(12.dp),
            tint = LocalContentColor.current
        )
        Spacer(Modifier.size(4.dp))
        Text(
            "思考",
            style = MaterialTheme.typography.labelLarge,
            color = LocalContentColor.current
        )
    }
}

@Composable
private fun NewChatButton(glass: LiquidGlassState?, onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .liquidGlass(
                glass,
                shape = shape,
                cornerRadius = 22.dp,
                blurRadius = 34.dp,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
            )
            .bouncyPress(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(36.dp)
                .liquidGlass(
                    glass,
                    shape = CircleShape,
                    cornerRadius = 36.dp,
                    blurRadius = 20.dp,
                    tint = Color.White.copy(alpha = 0.22f)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Rounded.Add,
                contentDescription = null,
                tint = LocalContentColor.current,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.size(14.dp))
        Column(Modifier.padding(vertical = 0.dp)) {
            Text(
                "新建对话",
                style = MaterialTheme.typography.titleMedium,
                color = LocalContentColor.current,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "随时和 DeepSeek 聊点什么",
                style = MaterialTheme.typography.labelLarge,
                color = LocalContentColor.current.copy(alpha = 0.65f)
            )
        }
    }
}

@Composable
private fun ConversationCard(
    convo: Conversation,
    glass: LiquidGlassState?,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val shape = RoundedCornerShape(22.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .liquidGlass(glass, shape = shape, cornerRadius = 22.dp, blurRadius = 32.dp)
            .bouncyPress(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(34.dp)
                .liquidGlass(
                    glass,
                    shape = CircleShape,
                    cornerRadius = 34.dp,
                    blurRadius = 18.dp,
                    tint = Color.White.copy(alpha = 0.20f)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Rounded.ChatBubbleOutline,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = LocalContentColor.current
            )
        }
        Spacer(Modifier.size(12.dp))
        Column(Modifier.padding(end = 8.dp).fillMaxWidth().weight(1f)) {
            Text(
                convo.title,
                style = MaterialTheme.typography.titleMedium,
                color = LocalContentColor.current,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatDate(convo.updatedAt),
                    style = MaterialTheme.typography.labelLarge,
                    color = LocalContentColor.current.copy(alpha = 0.55f)
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    if (convo.model == "deepseek-reasoner") "Reasoner" else "Chat",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        Box(
            Modifier
                .size(32.dp)
                .liquidGlass(
                    glass,
                    shape = CircleShape,
                    cornerRadius = 32.dp,
                    blurRadius = 18.dp,
                    tint = Color.White.copy(alpha = 0.12f)
                )
                .bouncyPress(onClick = onDelete),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Rounded.DeleteOutline,
                contentDescription = "删除",
                modifier = Modifier.size(16.dp),
                tint = LocalContentColor.current.copy(alpha = 0.85f)
            )
        }
    }
}

@Composable
private fun EmptyHint(glass: LiquidGlassState?) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .liquidGlass(
                glass,
                shape = RoundedCornerShape(22.dp),
                cornerRadius = 22.dp,
                blurRadius = 30.dp
            )
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "点上面新建一个对话，或者去「设置」填一下 DeepSeek 的 API Key。",
            style = MaterialTheme.typography.bodyMedium,
            color = LocalContentColor.current.copy(alpha = 0.75f)
        )
    }
}

private fun formatDate(ts: Long): String =
    SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date(ts))
