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
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Terminal
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
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

// =====================================================================
// SandboxToolCard — for read_file / write_file / list_directory /
// run_shell_command results. Codex-style two-level expand: header
// shows command/path summary, first tap reveals an output preview,
// second tap reveals the full body in a scrollable monospace block.
// =====================================================================

@Composable
fun SandboxToolCard(message: Message) {
    val cs = MaterialTheme.colorScheme
    val data = runCatching { Lite.parseToJsonElement(message.toolData.orEmpty()).jsonObject }
        .getOrNull() ?: return
    val kind = data["kind"]?.jsonPrimitive?.contentOrNull ?: return
    val (icon, header, primaryLine, body) = describeSandbox(kind, data)
    var open by remember { mutableStateOf(false) }
    var fullOpen by remember { mutableStateOf(false) }
    val rotation by animateFloatAsState(
        targetValue = if (open) 90f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "sandboxChev"
    )
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(cs.surfaceContainer)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .bouncyClickable(pressedScale = 0.99f) { open = !open }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon, contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = cs.primary
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    header,
                    style = MaterialTheme.typography.labelLarge,
                    color = cs.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                if (primaryLine.isNotBlank()) {
                    Text(
                        primaryLine,
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                }
            }
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = if (open) "收起" else "展开",
                modifier = Modifier.size(14.dp).rotate(rotation),
                tint = cs.onSurfaceVariant
            )
        }
        AnimatedVisibility(
            visible = open,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp)) {
                // run_shell_command → render a high-contrast terminal
                // view (bold prompt, dim metadata, red stderr / non-
                // zero exit) instead of the flat monospace dump.
                if (kind == "run_shell_command") {
                    TerminalView(data)
                    return@Column
                }
                // write_file with a before/after snapshot → render a
                // proper diff inline in chat (the user no longer has
                // to open the editor for a quick glance).
                if (kind == "write_file") {
                    val before = data.str("before").orEmpty()
                    val after = data.str("after").orEmpty()
                    val truncated = data.bool("truncated") == true
                    if (before.isNotEmpty() || after.isNotEmpty()) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(cs.surface)
                        ) {
                            com.biji.notes.ui.editor.DiffView(
                                before = before,
                                after = after
                            )
                        }
                        if (truncated) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "（输出过长，diff 已截断到 32 KB 每侧）",
                                style = MaterialTheme.typography.labelSmall,
                                color = cs.onSurfaceVariant
                            )
                        }
                        return@Column
                    }
                }
                // Default path — preview ≤600 chars, second tap to expand.
                val preview = body.take(600)
                val isTruncated = body.length > preview.length
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(cs.surface)
                        .padding(10.dp)
                ) {
                    Text(
                        if (fullOpen) body else preview,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurface
                    )
                }
                if (isTruncated) {
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .bouncyClickable(pressedScale = 0.97f) { fullOpen = !fullOpen }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (fullOpen) "收起完整输出" else "展开完整输出 (${body.length} 字符)",
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

private data class SandboxPreview(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val header: String,
    val primaryLine: String,
    val body: String
)

private fun describeSandbox(kind: String, data: JsonObject): SandboxPreview =
    runCatching { describeSandboxImpl(kind, data) }
        .getOrElse { err ->
            // ANY exception in field parsing — wrong JSON shape, missing
            // key, weird Unicode in shell output — surfaces as a tiny
            // notice instead of propagating into composition and
            // crashing the chat. The trace is still captured by
            // CrashHandler if we want to dig in later.
            SandboxPreview(
                icon = Icons.Outlined.Description,
                header = "工具结果",
                primaryLine = "解析失败: ${err.message ?: err.javaClass.simpleName}",
                body = data.toString().take(2000)
            )
        }

private fun JsonObject.str(key: String): String? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
private fun JsonObject.long(key: String): Long? = str(key)?.toLongOrNull()
private fun JsonObject.int(key: String): Int? = str(key)?.toIntOrNull()
private fun JsonObject.bool(key: String): Boolean? = str(key)?.toBooleanStrictOrNull()
private fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

private fun describeSandboxImpl(kind: String, data: JsonObject): SandboxPreview = when (kind) {
    "list_directory" -> {
        val path = data.str("path").orEmpty()
        val entries = data.arr("entries") ?: kotlinx.serialization.json.JsonArray(emptyList())
        val body = buildString {
            entries.forEach { el ->
                val o = (el as? JsonObject) ?: return@forEach
                val name = o.str("name").orEmpty()
                val isDir = o.bool("isDirectory") == true
                val size = o.long("sizeBytes") ?: -1L
                appendLine(if (isDir) "[dir]  $name" else "${size}B  $name")
            }
            if (entries.isEmpty()) appendLine("(空目录)")
        }
        SandboxPreview(
            icon = Icons.Outlined.FolderOpen,
            header = "列目录",
            primaryLine = if (path.isBlank()) "/" else path,
            body = body
        )
    }
    "read_file" -> {
        val path = data.str("path").orEmpty()
        val bytes = data.int("bytes") ?: 0
        val content = data.str("content").orEmpty()
        SandboxPreview(
            icon = Icons.Outlined.Description,
            header = "读取文件",
            primaryLine = "$path ($bytes bytes)",
            body = content
        )
    }
    "write_file" -> {
        val path = data.str("path").orEmpty()
        val bytes = data.int("bytes") ?: 0
        val append = data.bool("append") == true
        SandboxPreview(
            icon = Icons.Outlined.Edit,
            header = if (append) "追加文件" else "写入文件",
            primaryLine = "$path ($bytes bytes)",
            body = "已${if (append) "追加" else "写入"} ${bytes} 字节到 $path"
        )
    }
    "run_shell_command" -> {
        val command = data.str("command").orEmpty()
        val cwd = data.str("workingDir").orEmpty()
        val exit = data.int("exitCode") ?: -1
        val duration = data.long("durationMs") ?: 0L
        val timedOut = data.bool("timedOut") == true
        val stdout = data.str("stdout").orEmpty()
        val stderr = data.str("stderr").orEmpty()
        val body = buildString {
            appendLine("$ $command")
            appendLine("# cwd=$cwd  exit=$exit  ${duration}ms${if (timedOut) "  TIMEOUT" else ""}")
            if (stdout.isNotEmpty()) {
                appendLine("--- stdout ---")
                append(stdout)
                if (!stdout.endsWith("\n")) appendLine()
            }
            if (stderr.isNotEmpty()) {
                appendLine("--- stderr ---")
                append(stderr)
            }
        }
        SandboxPreview(
            icon = Icons.Outlined.Terminal,
            header = "运行命令",
            primaryLine = command,
            body = body
        )
    }
    else -> SandboxPreview(
        icon = Icons.Outlined.Description,
        header = "工具结果",
        primaryLine = "",
        body = data.toString()
    )
}

/**
 * High-contrast terminal-style render for run_shell_command results.
 * - Prompt line `$ <cmd>` rendered bold in the primary tint.
 * - Metadata line (cwd / exit / duration) dimmed; exit≠0 in red.
 * - stdout: normal monospace.
 * - stderr: red-tinted background pane.
 * Each pane has its own background so the boundary between stdout
 * and stderr is obvious at a glance.
 */
@Composable
private fun TerminalView(data: JsonObject) {
    val cs = MaterialTheme.colorScheme
    val isDark = cs.background.luminance() < 0.5f
    val command = data.str("command").orEmpty()
    val cwd = data.str("workingDir").orEmpty()
    val exit = data.int("exitCode") ?: -1
    val duration = data.long("durationMs") ?: 0L
    val timedOut = data.bool("timedOut") == true
    val stdout = data.str("stdout").orEmpty()
    val stderr = data.str("stderr").orEmpty()
    var fullOpen by remember(command, stdout, stderr) { mutableStateOf(false) }
    val totalLen = stdout.length + stderr.length

    val mono = androidx.compose.ui.text.font.FontFamily.Monospace
    val terminalBg = if (isDark) Color(0xFF0F0F12) else Color(0xFFF4F1EA)
    val terminalFg = if (isDark) Color(0xFFE6E1D8) else Color(0xFF1A1A1F)
    val promptFg = cs.primary
    val errBg = if (isDark) Color(0xFF3A1518) else Color(0xFFFBE2E4)
    val errFg = if (isDark) Color(0xFFE07C84) else Color(0xFFB42E3F)
    val metaFg = cs.onSurfaceVariant
    val exitFg = if (exit == 0 && !timedOut) cs.primary else cs.error

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(terminalBg)
            .padding(10.dp)
    ) {
        // Prompt
        Row(verticalAlignment = androidx.compose.ui.Alignment.Top) {
            Text(
                "$ ",
                fontFamily = mono,
                color = promptFg,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                command,
                fontFamily = mono,
                color = terminalFg,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodySmall
            )
        }
        // Metadata
        Text(
            buildString {
                append("# cwd=$cwd")
                append("  exit=")
            },
            fontFamily = mono,
            color = metaFg,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 2.dp)
        )
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(
                "exit $exit  ${duration}ms${if (timedOut) "  (TIMEOUT)" else ""}",
                fontFamily = mono,
                color = exitFg,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.labelSmall
            )
        }
        // stdout
        if (stdout.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            val previewOut = if (fullOpen) stdout else stdout.take(600)
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(terminalBg.copy(alpha = if (isDark) 0.6f else 1f))
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            ) {
                Text(
                    previewOut,
                    fontFamily = mono,
                    color = terminalFg,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        // stderr — its own red-tinted pane.
        if (stderr.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            val previewErr = if (fullOpen) stderr else stderr.take(600)
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(errBg)
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            ) {
                Text(
                    previewErr,
                    fontFamily = mono,
                    color = errFg,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        // Second-level expand for long combined output.
        if (totalLen > 600 && !fullOpen) {
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .bouncyClickable(pressedScale = 0.97f) { fullOpen = true }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Text(
                    "展开完整输出 ($totalLen 字符)",
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }
        } else if (fullOpen && totalLen > 600) {
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .bouncyClickable(pressedScale = 0.97f) { fullOpen = false }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Text(
                    "收起完整输出",
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
