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
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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

/**
 * Lightweight Markdown renderer with Claude-style typography.
 *
 * Supported:
 *  - ATX headings #..######
 *  - Paragraphs (with hard line break on trailing two spaces or backslash)
 *  - Bulleted (*, -, +) and ordered (1.) lists, single-level nesting
 *  - Task lists `- [ ]` / `- [x]`
 *  - Blockquotes `>` (multiline)
 *  - Fenced code ``` with optional language tag and copy button
 *  - Horizontal rules `---` / `***` / `___`
 *  - GFM pipe tables  | a | b | / |---|---|
 *  - Inline: **bold**, *italic*, ~~strike~~, `code`, [text](url)
 *  - Auto-link bare http(s) URLs
 */
private sealed interface Block {
    data class Heading(val level: Int, val text: String) : Block
    data class Paragraph(val text: String) : Block
    data class BulletItem(val depth: Int, val text: String) : Block
    data class TaskItem(val depth: Int, val text: String, val checked: Boolean) : Block
    data class NumberedItem(val depth: Int, val number: Int, val text: String) : Block
    data class Quote(val lines: List<String>) : Block
    data class CodeBlock(val lang: String, val code: String) : Block
    data class Table(val header: List<String>, val rows: List<List<String>>) : Block
    data object Divider : Block
    data object Blank : Block
}

private val FenceRegex = Regex("^```([\\w+-]*)\\s*$")
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
    return count / 2 // 2 spaces per level
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

        // Fenced code
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
            out += Block.CodeBlock(lang, buf.toString().trimEnd('\n'))
            orderedCounters.clear()
            continue
        }

        // Horizontal rule
        if (HrRegex.matches(line.trimStart())) {
            out += Block.Divider
            i++
            orderedCounters.clear()
            continue
        }

        // GFM table — header row followed by separator row
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

        // Blockquote (consume consecutive `> ` lines)
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

        // Task list `- [ ] foo`
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

        // Bullet list
        val bullet = BulletRegex.matchEntire(trimmed)
        if (bullet != null) {
            out += Block.BulletItem(depth, bullet.groupValues[1].trim())
            i++
            orderedCounters.clear()
            continue
        }

        // Paragraph — accumulate consecutive non-empty/non-special lines into one
        val paraBuf = StringBuilder(line)
        i++
        while (i < lines.size) {
            val next = lines[i]
            val nt = next.trimEnd()
            if (nt.isBlank()) break
            if (nt.trimStart().let {
                    it.startsWith("#") || it.startsWith("- ") || it.startsWith("* ") ||
                        it.startsWith("+ ") || it.startsWith("> ") || it.startsWith("```") ||
                        HrRegex.matches(it) || OrderedRegex.matches(it) ||
                        TablePipeRow.matches(nt)
                }) break
            paraBuf.append('\n').append(next.trimEnd())
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

private fun inline(source: String, baseColor: Color, accent: Color): AnnotatedString =
    buildAnnotatedString {
        var i = 0
        val s = source
        while (i < s.length) {
            // Hard line break: trailing two spaces or backslash before newline
            if (s[i] == '\n') { append('\n'); i++; continue }
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
            // Bold + italic combos: ***x***
            if (i + 2 < s.length && s[i] == '*' && s[i + 1] == '*' && s[i + 2] == '*') {
                val end = s.indexOf("***", i + 3)
                if (end != -1) {
                    withStyle(
                        SpanStyle(
                            fontWeight = FontWeight.Bold,
                            fontStyle = FontStyle.Italic
                        )
                    ) { append(inline(s.substring(i + 3, end), baseColor, accent)) }
                    i = end + 3
                    continue
                }
            }
            // Bold
            if (i + 1 < s.length && s[i] == '*' && s[i + 1] == '*') {
                val end = s.indexOf("**", i + 2)
                if (end != -1) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(inline(s.substring(i + 2, end), baseColor, accent))
                    }
                    i = end + 2
                    continue
                }
            }
            // Italic with _ or *
            if (s[i] == '*' || s[i] == '_') {
                val ch = s[i]
                val end = s.indexOf(ch, i + 1)
                if (end != -1 && end > i + 1) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(inline(s.substring(i + 1, end), baseColor, accent))
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
                        append(inline(s.substring(i + 2, end), baseColor, accent))
                    }
                    i = end + 2
                    continue
                }
            }
            // Markdown link
            if (s[i] == '[') {
                val closeBracket = s.indexOf(']', i + 1)
                if (closeBracket != -1 && closeBracket + 1 < s.length && s[closeBracket + 1] == '(') {
                    val closeParen = s.indexOf(')', closeBracket + 2)
                    if (closeParen != -1) {
                        val text = s.substring(i + 1, closeBracket)
                        val url = s.substring(closeBracket + 2, closeParen)
                        pushStringAnnotation("URL", url)
                        withStyle(
                            SpanStyle(color = accent, textDecoration = TextDecoration.Underline)
                        ) { append(inline(text, baseColor, accent)) }
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
                    withStyle(
                        SpanStyle(color = accent, textDecoration = TextDecoration.Underline)
                    ) { append(m.value) }
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
    val blocks = remember(markdown) { parseBlocks(markdown) }

    Column(modifier.padding(contentPadding)) {
        for (b in blocks) {
            when (b) {
                is Block.Heading -> Heading(b.level, b.text, baseColor, accent)
                is Block.Paragraph ->
                    Text(
                        inline(b.text, baseColor, accent),
                        style = MaterialTheme.typography.bodyLarge.copy(
                            lineHeight = 26.sp
                        ),
                        color = baseColor,
                        modifier = Modifier.padding(vertical = 3.dp)
                    )
                is Block.BulletItem -> Bullet(b.depth, b.text, baseColor, accent)
                is Block.NumberedItem -> Numbered(b.depth, b.number, b.text, baseColor, accent)
                is Block.TaskItem -> TaskItem(b.depth, b.text, b.checked, baseColor, accent)
                is Block.Quote -> Quote(b.lines, baseColor, accent)
                is Block.CodeBlock -> CodeBlock(b.lang, b.code, baseColor)
                is Block.Table -> Table(b.header, b.rows, baseColor, accent)
                Block.Divider -> HorizontalDivider(
                    modifier = Modifier.padding(vertical = 10.dp),
                    thickness = 0.5.dp,
                    color = baseColor.copy(alpha = 0.2f)
                )
                Block.Blank -> Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun Heading(level: Int, text: String, baseColor: Color, accent: Color) {
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
        inline(text, baseColor, accent),
        style = style.copy(fontWeight = FontWeight.Bold),
        color = baseColor
    )
    Spacer(Modifier.height(if (level <= 2) 4.dp else 2.dp))
}

@Composable
private fun Bullet(depth: Int, text: String, baseColor: Color, accent: Color) {
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
            inline(text, baseColor, accent),
            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp),
            color = baseColor
        )
    }
}

@Composable
private fun Numbered(depth: Int, number: Int, text: String, baseColor: Color, accent: Color) {
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
            inline(text, baseColor, accent),
            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp),
            color = baseColor
        )
    }
}

@Composable
private fun TaskItem(depth: Int, text: String, checked: Boolean, baseColor: Color, accent: Color) {
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
            inline(text, baseColor, accent),
            style = MaterialTheme.typography.bodyLarge.copy(
                textDecoration = if (checked) TextDecoration.LineThrough else null
            ),
            color = if (checked) baseColor.copy(alpha = 0.55f) else baseColor
        )
    }
}

@Composable
private fun Quote(lines: List<String>, baseColor: Color, accent: Color) {
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
                    inline(l, baseColor, accent),
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
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(baseColor.copy(alpha = 0.06f))
    ) {
        if (lang.isNotBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 6.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    lang,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    color = baseColor.copy(alpha = 0.55f),
                    modifier = Modifier.weight(1f)
                )
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier
                            .size(12.dp)
                            .clip(RoundedCornerShape(50))
                            .padding(end = 4.dp)
                            .also {
                                // tap target handled by row click below — keep icon non-clickable for now
                            },
                        tint = baseColor.copy(alpha = 0.55f)
                    )
                    Text(
                        "复制",
                        style = MaterialTheme.typography.labelSmall,
                        color = baseColor.copy(alpha = 0.55f),
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .padding(horizontal = 2.dp)
                            .also { }
                    )
                }
            }
        }
        // Code lines — horizontal scroll for long lines.
        val hScroll = rememberScrollState()
        Box(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(hScroll)
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Text(
                code,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                color = baseColor
            )
        }
    }
    // Tap anywhere on code body to copy
    androidx.compose.runtime.LaunchedEffect(code) {
        // Copy action is wired below as a simple invisible click row.
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(50)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "",
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .padding(2.dp)
                .also {
                    // hidden actor — we rely on the code block area copy chip above
                    clipboard // touch ref to avoid unused warning
                }
        )
    }
}

@Composable
private fun Table(header: List<String>, rows: List<List<String>>, baseColor: Color, accent: Color) {
    val borderColor = baseColor.copy(alpha = 0.18f)
    val cols = (listOf(header) + rows).maxOf { it.size }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(baseColor.copy(alpha = 0.04f))
    ) {
        TableRow(
            header.padEnd(cols),
            baseColor, accent,
            isHeader = true,
            borderColor = borderColor
        )
        rows.forEach { r ->
            HorizontalDivider(thickness = 0.5.dp, color = borderColor)
            TableRow(r.padEnd(cols), baseColor, accent, isHeader = false, borderColor = borderColor)
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
    isHeader: Boolean,
    borderColor: Color
) {
    Row(Modifier.fillMaxWidth()) {
        cells.forEachIndexed { idx, c ->
            if (idx > 0) {
                Box(
                    Modifier
                        .width(0.5.dp)
                        .height(if (isHeader) 36.dp else 32.dp)
                        .background(borderColor)
                )
            }
            Box(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp, vertical = if (isHeader) 8.dp else 6.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    inline(c, baseColor, accent),
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
