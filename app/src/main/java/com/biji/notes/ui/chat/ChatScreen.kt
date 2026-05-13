package com.biji.notes.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.biji.notes.data.Message
import com.biji.notes.data.Role
import com.biji.notes.ui.glass.bouncyPress
import com.biji.notes.ui.glass.cardContainerColor
import com.biji.notes.ui.glass.mdSurface
import com.biji.notes.ui.markdown.MarkdownText

@Composable
fun ChatScreen(
    vm: ChatViewModel,
    onBack: () -> Unit
) {
    val messages by vm.activeMessages.collectAsState()
    val settings by vm.settings.collectAsState()
    val streaming by vm.isStreaming.collectAsState()
    val error by vm.streamError.collectAsState()

    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, streaming) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .imePadding()
    ) {
        ChatTopBar(
            title = "DeepSeek",
            subtitle = if (settings.thinking) "${settings.model} · 思考开启" else settings.model,
            thinking = settings.thinking,
            onToggleThinking = vm::setThinking,
            onBack = onBack
        )

        Box(Modifier.weight(1f)) {
            if (messages.isEmpty()) {
                EmptyChatHint()
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp, end = 16.dp,
                        top = 12.dp, bottom = 12.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(messages, key = { it.id }) { m -> MessageBubble(m = m) }
                    if (error != null) {
                        item { ErrorPill(message = error!!, onDismiss = vm::dismissError) }
                    }
                }
            }
        }

        Composer(
            value = input,
            onValueChange = { input = it },
            sending = streaming,
            onSend = {
                if (input.isNotBlank() && !streaming) {
                    vm.send(input); input = ""
                }
            },
            onStop = vm::cancelStream
        )
        Spacer(Modifier.height(8.dp).navigationBarsPadding())
    }
}

@Composable
private fun ChatTopBar(
    title: String,
    subtitle: String,
    thinking: Boolean,
    onToggleThinking: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(40.dp)
                .mdSurface(cs.surfaceContainerHigh, CircleShape)
                .bouncyPress(onClick = onBack),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "返回",
                modifier = Modifier.size(18.dp),
                tint = cs.onSurface
            )
        }
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = cs.onBackground,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelLarge,
                color = cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        ThinkingToggle(thinking = thinking, onChange = onToggleThinking)
    }
}

@Composable
private fun ThinkingToggle(thinking: Boolean, onChange: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val bg = if (thinking) cs.primaryContainer else cs.surfaceContainerHigh
    val fg = if (thinking) cs.onPrimaryContainer else cs.onSurface
    Row(
        Modifier
            .mdSurface(bg, RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Rounded.AutoAwesome,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = fg
        )
        Spacer(Modifier.size(6.dp))
        Text(
            "思考",
            style = MaterialTheme.typography.labelLarge,
            color = fg,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.size(6.dp))
        Switch(
            checked = thinking,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = cs.onPrimary,
                checkedTrackColor = cs.primary,
                uncheckedThumbColor = cs.onSurface,
                uncheckedTrackColor = cs.surfaceContainerHighest,
                checkedBorderColor = Color.Transparent,
                uncheckedBorderColor = Color.Transparent
            ),
            modifier = Modifier.size(width = 38.dp, height = 22.dp)
        )
    }
}

@Composable
private fun MessageBubble(m: Message) {
    val isUser = m.role == Role.USER
    val cs = MaterialTheme.colorScheme
    val shape = if (isUser)
        RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 6.dp)
    else
        RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 6.dp, bottomEnd = 20.dp)
    val bg = if (isUser) cs.primary else cardContainerColor(2)
    val fg = if (isUser) cs.onPrimary else cs.onSurface
    val clipboard = LocalClipboardManager.current

    AnimatedVisibility(
        visible = true,
        enter = fadeIn(spring(stiffness = Spring.StiffnessMediumLow)) +
            scaleIn(
                initialScale = 0.94f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
    ) {
        Box(
            Modifier.fillMaxWidth(),
            contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
        ) {
            Column(
                Modifier
                    .widthIn(max = 320.dp)
                    .mdSurface(bg, shape)
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                if (!isUser && !m.reasoning.isNullOrBlank()) {
                    ReasoningBlock(reasoning = m.reasoning, fg = fg)
                    Spacer(Modifier.height(8.dp))
                }
                if (m.content.isBlank() && !isUser && m.reasoning.isNullOrBlank()) {
                    TypingDots(fg = fg)
                } else if (isUser) {
                    Text(
                        m.content,
                        style = MaterialTheme.typography.bodyLarge,
                        color = fg
                    )
                } else {
                    MarkdownText(markdown = m.content)
                }
                if (!isUser && m.content.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(28.dp)
                                .mdSurface(cs.surfaceContainerHighest, CircleShape)
                                .bouncyPress(onClick = {
                                    clipboard.setText(AnnotatedString(m.content))
                                }),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Rounded.ContentCopy,
                                contentDescription = "复制",
                                modifier = Modifier.size(13.dp),
                                tint = cs.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReasoningBlock(reasoning: String, fg: Color) {
    val cs = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "chev"
    )
    Column(
        Modifier
            .fillMaxWidth()
            .mdSurface(cs.surfaceContainerHigh, RoundedCornerShape(12.dp))
            .padding(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().bouncyPress(onClick = { expanded = !expanded }),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(16.dp).rotate(rotation),
                tint = cs.onSurfaceVariant
            )
            Spacer(Modifier.size(4.dp))
            Icon(
                Icons.Rounded.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = cs.primary
            )
            Spacer(Modifier.size(6.dp))
            Text(
                "思考过程",
                style = MaterialTheme.typography.labelLarge,
                color = cs.onSurface,
                fontWeight = FontWeight.SemiBold
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column {
                Spacer(Modifier.height(8.dp))
                Text(
                    reasoning,
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun TypingDots(fg: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            Box(
                Modifier
                    .size(8.dp)
                    .mdSurface(fg.copy(alpha = 0.45f), CircleShape)
            )
            if (i < 2) Spacer(Modifier.size(4.dp))
        }
    }
}

@Composable
private fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    sending: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .mdSurface(cs.surfaceContainerHigh, RoundedCornerShape(26.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Box(Modifier.weight(1f).padding(top = 6.dp, bottom = 6.dp, end = 8.dp)) {
            if (value.isEmpty()) {
                Text(
                    "和 DeepSeek 说点什么…",
                    style = MaterialTheme.typography.bodyLarge,
                    color = cs.onSurfaceVariant
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface),
                cursorBrush = SolidColor(cs.primary),
                modifier = Modifier.fillMaxWidth()
            )
        }
        SendOrStopButton(
            sending = sending,
            enabled = sending || value.isNotBlank(),
            onSend = onSend,
            onStop = onStop
        )
    }
}

@Composable
private fun SendOrStopButton(
    sending: Boolean,
    enabled: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val bg = when {
        sending -> cs.error
        enabled -> cs.primary
        else -> cs.surfaceContainerHighest
    }
    val fg = when {
        sending -> cs.onPrimary
        enabled -> cs.onPrimary
        else -> cs.onSurfaceVariant
    }
    Box(
        Modifier
            .size(44.dp)
            .mdSurface(bg, CircleShape)
            .bouncyPress(onClick = { if (sending) onStop() else if (enabled) onSend() }),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (sending) Icons.Rounded.Stop else Icons.AutoMirrored.Rounded.Send,
            contentDescription = if (sending) "停止" else "发送",
            modifier = Modifier.size(18.dp),
            tint = fg
        )
    }
}

@Composable
private fun ErrorPill(message: String, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .mdSurface(cs.error, RoundedCornerShape(16.dp))
            .bouncyPress(onClick = onDismiss)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onPrimary,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun EmptyChatHint() {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .mdSurface(cardContainerColor(1), RoundedCornerShape(24.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier
                    .size(52.dp)
                    .mdSurface(cs.primary, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    tint = cs.onPrimary,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "现在开始聊吧",
                style = MaterialTheme.typography.titleLarge,
                color = cs.onSurface,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "支持 Markdown 流式输出；打开「思考」切到 deepseek-reasoner。",
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant
            )
        }
    }
}
