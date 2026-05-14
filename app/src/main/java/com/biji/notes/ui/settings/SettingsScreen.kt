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
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
                            DevEnvRow(runShell = { cmd ->
                                val r = vm.sandbox.runShell(
                                    folder = null,
                                    command = cmd,
                                    asRoot = settings.useRoot
                                )
                                r.exitCode to r.stdout
                            })
                        }
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
                        true -> "su 可用 · 命令将通过 su -c 执行"
                        false -> "su 不可用或被拒绝"
                        null -> if (enabled) "命令将通过 su -c 执行"
                        else "命令以普通进程执行"
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

/**
 * "一键初始化开发环境" — with the Root 模式 toggle on, the dialog
 * adds an "扫描工具链" button that runs `which <tool>` for the common
 * native build chain and reports which binaries are present on PATH.
 * With root off we still surface the Termux suggestion. The dialog
 * also exposes a button to launch Termux directly.
 *
 * [runShell] is the host's shell entry point — when null, the dialog
 * hides the probe button. The lambda returns (exitCode, stdout) so
 * the row can detect "exit 0 + non-empty path" as "tool installed".
 */
@Composable
private fun DevEnvRow(runShell: (suspend (String) -> Pair<Int, String>)? = null) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var probing by remember { mutableStateOf(false) }
    // (tool, present, path-or-stderr) — surfaces inline after a probe.
    var probeReport by remember { mutableStateOf<List<Triple<String, Boolean, String>>>(emptyList()) }
    val tools = listOf("gcc", "clang", "cmake", "make", "git", "python", "python3", "node")
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
                "初始化开发环境",
                style = MaterialTheme.typography.titleMedium,
                color = cs.onSurface,
                fontWeight = FontWeight.Medium
            )
            Text(
                "了解 Android 上 cmake / ndk / gcc 的安装方式",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant
            )
        }
    }
    if (open) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { open = false },
            title = {
                Text(
                    "开发工具链",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Column {
                    Text(
                        "biji 跑在 Android 沙箱里，无法直接 apt install cmake / ndk / gcc，也不能在应用进程内编译 native 代码。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "推荐流程：",
                        style = MaterialTheme.typography.labelLarge,
                        color = cs.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "1. 安装 Termux（F-Droid 版本最稳）。\n" +
                            "2. 在 Termux 里执行：\n" +
                            "   pkg update && pkg install build-essential cmake clang make git python\n" +
                            "3. 回到 biji，工具链命令通过 sh -c 走 PATH 即可调用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                    if (runShell != null) {
                        Spacer(Modifier.size(10.dp))
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(cs.primary.copy(alpha = 0.14f))
                                .bouncyClickable(enabled = !probing, pressedScale = 0.96f) {
                                    probing = true
                                    probeReport = emptyList()
                                    scope.launch {
                                        val results = tools.map { tool ->
                                            val (exit, out) = runCatching {
                                                runShell("which $tool 2>/dev/null || command -v $tool 2>/dev/null")
                                            }.getOrDefault(1 to "")
                                            val path = out.trim().lineSequence().firstOrNull().orEmpty()
                                            Triple(tool, exit == 0 && path.isNotBlank(), path)
                                        }
                                        probeReport = results
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
                                    "扫描中…",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = cs.primary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            } else {
                                Text(
                                    "扫描工具链（需 Root 模式 / Termux PATH）",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = cs.primary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                    if (probeReport.isNotEmpty()) {
                        Spacer(Modifier.size(8.dp))
                        probeReport.forEach { (tool, present, path) ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(vertical = 2.dp)
                            ) {
                                Text(
                                    if (present) "✓" else "✗",
                                    color = if (present) cs.primary else cs.error,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.labelLarge
                                )
                                Spacer(Modifier.size(8.dp))
                                Text(
                                    tool,
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                    ),
                                    color = cs.onSurface,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(Modifier.size(8.dp))
                                Text(
                                    if (present) path else "未找到",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                    ),
                                    color = cs.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    val pkg = "com.termux"
                    val launch = ctx.packageManager.getLaunchIntentForPackage(pkg)
                    if (launch != null) {
                        ctx.startActivity(launch)
                    } else {
                        val intent = android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://f-droid.org/packages/com.termux/")
                        )
                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { ctx.startActivity(intent) }
                    }
                    open = false
                }) {
                    Text("打开 Termux")
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { open = false }) { Text("关闭") }
            }
        )
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
