package com.biji.notes.ui.chat

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
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
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.biji.notes.data.MODEL_CHAT
import com.biji.notes.data.MODEL_REASONER
import com.biji.notes.data.Message
import com.biji.notes.data.MessageKind
import com.biji.notes.data.Role
import com.biji.notes.ui.glass.bouncyClickable
import com.biji.notes.ui.markdown.MarkdownText
import com.biji.notes.voice.VoiceState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
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
    val toolStatus by vm.toolStatus.collectAsState()
    val compactStatus by vm.compactStatus.collectAsState()
    val ctxUsage by vm.contextUsage.collectAsState()
    val modelsState by vm.models.collectAsState()
    val voiceState by vm.voiceState.collectAsState()
    val voiceVisible by vm.voiceVisible.collectAsState()
    val ctx = LocalContext.current
    val convoThinking = conversations.firstOrNull { it.id == convoId }?.thinking == true

    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var modelSheetOpen by remember { mutableStateOf(false) }

    // Pull a fresh models list as soon as we land on a chat screen.
    LaunchedEffect(settings.apiKey, settings.baseUrl) { vm.ensureModelsLoaded() }

    // Microphone permission launcher.
    val micPerm = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) vm.startVoice() }

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

    Box(Modifier.fillMaxSize().imePadding()) {
        // Backdrop layer: captures whatever the LazyColumn draws so the
        // top/bottom fades can sample it through a Gaussian blur. Below
        // API 31 the layer still works, just without the blur — and the
        // gradient alone produces a clean solid-fade.
        val backdrop = rememberGraphicsLayer()
        Box(
            Modifier
                .fillMaxSize()
                .drawWithContent {
                    backdrop.record { this@drawWithContent.drawContent() }
                    drawLayer(backdrop)
                }
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 18.dp, end = 18.dp,
                    top = TopFadeHeight + 12.dp,
                    bottom = ComposerArea + 16.dp
                ),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                if (messages.isEmpty()) {
                    item { EmptyChatHint() }
                } else {
                    items(messages, key = { it.id }) { m ->
                        MessageItem(m = m, onOpenUrl = vm::openWebUrl)
                    }
                    if (toolStatus != null) {
                        item { StatusPill(text = toolStatus!!) }
                    }
                    if (compactStatus != null) {
                        item { StatusPill(text = compactStatus!!) }
                    }
                    if (error != null) {
                        item { ErrorRow(message = error!!, onDismiss = vm::dismissError) }
                    }
                }
            }
        }

        BlurFade(
            backdrop = backdrop,
            isBottom = false,
            heightTotal = TopFadeHeight + WindowInsetsTopHeight(),
            modifier = Modifier.align(Alignment.TopCenter)
        )

        TopBar(
            title = title,
            usage = ctxUsage,
            onBack = onBack,
            onTapRing = { /* could expand a sheet with details */ },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp)
        )

        BlurFade(
            backdrop = backdrop,
            isBottom = true,
            heightTotal = BottomFadeHeight,
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        AnimatedVisibility(
            visible = !isNearBottom && messages.isNotEmpty(),
            enter = fadeIn() + scaleIn(initialScale = 0.6f),
            exit = fadeOut() + scaleOut(targetScale = 0.6f),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = ComposerArea - 16.dp)
        ) {
            ScrollToBottomButton {
                scope.launch { listState.animateScrollToItem(messages.size - 1) }
            }
        }

        Composer(
            value = input,
            onValueChange = { input = it },
            model = settings.model,
            thinking = convoThinking || settings.model == MODEL_REASONER,
            sending = streaming,
            onSend = {
                if (input.isNotBlank() && !streaming) {
                    vm.send(input); input = ""
                }
            },
            onStop = vm::cancelStream,
            onOpenModelSheet = { modelSheetOpen = true },
            onVoice = {
                val granted = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                if (granted) vm.startVoice() else micPerm.launch(Manifest.permission.RECORD_AUDIO)
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        )

        // Model picker sheet (shared composable in ModelPickerSheet.kt).
        if (modelSheetOpen) {
            ModelPickerSheet(
                models = modelsState.list.map { it.id },
                loading = modelsState.loading,
                error = modelsState.error,
                currentModel = settings.model,
                convoThinking = convoThinking,
                showThinkingToggle = true,
                onPick = { vm.setModel(it); modelSheetOpen = false },
                onToggleThinking = vm::setConversationThinking,
                onRefresh = vm::refreshModels,
                onDismiss = { modelSheetOpen = false }
            )
        }

        // Voice input sheet.
        if (voiceVisible) {
            VoiceSheet(
                state = voiceState,
                onConfirm = { transcript ->
                    input = if (input.isBlank()) transcript else "$input $transcript"
                    vm.dismissVoice()
                },
                onCancel = vm::cancelVoice,
                onDismiss = vm::dismissVoice,
                onRetry = vm::startVoice
            )
        }
    }
}

// =====================================================================
// Layout constants
// =====================================================================

private val TopFadeHeight = 96.dp
private val BottomFadeHeight = 180.dp
private val ComposerArea = 172.dp

// =====================================================================
// Fade / Top bar
// =====================================================================

/**
 * Frosted-glass fade overlay. Samples the underlying content from a
 * recorded [GraphicsLayer] and renders it back through a Gaussian
 * [androidx.compose.ui.graphics.BlurEffect] (API 31+), then layers an
 * alpha-mask gradient on top so the blur dissolves into the background
 * colour. Below API 31, [renderEffect] is left null — you still get a
 * clean solid fade.
 */
@Composable
private fun BlurFade(
    backdrop: GraphicsLayer,
    isBottom: Boolean,
    heightTotal: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier
) {
    val bg = MaterialTheme.colorScheme.background
    val maskBrush = if (isBottom) {
        Brush.verticalGradient(
            0.00f to bg.copy(alpha = 0f),
            0.35f to bg.copy(alpha = 0.55f),
            0.70f to bg.copy(alpha = 0.92f),
            1.00f to bg
        )
    } else {
        Brush.verticalGradient(
            0.00f to bg,
            0.30f to bg.copy(alpha = 0.92f),
            0.65f to bg.copy(alpha = 0.55f),
            1.00f to bg.copy(alpha = 0f)
        )
    }
    val blurEffect = remember {
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            BlurEffect(30f, 30f, TileMode.Clamp)
        } else null
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(heightTotal)
    ) {
        // Backdrop pass: sample the recorded layer, clip to our bounds,
        // apply Gaussian blur. The fade box only covers a slice of the
        // screen, so for the bottom fade we translate the layer up so its
        // bottom slice aligns with this box.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    clip = true
                    renderEffect = blurEffect
                }
                .drawBehind {
                    val ty = if (isBottom) -(backdrop.size.height.toFloat() - size.height) else 0f
                    translate(top = ty) { drawLayer(backdrop) }
                }
        )
        // Mask pass: alpha gradient that fades the blurred layer into bg.
        Box(
            Modifier
                .fillMaxSize()
                .background(maskBrush)
        )
    }
}

@Composable
private fun WindowInsetsTopHeight(): androidx.compose.ui.unit.Dp {
    val density = LocalDensity.current
    return with(density) { WindowInsets.statusBars.getTop(density).toDp() }
}

@Composable
private fun TopBar(
    title: String,
    usage: ContextUsage,
    onBack: () -> Unit,
    onTapRing: () -> Unit,
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
        ContextRing(usage = usage, onClick = onTapRing)
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

/**
 * MD3 expressive determinate ring: thick rounded active arc + thick rounded
 * track arc separated by the standard 4dp track-gap on each side. The track
 * is *always* drawn (so the ring is visible even at 0%), the active arc
 * grows clockwise from 12 o'clock. Tap = stats sheet (not wired yet).
 *
 * Spec source: m3.material.io / CircularProgressIndicator – gap 4dp,
 * stroke ~4dp, rounded stroke cap.
 */
@Composable
private fun ContextRing(usage: ContextUsage, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val fraction by animateFloatAsState(
        targetValue = usage.fraction,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "ringFrac"
    )
    val warn = fraction > 0.78f
    val activeColor = if (warn) cs.error else cs.primary
    val trackColor = cs.surfaceContainerHighest

    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .bouncyClickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(32.dp)) {
            val stroke = 3.5.dp.toPx()
            val gapDp = 4.dp.toPx()
            // Convert linear track gap to degrees on the indicator circle.
            val radius = (size.minDimension - stroke) / 2f
            val gapDeg = (gapDp / radius) * (180f / Math.PI.toFloat())

            val usedSweep = (fraction * 360f).coerceIn(0f, 360f)
            // Active arc – grows clockwise from 12 o'clock.
            if (usedSweep > 0.5f) {
                drawArc(
                    color = activeColor,
                    startAngle = -90f,
                    sweepAngle = usedSweep,
                    useCenter = false,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }
            // Track arc – fills the rest, separated by gap on both sides.
            val trackStart = -90f + usedSweep + gapDeg
            val trackSweep = 360f - usedSweep - gapDeg * 2f
            if (trackSweep > 0.5f) {
                drawArc(
                    color = trackColor,
                    startAngle = trackStart,
                    sweepAngle = trackSweep,
                    useCenter = false,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }
        }
        Text(
            "${(fraction * 100).toInt()}",
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = androidx.compose.ui.unit.TextUnit(10f, androidx.compose.ui.unit.TextUnitType.Sp)
            ),
            color = if (warn) cs.error else cs.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold
        )
    }
}

// =====================================================================
// Messages
// =====================================================================

@Composable
private fun MessageItem(m: Message, onOpenUrl: (String) -> Unit) {
    if (m.archived) return
    if (m.kind == MessageKind.CONTEXT_SUMMARY) {
        ArchivedSummary(m)
        return
    }
    if (m.role == Role.ASSISTANT && m.kind == MessageKind.TEXT &&
        m.content.isBlank() && m.reasoning.isNullOrBlank() && m.toolData == null) {
        return
    }
    if (m.role == Role.ASSISTANT && m.toolData != null && m.content.isBlank()) {
        // tool_call carrier – UI is the following tool_result row
        return
    }
    if (m.kind == MessageKind.TOOL_RESULT) {
        AnimatedVisibility(
            visible = true,
            enter = fadeIn(spring(stiffness = Spring.StiffnessMediumLow)) +
                slideInVertically(initialOffsetY = { it / 6 }, animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow
                ))
        ) {
            ToolMessageCard(message = m, onOpenUrl = onOpenUrl)
        }
        return
    }

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
private fun ArchivedSummary(m: Message) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(cs.surfaceContainerLow)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Outlined.AutoAwesome,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = cs.primary
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "早期对话已自动向量化压缩",
            style = MaterialTheme.typography.labelLarge,
            color = cs.onSurfaceVariant
        )
    }
}

@Composable
private fun UserBubble(content: String) {
    val cs = MaterialTheme.colorScheme
    // Distinct user-bubble colour – noticeably deeper than the
    // surfaceContainerHigh used by other surface chips.
    val bg = if (isLight()) Color(0xFFE0DACE) else Color(0xFF2D2D33)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(bg)
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
private fun isLight(): Boolean = MaterialTheme.colorScheme.background.luminance() > 0.5f
private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

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
    thinking: Boolean,
    sending: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onOpenModelSheet: () -> Unit,
    onVoice: () -> Unit,
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
            ComposerModelChip(model = model, thinking = thinking, onClick = onOpenModelSheet)
            Spacer(Modifier.weight(1f))
            CircleAction(
                icon = Icons.Rounded.Mic,
                contentDescription = "语音输入",
                onClick = onVoice
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
private fun ComposerModelChip(model: String, thinking: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val bg = if (thinking) cs.primaryContainer else cs.surfaceContainerHigh
    val fg = if (thinking) cs.onPrimaryContainer else cs.onSurface
    // Reveal the thinking icon with a bouncy expand-in transition so flipping
    // reasoner on/off animates the capsule width with content-aware tween.
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .bouncyClickable(pressedScale = 0.94f, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AnimatedVisibility(
            visible = thinking,
            enter = fadeIn() + expandHorizontally(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow
                ),
                expandFrom = Alignment.Start
            ),
            exit = fadeOut() + shrinkHorizontally(shrinkTowards = Alignment.Start)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = fg
                )
                Spacer(Modifier.width(4.dp))
            }
        }
        AnimatedContent(
            targetState = model.removePrefix("deepseek-"),
            transitionSpec = {
                (fadeIn(tween(160)) + slideInVertically(
                    initialOffsetY = { it / 2 },
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )).togetherWith(fadeOut(tween(120)))
            },
            label = "modelName"
        ) { name ->
            Text(
                name,
                style = MaterialTheme.typography.labelLarge,
                color = fg,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(2.dp))
        Icon(
            Icons.Rounded.ExpandMore,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = fg
        )
    }
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
// Model picker bottom sheet
// =====================================================================

// =====================================================================
// Voice sheet
// =====================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceSheet(
    state: VoiceState,
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    onRetry: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = cs.surface
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val (icon, text) = when (state) {
                is VoiceState.Idle -> Icons.Rounded.Mic to "等待开始…"
                is VoiceState.Listening -> Icons.Rounded.Mic to (state.partial.ifBlank { "在听…" })
                is VoiceState.Result -> Icons.Outlined.AutoAwesome to state.text
                is VoiceState.Error -> Icons.Rounded.Mic to state.message
            }
            val pulse by animateFloatAsState(
                targetValue = if (state is VoiceState.Listening) 1.08f else 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                ),
                label = "pulse"
            )
            Box(
                Modifier
                    .size(72.dp)
                    .scale(pulse)
                    .clip(CircleShape)
                    .background(cs.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(34.dp),
                    tint = cs.onPrimaryContainer
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                color = cs.onSurface
            )
            Spacer(Modifier.height(22.dp))
            Row {
                SheetButton(
                    label = "取消",
                    color = cs.surfaceContainerHigh,
                    onColor = cs.onSurface,
                    onClick = onCancel,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(10.dp))
                when (state) {
                    is VoiceState.Result -> SheetButton(
                        label = "确认填入",
                        color = cs.tertiary,
                        onColor = cs.onTertiary,
                        onClick = { onConfirm(state.text) },
                        modifier = Modifier.weight(1f)
                    )
                    is VoiceState.Error -> SheetButton(
                        label = "重试",
                        color = cs.primary,
                        onColor = cs.onPrimary,
                        onClick = onRetry,
                        modifier = Modifier.weight(1f)
                    )
                    is VoiceState.Listening -> SheetButton(
                        label = "停止",
                        color = cs.tertiary,
                        onColor = cs.onTertiary,
                        onClick = onCancel,
                        modifier = Modifier.weight(1f)
                    )
                    else -> SheetButton(
                        label = "开始",
                        color = cs.primary,
                        onColor = cs.onPrimary,
                        onClick = onRetry,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun SheetButton(
    label: String,
    color: Color,
    onColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color)
            .bouncyClickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            color = onColor,
            fontWeight = FontWeight.SemiBold
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
private fun StatusPill(text: String) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(cs.surfaceContainerHigh)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
            color = cs.primary
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = cs.onSurface,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun ErrorRow(message: String, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
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
            "在底部聊天栏的模型胶囊里可以切换模型与思考模式；点 🎤 可以语音输入。",
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant
        )
    }
}
