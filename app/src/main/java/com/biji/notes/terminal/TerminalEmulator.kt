package com.biji.notes.terminal

import com.biji.notes.terminal.TerminalColors.ATTR_BLINK
import com.biji.notes.terminal.TerminalColors.ATTR_BOLD
import com.biji.notes.terminal.TerminalColors.ATTR_DIM
import com.biji.notes.terminal.TerminalColors.ATTR_HIDDEN
import com.biji.notes.terminal.TerminalColors.ATTR_INVERSE
import com.biji.notes.terminal.TerminalColors.ATTR_ITALIC
import com.biji.notes.terminal.TerminalColors.ATTR_OVERLINE
import com.biji.notes.terminal.TerminalColors.ATTR_STRIKE
import com.biji.notes.terminal.TerminalColors.ATTR_UNDERLINE
import com.biji.notes.terminal.TerminalColors.ATTR_UNDERLINE_CURLY
import com.biji.notes.terminal.TerminalColors.ATTR_UNDERLINE_DOUBLE
import com.biji.notes.terminal.TerminalColors.COLOR_DEFAULT_BG
import com.biji.notes.terminal.TerminalColors.COLOR_DEFAULT_FG

/**
 * 终端要往"上游"送的东西。写 pty / stdin 的那一头实现它。
 *
 * 全部给了默认空实现：接线时只关心自己要的那几个，不用写一堆空方法。
 * **所有回调都在解析线程、且在持锁状态下被调用** —— 实现里不要做耗时操作，
 * 更不要反过来调 [TerminalEmulator] 的方法（自锁）。要跨线程就往队列里丢。
 */
interface TerminalHost {
    /** 终端自己要回复的报文（DA / DSR / 鼠标上报）。原样写进 pty。 */
    fun onResponse(data: ByteArray) {}

    /** OSC 0/1/2 的标题。 */
    fun onTitle(title: String) {}

    /** BEL。手机上建议短振动而不是响铃。 */
    fun onBell() {}

    /**
     * OSC 7 上报的工作目录（`file://host/path`）。
     * 这个对 biji 格外有用：终端知道 shell 当前在哪个目录，可以直接同步给
     * `LocalSandbox` 的项目树 UI —— 收益在终端之外。
     */
    fun onWorkingDirectory(uri: String) {}

    /** OSC 52，桥到 Android 剪贴板。 */
    fun onClipboard(text: String) {}
}

/**
 * VT/ANSI 解析器 + 屏幕状态机。
 *
 * ## 分片输入
 * [feed] 可以在**任意字节位置**被切开：一条转义序列被劈成两次 read、一个
 * UTF-8 码点被劈成两半，都必须正确。所以状态机状态和 UTF-8 半解码状态都是
 * **实例字段**，不是栈上局部量 —— 这是自研终端 Top 3 的 bug 源。
 *
 * ## 状态机骨架照抄 Paul Williams 的状态图
 * 采纳它的理由不是"最快"，是**对畸形输入的行为有定义**。手机上程序被 kill
 * 在半条序列中间是常态（用户按返回、系统回收、Ctrl-C 打断重绘），ad-hoc 的
 * 解析器碰到这种输入会永久卡在某个中间状态，症状是"终端突然不显示了，
 * 只能重启"。这里打了四个补丁：
 *
 *  1. **不实现 8 位 C1（0x80-0x9F）**。UTF-8 模式下那些是续字节，冲突。
 *     非 ground 状态遇到 >= 0x80 一律当"可忽略字节"（既不喂解码器，
 *     也不中止当前序列）。所有现代终端（vte、alacritty）都是这么做的。
 *  2. **参数区扩展**到 32 个 + 冒号子参数（ISO 8613-6 的 `38:2::R:G:B`），
 *     单值 clamp 到 65535（xterm 行为，防 `CSI 99999999999A` 溢出），
 *     **空参数和 0 必须区分**（`CSI ;5H` 与 `CSI 0;5H` 语义不同）。
 *  3. **字符串态有上限**：OSC/DCS 载荷 4 KB 封顶，超了就吃到终止符但丢内容，
 *     免得一个不发 ST 的程序把内存吃光。
 *  4. **ground 态批量快路径**：连续的可打印 ASCII 走一个不查表、不分派的
 *     内层循环。`cat` 大文件的绝大部分字节走这条。
 *
 * ## 线程契约
 * 所有 public 方法都在同一把锁里。典型用法是：一条 `Dispatchers.IO` 协程
 * 反复 [feed]，UI 线程每帧 [snapshot]。UI 侧不要轮询别的东西，
 * 只看 [revision] 变没变 —— 变了才重绘，多次输出天然合并成一帧。
 *
 * ## 明确不做的（**别顺手补上**，每一条都是权衡过的）
 *  - **8 位 C1（0x80-0x9F）**：和 UTF-8 冲突，现代终端一律不实现。
 *  - **双宽 / 双高行**（`ESC # 3/4/5/6`）：网格模型里没有它的位置。
 *  - **Sixel / iTerm2 / kitty 图形协议**、**ReGIS**：整个另一个量级的工程。
 *  - **DECSLRM 左右边距（`?69`）**：所以 `CSI s` 无歧义地当保存光标用。
 *  - **矩形区域操作**（DECCRA/DECFRA/DECERA/DECSERA）、**DECUDK**、
 *    **打印控制（MC）**、**ENQ answerback**。
 *  - **除 ASCII 与 DEC 特殊图形外的字符集**（英国 / 各国替换集）。
 *  - **`?1005` UTF-8 鼠标编码**（已废弃，用 `?1006`）。
 *  - **备用屏的回滚**：xterm 语义，不遵守的话 vim 滚一次就把回滚缓冲填满垃圾。
 *  - **`XTVERSION` / `XTGETTCAP`**：tmux 查不到会自己降级，无害。
 *  - **双向文字 / 阿拉伯字形整形**：终端是网格，容器里的程序也按 wcwidth 算列。
 */

/**
 * ESC (0x1B)。
 *
 * 不写成反斜杠 u 转义：这份源码要经 JSON 通道搬运（本地 git push 没凭据时
 * 只能走 GitHub API），那种转义在送达之前必然被解码成真正的 0x1B 字节塞进
 * 源文件 —— 编译照样过，但源码里从此躺着一堆看不见的控制字符，而且每搬一次
 * 就再错一次。插值写法反而更贴近它表达的东西：ESC + 后面那串。
 */
private val ESC = Char(0x1B).toString()

/** DEL (0x7F)。同上，别写成转义。 */
private val DEL = Char(0x7F).toString()

class TerminalEmulator(
    cols: Int,
    rows: Int,
    scrollbackLines: Int = DEFAULT_SCROLLBACK_LINES,
    maxScrollbackBytes: Long = DEFAULT_SCROLLBACK_BYTES,
    private val host: TerminalHost? = null
) {

    private val lock = Any()
    private val buffer = TerminalBuffer(cols, rows, scrollbackLines, maxScrollbackBytes)

    /**
     * 每次屏幕内容 / 视口发生变化时 +1。UI 侧拿它当 Compose 的 state key：
     * `withFrameNanos` 里读一次，变了才触发重绘。
     *
     * 刻意不做"native 回调 UI"式的通知：回调频率由输出速率决定（`yes` 能每
     * 毫秒回一次），而轮询天然限流到刷新率。
     */
    @Volatile
    var revision: Int = 0
        private set

    // ---- 光标 ----
    private var cursorRow = 0
    private var cursorCol = 0

    /**
     * 延迟换行。写满最后一列时**不立刻换行**，只置这个标志；真正换行发生在
     * 下一个可打印字符到来时。任何光标定位类操作都要清掉它。
     *
     * 不做的直接后果：任何正好铺满一行的输出（80 列上刚好占满的 `ls`）都会
     * 多出一个空行，整屏内容错位一行。这是自研终端最常写错、症状最普遍的一点。
     */
    private var pendingWrap = false

    // ---- SGR 当前状态 ----
    private var curFg = COLOR_DEFAULT_FG
    private var curBg = COLOR_DEFAULT_BG
    private var curFlags = 0
    private var styleId = STYLE_DEFAULT

    /**
     * 擦除用样式（BCE，background color erase）：只带背景色，不带下划线 /
     * 反显之类的字形属性。EL/ED/滚入的新行都用它 —— xterm-256color 的
     * terminfo 里 bce 是 true，程序（尤其是 mc、htop）指望着这个行为。
     */
    private var eraseStyleId = STYLE_DEFAULT

    // ---- 模式 ----
    private var decawm = true          // ?7   自动换行
    private var decckm = false         // ?1   应用光标键：改变我们**发出去**的字节
    private var decom = false          // ?6   原点模式
    private var decTcem = true         // ?25  光标可见
    private var reverseVideo = false   // ?5
    private var reverseWrap = false    // ?45
    private var bracketedPaste = false // ?2004
    private var focusEvents = false    // ?1004
    private var syncUpdate = false     // ?2026 同步输出
    private var syncHeld = 0           // 已经因为 ?2026 压住了几次刷新
    private var irm = false            // CSI 4h   插入模式
    private var lnm = false            // CSI 20h  换行模式
    private var keypadApp = false      // DECKPAM
    private var mouseMode = 0          // 0 / 1000 / 1002 / 1003
    private var mouseSgr = false       // ?1006
    private var cursorShape = 0        // DECSCUSR

    // ---- 滚动区（闭区间，屏幕行号） ----
    private var scrollTop = 0
    private var scrollBottom = rows - 1

    // ---- 字符集 ----
    // G0..G3 存的是最终字节（'B' = ASCII，'0' = DEC 特殊图形）。
    private val charsets = intArrayOf('B'.code, 'B'.code, 'B'.code, 'B'.code)
    private var gl = 0
    private var glGraphics = false

    // ---- DECSC / DECRC 存档。主屏和备用屏各一份（xterm 语义）。 ----
    private val savedMain = SavedCursor()
    private val savedAlt = SavedCursor()

    private class SavedCursor {
        var row = 0
        var col = 0
        var fg = COLOR_DEFAULT_FG
        var bg = COLOR_DEFAULT_BG
        var flags = 0
        var g0 = 'B'.code
        var g1 = 'B'.code
        var gl = 0
        var origin = false
        var valid = false
    }

    // ---- tab stops ----
    private var tabStops = BooleanArray(buffer.cols)

    // ---- 视口 ----
    private var viewTop = 0
    private var followBottom = true

    // ---- 解析器状态（必须跨 feed 保持） ----
    private var state = ST_GROUND
    private val params = IntArray(MAX_PARAMS)
    private val paramColon = BooleanArray(MAX_PARAMS)
    private var paramCount = 0
    private var csiPrefix = 0
    private var csiInter = 0
    private var escInter = 0
    private var oscBuf = CharArray(OSC_MAX)
    private var oscLen = 0
    private var oscOverflow = false
    private var strEsc = false

    // ---- UTF-8 增量解码状态（同样必须跨 feed 保持） ----
    private var u8Remaining = 0
    private var u8Acc = 0
    private var u8Min = 0

    private val resizeMarks = IntArray(4)

    init {
        resetTabStops()
        applyStyle()
    }

    val columns: Int get() = buffer.cols
    val screenRows: Int get() = buffer.rows
    val totalLines: Int get() = buffer.totalLines
    val historyLines: Int get() = buffer.historyLines

    // =================================================================
    // 输入：字节流
    // =================================================================

    /**
     * 喂一段字节。可以在任意位置被切开，状态跨调用保持。
     *
     * 越界参数直接夹住并静默返回 —— 这条路径在 IO 协程里，抛异常等于
     * 让读循环整个死掉，而终端会表现成"卡住不动"。
     */
    fun feed(data: ByteArray, offset: Int = 0, length: Int = data.size - offset) {
        if (length <= 0) return
        val from = if (offset < 0) 0 else offset
        val to = minOf(data.size, from + length)
        if (from >= to) return
        synchronized(lock) {
            var i = from
            while (i < to) {
                // ---- ground 态批量快路径 ----
                // 纯 ASCII 段一次性打完，不查表、不分派。cat 大文件时绝大部分
                // 字节走这里；注意**仍然要逐格判延迟换行和插入模式**，
                // 不能一 memcpy 了事。
                if (state == ST_GROUND && u8Remaining == 0) {
                    var b = data[i].toInt() and 0xFF
                    while (b in 0x20..0x7E) {
                        printAscii(b)
                        i++
                        if (i >= to) break
                        b = data[i].toInt() and 0xFF
                    }
                    if (i >= to) break
                }
                handleByte(data[i].toInt() and 0xFF)
                i++
            }
            afterFeed()
        }
    }

    /**
     * 喂一段已经是 UTF-16 的文本。
     *
     * 给还没有 PTY 的过渡期用：`InteractiveShell` 吐的是 `String`，
     * 走这条就不用先编码回 UTF-8 再解一遍。语义和 [feed] 完全一致，
     * 连状态机都是同一个 —— 只是跳过了 UTF-8 解码那一层。
     */
    fun feed(text: String) {
        if (text.isEmpty()) return
        synchronized(lock) {
            var i = 0
            while (i < text.length) {
                val ch = text[i]
                var cp = ch.code
                if (ch.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
                    cp = Character.toCodePoint(ch, text[i + 1])
                    i++
                }
                i++
                when {
                    cp < 0x80 -> handleByte(cp)
                    state == ST_GROUND -> printCodePoint(cp)
                    state == ST_OSC || state == ST_STRING -> oscAppend(cp)
                    // 其它状态下的非 ASCII 一律忽略，和字节路径的补丁 1 一致。
                }
            }
            afterFeed()
        }
    }

    private fun afterFeed() {
        if (followBottom) {
            val max = buffer.totalLines - buffer.rows
            viewTop = if (max > 0) max else 0
        }
        // ?2026 同步输出：程序说"我正在重绘，先别刷"。不 bump revision，
        // UI 就不会重绘到画了一半的屏幕 —— 对 Compose 侧是白拿的防闪烁。
        //
        // 但绝不能无条件相信它：程序被 kill 在 ?2026h 和 ?2026l 之间是常态，
        // 那样屏幕就永久冻住了。攒够 SYNC_MAX_FEEDS 次就强制刷一帧。
        if (syncUpdate) {
            syncHeld++
            if (syncHeld < SYNC_MAX_FEEDS) return
            syncHeld = 0
        }
        revision++
    }

    // -----------------------------------------------------------------
    // 状态机
    // -----------------------------------------------------------------

    private fun handleByte(b: Int) {
        // UTF-8 续读优先：一次 read 会同时劈开转义序列和 UTF-8 序列。
        if (u8Remaining > 0) {
            if (b in 0x80..0xBF) {
                u8Acc = (u8Acc shl 6) or (b and 0x3F)
                u8Remaining--
                if (u8Remaining == 0) {
                    val cp = u8Acc
                    // 过长编码、代理区、超出 U+10FFFF 全部当非法。放过去的话
                    // 屏幕上会出现孤代理，Compose 的 drawText 直接画成豆腐块。
                    val bad = cp < u8Min || (cp in 0xD800..0xDFFF) || cp > 0x10FFFF
                    emitDecoded(if (bad) 0xFFFD else cp)
                }
                return
            }
            // 非法：按"最大子部分"规则吐一个 U+FFFD，然后让**触发错误的这个
            // 字节重新进状态机** —— 它完全可能是 0x1B，吞掉就把整条序列吃没了。
            u8Remaining = 0
            emitDecoded(0xFFFD)
        }

        if (state == ST_OSC) {
            oscByte(b)
            return
        }
        if (state == ST_STRING) {
            stringByte(b)
            return
        }

        // C0 在任何非字符串状态下都立即生效（Williams 表的 execute 动作）。
        if (b == 0x1B) {
            startEsc()
            return
        }
        if (b == 0x18 || b == 0x1A) {
            // CAN / SUB：中止当前序列回到 ground。程序被 kill 在半条序列里时
            // 靠这条自救，不然终端会永久卡在解析状态。
            state = ST_GROUND
            return
        }
        if (b < 0x20) {
            execC0(b)
            return
        }
        if (b == 0x7F) return                       // DEL 一律忽略
        if (b >= 0x80 && state != ST_GROUND) return // 补丁 1

        when (state) {
            ST_GROUND -> if (b >= 0x80) utf8Start(b) else printAscii(b)
            ST_ESC -> escByte(b)
            ST_ESC_INTER -> escInterByte(b)
            ST_CSI_ENTRY, ST_CSI_PARAM -> csiParamByte(b)
            ST_CSI_INTER -> csiInterByte(b)
            ST_CSI_IGNORE -> if (b in 0x40..0x7E) state = ST_GROUND
        }
    }

    private fun emitDecoded(cp: Int) {
        when (state) {
            ST_GROUND -> printCodePoint(cp)
            ST_OSC, ST_STRING -> oscAppend(cp)
        }
    }

    private fun utf8Start(b: Int) {
        when {
            // 出错走 emitDecoded 而不是直接 printCodePoint：OSC 里的标题也走这条
            // 解码路径，直接打印会把 U+FFFD 喷到屏幕上。
            b < 0xC2 -> emitDecoded(0xFFFD)      // 续字节裸奔 / 过长的 C0/C1 编码
            b < 0xE0 -> {
                u8Remaining = 1; u8Acc = b and 0x1F; u8Min = 0x80
            }
            b < 0xF0 -> {
                u8Remaining = 2; u8Acc = b and 0x0F; u8Min = 0x800
            }
            b < 0xF5 -> {
                u8Remaining = 3; u8Acc = b and 0x07; u8Min = 0x10000
            }
            else -> emitDecoded(0xFFFD)          // 0xF5..0xFF 永远非法
        }
    }

    private fun execC0(b: Int) {
        when (b) {
            0x07 -> host?.onBell()
            0x08 -> {
                // BS 只退一格，不跨行、不擦除。反向自动换行（?45）开着时才跨行。
                pendingWrap = false
                if (cursorCol > 0) cursorCol--
                else if (reverseWrap && cursorRow > scrollTop) {
                    cursorRow--
                    cursorCol = buffer.cols - 1
                }
            }
            0x09 -> tabForward(1)
            0x0A, 0x0B, 0x0C -> lineFeed()
            0x0D -> {
                cursorCol = 0
                pendingWrap = false
            }
            0x0E -> setGl(1)
            0x0F -> setGl(0)
        }
    }

    private fun startEsc() {
        state = ST_ESC
        escInter = 0
    }

    private fun escByte(b: Int) {
        when (b) {
            '['.code -> {
                state = ST_CSI_ENTRY
                paramCount = 0
                params[0] = -1
                paramColon[0] = false
                csiPrefix = 0
                csiInter = 0
            }
            ']'.code -> {
                state = ST_OSC
                oscLen = 0
                oscOverflow = false
                strEsc = false
            }
            // DCS / SOS / PM / APC：一个都不实现，但**必须有 ignore 状态吃到 ST**，
            // 否则载荷会喷到屏幕上。
            'P'.code, 'X'.code, '^'.code, '_'.code -> {
                state = ST_STRING
                strEsc = false
            }
            in 0x20..0x2F -> {
                escInter = b
                state = ST_ESC_INTER
            }
            else -> {
                escDispatch(b)
                state = ST_GROUND
            }
        }
    }

    private fun escInterByte(b: Int) {
        if (b in 0x20..0x2F) {
            escInter = b
            return
        }
        escInterDispatch(escInter, b)
        state = ST_GROUND
    }

    private fun escDispatch(final: Int) {
        when (final.toChar()) {
            '7' -> saveCursor()
            '8' -> restoreCursor()
            'D' -> lineFeedNoCr()                       // IND
            'E' -> {                                    // NEL
                cursorCol = 0
                lineFeedNoCr()
            }
            'M' -> reverseIndex()                       // RI：vi 在顶部往上滚必用
            'H' -> if (cursorCol < tabStops.size) tabStops[cursorCol] = true   // HTS
            'c' -> hardReset()                          // RIS
            '=' -> keypadApp = true                     // DECKPAM
            '>' -> keypadApp = false                    // DECKPNM
            // ESC \ (ST)、锁定移位等：认得，但不做。
        }
    }

    private fun escInterDispatch(inter: Int, final: Int) {
        when (inter.toChar()) {
            '(' -> setCharset(0, final)
            ')' -> setCharset(1, final)
            '*' -> setCharset(2, final)
            '+' -> setCharset(3, final)
            '#' -> if (final == '8'.code) decAlign()
            // '%'（选 UTF-8 / ISO-8859-1）：我们恒定 UTF-8，容器统一 LANG=C.UTF-8，
            // 所以只消费不响应。双宽 / 双高行（# 3/4/5/6）明确不做。
        }
    }

    private fun setCharset(slot: Int, final: Int) {
        charsets[slot] = final
        refreshGraphics()
    }

    private fun setGl(which: Int) {
        gl = which
        refreshGraphics()
    }

    private fun refreshGraphics() {
        glGraphics = charsets[gl] == '0'.code
    }

    // -----------------------------------------------------------------
    // CSI
    // -----------------------------------------------------------------

    private fun csiParamByte(b: Int) {
        when {
            b in 0x30..0x39 -> {
                state = ST_CSI_PARAM
                paramDigit(b - 0x30)
            }
            b == 0x3B -> {
                state = ST_CSI_PARAM
                paramSeparator(false)
            }
            b == 0x3A -> {
                state = ST_CSI_PARAM
                paramSeparator(true)
            }
            b in 0x3C..0x3F -> {
                // 私有前缀只允许出现在最前面；出现在参数中间是畸形序列。
                if (state == ST_CSI_ENTRY && paramCount == 0) {
                    csiPrefix = b
                    state = ST_CSI_PARAM
                } else {
                    state = ST_CSI_IGNORE
                }
            }
            b in 0x20..0x2F -> {
                csiInter = b
                state = ST_CSI_INTER
            }
            b in 0x40..0x7E -> {
                csiDispatch(b)
                state = ST_GROUND
            }
            else -> state = ST_CSI_IGNORE
        }
    }

    private fun csiInterByte(b: Int) {
        when {
            b in 0x20..0x2F -> csiInter = b
            b in 0x40..0x7E -> {
                csiDispatch(b)
                state = ST_GROUND
            }
            else -> state = ST_CSI_IGNORE
        }
    }

    private fun paramDigit(d: Int) {
        if (paramCount == 0) paramCount = 1
        val i = paramCount - 1
        if (i >= MAX_PARAMS) return
        var v = params[i]
        if (v < 0) v = 0
        v = v * 10 + d
        // xterm 的做法：夹住而不是让它溢出成负数。`CSI 99999999999A` 不该
        // 把光标送到某个随机位置。
        params[i] = if (v > 65535) 65535 else v
    }

    private fun paramSeparator(colon: Boolean) {
        if (paramCount == 0) paramCount = 1
        // 超过 32 个参数就只丢不记：paramCount 必须夹住，否则后面
        // `while (i < paramCount)` 那几个循环会按一个虚高的数空转。
        if (paramCount < MAX_PARAMS) {
            params[paramCount] = -1
            paramColon[paramCount] = colon
            paramCount++
        }
    }

    /** 取第 i 个参数；空参数（不是 0！）取 [def]。 */
    private fun param(i: Int, def: Int): Int {
        if (i >= paramCount || i >= MAX_PARAMS) return def
        val v = params[i]
        return if (v < 0) def else v
    }

    /** 取第 i 个参数并保证 >= 1（CUU 之类的"参数 0 视同 1"）。 */
    private fun paramMin1(i: Int): Int {
        val v = param(i, 1)
        return if (v < 1) 1 else v
    }

    private fun csiDispatch(final: Int) {
        val f = final.toChar()
        when (csiPrefix) {
            '?'.code -> {
                when (f) {
                    'h' -> setDecModes(true)
                    'l' -> setDecModes(false)
                    'n' -> decDeviceStatus()
                    'J' -> eraseDisplay(param(0, 0))   // DECSED：我们不实现选择性保护，等同 ED
                    'K' -> eraseLine(param(0, 0))      // DECSEL 同上
                    'p' -> if (csiInter == '$'.code) reportMode()
                    's' -> saveDecModes()
                    'r' -> restoreDecModes()
                }
                return
            }
            '>'.code -> {
                // DA2。vim / tmux 会等这个回复，不回就是启动时的一次超时卡顿。
                if (f == 'c') respond("${ESC}[>0;10;1c")
                return
            }
            '='.code -> return   // DA3 等，认得但不回
            '<'.code -> return
        }

        when (f) {
            '@' -> buffer.insertCells(cursorRow, cursorCol, paramMin1(0), eraseStyleId)
            'A' -> cursorUp(paramMin1(0))
            'B', 'e' -> cursorDown(paramMin1(0))
            'C', 'a' -> cursorRight(paramMin1(0))
            'D' -> cursorLeft(paramMin1(0))
            'E' -> {
                cursorDown(paramMin1(0))
                cursorCol = 0
            }
            'F' -> {
                cursorUp(paramMin1(0))
                cursorCol = 0
            }
            'G', '`' -> setCursorCol(paramMin1(0) - 1)
            'H', 'f' -> setCursorPos(paramMin1(0) - 1, paramMin1(1) - 1)
            'I' -> tabForward(paramMin1(0))
            'J' -> eraseDisplay(param(0, 0))
            'K' -> eraseLine(param(0, 0))
            'L' -> insertLines(paramMin1(0))
            'M' -> deleteLines(paramMin1(0))
            'P' -> buffer.deleteCells(cursorRow, cursorCol, paramMin1(0), eraseStyleId)
            'S' -> buffer.scrollUp(scrollTop, scrollBottom, paramMin1(0), eraseStyleId, historyEligible())
            'T' -> buffer.scrollDown(scrollTop, scrollBottom, paramMin1(0), eraseStyleId)
            'X' -> {
                // ECH：从光标起擦 n 格，光标不动。
                val n = paramMin1(0)
                buffer.blankRun(cursorRow, cursorCol, cursorCol + n, eraseStyleId)
                pendingWrap = false
            }
            'Z' -> tabBackward(paramMin1(0))
            'b' -> repeatLast(paramMin1(0))
            'c' -> respond(DA1)
            'd' -> setCursorRow(paramMin1(0) - 1)
            'g' -> clearTabStop(param(0, 0))
            'h' -> setAnsiModes(true)
            'l' -> setAnsiModes(false)
            'm' -> applySgr()
            'n' -> deviceStatus()
            'q' -> if (csiInter == ' '.code) cursorShape = param(0, 0)   // DECSCUSR
            'p' -> if (csiInter == '!'.code) softReset()                 // DECSTR
            'r' -> setScrollRegion()
            's' -> saveCursor()   // SCOSC。我们不做 DECSLRM，所以这里无歧义
            'u' -> restoreCursor()
            't' -> Unit           // 窗口操作：一律消费，绝不回复尺寸（会被当成键盘输入）
        }
    }

    // -----------------------------------------------------------------
    // 光标 / 擦除 / 滚动
    // -----------------------------------------------------------------

    private fun cursorUp(n: Int) {
        val limit = if (cursorRow >= scrollTop) scrollTop else 0
        cursorRow = maxOf(limit, cursorRow - n)
        pendingWrap = false
    }

    private fun cursorDown(n: Int) {
        val limit = if (cursorRow <= scrollBottom) scrollBottom else buffer.rows - 1
        cursorRow = minOf(limit, cursorRow + n)
        pendingWrap = false
    }

    private fun cursorRight(n: Int) {
        cursorCol = minOf(buffer.cols - 1, cursorCol + n)
        pendingWrap = false
    }

    private fun cursorLeft(n: Int) {
        cursorCol = maxOf(0, cursorCol - n)
        pendingWrap = false
    }

    private fun setCursorCol(col: Int) {
        cursorCol = col.coerceIn(0, buffer.cols - 1)
        pendingWrap = false
    }

    private fun setCursorRow(row: Int) {
        cursorRow = if (decom) (scrollTop + row).coerceIn(scrollTop, scrollBottom)
        else row.coerceIn(0, buffer.rows - 1)
        pendingWrap = false
    }

    private fun setCursorPos(row: Int, col: Int) {
        setCursorRow(row)
        setCursorCol(col)
    }

    private fun lineFeed() {
        // LNM（CSI 20h）置位时 LF 兼做 CR。默认 reset；实现成本近零，
        // 不实现则少数程序（发裸 LF 的老脚本）会阶梯状错位。
        if (lnm) cursorCol = 0
        lineFeedNoCr()
    }

    private fun lineFeedNoCr() {
        pendingWrap = false
        if (cursorRow == scrollBottom) {
            buffer.scrollUp(scrollTop, scrollBottom, 1, eraseStyleId, historyEligible())
        } else if (cursorRow < buffer.rows - 1) {
            cursorRow++
        }
    }

    private fun reverseIndex() {
        pendingWrap = false
        if (cursorRow == scrollTop) buffer.scrollDown(scrollTop, scrollBottom, 1, eraseStyleId)
        else if (cursorRow > 0) cursorRow--
    }

    /**
     * 只有"主屏 + 滚动区就是整屏"时滚出去的行才进回滚缓冲。
     *
     * vi 把底部一行留作状态栏时滚动区是 `1;23`，那种滚动**不该**进历史，
     * 否则用户上滑翻到的全是状态栏碎片。备用屏则永远没有历史。
     */
    private fun historyEligible(): Boolean =
        !buffer.altActive && scrollTop == 0 && scrollBottom == buffer.rows - 1

    private fun insertLines(n: Int) {
        if (cursorRow < scrollTop || cursorRow > scrollBottom) return
        buffer.scrollDown(cursorRow, scrollBottom, n, eraseStyleId)
        cursorCol = 0
        pendingWrap = false
    }

    private fun deleteLines(n: Int) {
        if (cursorRow < scrollTop || cursorRow > scrollBottom) return
        buffer.scrollUp(cursorRow, scrollBottom, n, eraseStyleId, toHistory = false)
        cursorCol = 0
        pendingWrap = false
    }

    private fun eraseDisplay(mode: Int) {
        pendingWrap = false
        when (mode) {
            0 -> {
                buffer.blankRun(cursorRow, cursorCol, buffer.cols, eraseStyleId)
                buffer.blankRows(cursorRow + 1, buffer.rows, eraseStyleId)
            }
            1 -> {
                buffer.blankRows(0, cursorRow, eraseStyleId)
                buffer.blankRun(cursorRow, 0, cursorCol + 1, eraseStyleId)
            }
            2 -> buffer.blankRows(0, buffer.rows, eraseStyleId)
            3 -> {
                // 新版 `clear` 会发这个：连回滚一起清。
                buffer.clearHistory()
                viewTop = 0
                followBottom = true
            }
        }
    }

    private fun eraseLine(mode: Int) {
        pendingWrap = false
        when (mode) {
            0 -> buffer.blankRun(cursorRow, cursorCol, buffer.cols, eraseStyleId)
            1 -> buffer.blankRun(cursorRow, 0, cursorCol + 1, eraseStyleId)
            2 -> buffer.blankRun(cursorRow, 0, buffer.cols, eraseStyleId)
        }
    }

    private fun setScrollRegion() {
        val top = paramMin1(0) - 1
        val bottom = param(1, buffer.rows) - 1
        if (top >= bottom || top < 0 || bottom >= buffer.rows) {
            // 畸形区域整个丢弃，退回整屏。照 xterm：不合法就当没设过。
            scrollTop = 0
            scrollBottom = buffer.rows - 1
        } else {
            scrollTop = top
            scrollBottom = bottom
        }
        // DECSTBM 之后光标回原点（原点模式下是区域左上角）。
        cursorRow = if (decom) scrollTop else 0
        cursorCol = 0
        pendingWrap = false
    }

    private fun repeatLast(n: Int) {
        // REP：重复上一个可打印字符。取当前光标左边那一格。
        var col = cursorCol - 1
        if (pendingWrap) col = cursorCol
        if (col < 0) return
        if (buffer.isTrail(cursorRow, col)) col--
        if (col < 0) return
        val cp = buffer.baseCp(buffer.cpAt(cursorRow, col))
        if (cp <= 0) return
        var k = n
        while (k > 0) {
            printCodePoint(cp)
            k--
        }
    }

    private fun decAlign() {
        // DECALN：整屏铺满 'E'。只有 vttest 之类会用，但实现成本是零。
        for (r in 0 until buffer.rows) {
            for (c in 0 until buffer.cols) buffer.put(r, c, 'E'.code, STYLE_DEFAULT)
        }
        cursorRow = 0
        cursorCol = 0
        pendingWrap = false
    }

    // -----------------------------------------------------------------
    // tab stops
    // -----------------------------------------------------------------

    private fun resetTabStops() {
        if (tabStops.size != buffer.cols) tabStops = BooleanArray(buffer.cols)
        for (i in tabStops.indices) tabStops[i] = i % 8 == 0 && i != 0
    }

    private fun tabForward(n: Int) {
        pendingWrap = false
        var remaining = n
        var c = cursorCol
        val last = buffer.cols - 1
        while (remaining > 0 && c < last) {
            c++
            while (c < last && !tabStops[c]) c++
            remaining--
        }
        cursorCol = c
    }

    private fun tabBackward(n: Int) {
        pendingWrap = false
        var remaining = n
        var c = cursorCol
        while (remaining > 0 && c > 0) {
            c--
            while (c > 0 && !tabStops[c]) c--
            remaining--
        }
        cursorCol = c
    }

    private fun clearTabStop(mode: Int) {
        when (mode) {
            0 -> if (cursorCol < tabStops.size) tabStops[cursorCol] = false
            3 -> java.util.Arrays.fill(tabStops, false)
        }
    }

    // -----------------------------------------------------------------
    // 打印
    // -----------------------------------------------------------------

    private fun printAscii(b: Int) {
        // DEC 特殊图形集（ESC ( 0）：ncurses 的 ACS 画线全走这里。不做的话
        // 所有画框程序显示成 lqqqk，而成本只是一张 32 项的表。
        val cp = if (glGraphics && b >= 0x5F) DEC_GRAPHICS[b - 0x5F] else b
        if (pendingWrap) doWrap()
        if (irm) buffer.insertCells(cursorRow, cursorCol, 1, eraseStyleId)
        buffer.put(cursorRow, cursorCol, cp, styleId)
        if (cursorCol + 1 >= buffer.cols) {
            if (decawm) pendingWrap = true
        } else {
            cursorCol++
        }
    }

    private fun printCodePoint(cp: Int) {
        val w = TerminalWidth.of(cp)
        if (w < 0) return
        if (w == 0) {
            attachCombining(cp)
            return
        }
        if (pendingWrap) doWrap()
        if (w == 2 && cursorCol + 1 >= buffer.cols) {
            // 行末只剩一格塞不下宽字符。开自动换行就换行后再写，
            // 关了就写个空格占位（劈开宽字符是绝对不行的）。
            if (decawm) {
                buffer.setWrapped(cursorRow, true)
                cursorCol = 0
                lineFeedNoCr()
            } else {
                buffer.put(cursorRow, cursorCol, 0x20, styleId)
                return
            }
        }
        if (irm) buffer.insertCells(cursorRow, cursorCol, w, eraseStyleId)
        if (w == 2) buffer.putWide(cursorRow, cursorCol, cp, styleId)
        else buffer.put(cursorRow, cursorCol, cp, styleId)
        if (cursorCol + w >= buffer.cols) {
            cursorCol = buffer.cols - 1
            if (decawm) pendingWrap = true
        } else {
            cursorCol += w
        }
    }

    private fun doWrap() {
        pendingWrap = false
        buffer.setWrapped(cursorRow, true)
        cursorCol = 0
        lineFeedNoCr()
    }

    private fun attachCombining(cp: Int) {
        var c = if (pendingWrap) cursorCol else cursorCol - 1
        if (c < 0) return
        if (buffer.isTrail(cursorRow, c)) c--
        if (c < 0) return
        buffer.appendCombining(cursorRow, c, cp)
    }

    // -----------------------------------------------------------------
    // SGR
    // -----------------------------------------------------------------

    private fun applyStyle() {
        styleId = buffer.styles.intern(curFg, curBg, curFlags)
        // BCE：擦除只继承背景色。
        eraseStyleId = buffer.styles.intern(COLOR_DEFAULT_FG, curBg, 0)
    }

    /**
     * SGR。必须是"游标式逐参消费"而不是 `for each param { switch }`：
     * `38;5;n` 吃 3 个参数、`38;2;r;g;b` 吃 5 个，而且可以出现在一长串中间
     * （`CSI 0;1;38;5;196;48;5;17m`）。逐参 switch 会把 `5`、`196` 当成
     * 独立的 SGR（闪烁、越界色）。
     */
    private fun applySgr() {
        if (paramCount == 0) {
            curFg = COLOR_DEFAULT_FG
            curBg = COLOR_DEFAULT_BG
            curFlags = 0
            applyStyle()
            return
        }
        var i = 0
        while (i < paramCount && i < MAX_PARAMS) {
            val p = param(i, 0)
            when (p) {
                0 -> {
                    curFg = COLOR_DEFAULT_FG
                    curBg = COLOR_DEFAULT_BG
                    curFlags = 0
                }
                1 -> curFlags = curFlags or ATTR_BOLD
                2 -> curFlags = curFlags or ATTR_DIM
                3 -> curFlags = curFlags or ATTR_ITALIC
                4 -> {
                    // 冒号形式 `4:0..4:3`（ISO 8613-6）：新版 gcc / delta 会发。
                    val sub = colonSub(i)
                    curFlags = curFlags and
                            (ATTR_UNDERLINE or ATTR_UNDERLINE_DOUBLE or ATTR_UNDERLINE_CURLY).inv()
                    when (sub) {
                        0 -> Unit
                        2 -> curFlags = curFlags or ATTR_UNDERLINE or ATTR_UNDERLINE_DOUBLE
                        3, 4, 5 -> curFlags = curFlags or ATTR_UNDERLINE or ATTR_UNDERLINE_CURLY
                        else -> curFlags = curFlags or ATTR_UNDERLINE
                    }
                    i = skipColonGroup(i)
                }
                5, 6 -> curFlags = curFlags or ATTR_BLINK
                7 -> curFlags = curFlags or ATTR_INVERSE
                8 -> curFlags = curFlags or ATTR_HIDDEN
                9 -> curFlags = curFlags or ATTR_STRIKE
                21 -> curFlags = curFlags or ATTR_UNDERLINE or ATTR_UNDERLINE_DOUBLE
                22 -> curFlags = curFlags and (ATTR_BOLD or ATTR_DIM).inv()
                23 -> curFlags = curFlags and ATTR_ITALIC.inv()
                24 -> curFlags = curFlags and
                        (ATTR_UNDERLINE or ATTR_UNDERLINE_DOUBLE or ATTR_UNDERLINE_CURLY).inv()
                25 -> curFlags = curFlags and ATTR_BLINK.inv()
                27 -> curFlags = curFlags and ATTR_INVERSE.inv()
                28 -> curFlags = curFlags and ATTR_HIDDEN.inv()
                29 -> curFlags = curFlags and ATTR_STRIKE.inv()
                in 30..37 -> curFg = p - 30
                38 -> {
                    val c = parseExtendedColor(i)
                    if (c != COLOR_UNSET) curFg = c
                    i = colorConsumed
                }
                39 -> curFg = COLOR_DEFAULT_FG
                in 40..47 -> curBg = p - 40
                48 -> {
                    val c = parseExtendedColor(i)
                    if (c != COLOR_UNSET) curBg = c
                    i = colorConsumed
                }
                49 -> curBg = COLOR_DEFAULT_BG
                53 -> curFlags = curFlags or ATTR_OVERLINE
                55 -> curFlags = curFlags and ATTR_OVERLINE.inv()
                58 -> {
                    // 下划线颜色。解析掉但不存 —— 存了也没地方画。
                    parseExtendedColor(i)
                    i = colorConsumed
                }
                59 -> Unit
                in 90..97 -> curFg = p - 90 + 8
                in 100..107 -> curBg = p - 100 + 8
            }
            i++
        }
        applyStyle()
    }

    /** 冒号组的第一个子参数（`4:3` → 3）；没有子参数时返回 -1。 */
    private fun colonSub(i: Int): Int =
        if (i + 1 < paramCount && i + 1 < MAX_PARAMS && paramColon[i + 1]) param(i + 1, 0) else -1

    private fun skipColonGroup(i: Int): Int {
        var j = i
        while (j + 1 < paramCount && j + 1 < MAX_PARAMS && paramColon[j + 1]) j++
        return j
    }

    // parseExtendedColor 的"吃掉了几个参数"通过这个字段回传：Kotlin 没有多返回值
    // 又不想为每个 SGR 分配一个 Pair（彩色输出里这函数一秒钟能被调几万次）。
    private var colorConsumed = 0

    /**
     * `38` / `48` / `58` 之后的颜色。分号形式和冒号形式都要认：
     *  - `38;5;n` / `38;2;r;g;b`
     *  - `38:5:n` / `38:2:r:g:b` / `38:2:cs:r:g:b`（多一个色彩空间 id）
     */
    private fun parseExtendedColor(i: Int): Int {
        val groupEnd = skipColonGroup(i)
        if (groupEnd > i) {
            // 冒号形式：整组一次吃掉。
            colorConsumed = groupEnd
            val kind = param(i + 1, -1)
            return when (kind) {
                5 -> {
                    val n = param(i + 2, -1)
                    if (n in 0..255) n else COLOR_UNSET
                }
                2 -> {
                    // 6 项时中间那个是色彩空间 id，跳过。
                    val base = if (groupEnd - i >= 5) i + 3 else i + 2
                    TerminalColors.rgb(param(base, 0), param(base + 1, 0), param(base + 2, 0))
                }
                else -> COLOR_UNSET
            }
        }
        return when (param(i + 1, -1)) {
            5 -> {
                colorConsumed = i + 2
                val n = param(i + 2, -1)
                if (n in 0..255) n else COLOR_UNSET
            }
            2 -> {
                colorConsumed = i + 4
                TerminalColors.rgb(param(i + 2, 0), param(i + 3, 0), param(i + 4, 0))
            }
            else -> {
                colorConsumed = i
                COLOR_UNSET
            }
        }
    }

    // -----------------------------------------------------------------
    // 模式
    // -----------------------------------------------------------------

    private fun setAnsiModes(set: Boolean) {
        var i = 0
        while (i < paramCount) {
            when (param(i, -1)) {
                4 -> irm = set
                20 -> lnm = set
            }
            i++
        }
    }

    private fun setDecModes(set: Boolean) {
        var i = 0
        while (i < paramCount) {
            when (param(i, -1)) {
                1 -> decckm = set
                5 -> reverseVideo = set
                6 -> {
                    decom = set
                    cursorRow = if (set) scrollTop else 0
                    cursorCol = 0
                    pendingWrap = false
                }
                7 -> {
                    decawm = set
                    pendingWrap = false
                }
                12 -> Unit                       // 光标闪烁：解析并忽略
                25 -> decTcem = set
                45 -> reverseWrap = set
                47 -> switchAlt(set, clearOnEnter = false, saveRestore = false)
                66 -> keypadApp = set            // DECNKM
                1000 -> mouseMode = if (set) 1000 else 0
                1002 -> mouseMode = if (set) 1002 else 0
                1003 -> mouseMode = if (set) 1003 else 0
                1004 -> focusEvents = set
                1005 -> Unit                     // 明确不做（已废弃的 UTF-8 鼠标编码）
                1006 -> mouseSgr = set
                1015 -> Unit                     // urxvt 编码，不做
                1047 -> switchAlt(set, clearOnEnter = false, saveRestore = false)
                1048 -> if (set) saveCursor() else restoreCursor()
                1049 -> switchAlt(set, clearOnEnter = true, saveRestore = true)
                2004 -> bracketedPaste = set
                2026 -> {
                    syncUpdate = set
                    syncHeld = 0
                }
            }
            i++
        }
    }

    // ?1049 之类的私有模式栈（XTSAVE/XTRESTORE）。只存我们真的会改的那几个。
    private var savedModeBits = 0

    private fun saveDecModes() {
        savedModeBits = (if (decckm) 1 else 0) or
                (if (decawm) 2 else 0) or
                (if (decTcem) 4 else 0) or
                (if (bracketedPaste) 8 else 0)
    }

    private fun restoreDecModes() {
        decckm = savedModeBits and 1 != 0
        decawm = savedModeBits and 2 != 0
        decTcem = savedModeBits and 4 != 0
        bracketedPaste = savedModeBits and 8 != 0
    }

    /**
     * 备用屏切换。
     *
     * `?1049h` = 存光标 + 切 alt + 清 alt；`?1049l` = 清 alt + 切回 + 恢复光标。
     * 不做的后果非常显眼："退出 vim 之后 shell 的历史全没了"——因为 vim 的
     * 内容直接盖在主屏上。
     */
    private fun switchAlt(on: Boolean, clearOnEnter: Boolean, saveRestore: Boolean) {
        if (on == buffer.altActive) return
        if (on) {
            if (saveRestore) saveCursor()
            buffer.setAlternate(true)
            if (clearOnEnter) buffer.blankRows(0, buffer.rows, eraseStyleId)
            // 备用屏没有历史，视口必须锁在屏幕上，否则上滑会看到主屏的旧内容
            // 和 alt 屏错位拼接。
            followBottom = true
        } else {
            buffer.clearAlternate(eraseStyleId)
            buffer.setAlternate(false)
            if (saveRestore) restoreCursor()
            followBottom = true
        }
        scrollTop = 0
        scrollBottom = buffer.rows - 1
        pendingWrap = false
        clampCursor()
    }

    private fun clampCursor() {
        cursorRow = cursorRow.coerceIn(0, buffer.rows - 1)
        cursorCol = cursorCol.coerceIn(0, buffer.cols - 1)
    }

    private fun saveCursor() {
        val s = if (buffer.altActive) savedAlt else savedMain
        s.row = cursorRow
        s.col = cursorCol
        s.fg = curFg
        s.bg = curBg
        s.flags = curFlags
        s.g0 = charsets[0]
        s.g1 = charsets[1]
        s.gl = gl
        s.origin = decom
        s.valid = true
    }

    private fun restoreCursor() {
        val s = if (buffer.altActive) savedAlt else savedMain
        if (!s.valid) {
            cursorRow = 0
            cursorCol = 0
            pendingWrap = false
            return
        }
        cursorRow = s.row
        cursorCol = s.col
        curFg = s.fg
        curBg = s.bg
        curFlags = s.flags
        charsets[0] = s.g0
        charsets[1] = s.g1
        gl = s.gl
        decom = s.origin
        refreshGraphics()
        applyStyle()
        pendingWrap = false
        clampCursor()
    }

    /** DECSTR 软复位：程序异常退出后的自救路径，不清屏。 */
    private fun softReset() {
        irm = false
        decom = false
        decawm = true
        decckm = false
        decTcem = true
        lnm = false
        mouseMode = 0
        mouseSgr = false
        bracketedPaste = false
        scrollTop = 0
        scrollBottom = buffer.rows - 1
        curFg = COLOR_DEFAULT_FG
        curBg = COLOR_DEFAULT_BG
        curFlags = 0
        charsets[0] = 'B'.code
        charsets[1] = 'B'.code
        gl = 0
        refreshGraphics()
        applyStyle()
        savedMain.valid = false
        savedAlt.valid = false
        pendingWrap = false
    }

    private fun hardReset() {
        softReset()
        buffer.reset()
        cursorRow = 0
        cursorCol = 0
        keypadApp = false
        reverseVideo = false
        cursorShape = 0
        resetTabStops()
        applyStyle()
        viewTop = 0
        followBottom = true
    }

    // -----------------------------------------------------------------
    // 回复
    // -----------------------------------------------------------------

    private fun respond(s: String) {
        val h = host ?: return
        h.onResponse(s.toByteArray(Charsets.UTF_8))
    }

    private fun deviceStatus() {
        when (param(0, 0)) {
            5 -> respond("${ESC}[0n")
            // CPR。**不回复会让部分程序直接阻塞等待**（bash 的 checkwinsize、
            // zsh 的行编辑都会问）。原点模式下要报相对坐标。
            6 -> {
                val r = if (decom) cursorRow - scrollTop else cursorRow
                respond("${ESC}[${r + 1};${cursorCol + 1}R")
            }
        }
    }

    private fun decDeviceStatus() {
        when (param(0, 0)) {
            6 -> {
                val r = if (decom) cursorRow - scrollTop else cursorRow
                respond("${ESC}[?${r + 1};${cursorCol + 1}R")
            }
            // 其余（打印机、UDK 状态）一律不回：回错比不回更糟。
        }
    }

    /** DECRQM：`CSI ? Ps $ p` → `CSI ? Ps ; Pv $ y`。1 = 已置位，2 = 已复位。 */
    private fun reportMode() {
        val m = param(0, 0)
        val v = when (m) {
            1 -> if (decckm) 1 else 2
            6 -> if (decom) 1 else 2
            7 -> if (decawm) 1 else 2
            25 -> if (decTcem) 1 else 2
            1049 -> if (buffer.altActive) 1 else 2
            2004 -> if (bracketedPaste) 1 else 2
            2026 -> if (syncUpdate) 1 else 2
            else -> 0   // 0 = 不认识这个模式
        }
        respond("${ESC}[?$m;$v\$y")
    }

    // -----------------------------------------------------------------
    // OSC / 字符串态
    // -----------------------------------------------------------------

    private fun oscByte(b: Int) {
        if (strEsc) {
            strEsc = false
            if (b == 0x5C) {          // ESC \ = ST
                oscDispatch()
                state = ST_GROUND
                return
            }
            // ESC 后面跟的不是反斜杠：字符串就此结束，这个字节按新序列处理。
            oscDispatch()
            startEsc()
            handleByte(b)
            return
        }
        when {
            b == 0x07 -> {            // BEL 也是合法终止符
                oscDispatch()
                state = ST_GROUND
            }
            b == 0x1B -> strEsc = true
            b < 0x20 -> Unit          // 其它 C0 在 OSC 里直接丢
            b < 0x80 -> oscAppend(b)
            else -> utf8Start(b)      // 标题可以是中文
        }
    }

    private fun stringByte(b: Int) {
        // DCS / APC / PM / SOS：内容全丢，只找终止符。
        if (strEsc) {
            strEsc = false
            if (b == 0x5C) {
                state = ST_GROUND
                return
            }
            startEsc()
            handleByte(b)
            return
        }
        if (b == 0x1B) strEsc = true
        else if (b == 0x07) state = ST_GROUND
    }

    private fun oscAppend(cp: Int) {
        if (state == ST_STRING) return
        if (oscLen + 2 > oscBuf.size) {
            // 超限之后转入"吃到终止符为止但丢内容"，防一个不发 ST 的程序把内存吃光。
            oscOverflow = true
            return
        }
        if (cp < 0x10000) {
            oscBuf[oscLen++] = cp.toChar()
        } else {
            val v = cp - 0x10000
            oscBuf[oscLen++] = (0xD800 + (v ushr 10)).toChar()
            oscBuf[oscLen++] = (0xDC00 + (v and 0x3FF)).toChar()
        }
    }

    private fun oscDispatch() {
        if (oscOverflow || oscLen == 0) {
            oscLen = 0
            oscOverflow = false
            return
        }
        val s = String(oscBuf, 0, oscLen)
        oscLen = 0
        var sep = s.indexOf(';')
        if (sep < 0) sep = s.length
        val code = s.substring(0, sep).toIntOrNull() ?: -1
        val body = if (sep < s.length) s.substring(sep + 1) else ""
        when (code) {
            // 0/1/2 = 标题。**即使不显示也必须完整消费** —— bash 的
            // PROMPT_COMMAND 默认就发 OSC 0，漏一个终止符标题就喷到屏幕上。
            0, 2 -> host?.onTitle(body)
            1 -> Unit
            4 -> answerPaletteQuery(body)
            7 -> host?.onWorkingDirectory(body)
            8 -> Unit                       // 超链接：先只消费
            10 -> if (body.startsWith("?")) respondColor(10, TerminalColors.DEFAULT_FOREGROUND)
            11 -> if (body.startsWith("?")) respondColor(11, TerminalColors.DEFAULT_BACKGROUND)
            12 -> if (body.startsWith("?")) respondColor(12, TerminalColors.DEFAULT_CURSOR)
            52 -> handleClipboard(body)
            104, 105, 110, 111, 112 -> Unit // 复位调色板：消费
        }
    }

    /**
     * `OSC 4;n;?` 查色。neovim / bat 会拿它探测背景色来选主题，**不回答会让
     * 它们等超时**（约 100 ms 的启动卡顿），所以哪怕回一个近似值也比不回强。
     */
    private fun answerPaletteQuery(body: String) {
        val parts = body.split(';')
        if (parts.size < 2 || parts[1] != "?") return
        val idx = parts[0].toIntOrNull() ?: return
        if (idx !in 0..255) return
        val argb = TerminalColors.DEFAULT_PALETTE[idx]
        respond("${ESC}]4;$idx;${rgbSpec(argb)}${ESC}\\")
    }

    private fun respondColor(code: Int, argb: Int) {
        respond("${ESC}]$code;${rgbSpec(argb)}${ESC}\\")
    }

    /**
     * xterm 的 `rgb:RRRR/GGGG/BBBB` 格式（16 位分量，我们把 8 位重复一遍）。
     * 手写十六进制而不是 String.format：后者跟 Locale 走，阿拉伯语环境下
     * 会输出非 ASCII 数字，对端解析直接失败。
     */
    private fun rgbSpec(argb: Int): String {
        val sb = StringBuilder(20)
        sb.append("rgb:")
        var shift = 16
        while (shift >= 0) {
            val v = (argb ushr shift) and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0xF])
            sb.append(HEX[v ushr 4]).append(HEX[v and 0xF])
            if (shift > 0) sb.append('/')
            shift -= 8
        }
        return sb.toString()
    }

    private fun handleClipboard(body: String) {
        val sep = body.indexOf(';')
        if (sep < 0) return
        val payload = body.substring(sep + 1)
        // 查询剪贴板一律不回：让终端里的任意程序读走用户剪贴板是个安全洞。
        if (payload == "?" || payload.isEmpty()) return
        val text = runCatching {
            String(java.util.Base64.getDecoder().decode(payload), Charsets.UTF_8)
        }.getOrNull() ?: return
        host?.onClipboard(text)
    }

    // =================================================================
    // 对外读接口
    // =================================================================

    /**
     * 填一帧快照。[out] 由调用方 `remember(cols, rows)` 分配一次反复用，
     * 每帧零分配。
     *
     * @param topLine 视口顶部的绝对行号；传 -1 用终端自己维护的视口
     *                （跟随底部 / 用户上滚的位置都在里面）。
     */
    fun snapshot(out: TerminalSnapshot, topLine: Int = -1, rowCount: Int = -1) {
        synchronized(lock) {
            val rc = if (rowCount <= 0) buffer.rows else rowCount
            val top = if (topLine < 0) viewTop else topLine
            buffer.fillSnapshot(top, rc, out)

            val meta = out.meta
            val actualTop = meta[TerminalSnapshot.META_TOP_LINE]
            val absCursor = buffer.historyLines + cursorRow
            val visRow = absCursor - actualTop
            if (visRow in 0 until meta[TerminalSnapshot.META_ROWS]) {
                meta[TerminalSnapshot.META_CURSOR_ROW] = visRow
                meta[TerminalSnapshot.META_CURSOR_COL] = cursorCol
            } else {
                meta[TerminalSnapshot.META_CURSOR_ROW] = -1
            }
            var cf = if (decTcem) TerminalSnapshot.CURSOR_VISIBLE else 0
            if (buffer.isTrail(cursorRow, cursorCol)) cf = cf or TerminalSnapshot.CURSOR_ON_WIDE_TRAIL
            cf = cf or ((cursorShape and 0xF) shl 4)
            meta[TerminalSnapshot.META_CURSOR_FLAGS] = cf

            var mf = 0
            if (buffer.altActive) mf = mf or TerminalSnapshot.MODE_ALT_SCREEN
            if (decckm) mf = mf or TerminalSnapshot.MODE_APP_CURSOR_KEYS
            if (bracketedPaste) mf = mf or TerminalSnapshot.MODE_BRACKETED_PASTE
            if (decawm) mf = mf or TerminalSnapshot.MODE_AUTO_WRAP
            if (mouseMode != 0) mf = mf or TerminalSnapshot.MODE_MOUSE_ANY
            if (reverseVideo) mf = mf or TerminalSnapshot.MODE_REVERSE_VIDEO
            meta[TerminalSnapshot.META_MODE_FLAGS] = mf
            meta[TerminalSnapshot.META_REVISION] = revision
        }
    }

    /**
     * 选区文本。绝对行号，闭区间。
     * 自动换行处不插换行符 —— 复制一条被折行的长命令，粘出去必须还是一条。
     */
    fun selectionText(line0: Int, col0: Int, line1: Int, col1: Int): String =
        synchronized(lock) { buffer.text(line0, col0, line1, col1, joinWrapped = true) }

    /**
     * 可见屏幕的纯文本转储，一个物理行一条。
     * 给 AI 工具看屏幕用（`terminal_snapshot` 直接吐这个就行）。
     */
    fun screenText(): String = synchronized(lock) { buffer.screenText() }

    /** 任意一段历史 + 屏幕的转储，按物理行。给"把最近 N 行喂给 AI"用。 */
    fun dumpText(fromLine: Int, toLine: Int): String =
        synchronized(lock) { buffer.text(fromLine, 0, toLine, buffer.cols - 1, joinWrapped = false) }

    fun cellForeground(row: Int, col: Int): Int =
        synchronized(lock) { buffer.styles.fg(buffer.styleAt(row, col)) }

    fun cellBackground(row: Int, col: Int): Int =
        synchronized(lock) { buffer.styles.bg(buffer.styleAt(row, col)) }

    fun cellFlags(row: Int, col: Int): Int =
        synchronized(lock) { buffer.styles.flags(buffer.styleAt(row, col)) }

    val cursorPosition: Int get() = synchronized(lock) { (cursorRow shl 16) or (cursorCol and 0xFFFF) }

    // =================================================================
    // 视口
    // =================================================================

    /** 视口顶部的绝对行号。 */
    val viewportTop: Int get() = synchronized(lock) { viewTop }

    /** 视口是否贴在底部（贴底时新输出自动跟随）。 */
    val atBottom: Boolean get() = synchronized(lock) { followBottom }

    fun scrollTo(line: Int) {
        synchronized(lock) {
            val max = buffer.totalLines - buffer.rows
            val clamped = line.coerceIn(0, if (max > 0) max else 0)
            if (clamped == viewTop && followBottom == (clamped >= max)) return
            viewTop = clamped
            followBottom = clamped >= max
            revision++
        }
    }

    fun scrollBy(delta: Int) {
        scrollTo(synchronized(lock) { viewTop } + delta)
    }

    fun scrollToBottom() {
        scrollTo(Int.MAX_VALUE)
    }

    // =================================================================
    // resize
    // =================================================================

    /**
     * 改尺寸。列数变了会做真 rewrap（O(总 cell)）。
     *
     * **调用方必须 debounce（约 120 ms）且只在 cols/rows 真的变了时才调**：
     * Compose 的 IME 弹出动画会在 200 ms 内触发几十次 onSizeChanged，
     * 每次全量 rewrap 就是几百毫秒的卡顿。
     */
    fun resize(newCols: Int, newRows: Int) {
        synchronized(lock) {
            val nc = if (newCols < 2) 2 else newCols
            val nr = if (newRows < 1) 1 else newRows
            if (nc == buffer.cols && nr == buffer.rows) return
            resizeMarks[0] = buffer.historyLines + cursorRow
            resizeMarks[1] = cursorCol
            resizeMarks[2] = viewTop
            resizeMarks[3] = 0
            buffer.resize(nc, nr, resizeMarks)
            cursorRow = (resizeMarks[0] - buffer.historyLines).coerceIn(0, buffer.rows - 1)
            cursorCol = resizeMarks[1].coerceIn(0, buffer.cols - 1)
            viewTop = resizeMarks[2]
            // 尺寸变了滚动区一律回到整屏（xterm 行为）：留着旧的 bottom
            // 会让变高之后的底部几行永远滚不到。
            scrollTop = 0
            scrollBottom = buffer.rows - 1
            pendingWrap = false
            resetTabStops()
            if (followBottom) {
                val max = buffer.totalLines - buffer.rows
                viewTop = if (max > 0) max else 0
            } else {
                viewTop = viewTop.coerceIn(0, maxOf(0, buffer.totalLines - buffer.rows))
            }
            revision++
        }
    }

    fun reset() {
        synchronized(lock) {
            hardReset()
            revision++
        }
    }

    // =================================================================
    // 按键 → 字节
    // =================================================================

    /**
     * 把一次按键编码成要写进 pty 的字节。
     *
     * 编码依赖 DECCKM / DECKPAM / `?2004` 这些**由程序设置的模式**，所以必须
     * 在这里做而不是在 UI 层：把模式同步给 UI 必然产生"UI 拿着陈旧模式"的 bug，
     * 症状是 vi 里方向键偶发失灵。
     *
     * @param key      [KEY_CHAR] 表示普通字符（看 [codePoint]），其余见 `KEY_*`
     * @param mods     [MOD_SHIFT] / [MOD_ALT] / [MOD_CTRL] 的位或
     * @return null = 这个键不产生任何输出
     */
    fun encodeKey(key: Int, mods: Int = 0, codePoint: Int = -1): ByteArray? {
        val s = synchronized(lock) { encodeKeyLocked(key, mods, codePoint) } ?: return null
        return s.toByteArray(Charsets.UTF_8)
    }

    private fun encodeKeyLocked(key: Int, mods: Int, codePoint: Int): String? {
        val alt = mods and MOD_ALT != 0
        val ctrl = mods and MOD_CTRL != 0
        val shift = mods and MOD_SHIFT != 0
        // xterm 的修饰符参数：1 + shift(1) + alt(2) + ctrl(4)
        val modParam = 1 + (if (shift) 1 else 0) + (if (alt) 2 else 0) + (if (ctrl) 4 else 0)
        val plain = modParam == 1

        fun esc(body: String) = if (alt && key != KEY_CHAR) "${ESC}$body" else body

        return when (key) {
            KEY_CHAR -> {
                if (codePoint < 0) return null
                val body = if (ctrl) {
                    controlChar(codePoint) ?: return null
                } else {
                    String(Character.toChars(codePoint))
                }
                // Alt 前缀一个 ESC（xterm 的 metaSendsEscape）：readline 的
                // Alt-b / Alt-f、vim 的 Alt 映射全指望这个。
                if (alt) "${ESC}$body" else body
            }
            // 光标键：DECCKM 决定发 CSI 还是 SS3。不做的话 vi 里方向键失灵。
            KEY_UP -> cursorKey('A', plain, modParam)
            KEY_DOWN -> cursorKey('B', plain, modParam)
            KEY_RIGHT -> cursorKey('C', plain, modParam)
            KEY_LEFT -> cursorKey('D', plain, modParam)
            KEY_HOME -> cursorKey('H', plain, modParam)
            KEY_END -> cursorKey('F', plain, modParam)
            KEY_INSERT -> tildeKey(2, plain, modParam)
            KEY_DELETE -> tildeKey(3, plain, modParam)
            KEY_PAGE_UP -> tildeKey(5, plain, modParam)
            KEY_PAGE_DOWN -> tildeKey(6, plain, modParam)
            KEY_ENTER -> esc(if (lnm) "\r\n" else "\r")
            KEY_TAB -> if (shift) "${ESC}[Z" else esc("\t")
            // 退格发 DEL(0x7F) 不是 BS(0x08)：terminfo 里 xterm 的 kbs 就是 \177，
            // 发 0x08 的话 bash 行编辑删不掉字符，只是把光标往左挪。
            KEY_BACKSPACE -> esc(if (ctrl) "\b" else DEL)
            KEY_ESCAPE -> "${ESC}"
            KEY_F1 -> functionKey(0, plain, modParam)
            KEY_F2 -> functionKey(1, plain, modParam)
            KEY_F3 -> functionKey(2, plain, modParam)
            KEY_F4 -> functionKey(3, plain, modParam)
            KEY_F5 -> tildeKey(15, plain, modParam)
            KEY_F6 -> tildeKey(17, plain, modParam)
            KEY_F7 -> tildeKey(18, plain, modParam)
            KEY_F8 -> tildeKey(19, plain, modParam)
            KEY_F9 -> tildeKey(20, plain, modParam)
            KEY_F10 -> tildeKey(21, plain, modParam)
            KEY_F11 -> tildeKey(23, plain, modParam)
            KEY_F12 -> tildeKey(24, plain, modParam)
            else -> null
        }
    }

    private fun cursorKey(final: Char, plain: Boolean, modParam: Int): String = when {
        !plain -> "${ESC}[1;$modParam$final"
        decckm -> "${ESC}O$final"
        else -> "${ESC}[$final"
    }

    private fun tildeKey(n: Int, plain: Boolean, modParam: Int): String =
        if (plain) "${ESC}[$n~" else "${ESC}[$n;$modParam~"

    private fun functionKey(i: Int, plain: Boolean, modParam: Int): String {
        val final = "PQRS"[i]
        return if (plain) "${ESC}O$final" else "${ESC}[1;$modParam$final"
    }

    private fun controlChar(cp: Int): String? {
        val c = when {
            cp in 'a'.code..'z'.code -> cp - 'a'.code + 1
            cp in 'A'.code..'Z'.code -> cp - 'A'.code + 1
            cp == ' '.code || cp == '@'.code -> 0
            cp == '['.code -> 27
            cp == '\\'.code -> 28
            cp == ']'.code -> 29
            cp == '^'.code -> 30
            cp == '_'.code -> 31
            cp == '?'.code -> 127
            else -> return null
        }
        return c.toChar().toString()
    }

    /**
     * 粘贴。
     *
     * 括号粘贴（`?2004`）不做的话，往 vim 里粘一段带缩进的代码会触发自动缩进
     * 灾难（每行都在前一行基础上再缩一层）。另外**必须把 ESC 滤掉**：
     * 否则剪贴板里的转义序列等于让任意内容驱动终端。
     */
    fun encodePaste(text: String): ByteArray {
        val wrap = synchronized(lock) { bracketedPaste }
        val sb = StringBuilder(text.length + 16)
        if (wrap) sb.append("${ESC}[200~")
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                ch == '\r' -> {
                    sb.append('\r')
                    if (i + 1 < text.length && text[i + 1] == '\n') i++
                }
                ch == '\n' -> sb.append('\r')
                ch == Char(0x1B) -> Unit
                else -> sb.append(ch)
            }
            i++
        }
        if (wrap) sb.append("${ESC}[201~")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * 焦点变化上报（`?1004`）。vim / tmux 用它决定要不要重绘和自动保存。
     * 程序没开这个模式就返回 null。
     */
    fun encodeFocus(focused: Boolean): ByteArray? {
        val s = synchronized(lock) {
            if (!focusEvents) return@synchronized null
            // ESC 写成 ${ESC} 而不是源码里的裸 0x1B 字节：裸控制符会被很多工具
            // （格式化器 / patch / 剪贴板）静默吃掉，一旦丢了就是把字面量 "[I" 敲进 shell 命令行。
            if (focused) "${ESC}[I" else "${ESC}[O"
        } ?: return null
        return s.toByteArray(Charsets.US_ASCII)
    }

    /**
     * 鼠标 / 触摸上报。触屏设备上这是"点一下把 vim 光标放过去"的唯一途径。
     *
     * 优先 SGR 扩展编码（`?1006`）：老的 X10 编码列号超过 223 就溢出，
     * 手机横屏很容易超。
     *
     * @param button 0=左 1=中 2=右 64=滚轮上 65=滚轮下
     * @param col/row 0 起的网格坐标
     * @return null = 程序没有开启鼠标上报（或这个事件当前模式不关心）
     */
    fun encodeMouse(button: Int, col: Int, row: Int, pressed: Boolean, motion: Boolean = false): ByteArray? {
        val s = synchronized(lock) {
            if (mouseMode == 0) return@synchronized null
            if (motion && mouseMode == 1000) return@synchronized null
            var b = button
            if (motion) b += 32
            val x = col + 1
            val y = row + 1
            if (mouseSgr) {
                "${ESC}[<$b;$x;$y${if (pressed) 'M' else 'm'}"
            } else {
                // X10：坐标 +32 塞进一个字节，超过 223 就没法表达了，直接丢弃
                // 比发一个错的坐标好（错的坐标会让 vim 把光标扔到屏幕外）。
                if (x > 223 || y > 223) return@synchronized null
                val code = if (pressed) b else 3
                "${ESC}[M${(32 + code).toChar()}${(32 + x).toChar()}${(32 + y).toChar()}"
            }
        } ?: return null
        return s.toByteArray(Charsets.UTF_8)
    }

    // =================================================================
    companion object {
        // 解析器状态。命名和 Williams 状态图一致，方便对着图 review。
        private const val ST_GROUND = 0
        private const val ST_ESC = 1
        private const val ST_ESC_INTER = 2
        private const val ST_CSI_ENTRY = 3
        private const val ST_CSI_PARAM = 4
        private const val ST_CSI_INTER = 5
        private const val ST_CSI_IGNORE = 6
        private const val ST_OSC = 7
        private const val ST_STRING = 8   // DCS / APC / PM / SOS 的载荷，只吃不解析

        private const val MAX_PARAMS = 32
        private const val OSC_MAX = 4096

        /** ?2026 最多压住多少次 feed 不刷新。防止程序死在同步区间里让屏幕冻住。 */
        private const val SYNC_MAX_FEEDS = 32

        private val HEX = charArrayOf(
            '0', '1', '2', '3', '4', '5', '6', '7',
            '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
        )
        private const val COLOR_UNSET = Int.MIN_VALUE + 1

        /** DA1：宣称 VT220 级 + 132 列 + 选择性擦除 + 彩色 + 技术字符 + ANSI 颜色。 */
        private val DA1 = "${ESC}[?62;1;6;9;15;22c"

        // ---- encodeKey 的键码 ----
        const val KEY_CHAR = 0
        const val KEY_UP = 1
        const val KEY_DOWN = 2
        const val KEY_RIGHT = 3
        const val KEY_LEFT = 4
        const val KEY_HOME = 5
        const val KEY_END = 6
        const val KEY_INSERT = 7
        const val KEY_DELETE = 8
        const val KEY_PAGE_UP = 9
        const val KEY_PAGE_DOWN = 10
        const val KEY_ENTER = 11
        const val KEY_TAB = 12
        const val KEY_BACKSPACE = 13
        const val KEY_ESCAPE = 14
        const val KEY_F1 = 20
        const val KEY_F2 = 21
        const val KEY_F3 = 22
        const val KEY_F4 = 23
        const val KEY_F5 = 24
        const val KEY_F6 = 25
        const val KEY_F7 = 26
        const val KEY_F8 = 27
        const val KEY_F9 = 28
        const val KEY_F10 = 29
        const val KEY_F11 = 30
        const val KEY_F12 = 31

        const val MOD_SHIFT = 1
        const val MOD_ALT = 2
        const val MOD_CTRL = 4

        /**
         * DEC 特殊图形字符集（`ESC ( 0`），0x5F..0x7E 共 32 项。
         * ncurses 的 ACS 画线走的就是它。
         */
        private val DEC_GRAPHICS = intArrayOf(
            0x0020, 0x25C6, 0x2592, 0x2409, 0x240C, 0x240D, 0x240A, 0x00B0,
            0x00B1, 0x2424, 0x240B, 0x2518, 0x2510, 0x250C, 0x2514, 0x253C,
            0x23BA, 0x23BB, 0x2500, 0x23BC, 0x23BD, 0x251C, 0x2524, 0x2534,
            0x252C, 0x2502, 0x2264, 0x2265, 0x03C0, 0x2260, 0x00A3, 0x00B7
        )

        /**
         * [selfTest] 的进程级门控。全进程只跑一次，拿到 false 的调用方必须走
         * 明确的降级路径（见 `nativebridge/NativeGate.vt` 的说明），
         * 而不是继续拿这个模拟器画屏 —— 它失败时的症状是"画出来的东西看着
         * 像对的，其实错位"，没有异常、没有日志，靠肉眼根本发现不了。
         *
         * `by lazy` 放在这里而不是 [com.biji.notes.nativebridge.NativeGate]：
         * `nativebridge` 已经引用 `terminal`（转发到这个属性），反过来再让
         * `terminal` 依赖 `nativebridge` 就是一个包级环。
         *
         * 也不存在 NativeGate 文档里说的那种自锁：[selfTest] 自己 new 全新的
         * 小模拟器实例，不碰任何被门控的对象。
         */
        val usable: Boolean by lazy { selfTest() }

        /**
         * 自检。**不启动任何进程**，喂一段脚本化字节流然后比对结果。
         *
         * 样本刻意覆盖本项目最容易静默出错的几个维度（选样思路抄
         * `NativeText.selfTest`）：宽字符列数、代理对、分片输入、
         * SGR 颜色、EL、备用屏往返。任何一处退化成"按 UTF-16 长度算列"
         * 或"状态不跨 feed 保持"，这里会立刻失败而不是等到用户看到花屏。
         */
        fun selfTest(): Boolean = runCatching {
            val e = TerminalEmulator(20, 4, scrollbackLines = 8)

            // 中文占 2 列、emoji 是代理对且占 2 列 → 'b' 必须落在第 5 列。
            e.feed("a中😀b")
            if (e.cursorPosition != (0 shl 16 or 6)) return@runCatching false
            if (e.screenText().lineSequence().first() != "a中😀b") return@runCatching false

            // CUP + SGR 31 + EL。CUP 是 1 起的，(2,3) → 屏幕 (1,2)。
            e.feed("${ESC}[2;3H${ESC}[31mX${ESC}[0m${ESC}[K")
            if (e.cellForeground(1, 2) != 1) return@runCatching false
            if (e.cursorPosition != (1 shl 16 or 3)) return@runCatching false

            // 分片：一条 SGR 被劈成三段，状态必须跨 feed 保持。
            e.feed("${ESC}[3")
            e.feed("2")
            e.feed("mG")
            if (e.cellForeground(1, 3) != 2) return@runCatching false

            // 备用屏往返：主屏内容必须原样还在。
            val before = e.screenText()
            e.feed("${ESC}[?1049h")
            e.feed("${ESC}[HZZZ")
            e.feed("${ESC}[?1049l")
            if (e.screenText() != before) return@runCatching false

            // 延迟换行：正好写满一行不能立刻换行。
            val w = TerminalEmulator(4, 3, scrollbackLines = 4)
            w.feed("abcd")
            if (w.cursorPosition != (0 shl 16 or 3)) return@runCatching false
            w.feed("e")
            if (w.cursorPosition != (1 shl 16 or 1)) return@runCatching false

            // 非法 UTF-8 不能吞掉后面的 ESC：0xC3 后面直接跟 ESC 序列。
            val u = TerminalEmulator(8, 2, scrollbackLines = 4)
            u.feed(byteArrayOf(0xC3.toByte(), 0x1B, '['.code.toByte(), '3'.code.toByte(),
                '1'.code.toByte(), 'm'.code.toByte(), 'Z'.code.toByte()))
            u.cellForeground(0, 1) == 1
        }.getOrDefault(false)
    }
}
