package com.biji.notes.ui.terminal

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.biji.notes.terminal.TerminalColors
import com.biji.notes.terminal.TerminalSnapshot
import com.biji.notes.terminal.TerminalWidth
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

// =====================================================================
//  网格渲染。
//
//  ## 为什么不是 LazyColumn + 一行一个 Text
//  终端的行是会被**原地覆写**的（`\r`、CUP、EL、备用屏），而 LazyColumn 的
//  key-diff 模型假定 item 是追加 / 稳定的，两者根本冲突 —— 旧实现里「进度条
//  越长越长」「一行被劈成两条」都是这个错配的症状。
//
//  量化的那一面（80x40 = 3200 格，整屏刷新）：
//   * 逐行 Text(AnnotatedString)：40 个 String + 40 个 AnnotatedString +
//     几百个 SpanStyle + 40 次 StaticLayout 换行计算（我们根本不需要换行，
//     网格已经排好版了）≈ 800-1000 个对象/帧，60fps 下每秒五六万个对象。
//     GC 停顿正好落在用户滑动的时候。
//   * 按 run 的 drawText：整屏典型 150-500 个 run，每个一次 drawRect（默认
//     背景直接跳过）+ 一次 drawText，约 2 ms/帧，**零分配**。
//
//  差的不是两倍，是一个量级 + 分配数 800 vs 0。另外 Text 会做字距调整 /
//  连字 / 字体回退，一旦出现 CJK，第 k 列在不同行的 x 就不再相同 ——
//  而终端的定义就是网格。
//
//  ## 数据从哪来
//  `TerminalSnapshot` 是模拟器按 run 填好的（同一行 + 连续列 + 同一 style
//  为一段），文本已经是 UTF-16 code unit，直接喂
//  `Canvas.drawText(char[], off, len, x, y, paint)`。
//  **从 shell 的字节到屏幕像素，全程不产生一个 String。**
// =====================================================================

/**
 * 配色。黑底 + 标准 ANSI 16 色，[TerminalColors] 已经按 Termux 的默认盘给好了
 * （xterm 那套老色在深色背景下蓝得看不见，4 / 12 号被提亮过）。
 *
 * 颜色一律是 ARGB `Int` 而不是 [Color]：渲染热路径上每个 run 要解析 2 次颜色，
 * `Color` 是 inline class 包 ULong，在这条路上只会多一层拆装。
 */
@Immutable
class TerminalTheme(
    val background: Int = TerminalColors.DEFAULT_BACKGROUND,
    val foreground: Int = TerminalColors.DEFAULT_FOREGROUND,
    val cursor: Int = TerminalColors.DEFAULT_CURSOR,
    val palette: IntArray = TerminalColors.DEFAULT_PALETTE,
    /** 选区高亮。半透明叠加，底下的文字要还能读。 */
    val selection: Int = 0x553B8EEA,
    /** 滚动条（只在离开底部时出现）。 */
    val scrollbar: Int = 0x66FFFFFF
) {
    companion object {
        val Default = TerminalTheme()

        /** 浅色模式。见 [TerminalColors.BASE_16_LIGHT] 里为什么不能只把深色盘调亮。 */
        val Light = TerminalTheme(
            background = TerminalColors.LIGHT_BACKGROUND,
            foreground = TerminalColors.LIGHT_FOREGROUND,
            cursor = TerminalColors.LIGHT_CURSOR,
            palette = TerminalColors.LIGHT_PALETTE,
            selection = 0x552C74D6,
            scrollbar = 0x66000000
        )

        fun of(dark: Boolean): TerminalTheme = if (dark) Default else Light
    }
}

/**
 * 字体几何。
 *
 * **cellW 是 float，绝不取整**：取整后 80 列能累计漂移 40 px。用字体真实的
 * advance 布网格，run 内部由字体自己推进、run 之间由 `col * cellW` 定位，
 * 两者按定义相等。只有背景矩形的右边缘用 ceil 覆盖，避免行列之间露出 1px 缝。
 *
 * cellH 反过来**必须取整**：行高带小数的话，第 20 行的基线会落在半个像素上，
 * 整屏文字忽清忽糊。
 */
@Stable
class TerminalMetrics(val fontPx: Float) {

    /** 渲染用的 Paint。UI 线程独占，绘制时按 run 改 color / flags。 */
    val paint: Paint = Paint().apply {
        // 内置等宽字体是更稳的选择（各家 ROM 的 MONOSPACE 落到不同字体，
        // advance 不确定），但那要往 assets 里塞 200-400 KB，不在本次范围内。
        // 退而求其次：ASCII 用 MONOSPACE 的 advance 定 cellW，CJK / emoji
        // 走系统回退字体但**按格居中绘制**，不跟着回退字体的 advance 走。
        typeface = Typeface.MONOSPACE
        textSize = fontPx
        isAntiAlias = true
        isSubpixelText = true
    }

    private val fillPaint: Paint = Paint().apply { isAntiAlias = false }

    val cellW: Float = paint.measureText("M")
    val cellH: Float
    val baseline: Float
    val descent: Float

    init {
        val fm = paint.fontMetrics
        cellH = kotlin.math.ceil(fm.descent - fm.ascent)
        baseline = -fm.ascent
        descent = fm.descent
    }

    fun columnsFor(widthPx: Float): Int =
        max(MIN_COLS, floor(widthPx / max(1f, cellW)).toInt())

    fun rowsFor(heightPx: Float): Int =
        max(MIN_ROWS, floor(heightPx / max(1f, cellH)).toInt())

    internal fun fill(): Paint = fillPaint

    companion object {
        const val MIN_COLS = 8
        const val MIN_ROWS = 2

        /** Termux 的步进：2 px。同时兼作捏合的去抖阈值。 */
        const val STEP_PX = 2f
        const val MIN_FONT_PX = 9f
        const val MAX_FONT_PX = 64f

        fun clampFont(px: Float): Float = px.coerceIn(MIN_FONT_PX, MAX_FONT_PX)

        /** 捏合时用：按 2px 量化，跨过一档才真的改字号，避免每帧重建 Paint。 */
        fun quantize(px: Float): Float =
            clampFont(Math.round(px / STEP_PX) * STEP_PX)
    }
}

@Composable
fun rememberTerminalMetrics(fontPx: Float): TerminalMetrics =
    remember(fontPx) { TerminalMetrics(fontPx) }

/**
 * 终端网格本体。
 *
 * @param onGridSize 量出真实行列后回调（**已经过 120 ms 去抖**）。调用方拿它
 *        去 resize 会话：Compose 的 IME 弹出动画会在 200 ms 内触发几十次
 *        onSizeChanged，每次都下 TIOCSWINSZ 就是连发 SIGWINCH，vim 会疯狂重绘。
 */
@Composable
fun TerminalCanvas(
    binding: TerminalBinding,
    metrics: TerminalMetrics,
    theme: TerminalTheme,
    selection: TerminalSelection,
    /** 每帧顺手更新，供手势层判断「现在是不是备用屏」。 */
    viewInfo: TerminalViewInfo,
    focused: Boolean,
    onGridSize: (cols: Int, rows: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var sizePx by remember { mutableStateOf(IntSize.Zero) }

    val cols = remember(sizePx, metrics) { metrics.columnsFor(sizePx.width.toFloat()) }
    val rows = remember(sizePx, metrics) { metrics.rowsFor(sizePx.height.toFloat()) }

    // 快照按 (cols, rows) 分配一次，之后每帧原地填充 —— 稳态零分配。
    val snapshot = remember(cols, rows) { TerminalSnapshot(cols, rows) }

    // 去抖后再通知外面 resize。列数没变（只是键盘弹出让高度变了）时，
    // 会话层自己也会跳过 rewrap，这里不重复判断。
    LaunchedEffect(cols, rows, binding) {
        if (sizePx == IntSize.Zero) return@LaunchedEffect
        delay(RESIZE_DEBOUNCE_MS)
        // 放到 IO 上：列数变了会触发整个回滚缓冲的 rewrap（O(总 cell)，
        // 5000 行能到十几毫秒），在主线程上做正好卡在转屏动画里。
        // 会话层的 resize 自带锁，从哪个线程调都安全。
        withContext(Dispatchers.IO) { onGridSize(cols, rows) }
    }

    // 重绘循环。**唤醒源只有一个**：binding.redraw —— 模拟器被写了（pty 出字节 /
    // 用户滚屏 / reset / resize）。没有输出就没有信号，这条协程一直挂着。
    //
    // 之前这里是 `while (isActive) { withFrameNanos {} ... }`，也就是每帧醒一次去
    // 比对 revision。那个写法的账不在轮询本身，在 Recomposer：只要有 withFrameNanos
    // 的等待者，它每帧都会向 Choreographer 申请 vsync，于是终端页只要开着、哪怕
    // 屏幕上一个字都不动，主线程也被钉在 60/90/120 Hz 上做「vsync → 回调 → 读一个
    // volatile → 什么都没变」的空转。现在空闲 = 零等待者 = 不申请 vsync。
    //
    // 保留的两个性质：
    //  * 限流到刷新率：信号来了先 withFrameNanos 走到帧边界再取快照。`yes` 那种
    //    每毫秒一个 chunk 的输出，在我们挂在帧边界的这十几毫秒里被 StateFlow 合并
    //    成一个信号，醒来只画一帧 —— 和以前完全一样，`tail -f` 该来的照样来。
    //  * 后台自动停摆：帧时钟被 Recomposer 在 ON_STOP 时暂停，这里会一直挂在
    //    withFrameNanos 上（后台再来多少输出也只是把 StateFlow 的值改掉，不会
    //    重复唤醒），回到前台的第一帧自动补画最新内容。
    val tick = remember { mutableIntStateOf(0) }
    LaunchedEffect(binding, snapshot, rows) {
        var last = Int.MIN_VALUE
        // StateFlow 会先补发当前值，所以首帧不需要额外的初始化绘制，也不存在
        // 「订阅之前刚好来了一批输出」的窗口。
        binding.redraw.collect {
            withFrameNanos { }
            // 信号响了但画面没变（比如已经在底部时又滚了一次到底）：不重填快照。
            val rev = binding.revision
            if (rev == last) return@collect
            last = rev
            binding.emulator.snapshot(snapshot, rowCount = rows)
            // 手势层要靠它决定滑动是滚回滚缓冲还是发方向键。放在这里更新是
            // 因为「是不是备用屏」只有快照里有，而这是每帧唯一一次拿快照的地方。
            viewInfo.altScreen = snapshot.altScreen
            tick.intValue++
        }
    }

    Canvas(
        modifier = modifier.onSizeChanged { sizePx = it }
    ) {
        // frameTick / 选区这几个读取**不能删**：快照是原地更新的，Compose 根本
        // 看不见它变，全靠在绘制 lambda 里读这几个 state 把本次绘制订阅上去。
        // 读在 draw 阶段 → 只失效 draw，不触发重组、不重新布局。
        drawTerminal(
            frameTick = tick.intValue,
            snapshot = snapshot,
            metrics = metrics,
            theme = theme,
            focused = focused,
            selActive = selection.active,
            selStartLine = selection.startLine,
            selStartCol = selection.startCol,
            selEndLine = selection.endLine,
            selEndCol = selection.endCol
        )
    }
}

private const val RESIZE_DEBOUNCE_MS = 120L

/**
 * 纯绘制。没有任何 Compose state 读取，也不分配对象。
 *
 * 三趟：背景 → 选区 → 文字。分趟而不是每个 run 画完背景马上画字，是因为
 * 斜体 / 粗体的字形会**溢出自己的格子**，后一个 run 的背景矩形会把前一个 run
 * 的溢出部分切掉，表现为斜体字最后一竖被削平。
 */
@Suppress("UNUSED_PARAMETER")
private fun DrawScope.drawTerminal(
    /** 只为在绘制阶段建立对重绘信号的依赖，函数体里用不到。 */
    frameTick: Int,
    snapshot: TerminalSnapshot,
    metrics: TerminalMetrics,
    theme: TerminalTheme,
    focused: Boolean,
    selActive: Boolean,
    selStartLine: Int,
    selStartCol: Int,
    selEndLine: Int,
    selEndCol: Int
) {
    val cellW = metrics.cellW
    val cellH = metrics.cellH
    val runCount = snapshot.runCount

    // 整屏反显（DECSCNM）。少见，但 `vim` 的某些主题和一些 TUI 会用，
    // 而且实现成本只是交换两个默认色。
    val reverse = snapshot.meta[TerminalSnapshot.META_MODE_FLAGS] and
        TerminalSnapshot.MODE_REVERSE_VIDEO != 0
    val defFg = if (reverse) theme.background else theme.foreground
    val defBg = if (reverse) theme.foreground else theme.background

    drawRect(color = Color(defBg), size = size)

    val paint = metrics.paint
    val fill = metrics.fill()

    drawIntoCanvas { canvas ->
        val nc = canvas.nativeCanvas

        // ---- 第一趟：背景 ----
        var i = 0
        while (i < runCount) {
            val sid = snapshot.runStyle(i)
            val bg = TerminalColors.backgroundArgb(
                snapshot.styleBg(sid), theme.palette, defFg, defBg
            )
            if (bg != defBg) {
                val x = snapshot.runCol(i) * cellW
                val y = snapshot.runRow(i) * cellH
                fill.color = bg
                // 右边缘和下边缘都用 ceil 往外扩一点：相邻 run 之间不能露缝，
                // 露一条 1px 的黑线在深色背景上特别显眼。
                nc.drawRect(
                    x,
                    y,
                    x + kotlin.math.ceil(snapshot.runColumns(i) * cellW),
                    y + cellH,
                    fill
                )
            }
            i++
        }

        // ---- 第二趟：选区 ----
        if (selActive) {
            drawSelection(
                nc, fill, snapshot, metrics, theme,
                selStartLine, selStartCol, selEndLine, selEndCol
            )
        }

        // ---- 第三趟：文字 ----
        i = 0
        while (i < runCount) {
            val sid = snapshot.runStyle(i)
            val flags = snapshot.styleFlags(sid)
            val fg = TerminalColors.foregroundArgb(
                snapshot.styleFg(sid), flags, theme.palette, defFg, defBg
            )
            val textLen = snapshot.runTextLength(i)
            if (textLen > 0) {
                paint.color = fg
                // 粗体：提亮（0-7 → 8-15，已在 foregroundArgb 里做）**加**
                // 伪粗。只提亮不加粗的话，白色前景的粗体看不出任何区别。
                paint.isFakeBoldText = flags and TerminalColors.ATTR_BOLD != 0
                paint.textSkewX = if (flags and TerminalColors.ATTR_ITALIC != 0) -0.25f else 0f
                // 双下划线 / 波浪下划线退化成普通下划线：Paint 只有一种，
                // 而它们的语义（这里有个东西）已经传达到了。
                paint.isUnderlineText = flags and (
                    TerminalColors.ATTR_UNDERLINE or
                        TerminalColors.ATTR_UNDERLINE_DOUBLE or
                        TerminalColors.ATTR_UNDERLINE_CURLY
                    ) != 0
                paint.isStrikeThruText = flags and TerminalColors.ATTR_STRIKE != 0

                val x0 = snapshot.runCol(i) * cellW
                val y = snapshot.runRow(i) * cellH + metrics.baseline
                if (snapshot.runIsAscii(i)) {
                    // 一列一个 code unit，字体自己推进就是对的 —— 一次 drawText
                    // 画完整段，这是最常见也最快的路径。
                    nc.drawText(snapshot.text, snapshot.runTextOffset(i), textLen, x0, y, paint)
                } else {
                    drawWideRun(nc, paint, snapshot, i, x0, y, cellW)
                }

                if (flags and TerminalColors.ATTR_OVERLINE != 0) {
                    fill.color = fg
                    val top = snapshot.runRow(i) * cellH
                    nc.drawRect(
                        x0, top, x0 + snapshot.runColumns(i) * cellW, top + 1.5f, fill
                    )
                }
            }
            i++
        }

        paint.isFakeBoldText = false
        paint.textSkewX = 0f
        paint.isUnderlineText = false
        paint.isStrikeThruText = false
    }

    drawCursor(snapshot, metrics, theme, focused)
    drawScrollbar(snapshot, theme)
}

/**
 * 含 CJK / emoji / 组合字符的 run：**逐簇按列居中**画。
 *
 * 不能一次 drawText 画完：系统回退字体（CJK 走 Noto Sans CJK，emoji 走 Noto
 * Color Emoji）的 advance 不保证正好是 ASCII 的两倍，跟着字体走，一行里
 * 中英混排就会越走越歪，第 k 列在不同行对不齐。
 */
private fun drawWideRun(
    nc: android.graphics.Canvas,
    paint: Paint,
    snapshot: TerminalSnapshot,
    runIndex: Int,
    x0: Float,
    baselineY: Float,
    cellW: Float
) {
    val text = snapshot.text
    val start = snapshot.runTextOffset(runIndex)
    val end = start + snapshot.runTextLength(runIndex)
    var ti = start
    var col = 0
    while (ti < end) {
        val cp = Character.codePointAt(text, ti)
        var n = Character.charCount(cp)
        var w = TerminalWidth.of(cp)
        if (w < 1) w = 1
        // 把后面所有宽度 0 的码点（组合符 / 变体选择符 / ZWJ）吸进同一簇，
        // 一次画出来，字体才有机会把它们合成一个字形。
        while (ti + n < end) {
            val next = Character.codePointAt(text, ti + n)
            if (TerminalWidth.of(next) != 0) break
            n += Character.charCount(next)
        }
        val cellSpan = w * cellW
        val advance = paint.measureText(text, ti, n)
        // 居中：宽字符字形通常比两格窄一点，靠左画会看着贴着前一个字。
        val x = x0 + col * cellW + (cellSpan - advance) / 2f
        nc.drawText(text, ti, n, x, baselineY, paint)
        col += w
        ti += n
    }
}

private fun drawSelection(
    nc: android.graphics.Canvas,
    fill: Paint,
    snapshot: TerminalSnapshot,
    metrics: TerminalMetrics,
    theme: TerminalTheme,
    startLine: Int,
    startCol: Int,
    endLine: Int,
    endCol: Int
) {
    val top = snapshot.topLine
    val rows = snapshot.rows
    val cols = snapshot.cols
    val first = max(startLine - top, 0)
    val last = min(endLine - top, rows - 1)
    if (first > last) return
    fill.color = theme.selection
    for (r in first..last) {
        val absLine = top + r
        val from = if (absLine == startLine) startCol else 0
        val to = if (absLine == endLine) endCol + 1 else cols
        if (to <= from) continue
        val y = r * metrics.cellH
        nc.drawRect(
            from * metrics.cellW,
            y,
            min(to, cols) * metrics.cellW,
            y + metrics.cellH,
            fill
        )
    }

    // 两个把手。画在**行的下边缘**上而不是行下方一整格：最后一行的把手
    // 那样会被画到画布外面，用户就再也调不动选区的尾端了。
    // 命中半径由手势层单独给（比这个圆大得多），这里只管看得见。
    fill.color = HANDLE_COLOR
    fill.isAntiAlias = true
    if (startLine - top in 0 until rows) {
        nc.drawCircle(
            startCol * metrics.cellW,
            (startLine - top + 1) * metrics.cellH,
            HANDLE_RADIUS_PX,
            fill
        )
    }
    if (endLine - top in 0 until rows) {
        nc.drawCircle(
            (endCol + 1) * metrics.cellW,
            (endLine - top + 1) * metrics.cellH,
            HANDLE_RADIUS_PX,
            fill
        )
    }
    fill.isAntiAlias = false
}

private const val HANDLE_RADIUS_PX = 9f
private const val HANDLE_COLOR = 0xFF3B8EEA.toInt()

/**
 * 光标。
 *
 * 块状光标用半透明而不是「填满 + 反色重画字符」：反色重画要回头去快照里找
 * 这一格属于哪个 run，为了一格做一次线性查找不值当，而半透明块底下的字
 * 照样读得出来。失焦时画空心框（和所有终端一致，表示「键盘不在我这」）。
 */
private fun DrawScope.drawCursor(
    snapshot: TerminalSnapshot,
    metrics: TerminalMetrics,
    theme: TerminalTheme,
    focused: Boolean
) {
    val row = snapshot.cursorRow
    // -1 = 光标不在视口里（用户上滚了）。这时画光标会误导人。
    if (row < 0 || !snapshot.cursorVisible) return
    val col = snapshot.cursorCol
    val x = col * metrics.cellW
    val y = row * metrics.cellH
    val color = Color(theme.cursor)
    // 形状：0/1 块，2/3 下划线，4/5 竖线（DECSCUSR）。闪烁位（奇数）一律
    // 当不闪：永远闪烁 = 每 500 ms 强制一帧，纯耗电，收益是零。
    when (snapshot.cursorShape) {
        2, 3 -> drawRect(
            color = color,
            topLeft = Offset(x, y + metrics.cellH - CURSOR_BAR_PX),
            size = Size(metrics.cellW, CURSOR_BAR_PX)
        )
        4, 5 -> drawRect(
            color = color,
            topLeft = Offset(x, y),
            size = Size(CURSOR_BAR_PX, metrics.cellH)
        )
        else -> if (focused) {
            drawRect(
                color = color.copy(alpha = 0.62f),
                topLeft = Offset(x, y),
                size = Size(metrics.cellW, metrics.cellH)
            )
        } else {
            drawRect(
                color = color.copy(alpha = 0.8f),
                topLeft = Offset(x + 0.5f, y + 0.5f),
                size = Size(metrics.cellW - 1f, metrics.cellH - 1f),
                style = CURSOR_OUTLINE
            )
        }
    }
}

private const val CURSOR_BAR_PX = 2.5f

// Stroke 是普通类不是 value class，写成 `Stroke(1.5f)` 就是每帧一次分配。
// 宽度是常量，提出来一次建好。（Color / Offset / Size 都是 value class，
// 那几个 inline 掉了，不用管。）
private val CURSOR_OUTLINE = Stroke(width = 1.5f)

/** 只在离开底部时出现的细滚动条 —— 用户得知道自己正在翻历史。 */
private fun DrawScope.drawScrollbar(snapshot: TerminalSnapshot, theme: TerminalTheme) {
    val total = snapshot.totalLines
    val rows = snapshot.rows
    if (total <= rows) return
    val top = snapshot.topLine
    if (top >= total - rows) return
    val h = size.height
    val thumbH = max(h * rows / total, MIN_THUMB_PX)
    val y = (h - thumbH) * top / (total - rows).toFloat()
    drawRect(
        color = Color(theme.scrollbar),
        topLeft = Offset(size.width - SCROLLBAR_W, y),
        size = Size(SCROLLBAR_W, thumbH)
    )
}

private const val SCROLLBAR_W = 3f
private const val MIN_THUMB_PX = 24f
