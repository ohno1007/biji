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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.biji.notes.sandbox.BijiBootstrap
import com.biji.notes.sandbox.InteractiveShell
import com.biji.notes.sandbox.LocalSandbox
import com.biji.notes.ui.glass.bouncyClickable
import kotlinx.coroutines.launch

/**
 * Persistent terminal screen. Holds a long-lived `sh` process whose
 * cwd + env survive across user inputs — the Termux experience minus
 * a true PTY (no live cursor, but ANSI byte streams pass through
 * intact). Output ticks in real-time as the shell produces it.
 */
@Composable
fun TerminalScreen(
    sandbox: LocalSandbox,
    bootstrap: BijiBootstrap,
    folder: String,
    onBack: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val workDir = remember(folder) { sandbox.projectRoot(folder) }
    val extraPath = remember(bootstrap.binDir.absolutePath) {
        listOf(bootstrap.binDir.absolutePath)
    }
    val shell = remember(workDir) {
        InteractiveShell(workDir, extraPath, scope)
    }
    val lines = remember { mutableStateListOf<InteractiveShell.Chunk>() }
    var input by remember { mutableStateOf(TextFieldValue("")) }
    val listState = rememberLazyListState()

    LaunchedEffect(shell) {
        shell.start()
        shell.output.collect { chunk ->
            // Coalesce on newline boundaries so multi-line output
            // shows up as separate row entries.
            val pieces = chunk.text.split('\n')
            pieces.forEachIndexed { i, piece ->
                val withNl = if (i < pieces.size - 1) piece + "\n" else piece
                if (withNl.isNotEmpty()) {
                    lines += InteractiveShell.Chunk(withNl, chunk.isStderr)
                }
            }
            // Cap history so an `infinite-loop | hexdump` doesn't OOM us.
            while (lines.size > 4000) lines.removeAt(0)
        }
    }
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
    }
    DisposableEffect(shell) {
        onDispose { shell.shutdown() }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(cs.background)
            .imePadding()
    ) {
        Column(Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .bouncyClickable(onClick = onBack),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "返回",
                        modifier = Modifier.size(20.dp),
                        tint = cs.onSurface
                    )
                }
                Spacer(Modifier.width(4.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "终端",
                        style = MaterialTheme.typography.titleMedium,
                        color = cs.onBackground,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        workDir.absolutePath,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = cs.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .bouncyClickable {
                            shell.interrupt()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.Stop,
                        contentDescription = "中断当前命令",
                        modifier = Modifier.size(20.dp),
                        tint = cs.onSurface
                    )
                }
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .bouncyClickable {
                            shell.shutdown()
                            lines.clear()
                            scope.launch { shell.start() }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.RestartAlt,
                        contentDescription = "重启 shell",
                        modifier = Modifier.size(20.dp),
                        tint = cs.onSurface
                    )
                }
            }

            // Output history
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(cs.surfaceContainerLowest)
                    .padding(8.dp),
                verticalArrangement = Arrangement.Top
            ) {
                items(lines.toList()) { chunk ->
                    val color = if (chunk.isStderr) cs.error else cs.onSurface
                    val hScroll = rememberScrollState()
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(hScroll)
                    ) {
                        Text(
                            chunk.text,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = color
                        )
                    }
                }
            }

            // Input
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(20.dp))
                        .background(cs.surfaceContainer)
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    BasicTextField(
                        value = input,
                        onValueChange = { input = it },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            color = cs.onSurface
                        ),
                        cursorBrush = SolidColor(cs.primary),
                        modifier = Modifier.fillMaxWidth(),
                        decorationBox = { inner ->
                            if (input.text.isEmpty()) {
                                Text(
                                    "在这里输入命令（例如 ls / pwd / uname -a）",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontFamily = FontFamily.Monospace
                                    ),
                                    color = cs.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                            }
                            inner()
                        }
                    )
                }
                Spacer(Modifier.width(6.dp))
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(cs.primary)
                        .bouncyClickable(enabled = input.text.isNotBlank()) {
                            val line = input.text
                            if (line.isNotBlank()) {
                                shell.send(line)
                                input = TextFieldValue("")
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.ArrowUpward,
                        contentDescription = "发送",
                        modifier = Modifier.size(20.dp),
                        tint = cs.onPrimary
                    )
                }
            }
        }
    }
}

