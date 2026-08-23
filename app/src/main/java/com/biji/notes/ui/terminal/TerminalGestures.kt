package com.biji.notes.ui.terminal

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// =====================================================================
//  手势。
//
//  ## 冲突是怎么解的
//  **不叠 `horizontalScroll` + `detectTapGestures` + `transformable`** —— 三者
//  会互相抢 pointer，谁先拿到取决于 modifier 顺序和 slop 谁先到，结果不可控，
//  症状是「有时候能滚有时候在选字」。这里只有**一个** `pointerInput`，里面是
//  一个自己写的状态机（Termux 的做法也是 ScaleGestureDetector 先吃、
//  GestureDetector 后吃，本质是同一件事）：
//
//    按下 → UNDECIDED
//      ├ 出现第 2 根手指 ────────────→ ZOOM   （独占，之后忽略一切滚动）
//      ├ 单指位移 > touchSlop ──────→ SCROLL （只吃纵向；抬手跑惯性）
//      ├ 不动且超过 longPressTimeout → SELECT （震一下 + 选中整词 + 弹菜单）
//      └ 抬手且以上都没发生 ────────→ TAP    （退出选择 / 上报鼠标 / 唤起键盘）
//    按下点落在选择把手上 ──────────→ HANDLE （直接进拖把手，不看 slop）
//
//  进了某个模式就不再改判，从根上消灭「滚一半突然开始选字」。
//
//  ## 横向滚动被**删掉**了
//  终端有固定列数，超宽内容由 DECAWM 自动折行，横向拖内容没有语义。旧实现
//  那条共享的 `horizontalScroll` 是「进度条能横拖到天边」的帮凶。删掉它同时
//  解掉了「横滚 vs 选区」的冲突，也把横向手势位腾了出来（留给切换会话）。
// =====================================================================

/**
 * 选区。坐标是**绝对行号**（0 = 回滚缓冲最老的一行），不是屏幕行号 ——
 * 选中之后还能继续滚屏，用屏幕行号存的话一滚就错位。
 */
@Stable
class TerminalSelection {

    var active by mutableStateOf(false)
        private set

    /** 手指最先按下的那一端，拖动时不动。 */
    private var anchorLine by mutableIntStateOf(0)
    private var anchorCol by mutableIntStateOf(0)

    /** 跟着手指走的那一端。 */
    private var headLine by mutableIntStateOf(0)
    private var headCol by mutableIntStateOf(0)

    private fun anchorFirst(): Boolean =
        anchorLine < headLine || (anchorLine == headLine && anchorCol <= headCol)

    val startLine: Int get() = if (anchorFirst()) anchorLine else headLine
    val startCol: Int get() = if (anchorFirst()) anchorCol else headCol
    val endLine: Int get() = if (anchorFirst()) headLine else anchorLine
    val endCol: Int get() = if (anchorFirst()) headCol else anchorCol

    fun begin(line: Int, col: Int) {
        anchorLine = line
        anchorCol = col
        headLine = line
        headCol = col
        active = true
    }

    /** 拖动跟手的那一端。 */
    fun moveHead(line: Int, col: Int) {
        if (!active) return
        headLine = line
        headCol = col
    }

    /**
     * 抓住某一端的把手：把被抓的那端换成 head、另一端换成 anchor，
     * 之后拖动一律走 [moveHead]，两端的处理就没有分支了。
     *
     * 不做这步归一化的话，**从右往左选出来的选区**（anchor 在后、head 在前）
     * 会出现「抓住尾巴的把手，动的却是头」—— 因为 start/end 是排序后的视图，
     * 而 anchor/head 是输入顺序。这是自己写选区最容易漏的一处。
     *
     * @param which 0 = 起点把手，1 = 终点把手
     */
    fun grabHandle(which: Int) {
        if (!active) return
        val sL = startLine
        val sC = startCol
        val eL = endLine
        val eC = endCol
        if (which == 0) {
            anchorLine = eL
            anchorCol = eC
            headLine = sL
            headCol = sC
        } else {
            anchorLine = sL
            anchorCol = sC
            headLine = eL
            headCol = eC
        }
    }

    /** 长按之后自动扩到整词（Termux 语义）：单字符选区在手机上没法用。 */
    fun expandTo(line: Int, from: Int, to: Int) {
        anchorLine = line
        anchorCol = from
        headLine = line
        headCol = to
        active = true
    }

    fun selectAll(totalLines: Int, cols: Int) {
        anchorLine = 0
        anchorCol = 0
        headLine = max(0, totalLines - 1)
        headCol = max(0, cols - 1)
        active = true
    }

    fun clear() {
        active = false
    }

    fun text(binding: TerminalBinding): String {
        if (!active) return ""
        return binding.selectionText(startLine, startCol, endLine, endCol)
    }
}

/**
 * 手势状态机需要、但只有渲染侧知道的东西。
 *
 * 只有 [altScreen] 一项：模拟器没有公开这个模式（快照里才有），而滚动手势
 * 必须知道它 —— 备用屏没有回滚缓冲，在 `less` 里滑动会毫无反应，用户会以为
 * 终端卡了。由 `TerminalCanvas` 每帧顺手更新，普通 `@Volatile` 字段即可，
 * 做成 Compose state 只会让每帧都触发重组。
 */
@Stable
class TerminalViewInfo {
    @Volatile
    var altScreen: Boolean = false
}

/**
 * 手势的接线板。回调做成可变字段而不是构造参数：每次重组都会产生新的 lambda，
 * 放构造参数里就得重建控制器，`pointerInput` 跟着重启，手势会在滑动中途断掉。
 */
@Stable
class TerminalGestureController(
    val binding: TerminalBinding,
    /**
     * 字号一变 [TerminalMetrics] 就是新对象，所以它必须是**可变字段**而不是
     * 构造参数：作为构造参数的话，捏合缩放到第二档时控制器会被重建，
     * `pointerInput` 的 key 跟着变，正在进行的捏合手势当场断掉 —— 表现是
     * 「只能缩放一档，手指还没松就没反应了」。只在 UI 线程写，不做成 state。
     */
    var metrics: TerminalMetrics,
    val selection: TerminalSelection,
    val info: TerminalViewInfo,
    private val scope: CoroutineScope,
    density: Density
) {
    var haptics: HapticFeedback? = null
    var onZoomStart: () -> Unit = {}
    var onZoom: (Float) -> Unit = {}
    var onZoomEnd: () -> Unit = {}
    var onLongPress: (Offset) -> Unit = {}
    var onTap: () -> Unit = {}

    private val decay: DecayAnimationSpec<Float> = splineBasedDecay(density)

    private var flingJob: Job? = null

    /** 像素 → 绝对行号。视口顶行每次都现取：选着字的时候屏幕还在滚。 */
    fun lineAt(y: Float): Int {
        val row = (y / metrics.cellH).toInt()
        val top = binding.viewportTop
        return (top + row).coerceIn(0, max(0, binding.totalLines - 1))
    }

    fun colAt(x: Float): Int =
        (x / metrics.cellW).toInt().coerceIn(0, max(0, binding.columns - 1))

    fun rowAt(y: Float): Int =
        (y / metrics.cellH).toInt().coerceIn(0, max(0, binding.screenRows - 1))

    fun cancelFling() {
        flingJob?.cancel()
        flingJob = null
    }

    fun scrollLines(lines: Int) {
        if (lines == 0) return
        if (info.altScreen) {
            // 备用屏（vi / less / man）没有回滚缓冲，滚它等于什么都不做。
            // 转成方向键：`less` 直接翻行，vi 里是移动光标 —— 不是完美语义，
            // 但比「滑动毫无反应」好太多。真正开了鼠标上报的程序走不到这里，
            // 上面的 wheel 分支会先接管。
            val key = if (lines > 0) com.biji.notes.terminal.TerminalEmulator.KEY_UP
            else com.biji.notes.terminal.TerminalEmulator.KEY_DOWN
            var n = min(abs(lines), MAX_ALT_SCROLL_KEYS)
            while (n > 0) {
                binding.key(key)
                n--
            }
            return
        }
        binding.scrollBy(-lines)
    }

    /** 滚轮上报（`?1000`/`?1002` + `?1006`）。程序没开就返回 false，走默认滚动。 */
    fun wheel(lines: Int, col: Int, row: Int): Boolean {
        if (lines == 0) return false
        val button = if (lines > 0) WHEEL_UP else WHEEL_DOWN
        var sent = false
        var n = min(abs(lines), MAX_WHEEL_EVENTS)
        while (n > 0) {
            if (!binding.mouse(button, col, row, pressed = true)) return sent
            sent = true
            n--
        }
        return sent
    }

    fun fling(velocityPxPerSec: Float) {
        cancelFling()
        // 备用屏上滚动是发方向键，惯性会变成几十次按键连发（vi 里光标会飞），
        // 所以那边只做跟手滚动，不跑惯性。
        if (info.altScreen) return
        // ×0.25：Termux 的系数。手机上原速惯性在终端里太滑，一甩就冲到头。
        val v = velocityPxPerSec * FLING_SCALE
        if (abs(v) < MIN_FLING_VELOCITY) return
        flingJob = scope.launch {
            var last = 0f
            var acc = 0f
            AnimationState(initialValue = 0f, initialVelocity = v).animateDecay(decay) {
                val delta = value - last
                last = value
                acc += delta
                val lines = (acc / metrics.cellH).toInt()
                if (lines != 0) {
                    acc -= lines * metrics.cellH
                    val before = binding.viewportTop
                    scrollLines(lines)
                    // 撞到顶 / 底就停，不然惯性会空跑一秒
                    if (binding.viewportTop == before) cancelAnimation()
                }
            }
        }
    }

    /** 长按起选：先定位，再自动扩到整词。 */
    fun beginSelectionAt(offset: Offset) {
        val line = lineAt(offset.y)
        val col = colAt(offset.x)
        val range = wordRangeAt(line, col)
        if (range == null) selection.begin(line, col) else selection.expandTo(line, range.first, range.second)
        haptics?.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    /**
     * 词边界。取这一行的文本自己扫 —— 只在长按那一下跑一次，
     * 为它维护一份常驻的行缓存不值当。
     */
    private fun wordRangeAt(line: Int, col: Int): Pair<Int, Int>? {
        val cols = binding.columns
        if (cols <= 0) return null
        val text = binding.selectionText(line, 0, line, cols - 1)
        if (text.isEmpty() || col >= text.length) return null
        if (!isWordChar(text[col])) return null
        var from = col
        while (from > 0 && isWordChar(text[from - 1])) from--
        var to = col
        while (to + 1 < text.length && isWordChar(text[to + 1])) to++
        return from to to
    }

    // 路径和 URL 要能一把选中 —— 手机上最常见的复制目标就是这两样，
    // 严格按「字母数字下划线」切词的话，`/data/data/...` 要拖十几次。
    private fun isWordChar(c: Char): Boolean =
        c.isLetterOrDigit() || c in "_-./~:@%+=#&?"

    /** 选区把手命中测试。返回 0 = 起点把手，1 = 终点把手，-1 = 没命中。 */
    fun handleAt(offset: Offset, radiusPx: Float): Int {
        if (!selection.active) return -1
        val top = binding.viewportTop
        val startX = selection.startCol * metrics.cellW
        val startY = (selection.startLine - top + 1) * metrics.cellH
        val endX = (selection.endCol + 1) * metrics.cellW
        val endY = (selection.endLine - top + 1) * metrics.cellH
        val ds = dist2(offset, startX, startY)
        val de = dist2(offset, endX, endY)
        val r2 = radiusPx * radiusPx
        return when {
            ds <= r2 && ds <= de -> 0
            de <= r2 -> 1
            else -> -1
        }
    }

    private fun dist2(p: Offset, x: Float, y: Float): Float {
        val dx = p.x - x
        val dy = p.y - y
        return dx * dx + dy * dy
    }

    private companion object {
        const val FLING_SCALE = 0.25f
        const val MIN_FLING_VELOCITY = 60f
        const val MAX_ALT_SCROLL_KEYS = 8
        const val MAX_WHEEL_EVENTS = 4
        const val WHEEL_UP = 64
        const val WHEEL_DOWN = 65
    }
}

private const val MODE_UNDECIDED = 0
private const val MODE_SCROLL = 1
private const val MODE_ZOOM = 2
private const val MODE_SELECT = 3
private const val MODE_HANDLE = 4

/** 拖到视口边缘时的自动滚屏节流：每 40 ms 一行，比帧率慢，不然一眨眼滚到头。 */
private const val AUTOSCROLL_INTERVAL_MS = 40L

/** 把手的命中半径。比手指小一点会抓不住，太大又会挡住起选。 */
private const val HANDLE_TOUCH_RADIUS_DP = 22f

fun Modifier.terminalGestures(controller: TerminalGestureController): Modifier =
    pointerInput(controller) {
        val handleRadius = HANDLE_TOUCH_RADIUS_DP * density
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            // 新的一次按下必须先掐掉上一次的惯性，否则屏幕在手指底下继续滑。
            controller.cancelFling()

            val handleIndex = controller.handleAt(down.position, handleRadius)
            var mode = if (handleIndex >= 0) MODE_HANDLE else MODE_UNDECIDED
            if (mode == MODE_HANDLE) controller.selection.grabHandle(handleIndex)

            val slop = viewConfiguration.touchSlop
            // 用 currentTimeMillis 而不是 down.uptimeMillis：后者是 uptime 时钟
            // （SystemClock.uptimeMillis），和下面比较用的 wall clock 不是一个原点，
            // 混着用会让长按要么秒触发要么永不触发。
            val longPressAt = System.currentTimeMillis() + viewConfiguration.longPressTimeoutMillis
            val tracker = VelocityTracker()
            tracker.addPosition(down.uptimeMillis, down.position)

            var last = down.position
            var scrollAcc = 0f
            var zoomAcc = 1f

            // 视口上下各留一格当「自动滚屏区」。返回 +1 = 往历史翻，-1 = 往新的翻。
            fun edgeDirection(p: Offset): Int {
                val h = size.height.toFloat()
                val band = controller.metrics.cellH
                return when {
                    p.y < band -> 1
                    p.y > h - band -> -1
                    else -> 0
                }
            }

            // 选区跟手 + 贴边自动滚屏。滚动之后**必须重算一次 head** ——
            // 绝对行号是 viewTop + 屏幕行，viewTop 变了同一个 y 就是另一行了。
            fun dragSelection(p: Offset) {
                val dir = edgeDirection(p)
                if (dir != 0) controller.scrollLines(dir)
                controller.selection.moveHead(controller.lineAt(p.y), controller.colAt(p.x))
            }

            while (true) {
                // 等待策略按模式分三种：
                //  * 还没定性 → 等到长按超时为止
                //  * 选区拖到了边缘 → 每 40 ms 醒一次，手指停着不动也要继续滚
                //    （事件驱动的写法在手指静止时收不到任何事件，会表现成
                //     「拖到顶就再也选不上去了」）
                //  * 其余 → 老实等事件
                val selecting = mode == MODE_SELECT || mode == MODE_HANDLE
                val event: PointerEvent? = when {
                    mode == MODE_UNDECIDED -> {
                        val remaining = longPressAt - System.currentTimeMillis()
                        if (remaining <= 0) null
                        else withTimeoutOrNull(remaining) { awaitPointerEvent() }
                    }

                    selecting && edgeDirection(last) != 0 ->
                        withTimeoutOrNull(AUTOSCROLL_INTERVAL_MS) { awaitPointerEvent() }

                    else -> awaitPointerEvent()
                }

                if (event == null) {
                    if (mode == MODE_UNDECIDED) {
                        // 长按超时：起选（震一下 + 选中整词），之后这次手势全归
                        // 选择控制器。**菜单要等抬手才弹** —— 菜单是个 Popup，
                        // 会盖住整块区域接管触摸，现在弹的话手指想继续拖着扩选
                        // 只会把菜单关掉。和 Android 自己的文本选择一个节奏。
                        mode = MODE_SELECT
                        controller.beginSelectionAt(last)
                    } else {
                        dragSelection(last)
                    }
                    continue
                }

                val pressed = event.changes.count { it.pressed }
                if (pressed == 0) break

                // 第二根手指一出现就锁死成缩放，并且**不再回退** —— 抬起一根手指
                // 之后继续按 ZOOM 处理，不然松手的瞬间会被判成一次滚动，
                // 屏幕跟着跳一下。
                if (pressed >= 2 && mode != MODE_HANDLE) {
                    if (mode != MODE_ZOOM) {
                        mode = MODE_ZOOM
                        zoomAcc = 1f
                        controller.onZoomStart()
                    }
                }

                val change = event.changes.firstOrNull { it.id == down.id }
                    ?: event.changes.firstOrNull { it.pressed }
                    ?: break
                val pos = change.position

                when (mode) {
                    MODE_ZOOM -> {
                        val z = event.calculateZoom()
                        if (z != 0f && z.isFinite()) {
                            zoomAcc *= z
                            controller.onZoom(zoomAcc)
                        }
                        event.changes.forEach { if (it.pressed) it.consume() }
                    }

                    MODE_UNDECIDED -> {
                        if ((pos - down.position).getDistance() > slop) {
                            mode = MODE_SCROLL
                            scrollAcc = 0f
                            last = pos
                        }
                        tracker.addPosition(change.uptimeMillis, pos)
                    }

                    MODE_SCROLL -> {
                        tracker.addPosition(change.uptimeMillis, pos)
                        val dy = pos.y - last.y
                        last = pos
                        scrollAcc += dy
                        val lines = (scrollAcc / controller.metrics.cellH).toInt()
                        if (lines != 0) {
                            scrollAcc -= lines * controller.metrics.cellH
                            // 程序自己开了鼠标上报（less / vim / htop）就把滚轮
                            // 事件发过去，别去动我们的回滚缓冲 —— 两边同时滚
                            // 是最让人迷惑的状态。
                            val col = controller.colAt(pos.x)
                            val row = controller.rowAt(pos.y)
                            if (!controller.wheel(lines, col, row)) {
                                controller.scrollLines(lines)
                            }
                        }
                        change.consume()
                    }

                    // 抓把手时已经在 grabHandle 里归一化过了（被抓的那端 = head），
                    // 所以这里两种模式的处理完全一样。
                    MODE_SELECT, MODE_HANDLE -> {
                        last = pos
                        dragSelection(pos)
                        change.consume()
                    }
                }
            }

            when (mode) {
                MODE_SCROLL -> {
                    val v = tracker.calculateVelocity().y
                    controller.fling(v)
                }

                MODE_ZOOM -> controller.onZoomEnd()

                // 抬手了才弹菜单：选区已经定下来，用户下一步十有八九是「复制」。
                MODE_SELECT, MODE_HANDLE -> controller.onLongPress(last)

                MODE_UNDECIDED -> {
                    // 单击：有选区先退选（和所有终端一致，也和 Termux 一致），
                    // 否则尝试上报鼠标点击，都不是就唤起键盘。
                    if (controller.selection.active) {
                        controller.selection.clear()
                    } else {
                        val col = controller.colAt(down.position.x)
                        val row = controller.rowAt(down.position.y)
                        val reported = controller.binding.mouse(0, col, row, pressed = true)
                        if (reported) {
                            controller.binding.mouse(0, col, row, pressed = false)
                        } else {
                            controller.onTap()
                        }
                    }
                }
            }
        }
    }
