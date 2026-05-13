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
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.rounded.Check
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
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

private val Lite = Json { ignoreUnknownKeys = true }

/**
 * Codex-style workflow card: a compact, chronologically-ordered list of
 * tool calls inside a single frosted-glass surface. 80% wide so it
 * visually steps back from the user's message bubbles and the assistant
 * answer text. The whole card collapses to a one-line summary; tap to
 * expand the full list; tap each entry to expand its detailed result.
 */
@Composable
fun WorkflowCard(
    entries: List<Message>,
    onOpenUrl: (String) -> Unit
) {
    if (entries.isEmpty()) return
    val cs = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(true) }
    val rotation by animateFloatAsState(
        targetValue = if (open) 180f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "wfChev"
    )

    val previews = entries.map { describe(it) }
    val summary = when (entries.size) {
        1 -> previews.first().headline
        else -> "${entries.size} 步工作流 · ${previews.first().headline}"
    }

    // "Frosted" look: very translucent surface tone + 0.5dp hairline.
    // The blur is intentionally small (3 dp on API 31+) so the edge
    // feathers without smearing the interior text. Below API 31 the
    // translucent fill alone still reads as "glass on the cream wash".
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .fillMaxWidth(0.8f)
                .clip(RoundedCornerShape(16.dp))
                .background(cs.surface.copy(alpha = 0.78f))
                .border(
                    width = 0.5.dp,
                    color = cs.outlineVariant.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(16.dp)
                )
                .blur(if (android.os.Build.VERSION.SDK_INT >= 31) 0.dp else 0.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .bouncyClickable(pressedScale = 0.99f) { open = !open }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(cs.primary.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = cs.primary
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    summary,
                    style = MaterialTheme.typography.labelLarge,
                    color = cs.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    Icons.Rounded.ExpandMore,
                    contentDescription = if (open) "收起" else "展开",
                    modifier = Modifier
                        .size(16.dp)
                        .rotate(rotation),
                    tint = cs.onSurfaceVariant
                )
            }
            AnimatedVisibility(
                visible = open,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 10.dp, top = 0.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    entries.forEachIndexed { i, m ->
                        WorkflowEntry(
                            preview = previews[i],
                            message = m,
                            onOpenUrl = onOpenUrl
                        )
                    }
                }
            }
        }
    }
}

private data class WorkflowPreview(
    val icon: ImageVector,
    val headline: String,
    val data: JsonObject?
)

private fun describe(m: Message): WorkflowPreview {
    val data = runCatching { Lite.parseToJsonElement(m.toolData.orEmpty()).jsonObject }.getOrNull()
    val kind = data?.get("kind")?.jsonPrimitive?.contentOrNull
    return when (kind) {
        Tools.WEB_SEARCH -> {
            val q = data?.get("query")?.jsonPrimitive?.contentOrNull.orEmpty()
            WorkflowPreview(
                icon = Icons.Outlined.Search,
                headline = if (q.isBlank()) "联网搜索" else "联网搜索 “$q”",
                data = data
            )
        }
        Tools.READ_URL -> {
            val url = data?.get("url")?.jsonPrimitive?.contentOrNull.orEmpty()
            val title = data?.get("title")?.jsonPrimitive?.contentOrNull
            val host = runCatching { url.toUri().host.orEmpty() }.getOrDefault("")
            val tag = title?.takeIf { it.isNotBlank() } ?: host.ifBlank { url }
            WorkflowPreview(
                icon = Icons.Outlined.Article,
                headline = "网页解析 $tag",
                data = data
            )
        }
        else -> WorkflowPreview(
            icon = Icons.Outlined.Article,
            headline = "工具结果",
            data = data
        )
    }
}

@Composable
private fun WorkflowEntry(
    preview: WorkflowPreview,
    message: Message,
    onOpenUrl: (String) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "wfEntryChev"
    )
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .bouncyClickable(pressedScale = 0.99f) { expanded = !expanded }
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                preview.icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = cs.primary
            )
            Spacer(Modifier.width(8.dp))
            Text(
                preview.headline,
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Icon(
                Icons.Rounded.ExpandMore,
                contentDescription = null,
                modifier = Modifier
                    .size(14.dp)
                    .rotate(rotation),
                tint = cs.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Box(Modifier.padding(start = 22.dp, end = 4.dp, bottom = 4.dp, top = 2.dp)) {
                when (preview.data?.get("kind")?.jsonPrimitive?.contentOrNull) {
                    Tools.WEB_SEARCH -> SearchResultsDetail(preview.data, onOpenUrl)
                    Tools.READ_URL -> ArticleDetail(preview.data, onOpenUrl)
                    else -> Text(
                        message.content.take(400),
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurfaceVariant
                    )
                }
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
        results.take(5).forEach { el ->
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
                    .padding(horizontal = 6.dp, vertical = 6.dp)
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

@Composable
private fun ArticleDetail(data: JsonObject, onOpenUrl: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val url = data["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val excerpt = data["excerpt"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val site = data["siteName"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val err = data["error"]?.jsonPrimitive?.contentOrNull
    Column {
        if (err != null) {
            Text(err, style = MaterialTheme.typography.bodyMedium, color = cs.error)
            return
        }
        if (site.isNotBlank()) {
            Text(site, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
        if (excerpt.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                excerpt,
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (url.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .bouncyClickable(pressedScale = 0.99f) { onOpenUrl(url) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Outlined.Public,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = cs.primary
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "打开网页",
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

// =====================================================================
// Backwards-compat single-card renderer kept for any callsite that still
// invokes ToolMessageCard directly (e.g. preview, error paths). The
// chat screen itself now batches tool messages through [WorkflowCard].
// =====================================================================

@Composable
fun ToolMessageCard(
    message: Message,
    onOpenUrl: (String) -> Unit
) {
    WorkflowCard(entries = listOf(message), onOpenUrl = onOpenUrl)
}
