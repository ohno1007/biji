package com.biji.notes.nativebridge

/**
 * native 文本工具三件套的 Kotlin 侧入口，对应 `cpp/text_tools.cpp`：
 *
 *  1. [lineDiff]        ← 替代 `ui/editor/Diff.kt` 的 `lineDiff`
 *  2. [syntaxIssues]    ← 替代 `ui/editor/SyntaxTransform.kt` 的 `findSyntaxIssues`
 *  3. [latexToUnicode]  ← 替代 `ui/markdown/Math.kt` 的 `latexToUnicode`
 *
 * 契约（三条，改动前先读）：
 *  - **所有 offset 都是 UTF-16 code unit 下标**，与 `String.get(i)` /
 *    `String.substring` / `AnnotatedString` 的下标口径一一对应。native 侧
 *    用 `GetStringRegion` 直接拿 `jchar*`，全程不做 UTF-8 转换，也不碰
 *    `NewStringUTF` —— 中文注释、emoji（代理对）都不会让偏移错位。
 *  - **native 不返回任何行文本**（[latexToUnicode] 除外，它的产物本来就是
 *    字符串）。diff 只吐 offset，调用方只对真正要渲染的行做 `substring`，
 *    屏幕外的行永远不会产生 String。
 *  - 纯函数、无状态，可被多个协程并发调用。
 *
 * 降级：[available] 为 false（.so 没加载上）或 native 返回 null 时，
 * 所有方法返回 null，调用方应退回原有的 Kotlin 实现。
 */
object NativeText {

    /** .so 是否加载成功。失败时所有 public 方法返回 null，让调用方降级。 */
    val available: Boolean = runCatching { System.loadLibrary("bijinative") }.isSuccess

    // ---- 以下常量必须和 cpp/text_tools.h 保持一致 ----

    /** diff 返回数组的布局版本。native 改布局会 +1，对不上就当不可用。 */
    const val DIFF_FORMAT_VERSION = 1

    /** diff 表头长度（int 个数）：version / flags / beforeLines / afterLines */
    internal const val DIFF_HEADER_INTS = 4

    const val OP_COMMON = 0
    const val OP_ADDED = 1
    const val OP_REMOVED = 2

    /** before 侧输入超限，只 diff 了前面一段 */
    const val DIFF_FLAG_TRUNCATED_BEFORE = 1 shl 0
    /** after 侧输入超限，只 diff 了前面一段 */
    const val DIFF_FLAG_TRUNCATED_AFTER = 1 shl 1
    /** 计算预算耗尽，部分区段退化成"整段删+整段加"（结果仍然合法，只是不最短） */
    const val DIFF_FLAG_APPROXIMATE = 1 shl 2
    /** 输出条目触顶，后面的行没有输出 */
    const val DIFF_FLAG_OUTPUT_CAPPED = 1 shl 3

    /** 与 native 的 kIssuesMaxUnits 一致：超过就降级给 Kotlin（那边是 O(n)，不会 OOM）。 */
    const val MAX_SYNTAX_UNITS = 1_000_000

    /** 与 native 的 kLatexMaxUnits 一致。 */
    const val MAX_LATEX_UNITS = 100_000

    // ---- JNI 入口。不要直接调，走下面的包装。 ----
    private external fun nLineDiff(before: String, after: String): IntArray?
    private external fun nSyntaxIssues(text: String): IntArray?
    private external fun nLatexToUnicode(raw: String): String?

    /**
     * 行级 diff，替代 `Diff.kt` 的 `lineDiff`。
     *
     * 原 Kotlin 版是 O(n·m) 时间 **且 O(n·m) 内存** 的 LCS DP：4000×4000 行
     * 就要一次性分配 64 MB，`EditorScreen` 那条路径（文件上限 256 KB ≈ 6400 行）
     * 能直接把手机打 OOM。native 版是 Myers O(ND) 线性空间分治，10 万行级
     * 输入峰值也只有几 MB。
     *
     * **超长输入不会返回 null**：native 侧是在行边界截断并置
     * [NativeDiff.truncated]，而不是失败。因为"返回 null → 退回 Kotlin 版"
     * 恰恰会踩到那个 OOM，那是最不该发生的降级。
     *
     * ## 与 `Diff.kt lineDiff` 的差异：**hunk 对齐可能不同，计数一定相同**
     * 两边都产出**最短**编辑脚本，但最短脚本不唯一：DP 版的取舍规则是
     * `dp[i+1][j] >= dp[i][j+1]`（同分时先删后加），Myers 分治的取舍来自
     * 中点切分。已对拍验证（3 万组随机 + 60 组千行文档）：
     *  - 删除行拼公共行能逐字符重建 before，新增行拼公共行能重建 after；
     *  - COMMON 行数（= LCS 长度）与 DP 版**完全一致**，所以表头的
     *    `+N 行 / -N 行` 两个数字在两条路径上相同；
     *  - 但同一处改动里 `-`/`+` 的先后、以及"改哪几行算公共"可能不同
     *    （随机语料里约 1/3 的用例有这类差异，git 与 diff(1) 之间同样如此）。
     * 结论：**别用逐行截图比对两条路径**，也别写"native 与 Kotlin 输出逐行
     * 相同"的断言；要断言就断言重建性与 addedCount/removedCount。
     *
     * @return null 表示 native 不可用 / 调用失败 —— 调用方应退回 `lineDiff`。
     */
    fun lineDiff(before: String, after: String): NativeDiff? {
        if (!available) return null
        val raw = runCatching { nLineDiff(before, after) }.getOrNull() ?: return null
        // 契约自检：表头不完整或版本对不上，一律当不可用而不是硬解析。
        if (raw.size < DIFF_HEADER_INTS) return null
        if (raw[0] != DIFF_FORMAT_VERSION) return null
        return NativeDiff(raw, before, after)
    }

    /**
     * 括号/引号配对检查，替代 `SyntaxTransform.kt` 的 `findSyntaxIssues`。
     * 行为逐字符对齐原实现（含"字符串里的反斜杠转义会跨行"这种边角语义）。
     *
     * @return 闭区间列表，可直接喂给 `SyntaxVisualTransformation(errorRanges = …)`。
     *         null 表示 native 不可用 —— 调用方应退回 `findSyntaxIssues`。
     *         空列表表示 native 正常跑完且没发现问题，**不要**再跑一遍 Kotlin 版。
     */
    fun syntaxIssues(text: String): List<IntRange>? {
        if (!available) return null
        if (text.isEmpty()) return emptyList()
        // 超长先挡一道：省掉一次 JNI 调用 + 一次 2 MB 的 UTF-16 拷贝。
        if (text.length > MAX_SYNTAX_UNITS) return null
        val flat = runCatching { nSyntaxIssues(text) }.getOrNull() ?: return null
        if (flat.isEmpty()) return emptyList()
        val out = ArrayList<IntRange>(flat.size / 2)
        val n = text.length
        var i = 0
        // 对越界/畸形二元组只跳过、不抛异常：万一契约被改坏，
        // 这里最多少画几条波浪线，不会把编辑器打崩。
        while (i + 1 < flat.size) {
            val s = flat[i]
            val e = flat[i + 1]
            i += 2
            if (s < 0 || e > n || s >= e) continue
            out.add(s until e)  // native 吐开区间，IntRange 是闭区间
        }
        return out
    }

    /**
     * LaTeX → Unicode，替代 `Math.kt` 的 `latexToUnicode`。
     * 把原来的 55 趟全文扫描（37 次 GreekMap replace + 16 次操作符 replace
     * + 5 趟 Regex）压成表驱动的 9 趟单遍扫描，不用任何正则。
     *
     * 与 Kotlin 版有两处**有意为之**的修正（详见 `cpp/text_tools.h`）：
     *  - `\leftarrow` 输出 `←`，而不是 Kotlin 因 `\le` 先被替换而产生的 `≤ftarrow`；
     *  - `^{😀}` 这类上下标里的代理对整对保留，而不是被劈成两个孤代理。
     * 除这两类输入外，输出与 Kotlin 版逐 code unit 相同。
     *
     * @return null 表示 native 不可用 / 输入超长 —— 调用方应退回 `latexToUnicode`。
     */
    fun latexToUnicode(raw: String): String? {
        if (!available) return null
        if (raw.length > MAX_LATEX_UNITS) return null
        return runCatching { nLatexToUnicode(raw) }.getOrNull()
    }

    /**
     * UTF-16 偏移自检。native 侧一旦退化成 UTF-8 byte offset 或切断代理对，
     * 这里会立刻失败 —— 这是本项目唯一会"静默出错"的失败模式，别靠肉眼 review。
     *
     * 三个样本都刻意混了中文和 emoji（代理对），且让 emoji 正好压在 token 边界上：
     *  - 括号检查：`f(中😀 {\n  "字😀\n}` —— 未闭合的 `(` 在下标 1，
     *    未闭合的字符串从下标 10 到 14（emoji 在字符串内部）。
     *    若按 UTF-8 算，这两个数会变成 1 和 12/17，立刻对不上。
     *  - LaTeX：`\frac{中}{😀}` 的 emoji 必须整对搬出来；`\leftarrow` 必须是 `←`。
     *  - diff：`😀` 那一行的 span 必须是 2 个 code unit（(2,4)），不是 4 个字节。
     */
    fun selfTest(): Boolean = runCatching {
        if (!available) return@runCatching false

        val issues = nSyntaxIssues("f(中\uD83D\uDE00 {\n  \"字\uD83D\uDE00\n}")
        if (issues == null || !issues.contentEquals(intArrayOf(10, 14, 1, 2))) {
            return@runCatching false
        }

        val latex = nLatexToUnicode("\$\\alpha^2 + \\frac{中}{\uD83D\uDE00} \\leftarrow\$")
        if (latex != "α² + 中 / \uD83D\uDE00 ←") return@runCatching false

        val diff = nLineDiff("中\n\uD83D\uDE00\nx", "中\n\uD83D\uDE00\ny")
        diff != null && diff.contentEquals(
            intArrayOf(
                DIFF_FORMAT_VERSION, 0, 3, 3,
                OP_COMMON, 0, 1,
                OP_COMMON, 2, 4,
                OP_REMOVED, 5, 6,
                OP_ADDED, 5, 6
            )
        )
    }.getOrDefault(false)
}

/**
 * [NativeText.lineDiff] 的结果视图。
 *
 * 刻意**不**在构造时把每行 `substring` 出来：native 只给了 offset，
 * 只有真正要渲染的那几行才通过 [textAt] 取字符串（`DiffView` 本来就
 * `take(2000)`），屏幕外的行一个 String 都不会产生。
 *
 * offset 指向哪个串是唯一容易记错的地方：
 *  - [OP_REMOVED][NativeText.OP_REMOVED] 的下标指向 **before**
 *  - [OP_COMMON][NativeText.OP_COMMON] / [OP_ADDED][NativeText.OP_ADDED]
 *    的下标指向 **after**
 * [textAt] 已经处理了这个分支，直接用它就不会错。
 */
class NativeDiff internal constructor(
    private val raw: IntArray,
    private val before: String,
    private val after: String
) {
    /** diff 条目数（= 输出行数）。 */
    val size: Int = (raw.size - NativeText.DIFF_HEADER_INTS) / 3

    /** 新增行数。数的是 int 数组，不产生任何 String。 */
    val addedCount: Int

    /** 删除行数。 */
    val removedCount: Int

    init {
        // 一趟扫完两个计数。`DiffView` 的表头两个都要用，
        // 而扫 int 数组比按需 lazy 省事，也不用操心跨线程可见性。
        var add = 0
        var rem = 0
        for (i in 0 until size) {
            when (raw[NativeText.DIFF_HEADER_INTS + i * 3]) {
                NativeText.OP_ADDED -> add++
                NativeText.OP_REMOVED -> rem++
            }
        }
        addedCount = add
        removedCount = rem
    }

    /** 位标志，见 `NativeText.DIFF_FLAG_*`。 */
    val flags: Int get() = raw[1]

    /** 参与 diff 的 before 行数（截断后）。 */
    val beforeLineCount: Int get() = raw[2]

    /** 参与 diff 的 after 行数（截断后）。 */
    val afterLineCount: Int get() = raw[3]

    /** 输入太大被截断，或输出条目触顶 —— UI 应提示"内容过长，已截断"。 */
    val truncated: Boolean
        get() = flags and (NativeText.DIFF_FLAG_TRUNCATED_BEFORE or
                NativeText.DIFF_FLAG_TRUNCATED_AFTER or
                NativeText.DIFF_FLAG_OUTPUT_CAPPED) != 0

    /**
     * 差异过大，部分区段退化成"整段删+整段加"。结果仍然是**合法**的 diff
     * （删除+公共能重建出 before，新增+公共能重建出 after），只是不再最短。
     * 预算是从头往后花的，所以越靠前的行质量越好 —— 正好和
     * `DiffView` 只渲染前 2000 行对上。
     */
    val approximate: Boolean
        get() = flags and NativeText.DIFF_FLAG_APPROXIMATE != 0

    /** 第 [index] 条的类型：`OP_COMMON` / `OP_ADDED` / `OP_REMOVED`。 */
    fun typeAt(index: Int): Int = raw[NativeText.DIFF_HEADER_INTS + index * 3]

    /** 第 [index] 条在源串里的起始 UTF-16 下标（含）。 */
    fun startAt(index: Int): Int = raw[NativeText.DIFF_HEADER_INTS + index * 3 + 1]

    /** 第 [index] 条在源串里的结束 UTF-16 下标（不含）。 */
    fun endAt(index: Int): Int = raw[NativeText.DIFF_HEADER_INTS + index * 3 + 2]

    /**
     * 取第 [index] 行的文本。按类型自动选 before / after 作为源串。
     * 越界一律返回空串，不抛异常。
     */
    fun textAt(index: Int): String {
        val src = if (typeAt(index) == NativeText.OP_REMOVED) before else after
        val s = startAt(index)
        val e = endAt(index)
        if (s < 0 || e > src.length || s > e) return ""
        return src.substring(s, e)
    }

    /**
     * 第 [index] 行（必须是 COMMON）是否落在真实增删的 ±[window] 邻域内。
     * 等价于 `Diff.kt` 的 `nearChange`，但不需要先把整个 `List<DiffLine>` 建出来。
     */
    fun isNearChange(index: Int, window: Int = 2): Boolean {
        if (typeAt(index) != NativeText.OP_COMMON) return false
        val lo = (index - window).coerceAtLeast(0)
        val hi = (index + window).coerceAtMost(size - 1)
        for (i in lo..hi) {
            val t = typeAt(i)
            if (t == NativeText.OP_ADDED || t == NativeText.OP_REMOVED) return true
        }
        return false
    }

}
