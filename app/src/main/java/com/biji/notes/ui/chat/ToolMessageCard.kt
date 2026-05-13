package com.biji.notes.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.biji.notes.data.Message
import com.biji.notes.net.Tools
import com.biji.notes.ui.glass.bouncyClickable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal val Lite = Json { ignoreUnknownKeys = true }

/**
 * Compact Claude-style "Searched for X" inline chip. Tap once to expand
 * the search results, tap again to collapse. read_url tool calls are NOT
 * surfaced — the article content is already folded into the assistant's
 * follow-up answer, so the chip would be noise.
 */
@Composable
fun SearchedForChip(message: Message, onOpenUrl: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val data = runCatching { Lite.parseToJsonElement(message.toolData.orEmpty()).jsonObject }
        .getOrNull() ?: return
    if (data["kind"]?.jsonPrimitive?.contentOrNull != Tools.WEB_SEARCH) return

    val query = data["query"]?.jsonPrimitive?.contentOrNull.orEmpty()
    var open by remember { mutableStateOf(false) }
    val rotation by animateFloatAsState(
        targetValue = if (open) 90f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "chevSearch"
    )
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .bouncyClickable(pressedScale = 0.97f) { open = !open }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.Search,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = cs.onSurfaceVariant
            )
            Spacer(Modifier.width(6.dp))
            Text(
                if (query.isBlank()) "Searched for…" else "Searched for “$query”",
                style = MaterialTheme.typography.labelLarge,
                color = cs.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = if (open) "收起" else "展开",
                modifier = Modifier
                    .size(14.dp)
                    .rotate(rotation),
                tint = cs.onSurfaceVariant
            )
        }
        AnimatedVisibility(
            visible = open,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Box(Modifier.padding(top = 4.dp, bottom = 4.dp, start = 8.dp, end = 8.dp)) {
                SearchResultsDetail(data, onOpenUrl)
            }
        }
    }
}

@Composable
private fun SearchResultsDetail(data: JsonObject, onOpenUrl: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val results = (data["results"] as? JsonArray) ?: return
    if (results.isEmpty()) {
        Text("没有返回结果", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        results.take(6).forEach { el ->
            val obj = el.jsonObject
            val title = obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val url = obj["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val snippet = obj["snippet"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (title.isBlank() || url.isBlank()) return@forEach
            val host = runCatching { url.toUri().host.orEmpty() }.getOrDefault("")
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .bouncyClickable(pressedScale = 0.99f) { onOpenUrl(url) }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (snippet.isNotBlank()) {
                    Text(
                        snippet,
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.Public,
                        contentDescription = null,
                        modifier = Modifier.size(11.dp),
                        tint = cs.primary
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        host.ifBlank { url },
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

// Legacy shim — anyone still calling ToolMessageCard now falls through
// the same compact chip.
@Composable
fun ToolMessageCard(message: Message, onOpenUrl: (String) -> Unit) {
    SearchedForChip(message = message, onOpenUrl = onOpenUrl)
}
