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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.biji.notes.data.Conversation
import com.biji.notes.data.MODEL_REASONER
import com.biji.notes.ui.glass.bouncyPress
import com.biji.notes.ui.glass.cardContainerColor
import com.biji.notes.ui.glass.cornerRadius
import com.biji.notes.ui.glass.mdSurface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ConversationListScreen(
    vm: ChatViewModel,
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
                top = 0.dp, bottom = 140.dp
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Spacer(Modifier.statusBarsPadding().height(8.dp))
                Header(count = conversations.size, model = settings.model)
                Spacer(Modifier.height(14.dp))
            }
            item { NewChatCard(onClick = onNew) }
            if (conversations.isEmpty()) {
                item { EmptyHint() }
            } else {
                items(conversations, key = { it.id }) { convo ->
                    ConversationRow(
                        convo = convo,
                        onClick = { onOpen(convo.id) },
                        onDelete = { vm.deleteConversation(convo.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(count: Int, model: String) {
    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Text(
            text = "对话",
            style = MaterialTheme.typography.displayLarge.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (count == 0) "和 DeepSeek 开始第一次对话" else "$count 个对话",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.size(8.dp))
            ModelPill(model = model)
        }
    }
}

@Composable
private fun ModelPill(model: String) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .mdSurface(cs.primaryContainer, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (model == MODEL_REASONER) {
            Icon(
                Icons.Rounded.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = cs.onPrimaryContainer
            )
            Spacer(Modifier.size(4.dp))
        }
        Text(
            text = model,
            style = MaterialTheme.typography.labelLarge,
            color = cs.onPrimaryContainer,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun NewChatCard(onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .mdSurface(cs.primary, RoundedCornerShape(cornerRadius()))
            .bouncyPress(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(36.dp)
                .mdSurface(cs.onPrimary, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Rounded.Add,
                contentDescription = null,
                tint = cs.primary,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.size(14.dp))
        Column {
            Text(
                "新建对话",
                style = MaterialTheme.typography.titleMedium,
                color = cs.onPrimary,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "随时和 DeepSeek 聊点什么",
                style = MaterialTheme.typography.labelLarge,
                color = cs.onPrimary.copy(alpha = 0.80f)
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
            .mdSurface(cardContainerColor(1), RoundedCornerShape(cornerRadius()))
            .bouncyPress(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(36.dp)
                .mdSurface(cs.surfaceContainerHighest, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Rounded.ChatBubbleOutline,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = cs.onSurface
            )
        }
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                convo.title,
                style = MaterialTheme.typography.titleMedium,
                color = cs.onSurface,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatDate(convo.updatedAt),
                    style = MaterialTheme.typography.labelLarge,
                    color = cs.onSurfaceVariant
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    convo.model,
                    style = MaterialTheme.typography.labelLarge,
                    color = cs.primary
                )
            }
        }
        Spacer(Modifier.size(8.dp))
        Box(
            Modifier
                .size(36.dp)
                .mdSurface(cs.surfaceContainerHighest, CircleShape)
                .bouncyPress(onClick = onDelete),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Rounded.DeleteOutline,
                contentDescription = "删除",
                modifier = Modifier.size(16.dp),
                tint = cs.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun EmptyHint() {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .mdSurface(cardContainerColor(0), RoundedCornerShape(cornerRadius()))
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
