package com.biji.notes.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.Text
import com.biji.notes.terminal.TerminalEmulator

// =====================================================================
//  额外按键行（Termux 的 extra keys）。
//
//  ## 粘滞修饰键的状态机 —— 这是整个交互的核心
//  手机上没有第二只手按住 Ctrl，所以 CTRL/ALT 必须是**粘滞**的：
//
//    单击   → active 取反；关掉时顺带解锁
//    双击   → 锁定（连按 c、v、d 时不用每次都点一遍 CTRL）
//    长按   → 锁定 / 解锁（Termux 的原生语义，老用户的肌肉记忆）
//    取用   → active && !locked 时**用完即失效**
//
//  两个必须踩过才知道的坑：
//
//  1. **不能用 `detectTapGestures(onDoubleTap = ...)` 实现双击**。它为了分辨
//     单击 / 双击，会把单击的回调推迟一个 doubleTapTimeout（约 300 ms）——
//     每敲一个键都延迟 300 ms 才有反应，整排按键会「粘手」。这里的做法是：
//     第一次点立刻生效（active = true），如果 300 ms 内又来一次，就把它
//     **升级**成锁定，而不是按常规逻辑取消。用户拿到的是即时反馈 + 双击锁定。
//
//  2. **长按之后必须吞掉抬手时的那次 click**。Termux 用 `mLongPressCount > 0`
//     做这件事；不吞的话，长按刚锁上，手一松 click 又把它解锁了，锁定
//     永远生效不了 —— 这是复刻 Termux 时最容易漏的一条。
//
//  ## 明确不做
//   * **FN 键**：Termux 用它做 FN+q 收工具栏、FN+1..0 发 F1-F10。我们的
//     F1-F12 走硬件键盘和菜单，为一个手机上没人用的组合再加一个粘滞状态
//     不划算。
//   * **extra-keys 的 JSON 自定义 / 宏（`{macro:'CTRL f d'}`）**：等这套
//     交互稳定之后再说，现在写死成 Termux 的默认两行。
//   * **PopupWindow 式的上滑弹窗**：上滑**动作**保留（`-` 上滑得到 `|`），
//     但不弹窗口，改成在按键右上角常驻一个小字提示。手机上那个弹窗本来
//     就常被手指挡住。
// =====================================================================

/**
 * 一个粘滞修饰键。
 *
 * 只有 CTRL / ALT / SHIFT 三个（Termux 是四个，多一个 FN，见上）。
 */
@Stable
class StickyModifier(val label: String, val mod: Int) {

    var active by mutableStateOf(false)
        private set

    var locked by mutableStateOf(false)
        private set

    private var lastTapAt = 0L

    fun tap() {
        val now = System.currentTimeMillis()
        val doubleTap = now - lastTapAt < DOUBLE_TAP_MS
        lastTapAt = now
        when {
            // 已锁定：任何一次点击都是「解锁并关掉」，这是唯一的退出路径。
            locked -> {
                locked = false
                active = false
            }
            // 第一次点已经把它打开了，300 ms 内再点一次 = 我要一直按着。
            active && doubleTap -> locked = true
            else -> active = !active
        }
    }

    /** 长按：Termux 语义（inactive → 锁定并打开；active → 解锁并关掉）。 */
    fun longPress() {
        val next = !active
        locked = next
        active = next
    }

    /**
     * 取用一次。锁定时保持，否则用完即失效。
     * @return 这次按键要不要带上这个修饰符
     */
    fun consume(): Boolean {
        if (!active) return false
        if (!locked) active = false
        return true
    }

    fun clear() {
        active = false
        locked = false
    }

    private companion object {
        const val DOUBLE_TAP_MS = 300L
    }
}

/** 三个粘滞修饰键 + 一次性取用。 */
@Stable
class ExtraKeysState {
    val ctrl = StickyModifier("CTRL", TerminalEmulator.MOD_CTRL)
    val alt = StickyModifier("ALT", TerminalEmulator.MOD_ALT)
    val shift = StickyModifier("SHIFT", TerminalEmulator.MOD_SHIFT)

    /** 发一个键时调：拿到修饰符位或，并把没锁定的那些清掉。 */
    fun consumeMods(): Int {
        var m = 0
        if (ctrl.consume()) m = m or TerminalEmulator.MOD_CTRL
        if (alt.consume()) m = m or TerminalEmulator.MOD_ALT
        if (shift.consume()) m = m or TerminalEmulator.MOD_SHIFT
        return m
    }

    /** 只看不取（硬件键盘要把物理修饰键和粘滞状态取并集）。 */
    fun peekMods(): Int {
        var m = 0
        if (ctrl.active) m = m or TerminalEmulator.MOD_CTRL
        if (alt.active) m = m or TerminalEmulator.MOD_ALT
        if (shift.active) m = m or TerminalEmulator.MOD_SHIFT
        return m
    }

    fun clearAll() {
        ctrl.clear()
        alt.clear()
        shift.clear()
    }
}

private const val KIND_KEY = 0
private const val KIND_TEXT = 1
private const val KIND_MOD = 2

/**
 * 一个按键的定义。
 *
 * @param repeatable 长按连发。只给方向键 / 退格 / 翻页这些「按住不放有意义」
 *        的键开，字母键连发只会打出一串垃圾。
 * @param popup 上滑触发的第二功能。
 */
@Stable
class ExtraKey(
    val label: String,
    private val kind: Int,
    val keyCode: Int = 0,
    val codePoint: Int = -1,
    val modifier: StickyModifier? = null,
    val repeatable: Boolean = false,
    val popup: ExtraKey? = null
) {
    val isModifier: Boolean get() = kind == KIND_MOD

    fun send(binding: TerminalBinding, mods: Int) {
        when (kind) {
            KIND_KEY -> binding.key(keyCode, mods)
            KIND_TEXT -> binding.key(TerminalEmulator.KEY_CHAR, mods, codePoint)
        }
    }
}

/**
 * Termux 的默认两行布局，一比一照搬：
 * ```
 * ESC  /  -  HOME  UP    END    PGUP
 * TAB CTRL ALT LEFT DOWN RIGHT  PGDN
 * ```
 * 注意这里**没有 `^C` / `^D` 这类硬编码组合键**。Termux 的语义是「CTRL 粘滞 +
 * 你自己按 c」，多一个 `^C` 按钮反而会让人以为 CTRL 只能这么用。真要一键中断
 * 的话，标题栏和长按菜单里都有「中断」。
 */
@Composable
private fun defaultLayout(state: ExtraKeysState): List<List<ExtraKey>> = remember(state) {
    listOf(
        listOf(
            ExtraKey("ESC", KIND_KEY, keyCode = TerminalEmulator.KEY_ESCAPE),
            ExtraKey(
                "/", KIND_TEXT, codePoint = '/'.code,
                popup = ExtraKey("\\", KIND_TEXT, codePoint = '\\'.code)
            ),
            ExtraKey(
                "-", KIND_TEXT, codePoint = '-'.code,
                popup = ExtraKey("|", KIND_TEXT, codePoint = '|'.code)
            ),
            ExtraKey("HOME", KIND_KEY, keyCode = TerminalEmulator.KEY_HOME),
            ExtraKey("▲", KIND_KEY, keyCode = TerminalEmulator.KEY_UP, repeatable = true),
            ExtraKey("END", KIND_KEY, keyCode = TerminalEmulator.KEY_END),
            ExtraKey("PGUP", KIND_KEY, keyCode = TerminalEmulator.KEY_PAGE_UP, repeatable = true)
        ),
        listOf(
            ExtraKey("TAB", KIND_KEY, keyCode = TerminalEmulator.KEY_TAB),
            ExtraKey("CTRL", KIND_MOD, modifier = state.ctrl),
            ExtraKey("ALT", KIND_MOD, modifier = state.alt),
            ExtraKey("◀", KIND_KEY, keyCode = TerminalEmulator.KEY_LEFT, repeatable = true),
            ExtraKey("▼", KIND_KEY, keyCode = TerminalEmulator.KEY_DOWN, repeatable = true),
            ExtraKey("▶", KIND_KEY, keyCode = TerminalEmulator.KEY_RIGHT, repeatable = true),
            ExtraKey("PGDN", KIND_KEY, keyCode = TerminalEmulator.KEY_PAGE_DOWN, repeatable = true)
        )
    )
}

/**
 * @param onKeySent 每发出一个键之后调一次。调用方拿它把焦点抢回输入 View ——
 *        Compose 的可点击元素有可能顺走焦点，焦点一走软键盘就自己收了。
 */
@Composable
fun ExtraKeysRow(
    binding: TerminalBinding?,
    state: ExtraKeysState,
    onKeySent: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val rows = defaultLayout(state)
    Column(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 4.dp, vertical = 4.dp)
    ) {
        for (row in rows) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                for (key in row) {
                    ExtraKeyButton(
                        key = key,
                        onSend = { k ->
                            val b = binding ?: return@ExtraKeyButton
                            k.send(b, state.consumeMods())
                            onKeySent()
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

/** 长按连发的间隔。Termux 的默认值就是 80 ms。 */
private const val REPEAT_INTERVAL_MS = 80L

@Composable
private fun ExtraKeyButton(
    key: ExtraKey,
    onSend: (ExtraKey) -> Unit,
    modifier: Modifier = Modifier
) {
    var pressed by remember { mutableStateOf(false) }
    var popupArmed by remember { mutableStateOf(false) }

    val mod = key.modifier
    val active = mod?.active == true
    val locked = mod?.locked == true

    val cs = MaterialTheme.colorScheme
    val fg = when {
        locked || active -> cs.primary
        else -> cs.onSurface
    }
    // 三档底色都从 primary 兑出来，深浅两套主题下的相对关系才一致；
    // 写死的青色在浅色主题上会直接糊成一片。
    val bg = when {
        pressed -> cs.primary.copy(alpha = 0.28f)
        locked -> cs.primary.copy(alpha = 0.22f)
        active -> cs.primary.copy(alpha = 0.12f)
        else -> cs.surfaceContainerHigh
    }

    Box(
        modifier
            .padding(vertical = 3.dp)
            .height(38.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(bg)
            .pointerInput(key) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    pressed = true
                    popupArmed = false

                    // swallowClick：长按已经产生过效果了，抬手那一下必须**吞掉**。
                    // 不吞的话：修饰键刚长按锁上，松手的 click 又把它解锁；
                    // 连发键松手会多打出一个字符。
                    var swallowClick = false
                    var repeating = false
                    var longPressDone = false
                    val longPressAt =
                        System.currentTimeMillis() + viewConfiguration.longPressTimeoutMillis
                    var armed = false

                    while (true) {
                        // 已经超时的情况要夹到 0（=「立刻算超时」），不能让它变成
                        // 负数走进「不带超时地等」那条分支 —— 那样长按永远不触发。
                        val wait = when {
                            repeating -> REPEAT_INTERVAL_MS
                            !longPressDone ->
                                (longPressAt - System.currentTimeMillis()).coerceAtLeast(0L)
                            else -> -1L
                        }
                        val event = if (wait >= 0) {
                            if (wait == 0L) null else withTimeoutOrNull(wait) { awaitPointerEvent() }
                        } else {
                            awaitPointerEvent()
                        }

                        if (event == null) {
                            if (repeating) {
                                onSend(key)
                                continue
                            }
                            longPressDone = true
                            when {
                                key.isModifier -> {
                                    mod?.longPress()
                                    swallowClick = true
                                }
                                key.repeatable -> {
                                    onSend(key)
                                    repeating = true
                                    swallowClick = true
                                }
                            }
                            continue
                        }

                        val change = event.changes.firstOrNull { it.id == down.id }
                            ?: event.changes.firstOrNull()
                            ?: break
                        if (!change.pressed) {
                            change.consume()
                            break
                        }
                        // 上滑取第二功能。阈值给 2 倍 slop：按键只有 38dp 高，
                        // 一倍 slop 会让「手指按下时的自然滑动」误触发。
                        if (key.popup != null) {
                            val dy = change.position.y - down.position.y
                            armed = dy < -slop * 2
                            popupArmed = armed
                        }
                        change.consume()
                    }

                    pressed = false
                    popupArmed = false
                    if (!swallowClick) {
                        val target = if (armed) key.popup ?: key else key
                        if (key.isModifier) mod?.tap() else onSend(target)
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            key.label,
            fontFamily = FontFamily.Monospace,
            fontSize = if (key.label.length > 3) 10.sp else 13.sp,
            color = fg,
            fontWeight = if (active || locked) FontWeight.Bold else FontWeight.Medium
        )
        key.popup?.let { p ->
            Text(
                p.label,
                fontFamily = FontFamily.Monospace,
                fontSize = 8.sp,
                color = if (popupArmed) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 3.dp, top = 1.dp)
            )
        }
        // 锁定标记：一条底边。只靠变色的话，色弱用户分不出 active 和 locked。
        if (locked) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}
