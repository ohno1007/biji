package com.biji.notes.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.biji.notes.data.MODEL_CHAT
import com.biji.notes.data.MODEL_REASONER
import com.biji.notes.data.Message
import com.biji.notes.data.Role
import com.biji.notes.ui.glass.bouncyClickable
import com.biji.notes.ui.markdown.MarkdownText
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(
    vm: ChatViewModel,
    onBack: () -> Unit
) {
    val messages by vm.activeMessages.collectAsState()
    val settings by vm.settings.collectAsState()
    val streaming by vm.isStreaming.collectAsState()
    val error by vm.streamError.collectAsState()
    val convoId by vm.activeConvoId.collectAsState()
    val conversations by vm.conversations.collectAsState()
    val title = conversations.firstOrNull { it.id == convoId }?.title ?: "DeepSeek"

    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(messages.size, streaming) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    val isNearBottom by remember {
        derivedStateOf {
            val li = listState.layoutInfo
            val last = li.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf true
            last >= (li.totalItemsCount - 1)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .imePadding()
    ) {
        // 1. Messages list – fills the entire screen, content padded so it
        //    appears to slide *behind* the floating top title and the
        //    floating composer.
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 18.dp,
                end = 18.dp,
                top = TopFadeHeight + 12.dp,
                bottom = ComposerArea + 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            if (messages.isEmpty()) {
                item { EmptyChatHint() }
            } else {
                items(messages, key = { it.id }) { m -> MessageItem(m = m) }
                if (error != null) {
                    item { ErrorRow(message = error!!, onDismiss = vm::dismissError) }
                }
            }
        }

        // 2. Top fade: cream-coloured vertical gradient that messages
        //    scroll into / out of, so they appear to dissolve behind the
        //    title row.
        TopFade(modifier = Modifier.align(Alignment.TopCenter))

        // 3. The title row sits on top of that fade.
        TopBar(
            title = title,
            onBack = onBack,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp)
        )

        // 4. Bottom fade: covers the area where the floating composer sits
        //    so messages dissolve behind the composer as you scroll.
        BottomFade(modifier = Modifier.align(Alignment.BottomCenter))

        // 5. Scroll-to-bottom puck, hovers just above the composer.
        AnimatedVisibility(
            visible = !isNearBottom && messages.isNotEmpty(),
            enter = fadeIn() + scaleIn(initialScale = 0.6f),
            exit = fadeOut() + scaleOut(targetScale = 0.6f),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = ComposerArea - 16.dp)
        ) {
            ScrollToBottomButton {
                scope.launch {
                    listState.animateScrollToItem(messages.size - 1)
                }
            }
        }

        // 6. The composer floats with margin on all sides; the navbar inset
        //    is honoured so the cream background shows through underneath
        //    on devices with gesture nav ("镂空").
        Composer(
            value = input,
            onValueChange = { input = it },
            model = settings.model,
            sending = streaming,
            onSend = {
                if (input.isNotBlank() && !streaming) {
                    vm.send(input); input = ""
                }
            },
            onStop = vm::cancelStream,
            onPickModel = vm::setModel,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        )
    }
}

// =====================================================================
// Layout constants
// =====================================================================

private val TopFadeHeight = 96.dp
private val BottomFadeHeight = 180.dp

/**
 * Composer occupies roughly two rows + padding; this is the slug the
 * message list reserves at the bottom so the last bubble doesn't tuck
 * under the floating composer.
 */
private val ComposerArea = 168.dp

// =====================================================================
// Fade / glow overlays
// =====================================================================

@Composable
private fun TopFade(modifier: Modifier = Modifier) {
    val bg = MaterialTheme.colorScheme.background
    Box(
        modifier
            .fillMaxWidth()
            .height(TopFadeHeight + WindowInsetsTopHeight())
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
private fun BottomFade(modifier: Modifier = Modifier) {
    val bg = MaterialTheme.colorScheme.background
    Box(
        modifier
            .fillMaxWidth()
            .height(BottomFadeHeight)
            .background(
                Brush.verticalGradient(
                    0.0f to bg.copy(alpha = 0f),
                    0.45f to bg.copy(alpha = 0.92f),
                    1.0f to bg
                )
            )
    )
}

@Composable
private fun WindowInsetsTopHeight(): androidx.compose.ui.unit.Dp {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val insets = WindowInsets.statusBars
    return with(density) { insets.getTop(density).toDp() }
}

// =====================================================================
// Top bar (image 2: hamburger + title + more)
// =====================================================================

@Composable
private fun TopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconBtn(
            icon = Icons.AutoMirrored.Rounded.ArrowBack,
            contentDescription = "返回",
            onClick = onBack
        )
        Spacer(Modifier.width(4.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            color = cs.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        IconBtn(
            icon = Icons.Outlined.MoreHoriz,
            contentDescription = "更多",
            onClick = { /* reserved */ }
        )
    }
}

@Composable
private fun IconBtn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String?,
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

// =====================================================================
// Messages
// =====================================================================

@Composable
private fun MessageItem(m: Message) {
    val isUser = m.role == Role.USER
    AnimatedVisibility(
        visible = true,
        enter = fadeIn(spring(stiffness = Spring.StiffnessMediumLow)) +
            slideInVertically(
                initialOffsetY = { it / 6 },
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
    ) {
        if (isUser) UserBubble(m.content) else AssistantBlock(m)
    }
}

@Composable
private fun UserBubble(content: String) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(cs.surfaceContainerHigh)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                content,
                style = MaterialTheme.typography.bodyLarge,
                color = cs.onSurface
            )
        }
    }
}

@Composable
private fun AssistantBlock(m: Message) {
    val cs = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth()) {
        if (!m.reasoning.isNullOrBlank()) {
            ReasoningBlock(m.reasoning)
            Spacer(Modifier.height(10.dp))
        }
        if (m.content.isBlank() && m.reasoning.isNullOrBlank()) {
            TypingDots()
        } else {
            MarkdownText(markdown = m.content)
            if (m.content.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .bouncyClickable(pressedScale = 0.94f) {
                            clipboard.setText(AnnotatedString(m.content))
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.ContentCopy,
                        contentDescription = "复制",
                        modifier = Modifier.size(13.dp),
                        tint = cs.onSurfaceVariant
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "复制",
                        style = MaterialTheme.typography.labelLarge,
                        color = cs.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ReasoningBlock(reasoning: String) {
    val cs = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "chev"
    )
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .bouncyClickable(pressedScale = 0.97f) { expanded = !expanded }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = cs.primary
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "思考过程",
                style = MaterialTheme.typography.labelLarge,
                color = cs.onSurface,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Rounded.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(14.dp).rotate(rotation),
                tint = cs.onSurfaceVariant
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Row(modifier = Modifier.padding(top = 6.dp, start = 4.dp)) {
                Box(
                    Modifier
                        .width(2.dp)
                        .heightIn(min = 24.dp)
                        .background(cs.outlineVariant)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    reasoning,
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun TypingDots() {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(cs.onSurfaceVariant.copy(alpha = 0.45f))
            )
            if (i < 2) Spacer(Modifier.width(5.dp))
        }
    }
}

// =====================================================================
// Composer
// =====================================================================

@Composable
private fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    model: String,
    sending: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onPickModel: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(cs.surface)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface),
            cursorBrush = SolidColor(cs.primary),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text(
                        "和 DeepSeek 说点什么…",
                        style = MaterialTheme.typography.bodyLarge,
                        color = cs.onSurfaceVariant.copy(alpha = 0.55f)
                    )
                }
                inner()
            }
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircleAction(
                icon = Icons.Outlined.Add,
                contentDescription = "更多",
                onClick = { /* reserved */ }
            )
            Spacer(Modifier.width(6.dp))
            ModelMenuChip(model = model, onPick = onPickModel)
            Spacer(Modifier.weight(1f))
            CircleAction(
                icon = Icons.Outlined.GraphicEq,
                contentDescription = "语音",
                onClick = { /* reserved */ }
            )
            Spacer(Modifier.width(6.dp))
            SendDot(
                sending = sending,
                enabled = sending || value.isNotBlank(),
                onSend = onSend,
                onStop = onStop
            )
        }
    }
}

@Composable
private fun CircleAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String?,
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
            tint = cs.onSurfaceVariant
        )
    }
}

@Composable
private fun ModelMenuChip(model: String, onPick: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    val isReasoner = model == MODEL_REASONER
    val bg = if (isReasoner) cs.primaryContainer else cs.surfaceContainerHigh
    val fg = if (isReasoner) cs.onPrimaryContainer else cs.onSurface

    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(bg)
                .bouncyClickable { open = true }
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isReasoner) {
                Icon(
                    Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = fg
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                model.removePrefix("deepseek-"),
                style = MaterialTheme.typography.labelLarge,
                color = fg,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.width(2.dp))
            Icon(
                Icons.Rounded.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = fg
            )
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false }
        ) {
            ModelMenuItem(
                label = "deepseek-chat",
                checked = model == MODEL_CHAT,
                onClick = { onPick(MODEL_CHAT); open = false }
            )
            ModelMenuItem(
                label = "deepseek-reasoner · 思考",
                checked = model == MODEL_REASONER,
                onClick = { onPick(MODEL_REASONER); open = false }
            )
        }
    }
}

@Composable
private fun ModelMenuItem(label: String, checked: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, modifier = Modifier.weight(1f))
                if (checked) {
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        onClick = onClick
    )
}

@Composable
private fun SendDot(
    sending: Boolean,
    enabled: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val bg = when {
        sending -> cs.error
        enabled -> cs.tertiary
        else -> cs.surfaceContainerHighest
    }
    val fg = when {
        sending -> Color.White
        enabled -> cs.onTertiary
        else -> cs.onSurfaceVariant
    }
    val target = if (sending || enabled) 1f else 0.92f
    val scale by animateFloatAsState(
        target,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "sendScale"
    )
    Box(
        modifier = Modifier
            .size(44.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(bg)
            .bouncyClickable(
                enabled = sending || enabled,
                pressedScale = 0.90f,
                onClick = { if (sending) onStop() else onSend() }
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (sending) Icons.Rounded.Stop else Icons.Rounded.ArrowUpward,
            contentDescription = if (sending) "停止" else "发送",
            modifier = Modifier.size(18.dp),
            tint = fg
        )
    }
}

// =====================================================================
// Misc
// =====================================================================

@Composable
private fun ScrollToBottomButton(onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(cs.surfaceContainerHigh)
            .bouncyClickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Rounded.ArrowDownward,
            contentDescription = "回到底部",
            modifier = Modifier.size(18.dp),
            tint = cs.onSurface
        )
    }
}

@Composable
private fun ErrorRow(message: String, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(cs.error.copy(alpha = 0.12f))
            .bouncyClickable(pressedScale = 0.99f, onClick = onDismiss)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = cs.error)
    }
}

@Composable
private fun EmptyChatHint() {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(cs.surface),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Outlined.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = cs.primary
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            "和 DeepSeek 说点什么",
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            color = cs.onSurface
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "在底部聊天栏的模型胶囊里可以切换 deepseek-chat / reasoner。",
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant
        )
    }
}
