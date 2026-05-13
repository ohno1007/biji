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
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
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

// Each row is its own rounded card; the cream background "镂空" between cards
// acts as the divider — no hairlines, no internal separators.
private val RowShape = RoundedCornerShape(20.dp)
private val RowGap = 10.dp
private val SectionGap = 26.dp
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

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(
                        "设置",
                        style = MaterialTheme.typography.displayLarge.copy(fontWeight = FontWeight.Bold)
                    )
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = SectionEdge,
                end = SectionEdge,
                top = inner.calculateTopPadding(),
                bottom = inner.calculateBottomPadding() +
                    contentPadding.calculateBottomPadding() + 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(RowGap)
        ) {

            // ===== 接入 ============================================
            sectionHeader("DEEPSEEK 接入")
            item {
                CardRow(
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
            }
            item {
                CardRow(icon = Icons.Outlined.Cloud, title = "Base URL") {
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
            item { Spacer(Modifier.size(SectionGap - RowGap)) }

            // ===== 账户 ============================================
            sectionHeader("账户")
            item { BalanceCard(state = balance, onRefresh = vm::refreshBalance) }
            item { Spacer(Modifier.size(SectionGap - RowGap)) }

            // ===== 模型 ============================================
            sectionHeader("模型")
            item {
                CardNavRow(
                    icon = Icons.Outlined.AutoAwesome,
                    title = "模型",
                    subtitle = settings.model,
                    onClick = { showModels = !showModels }
                )
            }
            item {
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
                    ModelListCard(
                        current = settings.model,
                        list = models.list.map { it.id },
                        loading = models.loading,
                        error = models.error,
                        onPick = { vm.setModel(it) },
                        onRefresh = vm::refreshModels
                    )
                }
            }
            item {
                SliderCardRow(
                    icon = Icons.Outlined.Thermostat,
                    title = "Temperature",
                    value = temperature,
                    onChange = { temperature = it }
                )
            }
            item {
                ToggleCardRow(
                    icon = Icons.Outlined.AutoAwesome,
                    title = "快捷切到 Reasoner",
                    subtitle = "等同于把模型设为 deepseek-reasoner",
                    checked = settings.thinking,
                    onChange = vm::setThinking
                )
            }
            item { Spacer(Modifier.size(SectionGap - RowGap)) }

            // ===== 工具与记忆 ======================================
            sectionHeader("工具 / 记忆")
            item {
                ToggleCardRow(
                    icon = Icons.Outlined.Search,
                    title = "联网搜索",
                    subtitle = "允许模型调用 web_search / read_url 工具",
                    checked = settings.webSearch,
                    onChange = vm::setWebSearch
                )
            }
            item {
                ToggleCardRow(
                    icon = Icons.Outlined.Memory,
                    title = "长期记忆",
                    subtitle = "用 BM25 在过去对话里检索相关片段注入上下文",
                    checked = settings.longMemory,
                    onChange = vm::setLongMemory
                )
            }
            item {
                ToggleCardRow(
                    icon = Icons.Outlined.Notifications,
                    title = "完成通知",
                    subtitle = "AI 回答完成时在后台时弹通知",
                    checked = settings.notify,
                    onChange = vm::setNotify
                )
            }
            item { Spacer(Modifier.size(SectionGap - RowGap)) }

            // ===== 系统提示词 ======================================
            sectionHeader("系统提示词")
            item {
                CardRow(
                    icon = Icons.Outlined.Tune,
                    title = "Prompt",
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
            item { Spacer(Modifier.size(SectionGap - RowGap)) }

            // ===== 关于 ============================================
            sectionHeader("关于")
            item {
                CardRow(
                    icon = Icons.Outlined.Info,
                    title = "Biji",
                    subtitle = "1.2 · DeepSeek 流式聊天"
                ) {}
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.sectionHeader(label: String) {
    item {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 2.dp)
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium.copy(
                    letterSpacing = androidx.compose.ui.unit.TextUnit(
                        1.5f,
                        androidx.compose.ui.unit.TextUnitType.Sp
                    )
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

// =====================================================================
// Card primitives (one self-contained card per row)
// =====================================================================

@Composable
private fun CardSurface(
    onClick: (() -> Unit)? = null,
    minHeight: Dp = 64.dp,
    content: @Composable () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val clickable = if (onClick != null) Modifier.bouncyClickable(pressedScale = 0.985f) { onClick() }
    else Modifier
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RowShape)
            .background(cs.surfaceContainer)
            .then(clickable)
            .heightIn(min = minHeight)
    ) {
        content()
    }
}

@Composable
private fun CardRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    minHeight: Dp = 64.dp,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
    editor: @Composable () -> Unit = {}
) {
    val cs = MaterialTheme.colorScheme
    CardSurface(onClick = onClick, minHeight = minHeight) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
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
}

@Composable
private fun CardNavRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit
) {
    CardRow(
        icon = icon,
        title = title,
        subtitle = subtitle,
        onClick = onClick
    ) {
        Icon(
            Icons.Rounded.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ToggleCardRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    CardRow(icon = icon, title = title, subtitle = subtitle) {
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
private fun SliderCardRow(
    icon: ImageVector,
    title: String,
    value: Float,
    onChange: (Float) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    CardSurface(minHeight = 74.dp) {
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

@Composable
private fun BalanceCard(state: BalanceState, onRefresh: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val info = state.info
    CardSurface(minHeight = 70.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
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
}

@Composable
private fun ModelListCard(
    current: String,
    list: List<String>,
    loading: Boolean,
    error: String?,
    onPick: (String) -> Unit,
    onRefresh: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val all = (listOf(MODEL_CHAT, MODEL_REASONER) + list).distinct()
    CardSurface(minHeight = 0.dp) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
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
}
