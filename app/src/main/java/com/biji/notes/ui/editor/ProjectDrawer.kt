package com.biji.notes.ui.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.biji.notes.sandbox.LocalSandbox
import com.biji.notes.ui.glass.bouncyClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private data class TreeNode(
    val file: File,
    val depth: Int,
    val relPath: String,
    val children: List<TreeNode>
)

/**
 * Right-anchored drawer that lists every file in the local sandbox.
 * Tapping a file forwards the relative path to [onOpenFile]. Files the
 * assistant has written to during this session get a small ⚡ badge.
 */
@Composable
fun ProjectDrawer(
    sandbox: LocalSandbox,
    folder: String,
    open: Boolean,
    onOpenFile: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AnimatedVisibility(
        visible = open,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f))
                .pointerInput(Unit) {
                    detectTapGestures { onDismiss() }
                }
        ) {
            androidx.compose.animation.AnimatedVisibility(
                visible = open,
                enter = slideInHorizontally(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    ),
                    initialOffsetX = { it }
                ) + fadeIn(),
                exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
                modifier = Modifier.align(Alignment.CenterEnd)
            ) {
                DrawerBody(
                    sandbox = sandbox,
                    folder = folder,
                    onOpenFile = {
                        onOpenFile(it)
                        onDismiss()
                    }
                )
            }
        }
    }
}

@Composable
private fun DrawerBody(
    sandbox: LocalSandbox,
    folder: String,
    onOpenFile: (String) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val aiEdited by sandbox.aiEditedPaths.collectAsState()
    var refreshKey by remember { mutableStateOf(0) }
    var tree by remember { mutableStateOf<TreeNode?>(null) }
    // Track which directories are expanded; defaults to root open.
    val expanded = remember { mutableStateMapOf<String, Boolean>().apply { put("", true) } }
    val projectRoot = remember(folder) { sandbox.projectRoot(folder) }
    val folderKeyPrefix = remember(folder) {
        (folder.ifEmpty { LocalSandbox.DEFAULT_FOLDER }) + "/"
    }

    LaunchedEffect(refreshKey, folder, aiEdited.size) {
        val built = withContext(Dispatchers.IO) {
            buildTree(projectRoot, projectRoot, depth = 0)
        }
        tree = built
    }

    Column(
        Modifier
            .fillMaxHeight()
            .width(320.dp)
            .background(cs.surface)
            .pointerInput(Unit) { detectTapGestures { /* swallow */ } }
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "项目文件",
                    style = MaterialTheme.typography.titleMedium,
                    color = cs.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    folder.ifEmpty { LocalSandbox.DEFAULT_FOLDER },
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (aiEdited.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(cs.primary.copy(alpha = 0.15f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Outlined.Bolt,
                        contentDescription = null,
                        modifier = Modifier.size(10.dp),
                        tint = cs.primary
                    )
                    Spacer(Modifier.width(2.dp))
                    Text(
                        "${aiEdited.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
        Text(
            projectRoot.path,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = cs.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        val root = tree
        if (root == null) {
            Text(
                "扫描中…",
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
        } else {
            val visible = remember(root, expanded.size, expanded.values.toList()) {
                flatten(root, expanded)
            }
            if (visible.isEmpty()) {
                Text(
                    "项目目录为空。让助手用 write_file 创建第一个文件，或把文件拷贝到上面的路径。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    items(visible, key = { it.relPath.ifEmpty { "/" } }) { node ->
                        FileRow(
                            node = node,
                            isExpanded = expanded[node.relPath] == true,
                            highlighted = (folderKeyPrefix + node.relPath) in aiEdited,
                            onClick = {
                                if (node.file.isDirectory) {
                                    expanded[node.relPath] = !(expanded[node.relPath] ?: false)
                                } else {
                                    onOpenFile(node.relPath)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FileRow(
    node: TreeNode,
    isExpanded: Boolean,
    highlighted: Boolean,
    onClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val isDir = node.file.isDirectory
    val rot by animateFloatAsState(
        targetValue = if (isExpanded) 90f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "drawerChev"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .bouncyClickable(pressedScale = 0.985f, onClick = onClick)
            .padding(
                start = (16 + node.depth * 14).dp,
                end = 12.dp,
                top = 8.dp,
                bottom = 8.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isDir) {
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(12.dp).rotate(rot),
                tint = cs.onSurfaceVariant
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Outlined.FolderOpen,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = cs.primary
            )
        } else {
            Spacer(Modifier.width(16.dp))
            Icon(
                Icons.Outlined.Description,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = cs.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            node.file.name.ifEmpty { "/" },
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurface,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f)
        )
        if (highlighted) {
            Icon(
                Icons.Outlined.Bolt,
                contentDescription = "AI 修改",
                modifier = Modifier.size(14.dp),
                tint = cs.primary
            )
        }
    }
}

private fun flatten(
    root: TreeNode,
    expanded: Map<String, Boolean>
): List<TreeNode> {
    val out = mutableListOf<TreeNode>()
    fun walk(n: TreeNode) {
        if (n.depth > 0) out += n
        if (n.file.isDirectory && expanded[n.relPath] == true) {
            n.children.forEach(::walk)
        }
    }
    walk(root)
    return out
}

private fun buildTree(file: File, root: File, depth: Int, maxEntries: Int = 500): TreeNode {
    val rel = file.relativeTo(root).path
    if (!file.isDirectory) return TreeNode(file, depth, rel, emptyList())
    val children = (file.listFiles() ?: emptyArray())
        .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        .take(maxEntries)
        .map { buildTree(it, root, depth + 1, maxEntries) }
    return TreeNode(file, depth, rel, children)
}
