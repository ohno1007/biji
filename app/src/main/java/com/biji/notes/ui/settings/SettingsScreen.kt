package com.biji.notes.ui.settings

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
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
import com.biji.notes.ui.chat.ChatViewModel
import com.biji.notes.ui.glass.LiquidGlassState
import com.biji.notes.ui.glass.bouncyPress
import com.biji.notes.ui.glass.liquidGlass

@Composable
fun SettingsScreen(
    vm: ChatViewModel,
    glass: LiquidGlassState?
) {
    val settings by vm.settings.collectAsState()

    var apiKey by remember(settings.apiKey) { mutableStateOf(settings.apiKey) }
    var baseUrl by remember(settings.baseUrl) { mutableStateOf(settings.baseUrl) }
    var systemPrompt by remember(settings.systemPrompt) { mutableStateOf(settings.systemPrompt) }
    var temperature by remember(settings.temperature) { mutableStateOf(settings.temperature) }
    var showKey by remember { mutableStateOf(false) }

    // Persist edits on debounce-like cycles via LaunchedEffect.
    LaunchedEffect(apiKey) { if (apiKey != settings.apiKey) vm.setApiKey(apiKey) }
    LaunchedEffect(baseUrl) { if (baseUrl != settings.baseUrl) vm.setBaseUrl(baseUrl) }
    LaunchedEffect(systemPrompt) {
        if (systemPrompt != settings.systemPrompt) vm.setSystemPrompt(systemPrompt)
    }
    LaunchedEffect(temperature) {
        if (kotlin.math.abs(temperature - settings.temperature) > 0.001f)
            vm.setTemperature(temperature)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = 0.dp, bottom = 160.dp
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Spacer(Modifier.statusBarsPadding().height(8.dp))
            Text(
                "设置",
                style = MaterialTheme.typography.displayLarge.copy(fontWeight = FontWeight.Bold),
                color = LocalContentColor.current,
                modifier = Modifier.padding(top = 16.dp, bottom = 14.dp)
            )
        }

        item {
            GlassSection(glass = glass, title = "DeepSeek 接入", icon = Icons.Rounded.Key) {
                LabeledRow("API Key") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            if (apiKey.isEmpty()) {
                                Text(
                                    "sk-...",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = LocalContentColor.current.copy(alpha = 0.35f)
                                )
                            }
                            BasicTextField(
                                value = apiKey,
                                onValueChange = { apiKey = it },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyLarge.copy(
                                    color = LocalContentColor.current
                                ),
                                visualTransformation = if (showKey) VisualTransformation.None
                                else PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Spacer(Modifier.size(8.dp))
                        Box(
                            Modifier
                                .size(34.dp)
                                .liquidGlass(
                                    glass,
                                    shape = CircleShape,
                                    cornerRadius = 34.dp,
                                    blurRadius = 18.dp,
                                    tint = Color.White.copy(alpha = 0.15f)
                                )
                                .bouncyPress(onClick = { showKey = !showKey }),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                if (showKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = if (showKey) "隐藏" else "显示",
                                modifier = Modifier.size(15.dp),
                                tint = LocalContentColor.current
                            )
                        }
                    }
                }
                Divider()
                LabeledRow("Base URL") {
                    BasicTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = LocalContentColor.current
                        ),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        item {
            GlassSection(glass = glass, title = "模型", icon = Icons.Rounded.AutoAwesome) {
                ToggleRow(
                    title = "思考（Reasoner）",
                    subtitle = "切到 deepseek-reasoner，展示完整推理过程",
                    checked = settings.thinking,
                    onChange = vm::setThinking
                )
                Divider()
                LabeledRow("Temperature ${"%.2f".format(temperature)}") {
                    Slider(
                        value = temperature,
                        onValueChange = { temperature = it },
                        valueRange = 0f..1.5f,
                        steps = 14,
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = Color.White.copy(alpha = 0.25f)
                        )
                    )
                }
            }
        }

        item {
            GlassSection(glass = glass, title = "系统提示词", icon = Icons.Rounded.Tune) {
                Box(Modifier.fillMaxWidth()) {
                    if (systemPrompt.isEmpty()) {
                        Text(
                            "可选：让模型扮演特定角色或遵循特定风格",
                            style = MaterialTheme.typography.bodyMedium,
                            color = LocalContentColor.current.copy(alpha = 0.40f)
                        )
                    }
                    BasicTextField(
                        value = systemPrompt,
                        onValueChange = { systemPrompt = it },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = LocalContentColor.current
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        item {
            GlassSection(glass = glass, title = "关于", icon = Icons.Rounded.Language) {
                Text(
                    "Biji · 1.0\n" +
                        "运行时液态玻璃 · DeepSeek 流式聊天\n" +
                        "Key 仅保存在本机的 DataStore 中。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalContentColor.current.copy(alpha = 0.75f)
                )
            }
        }
    }
}

@Composable
private fun GlassSection(
    glass: LiquidGlassState?,
    title: String,
    icon: ImageVector,
    content: @Composable () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .liquidGlass(
                glass,
                shape = RoundedCornerShape(24.dp),
                cornerRadius = 24.dp,
                blurRadius = 38.dp
            )
            .padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(28.dp)
                    .liquidGlass(
                        glass,
                        shape = CircleShape,
                        cornerRadius = 28.dp,
                        blurRadius = 14.dp,
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.White)
            }
            Spacer(Modifier.size(10.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = LocalContentColor.current
            )
        }
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun LabeledRow(label: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = LocalContentColor.current.copy(alpha = 0.65f)
        )
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = LocalContentColor.current)
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.labelLarge,
                color = LocalContentColor.current.copy(alpha = 0.60f)
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = Color.White.copy(alpha = 0.25f),
                checkedBorderColor = Color.Transparent,
                uncheckedBorderColor = Color.Transparent
            )
        )
    }
}

@Composable
private fun Divider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .height(1.dp)
            .background(Color.White.copy(alpha = 0.18f))
    )
}
