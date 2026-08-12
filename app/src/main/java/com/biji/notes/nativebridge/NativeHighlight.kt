package com.biji.notes.nativebridge

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

/**
 * native 语法高亮的 Kotlin 侧入口。
 *
 * 契约：
 *  - native 只吐 `(startUtf16, endUtf16, tokenType)` 扁平三元组，**不返回任何字符串、
 *    也不认识任何颜色**。亮暗主题切换只是换一个 [HighlightPalette]，不需要重跑 native。
 *  - 所有 offset 都是 **UTF-16 code unit 下标**，和 `String.get(i)` /
 *    `AnnotatedString` 的下标口径一一对应。中文注释、emoji、代理对都不会错位
 *    （native 侧只对 < 0x80 的 ASCII 做语法判定，>= 0x80 一律当普通文本字符，
 *    所以 token 边界不可能落在代理对中间）。
 *  - 三元组天然按 start 升序、互不重叠：单遍扫描保证一个 code unit 只属于一个 token，
 *    "字符串/注释先占位" 是结构性质而不是后处理，调用方不需要再做覆盖仲裁。
 *  - 纯函数、无状态，可被多个协程并发调用。
 *
 * 降级：[available] 为 false（.so 没加载上）或 native 返回 null 时，
 * [highlight] 返回 null，调用方应退回 `SyntaxHighlight.colorize`。
 * 注意区分两种"空"：
 *  - `null`      → native 不可用/出错，**退回 Kotlin 正则实现**；
 *  - 空 IntArray → native 正常跑完但没有 token（未知语言、超长输入），
 *                  **直接按纯文本渲染**，不要再去跑一遍正则版。
 *
 * ## 与 `SyntaxHighlight` 的渲染差异
 * 目标是"视觉等价"，不是逐 span 一致。**全部**已知差异逐条列在
 * `cpp/syntax_highlight.h` 顶部的注释里，接线 / 写对拍测试前请先读那一段。
 * 摘要：格式正确的真实代码上只差两处 ——
 *  1. `if (` / `catch (` / `vector<` 取关键字/类型色，原实现取函数名色；
 *  2. CSS 的 `50%` 连 `%` 一起染色（原实现因 `\b` 只染 `50`）。
 * 其余差异只在畸形 / 流式半截输入上出现（未闭合的注释和字符串）。
 */
object NativeHighlight {

    /** .so 是否加载成功。失败时所有 public 方法返回 null，让调用方降级。 */
    val available: Boolean = runCatching { System.loadLibrary("bijinative") }.isSuccess

    // ---- token 类型：必须和 cpp/syntax_highlight.h 的 TokenType 保持一致 ----
    const val TOKEN_COMMENT = 0
    const val TOKEN_STRING = 1
    const val TOKEN_NUMBER = 2
    const val TOKEN_KEYWORD = 3
    const val TOKEN_FUNC = 4
    const val TOKEN_TYPE = 5
    const val TOKEN_PREPROCESSOR = 6

    // ---- 语言 id：必须和 cpp/syntax_highlight.h 的 LangId 保持一致 ----
    // 别名到 id 的映射刻意留在 Kotlin 侧 —— native 只收基本类型，
    // 一次 GetStringUTFChars 都不需要。
    private const val LANG_NONE = 0
    private const val LANG_KOTLIN = 1
    private const val LANG_JS = 2
    private const val LANG_PYTHON = 3
    private const val LANG_JSON = 4
    private const val LANG_BASH = 5
    private const val LANG_SQL = 6
    private const val LANG_HTML = 7
    private const val LANG_CSS = 8
    private const val LANG_CPP = 9

    /** 与 native 的 kMaxHighlightUnits 一致：超过就不高亮，按纯文本渲染。 */
    const val MAX_HIGHLIGHT_UNITS = 200_000

    private val EMPTY = IntArray(0)

    /**
     * 语言别名 → native 语言 id。别名表和 `SyntaxHighlight.rulesFor` 逐条对齐，
     * 认不出来返回 [LANG_NONE]（此时按纯文本处理，和 Kotlin 版行为一致）。
     */
    private fun langId(lang: String): Int = when (lang.trim().lowercase()) {
        "kotlin", "kt", "java" -> LANG_KOTLIN
        "javascript", "js", "jsx", "mjs", "cjs", "typescript", "ts", "tsx" -> LANG_JS
        "python", "py" -> LANG_PYTHON
        "json" -> LANG_JSON
        "bash", "sh", "shell", "zsh" -> LANG_BASH
        "sql" -> LANG_SQL
        "html", "htm", "xml", "svg", "vue" -> LANG_HTML
        "css", "scss", "sass", "less" -> LANG_CSS
        "cpp", "c++", "cxx", "cc", "c", "h", "hpp", "hh",
        "objective-c", "objc", "objc++", "objcpp" -> LANG_CPP
        else -> LANG_NONE
    }

    /**
     * JNI 入口。**必须保持 private**：它是本对象里唯一一个在 [available]
     * 为 false 时会抛 UnsatisfiedLinkError 的成员，暴露出去就打破了
     * "每个 public 方法在 native 不可用时返回 null" 的契约。
     * 走 [highlight] / [colorize]。
     */
    private external fun nHighlight(code: String, langId: Int): IntArray?

    /**
     * 单遍扫描 [code]，返回扁平的 `(start, end, tokenType)` 三元组数组。
     *
     * @return null 表示 native 不可用 / 调用失败 —— 调用方应退回
     *         `SyntaxHighlight.colorize`。空数组表示"没有 token"，按纯文本渲染。
     */
    fun highlight(code: String, lang: String): IntArray? {
        if (!available) return null
        if (code.isEmpty()) return EMPTY
        // 超长直接放弃：和 native 侧同一个阈值，这里先挡一道省掉一次
        // JNI 调用 + 一次 400 KB 的 UTF-16 拷贝。
        if (code.length > MAX_HIGHLIGHT_UNITS) return EMPTY
        val id = langId(lang)
        if (id == LANG_NONE) return EMPTY
        return runCatching { nHighlight(code, id) }.getOrNull()
    }

    /**
     * 把 [highlight] 的三元组数组贴到 [code] 上组装成 [AnnotatedString]。
     * 颜色全部来自 [palette]，native 不碰颜色。
     *
     * 对越界/畸形三元组只跳过、不抛异常 —— 万一 native 契约被改坏，
     * 这里最多丢几个色块，不会把渲染打崩。
     */
    fun toAnnotatedString(
        code: String,
        spans: IntArray,
        palette: HighlightPalette
    ): AnnotatedString {
        if (spans.isEmpty()) return AnnotatedString(code)
        val builder = AnnotatedString.Builder(code)
        val n = code.length
        var i = 0
        while (i + 2 < spans.size) {
            val start = spans[i]
            val end = spans[i + 1]
            val type = spans[i + 2]
            i += 3
            if (start < 0 || end > n || start >= end) continue
            val style = palette.styleFor(type) ?: continue
            builder.addStyle(style, start, end)
        }
        return builder.toAnnotatedString()
    }

    /**
     * 一步到位：扫描 + 组装。
     * @return null 表示 native 不可用 —— 调用方应退回 `SyntaxHighlight.colorize`。
     */
    fun colorize(code: String, lang: String, palette: HighlightPalette): AnnotatedString? {
        val spans = highlight(code, lang) ?: return null
        return toAnnotatedString(code, spans, palette)
    }

    /** 用内置的 GitHub 风格亮/暗调色板，和现有 `SyntaxHighlight` 视觉一致。 */
    fun colorize(code: String, lang: String, darkMode: Boolean): AnnotatedString? =
        colorize(code, lang, if (darkMode) HighlightPalette.Dark else HighlightPalette.Light)

    /**
     * UTF-16 偏移自检。native 侧一旦退化成 UTF-8 byte offset 或切断代理对，
     * 这里会立刻失败 —— 这是本项目唯一会"静默出错"的失败模式，别靠肉眼 review。
     *
     * 样本刻意混了中文注释、emoji（代理对）和中文字符串字面量：
     * ```
     * // 中😀
     * val s = "字😀"
     * ```
     * 期望的 UTF-16 span：注释 0..6、关键字 `val` 7..10、字符串 15..20。
     */
    fun selfTest(): Boolean = runCatching {
        if (!available) return@runCatching false
        val code = "// 中😀\nval s = \"字😀\""
        val spans = nHighlight(code, LANG_KOTLIN) ?: return@runCatching false
        spans.contentEquals(
            intArrayOf(
                0, 6, TOKEN_COMMENT,
                7, 10, TOKEN_KEYWORD,
                15, 20, TOKEN_STRING
            )
        )
    }.getOrDefault(false)
}

/**
 * token 类型 → [SpanStyle] 的映射表。颜色只活在 Kotlin 侧，
 * native 只知道语义 id，所以换主题不需要重新扫描。
 *
 * [Light] / [Dark] 的取值逐字段复制自 `SyntaxHighlight` 的同名调色板，
 * 保证 native 路径和降级路径看起来是同一套配色。
 */
class HighlightPalette(
    val comment: SpanStyle,
    val string: SpanStyle,
    val number: SpanStyle,
    val keyword: SpanStyle,
    val func: SpanStyle,
    val type: SpanStyle,
    val preprocessor: SpanStyle
) {
    // 按 token id 下标取，避免每个 token 走一次 when 分支。
    private val byId: Array<SpanStyle> =
        arrayOf(comment, string, number, keyword, func, type, preprocessor)

    /** 未知 token 类型返回 null（跳过，不着色），不抛异常。 */
    fun styleFor(tokenType: Int): SpanStyle? = byId.getOrNull(tokenType)

    companion object {
        val Light = HighlightPalette(
            comment = SpanStyle(color = Color(0xFF6A737D), fontStyle = FontStyle.Italic),
            string = SpanStyle(color = Color(0xFF22863A)),
            number = SpanStyle(color = Color(0xFFD97706)),
            keyword = SpanStyle(color = Color(0xFF5B5BD6), fontWeight = FontWeight.SemiBold),
            func = SpanStyle(color = Color(0xFFB5188F)),
            type = SpanStyle(color = Color(0xFF1F6FEB), fontWeight = FontWeight.Medium),
            preprocessor = SpanStyle(color = Color(0xFFE36209), fontWeight = FontWeight.SemiBold)
        )

        val Dark = HighlightPalette(
            comment = SpanStyle(color = Color(0xFF8B949E), fontStyle = FontStyle.Italic),
            string = SpanStyle(color = Color(0xFF85E89D)),
            number = SpanStyle(color = Color(0xFFF8B763)),
            keyword = SpanStyle(color = Color(0xFFB392F0), fontWeight = FontWeight.SemiBold),
            func = SpanStyle(color = Color(0xFFFFA1F0)),
            type = SpanStyle(color = Color(0xFF79B8FF), fontWeight = FontWeight.Medium),
            preprocessor = SpanStyle(color = Color(0xFFFFAB70), fontWeight = FontWeight.SemiBold)
        )
    }
}
