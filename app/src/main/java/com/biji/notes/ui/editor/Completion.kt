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
    ),
    "html" to setOf(
        "html", "head", "body", "div", "span", "section", "article", "header",
        "footer", "nav", "main", "aside", "title", "meta", "link", "script",
        "style", "h1", "h2", "h3", "h4", "h5", "h6", "p", "a", "ul", "ol", "li",
        "img", "button", "form", "input", "textarea", "label", "table", "tr",
        "td", "th", "tbody", "thead", "tfoot", "iframe", "canvas", "video", "audio",
        "class", "id", "href", "src", "alt", "type", "value", "name", "placeholder",
        "onclick", "onload", "onchange", "viewport", "charset", "rel", "stylesheet"
    ),
    "css" to setOf(
        "color", "background", "background-color", "background-image", "border",
        "margin", "padding", "width", "height", "min-width", "max-width", "min-height",
        "max-height", "display", "flex", "grid", "position", "top", "left", "right",
        "bottom", "font", "font-size", "font-family", "font-weight", "line-height",
        "text-align", "text-decoration", "letter-spacing", "opacity", "transform",
        "transition", "animation", "overflow", "z-index", "cursor", "box-shadow",
        "border-radius", "absolute", "relative", "fixed", "sticky", "block", "inline",
        "inline-block", "none", "auto", "center", "left", "right", "bold", "italic",
        "important", "inherit", "initial", "unset"
    )
)

/** Snippet template: the literal text to insert (with `\n` for line
 *  breaks and `\t` for tab), plus an optional caret marker `$0` that
 *  will be expanded to the desired caret position. Per-language. */
private val SnippetsByLang: Map<String, Map<String, String>> = mapOf(
    "kotlin" to mapOf(
        "if" to "if ($0) {\n    \n}",
        "ife" to "if ($0) {\n    \n} else {\n    \n}",
        "for" to "for ($0 in ) {\n    \n}",
        "while" to "while ($0) {\n    \n}",
        "fun" to "fun $0() {\n    \n}",
        "class" to "class $0 {\n    \n}",
        "data" to "data class $0()",
        "when" to "when ($0) {\n    -> \n    else -> \n}",
        "try" to "try {\n    $0\n} catch (e: Exception) {\n    \n}"
    ),
    "java" to mapOf(
        "if" to "if ($0) {\n    \n}",
        "ife" to "if ($0) {\n    \n} else {\n    \n}",
        "for" to "for (int i = 0; i < $0; i++) {\n    \n}",
        "while" to "while ($0) {\n    \n}",
        "class" to "class $0 {\n    \n}",
        "psvm" to "public static void main(String[] args) {\n    $0\n}",
        "try" to "try {\n    $0\n} catch (Exception e) {\n    \n}"
    ),
    "javascript" to mapOf(
        "if" to "if ($0) {\n    \n}",
        "ife" to "if ($0) {\n    \n} else {\n    \n}",
        "for" to "for (let i = 0; i < $0; i++) {\n    \n}",
        "fore" to "for (const $0 of ) {\n    \n}",
        "while" to "while ($0) {\n    \n}",
        "fun" to "function $0() {\n    \n}",
        "arrow" to "const $0 = () => {\n    \n}",
        "try" to "try {\n    $0\n} catch (e) {\n    \n}"
    ),
    "typescript" to mapOf(
        "if" to "if ($0) {\n    \n}",
        "ife" to "if ($0) {\n    \n} else {\n    \n}",
        "for" to "for (let i = 0; i < $0; i++) {\n    \n}",
        "fore" to "for (const $0 of ) {\n    \n}",
        "while" to "while ($0) {\n    \n}",
        "fun" to "function $0(): void {\n    \n}",
        "arrow" to "const $0 = (): void => {\n    \n}",
        "interface" to "interface $0 {\n    \n}",
        "type" to "type $0 = ",
        "try" to "try {\n    $0\n} catch (e) {\n    \n}"
    ),
    "python" to mapOf(
        "if" to "if $0:\n    ",
        "ife" to "if $0:\n    \nelse:\n    ",
        "for" to "for $0 in :\n    ",
        "while" to "while $0:\n    ",
        "def" to "def $0():\n    ",
        "class" to "class $0:\n    def __init__(self):\n        ",
        "try" to "try:\n    $0\nexcept Exception as e:\n    "
    ),
    "cpp" to mapOf(
        "if" to "if ($0) {\n    \n}",
        "ife" to "if ($0) {\n    \n} else {\n    \n}",
        "for" to "for (int i = 0; i < $0; i++) {\n    \n}",
        "while" to "while ($0) {\n    \n}",
        "main" to "int main(int argc, char** argv) {\n    $0\n    return 0;\n}",
        "incl" to "#include <$0>"
    ),
    "c" to mapOf(
        "if" to "if ($0) {\n    \n}",
        "for" to "for (int i = 0; i < $0; i++) {\n    \n}",
        "while" to "while ($0) {\n    \n}",
        "main" to "int main(int argc, char** argv) {\n    $0\n    return 0;\n}",
        "incl" to "#include <$0>"
    ),
    "bash" to mapOf(
        "if" to "if [ $0 ]; then\n    \nfi",
        "ife" to "if [ $0 ]; then\n    \nelse\n    \nfi",
        "for" to "for $0 in ; do\n    \ndone",
        "while" to "while [ $0 ]; do\n    \ndone",
        "fun" to "$0() {\n    \n}"
    ),
    "html" to mapOf(
        "html5" to "<!DOCTYPE html>\n<html lang=\"zh\">\n<head>\n  <meta charset=\"UTF-8\">\n  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n  <title>$0</title>\n</head>\n<body>\n  \n</body>\n</html>",
        "div" to "<div class=\"$0\">\n  \n</div>",
        "a" to "<a href=\"$0\"></a>",
        "img" to "<img src=\"$0\" alt=\"\">",
        "script" to "<script>\n  $0\n</script>",
        "style" to "<style>\n  $0\n</style>",
        "link" to "<link rel=\"stylesheet\" href=\"$0\">",
        "btn" to "<button onclick=\"$0\"></button>",
        "ul" to "<ul>\n  <li>$0</li>\n</ul>",
        "form" to "<form action=\"$0\" method=\"post\">\n  \n</form>"
    ),
    "css" to mapOf(
        "flex" to "display: flex;\nalign-items: center;\njustify-content: $0;",
        "grid" to "display: grid;\ngrid-template-columns: $0;\ngap: 12px;",
        "abs" to "position: absolute;\ntop: $0;\nleft: 0;",
        "media" to "@media (max-width: $0) {\n  \n}",
        "kf" to "@keyframes $0 {\n  from {  }\n  to {  }\n}"
    )
)

/** A completion candidate. [snippetTemplate] is non-null for "expand a
 *  multi-line scaffold" suggestions; null for plain identifier inserts. */
internal data class CompletionItem(
    val label: String,
    val snippetTemplate: String? = null
)

/**
 * Compute lightweight completions for the current caret position.
 *
 *  - Reads the identifier-prefix immediately to the left of the caret.
 *  - If the prefix is < 1 char we don't suggest anything.
 *  - Pool: language snippet keys (expandable) + keywords + every
 *    identifier-shaped token already in the buffer.
 *  - Snippet matches surface first; the rest sort by length asc.
 */
internal fun suggestCompletions(
    value: TextFieldValue,
    lang: String
): Pair<String, List<CompletionItem>> {
    val caret = value.selection.start.coerceIn(0, value.text.length)
    if (caret == 0) return "" to emptyList()
    val text = value.text
    var start = caret
    while (start > 0 && text[start - 1].let { it.isLetterOrDigit() || it == '_' }) start--
    val prefix = text.substring(start, caret)
    if (prefix.isEmpty()) return "" to emptyList()
    val snippets = SnippetsByLang[lang].orEmpty()
    val snippetMatches = snippets
        .filter { (k, _) -> k.startsWith(prefix, ignoreCase = true) && k != prefix }
        .map { (k, tmpl) -> CompletionItem(label = k, snippetTemplate = tmpl) }
        .sortedBy { it.label.length }
    val pool = mutableSetOf<String>()
    KeywordsByLang[lang]?.let { pool += it }
    // Identifiers in the buffer.
    val idRegex = Regex("[A-Za-z_][A-Za-z0-9_]{1,}")
    for (m in idRegex.findAll(text)) {
        val token = m.value
        if (token != prefix) pool += token
    }
    val tokenMatches = pool
        .filter { it.startsWith(prefix, ignoreCase = true) && it != prefix && it !in snippets }
        .sortedWith(compareBy({ it.length }, { it.lowercase() }))
        .map { CompletionItem(it) }
    val merged = (snippetMatches + tokenMatches).take(12)
    return prefix to merged
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
    suggestions: List<CompletionItem>,
    onPick: (CompletionItem) -> Unit,
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
            items(suggestions, key = { it.label + (it.snippetTemplate ?: "") }) { item ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 3.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(
                                if (item.snippetTemplate != null) cs.primary.copy(alpha = 0.18f)
                                else cs.surface
                            )
                            .bouncyClickable(pressedScale = 0.95f) { onPick(item) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Snippet items get a small lightning glyph in front.
                        if (item.snippetTemplate != null) {
                            Text(
                                text = "⚡",
                                fontFamily = FontFamily.Monospace,
                                color = cs.primary,
                                style = MaterialTheme.typography.labelLarge
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                        // Bold the matched prefix portion.
                        Text(
                            text = item.label.take(prefix.length),
                            fontFamily = FontFamily.Monospace,
                            color = cs.onSurfaceVariant,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelLarge
                        )
                        Text(
                            text = item.label.drop(prefix.length),
                            fontFamily = FontFamily.Monospace,
                            color = if (item.snippetTemplate != null) cs.primary else cs.onSurface,
                            fontWeight = if (item.snippetTemplate != null) FontWeight.SemiBold else FontWeight.Normal,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }
    }
}

/** Insert [item] at the caret, replacing the current word prefix.
 *  Plain identifiers land with the caret after the inserted token;
 *  snippet templates resolve the `$0` placeholder and place the caret
 *  there (if no marker, caret ends after the inserted text). */
internal fun applySuggestion(value: TextFieldValue, item: CompletionItem): TextFieldValue {
    val caret = value.selection.start.coerceIn(0, value.text.length)
    val text = value.text
    var start = caret
    while (start > 0 && text[start - 1].let { it.isLetterOrDigit() || it == '_' }) start--
    val before = text.substring(0, start)
    val after = text.substring(caret)
    return if (item.snippetTemplate != null) {
        val tmpl = item.snippetTemplate
        val markerIdx = tmpl.indexOf("\$0")
        val resolved = if (markerIdx >= 0) tmpl.removeRange(markerIdx, markerIdx + 2) else tmpl
        val newText = before + resolved + after
        val caretPos = if (markerIdx >= 0) before.length + markerIdx
        else before.length + resolved.length
        TextFieldValue(newText, selection = androidx.compose.ui.text.TextRange(caretPos))
    } else {
        val newText = before + item.label + after
        val newCaret = (before + item.label).length
        TextFieldValue(newText, selection = androidx.compose.ui.text.TextRange(newCaret))
    }
}
