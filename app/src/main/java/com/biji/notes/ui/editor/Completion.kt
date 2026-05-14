package com.biji.notes.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.biji.notes.ui.glass.bouncyClickable

/** Per-language keyword set. Mirrors a subset of SyntaxHighlight's
 *  keyword tables so the editor doesn't need to depend on its private
 *  rule data. Plain text falls back to identifier-only suggestions. */
private val KeywordsByLang: Map<String, Set<String>> = mapOf(
    "kotlin" to setOf(
        "fun", "val", "var", "class", "object", "interface", "data", "sealed", "enum",
        "if", "else", "when", "for", "while", "return", "import", "package",
        "private", "public", "internal", "protected", "override", "open", "final",
        "lateinit", "true", "false", "null", "this", "super", "try", "catch",
        "finally", "throw", "suspend", "companion", "init"
    ),
    "java" to setOf(
        "class", "public", "private", "protected", "static", "final", "void", "return",
        "if", "else", "for", "while", "do", "switch", "case", "default", "break",
        "continue", "new", "this", "super", "try", "catch", "finally", "throw",
        "throws", "import", "package", "extends", "implements", "interface", "abstract",
        "true", "false", "null", "int", "long", "double", "float", "boolean", "char"
    ),
    "javascript" to setOf(
        "function", "const", "let", "var", "if", "else", "for", "while", "return",
        "import", "export", "from", "default", "class", "extends", "new", "this",
        "super", "try", "catch", "finally", "throw", "true", "false", "null",
        "undefined", "async", "await", "of", "in", "typeof", "instanceof"
    ),
    "typescript" to setOf(
        "function", "const", "let", "var", "if", "else", "for", "while", "return",
        "import", "export", "from", "default", "class", "extends", "new", "this",
        "super", "try", "catch", "finally", "throw", "true", "false", "null",
        "undefined", "async", "await", "of", "in", "typeof", "instanceof",
        "type", "interface", "enum", "as", "any", "string", "number", "boolean"
    ),
    "python" to setOf(
        "def", "if", "elif", "else", "return", "import", "from", "class",
        "for", "while", "try", "except", "finally", "with", "as",
        "lambda", "None", "True", "False", "pass", "break", "continue",
        "yield", "in", "not", "and", "or", "is", "global", "nonlocal"
    ),
    "cpp" to setOf(
        "auto", "break", "case", "char", "class", "const", "constexpr", "continue",
        "default", "delete", "do", "double", "else", "enum", "explicit",
        "extern", "false", "float", "for", "friend", "goto", "if", "inline",
        "int", "long", "namespace", "new", "noexcept", "nullptr", "operator",
        "private", "protected", "public", "return", "short", "signed",
        "sizeof", "static", "struct", "switch", "template", "this",
        "throw", "true", "try", "catch", "typedef", "typename", "union",
        "unsigned", "using", "virtual", "void", "volatile", "while",
        "std", "string", "vector", "cout", "cin", "endl"
    ),
    "c" to setOf(
        "auto", "break", "case", "char", "const", "continue", "default", "do",
        "double", "else", "enum", "extern", "float", "for", "goto", "if",
        "int", "long", "register", "return", "short", "signed", "sizeof", "static",
        "struct", "switch", "typedef", "union", "unsigned", "void", "volatile", "while"
    ),
    "bash" to setOf(
        "if", "then", "else", "elif", "fi", "for", "while", "do", "done",
        "case", "esac", "in", "function", "return", "export", "local",
        "echo", "read", "test", "true", "false"
    ),
    "json" to setOf("true", "false", "null"),
    "sql" to setOf(
        "select", "from", "where", "and", "or", "not", "in", "is", "null",
        "insert", "into", "values", "update", "set", "delete", "create",
        "table", "drop", "alter", "join", "left", "right", "inner", "outer",
        "on", "group", "by", "order", "limit", "offset", "having", "distinct",
        "as", "union", "all"
    )
)

/**
 * Compute lightweight completions for the current caret position.
 *
 *  - Reads the identifier-prefix immediately to the left of the caret.
 *  - If the prefix is < 1 char we don't suggest anything.
 *  - Pool: language keywords + every identifier-shaped token already in
 *    the buffer.
 *  - Filtered by `startsWith(prefix, ignoreCase = true)`, deduplicated,
 *    sorted by length ascending, capped at 12.
 */
internal fun suggestCompletions(
    value: TextFieldValue,
    lang: String
): Pair<String, List<String>> {
    val caret = value.selection.start.coerceIn(0, value.text.length)
    if (caret == 0) return "" to emptyList()
    val text = value.text
    var start = caret
    while (start > 0 && text[start - 1].let { it.isLetterOrDigit() || it == '_' }) start--
    val prefix = text.substring(start, caret)
    if (prefix.isEmpty()) return "" to emptyList()
    val pool = mutableSetOf<String>()
    KeywordsByLang[lang]?.let { pool += it }
    // Identifiers in the buffer.
    val idRegex = Regex("[A-Za-z_][A-Za-z0-9_]{1,}")
    for (m in idRegex.findAll(text)) {
        val token = m.value
        if (token != prefix) pool += token
    }
    val matches = pool
        .filter { it.startsWith(prefix, ignoreCase = true) && it != prefix }
        .sortedWith(compareBy({ it.length }, { it.lowercase() }))
        .take(12)
    return prefix to matches
}

/**
 * Bottom suggestion strip. Tapping an item inserts it at the caret,
 * replacing the in-progress prefix. Hidden when there are no
 * matches (so it doesn't take up keyboard real-estate during typing
 * that doesn't have a meaningful prefix).
 */
@Composable
internal fun CompletionStrip(
    prefix: String,
    suggestions: List<String>,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (suggestions.isEmpty()) return
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(cs.surfaceContainer)
            .padding(vertical = 6.dp)
    ) {
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)
        ) {
            items(suggestions, key = { it }) { sug ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 3.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(cs.surface)
                            .bouncyClickable(pressedScale = 0.95f) { onPick(sug) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Bold the matched prefix portion.
                        Text(
                            text = sug.take(prefix.length),
                            fontFamily = FontFamily.Monospace,
                            color = cs.onSurfaceVariant,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelLarge
                        )
                        Text(
                            text = sug.drop(prefix.length),
                            fontFamily = FontFamily.Monospace,
                            color = cs.onSurface,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }
    }
}

/** Insert [suggestion] at the caret, replacing the current word
 *  prefix. Returns the new [TextFieldValue] with the caret placed at
 *  the end of the inserted token. */
internal fun applySuggestion(value: TextFieldValue, suggestion: String): TextFieldValue {
    val caret = value.selection.start.coerceIn(0, value.text.length)
    val text = value.text
    var start = caret
    while (start > 0 && text[start - 1].let { it.isLetterOrDigit() || it == '_' }) start--
    val before = text.substring(0, start)
    val after = text.substring(caret)
    val newText = before + suggestion + after
    val newCaret = (before + suggestion).length
    return TextFieldValue(newText, selection = androidx.compose.ui.text.TextRange(newCaret))
}
