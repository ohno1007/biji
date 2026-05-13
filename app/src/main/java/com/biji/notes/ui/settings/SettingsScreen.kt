package com.biji.notes.ui.settings

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
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.unit.dp
import com.biji.notes.data.MODEL_CHAT
import com.biji.notes.data.MODEL_REASONER
import com.biji.notes.ui.chat.BalanceState
import com.biji.notes.ui.chat.ChatViewModel
import com.biji.notes.ui.glass.bouncyClickable
import com.biji.notes.ui.glass.mdSurface
import androidx.compose.foundation.background

private val GroupShape = RoundedCornerShape(20.dp)

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
                start = 16.dp,
                end = 16.dp,
                top = inner.calculateTopPadding(),
                bottom = inner.calculateBottomPadding() +
                    contentPadding.calculateBottomPadding() + 24.dp
            ),
            // Gap between groups = the "镂空" divider.
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {

            // ---- Group: DeepSeek 接入 -------------------------------------
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
                    KeyValueRow(
                        icon = Icons.Outlined.Cloud,
                        title = "Base URL"
                    ) {
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

            // ---- Group: 余额 ----------------------------------------------
            item {
                Group { BalanceRow(state = balance, onRefresh = vm::refreshBalance) }
            }

            // ---- Group: 模型 ----------------------------------------------
            item {
                Group {
                    NavRow(
                        icon = Icons.Outlined.AutoAwesome,
                        title = "模型",
                        subtitle = settings.model,
                        onClick = { showModels = !showModels }
                    )
                    if (showModels) {
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
                        title = "快捷切到 Reasoner",
                        subtitle = "等同于把模型设为 deepseek-reasoner",
                        checked = settings.thinking,
                        onChange = vm::setThinking
                    )
                }
            }

            // ---- Group: 系统提示词 ----------------------------------------
            item {
                Group {
                    KeyValueRow(
                        icon = Icons.Outlined.Tune,
                        title = "系统提示词",
                        minHeight = 56.dp
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

            // ---- Group: 关于 ----------------------------------------------
            item {
                Group {
                    KeyValueRow(
                        icon = Icons.Outlined.Info,
                        title = "关于",
                        subtitle = "Biji · 1.2 · DeepSeek 流式聊天"
                    ) {}
                }
            }
        }
    }
}

// =====================================================================
// Building blocks
// =====================================================================

@Composable
private fun Group(content: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .clip(GroupShape)
            .background(cs.surfaceContainer)
    ) { content() }
}

/**
 * Inset hairline divider, exactly matching the iOS look in image 1 –
 * starts past the leading icon column, never touches the rounded edges of
 * the group card.
 */
@Composable
private fun InsetDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 60.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

/**
 * Standard row: leading icon + title (+ subtitle) + optional trailing slot
 * (e.g. switch / chevron / inline editor).
 */
@Composable
private fun BaseRow(
    icon: ImageVector?,
    title: String,
    subtitle: String? = null,
    minHeight: androidx.compose.ui.unit.Dp = 60.dp,
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
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = cs.onSurface
            )
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

/** Key-value: title above, inline editor / value spread across the row. */
@Composable
private fun KeyValueRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    trailing: @Composable () -> Unit = {},
    minHeight: androidx.compose.ui.unit.Dp = 60.dp,
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
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = cs.onSurface
        )
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
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    BaseRow(icon = icon, title = title, subtitle = subtitle) {
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
        IconAction(
            icon = if (state.loading) Icons.Rounded.Refresh else Icons.Rounded.Refresh,
            onClick = onRefresh
        )
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
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
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
