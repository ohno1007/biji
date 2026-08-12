package com.biji.notes.terminal

/**
 * 终端的颜色 / 文本属性编码，以及 256 色调色板。
 *
 * 这里**刻意不 import 任何 Compose 类型**，颜色一律是 ARGB `Int`。
 * 沿用 `NativeHighlight` / `HighlightPalette` 的既定分工：缓冲区里存的是
 * *语义*（"第 4 号色"、"粗体"），不是像素；换主题只要换一张调色板，
 * 不需要重跑解析器、更不用动屏幕缓冲。顺带的好处是 terminal 这三个文件
 * 可以在纯 JVM 单测里跑，不需要 Android 运行时。
 *
 * ## 颜色的三种形态挤在一个 Int 里
 * 24 位真彩 + 索引色 + "默认色" 三种情况必须共用一个字段，否则 [StyleTable]
 * 的一条记录就要多两个 int，而 style 表是每个 cell 都要引用的东西。编码：
 *
 * | 取值            | 含义                                            |
 * |-----------------|-------------------------------------------------|
 * | `0 .. 255`      | 调色板索引（0-15 基本色 / 16-231 色立方 / 232-255 灰阶） |
 * | [COLOR_DEFAULT_FG] / [COLOR_DEFAULT_BG] | 默认前景 / 背景，跟主题走 |
 * | `< 0`（bit31=1）| 低 24 位是 0xRRGGBB 真彩                        |
 *
 * 用负数表示真彩而不是另开一个 flag，是因为 `c < 0` 这一次比较就能分流，
 * 渲染热路径上每个 run 要判两次（fg/bg）。
 *
 * ## 反显（SGR 7）为什么不在这里
 * 反显必须**在渲染时**解析，绝不能在写入 cell 时就把 fg/bg 交换掉 ——
 * 那样 `SGR 27`（关反显）就撤不回来了，屏幕上会留下一片永久反色。
 * 所以 [ATTR_INVERSE] 是一个 flag，交换发生在快照序列化那一步
 * （见 `TerminalBuffer.fillSnapshot`），Kotlin 渲染侧拿到的已经是交换后的结果。
 */
object TerminalColors {

    // ---------------------------------------------------------------
    // 颜色编码
    // ---------------------------------------------------------------

    /** 默认前景。不是 0 号色（黑），主题换成浅色时这两者天差地别。 */
    const val COLOR_DEFAULT_FG = 256

    /** 默认背景。 */
    const val COLOR_DEFAULT_BG = 257

    /** bit31。Kotlin 里 `1 shl 31` 就是 Int.MIN_VALUE，判断真彩用 `c < 0` 最省。 */
    private const val RGB_MARK = 1 shl 31

    /** 24 位真彩（SGR 38;2;r;g;b）。分量自动 clamp 到 0..255，畸形参数不会串到别的通道。 */
    fun rgb(r: Int, g: Int, b: Int): Int =
        RGB_MARK or (clamp8(r) shl 16) or (clamp8(g) shl 8) or clamp8(b)

    /** 是否是真彩色（相对于调色板索引 / 默认色）。 */
    fun isRgb(color: Int): Boolean = color < 0

    /** 调色板索引；不是索引色时返回 -1。 */
    fun indexOf(color: Int): Int = if (color in 0..255) color else -1

    private fun clamp8(v: Int): Int = if (v < 0) 0 else if (v > 255) 255 else v

    // ---------------------------------------------------------------
    // 属性位
    //
    // 全部按 SGR 的语义存成 flag，渲染侧自己决定画不画（例如闪烁我们建议
    // 永远不真闪：那等于每 500ms 强制一帧，纯耗电）。
    // ---------------------------------------------------------------

    const val ATTR_BOLD = 1 shl 0
    const val ATTR_DIM = 1 shl 1
    const val ATTR_ITALIC = 1 shl 2
    const val ATTR_UNDERLINE = 1 shl 3
    const val ATTR_BLINK = 1 shl 4
    const val ATTR_INVERSE = 1 shl 5
    const val ATTR_HIDDEN = 1 shl 6
    const val ATTR_STRIKE = 1 shl 7
    const val ATTR_UNDERLINE_DOUBLE = 1 shl 8
    const val ATTR_UNDERLINE_CURLY = 1 shl 9
    const val ATTR_OVERLINE = 1 shl 10

    // ---------------------------------------------------------------
    // 调色板
    // ---------------------------------------------------------------

    /**
     * 0-15 号基本色。取值抄 Termux 默认配色（xterm 的老一套在深色背景下
     * 蓝色几乎看不见，所以 4 号色和 12 号色都被提亮过）。
     * 主题想换就整块换，[buildPalette] 接受任意 16 项前缀。
     */
    val BASE_16 = intArrayOf(
        0xFF000000.toInt(), // 0  black
        0xFFCD3131.toInt(), // 1  red
        0xFF0DBC79.toInt(), // 2  green
        0xFFE5E510.toInt(), // 3  yellow
        0xFF2472C8.toInt(), // 4  blue
        0xFFBC3FBC.toInt(), // 5  magenta
        0xFF11A8CD.toInt(), // 6  cyan
        0xFFE5E5E5.toInt(), // 7  white
        0xFF666666.toInt(), // 8  bright black
        0xFFF14C4C.toInt(), // 9  bright red
        0xFF23D18B.toInt(), // 10 bright green
        0xFFF5F543.toInt(), // 11 bright yellow
        0xFF3B8EEA.toInt(), // 12 bright blue
        0xFFD670D6.toInt(), // 13 bright magenta
        0xFF29B8DB.toInt(), // 14 bright cyan
        0xFFFFFFFF.toInt()  // 15 bright white
    )

    /** 默认前景/背景，和 `TerminalScreen.kt` 现有的黑底浅灰保持一致。 */
    const val DEFAULT_FOREGROUND = 0xFFE6E6E6.toInt()
    const val DEFAULT_BACKGROUND = 0xFF000000.toInt()
    const val DEFAULT_CURSOR = 0xFF6FE26F.toInt()

    /**
     * 建一张 256 项 ARGB 调色板：16 项基本色 + 6×6×6 色立方 + 24 级灰阶。
     *
     * 每帧渲染都要按 index 取色，所以做成一次性构建的 IntArray 而不是函数：
     * 216 项色立方现算是三次除法 + 三次查表，放在每 cell 的路径上不值当。
     */
    fun buildPalette(base16: IntArray = BASE_16): IntArray {
        val p = IntArray(256)
        val n = if (base16.size < 16) base16.size else 16
        for (i in 0 until n) p[i] = base16[i]
        for (i in n until 16) p[i] = 0xFF808080.toInt()
        // 16..231：6×6×6 立方。分量刻度是 xterm 的 0/95/135/175/215/255，
        // 不是均分的 0..255 —— 用均分会让 bat/delta 的浅色背景偏灰。
        val steps = intArrayOf(0, 95, 135, 175, 215, 255)
        var idx = 16
        for (r in 0 until 6) for (g in 0 until 6) for (b in 0 until 6) {
            p[idx++] = 0xFF000000.toInt() or (steps[r] shl 16) or (steps[g] shl 8) or steps[b]
        }
        // 232..255：24 级灰阶，8 + 10*i
        for (i in 0 until 24) {
            val v = 8 + i * 10
            p[idx++] = 0xFF000000.toInt() or (v shl 16) or (v shl 8) or v
        }
        return p
    }

    /** 进程内共用的默认调色板。UI 换主题时自己 `buildPalette(自己的 16 色)`。 */
    val DEFAULT_PALETTE: IntArray by lazy { buildPalette() }

    // ---------------------------------------------------------------
    // 解析成 ARGB
    // ---------------------------------------------------------------

    /**
     * 把 [color] 解析成 ARGB。越界一律回落到 [defaultFg]，绝不抛
     * —— 这条路径在渲染循环里，一次 ArrayIndexOutOfBounds 就是整屏白屏。
     *
     * 两个"默认色"哨兵都要认：反显在快照序列化时已经把 fg/bg 交换过了，
     * 于是**前景字段里出现 [COLOR_DEFAULT_BG] 是正常的**。少认一个的后果是
     * 反显的默认色文字变成一团纯色，vim 的状态栏首当其冲。
     */
    fun toArgb(
        color: Int,
        palette: IntArray = DEFAULT_PALETTE,
        defaultFg: Int = DEFAULT_FOREGROUND,
        defaultBg: Int = DEFAULT_BACKGROUND
    ): Int = when {
        color < 0 -> 0xFF000000.toInt() or (color and 0x00FFFFFF)
        color == COLOR_DEFAULT_FG -> defaultFg
        color == COLOR_DEFAULT_BG -> defaultBg
        color < palette.size -> palette[color]
        else -> defaultFg
    }

    /**
     * 前景色的完整解析：处理"粗体把 0-7 号色提亮成 8-15"和暗淡。
     *
     * 提亮是 xterm/Termux 的历史行为而不是标准：`SGR 1` 本意是加粗字形，
     * 但绝大多数程序（ls、git、grep）拿它当"亮色"用，不提亮的话
     * `ls` 的目录蓝会暗到看不清。**只对 0-7 号索引色提亮**，真彩和
     * 16-255 号保持原样（那些是程序自己算好的颜色，动它必然出错）。
     */
    fun foregroundArgb(
        fg: Int,
        flags: Int,
        palette: IntArray = DEFAULT_PALETTE,
        defaultFg: Int = DEFAULT_FOREGROUND,
        defaultBg: Int = DEFAULT_BACKGROUND
    ): Int {
        var c = fg
        if (flags and ATTR_BOLD != 0 && c in 0..7) c += 8
        val argb = toArgb(c, palette, defaultFg, defaultBg)
        // 暗淡：整体压暗。真终端做法各异，压暗比单独维护一套暗色表省事且不会跑偏。
        return if (flags and ATTR_DIM != 0) blend(argb, 0x66) else argb
    }

    /** 背景色解析。背景不参与粗体提亮 —— 提亮背景会让反显后的文字糊成一团。 */
    fun backgroundArgb(
        bg: Int,
        palette: IntArray = DEFAULT_PALETTE,
        defaultFg: Int = DEFAULT_FOREGROUND,
        defaultBg: Int = DEFAULT_BACKGROUND
    ): Int = toArgb(bg, palette, defaultFg, defaultBg)

    /** 把颜色按 alpha 比例压暗（不引入第二张暗色表）。 */
    private fun blend(argb: Int, alpha: Int): Int {
        val r = ((argb ushr 16) and 0xFF) * alpha / 255
        val g = ((argb ushr 8) and 0xFF) * alpha / 255
        val b = (argb and 0xFF) * alpha / 255
        return (argb and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
    }
}
