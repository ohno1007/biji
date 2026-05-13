package com.biji.notes.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.biji.notes.ui.glass.bouncyClickable
import kotlinx.coroutines.delay

/**
 * Lightweight Markdown renderer with Claude-style typography.
 *
 * Supported block types:
 *  - ATX headings #..######
 *  - Paragraphs (auto-merge consecutive non-special lines)
 *  - Bulleted (*, -, +) and ordered lists, two-space nesting, task lists
 *  - Blockquotes `>` (multi-line)
 *  - Fenced code with optional language tag, syntax highlight + copy button
 *  - Horizontal rules `---` / `***` / `___`
 *  - GFM pipe tables  | a | b | / |---|---|
 *  - ``` mermaid blocks rendered as a minimal flowchart
 *  - $$math$$ blocks (basic LaTeX → unicode)
 *
 * Supported inline:
 *  - **bold**, *italic* / _italic_, ***bold-italic***
 *  - ~~strikethrough~~, ==highlighted==
 *  - `inline code`, $inline math$
 *  - [link text](url) — rendered with a soft beige pill background
 *  - auto-link of bare http(s) URLs
 */

// =====================================================================
// AST
// =====================================================================

private sealed interface Block {
    data class Heading(val level: Int, val text: String) : Block
    data class Paragraph(val text: String) : Block
    data class BulletItem(val depth: Int, val text: String) : Block
    data class TaskItem(val depth: Int, val text: String, val checked: Boolean) : Block
    data class NumberedItem(val depth: Int, val number: Int, val text: String) : Block
    data class Quote(val lines: List<String>) : Block
    data class CodeBlock(val lang: String, val code: String) : Block
    data class Mermaid(val source: String) : Block
    data class MathBlock(val source: String) : Block
    data class Table(val header: List<String>, val rows: List<List<String>>) : Block
    data object Divider : Block
    data object Blank : Block
}

private val FenceRegex = Regex("^```([\\w+-]*)\\s*$")
private val MathFenceRegex = Regex("^\\$\\$\\s*$")
private val HrRegex = Regex("^(-{3,}|_{3,}|\\*{3,})\\s*$")
private val HeadingRegex = Regex("^(#{1,6})\\s+(.*)$")
private val OrderedRegex = Regex("^(\\d+)\\.\\s+(.*)$")
private val TaskRegex = Regex("^[-*+]\\s+\\[([ xX])]\\s+(.*)$")
private val BulletRegex = Regex("^[-*+]\\s+(.*)$")
private val TablePipeRow = Regex("^\\|.*\\|\\s*$")
private val TableSeparator = Regex("^\\|\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)+\\|\\s*$")

private fun indentDepth(line: String): Int {
    var i = 0
    var count = 0
    while (i < line.length && line[i] == ' ') {
        i++
        count++
    }
    return count / 2
}

private fun parseBlocks(source: String): List<Block> {
    val out = mutableListOf<Block>()
    val lines = source.replace("\r\n", "\n").split("\n")
    var i = 0
    val orderedCounters = mutableMapOf<Int, Int>()
    while (i < lines.size) {
        val raw = lines[i]
        val line = raw.trimEnd()
        val trimmed = line.trimStart()
        val depth = indentDepth(raw).coerceAtMost(3)

        if (line.isBlank()) {
            if (out.lastOrNull() !is Block.Blank) out += Block.Blank
            orderedCounters.clear()
            i++
            continue
        }

        // $$ ... $$ math block
        if (MathFenceRegex.matches(trimmed)) {
            val buf = StringBuilder()
            i++
            while (i < lines.size && !MathFenceRegex.matches(lines[i].trimStart())) {
                buf.appendLine(lines[i])
                i++
            }
            if (i < lines.size) i++
            out += Block.MathBlock(buf.toString().trimEnd('\n'))
            orderedCounters.clear()
            continue
        }

        // Fenced code or mermaid
        val fence = FenceRegex.matchEntire(trimmed)
        if (fence != null) {
            val lang = fence.groupValues[1]
            val buf = StringBuilder()
            i++
            while (i < lines.size && !FenceRegex.matches(lines[i].trimStart())) {
                buf.appendLine(lines[i])
                i++
            }
            if (i < lines.size) i++
            val body = buf.toString().trimEnd('\n')
            out += if (lang.equals("mermaid", ignoreCase = true)) {
                Block.Mermaid(body)
            } else {
                Block.CodeBlock(lang, body)
            }
            orderedCounters.clear()
            continue
        }

        // Horizontal rule
        if (HrRegex.matches(trimmed)) {
            out += Block.Divider
            i++
            orderedCounters.clear()
            continue
        }

        // GFM table
        if (TablePipeRow.matches(line) &&
            i + 1 < lines.size && TableSeparator.matches(lines[i + 1].trimEnd())
        ) {
            val header = splitTableRow(line)
            val rows = mutableListOf<List<String>>()
            i += 2
            while (i < lines.size && TablePipeRow.matches(lines[i].trimEnd())) {
                rows += splitTableRow(lines[i].trimEnd())
                i++
            }
            out += Block.Table(header, rows)
            orderedCounters.clear()
            continue
        }

        // Headings
        val heading = HeadingRegex.matchEntire(line)
        if (heading != null) {
            val (h, t) = heading.destructured
            out += Block.Heading(h.length, t.trim())
            i++
            orderedCounters.clear()
            continue
        }

        // Blockquote
        if (trimmed.startsWith(">")) {
            val quoteBuf = mutableListOf<String>()
            while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                quoteBuf += lines[i].trimStart().removePrefix(">").trimStart()
                i++
            }
            out += Block.Quote(quoteBuf)
            orderedCounters.clear()
            continue
        }

        // Task list
        val task = TaskRegex.matchEntire(trimmed)
        if (task != null) {
            val checked = task.groupValues[1].lowercase() == "x"
            out += Block.TaskItem(depth, task.groupValues[2].trim(), checked)
            i++
            continue
        }

        // Ordered list
        val ordered = OrderedRegex.matchEntire(trimmed)
        if (ordered != null) {
            val n = orderedCounters.getOrPut(depth) { 0 } + 1
            orderedCounters[depth] = n
            out += Block.NumberedItem(depth, n, ordered.groupValues[2].trim())
            i++
            continue
        }

        // Bullet
        val bullet = BulletRegex.matchEntire(trimmed)
        if (bullet != null) {
            out += Block.BulletItem(depth, bullet.groupValues[1].trim())
            i++
            orderedCounters.clear()
            continue
        }

        // Paragraph
        val paraBuf = StringBuilder(line)
        i++
        while (i < lines.size) {
            val next = lines[i]
            val nt = next.trimEnd()
            if (nt.isBlank()) break
            val nts = nt.trimStart()
            if (nts.startsWith("#") || nts.startsWith("- ") || nts.startsWith("* ") ||
                nts.startsWith("+ ") || nts.startsWith("> ") || nts.startsWith("```") ||
                nts.startsWith("$$") ||
                HrRegex.matches(nts) || OrderedRegex.matches(nts) ||
                TablePipeRow.matches(nt)
            ) break
            paraBuf.append('\n').append(nt)
            i++
        }
        out += Block.Paragraph(paraBuf.toString())
        orderedCounters.clear()
    }
    return out
}

private fun splitTableRow(line: String): List<String> =
    line.trim().trim('|').split('|').map { it.trim() }

// =====================================================================
// Inline parser
// =====================================================================

private val UrlRegex = Regex("https?://[\\w\\-./%?=&#:+~]+")

private fun inline(source: String, baseColor: Color, accent: Color, linkBg: Color): AnnotatedString =
    buildAnnotatedString {
        var i = 0
        val s = source
        while (i < s.length) {
            if (s[i] == '\n') { append('\n'); i++; continue }
            // Inline math $...$
            if (s[i] == '$' && (i + 1 < s.length && s[i + 1] != '$')) {
                val end = s.indexOf('$', i + 1)
                if (end != -1 && end > i + 1) {
                    val raw = s.substring(i + 1, end)
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Serif,
                            fontStyle = FontStyle.Italic,
                            color = baseColor.copy(alpha = 0.92f),
                            background = baseColor.copy(alpha = 0.06f)
                        )
                    ) { append(latexToUnicode(raw)) }
                    i = end + 1
                    continue
                }
            }
            // Inline code
            if (s[i] == '`') {
                val end = s.indexOf('`', i + 1)
                if (end != -1) {
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = baseColor.copy(alpha = 0.10f),
                            color = baseColor.copy(alpha = 0.95f),
                            fontSize = 13.sp
                        )
                    ) { append(s.substring(i + 1, end)) }
                    i = end + 1
                    continue
                }
            }
            // Bold+italic ***x***
            if (i + 2 < s.length && s[i] == '*' && s[i + 1] == '*' && s[i + 2] == '*') {
                val end = s.indexOf("***", i + 3)
                if (end != -1) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) {
                        append(inline(s.substring(i + 3, end), baseColor, accent, linkBg))
                    }
                    i = end + 3
                    continue
                }
            }
            // Bold
            if (i + 1 < s.length && s[i] == '*' && s[i + 1] == '*') {
                val end = s.indexOf("**", i + 2)
                if (end != -1) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(inline(s.substring(i + 2, end), baseColor, accent, linkBg))
                    }
                    i = end + 2
                    continue
                }
            }
            // Italic *x* / _x_
            if (s[i] == '*' || s[i] == '_') {
                val ch = s[i]
                val end = s.indexOf(ch, i + 1)
                if (end != -1 && end > i + 1) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(inline(s.substring(i + 1, end), baseColor, accent, linkBg))
                    }
                    i = end + 1
                    continue
                }
            }
            // Strikethrough
            if (i + 1 < s.length && s[i] == '~' && s[i + 1] == '~') {
                val end = s.indexOf("~~", i + 2)
                if (end != -1) {
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                        append(inline(s.substring(i + 2, end), baseColor, accent, linkBg))
                    }
                    i = end + 2
                    continue
                }
            }
            // Highlight ==x==
            if (i + 1 < s.length && s[i] == '=' && s[i + 1] == '=') {
                val end = s.indexOf("==", i + 2)
                if (end != -1) {
                    withStyle(
                        SpanStyle(
                            background = Color(0xFFFFF3B0),
                            color = Color(0xFF1A1A1F)
                        )
                    ) { append(inline(s.substring(i + 2, end), baseColor, accent, linkBg)) }
                    i = end + 2
                    continue
                }
            }
            // Markdown link [text](url)
            if (s[i] == '[') {
                val closeBracket = s.indexOf(']', i + 1)
                if (closeBracket != -1 && closeBracket + 1 < s.length && s[closeBracket + 1] == '(') {
                    val closeParen = s.indexOf(')', closeBracket + 2)
                    if (closeParen != -1) {
                        val text = s.substring(i + 1, closeBracket)
                        val url = s.substring(closeBracket + 2, closeParen)
                        pushStringAnnotation("URL", url)
                        // Beige pill-ish background + accent text — no underline,
                        // small leading/trailing space so the bg reads as a chip.
                        withStyle(SpanStyle(background = linkBg, color = accent, fontWeight = FontWeight.Medium)) {
                            append(' ')
                            append(text)
                            append(' ')
                        }
                        pop()
                        i = closeParen + 1
                        continue
                    }
                }
            }
            // Auto-link bare URL
            if ((i == 0 || !s[i - 1].isLetterOrDigit()) && s.startsWith("http", i)) {
                val m = UrlRegex.matchAt(s, i)
                if (m != null) {
                    pushStringAnnotation("URL", m.value)
                    withStyle(SpanStyle(background = linkBg, color = accent, fontWeight = FontWeight.Medium)) {
                        append(' ')
                        append(m.value)
                        append(' ')
                    }
                    pop()
                    i += m.value.length
                    continue
                }
            }
            append(s[i])
            i++
        }
    }

// =====================================================================
// Renderer
// =====================================================================

@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val baseColor = LocalContentColor.current
    val accent = MaterialTheme.colorScheme.primary
    // Soft cream pill background for links — matches the chat surface palette.
    val linkBg = MaterialTheme.colorScheme.surfaceContainerHigh
    val blocks = remember(markdown) { parseBlocks(markdown) }

    Column(modifier.padding(contentPadding)) {
        for (b in blocks) {
            when (b) {
                is Block.Heading -> Heading(b.level, b.text, baseColor, accent, linkBg)
                is Block.Paragraph -> Text(
                    inline(b.text, baseColor, accent, linkBg),
                    style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp),
                    color = baseColor,
                    modifier = Modifier.padding(vertical = 3.dp)
                )
                is Block.BulletItem -> Bullet(b.depth, b.text, baseColor, accent, linkBg)
                is Block.NumberedItem -> Numbered(b.depth, b.number, b.text, baseColor, accent, linkBg)
                is Block.TaskItem -> TaskItem(b.depth, b.text, b.checked, baseColor, accent, linkBg)
                is Block.Quote -> Quote(b.lines, baseColor, accent, linkBg)
                is Block.CodeBlock -> CodeBlock(b.lang, b.code, baseColor)
                is Block.Mermaid -> MermaidBlock(b.source, baseColor, accent)
                is Block.MathBlock -> MathBlock(b.source, baseColor)
                is Block.Table -> TableBlock(b.header, b.rows, baseColor, accent, linkBg)
                Block.Divider -> SoftDivider(baseColor)
                Block.Blank -> Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun SoftDivider(baseColor: Color) {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 10.dp),
        thickness = 0.5.dp,
        color = baseColor.copy(alpha = 0.18f)
    )
}

@Composable
private fun Heading(level: Int, text: String, baseColor: Color, accent: Color, linkBg: Color) {
    val style = when (level) {
        1 -> MaterialTheme.typography.headlineMedium
        2 -> MaterialTheme.typography.headlineSmall
        3 -> MaterialTheme.typography.titleLarge
        4 -> MaterialTheme.typography.titleMedium
        5 -> MaterialTheme.typography.titleSmall
        else -> MaterialTheme.typography.labelLarge
    }
    Spacer(Modifier.height(if (level <= 2) 10.dp else 6.dp))
    Text(
        inline(text, baseColor, accent, linkBg),
        style = style.copy(fontWeight = FontWeight.Bold),
        color = baseColor
    )
    Spacer(Modifier.height(if (level <= 2) 4.dp else 2.dp))
}

@Composable
private fun Bullet(depth: Int, text: String, baseColor: Color, accent: Color, linkBg: Color) {
    Row(
        modifier = Modifier.padding(start = (depth * 16).dp, top = 1.dp, bottom = 1.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(Modifier.width(16.dp), contentAlignment = Alignment.TopCenter) {
            Text(
                if (depth == 0) "•" else "◦",
                color = accent,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        Spacer(Modifier.width(2.dp))
        Text(
            inline(text, baseColor, accent, linkBg),
            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp),
            color = baseColor
        )
    }
}

@Composable
private fun Numbered(depth: Int, number: Int, text: String, baseColor: Color, accent: Color, linkBg: Color) {
    Row(
        modifier = Modifier.padding(start = (depth * 16).dp, top = 1.dp, bottom = 1.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(Modifier.widthIn(min = 22.dp)) {
            Text(
                "$number.",
                color = accent,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 2.dp, end = 6.dp)
            )
        }
        Text(
            inline(text, baseColor, accent, linkBg),
            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp),
            color = baseColor
        )
    }
}

@Composable
private fun TaskItem(
    depth: Int, text: String, checked: Boolean,
    baseColor: Color, accent: Color, linkBg: Color
) {
    Row(
        modifier = Modifier.padding(start = (depth * 16).dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (checked) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = if (checked) accent else baseColor.copy(alpha = 0.55f)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            inline(text, baseColor, accent, linkBg),
            style = MaterialTheme.typography.bodyLarge.copy(
                textDecoration = if (checked) TextDecoration.LineThrough else null
            ),
            color = if (checked) baseColor.copy(alpha = 0.55f) else baseColor
        )
    }
}

@Composable
private fun Quote(lines: List<String>, baseColor: Color, accent: Color, linkBg: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height((lines.size * 26).coerceAtLeast(26).dp)
                .background(accent.copy(alpha = 0.55f), RoundedCornerShape(2.dp))
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            lines.forEach { l ->
                Text(
                    inline(l, baseColor, accent, linkBg),
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontStyle = FontStyle.Italic,
                        lineHeight = 24.sp
                    ),
                    color = baseColor.copy(alpha = 0.78f)
                )
            }
        }
    }
}

@Composable
private fun CodeBlock(lang: String, code: String, baseColor: Color) {
    val cs = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    var copied by remember(code) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    val borderColor = baseColor.copy(alpha = 0.15f)
    val containerColor = baseColor.copy(alpha = 0.05f)

    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(containerColor)
    ) {
        // Header: language label + functional copy button.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(baseColor.copy(alpha = 0.04f))
                .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                lang.ifBlank { "code" },
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = baseColor.copy(alpha = 0.55f),
                modifier = Modifier.weight(1f)
            )
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .bouncyClickable(pressedScale = 0.96f) {
                        clipboard.setText(AnnotatedString(code))
                        copied = true
                    }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                    contentDescription = "复制",
                    modifier = Modifier.size(12.dp),
                    tint = if (copied) cs.primary else baseColor.copy(alpha = 0.65f)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    if (copied) "已复制" else "复制",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (copied) cs.primary else baseColor.copy(alpha = 0.65f),
                    fontWeight = FontWeight.Medium
                )
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = borderColor)
        val hScroll = rememberScrollState()
        Box(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(hScroll)
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Text(
                SyntaxHighlight.colorize(code, lang, baseColor),
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                color = baseColor
            )
        }
    }
}

@Composable
private fun MathBlock(source: String, baseColor: Color) {
    val text = latexToUnicode(source).trim()
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(baseColor.copy(alpha = 0.05f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            fontFamily = FontFamily.Serif,
            fontStyle = FontStyle.Italic,
            color = baseColor,
            style = MaterialTheme.typography.titleMedium
        )
    }
}

@Composable
private fun MermaidBlock(source: String, baseColor: Color, accent: Color) {
    val borderColor = baseColor.copy(alpha = 0.18f)
    val nodes = parseMermaid(source)
    if (nodes.isEmpty()) {
        // Fall back to rendering as code if we couldn't parse a flow.
        CodeBlock(lang = "mermaid", code = source, baseColor = baseColor)
        return
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(baseColor.copy(alpha = 0.04f))
            .padding(horizontal = 14.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        nodes.forEachIndexed { idx, node ->
            Box(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(accent.copy(alpha = 0.12f))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    node.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = baseColor,
                    fontWeight = FontWeight.Medium
                )
            }
            if (idx < nodes.lastIndex) {
                Text(
                    if (node.edgeLabel.isNullOrBlank()) "↓" else "↓ ${node.edgeLabel}",
                    style = MaterialTheme.typography.labelSmall,
                    color = baseColor.copy(alpha = 0.55f),
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
    }
}

private data class MermaidNode(val id: String, val label: String, val edgeLabel: String?)

private val MermaidNodeRegex = Regex("([A-Za-z0-9_]+)(?:\\[([^\\]]+)])?")
private val MermaidEdgeRegex = Regex(
    "([A-Za-z0-9_]+)(?:\\[([^\\]]+)])?\\s*-->(?:\\|([^|]+)\\|)?\\s*([A-Za-z0-9_]+)(?:\\[([^\\]]+)])?"
)

/** Very small mermaid parser: handles a linear chain of `A --> B`,
 *  `A[Label] --> B[Label]`, `A -->|edge| B`. Branching is flattened. */
private fun parseMermaid(source: String): List<MermaidNode> {
    val labels = linkedMapOf<String, String>()
    val edges = mutableListOf<Triple<String, String, String?>>()
    for (raw in source.lines()) {
        val line = raw.trim()
        if (line.isBlank()) continue
        if (line.startsWith("flowchart") || line.startsWith("graph") || line.startsWith("---")) continue
        val m = MermaidEdgeRegex.find(line) ?: continue
        val a = m.groupValues[1]
        val aLabel = m.groupValues[2].ifBlank { a }
        val edgeLabel = m.groupValues[3].ifBlank { null }
        val b = m.groupValues[4]
        val bLabel = m.groupValues[5].ifBlank { b }
        labels.putIfAbsent(a, aLabel)
        labels.putIfAbsent(b, bLabel)
        edges += Triple(a, b, edgeLabel)
    }
    if (edges.isEmpty()) return emptyList()
    val ordered = mutableListOf<MermaidNode>()
    val seen = mutableSetOf<String>()
    for ((from, to, edge) in edges) {
        if (seen.add(from)) ordered += MermaidNode(from, labels[from] ?: from, edge)
        else if (ordered.isNotEmpty()) ordered[ordered.lastIndex] =
            ordered.last().copy(edgeLabel = edge ?: ordered.last().edgeLabel)
        if (seen.add(to)) ordered += MermaidNode(to, labels[to] ?: to, null)
    }
    return ordered
}

@Composable
private fun TableBlock(
    header: List<String>,
    rows: List<List<String>>,
    baseColor: Color,
    accent: Color,
    linkBg: Color
) {
    // Unified colour for the outline + all internal dividers; outline
    // sits flush with the rounded outer corners by clipping the column.
    val lineColor = baseColor.copy(alpha = 0.18f)
    val cellBg = baseColor.copy(alpha = 0.04f)
    val headerBg = baseColor.copy(alpha = 0.08f)
    val cols = (listOf(header) + rows).maxOf { it.size }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(cellBg)
    ) {
        // Header row
        Box(Modifier.fillMaxWidth().background(headerBg)) {
            TableRow(
                header.padEnd(cols),
                baseColor, accent, linkBg,
                isHeader = true,
                lineColor = lineColor
            )
        }
        rows.forEach { r ->
            HorizontalDivider(thickness = 0.5.dp, color = lineColor)
            TableRow(r.padEnd(cols), baseColor, accent, linkBg, isHeader = false, lineColor = lineColor)
        }
    }
}

private fun List<String>.padEnd(size: Int): List<String> =
    if (this.size >= size) this else this + List(size - this.size) { "" }

@Composable
private fun TableRow(
    cells: List<String>,
    baseColor: Color,
    accent: Color,
    linkBg: Color,
    isHeader: Boolean,
    lineColor: Color
) {
    Row(Modifier.fillMaxWidth()) {
        cells.forEachIndexed { idx, c ->
            if (idx > 0) {
                Box(
                    Modifier
                        .width(0.5.dp)
                        .height(if (isHeader) 38.dp else 34.dp)
                        .background(lineColor)
                )
            }
            Box(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp, vertical = if (isHeader) 9.dp else 7.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    inline(c, baseColor, accent, linkBg),
                    style = if (isHeader)
                        MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                    else
                        MaterialTheme.typography.bodyMedium,
                    color = baseColor
                )
            }
        }
    }
}
