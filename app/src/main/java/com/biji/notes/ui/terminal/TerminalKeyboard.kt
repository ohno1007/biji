package com.biji.notes.ui.terminal

import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.biji.notes.terminal.TerminalEmulator

// =====================================================================
//  软键盘 / 输入法桥。
//
//  ## 为什么是一个 AndroidView 而不是 BasicTextField
//  终端里根本没有「可编辑文本」这个东西 —— 光标位置归 shell 管，退格意味着
//  「发一个 0x7F 过去，然后看程序怎么回」，而不是「删掉缓冲区里的一个字符」。
//  用 BasicTextField 就必须去 diff 新旧字符串反推用户干了什么，中文输入法下
//  必然出现组合期抖动和重复上屏（拼音候选还没选，字就已经打进 shell 了）。
//
//  这里挂一个 1dp 的透明 View，只负责实现 `onCreateInputConnection`，渲染仍然
//  全 Compose。这样能拿到完整的 IME 语义：
//
//    setComposingText  → **只更新 editable，一个字节都不发**（拼音组合期）
//    commitText        → 候选上屏，整段发出去
//    finishComposingText → 输入法收工，把 editable 里剩的发出去
//    deleteSurroundingText → 翻译成 N 次退格
//
//  「组合期间不发送」是中文输入法能正常工作的**唯一**做法，也是 Termux 的做法。
//
//  ## inputType 的取舍
//  Termux 默认 `TYPE_NULL`，另外给了个 `enforce-char-based-input` 选项。
//  我们反过来：默认就用 `TYPE_CLASS_TEXT | VISIBLE_PASSWORD | NO_SUGGESTIONS`。
//  理由是这个 app 是中文优先的，而 `TYPE_NULL` 下相当一部分中文输入法会退化成
//  「直接发按键事件」，拼音候选栏都出不来。VISIBLE_PASSWORD 变体保留了完整的
//  组合语义，同时天然关掉自动纠错、自动大写、词库联想 —— 这些东西出现在
//  终端里全是灾难（把 `ls` 纠正成 `Is`）。
//
//  代价：极少数输入法看到 VISIBLE_PASSWORD 会禁用自己的候选栏。真碰上了，
//  把 [TerminalInputView.charBasedInput] 关掉就退回 TYPE_NULL。
// =====================================================================

/**
 * 输入的接线板。回调是可变字段（每次重组都会有新 lambda，做成构造参数就得
 * 重建 View，焦点会跟着丢）。
 */
@Stable
class TerminalImeController {

    internal var view: TerminalInputView? = null

    /**
     * 输入 View 有没有焦点。渲染侧拿它决定光标画实心块还是空心框 ——
     * 和所有终端一致：空心框 = 「键盘现在不在我这」。
     */
    var focused by mutableStateOf(false)
        internal set

    /** 输入法上屏的一整段文本（可能含 emoji / 多字）。 */
    var onText: (String) -> Unit = {}

    /** 一个具名键。[mods] 已经是 `TerminalEmulator.MOD_*` 的位或。 */
    var onKey: (key: Int, mods: Int, codePoint: Int) -> Unit = { _, _, _ -> }

    /** 硬件键盘按下时读一次粘滞修饰键，和物理修饰键取并集。 */
    var stickyMods: () -> Int = { 0 }

    fun show() {
        val v = view ?: return
        v.requestFocus()
        v.imm()?.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
    }

    fun hide() {
        val v = view ?: return
        v.imm()?.hideSoftInputFromWindow(v.windowToken, 0)
    }

    /**
     * 切换软键盘。
     *
     * 不用 `InputMethodManager.toggleSoftInput`（已废弃，而且在部分 ROM 上
     * 会把别的窗口的输入法也切了），改成先问 WindowInsets「键盘现在在不在」
     * 再决定 show / hide —— 这也是唯一一个跨版本可靠的可见性判断。
     */
    fun toggle() {
        val v = view ?: return
        val visible = ViewCompat.getRootWindowInsets(v)
            ?.isVisible(WindowInsetsCompat.Type.ime()) ?: false
        if (visible) hide() else show()
    }

    /**
     * 把焦点抢回来，但**不弹键盘**。
     *
     * 点标题栏按钮 / 抽屉里的东西之后，焦点可能被 Compose 的可点击元素拿走，
     * 焦点一走软键盘就自己收了 —— 表现是「点了一下工具栏，键盘没了」。
     */
    fun ensureFocus() {
        val v = view ?: return
        if (!v.hasFocus()) v.requestFocus()
    }
}

/**
 * 承载 InputConnection 的隐形 View。
 *
 * 尺寸给 1dp 而不是 0：0 尺寸的 View 在部分 ROM 上拿不到焦点，输入法也就
 * 永远不会弹出来。
 */
@Composable
fun TerminalImeAnchor(
    controller: TerminalImeController,
    modifier: Modifier = Modifier
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TerminalInputView(ctx).apply {
                bind(controller)
                controller.view = this
                setOnFocusChangeListener { _, hasFocus -> controller.focused = hasFocus }
                requestFocus()
            }
        }
    )
    DisposableEffect(controller) {
        onDispose { controller.view = null }
    }
}

class TerminalInputView(context: Context) : View(context) {

    /** false = 退回 `TYPE_NULL`（个别输入法在 VISIBLE_PASSWORD 下不给候选栏）。 */
    var charBasedInput: Boolean = true

    private var controller: TerminalImeController? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        // 拿到焦点时不要画那圈默认高亮：这是个隐形 View（API 26 起有这个属性，
        // 正好是本项目的 minSdk）。
        defaultFocusHighlightEnabled = false
    }

    fun bind(c: TerminalImeController) {
        controller = c
    }

    internal fun imm(): InputMethodManager? =
        context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = if (charBasedInput) {
            InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        } else {
            InputType.TYPE_NULL
        }
        // 绝不要全屏编辑模式：横屏时输入法会弹出一个盖住整个终端的文本框。
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI

        return object : BaseInputConnection(this, true) {

            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                super.commitText(text, newCursorPosition)
                flush()
                return true
            }

            override fun finishComposingText(): Boolean {
                super.finishComposingText()
                flush()
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                // 终端里没有「光标前的那几个字符」——已经打出去的东西归程序管。
                // 只能翻译成 N 次退格，让 readline / vim 自己决定删什么。
                var i = 0
                while (i < beforeLength) {
                    sendNamedKey(TerminalEmulator.KEY_BACKSPACE)
                    i++
                }
                return super.deleteSurroundingText(beforeLength, afterLength)
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                // TYPE_NULL 下的输入法、以及所有硬件键盘走这条路。
                // ACTION_UP 直接吞掉：终端只认「按下」这一个事件。
                if (event.action == KeyEvent.ACTION_DOWN) return handleKeyDown(event)
                return true
            }

            /**
             * 把 editable 里攒下的东西一次性发走并清空。
             *
             * **只在 commit / finish 时调**。组合期（setComposingText）editable
             * 里是拼音串，这时候发出去就是把 "nihao" 打进 shell。
             */
            private fun flush() {
                val ed = editable ?: return
                if (ed.isEmpty()) return
                val s = ed.toString()
                ed.clear()
                controller?.onText(s)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        handleKeyDown(event) || super.onKeyDown(keyCode, event)

    @Suppress("DEPRECATION")
    override fun onKeyMultiple(keyCode: Int, repeatCount: Int, event: KeyEvent): Boolean {
        // 某些输入法用 KEYCODE_UNKNOWN + characters 一次性塞一整串。
        // `characters` 标了废弃，但对这条路径没有替代 API，不接就会丢字。
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            val chars = event.characters
            if (!chars.isNullOrEmpty()) {
                controller?.onText(chars)
                return true
            }
        }
        return super.onKeyMultiple(keyCode, repeatCount, event)
    }

    private fun sendNamedKey(key: Int) {
        controller?.let { it.onKey(key, 0, -1) }
    }

    /**
     * 硬件键盘 / IME 的按键事件 → 模拟器的键码。
     *
     * 修饰键取**物理键 ∪ 粘滞键**：接了蓝牙键盘的人按住 Ctrl 的同时，
     * 屏幕上的 CTRL 也可能是亮的，两边都算数才符合直觉。
     */
    private fun handleKeyDown(event: KeyEvent): Boolean {
        val c = controller ?: return false
        val meta = event.metaState
        var mods = c.stickyMods()
        if (meta and KeyEvent.META_CTRL_ON != 0) mods = mods or TerminalEmulator.MOD_CTRL
        if (meta and KeyEvent.META_ALT_ON != 0) mods = mods or TerminalEmulator.MOD_ALT
        if (meta and KeyEvent.META_SHIFT_ON != 0) mods = mods or TerminalEmulator.MOD_SHIFT

        val named = namedKeyOf(event.keyCode)
        if (named >= 0) {
            c.onKey(named, mods, -1)
            return true
        }

        // 取字符时必须**先把 Ctrl / Alt 从 metaState 里摘掉**：带着 Ctrl 去问
        // getUnicodeChar 会得到 0（系统认为这是个组合键），于是 Ctrl-C 就变成
        // 「什么都没发生」。Shift 要留着，不然大写字母全变小写。
        val clean = meta and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK).inv()
        val cp = event.getUnicodeChar(clean)
        if (cp != 0) {
            c.onKey(TerminalEmulator.KEY_CHAR, mods, cp)
            return true
        }
        return false
    }

    private fun namedKeyOf(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> TerminalEmulator.KEY_UP
        KeyEvent.KEYCODE_DPAD_DOWN -> TerminalEmulator.KEY_DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> TerminalEmulator.KEY_LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> TerminalEmulator.KEY_RIGHT
        KeyEvent.KEYCODE_MOVE_HOME -> TerminalEmulator.KEY_HOME
        KeyEvent.KEYCODE_MOVE_END -> TerminalEmulator.KEY_END
        KeyEvent.KEYCODE_PAGE_UP -> TerminalEmulator.KEY_PAGE_UP
        KeyEvent.KEYCODE_PAGE_DOWN -> TerminalEmulator.KEY_PAGE_DOWN
        KeyEvent.KEYCODE_INSERT -> TerminalEmulator.KEY_INSERT
        KeyEvent.KEYCODE_FORWARD_DEL -> TerminalEmulator.KEY_DELETE
        KeyEvent.KEYCODE_DEL -> TerminalEmulator.KEY_BACKSPACE
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> TerminalEmulator.KEY_ENTER
        KeyEvent.KEYCODE_TAB -> TerminalEmulator.KEY_TAB
        KeyEvent.KEYCODE_ESCAPE -> TerminalEmulator.KEY_ESCAPE
        KeyEvent.KEYCODE_F1 -> TerminalEmulator.KEY_F1
        KeyEvent.KEYCODE_F2 -> TerminalEmulator.KEY_F2
        KeyEvent.KEYCODE_F3 -> TerminalEmulator.KEY_F3
        KeyEvent.KEYCODE_F4 -> TerminalEmulator.KEY_F4
        KeyEvent.KEYCODE_F5 -> TerminalEmulator.KEY_F5
        KeyEvent.KEYCODE_F6 -> TerminalEmulator.KEY_F6
        KeyEvent.KEYCODE_F7 -> TerminalEmulator.KEY_F7
        KeyEvent.KEYCODE_F8 -> TerminalEmulator.KEY_F8
        KeyEvent.KEYCODE_F9 -> TerminalEmulator.KEY_F9
        KeyEvent.KEYCODE_F10 -> TerminalEmulator.KEY_F10
        KeyEvent.KEYCODE_F11 -> TerminalEmulator.KEY_F11
        KeyEvent.KEYCODE_F12 -> TerminalEmulator.KEY_F12
        else -> -1
    }
}

@Composable
fun rememberTerminalImeController(): TerminalImeController = remember { TerminalImeController() }
