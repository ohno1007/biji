package com.biji.notes.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.biji.notes.sandbox.LocalSandbox
import com.biji.notes.ui.glass.bouncyClickable
import com.biji.notes.ui.markdown.SyntaxHighlight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Minimal in-app code editor backed by the local sandbox. Two modes:
 *  - View: read-only syntax-highlighted preview.
 *  - Edit: plain BasicTextField with syntax-highlight applied via the
 *    field's visualTransformation; saves through LocalSandbox.
 *
 * Language is inferred from the file extension; falls back to plain
 * text. No completion / next-word prediction in v1 — that needs a real
 * local model and is deferred.
 */
@Composable
fun EditorScreen(
    sandbox: LocalSandbox,
    path: String,
    onBack: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val isDark = cs.background.luminance() < 0.5f
    var loaded by remember(path) { mutableStateOf(false) }
    var loadError by remember(path) { mutableStateOf<String?>(null) }
    var fieldValue by remember(path) { mutableStateOf(TextFieldValue("")) }
    var dirty by remember(path) { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(path) {
        val body = withContext(Dispatchers.IO) {
            runCatching { sandbox.readFile(path, maxBytes = 256 * 1024) }
        }
        body.fold(
            onSuccess = { content ->
                fieldValue = TextFieldValue(content, selection = TextRange(0))
                loaded = true
            },
            onFailure = { err ->
                loadError = err.message ?: err.javaClass.simpleName
                loaded = true
            }
        )
    }

    val lang = remember(path) { langFor(path) }

    Box(
        Modifier
            .fillMaxSize()
            .background(cs.background)
            .statusBarsPadding()
            .imePadding()
    ) {
        Column(Modifier.fillMaxSize()) {
            // Header.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                EditorIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "返回", onBack)
                Spacer(Modifier.width(4.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        path.substringAfterLast('/', path),
                        style = MaterialTheme.typography.titleMedium,
                        color = cs.onBackground,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        if (dirty) "未保存 · $lang" else "$lang · ${fieldValue.text.length} chars",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (dirty) cs.error else cs.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                EditorIconButton(
                    if (editing) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    if (editing) "切到查看" else "切到编辑",
                    { editing = !editing }
                )
                Spacer(Modifier.width(2.dp))
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .bouncyClickable(enabled = dirty && !saving) {
                            saving = true
                            scope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        sandbox.writeFile(path, fieldValue.text, append = false)
                                    }
                                }
                                dirty = false
                                saving = false
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (saving) {
                        CircularProgressIndicator(
                            Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = cs.primary
                        )
                    } else {
                        Icon(
                            Icons.Outlined.Save,
                            contentDescription = "保存",
                            modifier = Modifier.size(20.dp),
                            tint = if (dirty) cs.primary else cs.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                    }
                }
            }
            // Body.
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(cs.surfaceContainer)
                    .padding(12.dp)
                    .navigationBarsPadding()
            ) {
                when {
                    !loaded -> CircularProgressIndicator(
                        Modifier.size(24.dp).align(Alignment.Center),
                        color = cs.primary,
                        strokeWidth = 2.dp
                    )
                    loadError != null -> Text(
                        "读取失败：${loadError}",
                        color = cs.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    editing -> {
                        val hScroll = rememberScrollState()
                        val vScroll = rememberScrollState()
                        BasicTextField(
                            value = fieldValue,
                            onValueChange = {
                                if (it.text != fieldValue.text) dirty = true
                                fieldValue = it
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(vScroll)
                                .horizontalScroll(hScroll),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                color = cs.onSurface
                            ),
                            cursorBrush = SolidColor(cs.primary)
                        )
                    }
                    else -> {
                        val hScroll = rememberScrollState()
                        val vScroll = rememberScrollState()
                        Box(
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(vScroll)
                                .horizontalScroll(hScroll)
                        ) {
                            Text(
                                text = SyntaxHighlight.colorize(
                                    fieldValue.text,
                                    lang,
                                    isDark
                                ),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                color = cs.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .bouncyClickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(20.dp),
            tint = cs.onSurface
        )
    }
}

private fun langFor(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
    "kt", "kts" -> "kotlin"
    "java" -> "java"
    "js", "mjs", "cjs" -> "javascript"
    "ts", "tsx" -> "typescript"
    "py", "pyi" -> "python"
    "json" -> "json"
    "sh", "bash", "zsh" -> "bash"
    "sql" -> "sql"
    "c", "h" -> "c"
    "cpp", "cxx", "cc", "hpp", "hh", "hxx" -> "cpp"
    "md", "markdown" -> "markdown"
    "yaml", "yml" -> "yaml"
    "xml", "html", "htm" -> "xml"
    else -> ""
}
