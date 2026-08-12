package com.biji.notes.ui.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

/**
 * Tiny regex-based syntax highlighter — good enough for chat-bubble code,
 * not trying to be a full lexer.
 *
 * Tokens are applied in a strict order so a `/`-comment inside a `"..."`
 * string never accidentally swallows the quote: strings + comments
 * claim characters first, keywords / numbers / functions are only
 * scored on remaining "plain" text.
 */
object SyntaxHighlight {

    private data class Rule(val regex: Regex, val style: SpanStyle, val priority: Priority)
    private enum class Priority { CLAIM, FILL }

    /** Adaptive palette — pick light / dark variants based on bg luminance. */
    private data class Palette(
        val comment: SpanStyle,
        val string: SpanStyle,
        val number: SpanStyle,
        val keyword: SpanStyle,
        val func: SpanStyle,
        val type: SpanStyle,
        val preprocessor: SpanStyle
    )

    private val Light = Palette(
        comment = SpanStyle(color = Color(0xFF6A737D), fontStyle = FontStyle.Italic),
        string = SpanStyle(color = Color(0xFF22863A)),
        number = SpanStyle(color = Color(0xFFD97706)),
        keyword = SpanStyle(color = Color(0xFF5B5BD6), fontWeight = FontWeight.SemiBold),
        func = SpanStyle(color = Color(0xFFB5188F)),
        type = SpanStyle(color = Color(0xFF1F6FEB), fontWeight = FontWeight.Medium),
        preprocessor = SpanStyle(color = Color(0xFFE36209), fontWeight = FontWeight.SemiBold)
    )

    private val Dark = Palette(
        comment = SpanStyle(color = Color(0xFF8B949E), fontStyle = FontStyle.Italic),
        string = SpanStyle(color = Color(0xFF85E89D)),
        number = SpanStyle(color = Color(0xFFF8B763)),
        keyword = SpanStyle(color = Color(0xFFB392F0), fontWeight = FontWeight.SemiBold),
        func = SpanStyle(color = Color(0xFFFFA1F0)),
        type = SpanStyle(color = Color(0xFF79B8FF), fontWeight = FontWeight.Medium),
        preprocessor = SpanStyle(color = Color(0xFFFFAB70), fontWeight = FontWeight.SemiBold)
    )

    // ---- Keyword sets ---------------------------------------------------

    private val KotlinKeywords = setOf(
        "fun", "val", "var", "class", "object", "interface", "data", "sealed", "enum",
        "if", "else", "when", "for", "while", "do", "return", "break", "continue",
        "import", "package", "as", "is", "in", "by", "out",
        "private", "public", "internal", "protected", "override", "open", "final",
        "abstract", "lateinit", "null", "true", "false", "this", "super",
        "try", "catch", "finally", "throw", "suspend", "companion", "init"
    )
    private val JsKeywords = setOf(
        "function", "const", "let", "var", "if", "else", "for", "while", "do",
        "return", "import", "export", "from", "default", "class", "extends",
        "new", "this", "super", "try", "catch", "finally", "throw",
        "null", "undefined", "true", "false", "async", "await",
        "of", "in", "typeof", "instanceof", "void"
    )
    private val PythonKeywords = setOf(
        "def", "if", "elif", "else", "return", "import", "from", "class",
        "for", "while", "try", "except", "finally", "with", "as",
        "lambda", "None", "True", "False", "pass", "break", "continue",
        "yield", "in", "not", "and", "or", "is", "global", "nonlocal", "async", "await"
    )
    private val CppKeywords = setOf(
        "auto", "break", "case", "char", "class", "const", "constexpr", "continue",
        "default", "delete", "do", "double", "else", "enum", "explicit",
        "extern", "false", "float", "for", "friend", "goto", "if", "inline",
        "int", "long", "namespace", "new", "noexcept", "nullptr", "operator",
        "private", "protected", "public", "register", "return", "short", "signed",
        "sizeof", "static", "static_cast", "dynamic_cast", "const_cast", "reinterpret_cast",
        "struct", "switch", "template", "this", "thread_local",
        "throw", "true", "try", "catch", "typedef", "typeid", "typename", "union",
        "unsigned", "using", "virtual", "void", "volatile", "while",
        "decltype", "mutable", "override", "final", "concept", "requires", "co_await",
        "co_return", "co_yield"
    )
    /** C++ identifiers that are types/STL classes. Coloured separately so
     *  `std::cout`, `std::endl`, `vector<int>` etc. stand out. */
    private val CppTypes = setOf(
        "std", "string", "vector", "map", "unordered_map", "set", "unordered_set",
        "pair", "tuple", "array", "list", "deque", "queue", "stack",
        "shared_ptr", "unique_ptr", "weak_ptr", "ostream", "istream",
        "size_t", "ptrdiff_t", "int8_t", "int16_t", "int32_t", "int64_t",
        "uint8_t", "uint16_t", "uint32_t", "uint64_t", "bool",
        "cout", "cin", "cerr", "endl"
    )
    private val SqlKeywords = setOf(
        "select", "from", "where", "and", "or", "not", "in", "is", "null",
        "insert", "into", "values", "update", "set", "delete", "create",
        "table", "drop", "alter", "join", "left", "right", "inner", "outer",
        "on", "group", "by", "order", "limit", "offset", "having", "distinct",
        "as", "union", "all"
    )

    /** 超过这个长度不高亮，直接返回纯文本。 */
    private const val MAX_HIGHLIGHT_CHARS = 60_000

    fun colorize(code: String, lang: String, darkMode: Boolean): AnnotatedString =
        runCatching { colorizeImpl(code, lang, darkMode) }
            .getOrElse { AnnotatedString(code) }

    // 每条规则都是一个 Regex，rulesFor() 每次调用都会重新编译一整
    // 套。流式输出时 colorize 会被反复调用，编译开销比匹配还大 ——
    // 按 (lang, darkMode) 缓存，顺便把两个 priority 也预分好组。
    private class Rules(val claim: List<Rule>, val fill: List<Rule>)
    private val ruleCache = HashMap<String, Rules>()

    private fun rulesCached(lang: String, darkMode: Boolean): Rules {
        val key = "$lang|$darkMode"
        ruleCache[key]?.let { return it }
        val palette = if (darkMode) Dark else Light
        val all = runCatching { rulesFor(lang, palette) }.getOrElse { emptyList() }
        val r = Rules(
            all.filter { it.priority == Priority.CLAIM },
            all.filter { it.priority == Priority.FILL }
        )
        ruleCache[key] = r
        return r
    }

    private fun colorizeImpl(code: String, lang: String, darkMode: Boolean): AnnotatedString {
        // 超长代码块直接放弃高亮：正则全文扫描是 O(rules × len)，
        // 几百 KB 的输出会把主线程钉死。
        if (code.length > MAX_HIGHLIGHT_CHARS) return AnnotatedString(code)
        val cached = rulesCached(lang.lowercase(), darkMode)
        if (cached.claim.isEmpty() && cached.fill.isEmpty()) return AnnotatedString(code)

        val claim = BooleanArray(code.length)
        val builder = AnnotatedString.Builder(code)

        // Pass 1: CLAIM-priority (strings, comments, preprocessor lines).
        for (rule in cached.claim) {
            for (m in rule.regex.findAll(code)) {
                val r = m.range
                if (r.any { claim[it] }) continue
                builder.addStyle(rule.style, r.first, r.last + 1)
                for (k in r) claim[k] = true
            }
        }
        // Pass 2: FILL-priority — only on unclaimed ranges.
        for (rule in cached.fill) {
            for (m in rule.regex.findAll(code)) {
                val r = m.range
                if (r.any { claim[it] }) continue
                builder.addStyle(rule.style, r.first, r.last + 1)
            }
        }
        return builder.toAnnotatedString()
    }

    private fun rulesFor(lang: String, p: Palette): List<Rule> = when (lang) {
        "kotlin", "kt", "java" ->
            kotlinJsRules(KotlinKeywords, p, includeFunctionCalls = true)
        "javascript", "js", "jsx", "mjs", "cjs", "typescript", "ts", "tsx" ->
            kotlinJsRules(JsKeywords, p, includeFunctionCalls = true)
        "python", "py" -> pythonRules(p)
        "json" -> jsonRules(p)
        "bash", "sh", "shell", "zsh" -> bashRules(p)
        "sql" -> sqlRules(p)
        "html", "htm", "xml", "svg", "vue" -> htmlRules(p)
        "css", "scss", "sass", "less" -> cssRules(p)
        "cpp", "c++", "cxx", "cc", "c", "h", "hpp", "hh", "objective-c", "objc", "objc++", "objcpp" ->
            cppRules(p)
        "" -> emptyList()
        else -> emptyList()
    }

    // ---- Per-language rule tables ---------------------------------------

    private fun kotlinJsRules(
        keywords: Set<String>,
        p: Palette,
        includeFunctionCalls: Boolean
    ): List<Rule> {
        val rules = mutableListOf<Rule>()
        rules += Rule(Regex("//[^\\n]*"), p.comment, Priority.CLAIM)
        rules += Rule(Regex("/\\*[\\s\\S]*?\\*/"), p.comment, Priority.CLAIM)
        rules += Rule(Regex("\"(?:\\\\.|[^\"\\\\])*\""), p.string, Priority.CLAIM)
        rules += Rule(Regex("'(?:\\\\.|[^'\\\\])*'"), p.string, Priority.CLAIM)
        rules += Rule(Regex("`[^`]*`"), p.string, Priority.CLAIM)
        rules += Rule(Regex("\\b\\d+(?:\\.\\d+)?(?:[Ff]|[Ll])?\\b"), p.number, Priority.FILL)
        rules += Rule(Regex("\\b(?:${keywords.joinToString("|")})\\b"), p.keyword, Priority.FILL)
        if (includeFunctionCalls) {
            rules += Rule(Regex("\\b[A-Za-z_][A-Za-z0-9_]*(?=\\s*\\()"), p.func, Priority.FILL)
        }
        return rules
    }

    private fun pythonRules(p: Palette): List<Rule> = listOf(
        Rule(Regex("#[^\\n]*"), p.comment, Priority.CLAIM),
        Rule(Regex("\"\"\"[\\s\\S]*?\"\"\""), p.string, Priority.CLAIM),
        Rule(Regex("'''[\\s\\S]*?'''"), p.string, Priority.CLAIM),
        Rule(Regex("\"(?:\\\\.|[^\"\\\\])*\""), p.string, Priority.CLAIM),
        Rule(Regex("'(?:\\\\.|[^'\\\\])*'"), p.string, Priority.CLAIM),
        Rule(Regex("\\b\\d+(?:\\.\\d+)?\\b"), p.number, Priority.FILL),
        Rule(Regex("\\b(?:${PythonKeywords.joinToString("|")})\\b"), p.keyword, Priority.FILL),
        Rule(Regex("\\b[A-Za-z_][A-Za-z0-9_]*(?=\\s*\\()"), p.func, Priority.FILL)
    )

    private fun jsonRules(p: Palette): List<Rule> = listOf(
        Rule(Regex("\"(?:\\\\.|[^\"\\\\])*\"(?=\\s*:)"), p.keyword, Priority.CLAIM),
        Rule(Regex("\"(?:\\\\.|[^\"\\\\])*\""), p.string, Priority.CLAIM),
        Rule(Regex("\\b(?:true|false|null)\\b"), p.keyword, Priority.FILL),
        Rule(Regex("-?\\b\\d+(?:\\.\\d+)?\\b"), p.number, Priority.FILL)
    )

    private fun bashRules(p: Palette): List<Rule> = listOf(
        Rule(Regex("#[^\\n]*"), p.comment, Priority.CLAIM),
        Rule(Regex("\"(?:\\\\.|[^\"\\\\])*\""), p.string, Priority.CLAIM),
        Rule(Regex("'(?:\\\\.|[^'\\\\])*'"), p.string, Priority.CLAIM),
        // Variables: `${NAME}` or `$NAME`. Android's ICU regex compiler
        // rejects standalone `}` outside a `{n,m}` quantifier — we have
        // to escape it via `\}`. Same goes for `\{`.
        Rule(Regex("""\$\{[^}]+\}|\$[A-Za-z_][A-Za-z0-9_]*"""), p.keyword, Priority.FILL),
        Rule(Regex("\\b(?:if|then|else|fi|for|while|do|done|case|esac|in|function|return|export|local)\\b"), p.keyword, Priority.FILL),
        Rule(Regex("\\b\\d+\\b"), p.number, Priority.FILL)
    )

    private fun sqlRules(p: Palette): List<Rule> = listOf(
        Rule(Regex("--[^\\n]*"), p.comment, Priority.CLAIM),
        Rule(Regex("/\\*[\\s\\S]*?\\*/"), p.comment, Priority.CLAIM),
        Rule(Regex("'(?:''|[^'])*'"), p.string, Priority.CLAIM),
        Rule(Regex("\\b\\d+(?:\\.\\d+)?\\b"), p.number, Priority.FILL),
        Rule(Regex("(?i)\\b(?:${SqlKeywords.joinToString("|")})\\b"), p.keyword, Priority.FILL)
    )

    /** HTML / XML — pull out tags, attribute names, attribute values
     *  and entities. Embedded `<style>` / `<script>` are left alone
     *  (not worth a real DOM-aware pass). */
    private fun htmlRules(p: Palette): List<Rule> = listOf(
        Rule(Regex("<!--[\\s\\S]*?-->"), p.comment, Priority.CLAIM),
        Rule(Regex("\"(?:\\\\.|[^\"\\\\])*\""), p.string, Priority.CLAIM),
        Rule(Regex("'(?:\\\\.|[^'\\\\])*'"), p.string, Priority.CLAIM),
        // <!DOCTYPE ...>, <?xml ...?>, processing instructions.
        Rule(Regex("<!(?:DOCTYPE|doctype)[^>]*>"), p.preprocessor, Priority.CLAIM),
        Rule(Regex("<\\?[\\s\\S]*?\\?>"), p.preprocessor, Priority.CLAIM),
        // Tag opener / closer chevrons.
        Rule(Regex("</?[A-Za-z][A-Za-z0-9-]*"), p.keyword, Priority.FILL),
        Rule(Regex("/?>"), p.keyword, Priority.FILL),
        Rule(Regex("\\b[A-Za-z_:][A-Za-z0-9_.:-]*(?==)"), p.func, Priority.FILL),
        Rule(Regex("&[#A-Za-z0-9]+;"), p.number, Priority.FILL)
    )

    /** CSS / SCSS / LESS — selector, property name, value, units. */
    private fun cssRules(p: Palette): List<Rule> = listOf(
        Rule(Regex("/\\*[\\s\\S]*?\\*/"), p.comment, Priority.CLAIM),
        Rule(Regex("//[^\\n]*"), p.comment, Priority.CLAIM), // SCSS/LESS
        Rule(Regex("\"(?:\\\\.|[^\"\\\\])*\""), p.string, Priority.CLAIM),
        Rule(Regex("'(?:\\\\.|[^'\\\\])*'"), p.string, Priority.CLAIM),
        // @-rules: @media, @import, @keyframes, etc.
        Rule(Regex("@[A-Za-z-]+"), p.preprocessor, Priority.FILL),
        // Property names before a colon.
        Rule(Regex("[A-Za-z-]+(?=\\s*:)"), p.func, Priority.FILL),
        // Hex colour literals + numeric values with optional unit.
        Rule(Regex("#[0-9A-Fa-f]{3,8}\\b"), p.number, Priority.FILL),
        Rule(Regex("\\b-?\\d+(?:\\.\\d+)?(?:px|em|rem|%|vw|vh|s|ms|deg|fr|pt)?\\b"), p.number, Priority.FILL),
        Rule(Regex("\\b(?:important|inherit|initial|unset|auto|none|true|false)\\b"), p.keyword, Priority.FILL)
    )

    private fun cppRules(p: Palette): List<Rule> {
        val rules = mutableListOf<Rule>()
        // Preprocessor lines: claim the whole `# ... endline`.
        rules += Rule(Regex("^[ \\t]*#[^\\n]*", RegexOption.MULTILINE), p.preprocessor, Priority.CLAIM)
        // Comments.
        rules += Rule(Regex("//[^\\n]*"), p.comment, Priority.CLAIM)
        rules += Rule(Regex("/\\*[\\s\\S]*?\\*/"), p.comment, Priority.CLAIM)
        // String / char / raw.
        rules += Rule(Regex("R\"\\(([\\s\\S]*?)\\)\""), p.string, Priority.CLAIM)
        rules += Rule(Regex("\"(?:\\\\.|[^\"\\\\])*\""), p.string, Priority.CLAIM)
        rules += Rule(Regex("'(?:\\\\.|[^'\\\\])'"), p.string, Priority.CLAIM)
        // Numbers (incl hex / float suffix).
        rules += Rule(Regex("\\b(?:0[xX][0-9a-fA-F]+|\\d+(?:\\.\\d+)?[fFlLuU]*)\\b"), p.number, Priority.FILL)
        // Keywords + types.
        rules += Rule(Regex("\\b(?:${CppKeywords.joinToString("|")})\\b"), p.keyword, Priority.FILL)
        rules += Rule(Regex("\\b(?:${CppTypes.joinToString("|")})\\b"), p.type, Priority.FILL)
        // Function calls.
        rules += Rule(Regex("\\b[A-Za-z_][A-Za-z0-9_]*(?=\\s*[(<])"), p.func, Priority.FILL)
        return rules
    }
}
