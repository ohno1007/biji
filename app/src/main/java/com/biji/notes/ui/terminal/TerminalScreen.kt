package com.biji.notes.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.biji.notes.sandbox.BijiBootstrap
import com.biji.notes.sandbox.InteractiveShell
import com.biji.notes.sandbox.LocalSandbox
import com.biji.notes.ui.glass.bouncyClickable
import kotlinx.coroutines.launch

/** 终端一行。id 稳定，给 LazyColumn 当 key —— 没有 key 的话高速
 *  输出时 Compose 每次都要重新匹配整张列表。 */
private data class TermLine(val id: Long, val text: String, val isErr: Boolean)

/** 输出批量刷新间隔。shell 一个 chunk 可能只有几个字符，逐条推给
 *  UI 会让 LazyColumn 每秒重组几百次。 */
private const val FLUSH_MS = 50L
private const val MAX_LINES = 4000

// Termux-style palette: black bg, green prompt, white default, red stderr.
private val TermBg = Color(0xFF000000)
private val TermFg = Color(0xFFE6E6E6)
private val TermPrompt = Color(0xFF6FE26F)
private val TermPath = Color(0xFF6CD9E5)
private val TermErr = Color(0xFFFF6B6B)
private val TermAccent = Color(0xFF9FBDFF)
private val TermDim = Color(0xFF8A8A8A)

@Composable
fun TerminalScreen(
    sandbox: LocalSandbox,
    bootstrap: BijiBootstrap,
    folder: String,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val workDir = remember(folder) { sandbox.projectRoot(folder) }
    val extraPath = remember(bootstrap.binDir.absolutePath) {
        listOf(bootstrap.binDir.absolutePath)
    }
    val shell = remember(workDir) { InteractiveShell(workDir, extraPath, scope) }
    val lines = remember { mutableStateListOf<TermLine>() }
    // shell 线程往这里塞，UI 侧定时批量取 —— 两边不争锁。
    val pending = remember { java.util.concurrent.ConcurrentLinkedQueue<TermLine>() }
    val nextId = remember { java.util.concurrent.atomic.AtomicLong(0L) }
    // 整个输出区共用一条横向滚动，而不是每行一个 state（4000 行就是
    // 4000 个 state），顺带修了"每行各滚各的"这个怪行为。
    val hScroll = rememberScrollState()
    var input by remember { mutableStateOf(TextFieldValue("")) }
    val listState = rememberLazyListState()
    // 命令历史 + 当前游标（-1 表示「不在历史里」）。
    val history = remember { mutableStateListOf<String>() }
    var histPos by remember { mutableStateOf(-1) }

    // 用户手动上滚后就别再抢着往下拽；回到底部自动恢复跟随。
    val following by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= info.totalItemsCount - 2
        }
    }

    LaunchedEffect(shell) {
        shell.start()
        shell.output.collect { chunk ->
            val pieces = chunk.text.split('\n')
            for (i in pieces.indices) {
                val withNl = if (i < pieces.size - 1) pieces[i] + "\n" else pieces[i]
                if (withNl.isNotEmpty()) {
                    pending.add(TermLine(nextId.getAndIncrement(), withNl, chunk.isStderr))
                }
            }
        }
    }
    // 批量刷新：50ms 一次，把攒下的行一次性并进列表。裁剪用
    // removeRange 一刀切，逐个 removeAt(0) 在 4000 行时是 O(n²)。
    LaunchedEffect(shell) {
        while (true) {
            kotlinx.coroutines.delay(FLUSH_MS)
            if (pending.isEmpty()) continue
            val batch = ArrayList<TermLine>(pending.size)
            while (true) { val x = pending.poll() ?: break; batch.add(x) }
            if (batch.isEmpty()) continue
            val wasFollowing = following
            lines.addAll(batch)
            if (lines.size > MAX_LINES) lines.removeRange(0, lines.size - MAX_LINES)
            // 高频输出时不要用 animateScrollToItem：动画会互相打断，
            // 每帧都在跑插值，比直接跳贵得多。
            if (wasFollowing && lines.isNotEmpty()) listState.scrollToItem(lines.size - 1)
        }
    }
    DisposableEffect(shell) {
        onDispose { shell.shutdown() }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(TermBg)
            .imePadding()
    ) {
        Column(Modifier.fillMaxSize()) {
            TerminalHeader(
                pathLabel = workDir.name.ifBlank { "/" },
                onBack = onBack,
                onInterrupt = { shell.interrupt() },
                onRestart = {
                    shell.shutdown()
                    lines.clear()
                    pending.clear()
                    scope.launch { shell.start() }
                }
            )

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.Top
            ) {
                items(lines, key = { it.id }) { line ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(hScroll)
                    ) {
                        TerminalLine(line.text, line.isErr)
                    }
                }
            }

            TerminalFnKeyRow(
                onKey = { tag, keys ->
                    when (tag) {
                        // ↑ ↓ 切历史命令；其余原样写入 stdin
                        "↑" -> if (history.isNotEmpty()) {
                            histPos = if (histPos < 0) history.lastIndex
                            else (histPos - 1).coerceAtLeast(0)
                            input = TextFieldValue(
                                history[histPos],
                                selection = androidx.compose.ui.text.TextRange(history[histPos].length)
                            )
                        }
                        "↓" -> if (history.isNotEmpty() && histPos >= 0) {
                            histPos = (histPos + 1).coerceAtMost(history.lastIndex + 1)
                            input = if (histPos > history.lastIndex) {
                                histPos = -1
                                TextFieldValue("")
                            } else {
                                TextFieldValue(
                                    history[histPos],
                                    selection = androidx.compose.ui.text.TextRange(history[histPos].length)
                                )
                            }
                        }
                        else -> shell.sendRaw(keys)
                    }
                }
            )

            TerminalInput(
                value = input,
                onValueChange = {
                    input = it
                    if (it.text.isEmpty()) histPos = -1
                },
                onSubmit = {
                    val line = input.text
                    if (line.isNotEmpty()) {
                        if (history.lastOrNull() != line) history += line
                        if (history.size > 200) history.removeAt(0)
                        histPos = -1
                        shell.send(line)
                        input = TextFieldValue("")
                    }
                }
            )
        }
    }
}

@Composable
private fun TerminalHeader(
    pathLabel: String,
    onBack: () -> Unit,
    onInterrupt: () -> Unit,
    onRestart: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TerminalIconBtn(Icons.AutoMirrored.Rounded.ArrowBack, "返回", onBack)
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "终端",
                style = MaterialTheme.typography.titleMedium,
                color = TermFg,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                "→ $pathLabel",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = TermPath,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        TerminalIconBtn(Icons.Rounded.Stop, "中断", onInterrupt)
        TerminalIconBtn(Icons.Outlined.RestartAlt, "重启", onRestart)
    }
}

@Composable
private fun TerminalIconBtn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
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
            tint = TermFg
        )
    }
}

@Composable
private fun TerminalLine(text: String, isErr: Boolean) {
    val rendered = remember(text, isErr) {
        when {
            isErr -> AnnotatedString(text, SpanStyle(color = TermErr))
            text.startsWith("$ ") -> buildAnnotatedString {
                withStyle(SpanStyle(color = TermPrompt, fontWeight = FontWeight.Bold)) {
                    append("$ ")
                }
                withStyle(SpanStyle(color = TermFg)) {
                    append(text.substring(2))
                }
            }
            text.startsWith("→ ") -> buildAnnotatedString {
                withStyle(SpanStyle(color = TermPrompt, fontWeight = FontWeight.Bold)) {
                    append("→ ")
                }
                withStyle(SpanStyle(color = TermPath)) {
                    append(text.substring(2))
                }
            }
            else -> AnnotatedString(text, SpanStyle(color = TermFg))
        }
    }
    Text(
        rendered,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp
    )
}

@Composable
private fun TerminalFnKeyRow(onKey: (String, String) -> Unit) {
    // Termux-equivalent hardware key strip. ESC / arrows etc. use the
    // standard ANSI / xterm byte sequences — sh without a PTY won't
    // act on cursor keys but the bytes still arrive on stdin so any
    // line editor running inside the shell sees them.
    val keys = listOf(
        "Esc" to "",
        "Tab" to "\t",
        "↑" to "[A",
        "↓" to "[B",
        "←" to "[D",
        "→" to "[C",
        "Home" to "[H",
        "End" to "[F",
        "PgUp" to "[5~",
        "PgDn" to "[6~",
        "^C" to "",
        "^D" to ""
    )
    val hScroll = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF101010))
            .horizontalScroll(hScroll)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        keys.forEach { (label, seq) ->
            Box(
                modifier = Modifier
                    .padding(horizontal = 3.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF1C1C1C))
                    .bouncyClickable(pressedScale = 0.92f) { onKey(label, seq) }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    label,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = if (label.startsWith("^")) TermAccent else TermFg,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun TerminalInput(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onSubmit: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF080808))
            .navigationBarsPadding()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "$",
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            color = TermPrompt,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(end = 8.dp)
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(end = 6.dp)
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    color = TermFg
                ),
                cursorBrush = SolidColor(TermPrompt),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (value.text.isEmpty()) {
                        Text(
                            "ls / pwd / uname -a",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp,
                            color = TermDim
                        )
                    }
                    inner()
                }
            )
        }
        Box(
            Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(TermPrompt.copy(alpha = 0.18f))
                .bouncyClickable(enabled = value.text.isNotEmpty(), onClick = onSubmit),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Rounded.ArrowUpward,
                contentDescription = "发送",
                modifier = Modifier.size(18.dp),
                tint = TermPrompt
            )
        }
    }
}
