package com.biji.notes.terminal

import com.biji.notes.terminal.TerminalColors.ATTR_HIDDEN
import com.biji.notes.terminal.TerminalColors.ATTR_INVERSE
import com.biji.notes.terminal.TerminalColors.COLOR_DEFAULT_BG
import com.biji.notes.terminal.TerminalColors.COLOR_DEFAULT_FG

// =====================================================================
// 屏幕缓冲 + 回滚缓冲。
//
// ## 为什么整套核心是 Kotlin 而不是 C++
// 调研规格建议 C++，前提是 **PTY / read 循环 / 屏幕 / 回滚全在 native**，
// 数据路径上一次 JNI 都不过。那个前提在本次落地里不成立：PTY（vt_pty.cpp）、
// session（vt_session.cpp）、JNI 入口（terminal_jni.cpp）都不在可改范围内，
// CMakeLists 的 BIJI_REQUIRED_SOURCES 也动不了。只把解析器放 native、屏幕
// 缓冲留 Kotlin 是最坏的组合：每个 print 动作都要跨一次 JNI，比纯 Kotlin 慢，
// 还额外背上"崩溃没有软降级"的代价（jni_common.h 约束 #3 明说无可变全局状态，
// 而终端本质有状态）。
//
// 所以这里就是规格 §5.2 里点名保留的那条**真降级路径**：一份不依赖 .so、
// 不依赖 PTY、今天就能跑的完整实现。代价照单全收并明写在这里：
//   * 高吞吐（cat 大文件 / make -j 日志）时 GC 压力比 native 版大；
//   * 补偿手段是"用 Kotlin 语法写 C"——见下面的存储布局，**全程零对象、
//     零装箱、零临时 String**，热路径上只有 IntArray 下标和 System.arraycopy。
//
// ## Cell 存储：每格 2 个 int，行内交错
//   cells[col * 2]     = 码点 / 哨兵
//   cells[col * 2 + 1] = style 表下标（intern，见 StyleTable）
//
// 交错而不是两条平行数组，是为了让"屏幕行"和"历史行"是**同一种布局**：
// 滚动时把屏幕行原样 arraycopy 进历史槽位就完事，快照、选区、rewrap 三条
// 读路径各只有一份代码。规格里主屏定长网格 / 历史紧凑行的两套表示，
// 在 Kotlin 这边省下的内存（历史行已按 used 裁剪）远不抵多一套转换代码
// 带来的"历史和屏幕对宽字符的理解不一致"这类 bug。
//
// 码点字段的四种取值（一个字段吃四种情况，避免为罕见情况给每格加宽）：
//   0                    空白（从未写过，或被擦除）
//   1 .. 0x10FFFF        普通 UCS-4 码点（**不是 UTF-16**，代理对只在输出时才拼）
//   0x7FFFFFFF           宽字符的右半格占位（CP_WIDE_TRAIL）
//   < 0（bit31 = 1）     低 31 位是组合簇在 clusterPool 里的偏移
// =====================================================================

/** 空白格。0 而不是 ' '，这样整行 `Arrays.fill(0)` 就同时把 style 归了默认。 */
internal const val CP_BLANK = 0

/** 宽字符的右半格。取 Int.MAX_VALUE，和任何合法码点、任何簇偏移都不冲突。 */
internal const val CP_WIDE_TRAIL = 0x7FFFFFFF

/** bit31：低 31 位是组合簇偏移。 */
internal const val CP_CLUSTER_FLAG = 1 shl 31

/** 默认样式在 [StyleTable] 里恒为 0 号，`Arrays.fill(cells, 0)` 才能一步清行。 */
internal const val STYLE_DEFAULT = 0

/** 行标志：本行是被自动换行结束的（逻辑行在下一行继续）。 */
internal const val ROW_WRAPPED = 1

/** 单个组合簇最多存几个码点。超出直接丢，不增长——见 [TerminalBuffer.appendCombining]。 */
private const val MAX_CLUSTER_CPS = 8

/** 组合簇池上限（int 数）。到顶后新组合符被丢弃，只渲染基字符：只降级，不失败。 */
private const val MAX_CLUSTER_INTS = 64 * 1024

/** 默认回滚行数。 */
const val DEFAULT_SCROLLBACK_LINES = 5000

/** 默认回滚字节上限。只按行数限会被一行一百万字符的 `cat` 打爆。 */
const val DEFAULT_SCROLLBACK_BYTES = 8L * 1024 * 1024

// ---------------------------------------------------------------------
// Style intern 表
// ---------------------------------------------------------------------

/**
 * 把 (fg, bg, flags) 三元组 intern 成一个小整数。
 *
 * 为什么不把属性直接摊进 cell：24 位前景 + 24 位背景 + 11 位标志塞不进
 * 一个 int，摊开就是每格 4 个 int（+100% 内存），而且快照体积同步翻倍。
 * 真实终端输出里不同 style 的种类是常数级（纯文本 1-2 种，带语法高亮的
 * vim 也就几十种），intern 之后：run 合并退化成"比一个 int"，快照只需附
 * 一张几十项的小表。
 *
 * 满表（4096）时走**降级阶梯**而不是失败：先丢 flags，再丢 bg，最后回落到
 * 默认样式。整个仓库的基调是"只降级、绝不 abort"，颜色少一档没人会死。
 */
internal class StyleTable {

    private val fgArr = IntArray(MAX_STYLES)
    private val bgArr = IntArray(MAX_STYLES)
    private val flArr = IntArray(MAX_STYLES)

    /** 开放寻址，装载因子 0.5。链表法每插一个 style 就是一次对象分配。 */
    private val slots = IntArray(HASH_SIZE) { -1 }

    var count = 0
        private set

    init {
        reset()
    }

    fun reset() {
        count = 0
        java.util.Arrays.fill(slots, -1)
        // 0 号必须是默认样式：blankRun / Arrays.fill(0) 全靠这条不变量。
        intern(COLOR_DEFAULT_FG, COLOR_DEFAULT_BG, 0)
    }

    fun fg(id: Int): Int = if (id in 0 until count) fgArr[id] else COLOR_DEFAULT_FG
    fun bg(id: Int): Int = if (id in 0 until count) bgArr[id] else COLOR_DEFAULT_BG
    fun flags(id: Int): Int = if (id in 0 until count) flArr[id] else 0

    fun intern(fg: Int, bg: Int, flags: Int): Int {
        var h = hash(fg, bg, flags) and HASH_MASK
        while (true) {
            val id = slots[h]
            if (id < 0) break
            if (fgArr[id] == fg && bgArr[id] == bg && flArr[id] == flags) return id
            h = (h + 1) and HASH_MASK
        }
        if (count >= MAX_STYLES) {
            // 降级阶梯。递归最多两层，最后一层必然命中 0 号，不会打转。
            if (flags != 0) return intern(fg, bg, 0)
            if (bg != COLOR_DEFAULT_BG) return intern(fg, COLOR_DEFAULT_BG, 0)
            return STYLE_DEFAULT
        }
        val id = count++
        fgArr[id] = fg
        bgArr[id] = bg
        flArr[id] = flags
        slots[h] = id
        return id
    }

    private fun hash(fg: Int, bg: Int, flags: Int): Int {
        var h = fg * -1640531527
        h = (h xor (bg * 0x9E3779B1.toInt())) * 0x85EBCA6B.toInt()
        h = h xor (flags * 0xC2B2AE35.toInt())
        return h xor (h ushr 15)
    }

    companion object {
        const val MAX_STYLES = 4096
        private const val HASH_SIZE = 8192
        private const val HASH_MASK = HASH_SIZE - 1
    }
}

// ---------------------------------------------------------------------
// 快照
// ---------------------------------------------------------------------

/**
 * 一帧的可见区域快照。**由 UI 侧 `remember(cols, rows)` 分配一次、反复填充**，
 * 稳态下每帧零分配（规格 §5.4 的契约，只是把 JNI 那一层换成了直接调用）。
 *
 * 数据按 **run** 组织：一个 run = 同一行、连续列、同一 style 的一段，
 * 文本已经是 UTF-16 code unit，可以直接喂
 * `android.graphics.Canvas.drawText(char[], int, int, float, float, Paint)`
 * —— 从 shell 的字节到屏幕像素，全程不产生一个 String。
 *
 * ## 线程契约
 * 由 [TerminalEmulator.snapshot] 在它自己的锁里填充。填完之后 UI 线程独占读，
 * 期间解析线程可能在改缓冲区——**读的是快照，不是缓冲区**，所以不需要同步。
 * 不要把这个对象当 Compose state：它是可变的、内容原地更新。UI 侧应该拿
 * [revision]（一个 Int）当 state key，用 `withFrameNanos` 轮询，
 * 变了才触发重绘。这样多次输出天然合并成一帧。
 */
class TerminalSnapshot(cols: Int, rows: Int) {

    /** 分配时的列数。实际内容看 [cols]。 */
    val capCols: Int = if (cols < 1) 1 else cols

    /** 分配时的行数。 */
    val capRows: Int = if (rows < 1) 1 else rows

    /**
     * 可见文本，UTF-16。容量按 `cols * rows * 2` 给：代理对、组合簇都要裕量，
     * 超出则该行截断并置 [FLAG_TEXT_TRUNCATED]，**绝不越界写**。
     */
    val text = CharArray(capCols * capRows * 2 + 16)

    /** run 记录，stride = [RUN_STRIDE]。 */
    val runs = IntArray(capCols * capRows * RUN_STRIDE)

    /** 表头，见 `META_*` 常量。 */
    val meta = IntArray(META_INTS)

    /** 稠密化之后的 style 表：`[i*3] = fg, +1 = bg, +2 = flags`。 */
    val styles = IntArray(MAX_SNAPSHOT_STYLES * 3)

    // 源 style id → 本帧稠密下标。用 stamp 代替每帧清表：4096 项 fill 每帧
    // 也就几微秒，但没必要花。
    internal val styleMap = IntArray(StyleTable.MAX_STYLES)
    internal val styleStamp = IntArray(StyleTable.MAX_STYLES)
    internal var stamp = 0

    val cols: Int get() = meta[META_COLS]
    val rows: Int get() = meta[META_ROWS]
    val runCount: Int get() = meta[META_RUN_COUNT]
    val textLength: Int get() = meta[META_TEXT_LEN]
    val styleCount: Int get() = meta[META_STYLE_COUNT]

    /** 光标所在的可见行；-1 = 光标不在当前视口内（用户上滚了）。 */
    val cursorRow: Int get() = meta[META_CURSOR_ROW]
    val cursorCol: Int get() = meta[META_CURSOR_COL]
    val cursorVisible: Boolean get() = meta[META_CURSOR_FLAGS] and CURSOR_VISIBLE != 0

    /** 0=块 1=块闪 2=下划线 3=下划线闪 4=竖线 5=竖线闪（DECSCUSR 语义）。 */
    val cursorShape: Int get() = (meta[META_CURSOR_FLAGS] ushr 4) and 0xF

    /** 回滚 + 屏幕的总行数，给滚动条用。 */
    val totalLines: Int get() = meta[META_TOTAL_LINES]

    /** 实际生效的视口顶行（可能被 clamp 过）。 */
    val topLine: Int get() = meta[META_TOP_LINE]

    /** 与 [TerminalEmulator.revision] 对应，用来做 Compose 的 state key。 */
    val revision: Int get() = meta[META_REVISION]

    val altScreen: Boolean get() = meta[META_MODE_FLAGS] and MODE_ALT_SCREEN != 0

    // ---- run 访问器。全部是 IntArray 下标，无对象、无装箱。 ----

    fun runRow(i: Int): Int = runs[i * RUN_STRIDE]
    fun runCol(i: Int): Int = runs[i * RUN_STRIDE + 1]

    /** 本 run 占用的**列数**（宽字符按 2 算），不等于 [runTextLength]。 */
    fun runColumns(i: Int): Int = runs[i * RUN_STRIDE + 2]

    /** 文本在 [text] 里的起始下标。 */
    fun runTextOffset(i: Int): Int = runs[i * RUN_STRIDE + 3]

    /** 文本长度（UTF-16 code unit）。代理对 / 组合簇时 != [runColumns]。 */
    fun runTextLength(i: Int): Int = runs[i * RUN_STRIDE + 4]

    /** 稠密 style 下标，配合 [styleFg] / [styleBg] / [styleFlags]。 */
    fun runStyle(i: Int): Int = runs[i * RUN_STRIDE + 5] and STYLE_ID_MASK

    /**
     * 整段都是 ASCII 且一格一个 code unit —— 渲染侧可以一次 `drawText` 画完。
     * 否则应逐格按 `列号 * cellW` 居中绘制：CJK/emoji 走系统回退字体，
     * 回退字体的 advance 不保证是 ASCII 的两倍，跟着字体走就会列不齐。
     */
    fun runIsAscii(i: Int): Boolean = runs[i * RUN_STRIDE + 5] and RUN_ASCII_BIT != 0

    fun styleFg(id: Int): Int = styles[id * 3]
    fun styleBg(id: Int): Int = styles[id * 3 + 1]
    fun styleFlags(id: Int): Int = styles[id * 3 + 2]

    /** 文本区放不下，部分内容没有输出。正常输入下不会发生。 */
    val textTruncated: Boolean get() = meta[META_FLAGS] and FLAG_TEXT_TRUNCATED != 0

    internal fun begin(cols: Int, rows: Int) {
        stamp++
        if (stamp == Int.MAX_VALUE) {
            // 溢出前重置一次映射表，避免陈旧 stamp 被误判为命中。
            java.util.Arrays.fill(styleStamp, 0)
            stamp = 1
        }
        java.util.Arrays.fill(meta, 0)
        meta[META_MAGIC] = MAGIC
        meta[META_VERSION] = VERSION
        meta[META_COLS] = cols
        meta[META_ROWS] = rows
        meta[META_CURSOR_ROW] = -1
    }

    companion object {
        const val MAGIC = 0x42545631 // 'BTV1'
        const val VERSION = 1

        const val META_INTS = 16
        const val RUN_STRIDE = 6

        const val META_MAGIC = 0
        const val META_VERSION = 1
        const val META_COLS = 2
        const val META_ROWS = 3
        const val META_CURSOR_ROW = 4
        const val META_CURSOR_COL = 5
        const val META_CURSOR_FLAGS = 6
        const val META_MODE_FLAGS = 7
        const val META_TOTAL_LINES = 8
        const val META_TOP_LINE = 9
        const val META_TEXT_LEN = 10
        const val META_RUN_COUNT = 11
        const val META_STYLE_COUNT = 12
        const val META_FLAGS = 13
        const val META_REVISION = 14

        /** 快照里最多带几种 style。超出的 run 回落到默认样式。 */
        const val MAX_SNAPSHOT_STYLES = 512

        const val CURSOR_VISIBLE = 1 shl 0
        const val CURSOR_ON_WIDE_TRAIL = 1 shl 1

        const val MODE_ALT_SCREEN = 1 shl 0
        const val MODE_APP_CURSOR_KEYS = 1 shl 1
        const val MODE_BRACKETED_PASTE = 1 shl 2
        const val MODE_AUTO_WRAP = 1 shl 3
        const val MODE_MOUSE_ANY = 1 shl 4
        const val MODE_REVERSE_VIDEO = 1 shl 5

        const val FLAG_TEXT_TRUNCATED = 1 shl 0
        const val FLAG_RUNS_TRUNCATED = 1 shl 1

        internal const val RUN_ASCII_BIT = 1 shl 24
        internal const val STYLE_ID_MASK = 0xFFFF
    }
}

// ---------------------------------------------------------------------
// 缓冲区本体
// ---------------------------------------------------------------------

/**
 * 主屏 / 备用屏 + 回滚缓冲。
 *
 * **不是线程安全的**：所有方法必须在 [TerminalEmulator] 的锁里调用。
 * 单独拿出来当数据结构用也可以（纯 JVM 可测），但要自己管同步。
 *
 * 行坐标有两套，别混：
 *  - **屏幕行号** `0 until rows`，写入路径（光标、滚动区）全用这套；
 *  - **绝对行号** `0 until totalLines`，`0` 是回滚缓冲最老的一行，
 *    `historyLines` 开始才是屏幕第 0 行。读路径（快照、选区）用这套。
 */
class TerminalBuffer(
    cols: Int,
    rows: Int,
    scrollbackLines: Int = DEFAULT_SCROLLBACK_LINES,
    maxScrollbackBytes: Long = DEFAULT_SCROLLBACK_BYTES
) {

    // ---- 一块屏幕 ----
    private class Screen(var cols: Int, var rows: Int) {
        var cells: Array<IntArray> = Array(rows) { IntArray(cols * 2) }
        var used: IntArray = IntArray(rows)
        var flags: IntArray = IntArray(rows)
    }

    /**
     * 回滚缓冲：**环形数组 + 槽位复用**。
     *
     * 淘汰一行只是 `head++`，槽位里的 IntArray **不析构**，下一次 push 直接
     * 覆写 —— 稳态下滚 100 万行的分配次数是 0。用 ArrayDeque / removeAt(0)
     * 的话每淘汰一行都是 O(n) 搬移，`find /` 的输出就是 O(n²)
     * （`TerminalScreen.kt` 第 130 行那条注释踩的就是 Kotlin 版的同一个坑）。
     *
     * 双上限：行数**和**总字节，先到先淘汰。
     */
    private class History(val cap: Int, val maxBytes: Long) {
        val cells = arrayOfNulls<IntArray>(if (cap > 0) cap else 1)
        val len = IntArray(if (cap > 0) cap else 1)
        val flags = IntArray(if (cap > 0) cap else 1)
        var head = 0
        var count = 0
        var bytes = 0L

        /** 累计淘汰行数。resize 时用它把绝对行号平移回去。 */
        var evicted = 0

        fun slot(line: Int): Int = if (cap <= 0) 0 else (head + line) % cap

        fun push(src: IntArray, used: Int, rowFlags: Int) {
            // 完全不要历史（备用屏 / scrollbackLines = 0）时也要记账：rewrap
            // 靠 evicted 把 emit 序号换算成绝对行号。
            if (cap <= 0) {
                evicted++
                return
            }
            val n = if (used < 0) 0 else used
            if (count == cap) evictOne()
            val s = (head + count) % cap
            var arr = cells[s]
            if (arr == null || arr.size < n * 2) {
                // 只在装不下时重新分配；32 是给"几乎空行"的下限，省得反复 grow。
                arr = IntArray(if (n * 2 < 32) 32 else n * 2)
                cells[s] = arr
            }
            if (n > 0) System.arraycopy(src, 0, arr, 0, n * 2)
            len[s] = n
            flags[s] = rowFlags
            count++
            bytes += n.toLong() * 8
            while (bytes > maxBytes && count > 1) evictOne()
        }

        fun evictOne() {
            if (count <= 0) return
            bytes -= len[head].toLong() * 8
            if (bytes < 0) bytes = 0
            head = (head + 1) % cap
            count--
            evicted++
        }

        /**
         * 丢弃最新的一行，返回它的槽位下标（内容还在，调用方可以先读走）。
         * resize 往回拉行、rewrap 收尾都靠它。
         */
        fun popTail(): Int {
            if (count <= 0 || cap <= 0) return -1
            count--
            val s = (head + count) % cap
            bytes -= len[s].toLong() * 8
            if (bytes < 0) bytes = 0
            return s
        }

        fun clear() {
            head = 0
            count = 0
            bytes = 0
        }
    }

    var cols: Int = if (cols < 2) 2 else cols
        private set

    var rows: Int = if (rows < 1) 1 else rows
        private set

    internal val styles = StyleTable()

    private var main = Screen(this.cols, this.rows)
    private var alt: Screen? = null
    private var cur = main

    var altActive: Boolean = false
        private set

    private val histCap = if (scrollbackLines < 0) 0 else scrollbackLines
    private var hist = History(histCap, maxScrollbackBytes)
    private val maxBytes = maxScrollbackBytes

    // 组合簇池。append-only，只有 reset() 才整体清空 —— 历史行里存着指向
    // 池子的偏移，中途清池就是悬垂引用。
    private var clusterPool = IntArray(256)
    private var clusterUsed = 0

    // 滚动时轮换行引用用的临时数组，避免每滚一行就 new。
    private var tmpCells = arrayOfNulls<IntArray>(this.rows)

    val historyLines: Int get() = hist.count

    val totalLines: Int get() = hist.count + rows

    // -----------------------------------------------------------------
    // 单元格读写
    // -----------------------------------------------------------------

    internal fun cpAt(row: Int, col: Int): Int =
        if (row in 0 until rows && col in 0 until cols) cur.cells[row][col * 2] else CP_BLANK

    internal fun styleAt(row: Int, col: Int): Int =
        if (row in 0 until rows && col in 0 until cols) cur.cells[row][col * 2 + 1] else STYLE_DEFAULT

    internal fun isTrail(row: Int, col: Int): Boolean = cpAt(row, col) == CP_WIDE_TRAIL

    internal fun isWrapped(row: Int): Boolean =
        row in 0 until rows && (cur.flags[row] and ROW_WRAPPED) != 0

    internal fun setWrapped(row: Int, wrapped: Boolean) {
        if (row !in 0 until rows) return
        cur.flags[row] = if (wrapped) cur.flags[row] or ROW_WRAPPED
        else cur.flags[row] and ROW_WRAPPED.inv()
    }

    /**
     * 覆写一格。
     *
     * 关键不变量：**覆写宽字符的任一半都必须把另一半清掉**。不做的话会留下
     * 孤儿半格，`top` 刷新中文时屏幕上全是残影，而且残影会一直传染到历史里。
     */
    internal fun put(row: Int, col: Int, cp: Int, style: Int) {
        if (row !in 0 until rows || col !in 0 until cols) return
        clearWidePartner(row, col)
        val cells = cur.cells[row]
        cells[col * 2] = cp
        cells[col * 2 + 1] = style
        if (col + 1 > cur.used[row]) cur.used[row] = col + 1
    }

    /** 写一个宽字符：col 放码点，col+1 放占位。调用方保证 col+1 < cols。 */
    internal fun putWide(row: Int, col: Int, cp: Int, style: Int) {
        if (row !in 0 until rows || col < 0 || col + 1 >= cols) return
        clearWidePartner(row, col)
        clearWidePartner(row, col + 1)
        val cells = cur.cells[row]
        cells[col * 2] = cp
        cells[col * 2 + 1] = style
        cells[col * 2 + 2] = CP_WIDE_TRAIL
        cells[col * 2 + 3] = style
        if (col + 2 > cur.used[row]) cur.used[row] = col + 2
    }

    private fun clearWidePartner(row: Int, col: Int) {
        val cells = cur.cells[row]
        val i = col * 2
        if (cells[i] == CP_WIDE_TRAIL) {
            if (col > 0) {
                cells[i - 2] = CP_BLANK
            }
        } else if (col + 1 < cols && cells[i + 2] == CP_WIDE_TRAIL) {
            cells[i + 2] = CP_BLANK
        }
    }

    /**
     * 把一个宽度 0 的码点并进已有单元格（组合符 / 变体选择符 / ZWJ）。
     *
     * 不做 UAX #29 完整字素簇分段：那要整套表 + 状态机，成本远超收益，而且
     * 我们和容器里跑的程序必须**按同一套 wcwidth 算列**，多算一格比少画一个
     * 组合符严重得多。已知偏差：`👨‍👩‍👧`（ZWJ 序列）会占 3 格而不是 1 格
     * —— 真终端（含 Termux）在这件事上本就各不相同。
     */
    internal fun appendCombining(row: Int, col: Int, cp: Int) {
        if (row !in 0 until rows || col !in 0 until cols) return
        val cells = cur.cells[row]
        val i = col * 2
        val base = cells[i]
        if (base == CP_WIDE_TRAIL) return
        if (base < 0) {
            val off = base and 0x7FFFFFFF
            if (off >= clusterUsed) return
            val n = clusterPool[off]
            if (n >= MAX_CLUSTER_CPS) return
            if (off + 1 + n == clusterUsed) {
                // 命中率接近 100%：组合符总是紧跟基字符到达，簇就在池尾。
                if (!ensureCluster(1)) return
                clusterPool[clusterUsed++] = cp
                clusterPool[off] = n + 1
            } else {
                // 中间穿插过别的簇，只能复制一份到池尾。罕见。
                if (!ensureCluster(n + 2)) return
                val newOff = clusterUsed
                clusterPool[clusterUsed++] = n + 1
                for (k in 0 until n) clusterPool[clusterUsed++] = clusterPool[off + 1 + k]
                clusterPool[clusterUsed++] = cp
                cells[i] = CP_CLUSTER_FLAG or newOff
            }
            return
        }
        if (!ensureCluster(3)) return
        val newOff = clusterUsed
        clusterPool[clusterUsed++] = 2
        clusterPool[clusterUsed++] = if (base == CP_BLANK) 0x20 else base
        clusterPool[clusterUsed++] = cp
        cells[i] = CP_CLUSTER_FLAG or newOff
        if (col + 1 > cur.used[row]) cur.used[row] = col + 1
    }

    private fun ensureCluster(need: Int): Boolean {
        if (clusterUsed + need > MAX_CLUSTER_INTS) return false
        if (clusterUsed + need <= clusterPool.size) return true
        var cap = clusterPool.size * 2
        while (cap < clusterUsed + need) cap *= 2
        if (cap > MAX_CLUSTER_INTS) cap = MAX_CLUSTER_INTS
        clusterPool = clusterPool.copyOf(cap)
        return true
    }

    // -----------------------------------------------------------------
    // 擦除 / 插入 / 删除
    // -----------------------------------------------------------------

    /** 把 [from, to) 填成空白 + 给定样式。带背景色的擦除必须保留背景，所以带 style。 */
    internal fun blankRun(row: Int, from: Int, to: Int, style: Int) {
        if (row !in 0 until rows) return
        val f = if (from < 0) 0 else from
        val t = if (to > cols) cols else to
        if (f >= t) return
        clearWidePartner(row, f)
        clearWidePartner(row, t - 1)
        val cells = cur.cells[row]
        var i = f * 2
        val end = t * 2
        while (i < end) {
            cells[i] = CP_BLANK
            cells[i + 1] = style
            i += 2
        }
        if (style == STYLE_DEFAULT) {
            // 擦成默认样式的尾巴不需要再画，used 可以缩回去。
            if (t >= cur.used[row] && f < cur.used[row]) cur.used[row] = f
        } else if (t > cur.used[row]) {
            // 有背景色的空格必须画出来，算进 used。
            cur.used[row] = t
        }
    }

    internal fun blankRows(from: Int, to: Int, style: Int) {
        for (r in from until to) {
            if (r !in 0 until rows) continue
            blankRun(r, 0, cols, style)
            cur.flags[r] = 0
        }
    }

    /** ICH：从 col 起插入 n 个空格，行尾溢出的丢弃。 */
    internal fun insertCells(row: Int, col: Int, n: Int, style: Int) {
        if (row !in 0 until rows || col !in 0 until cols || n <= 0) return
        val k = if (n > cols - col) cols - col else n
        val cells = cur.cells[row]
        val move = cols - col - k
        if (move > 0) System.arraycopy(cells, col * 2, cells, (col + k) * 2, move * 2)
        var i = col * 2
        val end = (col + k) * 2
        while (i < end) {
            cells[i] = CP_BLANK
            cells[i + 1] = style
            i += 2
        }
        val u = cur.used[row] + k
        cur.used[row] = if (u > cols) cols else u
        repairWide(row)
    }

    /** DCH：从 col 起删除 n 格，右边整体左移，行尾补空白。 */
    internal fun deleteCells(row: Int, col: Int, n: Int, style: Int) {
        if (row !in 0 until rows || col !in 0 until cols || n <= 0) return
        val k = if (n > cols - col) cols - col else n
        val cells = cur.cells[row]
        val move = cols - col - k
        if (move > 0) System.arraycopy(cells, (col + k) * 2, cells, col * 2, move * 2)
        var i = (cols - k) * 2
        val end = cols * 2
        while (i < end) {
            cells[i] = CP_BLANK
            cells[i + 1] = style
            i += 2
        }
        val u = cur.used[row] - k
        cur.used[row] = if (u < col) col else u
        if (style != STYLE_DEFAULT) cur.used[row] = cols
        repairWide(row)
    }

    /**
     * 移位类操作（ICH/DCH）之后修复被劈开的宽字符。
     *
     * 判据只能靠 wcwidth 重算：cell 里没有"我是宽字符左半格"这一位。
     * 这条路径是 O(cols) + 二分，但 ICH/DCH 本身就是低频操作（行编辑），
     * 不在 `cat` 那种吞吐路径上。
     */
    private fun repairWide(row: Int) {
        val cells = cur.cells[row]
        for (c in 0 until cols) {
            val cp = cells[c * 2]
            if (cp == CP_WIDE_TRAIL) {
                val prev = if (c > 0) cells[(c - 1) * 2] else CP_BLANK
                if (c == 0 || prev == CP_WIDE_TRAIL || TerminalWidth.of(baseCp(prev)) != 2) {
                    cells[c * 2] = CP_BLANK
                }
            } else if (cp != CP_BLANK && TerminalWidth.of(baseCp(cp)) == 2) {
                if (c + 1 >= cols || cells[(c + 1) * 2] != CP_WIDE_TRAIL) {
                    cells[c * 2] = CP_BLANK
                }
            }
        }
    }

    /** 取一格的基字符码点（簇取第一个）。 */
    internal fun baseCp(cp: Int): Int {
        if (cp >= 0) return cp
        val off = cp and 0x7FFFFFFF
        return if (off + 1 < clusterUsed) clusterPool[off + 1] else 0x20
    }

    // -----------------------------------------------------------------
    // 滚动
    // -----------------------------------------------------------------

    /**
     * 滚动区 [top, bottom]（闭区间）内上滚 n 行。
     *
     * 实现是**轮换行引用**，不是搬 cell 数据：一次滚动只动几十个引用，
     * 而不是 cols*rows 个 int。`make` 的日志一秒钟能滚几千行，这里差一个量级。
     *
     * [toHistory] 由调用方判断（只有主屏 + 滚动区就是整屏时才为 true）：
     * vi 把底部一行留作状态栏时滚动区是 `1;23`，那种滚动**不该**进回滚缓冲，
     * 否则历史里全是状态栏的碎片。
     */
    internal fun scrollUp(top: Int, bottom: Int, n: Int, style: Int, toHistory: Boolean) {
        if (top < 0 || bottom >= rows || top > bottom || n <= 0) return
        val count = bottom - top + 1
        val k = if (n > count) count else n
        val cells = cur.cells
        for (i in 0 until k) {
            val r = top + i
            if (toHistory) hist.push(cells[r], minOf(cur.used[r], cols), cur.flags[r])
            tmpCells[i] = cells[r]
        }
        val rest = count - k
        if (rest > 0) {
            System.arraycopy(cells, top + k, cells, top, rest)
            System.arraycopy(cur.used, top + k, cur.used, top, rest)
            System.arraycopy(cur.flags, top + k, cur.flags, top, rest)
        }
        for (i in 0 until k) {
            val r = bottom - k + 1 + i
            cells[r] = tmpCells[i]!!
            cur.used[r] = 0
            cur.flags[r] = 0
            blankRun(r, 0, cols, style)
        }
    }

    /** 滚动区内下滚 n 行（SD / RI / IL）。永远不进回滚缓冲。 */
    internal fun scrollDown(top: Int, bottom: Int, n: Int, style: Int) {
        if (top < 0 || bottom >= rows || top > bottom || n <= 0) return
        val count = bottom - top + 1
        val k = if (n > count) count else n
        val cells = cur.cells
        for (i in 0 until k) {
            tmpCells[i] = cells[bottom - k + 1 + i]
        }
        val rest = count - k
        if (rest > 0) {
            System.arraycopy(cells, top, cells, top + k, rest)
            System.arraycopy(cur.used, top, cur.used, top + k, rest)
            System.arraycopy(cur.flags, top, cur.flags, top + k, rest)
        }
        for (i in 0 until k) {
            val r = top + i
            cells[r] = tmpCells[i]!!
            cur.used[r] = 0
            cur.flags[r] = 0
            blankRun(r, 0, cols, style)
        }
    }

    // -----------------------------------------------------------------
    // 备用屏 / 复位
    // -----------------------------------------------------------------

    /**
     * 切备用屏。备用屏**懒分配**，而且**永远没有回滚**（xterm 语义）——
     * 不遵守的话 vim 滚一次就把回滚缓冲填满垃圾，用户上滑看到的全是编辑器残影。
     */
    fun setAlternate(on: Boolean) {
        if (on == altActive) return
        if (on) {
            var a = alt
            if (a == null || a.cols != cols || a.rows != rows) {
                a = Screen(cols, rows)
                alt = a
            }
            cur = a
            altActive = true
        } else {
            cur = main
            altActive = false
        }
    }

    /** 清空备用屏内容（`?1049h` 语义里的"切过去顺便清屏"）。 */
    fun clearAlternate(style: Int) {
        val a = alt ?: return
        val prev = cur
        cur = a
        blankRows(0, a.rows, style)
        cur = prev
    }

    fun clearHistory() {
        hist.clear()
    }

    /**
     * RIS。连组合簇池和 style 表一起清 —— 这是唯一能安全清它们的时机：
     * 历史行里存着指向池子的偏移和 style id，中途清就是悬垂引用。
     * 清完之后一切归 0 号样式，所以这里不接收 style 参数。
     */
    fun reset() {
        cur = main
        altActive = false
        alt = null
        hist.clear()
        clusterUsed = 0
        styles.reset()
        blankRows(0, rows, STYLE_DEFAULT)
    }

    // -----------------------------------------------------------------
    // 绝对行号读访问（快照 / 选区 / rewrap 共用）
    // -----------------------------------------------------------------

    private fun histCells(line: Int): IntArray? = hist.cells[hist.slot(line)]
    private fun histLen(line: Int): Int = hist.len[hist.slot(line)]
    private fun histFlags(line: Int): Int = hist.flags[hist.slot(line)]

    internal fun absCells(absLine: Int): IntArray? = when {
        absLine < 0 -> null
        absLine < hist.count -> histCells(absLine)
        absLine < hist.count + rows -> cur.cells[absLine - hist.count]
        else -> null
    }

    internal fun absLen(absLine: Int): Int = when {
        absLine < 0 -> 0
        absLine < hist.count -> histLen(absLine)
        absLine < hist.count + rows -> {
            val u = cur.used[absLine - hist.count]
            if (u > cols) cols else u
        }
        else -> 0
    }

    internal fun absWrapped(absLine: Int): Boolean = when {
        absLine < 0 -> false
        absLine < hist.count -> histFlags(absLine) and ROW_WRAPPED != 0
        absLine < hist.count + rows -> cur.flags[absLine - hist.count] and ROW_WRAPPED != 0
        else -> false
    }

    // -----------------------------------------------------------------
    // 快照
    // -----------------------------------------------------------------

    /** 把 topLine 夹到合法范围。UI 侧滚动时直接用它，省得自己算边界。 */
    fun clampTopLine(topLine: Int, rowCount: Int): Int {
        val maxTop = totalLines - rowCount
        return when {
            maxTop <= 0 -> 0
            topLine < 0 -> 0
            topLine > maxTop -> maxTop
            else -> topLine
        }
    }

    /**
     * 把 `[topLine, topLine + rowCount)` 这段可见区域填进 [out]。
     *
     * 输出按 run 合并（同一行 + 连续列 + 同一 style）。真实屏幕上：纯文本行
     * 1-2 个 run，`ls --color` 一行 5-15 个，带语法高亮的 vim 一行 5-10 个
     * —— 80x40 整屏典型 150-500 个 run，渲染侧一个 run 一次 drawText，
     * 约 2 ms/帧且零分配。逐行 `Text(AnnotatedString)` 是 800+ 对象/帧。
     *
     * 反显（SGR 7）和隐藏（SGR 8）在**这里**解析完：fg/bg 已经换好，
     * 渲染侧不用管。粗体提亮留给渲染侧（那需要调色板知识）。
     */
    fun fillSnapshot(topLine: Int, rowCount: Int, out: TerminalSnapshot) {
        val rc = if (rowCount > out.capRows) out.capRows else rowCount
        val top = clampTopLine(topLine, rc)
        out.begin(cols, rc)
        out.meta[TerminalSnapshot.META_TOTAL_LINES] = totalLines
        out.meta[TerminalSnapshot.META_TOP_LINE] = top

        val textCap = out.text.size
        val runCap = out.runs.size / TerminalSnapshot.RUN_STRIDE
        var textLen = 0
        var runCount = 0
        var flags = 0

        for (r in 0 until rc) {
            val abs = top + r
            val cells = absCells(abs) ?: continue
            // capCols 也要夹一道：UI 还没来得及按新尺寸重新分配快照时不能越界写。
            // 注意是嵌套的两参 minOf：四参版本走的是 `minOf(vararg T: Comparable)`，
            // 会装箱 + 建数组，实测每帧 1 KB 垃圾。这条在每帧每行都跑。
            val limit = minOf(minOf(absLen(abs), cols), minOf(cells.size / 2, out.capCols))
            var c = 0
            while (c < limit) {
                val styleId = cells[c * 2 + 1]
                // ---- 开一个 run ----
                val runColStart = c
                val runTextStart = textLen
                var ascii = true
                var ok = true
                while (c < limit) {
                    val cp = cells[c * 2]
                    if (cp != CP_WIDE_TRAIL && cells[c * 2 + 1] != styleId) break
                    if (cp == CP_WIDE_TRAIL) {
                        // 右半格不产生 code unit：宽字符由前一格的码点整体绘制。
                        c++
                        continue
                    }
                    val before = textLen
                    textLen = appendCell(out.text, textLen, cp)
                    if (textLen < 0) {
                        textLen = before
                        flags = flags or TerminalSnapshot.FLAG_TEXT_TRUNCATED
                        ok = false
                        break
                    }
                    if (ascii) {
                        var k = before
                        while (k < textLen) {
                            val ch = out.text[k].code
                            if (ch < 0x20 || ch > 0x7E) {
                                ascii = false
                                break
                            }
                            k++
                        }
                        if (textLen - before != 1) ascii = false
                    }
                    c++
                }
                val colCount = c - runColStart
                if (colCount > 0 && runCount < runCap) {
                    // ASCII 快路径要求"一列一个 code unit"，宽字符即使是 ASCII 也不算。
                    if (textLen - runTextStart != colCount) ascii = false
                    val base = runCount * TerminalSnapshot.RUN_STRIDE
                    val dense = mapStyle(out, styleId)
                    out.runs[base] = r
                    out.runs[base + 1] = runColStart
                    out.runs[base + 2] = colCount
                    out.runs[base + 3] = runTextStart
                    out.runs[base + 4] = textLen - runTextStart
                    out.runs[base + 5] =
                        dense or (if (ascii) TerminalSnapshot.RUN_ASCII_BIT else 0)
                    runCount++
                } else if (colCount > 0) {
                    flags = flags or TerminalSnapshot.FLAG_RUNS_TRUNCATED
                }
                if (!ok) break
                if (colCount == 0) c++ // 防御：任何情况下都必须推进，不能原地打转
            }
        }

        out.meta[TerminalSnapshot.META_TEXT_LEN] = textLen
        out.meta[TerminalSnapshot.META_RUN_COUNT] = runCount
        out.meta[TerminalSnapshot.META_FLAGS] = flags
        // META_STYLE_COUNT 由 mapStyle 一路维护，这里不要覆盖。
    }

    /** 源 style id → 本帧稠密下标，顺带解析反显 / 隐藏。 */
    private fun mapStyle(out: TerminalSnapshot, id: Int): Int {
        if (id < 0 || id >= StyleTable.MAX_STYLES) return 0
        if (out.styleStamp[id] == out.stamp) return out.styleMap[id]
        var fg = styles.fg(id)
        var bg = styles.bg(id)
        val fl = styles.flags(id)
        if (fl and ATTR_INVERSE != 0) {
            val t = fg
            fg = bg
            bg = t
        }
        if (fl and ATTR_HIDDEN != 0) fg = bg
        // 找一个空位。表满时全部回落到 0 号（默认样式），只掉色不出错。
        var dense = out.meta[TerminalSnapshot.META_STYLE_COUNT]
        if (dense >= TerminalSnapshot.MAX_SNAPSHOT_STYLES) {
            dense = 0
        } else {
            out.styles[dense * 3] = fg
            out.styles[dense * 3 + 1] = bg
            out.styles[dense * 3 + 2] = fl
            out.meta[TerminalSnapshot.META_STYLE_COUNT] = dense + 1
        }
        out.styleStamp[id] = out.stamp
        out.styleMap[id] = dense
        return dense
    }

    /** 把一格展开成 UTF-16 写进 [dst]；放不下返回 -1（调用方负责置截断标志）。 */
    private fun appendCell(dst: CharArray, pos: Int, cp: Int): Int {
        if (cp == CP_BLANK) {
            if (pos >= dst.size) return -1
            dst[pos] = ' '
            return pos + 1
        }
        if (cp >= 0) return appendCodePoint(dst, pos, cp)
        val off = cp and 0x7FFFFFFF
        if (off >= clusterUsed) return appendCodePoint(dst, pos, 0x20)
        val n = clusterPool[off]
        var p = pos
        for (k in 0 until n) {
            p = appendCodePoint(dst, p, clusterPool[off + 1 + k])
            if (p < 0) return -1
        }
        return p
    }

    private fun appendCodePoint(dst: CharArray, pos: Int, cp: Int): Int {
        if (cp < 0x10000) {
            if (pos >= dst.size) return -1
            dst[pos] = cp.toChar()
            return pos + 1
        }
        if (pos + 1 >= dst.size) return -1
        val v = cp - 0x10000
        dst[pos] = (0xD800 + (v ushr 10)).toChar()
        dst[pos + 1] = (0xDC00 + (v and 0x3FF)).toChar()
        return pos + 2
    }

    // -----------------------------------------------------------------
    // 取文本（选区复制 / 给 AI 看屏幕）
    // -----------------------------------------------------------------

    /**
     * 取 `[line0, col0]` 到 `[line1, col1]`（含）之间的文本，绝对行号。
     *
     * [joinWrapped] = true（复制选区的语义）时，自动换行处**不插入换行符**
     * —— 这是所有终端复制功能最经典的 bug：复制一条被折了三次的长命令，
     * 粘出来变成三行、直接执行错。ROW_WRAPPED 这一位就是为这件事存在的。
     *
     * = false（屏幕转储的语义）时每个物理行一条 —— 给 AI 看屏幕、给自检比对
     * 用的是"用户眼睛看到的样子"，那就必须按物理行来。
     */
    fun text(line0: Int, col0: Int, line1: Int, col1: Int, joinWrapped: Boolean = true): String {
        val total = totalLines
        val a = if (line0 < 0) 0 else line0
        val b = if (line1 >= total) total - 1 else line1
        if (a > b || total <= 0) return ""
        // 先算总长再 reserve：逐行 `s = s + rowText` 累加是 O(n²)，
        // 长选区（几千行）复制一次能卡住半秒。
        var estimate = 0
        var l = a
        while (l <= b) {
            estimate += absLen(l) + 1
            l++
        }
        val sb = StringBuilder(if (estimate < 16) 16 else estimate)
        l = a
        while (l <= b) {
            appendRow(sb, l, if (l == a) col0 else 0, if (l == b) col1 + 1 else cols)
            if (l != b && !(joinWrapped && absWrapped(l))) sb.append('\n')
            l++
        }
        return sb.toString()
    }

    /**
     * 当前可见屏幕的纯文本转储，**一个物理行一条**（不合并自动换行）。
     * 给 AI 工具（`terminal_snapshot`）和自检比对用。
     */
    fun screenText(): String =
        text(hist.count, 0, hist.count + rows - 1, cols - 1, joinWrapped = false)

    /** 行尾的空白一律不输出（按 used 截断），复制出去的文本才不会拖一串空格。 */
    private fun appendRow(sb: StringBuilder, absLine: Int, from: Int, to: Int) {
        val cells = absCells(absLine) ?: return
        val limit = minOf(minOf(absLen(absLine), cols), minOf(cells.size / 2, to))
        var c = if (from < 0) 0 else from
        while (c < limit) {
            val cp = cells[c * 2]
            if (cp != CP_WIDE_TRAIL) appendCpTo(sb, cp)
            c++
        }
    }

    private fun appendCpTo(sb: StringBuilder, cp: Int) {
        if (cp == CP_BLANK) {
            sb.append(' ')
            return
        }
        if (cp >= 0) {
            sb.appendCodePoint(cp)
            return
        }
        val off = cp and 0x7FFFFFFF
        if (off >= clusterUsed) {
            sb.append(' ')
            return
        }
        val n = clusterPool[off]
        for (k in 0 until n) sb.appendCodePoint(clusterPool[off + 1 + k])
    }

    // -----------------------------------------------------------------
    // resize
    // -----------------------------------------------------------------

    /**
     * 改行列。
     *
     * [marks] 是"要跟着内容一起搬的锚点"，按 `(绝对行号, 列)` 成对存放，
     * **原地改写**成新坐标。至少要传两个：
     *   * 光标 —— 不带着走的话 resize 后 prompt 会跳到莫名其妙的位置；
     *   * 视口顶行 —— 不带着走的话每次转屏 / 弹键盘视图都会跳到底部，
     *     这是很多手机终端的通病。
     *
     * 列数变了才做真正的 rewrap（O(总 cell)）；只有行数变时走便宜的
     * 推历史 / 拉历史路径。**调用方必须自己 debounce**：Compose 的 IME
     * 弹出动画会在 200 ms 内触发几十次 onSizeChanged，每次全量 rewrap
     * 就是几百毫秒的卡顿。
     */
    fun resize(newCols: Int, newRows: Int, marks: IntArray) {
        val nc = if (newCols < 2) 2 else newCols
        val nr = if (newRows < 1) 1 else newRows
        if (nc == cols && nr == rows) return

        val evBefore = hist.evicted
        if (altActive) {
            // 备用屏不参与 rewrap（xterm 语义：它没有历史，重排也没有意义）。
            // 主屏在后台，同样只做裁剪 —— 它的光标由 DECRC 恢复时再 clamp。
            resizeClip(main, nc, nr)
            alt?.let { resizeClip(it, nc, nr) }
            cols = nc
            rows = nr
            growTmp()
            shiftMarks(marks, hist.evicted - evBefore)
            clampMarks(marks)
            return
        }

        if (nc == cols) {
            resizeRowsOnly(nr)
            rows = nr
            growTmp()
            shiftMarks(marks, hist.evicted - evBefore)
            clampMarks(marks)
            alt?.let { resizeClip(it, nc, nr) }
            return
        }

        reflow(nc, nr, marks)
        alt?.let { resizeClip(it, nc, nr) }
        clampMarks(marks)
    }

    private fun growTmp() {
        if (tmpCells.size < rows) tmpCells = arrayOfNulls(rows)
    }

    private fun shiftMarks(marks: IntArray, evicted: Int) {
        if (evicted == 0) return
        var i = 0
        while (i + 1 < marks.size) {
            marks[i] -= evicted
            if (marks[i] < 0) marks[i] = 0
            i += 2
        }
    }

    private fun clampMarks(marks: IntArray) {
        var i = 0
        val total = totalLines
        while (i + 1 < marks.size) {
            if (marks[i] < 0) marks[i] = 0
            if (marks[i] > total - 1) marks[i] = total - 1
            if (marks[i + 1] < 0) marks[i + 1] = 0
            if (marks[i + 1] > cols - 1) marks[i + 1] = cols - 1
            i += 2
        }
    }

    /**
     * 只改行数。
     *
     * 变矮：先丢光标下面的空行，不够再把**顶部的行推进回滚缓冲**（不是丢弃！
     * 丢弃的话 prompt 会"往上跳没了"）。变高：从回滚缓冲**拉回**行填顶部，
     * xterm / Termux 都是这个行为。
     *
     * 绝对行号在这两种操作下天然不变（历史 +k 而屏幕行 -k），所以除了
     * 淘汰造成的整体平移之外，marks 不用动。
     */
    private fun resizeRowsOnly(newRows: Int) {
        val s = main
        if (newRows == s.rows) return
        if (newRows < s.rows) {
            var excess = s.rows - newRows
            var lastUsed = s.rows - 1
            while (lastUsed > 0 && s.used[lastUsed] == 0) lastUsed--
            val dropBottom = minOf(excess, s.rows - 1 - lastUsed)
            excess -= dropBottom
            if (excess > 0) scrollUp(0, s.rows - 1, excess, STYLE_DEFAULT, toHistory = true)
            val ns = Screen(cols, newRows)
            for (r in 0 until newRows) {
                ns.cells[r] = s.cells[r]
                ns.used[r] = s.used[r]
                ns.flags[r] = s.flags[r]
            }
            main = ns
            if (!altActive) cur = ns
        } else {
            val pull = minOf(newRows - s.rows, hist.count)
            val ns = Screen(cols, newRows)
            for (r in 0 until s.rows) {
                ns.cells[r + pull] = s.cells[r]
                ns.used[r + pull] = s.used[r]
                ns.flags[r + pull] = s.flags[r]
            }
            // 从历史尾部往回拉，注意是倒着填。
            for (i in 0 until pull) {
                val slot = hist.popTail()
                if (slot < 0) break
                val src = hist.cells[slot]
                val n = hist.len[slot]
                val dst = ns.cells[pull - 1 - i]
                if (src != null && n > 0) {
                    System.arraycopy(src, 0, dst, 0, minOf(n, cols) * 2)
                }
                ns.used[pull - 1 - i] = minOf(n, cols)
                ns.flags[pull - 1 - i] = hist.flags[slot]
            }
            main = ns
            if (!altActive) cur = ns
        }
    }

    /** 裁剪 / 补齐，不重排。备用屏和后台主屏走这条。 */
    private fun resizeClip(s: Screen, newCols: Int, newRows: Int) {
        if (s.cols == newCols && s.rows == newRows) return
        val ns = Screen(newCols, newRows)
        val copyRows = minOf(s.rows, newRows)
        val copyCols = minOf(s.cols, newCols)
        for (r in 0 until copyRows) {
            System.arraycopy(s.cells[r], 0, ns.cells[r], 0, copyCols * 2)
            ns.used[r] = minOf(s.used[r], copyCols)
            ns.flags[r] = s.flags[r]
            // 右边界正好切在宽字符中间时，把孤儿半格抹掉。
            if (copyCols > 0 && ns.cells[r][(copyCols - 1) * 2] != CP_WIDE_TRAIL) {
                val cp = ns.cells[r][(copyCols - 1) * 2]
                if (cp != CP_BLANK && TerminalWidth.of(baseCp(cp)) == 2) {
                    ns.cells[r][(copyCols - 1) * 2] = CP_BLANK
                }
            }
        }
        s.cells = ns.cells
        s.used = ns.used
        s.flags = ns.flags
        s.cols = newCols
        s.rows = newRows
    }

    /**
     * 真 rewrap：按**逻辑行**（连续 WRAPPED 段）把历史 + 屏幕重新流式排版。
     *
     * 双游标、单趟、**不物化中间逻辑行**：一条逻辑行可以长达十万列
     * （`cat` 一个没有换行的大文件），先 join 成一个大字符串再切就是 O(L²)。
     *
     * 另一条被明确否决的路线是"保留原始字节流，resize 时重跑解析器"：
     * 那要留住所有字节，还会把带副作用的序列（DA 回复、改标题）重放一遍。
     *
     * 峰值内存是两份回滚缓冲（旧的还要读，新的在写），约 +2-4 MB。
     * resize 是低频且被 debounce 过的路径，这个代价换来的是实现上的绝对安全。
     */
    private fun reflow(newCols: Int, newRows: Int, marks: IntArray) {
        val src = main
        val histCount = hist.count

        // 源范围：全部历史 + 屏幕 [0, lastRow]。lastRow 至少要盖住光标那一行，
        // 否则光标下面的空行被吃掉，prompt 会莫名其妙上移。
        var lastRow = src.rows - 1
        while (lastRow > 0 && src.used[lastRow] == 0) lastRow--
        var i = 0
        while (i + 1 < marks.size) {
            val r = marks[i] - histCount
            if (r in 0 until src.rows && r > lastRow) lastRow = r
            i += 2
        }
        val totalSrc = histCount + lastRow + 1

        val out = History(histCap, maxBytes)
        val scratch = IntArray(newCols * 2)
        var dstCol = 0
        var emitted = 0

        // 最后 newRows 行要进屏幕，但**不能指望从 out 里读回来**：
        // scrollbackLines 配成 0（或小于屏幕高度）时，它们早就被环挤掉了。
        // 所以另开一个 newRows 大小的循环 tail 专门留内容，代价是一屏的内存。
        val tailCells = arrayOfNulls<IntArray>(newRows)
        val tailLen = IntArray(newRows)
        val tailFlags = IntArray(newRows)

        val markCount = marks.size / 2
        val markEmit = IntArray(markCount) { -1 }
        val markCol = IntArray(markCount)

        var line = 0
        while (line < totalSrc) {
            // walk 从逻辑行的第一物理行一路走到它的最后一行（连续 WRAPPED 段）。
            var walk = line
            while (true) {
                val cells = absCells(walk)
                val len = if (cells == null) 0 else minOf(absLen(walk), cells.size / 2)
                var c = 0
                while (c < len && cells != null) {
                    val cp = cells[c * 2]
                    if (cp == CP_WIDE_TRAIL) {
                        c++
                        continue
                    }
                    val w = if (c + 1 < len && cells[(c + 1) * 2] == CP_WIDE_TRAIL) 2 else 1
                    if (dstCol + w > newCols) {
                        out.push(scratch, dstCol, ROW_WRAPPED)
                        keepTail(tailCells, tailLen, tailFlags, emitted, scratch, dstCol, ROW_WRAPPED)
                        emitted++
                        java.util.Arrays.fill(scratch, 0)
                        dstCol = 0
                    }
                    var m = 0
                    while (m < markCount) {
                        if (markEmit[m] < 0 && marks[m * 2] == walk &&
                            (marks[m * 2 + 1] == c || (w == 2 && marks[m * 2 + 1] == c + 1))
                        ) {
                            markEmit[m] = emitted
                            markCol[m] = dstCol + (marks[m * 2 + 1] - c)
                        }
                        m++
                    }
                    scratch[dstCol * 2] = cp
                    scratch[dstCol * 2 + 1] = cells[c * 2 + 1]
                    if (w == 2) {
                        scratch[dstCol * 2 + 2] = CP_WIDE_TRAIL
                        scratch[dstCol * 2 + 3] = cells[c * 2 + 1]
                    }
                    dstCol += w
                    c += w
                }
                // 锚点落在内容之后（光标停在行尾空白处，最常见的情况）。
                var m = 0
                while (m < markCount) {
                    if (markEmit[m] < 0 && marks[m * 2] == walk && marks[m * 2 + 1] >= len) {
                        markEmit[m] = emitted
                        var col = dstCol + (marks[m * 2 + 1] - len)
                        if (col > newCols - 1) col = newCols - 1
                        markCol[m] = col
                    }
                    m++
                }
                if (absWrapped(walk) && walk + 1 < totalSrc) {
                    walk++
                } else {
                    break
                }
            }
            out.push(scratch, dstCol, 0)
            keepTail(tailCells, tailLen, tailFlags, emitted, scratch, dstCol, 0)
            emitted++
            java.util.Arrays.fill(scratch, 0)
            dstCol = 0
            line = walk + 1
        }

        // 屏幕取输出的**最后 newRows 行**。
        //
        // 这里曾经有一句「光标在屏外就把 screenStart 往上让到 markEmit[0]」，
        // 那是错的：tailCells 是一个只有 newRows 个槽的**循环**缓冲，里面只
        // 可能留着 [emitted-newRows, emitted-1] 这一段。screenStart 一旦小于
        // emitted-newRows，下面 `(screenStart + k) % newRows` 取到的就不是第
        // screenStart+k 行，而是最后一屏按 (screenStart-那个下界) mod newRows
        // **整体轮转**过的结果 —— 屏幕上的行会错位重排，而不是「少显示几行」。
        //
        // 触发条件：光标下方的内容 rewrap 之后超过一屏。例如 100 列缩到 20 列
        // （转屏 / 改字号），光标停在第 5 行而第 6..23 行是满的：18 行 × 5 = 90
        // 行 > 24 行，条件成立，整屏内容错乱。
        //
        // 而且 `markEmit[0] in 0 until screenStart` 这个判断**只可能**在上述
        // 越界情形下为真（否则光标本来就落在最后一屏里），所以这句话除了制造
        // 错乱之外没有别的作用，直接删掉。代价是这种情况下光标会被
        // TerminalEmulator.resize 夹到第 0 行 —— 位置不准，但屏幕是对的。
        val screenStart = if (emitted > newRows) emitted - newRows else 0
        val take = minOf(newRows, emitted - screenStart)

        // 进了屏幕的行不该同时留在历史里，从环尾弹掉。
        // 环可能比这个数还短，popTail 返回 -1 就停。
        var pop = take
        while (pop > 0 && out.popTail() >= 0) pop--

        val ns = Screen(newCols, newRows)
        for (k in 0 until take) {
            val slot = (screenStart + k) % newRows
            val s = tailCells[slot]
            val n = minOf(tailLen[slot], newCols)
            if (s != null && n > 0) System.arraycopy(s, 0, ns.cells[k], 0, n * 2)
            ns.used[k] = n
            ns.flags[k] = tailFlags[slot]
        }
        // 最后一屏的最后一行不该带 WRAPPED（后面没有内容接着了）。
        if (take > 0) ns.flags[take - 1] = ns.flags[take - 1] and ROW_WRAPPED.inv()

        hist = out
        main = ns
        cur = ns
        cols = newCols
        rows = newRows
        growTmp()

        // 输出序号 → 新的绝对行号。屏幕行和历史行分开算：histCap 比屏幕还小时
        // （scrollbackLines = 0）两者不再等价，用一个公式糊过去会让光标错位。
        val histLeft = out.count
        var m = 0
        while (m < markCount) {
            val emit = markEmit[m]
            if (emit >= 0) {
                val abs = if (emit >= screenStart) {
                    histLeft + (emit - screenStart)
                } else {
                    val h = emit - out.evicted
                    if (h < 0) 0 else h
                }
                marks[m * 2] = abs
                marks[m * 2 + 1] = markCol[m]
            }
            m++
        }
    }

    /** 把刚发出的一行留一份在 tail 循环缓冲里（rewrap 收尾要用它填屏幕）。 */
    private fun keepTail(
        cells: Array<IntArray?>, lens: IntArray, flags: IntArray,
        emitIndex: Int, src: IntArray, used: Int, rowFlags: Int
    ) {
        val slot = emitIndex % cells.size
        var arr = cells[slot]
        if (arr == null || arr.size < used * 2) {
            arr = IntArray(if (used * 2 < 16) 16 else used * 2)
            cells[slot] = arr
        }
        if (used > 0) System.arraycopy(src, 0, arr, 0, used * 2)
        lens[slot] = used
        flags[slot] = rowFlags
    }
}

// =====================================================================
// 字符宽度
// =====================================================================

/**
 * 自带的 wcwidth。
 *
 * **不能用 bionic 的 `wcwidth`**：它跟 locale 走，Android 上版本老旧且各家
 * ROM 不一致。而宽度判断只要和**容器里跑的程序**（静态链接自己 libc 的
 * busybox / neatvi / bash）不一致，光标算术就会漂移，表现是"输入中文之后
 * 光标位置和字符对不上，越打越歪"。宁可自己错得一致，也不要跟着系统摇摆。
 *
 * 三条规则：
 *  - 宽度 2：East_Asian_Width = W / F，以及 Emoji_Presentation。
 *  - 宽度 0：Mn/Me/Cf 组合符、变体选择符、ZWJ、Hangul Jamo 中缀、tag 字符。
 *  - 宽度 1：其余，**含 East_Asian_Width = Ambiguous**（`°` `→` 希腊/西里尔）。
 *    musl 和 glibc 默认都算 1，跟它们对齐；想让 CJK 用户把 Ambiguous 当 2
 *    可以做成设置项，但**默认必须是 1**。
 *
 * 已知且刻意不做的：VS16（U+FE0F）跟在文本形态 emoji 后面，理论上应该把
 * 基字符提升成宽度 2。我们不做这种回溯提升 —— 回溯会让我们和容器里程序的
 * wcwidth 产生分歧，而分歧比"某个 emoji 少占一格"严重得多。
 *
 * 表是排好序的闭区间，二分查。零宽表先查、宽表后查，两张表里重叠的那几段
 * （如 U+3099-309A 落在 U+3000-303E 之内）自然按零宽解释，这是对的。
 */
internal object TerminalWidth {

    fun of(cp: Int): Int {
        if (cp < 0) return -1
        if (cp < 0x20 || cp == 0x7F) return -1          // 控制字符不打印
        if (cp < 0x7F) return 1                         // ASCII 快路径
        if (cp < 0x300) return 1                        // Latin-1 / 扩展拉丁，最常见的非 ASCII
        if (inRange(ZERO, cp)) return 0
        if (inRange(WIDE, cp)) return 2
        return 1
    }

    private fun inRange(table: IntArray, cp: Int): Boolean {
        var lo = 0
        var hi = table.size / 2 - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            when {
                cp < table[mid * 2] -> hi = mid - 1
                cp > table[mid * 2 + 1] -> lo = mid + 1
                else -> return true
            }
        }
        return false
    }

    /** 宽度 0：组合符 / 格式字符 / 变体选择符 / ZWJ / tag。 */
    private val ZERO = intArrayOf(
        0x0300, 0x036F, 0x0483, 0x0489, 0x0591, 0x05BD, 0x05BF, 0x05BF,
        0x05C1, 0x05C2, 0x05C4, 0x05C5, 0x05C7, 0x05C7, 0x0610, 0x061A,
        0x064B, 0x065F, 0x0670, 0x0670, 0x06D6, 0x06DC, 0x06DF, 0x06E4,
        0x06E7, 0x06E8, 0x06EA, 0x06ED, 0x0711, 0x0711, 0x0730, 0x074A,
        0x07A6, 0x07B0, 0x07EB, 0x07F3, 0x0816, 0x0819, 0x081B, 0x0823,
        0x0825, 0x0827, 0x0829, 0x082D, 0x0859, 0x085B, 0x08D4, 0x0902,
        0x093A, 0x093A, 0x093C, 0x093C, 0x0941, 0x0948, 0x094D, 0x094D,
        0x0951, 0x0957, 0x0962, 0x0963, 0x0981, 0x0981, 0x09BC, 0x09BC,
        0x09C1, 0x09C4, 0x09CD, 0x09CD, 0x09E2, 0x09E3, 0x0A01, 0x0A02,
        0x0A3C, 0x0A3C, 0x0A41, 0x0A42, 0x0A47, 0x0A48, 0x0A4B, 0x0A4D,
        0x0A51, 0x0A51, 0x0A70, 0x0A71, 0x0A75, 0x0A75, 0x0A81, 0x0A82,
        0x0ABC, 0x0ABC, 0x0AC1, 0x0AC5, 0x0AC7, 0x0AC8, 0x0ACD, 0x0ACD,
        0x0AE2, 0x0AE3, 0x0B01, 0x0B01, 0x0B3C, 0x0B3C, 0x0B3F, 0x0B3F,
        0x0B41, 0x0B44, 0x0B4D, 0x0B4D, 0x0B56, 0x0B56, 0x0B62, 0x0B63,
        0x0B82, 0x0B82, 0x0BC0, 0x0BC0, 0x0BCD, 0x0BCD, 0x0C00, 0x0C00,
        0x0C3E, 0x0C40, 0x0C46, 0x0C48, 0x0C4A, 0x0C4D, 0x0C55, 0x0C56,
        0x0C62, 0x0C63, 0x0C81, 0x0C81, 0x0CBC, 0x0CBC, 0x0CBF, 0x0CBF,
        0x0CC6, 0x0CC6, 0x0CCC, 0x0CCD, 0x0CE2, 0x0CE3, 0x0D01, 0x0D01,
        0x0D41, 0x0D44, 0x0D4D, 0x0D4D, 0x0D62, 0x0D63, 0x0DCA, 0x0DCA,
        0x0DD2, 0x0DD4, 0x0DD6, 0x0DD6, 0x0E31, 0x0E31, 0x0E34, 0x0E3A,
        0x0E47, 0x0E4E, 0x0EB1, 0x0EB1, 0x0EB4, 0x0EB9, 0x0EBB, 0x0EBC,
        0x0EC8, 0x0ECD, 0x0F18, 0x0F19, 0x0F35, 0x0F35, 0x0F37, 0x0F37,
        0x0F39, 0x0F39, 0x0F71, 0x0F7E, 0x0F80, 0x0F84, 0x0F86, 0x0F87,
        0x0F8D, 0x0F97, 0x0F99, 0x0FBC, 0x0FC6, 0x0FC6, 0x102D, 0x1030,
        0x1032, 0x1037, 0x1039, 0x103A, 0x103D, 0x103E, 0x1058, 0x1059,
        0x105E, 0x1060, 0x1071, 0x1074, 0x1082, 0x1082, 0x1085, 0x1086,
        0x108D, 0x108D, 0x109D, 0x109D, 0x1160, 0x11FF, 0x135D, 0x135F,
        0x1712, 0x1714, 0x1732, 0x1734, 0x1752, 0x1753, 0x1772, 0x1773,
        0x17B4, 0x17B5, 0x17B7, 0x17BD, 0x17C6, 0x17C6, 0x17C9, 0x17D3,
        0x17DD, 0x17DD, 0x180B, 0x180E, 0x18A9, 0x18A9, 0x1920, 0x1922,
        0x1927, 0x1928, 0x1932, 0x1932, 0x1939, 0x193B, 0x1A17, 0x1A18,
        0x1A1B, 0x1A1B, 0x1A56, 0x1A56, 0x1A58, 0x1A5E, 0x1A60, 0x1A60,
        0x1A62, 0x1A62, 0x1A65, 0x1A6C, 0x1A73, 0x1A7C, 0x1A7F, 0x1A7F,
        0x1AB0, 0x1ABE, 0x1B00, 0x1B03, 0x1B34, 0x1B34, 0x1B36, 0x1B3A,
        0x1B3C, 0x1B3C, 0x1B42, 0x1B42, 0x1B6B, 0x1B73, 0x1B80, 0x1B81,
        0x1BA2, 0x1BA5, 0x1BA8, 0x1BA9, 0x1BAB, 0x1BAD, 0x1BE6, 0x1BE6,
        0x1BE8, 0x1BE9, 0x1BED, 0x1BED, 0x1BEF, 0x1BF1, 0x1C2C, 0x1C33,
        0x1C36, 0x1C37, 0x1CD0, 0x1CD2, 0x1CD4, 0x1CE0, 0x1CE2, 0x1CE8,
        0x1CED, 0x1CED, 0x1CF4, 0x1CF4, 0x1CF8, 0x1CF9, 0x1DC0, 0x1DF9,
        0x1DFB, 0x1DFF, 0x200B, 0x200F, 0x202A, 0x202E, 0x2060, 0x2064,
        0x2066, 0x206F, 0x20D0, 0x20F0, 0x2CEF, 0x2CF1, 0x2D7F, 0x2D7F,
        0x2DE0, 0x2DFF, 0x302A, 0x302D, 0x3099, 0x309A, 0xA66F, 0xA672,
        0xA674, 0xA67D, 0xA69E, 0xA69F, 0xA6F0, 0xA6F1, 0xA802, 0xA802,
        0xA806, 0xA806, 0xA80B, 0xA80B, 0xA825, 0xA826, 0xA8C4, 0xA8C5,
        0xA8E0, 0xA8F1, 0xA926, 0xA92D, 0xA947, 0xA951, 0xA980, 0xA982,
        0xA9B3, 0xA9B3, 0xA9B6, 0xA9B9, 0xA9BC, 0xA9BC, 0xA9E5, 0xA9E5,
        0xAA29, 0xAA2E, 0xAA31, 0xAA32, 0xAA35, 0xAA36, 0xAA43, 0xAA43,
        0xAA4C, 0xAA4C, 0xAA7C, 0xAA7C, 0xAAB0, 0xAAB0, 0xAAB2, 0xAAB4,
        0xAAB7, 0xAAB8, 0xAABE, 0xAABF, 0xAAC1, 0xAAC1, 0xAAEC, 0xAAED,
        0xAAF6, 0xAAF6, 0xABE5, 0xABE5, 0xABE8, 0xABE8, 0xABED, 0xABED,
        0xFB1E, 0xFB1E, 0xFE00, 0xFE0F, 0xFE20, 0xFE2F, 0xFEFF, 0xFEFF,
        0xFFF9, 0xFFFB, 0x101FD, 0x101FD, 0x102E0, 0x102E0, 0x10376, 0x1037A,
        0x10A01, 0x10A0F, 0x10A38, 0x10A3A, 0x10A3F, 0x10A3F, 0x10AE5, 0x10AE6,
        0x11001, 0x11001, 0x11038, 0x11046, 0x1107F, 0x11081, 0x110B3, 0x110B6,
        0x110B9, 0x110BA, 0x11100, 0x11102, 0x11127, 0x1112B, 0x1112D, 0x11134,
        0x11173, 0x11173, 0x11180, 0x11181, 0x111B6, 0x111BE, 0x1122F, 0x11231,
        0x11234, 0x11234, 0x11236, 0x11237, 0x112DF, 0x112DF, 0x112E3, 0x112EA,
        0x11301, 0x11301, 0x1133C, 0x1133C, 0x11340, 0x11340, 0x11366, 0x1136C,
        0x11370, 0x11374, 0x114B3, 0x114B8, 0x114BA, 0x114BA, 0x114BF, 0x114C0,
        0x114C2, 0x114C3, 0x115B2, 0x115B5, 0x115BC, 0x115BD, 0x115BF, 0x115C0,
        0x11633, 0x1163A, 0x1163D, 0x1163D, 0x1163F, 0x11640, 0x116AB, 0x116AB,
        0x116AD, 0x116AD, 0x116B0, 0x116B5, 0x116B7, 0x116B7, 0x1171D, 0x1171F,
        0x11722, 0x11725, 0x11727, 0x1172B, 0x16AF0, 0x16AF4, 0x16B30, 0x16B36,
        0x1BC9D, 0x1BC9E, 0x1BCA0, 0x1BCA3, 0x1D167, 0x1D169, 0x1D173, 0x1D182,
        0x1D185, 0x1D18B, 0x1D1AA, 0x1D1AD, 0x1D242, 0x1D244, 0xE0001, 0xE0001,
        0xE0020, 0xE007F, 0xE0100, 0xE01EF
    )

    /** 宽度 2：East_Asian_Width W/F + Emoji_Presentation。 */
    private val WIDE = intArrayOf(
        0x1100, 0x115F, 0x231A, 0x231B, 0x2329, 0x232A, 0x23E9, 0x23EC,
        0x23F0, 0x23F0, 0x23F3, 0x23F3, 0x25FD, 0x25FE, 0x2614, 0x2615,
        0x2648, 0x2653, 0x267F, 0x267F, 0x2693, 0x2693, 0x26A1, 0x26A1,
        0x26AA, 0x26AB, 0x26BD, 0x26BE, 0x26C4, 0x26C5, 0x26CE, 0x26CE,
        0x26D4, 0x26D4, 0x26EA, 0x26EA, 0x26F2, 0x26F3, 0x26F5, 0x26F5,
        0x26FA, 0x26FA, 0x26FD, 0x26FD, 0x2705, 0x2705, 0x270A, 0x270B,
        0x2728, 0x2728, 0x274C, 0x274C, 0x274E, 0x274E, 0x2753, 0x2755,
        0x2757, 0x2757, 0x2795, 0x2797, 0x27B0, 0x27B0, 0x27BF, 0x27BF,
        0x2B1B, 0x2B1C, 0x2B50, 0x2B50, 0x2B55, 0x2B55, 0x2E80, 0x2E99,
        0x2E9B, 0x2EF3, 0x2F00, 0x2FD5, 0x2FF0, 0x2FFB, 0x3000, 0x303E,
        0x3041, 0x3096, 0x3099, 0x30FF, 0x3105, 0x312F, 0x3131, 0x318E,
        0x3190, 0x31BA, 0x31C0, 0x31E3, 0x31F0, 0x321E, 0x3220, 0x3247,
        0x3250, 0x32FE, 0x3300, 0x4DBF, 0x4E00, 0xA48C, 0xA490, 0xA4C6,
        0xA960, 0xA97C, 0xAC00, 0xD7A3, 0xF900, 0xFAFF, 0xFE10, 0xFE19,
        0xFE30, 0xFE52, 0xFE54, 0xFE66, 0xFE68, 0xFE6B, 0xFF01, 0xFF60,
        0xFFE0, 0xFFE6, 0x16FE0, 0x16FE1, 0x17000, 0x187F7, 0x18800, 0x18AF2,
        0x1B000, 0x1B11E, 0x1B170, 0x1B2FB, 0x1F004, 0x1F004, 0x1F0CF, 0x1F0CF,
        0x1F18E, 0x1F18E, 0x1F191, 0x1F19A, 0x1F200, 0x1F320, 0x1F32D, 0x1F335,
        0x1F337, 0x1F37C, 0x1F37E, 0x1F393, 0x1F3A0, 0x1F3CA, 0x1F3CF, 0x1F3D3,
        0x1F3E0, 0x1F3F0, 0x1F3F4, 0x1F3F4, 0x1F3F8, 0x1F43E, 0x1F440, 0x1F440,
        0x1F442, 0x1F4FC, 0x1F4FF, 0x1F53D, 0x1F54B, 0x1F54E, 0x1F550, 0x1F567,
        0x1F57A, 0x1F57A, 0x1F595, 0x1F596, 0x1F5A4, 0x1F5A4, 0x1F5FB, 0x1F64F,
        0x1F680, 0x1F6C5, 0x1F6CC, 0x1F6CC, 0x1F6D0, 0x1F6D2, 0x1F6EB, 0x1F6EC,
        0x1F6F4, 0x1F6FC, 0x1F7E0, 0x1F7EB, 0x1F90C, 0x1F93A, 0x1F93C, 0x1F945,
        0x1F947, 0x1F9FF, 0x1FA70, 0x1FA74, 0x1FA78, 0x1FA7A, 0x1FA80, 0x1FA86,
        0x1FA90, 0x1FAA8, 0x1FAB0, 0x1FAB6, 0x1FAC0, 0x1FAC2, 0x1FAD0, 0x1FAD6,
        0x20000, 0x2FFFD, 0x30000, 0x3FFFD
    )
}
