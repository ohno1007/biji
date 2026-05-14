package com.biji.notes.ui.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import com.biji.notes.ui.markdown.SyntaxHighlight

/**
 * A [VisualTransformation] that paints [SyntaxHighlight.colorize] spans
 * onto the live edit buffer **without** changing offsets (so selection,
 * IME composition, cursor tracking all map 1:1).
 *
 * In addition, [errorRanges] gets a red wavy underline so unbalanced
 * brackets / quotes surface inline. Errors are computed once per
 * `value.text` change in the parent.
 */
class SyntaxVisualTransformation(
    private val lang: String,
    private val isDark: Boolean,
    private val errorRanges: List<IntRange>
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        if (lang.isEmpty() && errorRanges.isEmpty()) {
            return TransformedText(text, OffsetMapping.Identity)
        }
        val raw = text.text
        // Colorize gives us spans relative to the same raw string —
        // attach them on top of the existing AnnotatedString without
        // creating any new offsets.
        val colored = runCatching { SyntaxHighlight.colorize(raw, lang, isDark) }
            .getOrElse { AnnotatedString(raw) }
        val builder = AnnotatedString.Builder(raw)
        // 1. Lift colorize spans first (they paint underneath).
        for (span in colored.spanStyles) {
            builder.addStyle(span.item, span.start, span.end)
        }
        // 2. Wavy red underline for any error ranges.
        if (errorRanges.isNotEmpty()) {
            val errStyle = SpanStyle(
                color = Color(0xFFE5484D),
                textDecoration = TextDecoration.Underline
            )
            for (r in errorRanges) {
                val s = r.first.coerceIn(0, raw.length)
                val e = (r.last + 1).coerceIn(s, raw.length)
                if (s < e) builder.addStyle(errStyle, s, e)
            }
        }
        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }
}

/**
 * Find offsets that look syntactically suspicious. Keeps it simple:
 *  - unmatched opening / closing brackets `(){}[]` (each gets a single
 *    range covering its own character)
 *  - unterminated single / double quotes on a line (range from the
 *    opener to the end of the line)
 *
 * We deliberately skip lookups inside string literals and comments to
 * cut false positives; the scan keeps a tiny state machine.
 */
internal fun findSyntaxIssues(text: String): List<IntRange> {
    if (text.isEmpty()) return emptyList()
    val out = mutableListOf<IntRange>()
    val stack = ArrayDeque<Pair<Char, Int>>()
    val opens = mapOf('(' to ')', '[' to ']', '{' to '}')
    val closes = mapOf(')' to '(', ']' to '[', '}' to '{')
    var i = 0
    var lineStart = 0
    while (i < text.length) {
        val ch = text[i]
        when (ch) {
            '\n' -> { lineStart = i + 1; i++ }
            '/' -> {
                if (i + 1 < text.length && text[i + 1] == '/') {
                    // Line comment — skip to next newline.
                    while (i < text.length && text[i] != '\n') i++
                } else if (i + 1 < text.length && text[i + 1] == '*') {
                    i += 2
                    while (i + 1 < text.length && !(text[i] == '*' && text[i + 1] == '/')) i++
                    i = (i + 2).coerceAtMost(text.length)
                } else i++
            }
            '"', '\'' -> {
                val quote = ch
                val start = i
                i++
                var closed = false
                while (i < text.length && text[i] != '\n') {
                    if (text[i] == '\\' && i + 1 < text.length) { i += 2; continue }
                    if (text[i] == quote) { closed = true; i++; break }
                    i++
                }
                if (!closed) out += start..(i - 1).coerceAtLeast(start)
            }
            in opens -> { stack.addLast(ch to i); i++ }
            in closes -> {
                val expectOpen = closes[ch]!!
                if (stack.isEmpty() || stack.last().first != expectOpen) {
                    out += i..i
                } else {
                    stack.removeLast()
                }
                i++
            }
            else -> i++
        }
    }
    while (stack.isNotEmpty()) {
        val (_, pos) = stack.removeLast()
        out += pos..pos
    }
    return out
}
