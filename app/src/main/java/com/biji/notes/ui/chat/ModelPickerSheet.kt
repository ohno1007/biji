package com.biji.notes.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.biji.notes.data.MODEL_CHAT
import com.biji.notes.data.MODEL_REASONER
import com.biji.notes.ui.glass.bouncyClickable

/**
 * Shared bottom-sheet model picker reused by chat & conversation-list
 * screens. Auto-loads /v1/models on open via the caller, optionally
 * surfaces the per-conversation thinking toggle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerSheet(
    models: List<String>,
    loading: Boolean,
    error: String?,
    currentModel: String,
    convoThinking: Boolean,
    showThinkingToggle: Boolean,
    onPick: (String) -> Unit,
    onToggleThinking: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val cs = MaterialTheme.colorScheme
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = cs.surface
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "选择模型",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = cs.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .bouncyClickable(onClick = onRefresh)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = cs.primary
                            )
                        } else {
                            Icon(
                                Icons.Rounded.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = cs.primary
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "刷新",
                            style = MaterialTheme.typography.labelLarge,
                            color = cs.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            if (showThinkingToggle) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(cs.surfaceContainer)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Outlined.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = cs.primary
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "思考 / 输出模式",
                            style = MaterialTheme.typography.titleMedium,
                            color = cs.onSurface,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            if (currentModel == MODEL_REASONER) "Reasoner 默认展示推理"
                            else "对非推理模型追加 <think>…</think> 指令",
                            style = MaterialTheme.typography.labelLarge,
                            color = cs.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = convoThinking || currentModel == MODEL_REASONER,
                        onCheckedChange = onToggleThinking,
                        enabled = currentModel != MODEL_REASONER,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = cs.primary,
                            uncheckedThumbColor = Color.White,
                            uncheckedTrackColor = cs.surfaceContainerHighest,
                            checkedBorderColor = Color.Transparent,
                            uncheckedBorderColor = Color.Transparent
                        )
                    )
                }
                Spacer(Modifier.height(14.dp))
            }

            val builtin = listOf(MODEL_CHAT, MODEL_REASONER)
            val all = (builtin + models).distinct()
            all.forEach { id ->
                val selected = id == currentModel
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .bouncyClickable(pressedScale = 0.985f) { onPick(id) }
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        id,
                        style = MaterialTheme.typography.bodyLarge,
                        color = cs.onSurface,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.weight(1f)
                    )
                    if (selected) {
                        Icon(
                            Icons.Rounded.Check,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = cs.primary
                        )
                    }
                }
            }
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text("拉取失败：$it", style = MaterialTheme.typography.labelLarge, color = cs.error)
            }
        }
    }
}
