package com.biji.notes.ui.notes

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import com.biji.notes.data.Note
import com.biji.notes.ui.glass.LiquidGlassState
import com.biji.notes.ui.glass.liquidGlass
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun NotesListScreen(
    vm: NotesViewModel,
    glass: LiquidGlassState?,
    onNoteClick: (Long) -> Unit,
    onNewNote: () -> Unit
) {
    val state by vm.state.collectAsState()

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = 0.dp, bottom = 120.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Spacer(Modifier.statusBarsPadding().height(8.dp))
                Header(count = state.all.size)
                Spacer(Modifier.height(16.dp))
            }
            item {
                SearchBar(
                    query = state.query,
                    onQueryChange = vm::setQuery,
                    glass = glass
                )
                Spacer(Modifier.height(4.dp))
            }
            if (state.visible.isEmpty()) {
                item { EmptyState(query = state.query, glass = glass) }
            } else {
                items(state.visible, key = { it.id }) { note ->
                    NoteCard(note = note, glass = glass, onClick = { onNoteClick(note.id) })
                }
            }
        }

        FloatingNewButton(
            glass = glass,
            onClick = onNewNote,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(24.dp)
        )
    }
}

@Composable
private fun Header(count: Int) {
    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Text(
            text = "笔记",
            style = MaterialTheme.typography.displayLarge.copy(fontWeight = FontWeight.Bold),
            color = LocalContentColor.current
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = if (count == 0) "开始写下你的第一条" else "$count 条记录",
            style = MaterialTheme.typography.bodyMedium,
            color = LocalContentColor.current.copy(alpha = 0.65f)
        )
    }
}

@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    glass: LiquidGlassState?
) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .liquidGlass(glass, shape = shape, blurRadius = 28.dp)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Rounded.Search,
            contentDescription = null,
            tint = LocalContentColor.current.copy(alpha = 0.7f),
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    "搜索标题或内容",
                    style = MaterialTheme.typography.bodyLarge,
                    color = LocalContentColor.current.copy(alpha = 0.45f)
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = LocalContentColor.current),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth()
            )
        }
        AnimatedVisibility(
            visible = query.isNotEmpty(),
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut()
        ) {
            Box(
                Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .clickable { onQueryChange("") }
                    .background(LocalContentColor.current.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = "清除",
                    modifier = Modifier.size(14.dp),
                    tint = LocalContentColor.current
                )
            }
        }
    }
}

@Composable
private fun NoteCard(
    note: Note,
    glass: LiquidGlassState?,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(24.dp)
    val displayTitle = note.title.ifBlank { firstNonEmptyLine(note.content).ifBlank { "未命名" } }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .liquidGlass(glass, shape = shape, blurRadius = 36.dp)
            .clickable(onClick = onClick)
            .padding(18.dp)
    ) {
        Text(
            text = displayTitle,
            style = MaterialTheme.typography.titleLarge,
            color = LocalContentColor.current,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (note.preview.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = note.preview,
                style = MaterialTheme.typography.bodyMedium,
                color = LocalContentColor.current.copy(alpha = 0.75f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = formatDate(note.updatedAt),
            style = MaterialTheme.typography.labelLarge,
            color = LocalContentColor.current.copy(alpha = 0.55f)
        )
    }
}

@Composable
private fun EmptyState(query: String, glass: LiquidGlassState?) {
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .liquidGlass(glass, shape = shape, blurRadius = 32.dp)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (query.isBlank()) "还没有笔记，点右下角 + 创建一条" else "没有匹配 “$query” 的笔记",
            style = MaterialTheme.typography.bodyLarge,
            color = LocalContentColor.current.copy(alpha = 0.75f)
        )
    }
}

@Composable
private fun FloatingNewButton(
    glass: LiquidGlassState?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(64.dp)
            .liquidGlass(
                glass,
                shape = CircleShape,
                blurRadius = 40.dp,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                borderColor = Color.White.copy(alpha = 0.65f)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Rounded.Add,
            contentDescription = "新建笔记",
            tint = Color.White,
            modifier = Modifier.size(30.dp)
        )
    }
}

private fun firstNonEmptyLine(s: String): String =
    s.lineSequence().map { it.trim().removePrefix("#").trim() }
        .firstOrNull { it.isNotBlank() } ?: ""

private fun formatDate(ts: Long): String {
    val sdf = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
    return sdf.format(Date(ts))
}
