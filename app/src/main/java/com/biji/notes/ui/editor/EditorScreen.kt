package com.biji.notes.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.CompareArrows
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
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

private enum class EditorMode { VIEW, EDIT, DIFF }

/**
 * In-app sandbox-scoped code editor. Three modes:
 *  - VIEW: read-only, SyntaxHighlight-colourised display.
 *  - EDIT: BasicTextField + bottom completion strip (language keyword
 *    + buffer-identifier prefix matches).
 *  - DIFF: line-by-line comparison against the most recent pre-edit
 *    snapshot taken by the AI write tool, if any.
 *
 * Completion / suggestion is local & lightweight — no LM behind it,
 * but enough to take the edge off mobile-keyboard typing.
 */
@Composable
fun EditorScreen(
    sandbox: LocalSandbox,
    folder: String,
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
    var mode by remember { mutableStateOf(EditorMode.VIEW) }
    val scope = rememberCoroutineScope()
    val aiEdited by sandbox.aiEditedPaths.collectAsState()
    val editKeyForPath = remember(folder, path) {
        val f = folder.ifEmpty { LocalSandbox.DEFAULT_FOLDER }
        "$f/${path.trimStart('/')}"
    }
    val hasDiff = editKeyForPath in aiEdited && sandbox.snapshotBefore(folder, path) != null

    LaunchedEffect(path) {
        val body = withContext(Dispatchers.IO) {
            runCatching { sandbox.readFile(folder, path, maxBytes = 256 * 1024) }
        }
        body.fold(
            onSuccess = { content ->
                fieldValue = TextFieldValue(content, selection = TextRange(content.length))
                loaded = true
            },
            onFailure = { err ->
                loadError = err.message ?: err.javaClass.simpleName
                loaded = true
            }
        )
    }

    val lang = remember(path) { langFor(path) }

    // Live completion suggestions for EDIT mode. Recomputed on every
    // recompose — cheap enough that derivedStateOf would only add
    // bookkeeping without a real benefit, and avoids a Kotlin type
    // inference quirk where the delegate landed as `Any?`.
    val completion: Pair<String, List<CompletionItem>> =
        if (mode == EditorMode.EDIT) suggestCompletions(fieldValue, lang)
        else "" to emptyList()

    // Bracket / quote balance scan — runCatching just in case the
    // simple state machine ever bumps into something pathological.
    val errorRanges = remember(fieldValue.text, mode) {
        if (mode != EditorMode.EDIT) emptyList()
        else runCatching { findSyntaxIssues(fieldValue.text) }.getOrDefault(emptyList())
    }
    val syntaxTransform = remember(lang, isDark, errorRanges) {
        SyntaxVisualTransformation(lang, isDark, errorRanges)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(cs.background)
            .imePadding()
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(top = 56.dp)
        ) {
            // Body
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(cs.surfaceContainer)
                    .padding(if (mode == EditorMode.DIFF) 0.dp else 12.dp)
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
                    mode == EditorMode.DIFF -> {
                        val before = sandbox.snapshotBefore(folder, path).orEmpty()
                        DiffView(before = before, after = fieldValue.text)
                    }
                    mode == EditorMode.EDIT -> {
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
                            cursorBrush = SolidColor(cs.primary),
                            visualTransformation = syntaxTransform
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
            // Completion strip + tiny error counter — only when actively
            // editing. Errors flash a small red pill on the right end of
            // the strip; tapping it does nothing for now (jump-to-issue
            // would need a measured layout we don't track).
            if (mode == EditorMode.EDIT) {
                val (prefix, sugs) = completion
                if (errorRanges.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(cs.surfaceContainer)
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(cs.error)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "${errorRanges.size} 个语法问题（括号 / 引号未闭合）",
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.error
                        )
                    }
                }
                CompletionStrip(
                    prefix = prefix,
                    suggestions = sugs,
                    onPick = { item ->
                        fieldValue = applySuggestion(fieldValue, item)
                        dirty = true
                    },
                    modifier = Modifier.navigationBarsPadding()
                )
            } else {
                Spacer(Modifier.navigationBarsPadding())
            }
        }

        // Top fade — same vertical gradient as the chat screen so the
        // content scrolls under a soft cream / dark veil rather than
        // a hard edge.
        EditorTopFade(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
        )

        // Floating top bar — left title, right action cluster. Sits
        // above the fade so the controls stay readable while the file
        // scrolls underneath.
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
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
                    when {
                        dirty -> "未保存 · $lang"
                        mode == EditorMode.DIFF -> "Diff · 对比 AI 修改前"
                        else -> "$lang · ${fieldValue.text.length} chars"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (dirty) cs.error else cs.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            if (hasDiff) {
                EditorIconButton(
                    Icons.Outlined.CompareArrows,
                    "查看 AI 改动",
                    {
                        mode = if (mode == EditorMode.DIFF) EditorMode.VIEW
                        else EditorMode.DIFF
                    }
                )
            }
            EditorIconButton(
                if (mode == EditorMode.EDIT) Icons.Outlined.VisibilityOff
                else Icons.Outlined.Visibility,
                if (mode == EditorMode.EDIT) "切到查看" else "切到编辑",
                {
                    mode = if (mode == EditorMode.EDIT) EditorMode.VIEW
                    else EditorMode.EDIT
                }
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
                                    sandbox.writeFile(folder, path, fieldValue.text, append = false)
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
    }
}

/** Same vertical gradient TopFade as the chat screen — soft veil
 *  fading from fully-opaque background at top to transparent below.
 *  Status-bar inset is added in here so callers can just align it at
 *  the top of the layout. */
@Composable
private fun EditorTopFade(modifier: Modifier = Modifier) {
    val bg = MaterialTheme.colorScheme.background
    val statusBarHeight =
        WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Box(
        modifier
            .fillMaxWidth()
            .height(96.dp + statusBarHeight)
            .background(
                Brush.verticalGradient(
                    0.0f to bg,
                    0.55f to bg.copy(alpha = 0.92f),
                    1.0f to bg.copy(alpha = 0f)
                )
            )
    )
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
