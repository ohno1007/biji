package com.biji.notes.ui.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

/**
 * Tiny regex-based syntax highlighter — good enough for chat-bubble code,
 * not trying to be a full lexer. Handles strings / comments / numbers /
 * keywords for the most common languages we see in code blocks.
 *
 * Tokens are applied in a strict order so a `/`-comment inside a `"..."`
 * string never accidentally swallows the quote: strings + comments
 * claim characters first, keywords/numbers are only scored on remaining
 * "plain" text.
 */
object SyntaxHighlight {

    private data class Rule(val regex: Regex, val style: SpanStyle)

    private val CommentStyle = SpanStyle(color = Color(0xFF6A737D), fontStyle = FontStyle.Italic)
    private val StringStyle = SpanStyle(color = Color(0xFF22863A))
    private val NumberStyle = SpanStyle(color = Color(0xFFD97706))
    private val KeywordStyle = SpanStyle(color = Color(0xFF5B5BD6), fontWeight = FontWeight.SemiBold)
    private val FuncStyle = SpanStyle(color = Color(0xFFB5188F))

    // ---- Keyword sets ----------------------------------------------------

    private val KotlinKeywords = setOf(
        "fun", "val", "var", "class", "object", "interface", "data", "sealed", "enum",
        "if", "else", "when", "for", "while", "do", "return", "break", "continue",
        "import", "package", "as", "is", "in", "by", "out", "in",
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
    private val SqlKeywords = setOf(
        "select", "from", "where", "and", "or", "not", "in", "is", "null",
        "insert", "into", "values", "update", "set", "delete", "create",
        "table", "drop", "alter", "join", "left", "right", "inner", "outer",
        "on", "group", "by", "order", "limit", "offset", "having", "distinct",
        "as", "union", "all"
    )

    fun colorize(code: String, lang: String, baseColor: Color): AnnotatedString {
        val rules = rulesFor(lang.lowercase())
        if (rules.isEmpty()) return AnnotatedString(code)

        // claim[i] = true means a higher-priority rule already styled position i.
        val claim = BooleanArray(code.length)
        val builder = AnnotatedString.Builder(code)

        // Pass 1: comments + strings claim their ranges exclusively.
        for (rule in rules) {
            if (rule.style !== CommentStyle && rule.style !== StringStyle) continue
            for (m in rule.regex.findAll(code)) {
                val r = m.range
                var anyFree = false
                for (k in r) if (!claim[k]) { anyFree = true; break }
                if (!anyFree) continue
                builder.addStyle(rule.style, r.first, r.last + 1)
                for (k in r) claim[k] = true
            }
        }
        // Pass 2: numbers, keywords, function calls — must not overlap claims.
        for (rule in rules) {
            if (rule.style === CommentStyle || rule.style === StringStyle) continue
            for (m in rule.regex.findAll(code)) {
                val r = m.range
                if ((r).any { claim[it] }) continue
                builder.addStyle(rule.style, r.first, r.last + 1)
            }
        }
        return builder.toAnnotatedString()
    }

    private fun rulesFor(lang: String): List<Rule> = when (lang) {
        "kotlin", "kt" -> kotlinJsRules(KotlinKeywords, includeFunctionCalls = true)
        "java" -> kotlinJsRules(KotlinKeywords, includeFunctionCalls = true) // close enough
        "javascript", "js", "typescript", "ts" -> kotlinJsRules(JsKeywords, includeFunctionCalls = true)
        "python", "py" -> pythonRules()
        "json" -> jsonRules()
        "bash", "sh", "shell", "zsh" -> bashRules()
        "sql" -> sqlRules()
        "" -> emptyList()
        else -> emptyList()
    }

    // ---- Per-language rule tables ---------------------------------------

    private fun kotlinJsRules(keywords: Set<String>, includeFunctionCalls: Boolean): List<Rule> {
        val rules = mutableListOf<Rule>()
        // Comments: line + block.
        rules += Rule(Regex("//[^\\n]*"), CommentStyle)
        rules += Rule(Regex("/\\*[\\s\\S]*?\\*/"), CommentStyle)
        // Strings: ", ', `, plus triple-quoted Kotlin/JS template.
        rules += Rule(Regex("\"(?:\\\\.|[^\"\\\\])*\""), StringStyle)
        rules += Rule(Regex("'(?:\\\\.|[^'\\\\])*'"), StringStyle)
        rules += Rule(Regex("`[^`]*`"), StringStyle)
        // Numbers.
        rules += Rule(Regex("\\b\\d+(?:\\.\\d+)?(?:[Ff]|[Ll])?\\b"), NumberStyle)
        // Keywords.
        rules += Rule(Regex("\\b(?:${keywords.joinToString("|")})\\b"), KeywordStyle)
        // Function calls: identifier followed by `(`.
        if (includeFunctionCalls) {
            rules += Rule(Regex("\\b[A-Za-z_][A-Za-z0-9_]*(?=\\s*\\()"), FuncStyle)
        }
        return rules
    }

    private fun pythonRules(): List<Rule> {
        val rules = mutableListOf<Rule>()
        rules += Rule(Regex("#[^\\n]*"), CommentStyle)
        rules += Rule(Regex("\"\"\"[\\s\\S]*?\"\"\""), StringStyle)
        rules += Rule(Regex("'''[\\s\\S]*?'''"), StringStyle)
        rules += Rule(Regex("\"(?:\\\\.|[^\"\\\\])*\""), StringStyle)
        rules += Rule(Regex("'(?:\\\\.|[^'\\\\])*'"), StringStyle)
        rules += Rule(Regex("\\b\\d+(?:\\.\\d+)?\\b"), NumberStyle)
        rules += Rule(Regex("\\b(?:${PythonKeywords.joinToString("|")})\\b"), KeywordStyle)
        rules += Rule(Regex("\\b[A-Za-z_][A-Za-z0-9_]*(?=\\s*\\()"), FuncStyle)
        return rules
    }

    private fun jsonRules(): List<Rule> {
        return listOf(
            Rule(Regex("\"(?:\\\\.|[^\"\\\\])*\"(?=\\s*:)"), KeywordStyle), // key
            Rule(Regex("\"(?:\\\\.|[^\"\\\\])*\""), StringStyle),         // string value
            Rule(Regex("\\b(?:true|false|null)\\b"), KeywordStyle),
            Rule(Regex("-?\\b\\d+(?:\\.\\d+)?\\b"), NumberStyle)
        )
    }

    private fun bashRules(): List<Rule> {
        return listOf(
            Rule(Regex("#[^\\n]*"), CommentStyle),
            Rule(Regex("\"(?:\\\\.|[^\"\\\\])*\""), StringStyle),
            Rule(Regex("'(?:\\\\.|[^'\\\\])*'"), StringStyle),
            Rule(Regex("\\$\\{[^}]+}|\\$[A-Za-z_][A-Za-z0-9_]*"), KeywordStyle),
            Rule(Regex("\\b(?:if|then|else|fi|for|while|do|done|case|esac|in|function|return|export|local)\\b"), KeywordStyle),
            Rule(Regex("\\b\\d+\\b"), NumberStyle)
        )
    }

    private fun sqlRules(): List<Rule> {
        return listOf(
            Rule(Regex("--[^\\n]*"), CommentStyle),
            Rule(Regex("/\\*[\\s\\S]*?\\*/"), CommentStyle),
            Rule(Regex("'(?:''|[^'])*'"), StringStyle),
            Rule(Regex("\\b\\d+(?:\\.\\d+)?\\b"), NumberStyle),
            Rule(Regex("(?i)\\b(?:${SqlKeywords.joinToString("|")})\\b"), KeywordStyle)
        )
    }
}
