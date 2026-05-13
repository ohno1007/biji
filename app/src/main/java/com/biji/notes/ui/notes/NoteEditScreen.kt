package com.biji.notes.ui.notes

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.biji.notes.data.Note
import com.biji.notes.ui.glass.LiquidGlassState
import com.biji.notes.ui.glass.liquidGlass
import com.biji.notes.ui.markdown.MarkdownText
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
@Composable
fun NoteEditScreen(
    vm: NotesViewModel,
    noteId: Long,
    glass: LiquidGlassState?,
    onBack: () -> Unit
) {
    var loadedId by remember { mutableStateOf(noteId) }
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf(false) }
    var initialized by remember { mutableStateOf(noteId == 0L) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(noteId) {
        if (noteId > 0L) {
            val note = vm.load(noteId)
            if (note != null) {
                title = note.title
                content = note.content
            }
            initialized = true
        } else {
            initialized = true
        }
    }

    // Debounced auto-save: writes back to Room ~500ms after the user stops typing.
    LaunchedEffect(initialized) {
        if (!initialized) return@LaunchedEffect
        snapshotFlow { title to content }
            .drop(1)
            .distinctUntilChanged()
            .debounce(500)
            .collect { (t, c) ->
                if (t.isBlank() && c.isBlank()) return@collect
                val note = Note(id = loadedId, title = t, content = c)
                vm.save(note) { id -> loadedId = id }
            }
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .imePadding()
    ) {
        EditorTopBar(
            glass = glass,
            onBack = {
                // Flush any pending changes synchronously on back.
                if (title.isNotBlank() || content.isNotBlank()) {
                    scope.launch {
                        vm.save(Note(id = loadedId, title = title, content = content))
                    }
                }
                onBack()
            },
            previewing = preview,
            onTogglePreview = { preview = !preview },
            onDelete = if (loadedId > 0L) {
                {
                    vm.delete(loadedId)
                    onBack()
                }
            } else null
        )

        Spacer(Modifier.height(12.dp))

        Box(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            AnimatedContent(
                targetState = preview,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                label = "modeSwap"
            ) { previewMode ->
                if (previewMode) {
                    PreviewSurface(title = title, content = content, glass = glass)
                } else {
                    EditorSurface(
                        title = title,
                        onTitleChange = { title = it },
                        content = content,
                        onContentChange = { content = it },
                        glass = glass
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp).navigationBarsPadding())
    }
}

@Composable
private fun EditorTopBar(
    glass: LiquidGlassState?,
    onBack: () -> Unit,
    previewing: Boolean,
    onTogglePreview: () -> Unit,
    onDelete: (() -> Unit)?
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        GlassIconButton(glass, Icons.Rounded.ArrowBack, "返回", onClick = onBack)
        Spacer(Modifier.weight(1f))
        if (onDelete != null) {
            GlassIconButton(glass, Icons.Rounded.DeleteOutline, "删除", onClick = onDelete)
        }
        GlassIconButton(
            glass = glass,
            icon = if (previewing) Icons.Rounded.Edit else Icons.Rounded.Visibility,
            contentDescription = if (previewing) "编辑" else "预览",
            onClick = onTogglePreview
        )
    }
}

@Composable
private fun GlassIconButton(
    glass: LiquidGlassState?,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .liquidGlass(glass, shape = CircleShape, blurRadius = 28.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = LocalContentColor.current,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun EditorSurface(
    title: String,
    onTitleChange: (String) -> Unit,
    content: String,
    onContentChange: (String) -> Unit,
    glass: LiquidGlassState?
) {
    val shape = RoundedCornerShape(28.dp)
    Column(
        Modifier
            .fillMaxSize()
            .clip(shape)
            .liquidGlass(glass, shape = shape, blurRadius = 40.dp)
            .padding(horizontal = 20.dp, vertical = 18.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Box {
            if (title.isEmpty()) {
                Text(
                    "标题",
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    color = LocalContentColor.current.copy(alpha = 0.40f)
                )
            }
            BasicTextField(
                value = title,
                onValueChange = onTitleChange,
                textStyle = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = LocalContentColor.current
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.height(14.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(LocalContentColor.current.copy(alpha = 0.12f))
        )
        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth()) {
            if (content.isEmpty()) {
                Text(
                    "在这里开始写吧… 支持 Markdown：\n# 标题  ## 副标题\n**粗体** *斜体* `代码`\n- 列表项\n> 引用\n```\n代码块\n```",
                    style = MaterialTheme.typography.bodyLarge,
                    color = LocalContentColor.current.copy(alpha = 0.40f)
                )
            }
            BasicTextField(
                value = content,
                onValueChange = onContentChange,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = LocalContentColor.current),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun PreviewSurface(
    title: String,
    content: String,
    glass: LiquidGlassState?
) {
    val shape = RoundedCornerShape(28.dp)
    Column(
        Modifier
            .fillMaxSize()
            .clip(shape)
            .liquidGlass(glass, shape = shape, blurRadius = 40.dp)
            .padding(horizontal = 20.dp, vertical = 18.dp)
            .verticalScroll(rememberScrollState())
    ) {
        if (title.isNotBlank()) {
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = LocalContentColor.current
            )
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(LocalContentColor.current.copy(alpha = 0.12f))
            )
            Spacer(Modifier.height(10.dp))
        }
        MarkdownText(markdown = content)
        Spacer(Modifier.height(40.dp))
    }
}
