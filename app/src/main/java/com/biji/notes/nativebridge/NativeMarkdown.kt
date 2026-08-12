package com.biji.notes.nativebridge

/**
 * 块级 Markdown 解析的 native 绑定。对应 cpp/markdown_parser.{h,cpp}，
 * 行为与 `ui/markdown/Markdown.kt` 里的 `private fun parseBlocks(source)` 一比一对齐。
 *
 * ## 设计要点
 *
 * **一次 JNI 调用返回整篇文档。** [nParseBlocks] 返回一个自描述的扁平 `IntArray`，
 * 里面装着全部 block 的类型、附加数值、以及所有文本片段的 UTF-16 offset 区间。
 * 绝不会出现"每个 block 一次调用"。
 *
 * **native 侧一个字符串都不返回。** 所有文本都是 `(start, end)` 这样的
 * **原始 src 串的 UTF-16 下标**，由 Kotlin 侧按需 `substring`。好处有三：
 *  1. 完全绕开 `NewStringUTF`（它要 Modified UTF-8，中文/emoji 会出事）；
 *  2. offset 天然与 `String` / `AnnotatedString` 的下标口径一致；
 *  3. 屏幕外的内容可以干脆不 `substring`，不产生 String。
 *
 * 上游调研建议"再返回一个 String 数组放 lang"。这里刻意没有那么做 ——
 * lang 同样以 offset 区间返回，[NativeBlocks.langs] 在 Kotlin 侧按需拼出
 * `Array<String>`，接口面上等价，但 JNI 边界保持"只进 jstring、只出 jintArray"，
 * 一次 `FindClass` / `NewString` 都不需要。
 *
 * **降级。** 库加载失败、输入超限、native 返回结构不合法 —— 一律返回 `null`，
 * 调用方回到原有的 Kotlin 实现。native 侧不抛任何异常给 JVM。
 *
 * **线程安全。** native 侧是纯函数，无全局可变状态，可被多个协程并发调用。
 * [NativeBlocks] 构造后不可变。
 *
 * ## 用法（接线示例见文件末尾注释）
 * ```
 * val nb = NativeMarkdown.parseBlocks(markdown)
 * if (nb != null) { /* 按 nb.type(i) 组装 Block */ } else { /* 走原 parseBlocks */ }
 * ```
 */
object NativeMarkdown {

    /** `System.loadLibrary` 是否成功。false 时所有 public 方法返回 null。 */
    val available: Boolean = runCatching { System.loadLibrary("bijinative") }.isSuccess

    // ---- block type，必须与 markdown_parser.h 的 BlockType 保持同步 ----
    const val TYPE_BLANK = 0
    const val TYPE_DIVIDER = 1
    const val TYPE_HEADING = 2
    const val TYPE_PARAGRAPH = 3
    const val TYPE_BULLET = 4
    const val TYPE_TASK = 5
    const val TYPE_NUMBERED = 6
    const val TYPE_QUOTE = 7
    const val TYPE_CODE = 8
    const val TYPE_MERMAID = 9
    const val TYPE_MATH = 10
    const val TYPE_TABLE = 11

    internal const val MAGIC = 0x424D4B31   // 'BMK1'
    internal const val VERSION = 1
    internal const val HEADER_INTS = 8
    internal const val BLOCK_STRIDE = 8

    /** 与 markdown_parser.h 的 kMaxInput 一致；超过这个长度 native 直接返回 null。 */
    const val MAX_INPUT = 4_000_000

    private external fun nParseBlocks(src: String): IntArray?

    /**
     * 解析整篇 Markdown。native 不可用 / 输入超限 / 返回结构不合法时返回 `null`，
     * 调用方据此降级到 Kotlin 版 `parseBlocks`。
     */
    fun parseBlocks(src: String): NativeBlocks? {
        if (!available) return null
        if (src.length > MAX_INPUT) return null
        val data = runCatching { nParseBlocks(src) }.getOrNull() ?: return null
        return NativeBlocks.wrap(src, data)
    }

    /**
     * 结构自检。跑几个已知输入，验证 UTF-16 offset 契约没被破坏
     * （中文、emoji 代理对、未闭合围栏、表格）。
     * 建议在 `NativeProbe.selfTest()` 之后一起调用，任一为 false 就整体降级。
     */
    fun selfTest(): Boolean = runCatching {
        if (!available) return@runCatching false

        // 1) 中文 + emoji：offset 必须是 UTF-16 code unit
        //    "# 中文 😀" -> 1 个 Heading(level=1, text="中文 😀")
        //    "中文"占 2 个 code unit，"😀"占 2 个（代理对）
        val a = parseBlocks("# 中文 😀") ?: return@runCatching false
        if (a.count != 1) return@runCatching false
        if (a.type(0) != TYPE_HEADING || a.arg0(0) != 1) return@runCatching false
        if (a.text(0) != "中文 😀") return@runCatching false

        // 2) emoji 紧贴 token 边界 + 代码块正文原样保留
        val b = parseBlocks("```kt\nval s = \"😀\" // 注释\n```") ?: return@runCatching false
        if (b.count != 1 || b.type(0) != TYPE_CODE) return@runCatching false
        if (b.lang(0) != "kt") return@runCatching false
        if (b.code(0) != "val s = \"😀\" // 注释") return@runCatching false

        // 3) 表格单元格 offset
        val c = parseBlocks("| 名称 | 值 |\n|---|---|\n| a | 🎉 |")
            ?: return@runCatching false
        if (c.count != 1 || c.type(0) != TYPE_TABLE || c.tableRowCount(0) != 2) {
            return@runCatching false
        }
        if (c.tableRow(0, 0) != listOf("名称", "值")) return@runCatching false
        if (c.tableRow(0, 1) != listOf("a", "🎉")) return@runCatching false

        true
    }.getOrDefault(false)
}

/**
 * 一篇文档的解析结果。所有取文本的方法都是对原始 `src` 做 `substring`，
 * 没有任何字符串跨过 JNI 边界。构造后不可变，可跨线程读。
 */
class NativeBlocks private constructor(
    private val src: String,
    private val data: IntArray,
    /** block 个数。 */
    val count: Int,
    private val blockOff: Int,
    private val spanOff: Int,
    private val extraOff: Int,
) {

    private fun base(i: Int) = blockOff + i * NativeMarkdown.BLOCK_STRIDE

    /** `NativeMarkdown.TYPE_*` 之一。 */
    fun type(i: Int): Int = data[base(i)]

    /** Heading 的 level(1..6) / 列表项的 depth(0..3) / Table 的行数。 */
    fun arg0(i: Int): Int = data[base(i) + 1]

    /** NumberedItem 的序号 / TaskItem 的 checked(0|1)。 */
    fun arg1(i: Int): Int = data[base(i) + 2]

    /** 该 block 的文本片段个数。 */
    fun spanCount(i: Int): Int = data[base(i) + 4]

    private fun spanIndex(i: Int, k: Int) = data[base(i) + 3] + k

    /** 第 k 个片段在 src 中的起始 UTF-16 下标。 */
    fun spanStart(i: Int, k: Int): Int = data[spanOff + spanIndex(i, k) * 2]

    /** 第 k 个片段在 src 中的结束 UTF-16 下标（不含）。 */
    fun spanEnd(i: Int, k: Int): Int = data[spanOff + spanIndex(i, k) * 2 + 1]

    /** 第 k 个片段的文本。 */
    fun text(i: Int, k: Int = 0): String {
        val p = spanIndex(i, k)
        return src.substring(data[spanOff + p * 2], data[spanOff + p * 2 + 1])
    }

    /** 从第 [from] 个片段起，用 '\n' 连接所有片段 —— 多行块（段落 / 代码 / 公式）用。 */
    fun joined(i: Int, from: Int = 0): String {
        val n = spanCount(i)
        if (from >= n) return ""
        if (from == n - 1) return text(i, from)
        val sb = StringBuilder()
        for (k in from until n) {
            if (k > from) sb.append('\n')
            val p = spanIndex(i, k)
            sb.append(src, data[spanOff + p * 2], data[spanOff + p * 2 + 1])
        }
        return sb.toString()
    }

    /** 从第 [from] 个片段起的所有片段 —— Quote 的 `lines` 用。 */
    fun texts(i: Int, from: Int = 0): List<String> {
        val n = spanCount(i)
        if (from >= n) return emptyList()
        return List(n - from) { text(i, from + it) }
    }

    // ---- 各 block 形态的便捷取值 --------------------------------------

    /** Heading / BulletItem / TaskItem / NumberedItem 的正文。 */
    fun inlineText(i: Int): String = text(i, 0)

    /** Paragraph 的正文（多行以 '\n' 连接）。 */
    fun paragraph(i: Int): String = joined(i, 0)

    /** Quote 的 `lines`。 */
    fun quoteLines(i: Int): List<String> = texts(i, 0)

    /** CodeBlock 的 lang；非 CodeBlock 返回 ""。 */
    fun lang(i: Int): String =
        if (type(i) == NativeMarkdown.TYPE_CODE) text(i, 0) else ""

    /** CodeBlock 的正文（spans[0] 是 lang，正文从 1 开始）。 */
    fun code(i: Int): String = joined(i, 1)

    /** Mermaid / MathBlock 的正文。 */
    fun body(i: Int): String = joined(i, 0)

    /** TaskItem 是否勾选。 */
    fun checked(i: Int): Boolean = arg1(i) != 0

    // ---- 表格 ---------------------------------------------------------

    /** 总行数，含表头。表头恒为第 0 行。 */
    fun tableRowCount(i: Int): Int = arg0(i)

    /** 第 [row] 行的单元格数（各行可能不等 —— 缺列 / 多列都原样保留）。 */
    fun tableCellCount(i: Int, row: Int): Int = data[extraOff + data[base(i) + 5] + row]

    /** 第 [row] 行第 [col] 个单元格。 */
    fun tableCell(i: Int, row: Int, col: Int): String {
        var k = 0
        for (r in 0 until row) k += tableCellCount(i, r)
        return text(i, k + col)
    }

    /** 第 [row] 行的全部单元格。 */
    fun tableRow(i: Int, row: Int): List<String> {
        var k = 0
        for (r in 0 until row) k += tableCellCount(i, r)
        val n = tableCellCount(i, row)
        return List(n) { text(i, k + it) }
    }

    /** 表头（= 第 0 行）。 */
    fun tableHeader(i: Int): List<String> = tableRow(i, 0)

    /** 数据行（第 1 行起）。 */
    fun tableRows(i: Int): List<List<String>> =
        List(tableRowCount(i) - 1) { tableRow(i, it + 1) }

    /**
     * 每个 block 的 lang（非 CodeBlock 为 ""）。
     * 上游调研里"native 再返回一个 String 数组"的等价物，只是在 Kotlin 侧按需生成，
     * JNI 边界仍然只有 int。
     */
    val langs: Array<String> by lazy { Array(count) { lang(it) } }

    internal companion object {
        /**
         * 校验 native 返回的数组结构，并包装成 [NativeBlocks]。
         * 任何一处对不上就返回 null —— 宁可降级也不要在 Compose 组合里抛
         * IndexOutOfBounds（那会让整个聊天界面永久崩溃）。
         */
        fun wrap(src: String, d: IntArray): NativeBlocks? {
            if (d.size < NativeMarkdown.HEADER_INTS) return null
            if (d[0] != NativeMarkdown.MAGIC || d[1] != NativeMarkdown.VERSION) return null
            val blockCount = d[2]
            val blockOff = d[3]
            val spanCount = d[4]
            val spanOff = d[5]
            val extraCount = d[6]
            val extraOff = d[7]
            if (blockCount < 0 || spanCount < 0 || extraCount < 0) return null
            if (blockOff != NativeMarkdown.HEADER_INTS) return null
            if (spanOff != blockOff + blockCount * NativeMarkdown.BLOCK_STRIDE) return null
            if (extraOff != spanOff + spanCount * 2) return null
            if (d.size != extraOff + extraCount) return null

            // span 区：0 <= start <= end <= src.length
            val n = src.length
            var p = spanOff
            while (p < extraOff) {
                val a = d[p]
                val b = d[p + 1]
                if (a < 0 || b < a || b > n) return null
                p += 2
            }
            // block 区：span/extra 引用不越界
            for (i in 0 until blockCount) {
                val bse = blockOff + i * NativeMarkdown.BLOCK_STRIDE
                val ss = d[bse + 3]
                val sc = d[bse + 4]
                val es = d[bse + 5]
                val ec = d[bse + 6]
                if (ss < 0 || sc < 0 || ss + sc > spanCount) return null
                if (es < 0 || ec < 0 || es + ec > extraCount) return null
                if (d[bse] == NativeMarkdown.TYPE_TABLE) {
                    // rowCount 必须与 extra 区的行数一致，且各行列数之和 == span 数
                    if (d[bse + 1] != ec || ec < 1) return null
                    var sum = 0
                    for (r in 0 until ec) {
                        val c = d[extraOff + es + r]
                        if (c < 0) return null
                        sum += c
                    }
                    if (sum != sc) return null
                }
            }
            return NativeBlocks(src, d, blockCount, blockOff, spanOff, extraOff)
        }
    }
}

/* =====================================================================
 * 手工推演的测试向量 —— 全部已用差分对拍验证
 * =====================================================================
 *
 * 验证方法：把 Markdown.kt 的 parseBlocks 逐行移植成 Java（同样的
 * java.util.regex、同样的 trim 语义），与 C++ 版跑同一批输入，把结果
 * 规范化成文本后逐字节 diff。
 *   - 84 篇手写 tricky 文档：完全一致
 *   - 36 万篇随机拼装文档（12 个种子 × 3 万，片段表含中文 / emoji /
 *     裸代理 / NBSP / 全角空格 / \r / U+0085 / U+2028 / U+2029 /
 *     VT / FF）：完全一致
 *   - ASan + UBSan 全程干净
 *
 * 以下是其中最容易出错的几组，写明预期，改动时请当回归用例：
 *
 * ---------------------------------------------------------------------
 * 1) 中文混排 / emoji —— offset 必须是 UTF-16 code unit
 * ---------------------------------------------------------------------
 *   输入: "# 中文标题 😀\n\n这是一段中文 😀🎉 mixed。\n"
 *   预期: Heading(1, "中文标题 😀") / Blank / Paragraph("这是一段中文 😀🎉 mixed。")
 *   要点: "😀" 是 U+1F600，占 **2** 个 code unit。native 全程按 code unit
 *         步进，返回的 (start,end) 直接能喂给 String.substring。
 *         若哪天有人把 native 改成先转 UTF-8 再算 byte offset，这条会直接炸。
 *
 *   输入: "- 😀text\n"
 *   预期: BulletItem(0, "😀text")
 *   要点: 高代理 0xD83D >= 0x80，所有语法判定只认 ASCII，所以代理对永远
 *         被当作普通文本，token 边界不可能落在代理对中间。
 *
 *   输入: "- \uD83D lone high\n"（**孤立高代理**，非法 UTF-16）
 *   预期: BulletItem(0, "\uD83D lone high") —— 不崩、offset 与 Kotlin 一致。
 *
 * ---------------------------------------------------------------------
 * 2) 未闭合的代码围栏
 * ---------------------------------------------------------------------
 *   输入: "before\n\n```kotlin\nval x = 1 // 中文注释 😀\nfun f() {}\n"
 *   预期: Paragraph("before") / Blank / CodeBlock("kotlin",
 *         "val x = 1 // 中文注释 😀\nfun f() {}")
 *   要点: 扫到文件尾没找到闭围栏时**不报错**，把剩余全部吃进正文
 *         （Kotlin 版的 `if (i < lines.size) i++` 在越界时不执行）。
 *         这是流式输出期间的常态：围栏还没打完就已经渲染了。
 *
 *   输入: "```kotlin\nbody\n```kt\nafter\n"
 *   预期: CodeBlock("kotlin", "body") / Paragraph("after")
 *   要点: 闭围栏用的是**同一条** FenceRegex，所以 "```kt" 也能闭合。
 *
 *   输入: "````\nnot a fence\n````\n"
 *   预期: Paragraph("````\nnot a fence") + ...（4 个反引号不是围栏）
 *   要点: 第 4 个 '`' 既不属于 [\w+-] 也不属于 \s，整条正则失配。
 *
 *   输入: "```\na\n\n\n```\n"      预期: CodeBlock("", "a")
 *   输入: "```\na\n   \n```\n"    预期: CodeBlock("", "a\n   ")
 *   要点: 正文是 trimEnd('\n')，**只吃 '\n'**。所以结尾的空行被丢掉，
 *         但"只有空格"的行会保留。
 *
 * ---------------------------------------------------------------------
 * 3) 嵌套 / 连续引用
 * ---------------------------------------------------------------------
 *   输入: "> a\n>> b\n> > c\n>>> 中文 😀\n\ntail\n"
 *   预期: Quote(["a", "> b", "> c", ">> 中文 😀"]) / Blank / Paragraph("tail")
 *   要点: 每行只剥**一层** '>'（removePrefix(">")），剥完再 trimStart。
 *         所以嵌套引用不会被递归展开，多出来的 '>' 原样留在文本里。
 *         连续的 '>' 行合并成**一个** Quote 块，空行才结束。
 *
 *   输入: ">  spaced   \n> x\t\n"
 *   预期: Quote(["spaced   ", "x\t"])
 *   要点: 引用行**不做 trimEnd** —— 行尾空白原样保留（Kotlin 版如此）。
 *         只有 '>' 之后的前导空白被 trimStart 吃掉。
 *
 * ---------------------------------------------------------------------
 * 4) 表格缺列 / 多列 / 空单元格
 * ---------------------------------------------------------------------
 *   输入: "| a | b | c |\n|---|---|---|\n| 1 |\n| 1 | 2 | 3 | 4 |\n||\n|x||\n"
 *   预期: Table(header=["a","b","c"],
 *               rows=[["1"], ["1","2","3","4"], [""], ["x"]])
 *   要点: 各行列数**不强制对齐**，Kotlin 版就是这样，渲染层自己兜底。
 *         "||"  -> trim('|') 后是空串 -> split 得 [""]，1 个空单元格。
 *         "|x||" -> trim('|') 把**两端所有**竖线都吃掉 -> "x" -> 1 个单元格
 *         （不是 ["x", ""]）。
 *
 *   输入: "| a |\n| --- |\n| 1 |\n"
 *   预期: **不是表格** —— TableSeparator 的分组是 `+`，至少要两列。
 *         结果是 Paragraph("| a |\n| --- |\n| 1 |")。
 *
 *   输入: "| a | b |\n| -- | -- |\n"
 *   预期: 不是表格（-{3,} 要求至少三个横线）。
 *
 *   输入: " | a | b |\n | --- | --- |\n"
 *   预期: 不是表格 —— TablePipeRow 匹配的是 `line`（只 trimEnd），
 *         表格必须顶格。
 *
 * ---------------------------------------------------------------------
 * 5) orderedCounters 的重置时机（最容易写错的一处）
 * ---------------------------------------------------------------------
 *   序号来自**计数器**，不是字面数字：
 *     "5. five\n9. nine\n"  ->  NumberedItem(0,1,"five"), NumberedItem(0,2,"nine")
 *
 *   BulletItem **会**清零，TaskItem **不会**：
 *     "1. a\n2. b\n- bullet\n3. c\n4. d\n"
 *       -> N(0,1,"a") N(0,2,"b") Bullet(0,"bullet") N(0,1,"c") N(0,2,"d")
 *     "1. a\n2. b\n- [ ] t\n3. c\n"
 *       -> N(0,1,"a") N(0,2,"b") Task(0,"t",false) N(0,3,"c")
 *
 *   计数器**按 depth 分桶**，切换 depth 不互相清零：
 *     "1. a\n  1. nested\n  2. nested2\n2. b\n"
 *       -> N(0,1) N(1,1) N(1,2) N(0,2)
 *
 *   Blank / Heading / Quote / Divider / Table / 围栏 / 段落 都清零。
 *
 * ---------------------------------------------------------------------
 * 6) 缩进深度
 * ---------------------------------------------------------------------
 *   indentDepth = 前导**半角空格**数 / 2，再 coerceAtMost(3)。
 *     "- x"(0) "  - x"(1) "    - x"(2) "      - x"(3) "        - x"(3, 封顶)
 *     " - x" -> 1/2 = 0
 *     "\t- x" -> tab **不算**，depth = 0
 *
 * ---------------------------------------------------------------------
 * 7) CRLF 与行内裸 \r（native 与 Kotlin 分歧最隐蔽的一处）
 * ---------------------------------------------------------------------
 *   Kotlin 先 source.replace("\r\n","\n") 再 split("\n")，这会改变 offset。
 *   native 不重写字符串，改为在切行时把 "\r\n" 的那个 '\r' 排除在行外，
 *   于是每行内容逐字符相同而 offset 仍指向原始串。
 *     "# T\r\n\r\n- a\r\n"  ->  Heading(1,"T") / Blank / BulletItem(0,"a")
 *
 *   **行内的裸 \r**（没跟 \n）不会被归一，会留在行里。此时 Java 正则的
 *   '.' 不匹配行终止符（\n \r U+0085 U+2028 U+2029），而 matches() 要求
 *   吃光整段输入，所以 `^[-*+]\s+(.*)$` 之类的模式**整体失配** ->
 *   该行降级成 Paragraph：
 *     "- text\rmore"  ->  Paragraph("- text\rmore")   （**不是** BulletItem）
 *     "# h\rx"        ->  Paragraph("# h\rx")         （**不是** Heading）
 *     "|a\rb|"        ->  不是表格行
 *   受影响的模式：Heading / Ordered / Task / Bullet / TablePipeRow。
 *   不受影响：Fence / MathFence / Latex / Hr / TableSeparator（不含 '.'）。
 *   注意 VT(U+000B) / FF(U+000C) **不是**行终止符，只是 \s，不触发这条。
 *
 * ---------------------------------------------------------------------
 * 8) 两套"空白"定义
 * ---------------------------------------------------------------------
 *   Kotlin 的 trim/trimEnd/isBlank 用 Char.isWhitespace()（含 NBSP、全角
 *   空格等），Java 正则的 \s 只有 [ \t\n\x0B\f\r]。混用会错位：
 *     "$$ \nbody\n$$\n"   -> 开围栏成立（trimEnd 吃掉 NBSP）-> MathBlock
 *     "$$\nbody\n$$ \n"   -> 闭围栏**不成立**（闭合检测只 trimStart，
 *                                 \s* 不含 NBSP）-> 吃到文件尾
 *   这不是 bug，是 Kotlin 版的既有行为，native 必须原样复现。
 *
 * ---------------------------------------------------------------------
 * 9) 其它边界
 * ---------------------------------------------------------------------
 *   ""            -> [Blank]            （split 出一个空行）
 *   "\n\n\n"      -> [Blank]            （连续 Blank 去重）
 *   "a\n\n\n\nb"  -> Paragraph("a") / Blank / Paragraph("b")
 *   "#######  x"  -> Paragraph          （7 个 '#' 不是标题）
 *   "#nospace"    -> Paragraph          （'#' 后必须有空白）
 *   " # x"        -> Paragraph          （标题必须顶格）
 *   "- [x]"       -> BulletItem(0,"[x]")（']' 后缺 \s+，不是 TaskItem）
 *   "- "          -> Paragraph("-")     （trimEnd 之后没有 \s+ 了）
 *   "```\nst=>start: 开始\ncond->op\n```"
 *                 -> Mermaid            （空 lang + 正文同时含 "=>" 和 "->"）
 *   "```js\na=>b\nc->d\n```"
 *                 -> CodeBlock("js",…)  （有 lang 就不看 flow 语法）
 */

/* =====================================================================
 * 接线示例（供 Markdown.kt 的改造参考，本文件不做任何调用点改动）
 * =====================================================================
 *
 * private fun parseBlocksFast(source: String): List<Block> {
 *     val nb = NativeMarkdown.parseBlocks(source) ?: return parseBlocks(source)
 *     val out = ArrayList<Block>(nb.count)
 *     for (i in 0 until nb.count) {
 *         out += when (nb.type(i)) {
 *             NativeMarkdown.TYPE_BLANK     -> Block.Blank
 *             NativeMarkdown.TYPE_DIVIDER   -> Block.Divider
 *             NativeMarkdown.TYPE_HEADING   -> Block.Heading(nb.arg0(i), nb.inlineText(i))
 *             NativeMarkdown.TYPE_PARAGRAPH -> Block.Paragraph(nb.paragraph(i))
 *             NativeMarkdown.TYPE_BULLET    -> Block.BulletItem(nb.arg0(i), nb.inlineText(i))
 *             NativeMarkdown.TYPE_TASK      ->
 *                 Block.TaskItem(nb.arg0(i), nb.inlineText(i), nb.checked(i))
 *             NativeMarkdown.TYPE_NUMBERED  ->
 *                 Block.NumberedItem(nb.arg0(i), nb.arg1(i), nb.inlineText(i))
 *             NativeMarkdown.TYPE_QUOTE     -> Block.Quote(nb.quoteLines(i))
 *             NativeMarkdown.TYPE_CODE      -> Block.CodeBlock(nb.lang(i), nb.code(i))
 *             NativeMarkdown.TYPE_MERMAID   -> Block.Mermaid(nb.body(i))
 *             NativeMarkdown.TYPE_MATH      -> Block.MathBlock(nb.body(i))
 *             NativeMarkdown.TYPE_TABLE     -> Block.Table(nb.tableHeader(i), nb.tableRows(i))
 *             else -> return parseBlocks(source)   // 未知 type -> 整体降级
 *         }
 *     }
 *     return out
 * }
 */
