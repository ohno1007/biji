package com.biji.notes.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.biji.notes.data.MODEL_CHAT
import com.biji.notes.data.MODEL_REASONER
import com.biji.notes.ui.chat.ChatViewModel
import com.biji.notes.ui.glass.bouncyPress
import com.biji.notes.ui.glass.cardContainerColor
import com.biji.notes.ui.glass.cornerRadius
import com.biji.notes.ui.glass.mdSurface

@Composable
fun SettingsScreen(vm: ChatViewModel) {
    val settings by vm.settings.collectAsState()
    val models by vm.models.collectAsState()
    val balance by vm.balance.collectAsState()

    var apiKey by remember(settings.apiKey) { mutableStateOf(settings.apiKey) }
    var baseUrl by remember(settings.baseUrl) { mutableStateOf(settings.baseUrl) }
    var systemPrompt by remember(settings.systemPrompt) { mutableStateOf(settings.systemPrompt) }
    var temperature by remember(settings.temperature) { mutableStateOf(settings.temperature) }
    var showKey by remember { mutableStateOf(false) }

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

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = 0.dp, bottom = 140.dp
        ),
        // Gap = divider. No drawn lines anywhere.
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Spacer(Modifier.statusBarsPadding().height(8.dp))
            Text(
                "设置",
                style = MaterialTheme.typography.displayLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(top = 16.dp, bottom = 6.dp)
            )
        }

        item {
            Section(title = "DeepSeek 接入", icon = Icons.Rounded.Key) {
                FieldRow("API Key") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            if (apiKey.isEmpty()) {
                                Text(
                                    "sk-...",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            BasicTextField(
                                value = apiKey,
                                onValueChange = { apiKey = it },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyLarge.copy(
                                    color = MaterialTheme.colorScheme.onSurface
                                ),
                                visualTransformation = if (showKey) VisualTransformation.None
                                else PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Spacer(Modifier.size(8.dp))
                        IconChip(
                            icon = if (showKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            onClick = { showKey = !showKey }
                        )
                    }
                }
                GapDivider()
                FieldRow("Base URL") {
                    BasicTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        item {
            Section(title = "余额", icon = Icons.Rounded.AccountBalanceWallet) {
                BalanceBlock(
                    state = balance,
                    onRefresh = vm::refreshBalance
                )
            }
        }

        item {
            Section(title = "模型", icon = Icons.Rounded.AutoAwesome) {
                ModelPickerHeader(
                    selected = settings.model,
                    loading = models.loading,
                    onRefresh = vm::refreshModels
                )
                Spacer(Modifier.height(8.dp))

                // Quick picks always present so users without /v1/models access
                // can still operate the app.
                val quick = listOf(MODEL_CHAT, MODEL_REASONER)
                val remote = models.list.map { it.id }
                val all = (quick + remote).distinct()

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    all.forEach { id ->
                        ModelRow(
                            id = id,
                            selected = id == settings.model,
                            onClick = { vm.setModel(id) }
                        )
                    }
                }
                models.error?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "拉取模型列表失败：$it",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                GapDivider()
                FieldRow("Temperature ${"%.2f".format(temperature)}") {
                    Slider(
                        value = temperature,
                        onValueChange = { temperature = it },
                        valueRange = 0f..1.5f,
                        steps = 14,
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        )
                    )
                }
                GapDivider()
                ToggleRow(
                    title = "快捷切到 Reasoner",
                    subtitle = "等同于把模型设为 deepseek-reasoner",
                    checked = settings.thinking,
                    onChange = vm::setThinking
                )
            }
        }

        item {
            Section(title = "系统提示词", icon = Icons.Rounded.Tune) {
                Box(Modifier.fillMaxWidth()) {
                    if (systemPrompt.isEmpty()) {
                        Text(
                            "可选：让模型扮演特定角色或遵循特定风格",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    BasicTextField(
                        value = systemPrompt,
                        onValueChange = { systemPrompt = it },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        item {
            Section(title = "关于", icon = Icons.Rounded.Language) {
                Text(
                    "Biji · 1.1\n" +
                        "DeepSeek 流式聊天 · OpenAI 兼容\n" +
                        "/v1/chat/completions · /v1/models · /user/balance\n" +
                        "Key 仅保存在本机 DataStore。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun Section(
    title: String,
    icon: ImageVector,
    content: @Composable () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .mdSurface(cardContainerColor(1), RoundedCornerShape(cornerRadius()))
            .padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(28.dp)
                    .mdSurface(cs.primary, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = cs.onPrimary)
            }
            Spacer(Modifier.size(10.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = cs.onSurface
            )
        }
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun FieldRow(label: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@Composable
private fun GapDivider() {
    // "Seamless cutout divider": just background-coloured negative space.
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = cs.onSurface, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = cs.onPrimary,
                checkedTrackColor = cs.primary,
                uncheckedThumbColor = cs.onSurface,
                uncheckedTrackColor = cs.surfaceContainerHighest,
                checkedBorderColor = Color.Transparent,
                uncheckedBorderColor = Color.Transparent
            )
        )
    }
}

@Composable
private fun IconChip(icon: ImageVector, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(34.dp)
            .mdSurface(cs.surfaceContainerHigh, CircleShape)
            .bouncyPress(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(15.dp), tint = cs.onSurface)
    }
}

@Composable
private fun ModelPickerHeader(
    selected: String,
    loading: Boolean,
    onRefresh: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                "当前模型",
                style = MaterialTheme.typography.labelLarge,
                color = cs.onSurfaceVariant
            )
            Text(
                selected,
                style = MaterialTheme.typography.bodyLarge,
                color = cs.onSurface,
                fontWeight = FontWeight.SemiBold
            )
        }
        Box(
            Modifier
                .mdSurface(cs.surfaceContainerHigh, RoundedCornerShape(50))
                .bouncyPress(onClick = onRefresh)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = cs.onSurface
                    )
                } else {
                    Icon(
                        Icons.Rounded.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = cs.onSurface
                    )
                }
                Spacer(Modifier.size(6.dp))
                Text("拉取模型", style = MaterialTheme.typography.labelLarge, color = cs.onSurface, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun ModelRow(
    id: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val bg = if (selected) cs.primary else cs.surfaceContainerHigh
    val fg = if (selected) cs.onPrimary else cs.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .mdSurface(bg, RoundedCornerShape(14.dp))
            .bouncyPress(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            id,
            style = MaterialTheme.typography.bodyLarge,
            color = fg,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp), tint = fg)
        }
    }
}

@Composable
private fun BalanceBlock(
    state: com.biji.notes.ui.chat.BalanceState,
    onRefresh: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val info = state.info
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (info != null) "${info.totalBalance} ${info.currency}" else "—",
                    style = MaterialTheme.typography.headlineMedium,
                    color = cs.onSurface,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    when {
                        state.error != null -> "刷新失败：${state.error}"
                        info != null && !info.isAvailable -> "账户暂不可用"
                        info != null -> "可用余额"
                        else -> "尚未拉取"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (state.error != null) cs.error else cs.onSurfaceVariant
                )
            }
            Box(
                Modifier
                    .mdSurface(cs.primary, RoundedCornerShape(50))
                    .bouncyPress(onClick = onRefresh)
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = cs.onPrimary
                        )
                    } else {
                        Icon(
                            Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = cs.onPrimary
                        )
                    }
                    Spacer(Modifier.size(6.dp))
                    Text("查余额", style = MaterialTheme.typography.labelLarge, color = cs.onPrimary, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        if (info != null) {
            Spacer(Modifier.height(10.dp))
            Row {
                BalanceCell("赠送", "${info.grantedBalance} ${info.currency}", Modifier.weight(1f))
                Spacer(Modifier.size(8.dp))
                BalanceCell("充值", "${info.toppedUpBalance} ${info.currency}", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun BalanceCell(label: String, value: String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier
            .mdSurface(cs.surfaceContainerHigh, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(2.dp))
        Text(value, style = MaterialTheme.typography.titleMedium, color = cs.onSurface, fontWeight = FontWeight.SemiBold)
    }
}
