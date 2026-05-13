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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val Lite = Json { ignoreUnknownKeys = true }

@Composable
fun ToolMessageCard(
    message: Message,
    onOpenUrl: (String) -> Unit
) {
    val data = runCatching { Lite.parseToJsonElement(message.toolData.orEmpty()).jsonObject }
        .getOrNull() ?: return
    when (data["kind"]?.jsonPrimitive?.contentOrNull) {
        Tools.WEB_SEARCH -> SearchResultsCard(data, onOpenUrl)
        Tools.READ_URL -> ArticlePreviewCard(data, onOpenUrl)
        else -> GenericToolCard(text = message.content)
    }
}

@Composable
private fun SearchResultsCard(data: JsonObject, onOpenUrl: (String) -> Unit) {
    val query = data["query"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val results = (data["results"] as? JsonArray) ?: return

    ToolCardShell(
        icon = Icons.Outlined.Search,
        label = "联网搜索",
        sub = if (query.isBlank()) "" else "“$query”"
    ) {
        if (results.isEmpty()) {
            Text(
                "没有返回结果",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@ToolCardShell
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            results.forEachIndexed { idx, el ->
                val obj = el.jsonObject
                val title = obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val url = obj["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val snippet = obj["snippet"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val source = obj["source"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (title.isNotBlank() && url.isNotBlank()) {
                    if (idx > 0) InsetGap()
                    SearchResultRow(
                        title = title,
                        url = url,
                        snippet = snippet,
                        source = source,
                        onClick = { onOpenUrl(url) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(
    title: String,
    url: String,
    snippet: String,
    source: String,
    onClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val host = runCatching { url.toUri().host.orEmpty() }.getOrDefault("")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .bouncyClickable(pressedScale = 0.985f, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(cs.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Outlined.Public,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = cs.primary
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = cs.onSurface,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (snippet.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    snippet,
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    host.ifBlank { url },
                    style = MaterialTheme.typography.labelLarge,
                    color = cs.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (source.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        source,
                        style = MaterialTheme.typography.labelLarge,
                        color = cs.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ArticlePreviewCard(data: JsonObject, onOpenUrl: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val url = data["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val title = data["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val excerpt = data["excerpt"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val site = data["siteName"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val err = data["error"]?.jsonPrimitive?.contentOrNull

    ToolCardShell(
        icon = Icons.Outlined.Article,
        label = "网页解析",
        sub = title.ifBlank { url }
    ) {
        if (err != null) {
            Text(err, style = MaterialTheme.typography.bodyMedium, color = cs.error)
            return@ToolCardShell
        }
        if (site.isNotBlank()) {
            Text(
                site,
                style = MaterialTheme.typography.labelLarge,
                color = cs.onSurfaceVariant
            )
        }
        if (excerpt.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                excerpt,
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurface,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .bouncyClickable(onClick = { if (url.isNotBlank()) onOpenUrl(url) })
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.Public,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = cs.primary
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "应用内打开网页",
                style = MaterialTheme.typography.labelLarge,
                color = cs.primary,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun GenericToolCard(text: String) {
    val cs = MaterialTheme.colorScheme
    ToolCardShell(
        icon = Icons.Outlined.Article,
        label = "工具结果",
        sub = ""
    ) {
        Text(
            text.take(800),
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant
        )
    }
}

@Composable
private fun ToolCardShell(
    icon: ImageVector,
    label: String,
    sub: String,
    content: @Composable () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "toolChev"
    )
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(cs.surfaceContainer)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .bouncyClickable(pressedScale = 0.99f) { expanded = !expanded }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(cs.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = cs.onPrimaryContainer
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = cs.onSurface,
                fontWeight = FontWeight.SemiBold
            )
            if (sub.isNotBlank()) {
                Spacer(Modifier.width(6.dp))
                Text(
                    sub,
                    style = MaterialTheme.typography.labelLarge,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            Icon(
                Icons.Rounded.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开",
                modifier = Modifier.size(18.dp).rotate(rotation),
                tint = cs.onSurfaceVariant
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp, bottom = 12.dp, top = 2.dp)
            ) { content() }
        }
    }
}

@Composable
private fun InsetGap() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 38.dp, top = 2.dp, bottom = 2.dp)
            .height(0.5.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}
