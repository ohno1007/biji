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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
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
import com.biji.notes.net.Tools
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.biji.notes.ui.glass.bouncyClickable
import com.biji.notes.ui.markdown.MarkdownText
import com.biji.notes.voice.VoiceState
import kotlinx.coroutines.launch

/** Shared-element key for the bottom dock (nav pill ↔ chat composer). */
const val DOCK_SHARED_KEY = "biji-bottom-dock"

/** A single visible row in the chat log. */
sealed interface ChatItem {
    val key: Any
    data class Plain(val message: Message) : ChatItem {
        override val key: Any get() = "p-${message.id}"
    }
    /** One or more consecutive "Searched for X" chips rendered together
     *  with tight (4dp) internal spacing instead of the default 18dp
     *  used between regular chat rows. */
    data class SearchGroup(val messages: List<Message>) : ChatItem {
        override val key: Any get() = "sg-${messages.first().id}-${messages.last().id}"
    }
}

/**
 * Walk through the message list in chronological order, batching every
 * run of consecutive web_search tool-results into one
 * [ChatItem.SearchGroup] so the chips render tightly stacked (like a
 * reasoning toggle and its body) rather than with the chat's default
 * 18dp gap between unrelated turns.
 *
 * read_url and other tool kinds are silently dropped — the article body
 * is already folded into the next assistant answer.
 */
internal fun groupChatItems(messages: List<Message>): List<ChatItem> {
    val out = mutableListOf<ChatItem>()
    val pending = mutableListOf<Message>()
    fun flush() {
        if (pending.isNotEmpty()) {
            out += ChatItem.SearchGroup(pending.toList())
            pending.clear()
        }
    }
    for (m in messages) {
        if (m.archived) continue
        val isSearch = m.kind == MessageKind.TOOL_RESULT && runCatching {
            Lite.parseToJsonElement(m.toolData.orEmpty())
                .jsonObject["kind"]?.jsonPrimitive?.contentOrNull
        }.getOrNull() == Tools.WEB_SEARCH
        when {
            isSearch -> pending += m
            m.kind == MessageKind.TOOL_RESULT -> Unit  // non-search tools hidden
            // Silent assistant carrier (only tool_calls, no text & no reasoning) — hidden.
            m.role == Role.ASSISTANT && m.toolData != null &&
                m.content.isBlank() && m.reasoning.isNullOrBlank() -> Unit
            else -> {
                flush()
                out += ChatItem.Plain(m)
            }
        }
    }
    flush()
    return out
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@Composable
fun ChatScreen(
    vm: ChatViewModel,
    onBack: () -> Unit,
    sharedTransitionScope: androidx.compose.animation.SharedTransitionScope? = null,
    animatedVisibilityScope: androidx.compose.animation.AnimatedVisibilityScope? = null
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
    val workflow by vm.workflow.collectAsState()
    val ctx = LocalContext.current
    val convoThinking = conversations.firstOrNull { it.id == convoId }?.thinking == true

    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var modelSheetOpen by remember { mutableStateOf(false) }
    var contextStatsOpen by remember { mutableStateOf(false) }
    var modelPickerOpen by remember { mutableStateOf(false) }

    // Pull a fresh models list as soon as we land on a chat screen.
    LaunchedEffect(settings.apiKey, settings.baseUrl) { vm.ensureModelsLoaded() }

    // Microphone permission launcher.
    val micPerm = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) vm.startVoice() }

    LaunchedEffect(messages.size, streaming, workflow.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    val isNearBottom by remember {
        derivedStateOf {
            val li = listState.layoutInfo
            val last = li.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf true
            last >= (li.totalItemsCount - 1)
        }
    }

    // Snapshot the last non-empty workflow so AnimatedVisibility's exit
    // animation still has steps to render. Without this, the panel
    // would be invoked with an empty list during the fade-out frame
    // and crash on `steps.last()`.
    var lastWorkflow by remember { mutableStateOf<List<WorkflowStep>>(emptyList()) }
    LaunchedEffect(workflow) {
        if (workflow.isNotEmpty()) lastWorkflow = workflow
    }
    // The Column below holds (optional) WorkflowPanel + Composer. Its
    // measured height becomes the LazyColumn's bottom reserve so chat
    // content never scrolls underneath either piece.
    val density = LocalDensity.current
    var workflowPanelHeight by remember { mutableStateOf(0.dp) }
    val dockReserve = workflowPanelHeight + 8.dp

    Box(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 18.dp, end = 18.dp,
                top = TopFadeHeight + 12.dp,
                bottom = dockReserve
            ),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            if (messages.isEmpty()) {
                item { EmptyChatHint() }
            } else {
                val rendered = groupChatItems(messages)
                items(rendered, key = { it.key }) { item ->
                    when (item) {
                        is ChatItem.Plain ->
                            MessageItem(m = item.message, onOpenUrl = vm::openWebUrl)
                        is ChatItem.SearchGroup -> {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                item.messages.forEach { m ->
                                    SearchedForChip(message = m, onOpenUrl = vm::openWebUrl)
                                }
                            }
                        }
                    }
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

        TopFade(modifier = Modifier.align(Alignment.TopCenter))

        TopBar(
            title = title,
            usage = ctxUsage,
            onBack = onBack,
            onTapRing = { contextStatsOpen = true },
            ringVisible = !contextStatsOpen,
            sharedTransitionScope = sharedTransitionScope,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp)
        )

        BottomFade(modifier = Modifier.align(Alignment.BottomCenter))

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

        // Workflow + Composer stacked in a single bottom-anchored Column
        // so the panel always sits flush on top of the composer (rather
        // than at a fixed `bottom = ComposerArea` offset, which left a
        // visible gap between the two).
        val composerModifier = Modifier
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp)
        val sharedModifier =
            if (sharedTransitionScope != null && animatedVisibilityScope != null) {
                with(sharedTransitionScope) {
                    Modifier.sharedBounds(
                        rememberSharedContentState(key = DOCK_SHARED_KEY),
                        animatedVisibilityScope = animatedVisibilityScope,
                        enter = fadeIn(tween(220)),
                        exit = fadeOut(tween(140)),
                        resizeMode = androidx.compose.animation.SharedTransitionScope
                            .ResizeMode.RemeasureToBounds
                    )
                }
            } else Modifier
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onSizeChanged { sz ->
                    // Track the *combined* workflow + composer footprint so
                    // the chat scroll content leaves enough room above it.
                    workflowPanelHeight = with(density) { sz.height.toDp() }
                }
        ) {
            androidx.compose.animation.AnimatedVisibility(
                visible = workflow.isNotEmpty(),
                enter = fadeIn(tween(180)) +
                    slideInVertically(initialOffsetY = { it / 2 }),
                exit = fadeOut(tween(140)) +
                    androidx.compose.animation.slideOutVertically(targetOffsetY = { it / 2 })
            ) {
                val toShow = if (workflow.isNotEmpty()) workflow else lastWorkflow
                WorkflowPanel(steps = toShow)
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
                onOpenModelSheet = { modelPickerOpen = true },
                onVoice = {
                    val granted = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (granted) vm.startVoice() else micPerm.launch(Manifest.permission.RECORD_AUDIO)
                },
                modelPickerVisible = modelPickerOpen,
                sharedTransitionScope = sharedTransitionScope,
                modifier = composerModifier.then(sharedModifier)
            )
        }

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

        // Context-stats popup. Its bounds morph out of the ContextRing
        // (shared content state CONTEXT_STATS_KEY).
        AnimatedVisibility(
            visible = contextStatsOpen,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(140)),
            modifier = Modifier.fillMaxSize()
        ) {
            ContextStatsScrim(onDismiss = { contextStatsOpen = false }) {
                ContextStatsPopup(
                    usage = ctxUsage,
                    model = settings.model,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = this@AnimatedVisibility,
                    onDismiss = { contextStatsOpen = false }
                )
            }
        }

        // Model-picker popup. Its bounds morph out of the composer's
        // model chip (shared content state MODEL_PICKER_KEY).
        AnimatedVisibility(
            visible = modelPickerOpen,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(140)),
            modifier = Modifier.fillMaxSize()
        ) {
            ContextStatsScrim(onDismiss = { modelPickerOpen = false }) {
                ModelPickerPopup(
                    models = modelsState.list.map { it.id },
                    loading = modelsState.loading,
                    error = modelsState.error,
                    currentModel = settings.model,
                    convoThinking = convoThinking,
                    onPick = { vm.setModel(it); modelPickerOpen = false },
                    onToggleThinking = vm::setConversationThinking,
                    onRefresh = vm::refreshModels,
                    onDismiss = { modelPickerOpen = false },
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = this@AnimatedVisibility
                )
            }
        }
    }
}

/** Dimmed scrim under our custom popups — tap to dismiss. The scrim
 *  colour darkens in dark mode and tints toward black in light mode so
 *  the popup card always reads above the chat content. */
@Composable
private fun ContextStatsScrim(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val scrimColor = if (isDark) Color.Black.copy(alpha = 0.50f)
        else Color.Black.copy(alpha = 0.32f)
    Box(
        Modifier
            .fillMaxSize()
            .background(scrimColor)
            .pointerInput(Unit) {
                detectTapGestures { onDismiss() }
            }
    ) {
        content()
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

/**
 * Public re-export so other screens (Settings, ConversationList) can use
 * the exact same top-edge fade as the chat screen.
 */
@Composable
fun ChatTopFade(modifier: Modifier = Modifier, totalHeight: androidx.compose.ui.unit.Dp = TopFadeHeight) {
    val bg = MaterialTheme.colorScheme.background
    Box(
        modifier
            .fillMaxWidth()
            .height(totalHeight)
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
private fun WindowInsetsTopHeight(): androidx.compose.ui.unit.Dp {
    val density = LocalDensity.current
    return with(density) { WindowInsets.statusBars.getTop(density).toDp() }
}

@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@Composable
private fun TopBar(
    title: String,
    usage: ContextUsage,
    onBack: () -> Unit,
    onTapRing: () -> Unit,
    ringVisible: Boolean,
    sharedTransitionScope: androidx.compose.animation.SharedTransitionScope?,
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
        // Keep the ring's slot 40dp wide even when hidden, so the title's
        // weighted layout doesn't reflow when the popup opens. The
        // fully-qualified AnimatedVisibility call avoids the ambient
        // RowScope.AnimatedVisibility extension from kicking in.
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            androidx.compose.animation.AnimatedVisibility(
                visible = ringVisible,
                enter = fadeIn(tween(120)),
                exit = fadeOut(tween(120))
            ) {
                ContextRing(
                    usage = usage,
                    onClick = onTapRing,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = this@AnimatedVisibility
                )
            }
        }
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
private const val CONTEXT_STATS_KEY = "biji-context-stats"

@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@Composable
private fun ContextRing(
    usage: ContextUsage,
    onClick: () -> Unit,
    sharedTransitionScope: androidx.compose.animation.SharedTransitionScope?,
    animatedVisibilityScope: androidx.compose.animation.AnimatedVisibilityScope?
) {
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
    val trackColor = cs.outline.copy(alpha = 0.55f)

    val sharedMod = if (sharedTransitionScope != null && animatedVisibilityScope != null) {
        with(sharedTransitionScope) {
            Modifier.sharedBounds(
                rememberSharedContentState(key = CONTEXT_STATS_KEY),
                animatedVisibilityScope = animatedVisibilityScope,
                enter = fadeIn(tween(240)),
                exit = fadeOut(tween(140)),
                resizeMode = androidx.compose.animation.SharedTransitionScope
                    .ResizeMode.RemeasureToBounds
            )
        }
    } else Modifier

    Box(
        modifier = sharedMod
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
        if (isUser) UserBubble(m.content) else AssistantBlock(m, onOpenUrl = onOpenUrl)
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
private fun AssistantBlock(m: Message, onOpenUrl: (String) -> Unit = {}) {
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
            MarkdownText(markdown = m.content, onOpenUrl = onOpenUrl)
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

@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
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
    modelPickerVisible: Boolean = false,
    sharedTransitionScope: androidx.compose.animation.SharedTransitionScope? = null,
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
            // Cap visible lines so long input grows vertically up to 6 rows
            // and then scrolls internally — the composer's bounding box width
            // never balloons with the text length.
            maxLines = 6,
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
            ComposerModelChip(
                model = model,
                thinking = thinking,
                onClick = onOpenModelSheet,
                visible = !modelPickerVisible,
                sharedTransitionScope = sharedTransitionScope,
            )
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

@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@Composable
private fun ComposerModelChip(
    model: String,
    thinking: Boolean,
    onClick: () -> Unit,
    visible: Boolean = true,
    sharedTransitionScope: androidx.compose.animation.SharedTransitionScope? = null
) {
    // Wrap in AnimatedVisibility so the chip's sharedBounds element has a
    // proper scope to morph from when the model picker opens.
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(140)),
        exit = fadeOut(tween(120))
    ) {
        InnerComposerModelChip(
            model = model,
            thinking = thinking,
            onClick = onClick,
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = this@AnimatedVisibility
        )
    }
}

@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@Composable
private fun InnerComposerModelChip(
    model: String,
    thinking: Boolean,
    onClick: () -> Unit,
    sharedTransitionScope: androidx.compose.animation.SharedTransitionScope?,
    animatedVisibilityScope: androidx.compose.animation.AnimatedVisibilityScope
) {
    val cs = MaterialTheme.colorScheme
    val bg = if (thinking) cs.primaryContainer else cs.surfaceContainerHigh
    val fg = if (thinking) cs.onPrimaryContainer else cs.onSurface
    val sharedMod = if (sharedTransitionScope != null) {
        with(sharedTransitionScope) {
            Modifier.sharedBounds(
                rememberSharedContentState(key = MODEL_PICKER_KEY),
                animatedVisibilityScope = animatedVisibilityScope,
                enter = fadeIn(tween(240)),
                exit = fadeOut(tween(140)),
                resizeMode = androidx.compose.animation.SharedTransitionScope
                    .ResizeMode.RemeasureToBounds
            )
        }
    } else Modifier
    Row(
        modifier = sharedMod
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

/**
 * Stage-tracker panel that floats above the composer while a multi-tool
 * turn is in flight. 80% of the screen width, frosted-glass surface,
 * one row per step (running / done / error). Hidden when the workflow
 * list is empty.
 */
@Composable
private fun WorkflowPanel(steps: List<WorkflowStep>) {
    if (steps.isEmpty()) return
    val cs = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(true) }
    val rotation by animateFloatAsState(
        targetValue = if (open) 180f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "wfPanelChev"
    )
    val active = steps.lastOrNull { it.state == WorkflowStepState.RUNNING } ?: steps.last()
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .fillMaxWidth(0.8f)
                .clip(RoundedCornerShape(18.dp))
                .background(cs.surface.copy(alpha = 0.85f))
                .androidx_border_compat(cs.outlineVariant.copy(alpha = 0.7f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .bouncyClickable(pressedScale = 0.99f) { open = !open }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StepIcon(state = active.state)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (active.state == WorkflowStepState.RUNNING)
                        active.label
                    else
                        "${steps.size} 步已完成",
                    style = MaterialTheme.typography.labelLarge,
                    color = cs.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    Icons.Rounded.ExpandMore,
                    contentDescription = if (open) "收起" else "展开",
                    modifier = Modifier.size(16.dp).rotate(rotation),
                    tint = cs.onSurfaceVariant
                )
            }
            AnimatedVisibility(
                visible = open,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(
                    Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    steps.forEachIndexed { i, s ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${i + 1}.",
                                style = MaterialTheme.typography.labelSmall,
                                color = cs.onSurfaceVariant
                            )
                            Spacer(Modifier.width(8.dp))
                            StepIcon(state = s.state)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                s.label,
                                style = MaterialTheme.typography.bodySmall,
                                color = cs.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepIcon(state: WorkflowStepState) {
    val cs = MaterialTheme.colorScheme
    when (state) {
        WorkflowStepState.RUNNING ->
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 1.5.dp,
                color = cs.primary
            )
        WorkflowStepState.DONE ->
            Icon(
                Icons.Rounded.Check,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = cs.primary
            )
        WorkflowStepState.ERROR ->
            Icon(
                Icons.Outlined.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = cs.error
            )
    }
}

private fun Modifier.androidx_border_compat(color: Color) =
    this.then(
        Modifier.drawBehind {
            drawRect(
                color = color,
                size = size,
                style = Stroke(width = 0.5.dp.toPx())
            )
        }
    )

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

// =====================================================================
// Context-stats popup — bounds morph from the ring's CONTEXT_STATS_KEY
// =====================================================================

@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@Composable
private fun ContextStatsPopup(
    usage: ContextUsage,
    model: String,
    sharedTransitionScope: androidx.compose.animation.SharedTransitionScope?,
    animatedVisibilityScope: androidx.compose.animation.AnimatedVisibilityScope,
    onDismiss: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val sharedMod = if (sharedTransitionScope != null) {
        with(sharedTransitionScope) {
            Modifier.sharedBounds(
                rememberSharedContentState(key = CONTEXT_STATS_KEY),
                animatedVisibilityScope = animatedVisibilityScope,
                enter = fadeIn(tween(240)),
                exit = fadeOut(tween(160)),
                resizeMode = androidx.compose.animation.SharedTransitionScope
                    .ResizeMode.RemeasureToBounds
            )
        }
    } else Modifier
    Box(
        Modifier.fillMaxSize().statusBarsPadding().padding(16.dp),
        contentAlignment = Alignment.TopEnd
    ) {
        Column(
            modifier = sharedMod
                .widthIn(min = 280.dp, max = 360.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(cs.surface)
                .pointerInput(Unit) {
                    // Swallow taps so the scrim's tap-to-dismiss doesn't
                    // fire when the user touches the popup card.
                    detectTapGestures { /* eat */ }
                }
                .padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(56.dp)
                        .clip(CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(Modifier.size(48.dp)) {
                        val stroke = 4.dp.toPx()
                        val gapDp = 4.dp.toPx()
                        val radius = (size.minDimension - stroke) / 2f
                        val gapDeg = (gapDp / radius) * (180f / Math.PI.toFloat())
                        val warn = usage.fraction > 0.78f
                        val activeColor = if (warn) cs.error else cs.primary
                        val trackColor = cs.outline.copy(alpha = 0.55f)
                        val used = (usage.fraction * 360f).coerceIn(0f, 360f)
                        if (used > 0.5f) drawArc(
                            color = activeColor,
                            startAngle = -90f, sweepAngle = used,
                            useCenter = false,
                            style = Stroke(width = stroke, cap = StrokeCap.Round)
                        )
                        val trackStart = -90f + used + gapDeg
                        val trackSweep = 360f - used - gapDeg * 2
                        if (trackSweep > 0.5f) drawArc(
                            color = trackColor,
                            startAngle = trackStart, sweepAngle = trackSweep,
                            useCenter = false,
                            style = Stroke(width = stroke, cap = StrokeCap.Round)
                        )
                    }
                    Text(
                        "${(usage.fraction * 100).toInt()}%",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = cs.onSurface
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        "上下文用量",
                        style = MaterialTheme.typography.titleMedium,
                        color = cs.onSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        model.removePrefix("deepseek-"),
                        style = MaterialTheme.typography.labelLarge,
                        color = cs.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            StatsRow(label = "已用 token", value = formatTokens(usage.tokens))
            Spacer(Modifier.height(6.dp))
            StatsRow(label = "上下文窗口", value = formatTokens(usage.limit))
            Spacer(Modifier.height(6.dp))
            StatsRow(
                label = "剩余预算",
                value = formatTokens((usage.limit - usage.tokens).coerceAtLeast(0L))
            )
            Spacer(Modifier.height(14.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(50))
                    .background(cs.primary)
                    .bouncyClickable(pressedScale = 0.97f, onClick = onDismiss)
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "好",
                    color = cs.onPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun StatsRow(label: String, value: String) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            value,
            style = MaterialTheme.typography.titleMedium.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
            color = cs.onSurface,
            fontWeight = FontWeight.SemiBold
        )
    }
}

private fun formatTokens(n: Long): String =
    when {
        n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
        n >= 1_000 -> "%.1fK".format(n / 1_000.0)
        else -> n.toString()
    }

// =====================================================================
// Model-picker popup — bounds morph from the composer model chip
// =====================================================================

private const val MODEL_PICKER_KEY = "biji-model-picker"

@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@Composable
private fun ModelPickerPopup(
    models: List<String>,
    loading: Boolean,
    error: String?,
    currentModel: String,
    convoThinking: Boolean,
    onPick: (String) -> Unit,
    onToggleThinking: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
    sharedTransitionScope: androidx.compose.animation.SharedTransitionScope?,
    animatedVisibilityScope: androidx.compose.animation.AnimatedVisibilityScope
) {
    val cs = MaterialTheme.colorScheme
    val sharedMod = if (sharedTransitionScope != null) {
        with(sharedTransitionScope) {
            Modifier.sharedBounds(
                rememberSharedContentState(key = MODEL_PICKER_KEY),
                animatedVisibilityScope = animatedVisibilityScope,
                enter = fadeIn(tween(240)),
                exit = fadeOut(tween(160)),
                resizeMode = androidx.compose.animation.SharedTransitionScope
                    .ResizeMode.RemeasureToBounds
            )
        }
    } else Modifier
    val all = (listOf(com.biji.notes.data.MODEL_CHAT, com.biji.notes.data.MODEL_REASONER) + models).distinct()
    Box(
        Modifier.fillMaxSize().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 90.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            modifier = sharedMod
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(cs.surface)
                .pointerInput(Unit) {
                    detectTapGestures { /* eat */ }
                }
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "选择模型",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = cs.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .bouncyClickable(onClick = onRefresh)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
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
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(cs.surfaceContainer)
                    .bouncyClickable(pressedScale = 0.99f) { onToggleThinking(!convoThinking) }
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
                        "本对话 · 深度思考",
                        style = MaterialTheme.typography.titleMedium,
                        color = cs.onSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        if (convoThinking) "已开启 <think> 提示" else "未开启",
                        style = MaterialTheme.typography.labelLarge,
                        color = cs.onSurfaceVariant
                    )
                }
                Switch(
                    checked = convoThinking,
                    onCheckedChange = onToggleThinking,
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
            Spacer(Modifier.height(10.dp))
            // Scrollable model list — cap height so the popup doesn't
            // grow past the screen on long lists.
            Column(
                modifier = Modifier
                    .heightIn(max = 260.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                all.forEach { id ->
                    val selected = id == currentModel
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .bouncyClickable(pressedScale = 0.985f) { onPick(id) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
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
}
