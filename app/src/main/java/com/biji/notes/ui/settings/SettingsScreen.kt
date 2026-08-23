package com.biji.notes.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.biji.notes.data.MODEL_CHAT
import com.biji.notes.data.MODEL_REASONER
import com.biji.notes.ui.chat.BalanceState
import com.biji.notes.ui.chat.ChatViewModel
import com.biji.notes.ui.glass.bouncyClickable
import kotlinx.coroutines.launch

// iOS-style grouped settings: rows of a group share one rounded surface
// separated by inset hairlines; the cream background "镂空" between
// adjacent groups. Group corners are rounded; row corners are square
// inside the group.
private val GroupShape = RoundedCornerShape(22.dp)
private val GroupGap = 22.dp
private val SectionEdge = 16.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: ChatViewModel,
    contentPadding: PaddingValues
) {
    val settings by vm.settings.collectAsState()
    val models by vm.models.collectAsState()
    val balance by vm.balance.collectAsState()

    var apiKey by remember(settings.apiKey) { mutableStateOf(settings.apiKey) }
    var baseUrl by remember(settings.baseUrl) { mutableStateOf(settings.baseUrl) }
    var systemPrompt by remember(settings.systemPrompt) { mutableStateOf(settings.systemPrompt) }
    var temperature by remember(settings.temperature) { mutableStateOf(settings.temperature) }
    var showKey by remember { mutableStateOf(false) }
    var showModels by remember { mutableStateOf(false) }

    LaunchedEffect(apiKey) { if (apiKey != settings.apiKey) vm.setApiKey(apiKey) }
    LaunchedEffect(baseUrl) { if (baseUrl != settings.baseUrl) vm.setBaseUrl(baseUrl) }
    LaunchedEffect(systemPrompt) {
        if (systemPrompt != settings.systemPrompt) vm.setSystemPrompt(systemPrompt)
    }
    LaunchedEffect(temperature) {
        if (kotlin.math.abs(temperature - settings.temperature) > 0.001f) {
            vm.setTemperature(temperature)
        }
    }

    LaunchedEffect(settings.apiKey, settings.baseUrl) { vm.ensureModelsLoaded() }

    // Pinned scroll behavior — the large title stays fixed at the top
    // instead of collapsing on scroll.
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "设置",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { inner ->
        val topFadeHeight = 28.dp
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = SectionEdge,
                    end = SectionEdge,
                    top = inner.calculateTopPadding() + topFadeHeight,
                    bottom = inner.calculateBottomPadding() +
                        contentPadding.calculateBottomPadding() + 24.dp
                ),
                verticalArrangement = Arrangement.spacedBy(GroupGap)
            ) {
                // ===== 接入 =================================================
                item {
                    Group {
                        KeyValueRow(
                            icon = Icons.Outlined.Key,
                            title = "API Key",
                            trailing = {
                                IconAction(
                                    icon = if (showKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                    onClick = { showKey = !showKey }
                                )
                            }
                        ) {
                            BasicTextField(
                                value = apiKey,
                                onValueChange = { apiKey = it },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyLarge.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                visualTransformation = if (showKey) VisualTransformation.None
                                else PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                decorationBox = { inner ->
                                    if (apiKey.isEmpty()) {
                                        Text(
                                            "sk-...",
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                                        )
                                    }
                                    inner()
                                }
                            )
                        }
                        InsetDivider()
                        KeyValueRow(icon = Icons.Outlined.Cloud, title = "Base URL") {
                            BasicTextField(
                                value = baseUrl,
                                onValueChange = { baseUrl = it },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyLarge.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)
                            )
                        }
                    }
                }

                // ===== 账户 =================================================
                item {
                    Group { BalanceRow(state = balance, onRefresh = vm::refreshBalance) }
                }

                // ===== 模型 =================================================
                item {
                    Group {
                        NavRow(
                            icon = Icons.Outlined.AutoAwesome,
                            title = "模型",
                            onClick = { showModels = !showModels }
                        )
                        AnimatedVisibility(
                            visible = showModels,
                            enter = fadeIn() + expandVertically(
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessMediumLow
                                )
                            ),
                            exit = fadeOut() + shrinkVertically()
                        ) {
                            Column {
                                InsetDivider()
                                ModelListInline(
                                    current = settings.model,
                                    list = models.list.map { it.id },
                                    loading = models.loading,
                                    error = models.error,
                                    onPick = { vm.setModel(it) },
                                    onRefresh = vm::refreshModels
                                )
                            }
                        }
                        InsetDivider()
                        SliderRow(
                            icon = Icons.Outlined.Thermostat,
                            title = "Temperature",
                            value = temperature,
                            onChange = { temperature = it }
                        )
                        InsetDivider()
                        ToggleRow(
                            icon = Icons.Outlined.AutoAwesome,
                            title = "默认深度思考",
                            checked = settings.defaultThinking,
                            onChange = vm::setDefaultThinking
                        )
                    }
                }

                // ===== 工具与记忆 ===========================================
                item {
                    Group {
                        ToggleRow(
                            icon = Icons.Outlined.Search,
                            title = "联网搜索",
                            checked = settings.webSearch,
                            onChange = vm::setWebSearch
                        )
                        InsetDivider()
                        ToggleRow(
                            icon = Icons.Outlined.Memory,
                            title = "长期记忆",
                            checked = settings.longMemory,
                            onChange = vm::setLongMemory
                        )
                        InsetDivider()
                        ToggleRow(
                            icon = Icons.Outlined.Notifications,
                            title = "完成通知",
                            checked = settings.notify,
                            onChange = vm::setNotify
                        )
                        InsetDivider()
                        ToggleRow(
                            icon = Icons.Outlined.Build,
                            title = "工程模式",
                            checked = settings.developerMode,
                            onChange = vm::setDeveloperMode
                        )
                        if (settings.developerMode) {
                            InsetDivider()
                            SandboxPathRow(vm.sandbox.projectsBase.absolutePath)
                            InsetDivider()
                            RootAccessRow(
                                enabled = settings.useRoot,
                                onToggle = vm::setUseRoot,
                                onProbe = { vm.probeRoot() }
                            )
                            InsetDivider()
                            DevEnvRow(bootstrap = vm.bootstrap)
                        }
                    }
                }

                // ===== 语音 ================================================
                item {
                    Group {
                        VoiceModelRow(offline = vm.voice.offline)
                    }
                }

                // ===== 系统提示词 ===========================================
                item {
                    Group {
                        KeyValueRow(
                            icon = Icons.Outlined.Tune,
                            title = "系统提示词",
                            minHeight = 70.dp
                        ) {
                            BasicTextField(
                                value = systemPrompt,
                                onValueChange = { systemPrompt = it },
                                textStyle = MaterialTheme.typography.bodyLarge.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                decorationBox = { inner ->
                                    if (systemPrompt.isEmpty()) {
                                        Text(
                                            "让模型扮演特定角色或风格",
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                                        )
                                    }
                                    inner()
                                }
                            )
                        }
                    }
                }

                // ===== 关于 =================================================
                item {
                    Group {
                        BaseRow(
                            icon = Icons.Outlined.Info,
                            title = "Biji 1.2",
                            trailing = {}
                        )
                        InsetDivider()
                        CrashLogRow()
                    }
                }
            }
            // Soft top fade right under the LargeTopAppBar — same gradient
            // as the chat screen so content gently dissolves as it scrolls up.
            TopEdgeFade(
                height = topFadeHeight,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = inner.calculateTopPadding())
            )
        }
    }
}

@Composable
private fun TopEdgeFade(modifier: Modifier = Modifier, height: Dp = 28.dp) {
    val bg = MaterialTheme.colorScheme.background
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .background(
                Brush.verticalGradient(
                    0.0f to bg,
                    0.55f to bg.copy(alpha = 0.92f),
                    1.0f to bg.copy(alpha = 0f)
                )
            )
    )
}

// =====================================================================
// Grouped-card primitives
// =====================================================================

@Composable
private fun Group(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(GroupShape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
    ) { content() }
}

@Composable
private fun InsetDivider() {
    // Full-width hairline divider — no inset, no indent past the icon column.
    // (User asked for the line to be drawn all the way across.)
    HorizontalDivider(
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

@Composable
private fun BaseRow(
    icon: ImageVector?,
    title: String,
    subtitle: String? = null,
    minHeight: Dp = 60.dp,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val clickable = if (onClick != null) Modifier.bouncyClickable(pressedScale = 0.99f) { onClick() }
    else Modifier
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(clickable)
            .heightIn(min = minHeight)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp), tint = cs.onSurface)
            Spacer(Modifier.size(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = cs.onSurface,
                fontWeight = FontWeight.Medium
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant
                )
            }
        }
        trailing()
    }
}

@Composable
private fun KeyValueRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    trailing: @Composable () -> Unit = {},
    minHeight: Dp = 60.dp,
    onClick: (() -> Unit)? = null,
    editor: @Composable () -> Unit = {}
) {
    val cs = MaterialTheme.colorScheme
    val clickable = if (onClick != null) Modifier.bouncyClickable(pressedScale = 0.99f) { onClick() }
    else Modifier
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(clickable)
            .heightIn(min = minHeight)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp), tint = cs.onSurface)
        Spacer(Modifier.size(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = cs.onSurface,
                fontWeight = FontWeight.Medium
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant
                )
            }
            Box(Modifier.padding(top = 2.dp)) { editor() }
        }
        trailing()
    }
}

@Composable
private fun NavRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit
) {
    BaseRow(icon = icon, title = title, subtitle = subtitle, onClick = onClick) {
        Icon(
            Icons.Rounded.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ToggleRow(
    icon: ImageVector,
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    // The whole row flips the switch (with the standard MD3 ripple on the
    // full row surface). The Switch still works as a direct target.
    BaseRow(
        icon = icon,
        title = title,
        onClick = { onChange(!checked) }
    ) {
        Switch(
            checked = checked,
            onCheckedChange = onChange,
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
}

@Composable
private fun SliderRow(
    icon: ImageVector,
    title: String,
    value: Float,
    onChange: (Float) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp), tint = cs.onSurface)
        Spacer(Modifier.size(16.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = cs.onSurface,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "%.2f".format(value),
                    style = MaterialTheme.typography.labelLarge,
                    color = cs.onSurfaceVariant
                )
            }
            Slider(
                value = value,
                onValueChange = onChange,
                valueRange = 0f..1.5f,
                steps = 14,
                colors = SliderDefaults.colors(
                    thumbColor = cs.primary,
                    activeTrackColor = cs.primary,
                    inactiveTrackColor = cs.surfaceContainerHighest
                )
            )
        }
    }
}

@Composable
private fun IconAction(icon: ImageVector, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(50))
            .bouncyClickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = cs.onSurface)
    }
}

/** Read-only info row showing the local sandbox path with a copy
 *  button. The path is the dir under `Android/data/<pkg>/files/projects/
 *  default` — the user can put files there via the system file
 *  manager and the assistant will see them through its sandbox
 *  tools. */
@Composable
private fun SandboxPathRow(path: String) {
    val cs = MaterialTheme.colorScheme
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var copied by remember(path) { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1200)
            copied = false
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Outlined.FolderOpen,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = cs.onSurface
        )
        Spacer(Modifier.size(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "项目目录",
                style = MaterialTheme.typography.titleMedium,
                color = cs.onSurface,
                fontWeight = FontWeight.Medium
            )
            Text(
                path,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                ),
                color = cs.onSurfaceVariant,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .bouncyClickable(pressedScale = 0.95f) {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(path))
                    copied = true
                }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (copied) Icons.Rounded.Check
                else Icons.Rounded.ContentCopy,
                contentDescription = "复制路径",
                modifier = Modifier.size(14.dp),
                tint = if (copied) cs.primary else cs.onSurfaceVariant
            )
            Spacer(Modifier.size(4.dp))
            Text(
                if (copied) "已复制" else "复制",
                style = MaterialTheme.typography.labelLarge,
                color = if (copied) cs.primary else cs.onSurfaceVariant,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * Root toggle + on-demand `su -c id` probe. The toggle persists in
 * settings; flipping it on routes every subsequent shell tool call
 * through `su -c …`. The "申请权限" button does an explicit probe so
 * the user can pre-grant in Magisk / SuperSU before running tools.
 */
@Composable
private fun RootAccessRow(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onProbe: suspend () -> Boolean
) {
    val cs = MaterialTheme.colorScheme
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var probing by remember { mutableStateOf(false) }
    var lastResult by remember { mutableStateOf<Boolean?>(null) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.Build,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = cs.onSurface
            )
            Spacer(Modifier.size(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Root 模式",
                    style = MaterialTheme.typography.titleMedium,
                    color = cs.onSurface,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    when (lastResult) {
                        true -> "su 可用"
                        false -> "su 不可用"
                        null -> if (enabled) "走 su -c" else "普通进程"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = when (lastResult) {
                        true -> cs.primary
                        false -> cs.error
                        null -> cs.onSurfaceVariant
                    }
                )
            }
            androidx.compose.material3.Switch(
                checked = enabled,
                onCheckedChange = onToggle
            )
        }
        Spacer(Modifier.size(8.dp))
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(cs.primary.copy(alpha = 0.14f))
                .bouncyClickable(enabled = !probing, pressedScale = 0.96f) {
                    probing = true
                    scope.launch {
                        lastResult = runCatching { onProbe() }.getOrDefault(false)
                        probing = false
                    }
                }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (probing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    color = cs.primary,
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    "正在请求 su…",
                    style = MaterialTheme.typography.labelLarge,
                    color = cs.primary,
                    fontWeight = FontWeight.SemiBold
                )
            } else {
                Text(
                    "申请 root 权限",
                    style = MaterialTheme.typography.labelLarge,
                    color = cs.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun VoiceModelRow(offline: com.biji.notes.voice.OfflineVoiceRecognizer) {
    val cs = MaterialTheme.colorScheme
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val progress by offline.install.collectAsState()
    var installed by remember { mutableStateOf(offline.installed) }
    // 模型在不在，和「离线识别能不能用」是两件事：老用户升上来时模型是全的、
    // 只缺这次改成现下的 libvosk.so，installed 是 false。卸载按钮必须挂在
    // modelPresent 上，否则那个最大 1.3 GB 的模型在 UI 上就删不掉了。
    var modelPresent by remember { mutableStateOf(offline.modelPresent) }
    var needsLib by remember { mutableStateOf(offline.modelPresent && offline.nativeMissing) }
    var picked by remember { mutableStateOf(offline.defaultModel) }
    var menuOpen by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(progress) {
        installed = offline.installed
        modelPresent = offline.modelPresent
        needsLib = offline.modelPresent && offline.nativeMissing
    }
    val busy = progress is com.biji.notes.voice.OfflineVoiceRecognizer.InstallProgress.Downloading ||
        progress is com.biji.notes.voice.OfflineVoiceRecognizer.InstallProgress.Extracting

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // 标题行 —— 标题一行写完，模型 chip 单独一行，避免标题被压成竖排
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.Mic,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = if (installed) cs.primary else cs.onSurface
            )
            Spacer(Modifier.size(16.dp))
            Text(
                "离线语音识别",
                style = MaterialTheme.typography.titleMedium,
                color = cs.onSurface,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            if (installed) {
                Spacer(Modifier.size(8.dp))
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(cs.primary)
                )
            }
        }
        Spacer(Modifier.size(10.dp))
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(50))
                    .background(cs.surfaceContainerHigh)
                    .bouncyClickable(enabled = !busy, pressedScale = 0.98f) {
                        menuOpen = true
                    }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    picked.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = cs.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Text(
                    picked.sizeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant
                )
                Spacer(Modifier.size(6.dp))
                Icon(
                    Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = cs.onSurfaceVariant
                )
            }
            androidx.compose.material3.DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false }
            ) {
                offline.availableModels.forEach { m ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("${m.label} · ${m.sizeLabel}") },
                        onClick = {
                            picked = m
                            menuOpen = false
                        }
                    )
                }
            }
        }
        when (val p = progress) {
            is com.biji.notes.voice.OfflineVoiceRecognizer.InstallProgress.Downloading -> {
                Spacer(Modifier.size(8.dp))
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { if (p.total > 0) p.bytes.toFloat() / p.total else 0f },
                    modifier = Modifier.fillMaxWidth(),
                    color = cs.primary
                )
            }
            is com.biji.notes.voice.OfflineVoiceRecognizer.InstallProgress.Extracting -> {
                Spacer(Modifier.size(8.dp))
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { p.current.toFloat() / p.total.toFloat().coerceAtLeast(1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = cs.primary
                )
            }
            is com.biji.notes.voice.OfflineVoiceRecognizer.InstallProgress.Failed -> {
                Spacer(Modifier.size(6.dp))
                Text(
                    p.message,
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.error
                )
            }
            else -> Unit
        }
        // 模型齐了只缺 native 库 —— 独立的一条路，**不能**并进「下载」。
        // 「下载」按的是上面选中的那个模型，把补库悄悄塞进去，用户选了大模型
        // 却拿到「已安装」+ 原来的小模型，而且一个字都不提。
        if (needsLib) {
            Spacer(Modifier.size(8.dp))
            Text(
                "模型已在本机，缺离线语音库（约 2.9 MB）。补齐后即可离线识别；" +
                    "在此之前用的是系统语音识别。",
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant
            )
        }
        Spacer(Modifier.size(10.dp))
        Row {
            if (needsLib) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(cs.primary.copy(alpha = 0.14f))
                        .bouncyClickable(enabled = !busy, pressedScale = 0.96f) {
                            scope.launch { offline.repairNativeLib() }
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        "补齐语音库",
                        style = MaterialTheme.typography.labelLarge,
                        color = cs.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(Modifier.size(8.dp))
            }
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (needsLib) cs.surfaceContainerHigh
                        else cs.primary.copy(alpha = 0.14f)
                    )
                    .bouncyClickable(enabled = !busy, pressedScale = 0.96f) {
                        scope.launch { offline.installModel(picked.url) }
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    if (modelPresent) "重新下载" else "下载",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (needsLib) cs.onSurfaceVariant else cs.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }
            if (modelPresent) {
                Spacer(Modifier.size(8.dp))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(cs.error.copy(alpha = 0.10f))
                        .bouncyClickable(enabled = !busy, pressedScale = 0.96f) {
                            scope.launch { offline.uninstallModel() }
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        "卸载",
                        style = MaterialTheme.typography.labelLarge,
                        color = cs.error,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

/**
 * 开发环境。
 *
 * 这一块的立场是**「状态 + 兜底」，不是「操作台」**：工具由 AI 在对话里
 * 自己查、自己装、自己验，用户不需要知道 mlr 和 socat 是什么，也不该为
 * 「初始化开发环境」这种事负责 —— 那是实现细节，不是待办事项。
 *
 * 所以这里只剩三样东西：装了多少占多少、出问题时的清空按钮、以及要不要
 * 放手让 AI 装的策略开关。逐个包的「安装 / 卸载」按钮列表整个删掉了。
 *
 * 体积数字取自环境清单（安装时记的），不在这里走文件系统 —— 光 zig 一个
 * 就是两万多个文件，为了在设置页显示一个数字去 walk 它太蠢。
 */
@Composable
private fun DevEnvRow(bootstrap: com.biji.notes.sandbox.BijiBootstrap) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // 必须拿进程级单例：AI 那边的工具执行器用的是同一个实例，
    // 否则「AI 说装完了但设置页里没有」。
    val installer = remember { com.biji.notes.sandbox.ToolchainInstaller.get(ctx, bootstrap) }
    val manifest by installer.registry.state.collectAsState()
    val progress by installer.progress.collectAsState()

    var open by remember { mutableStateOf(false) }
    var free by remember { mutableStateOf(0L) }
    var busy by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }

    // usableSpace 是一次 statfs，便宜；但仍旧只在对话框开合或清单变化时问。
    LaunchedEffect(open, manifest) {
        free = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { installer.root.usableSpace }.getOrDefault(0L)
        }
    }

    val ready = manifest.tools.isNotEmpty() || bootstrap.installed
    // 不写「AI 首次使用时会自动准备」：没有任何代码在做这件事（BijiBootstrap
    // 那条自装路径已经删了）。工具是模型在对话里按需装的，副标题就照实说。
    val subtitle = if (!ready) "还没装工具"
    else "就绪 · ${manifest.commandCount.coerceAtLeast(manifest.tools.size)} 个命令 · " +
        com.biji.notes.sandbox.ToolchainInstaller.human(manifest.totalBytes)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .bouncyClickable(pressedScale = 0.99f) { open = true }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Outlined.Code,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = cs.onSurface
        )
        Spacer(Modifier.size(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "开发环境",
                style = MaterialTheme.typography.titleMedium,
                color = cs.onSurface,
                fontWeight = FontWeight.Medium
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (ready) cs.primary else cs.onSurfaceVariant
            )
        }
        Icon(
            Icons.Rounded.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = cs.onSurfaceVariant
        )
    }

    if (open) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { if (!busy) open = false },
            title = {
                Text(
                    "开发环境",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { if (!busy) open = false }) {
                    Text("好")
                }
            },
            text = {
                Column(
                    Modifier
                        .heightIn(max = 460.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    // ---- 状态条 ----
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(cs.surfaceContainerHigh)
                            .padding(12.dp)
                    ) {
                        StatCell("命令", "${manifest.commandCount}", cs.onSurface, Modifier.weight(1f))
                        StatCell(
                            "占用",
                            com.biji.notes.sandbox.ToolchainInstaller.human(manifest.totalBytes),
                            cs.onSurface, Modifier.weight(1f)
                        )
                        StatCell(
                            "剩余",
                            com.biji.notes.sandbox.ToolchainInstaller.human(free),
                            if (free in 1 until 500L * 1024 * 1024) cs.error else cs.onSurface,
                            Modifier.weight(1f)
                        )
                    }

                    // ---- 正在进行 ----
                    val running = progress
                    if (running !is com.biji.notes.sandbox.ToolchainProgress.Idle &&
                        running !is com.biji.notes.sandbox.ToolchainProgress.Done
                    ) {
                        Spacer(Modifier.size(10.dp))
                        val label = when (running) {
                            is com.biji.notes.sandbox.ToolchainProgress.Downloading ->
                                "AI 正在下载 ${running.name}" +
                                    if (running.total > 0)
                                        "  ${running.bytes * 100 / running.total}%（${com.biji.notes.sandbox.ToolchainInstaller.human(running.total)}）"
                                    else "  ${com.biji.notes.sandbox.ToolchainInstaller.human(running.bytes)}"
                            // 解包 zig 是两万多个条目、好几分钟。只写「正在解包」
                            // 的话这行字一动不动，用户分不清在干活还是卡死了。
                            // 目录里没登记安装后体积时（手工 url 装）算不出百分比，
                            // 退回报条目数 —— 它至少能证明还在动。
                            is com.biji.notes.sandbox.ToolchainProgress.Extracting ->
                                "正在解包 ${running.name}" + (
                                    running.fraction?.let { "  ${(it * 100).toInt()}%" }
                                        ?: "  ${running.entries} 个文件"
                                    )
                            is com.biji.notes.sandbox.ToolchainProgress.Verifying -> "正在校验 ${running.name}"
                            is com.biji.notes.sandbox.ToolchainProgress.Failed -> "${running.name} 安装失败：${running.message.take(80)}"
                            else -> ""
                        }
                        val failed = running is com.biji.notes.sandbox.ToolchainProgress.Failed
                        Text(
                            label,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (failed) cs.error else cs.primary
                        )
                        if (!failed) {
                            // 两个阶段都可能算得出比例，算不出的（体积未知、校验中）
                            // 才走不确定式。null 而不是 0f —— 一条停在最左边的确定式
                            // 进度条看着就是卡死了。
                            val frac: Float? = when (running) {
                                is com.biji.notes.sandbox.ToolchainProgress.Downloading ->
                                    if (running.total > 0)
                                        (running.bytes.toFloat() / running.total).coerceIn(0f, 1f)
                                    else null
                                is com.biji.notes.sandbox.ToolchainProgress.Extracting -> running.fraction
                                else -> null
                            }
                            Spacer(Modifier.size(4.dp))
                            if (frac != null) {
                                androidx.compose.material3.LinearProgressIndicator(
                                    progress = { frac },
                                    modifier = Modifier.fillMaxWidth(),
                                    color = cs.primary
                                )
                            } else {
                                androidx.compose.material3.LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = cs.primary
                                )
                            }
                        }
                    }

                    // ---- 已装工具 ----
                    Spacer(Modifier.size(14.dp))
                    if (manifest.tools.isEmpty()) {
                        Text(
                            "还没装工具",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant
                        )
                    } else {
                        var showAll by remember { mutableStateOf(false) }
                        var manage by remember { mutableStateOf(false) }
                        val sorted = manifest.tools.sortedByDescending { it.sizeBytes }
                        val shown = if (showAll) sorted else sorted.take(8)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "已装 ${sorted.size} 个",
                                style = MaterialTheme.typography.labelLarge,
                                color = cs.onSurface,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                if (manage) "完成" else "管理",
                                style = MaterialTheme.typography.labelMedium,
                                color = cs.primary,
                                modifier = Modifier.bouncyClickable(pressedScale = 0.95f) {
                                    manage = !manage
                                }
                            )
                        }
                        Spacer(Modifier.size(4.dp))
                        shown.forEach { t -> ToolLine(t, manage, busy, cs) { name ->
                            busy = true
                            scope.launch {
                                val r = installer.remove(name, purge = true)
                                toast = r.message
                                busy = false
                            }
                        } }
                        if (sorted.size > 8) {
                            Text(
                                if (showAll) "收起" else "展开全部（${sorted.size}）",
                                style = MaterialTheme.typography.labelMedium,
                                color = cs.primary,
                                modifier = Modifier
                                    .padding(top = 4.dp)
                                    .bouncyClickable(pressedScale = 0.96f) { showAll = !showAll }
                            )
                        }
                    }

                    // ---- 策略 ----
                    Spacer(Modifier.size(16.dp))
                    HorizontalDivider(thickness = 0.5.dp, color = cs.outlineVariant)
                    Spacer(Modifier.size(8.dp))
                    val policy = manifest.policy
                    // setPolicy 会写一次账本文件（几 KB），扇到 IO 上做，
                    // 别让开关的动画去等一次 write。
                    MiniToggle("允许 AI 自动装工具", null, policy.autoInstall) { v ->
                        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            installer.setPolicy(policy.copy(autoInstall = v))
                        }
                    }
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "磁盘预算",
                        style = MaterialTheme.typography.labelLarge,
                        color = cs.onSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.size(4.dp))
                    Row {
                        listOf(
                            "512 MB" to 512L * 1024 * 1024,
                            "2 GB" to 2L * 1024 * 1024 * 1024,
                            "不限" to com.biji.notes.sandbox.ToolchainPolicy.UNLIMITED
                        ).forEach { (label, value) ->
                            val on = policy.budgetBytes == value
                            Text(
                                label,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (on) cs.primary else cs.onSurfaceVariant,
                                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier
                                    .padding(end = 8.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(
                                        if (on) cs.primary.copy(alpha = 0.14f) else cs.surfaceContainerHigh
                                    )
                                    .bouncyClickable(pressedScale = 0.95f) {
                                        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                            installer.setPolicy(policy.copy(budgetBytes = value))
                                        }
                                    }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }

                    // ---- 兜底动作 ----
                    Spacer(Modifier.size(16.dp))
                    HorizontalDivider(thickness = 0.5.dp, color = cs.outlineVariant)
                    Spacer(Modifier.size(8.dp))
                    var confirmWipe by remember { mutableStateOf(false) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (confirmWipe) "确定清空？" else "全部清空并重建",
                            style = MaterialTheme.typography.labelLarge,
                            color = cs.error,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.bouncyClickable(enabled = !busy, pressedScale = 0.96f) {
                                if (!confirmWipe) confirmWipe = true
                                else {
                                    busy = true
                                    scope.launch {
                                        val r = installer.wipe()
                                        toast = r.message
                                        confirmWipe = false
                                        busy = false
                                    }
                                }
                            }
                        )
                    }

                    // ---- 高级 ----
                    Spacer(Modifier.size(12.dp))
                    var advanced by remember { mutableStateOf(false) }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .bouncyClickable(pressedScale = 0.99f) { advanced = !advanced }
                    ) {
                        Text(
                            "高级",
                            style = MaterialTheme.typography.labelLarge,
                            color = cs.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            Icons.Rounded.ChevronRight,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp).rotate(if (advanced) 90f else 0f),
                            tint = cs.onSurfaceVariant
                        )
                    }
                    if (advanced) {
                        Spacer(Modifier.size(6.dp))
                        Text(
                            "bin  ${installer.binDir.absolutePath}\nopt  ${installer.optDir.absolutePath}",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            ),
                            color = cs.onSurfaceVariant
                        )
                        Spacer(Modifier.size(10.dp))
                        ManualInstall(installer, busy, { busy = it }) { toast = it }
                    }

                    toast?.let {
                        Spacer(Modifier.size(10.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.onSurfaceVariant
                        )
                    }
                }
            }
        )
    }
}

@Composable
private fun StatCell(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            color = color,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 一行只读的已装工具。默认没有按钮 —— 点「管理」才露出删除。 */
@Composable
private fun ToolLine(
    t: com.biji.notes.sandbox.InstalledTool,
    manage: Boolean,
    busy: Boolean,
    cs: androidx.compose.material3.ColorScheme,
    onRemove: (String) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            t.name,
            style = MaterialTheme.typography.labelLarge.copy(
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
            ),
            color = if (t.smokeOk) cs.onSurface else cs.error,
            modifier = Modifier.weight(1f)
        )
        Text(
            com.biji.notes.sandbox.ToolchainInstaller.human(t.sizeBytes),
            style = MaterialTheme.typography.labelSmall,
            color = cs.onSurfaceVariant
        )
        if (manage) {
            Spacer(Modifier.size(10.dp))
            Text(
                "删除",
                style = MaterialTheme.typography.labelSmall,
                color = cs.error,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.bouncyClickable(enabled = !busy, pressedScale = 0.95f) {
                    onRemove(t.name)
                }
            )
        }
    }
}

@Composable
private fun MiniToggle(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .bouncyClickable(pressedScale = 0.99f) { onChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = cs.onSurface)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** 手动直链安装。主路径是让 AI 装，这里只是高级区里的一个逃生口 ——
 *  走的是和 AI 完全相同的那条安装流水线（校验 + 验活 + 记账）。 */
@Composable
private fun ManualInstall(
    installer: com.biji.notes.sandbox.ToolchainInstaller,
    busy: Boolean,
    setBusy: (Boolean) -> Unit,
    onResult: (String) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth()) {
        Text(
            "aarch64 静态二进制直链",
            style = MaterialTheme.typography.labelSmall,
            color = cs.onSurfaceVariant
        )
        Spacer(Modifier.size(6.dp))
        androidx.compose.material3.OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("命令名，如 rg") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.size(6.dp))
        androidx.compose.material3.OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("下载直链 https://…") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.size(8.dp))
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(cs.primary.copy(alpha = 0.14f))
                .bouncyClickable(
                    enabled = !busy && name.isNotBlank() && url.startsWith("https://"),
                    pressedScale = 0.95f
                ) {
                    setBusy(true)
                    scope.launch {
                        val r = installer.install(
                            com.biji.notes.sandbox.InstallRequest(
                                url = url.trim(),
                                binName = name.trim(),
                                installedBy = "user",
                                note = "设置页手动安装"
                            )
                        )
                        onResult(r.message)
                        if (r.ok) { name = ""; url = "" }
                        setBusy(false)
                    }
                }
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp), color = cs.primary, strokeWidth = 2.dp
                )
            } else {
                Text(
                    "下载安装",
                    style = MaterialTheme.typography.labelLarge,
                    color = cs.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

/** Tap to open a dialog showing the captured crash log + a clear
 *  button. Reads via [CrashHandler.readCrashes]. Empty → "暂无". */
@Composable
private fun CrashLogRow() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val cs = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    var log by remember { mutableStateOf("") }
    androidx.compose.runtime.LaunchedEffect(open) {
        if (open) log = com.biji.notes.CrashHandler.readCrashes(ctx)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .bouncyClickable(pressedScale = 0.99f) { open = true }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            androidx.compose.material.icons.Icons.Outlined.BugReport,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = cs.onSurface
        )
        Spacer(Modifier.size(16.dp))
        Text(
            "崩溃日志",
            style = MaterialTheme.typography.titleMedium,
            color = cs.onSurface,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        Icon(
            Icons.Rounded.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = cs.onSurfaceVariant
        )
    }
    if (open) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    com.biji.notes.CrashHandler.clearCrashes(ctx)
                    log = ""
                }) { Text("清空") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { open = false }) {
                    Text("关闭")
                }
            },
            title = { Text("崩溃日志") },
            text = {
                if (log.isBlank()) {
                    Text("暂无", color = cs.onSurfaceVariant)
                } else {
                    Box(
                        Modifier
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            log,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            ),
                            color = cs.onSurface
                        )
                    }
                }
            }
        )
    }
}

@Composable
private fun BalanceRow(state: BalanceState, onRefresh: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val info = state.info
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Outlined.AccountBalanceWallet,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = cs.onSurface
        )
        Spacer(Modifier.size(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "余额",
                style = MaterialTheme.typography.titleMedium,
                color = cs.onSurface,
                fontWeight = FontWeight.Medium
            )
            Text(
                when {
                    state.error != null -> "刷新失败：${state.error}"
                    info != null && !info.isAvailable -> "账户暂不可用"
                    info != null ->
                        "${info.totalBalance} ${info.currency} · 赠 ${info.grantedBalance} / 充 ${info.toppedUpBalance}"
                    else -> "尚未拉取"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.error != null) cs.error else cs.onSurfaceVariant
            )
        }
        IconAction(icon = Icons.Rounded.Refresh, onClick = onRefresh)
        if (state.loading) {
            Spacer(Modifier.size(4.dp))
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = cs.primary
            )
        }
    }
}

@Composable
private fun ModelListInline(
    current: String,
    list: List<String>,
    loading: Boolean,
    error: String?,
    onPick: (String) -> Unit,
    onRefresh: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val all = (listOf(MODEL_CHAT, MODEL_REASONER) + list).distinct()
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "选择模型",
                style = MaterialTheme.typography.labelLarge,
                color = cs.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .bouncyClickable(onClick = onRefresh)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 1.5.dp,
                            color = cs.primary
                        )
                    } else {
                        Icon(
                            Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                            tint = cs.primary
                        )
                    }
                    Spacer(Modifier.size(4.dp))
                    Text(
                        "拉取",
                        style = MaterialTheme.typography.labelLarge,
                        color = cs.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
        Spacer(Modifier.size(6.dp))
        all.forEach { id ->
            val selected = id == current
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .bouncyClickable(pressedScale = 0.98f) { onPick(id) }
                    .padding(horizontal = 4.dp, vertical = 10.dp),
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
            Spacer(Modifier.size(4.dp))
            Text("拉取失败：$it", style = MaterialTheme.typography.labelLarge, color = cs.error)
        }
    }
}
