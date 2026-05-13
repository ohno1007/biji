package com.biji.notes.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.runtime.rememberCoroutineScope
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
import com.biji.notes.ui.glass.LiquidGlassState
import com.biji.notes.ui.glass.bouncyPress
import com.biji.notes.ui.glass.liquidGlass
import com.biji.notes.ui.markdown.MarkdownText
import androidx.compose.animation.core.animateFloatAsState

@Composable
fun ChatScreen(
    vm: ChatViewModel,
    glass: LiquidGlassState?,
    onBack: () -> Unit
) {
    val messages by vm.activeMessages.collectAsState()
    val settings by vm.settings.collectAsState()
    val streaming by vm.isStreaming.collectAsState()
    val error by vm.streamError.collectAsState()
    val convoId by vm.activeConvoId.collectAsState()

    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

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
            glass = glass,
            title = "DeepSeek",
            subtitle = if (settings.thinking) "Reasoner · 思考开启" else "Chat",
            thinking = settings.thinking,
            onToggleThinking = { vm.setThinking(it) },
            onBack = onBack
        )

        Box(Modifier.weight(1f)) {
            if (messages.isEmpty()) {
                EmptyChatHint(glass = glass)
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp, end = 16.dp,
                        top = 12.dp, bottom = 12.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(
                        items = messages,
                        key = { it.id }
                    ) { m -> MessageBubble(m = m, glass = glass) }

                    if (error != null) {
                        item {
                            ErrorPill(message = error!!, glass = glass, onDismiss = vm::dismissError)
                        }
                    }
                }
            }
        }

        Composer(
            value = input,
            onValueChange = { input = it },
            glass = glass,
            sending = streaming,
            onSend = {
                if (input.isNotBlank() && !streaming) {
                    vm.send(input)
                    input = ""
                }
            },
            onStop = vm::cancelStream
        )
        Spacer(Modifier.height(8.dp).navigationBarsPadding())
    }
}

@Composable
private fun ChatTopBar(
    glass: LiquidGlassState?,
    title: String,
    subtitle: String,
    thinking: Boolean,
    onToggleThinking: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(40.dp)
                .liquidGlass(
                    glass,
                    shape = CircleShape,
                    cornerRadius = 40.dp,
                    blurRadius = 24.dp
                )
                .bouncyPress(onClick = onBack),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "返回",
                modifier = Modifier.size(18.dp),
                tint = LocalContentColor.current
            )
        }
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = LocalContentColor.current,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelLarge,
                color = LocalContentColor.current.copy(alpha = 0.65f)
            )
        }
        ThinkingToggle(thinking = thinking, glass = glass, onChange = onToggleThinking)
    }
}

@Composable
private fun ThinkingToggle(
    thinking: Boolean,
    glass: LiquidGlassState?,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier
            .liquidGlass(
                glass,
                shape = RoundedCornerShape(50),
                cornerRadius = 50.dp,
                blurRadius = 22.dp,
                tint = if (thinking)
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                else Color.White.copy(alpha = 0.10f)
            )
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Rounded.AutoAwesome,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = LocalContentColor.current
        )
        Spacer(Modifier.size(6.dp))
        Text("思考", style = MaterialTheme.typography.labelLarge, color = LocalContentColor.current)
        Spacer(Modifier.size(6.dp))
        Switch(
            checked = thinking,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = Color.White.copy(alpha = 0.25f),
                uncheckedBorderColor = Color.Transparent,
                checkedBorderColor = Color.Transparent
            ),
            modifier = Modifier.size(width = 40.dp, height = 22.dp)
        )
    }
}

@Composable
private fun MessageBubble(m: Message, glass: LiquidGlassState?) {
    val isUser = m.role == Role.USER
    val alignment = if (isUser) Alignment.End else Alignment.Start
    val shape = if (isUser)
        RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = 22.dp, bottomEnd = 6.dp)
    else
        RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = 6.dp, bottomEnd = 22.dp)
    val tint = if (isUser)
        MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
    else
        Color.White.copy(alpha = 0.12f)
    val cornerR = 22.dp
    val clipboard = LocalClipboardManager.current

    AnimatedVisibility(
        visible = true,
        enter = fadeIn(spring(stiffness = Spring.StiffnessMediumLow)) +
            scaleIn(
                initialScale = 0.92f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(),
            contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .liquidGlass(
                        glass,
                        shape = shape,
                        cornerRadius = cornerR,
                        blurRadius = 30.dp,
                        tint = tint
                    )
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                if (!isUser && !m.reasoning.isNullOrBlank()) {
                    ReasoningBlock(reasoning = m.reasoning)
                    Spacer(Modifier.height(8.dp))
                }
                if (m.content.isBlank() && !isUser && m.reasoning.isNullOrBlank()) {
                    TypingDots()
                } else if (isUser) {
                    Text(
                        m.content,
                        style = MaterialTheme.typography.bodyLarge,
                        color = LocalContentColor.current
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
                                .liquidGlass(
                                    glass,
                                    shape = CircleShape,
                                    cornerRadius = 28.dp,
                                    blurRadius = 14.dp,
                                    tint = Color.White.copy(alpha = 0.18f)
                                )
                                .bouncyPress(onClick = {
                                    clipboard.setText(AnnotatedString(m.content))
                                }),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Rounded.ContentCopy,
                                contentDescription = "复制",
                                modifier = Modifier.size(13.dp),
                                tint = LocalContentColor.current.copy(alpha = 0.85f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReasoningBlock(reasoning: String) {
    var expanded by remember { mutableStateOf(true) }
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "chev"
    )
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                color = LocalContentColor.current.copy(alpha = 0.06f),
                shape = RoundedCornerShape(12.dp)
            )
            .padding(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .bouncyPress(onClick = { expanded = !expanded }),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(16.dp).rotate(rotation),
                tint = LocalContentColor.current.copy(alpha = 0.65f)
            )
            Spacer(Modifier.size(4.dp))
            Icon(
                Icons.Rounded.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.size(6.dp))
            Text(
                "思考过程",
                style = MaterialTheme.typography.labelLarge,
                color = LocalContentColor.current.copy(alpha = 0.75f),
                fontWeight = FontWeight.Medium
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
                    color = LocalContentColor.current.copy(alpha = 0.80f)
                )
            }
        }
    }
}

@Composable
private fun TypingDots() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val anim by animateFloatAsState(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessLow
                ),
                label = "dot$i"
            )
            Box(
                Modifier
                    .size(8.dp)
                    .background(
                        color = LocalContentColor.current.copy(alpha = 0.5f * anim),
                        shape = CircleShape
                    )
            )
            if (i < 2) Spacer(Modifier.size(4.dp))
        }
    }
}

@Composable
private fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    glass: LiquidGlassState?,
    sending: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit
) {
    val shape = RoundedCornerShape(28.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .liquidGlass(
                glass,
                shape = shape,
                cornerRadius = 28.dp,
                blurRadius = 36.dp,
                tint = Color.White.copy(alpha = 0.12f)
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Box(
            Modifier
                .weight(1f)
                .padding(top = 6.dp, bottom = 6.dp, end = 8.dp)
        ) {
            if (value.isEmpty()) {
                Text(
                    "和 DeepSeek 说点什么…",
                    style = MaterialTheme.typography.bodyLarge,
                    color = LocalContentColor.current.copy(alpha = 0.40f)
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = LocalContentColor.current),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth()
            )
        }
        SendOrStopButton(
            sending = sending,
            enabled = sending || value.isNotBlank(),
            glass = glass,
            onSend = onSend,
            onStop = onStop
        )
    }
}

@Composable
private fun SendOrStopButton(
    sending: Boolean,
    enabled: Boolean,
    glass: LiquidGlassState?,
    onSend: () -> Unit,
    onStop: () -> Unit
) {
    val tint = if (enabled)
        MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
    else
        Color.White.copy(alpha = 0.12f)
    Box(
        Modifier
            .size(44.dp)
            .liquidGlass(
                glass,
                shape = CircleShape,
                cornerRadius = 44.dp,
                blurRadius = 24.dp,
                tint = tint
            )
            .bouncyPress(onClick = { if (sending) onStop() else if (enabled) onSend() }),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (sending) Icons.Rounded.Stop else Icons.Rounded.Send,
            contentDescription = if (sending) "停止" else "发送",
            modifier = Modifier.size(18.dp),
            tint = Color.White
        )
    }
}

@Composable
private fun ErrorPill(message: String, glass: LiquidGlassState?, onDismiss: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .liquidGlass(
                glass,
                shape = RoundedCornerShape(18.dp),
                cornerRadius = 18.dp,
                blurRadius = 26.dp,
                tint = Color(0xFFFF6B6B).copy(alpha = 0.25f)
            )
            .bouncyPress(onClick = onDismiss)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = LocalContentColor.current,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun EmptyChatHint(glass: LiquidGlassState?) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .liquidGlass(
                    glass,
                    shape = RoundedCornerShape(28.dp),
                    cornerRadius = 28.dp,
                    blurRadius = 40.dp
                )
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier
                    .size(56.dp)
                    .liquidGlass(
                        glass,
                        shape = CircleShape,
                        cornerRadius = 56.dp,
                        blurRadius = 22.dp,
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(26.dp)
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "现在开始聊吧",
                style = MaterialTheme.typography.titleLarge,
                color = LocalContentColor.current,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "支持流式输出、Markdown，开「思考」可切到 R1 推理。",
                style = MaterialTheme.typography.bodyMedium,
                color = LocalContentColor.current.copy(alpha = 0.70f)
            )
        }
    }
}
