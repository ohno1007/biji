package com.biji.notes.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import com.biji.notes.ui.glass.bouncyClickable
import kotlinx.coroutines.delay

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
    while (i < line.length && line[i] == ' ') { i++; count++ }
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
            i++; continue
        }
        if (MathFenceRegex.matches(trimmed)) {
            val buf = StringBuilder(); i++
            while (i < lines.size && !MathFenceRegex.matches(lines[i].trimStart())) {
                buf.appendLine(lines[i]); i++
            }
            if (i < lines.size) i++
            out += Block.MathBlock(buf.toString().trimEnd('\n'))
            orderedCounters.clear(); continue
        }
        val fence = FenceRegex.matchEntire(trimmed)
        if (fence != null) {
            val lang = fence.groupValues[1]
            val buf = StringBuilder(); i++
            while (i < lines.size && !FenceRegex.matches(lines[i].trimStart())) {
                buf.appendLine(lines[i]); i++
            }
            if (i < lines.size) i++
            val body = buf.toString().trimEnd('\n')
            val isFlowSyntax = body.contains("=>") && body.contains("->") // flowchart.js
            out += when {
                lang.equals("mermaid", ignoreCase = true) -> Block.Mermaid(body)
                lang.equals("flow", ignoreCase = true) ||
                    lang.equals("flowchart", ignoreCase = true) -> Block.Mermaid(body)
                lang.isBlank() && isFlowSyntax -> Block.Mermaid(body)
                else -> Block.CodeBlock(lang, body)
            }
            orderedCounters.clear(); continue
        }
        if (HrRegex.matches(trimmed)) {
            out += Block.Divider; i++; orderedCounters.clear(); continue
        }
        if (TablePipeRow.matches(line) &&
            i + 1 < lines.size && TableSeparator.matches(lines[i + 1].trimEnd())
        ) {
            val header = splitTableRow(line); val rows = mutableListOf<List<String>>(); i += 2
            while (i < lines.size && TablePipeRow.matches(lines[i].trimEnd())) {
                rows += splitTableRow(lines[i].trimEnd()); i++
            }
            out += Block.Table(header, rows); orderedCounters.clear(); continue
        }
        val heading = HeadingRegex.matchEntire(line)
        if (heading != null) {
            val (h, t) = heading.destructured
            out += Block.Heading(h.length, t.trim()); i++; orderedCounters.clear(); continue
        }
        if (trimmed.startsWith(">")) {
            val quoteBuf = mutableListOf<String>()
            while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                quoteBuf += lines[i].trimStart().removePrefix(">").trimStart(); i++
            }
            out += Block.Quote(quoteBuf); orderedCounters.clear(); continue
        }
        val task = TaskRegex.matchEntire(trimmed)
        if (task != null) {
            out += Block.TaskItem(depth, task.groupValues[2].trim(),
                task.groupValues[1].lowercase() == "x")
            i++; continue
        }
        val ordered = OrderedRegex.matchEntire(trimmed)
        if (ordered != null) {
            val n = orderedCounters.getOrPut(depth) { 0 } + 1
            orderedCounters[depth] = n
            out += Block.NumberedItem(depth, n, ordered.groupValues[2].trim())
            i++; continue
        }
        val bullet = BulletRegex.matchEntire(trimmed)
        if (bullet != null) {
            out += Block.BulletItem(depth, bullet.groupValues[1].trim())
            i++; orderedCounters.clear(); continue
        }
        // Paragraph
        val paraBuf = StringBuilder(line); i++
        while (i < lines.size) {
            val next = lines[i]; val nt = next.trimEnd()
            if (nt.isBlank()) break
            val nts = nt.trimStart()
            if (nts.startsWith("#") || nts.startsWith("- ") || nts.startsWith("* ") ||
                nts.startsWith("+ ") || nts.startsWith("> ") || nts.startsWith("```") ||
                nts.startsWith("$$") ||
                HrRegex.matches(nts) || OrderedRegex.matches(nts) ||
                TablePipeRow.matches(nt)
            ) break
            paraBuf.append('\n').append(nt); i++
        }
        out += Block.Paragraph(paraBuf.toString())
        orderedCounters.clear()
    }
    return out
}

private fun splitTableRow(line: String): List<String> =
    line.trim().trim('|').split('|').map { it.trim() }

// =====================================================================
// Inline parser with InlineTextContent for real link pills
// =====================================================================

private val UrlRegex = Regex("https?://[\\w\\-./%?=&#:+~]+")

internal data class InlineRendered(
    val annotated: AnnotatedString,
    val inlineContent: Map<String, InlineTextContent>
)

/**
 * Build an [AnnotatedString] for the supplied inline source, replacing
 * every `[text](url)` and bare `http(s)://…` token with an
 * [InlineTextContent] placeholder so the renderer can paint each link
 * as a real rounded-pill composable (with the correct measured width).
 */
@Composable
internal fun inlineRender(
    source: String,
    baseColor: Color,
    accent: Color,
    linkBg: Color,
    pillTextStyle: TextStyle
): InlineRendered {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val inlineContent = mutableMapOf<String, InlineTextContent>()
    var nextId = 0

    fun registerLink(text: String, url: String): String {
        val id = "link-${nextId++}"
        val layout = measurer.measure(AnnotatedString(text), pillTextStyle)
        val sizePx = layout.size
        // Pad horizontally (10 dp on each side) so the rounded pill leaves
        // breathing room around the text.
        val padPx = with(density) { 10.dp.toPx() }
        val widthSp = with(density) { (sizePx.width + padPx * 2).toSp() }
        val heightSp = with(density) { (sizePx.height + with(density) { 4.dp.toPx() }).toSp() }
        inlineContent[id] = InlineTextContent(
            placeholder = Placeholder(
                width = widthSp,
                height = heightSp,
                placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter
            ),
            children = {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(linkBg)
                        .padding(horizontal = 10.dp, vertical = 1.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text,
                        style = pillTextStyle,
                        color = accent,
                        maxLines = 1
                    )
                }
            }
        )
        return id
    }

    val annotated = buildAnnotatedString {
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
                    i = end + 1; continue
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
                    i = end + 1; continue
                }
            }
            // Bold + italic ***x***
            if (i + 2 < s.length && s[i] == '*' && s[i + 1] == '*' && s[i + 2] == '*') {
                val end = s.indexOf("***", i + 3)
                if (end != -1) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) {
                        append(s.substring(i + 3, end))
                    }
                    i = end + 3; continue
                }
            }
            // Bold
            if (i + 1 < s.length && s[i] == '*' && s[i + 1] == '*') {
                val end = s.indexOf("**", i + 2)
                if (end != -1) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(s.substring(i + 2, end))
                    }
                    i = end + 2; continue
                }
            }
            // Italic
            if (s[i] == '*' || s[i] == '_') {
                val ch = s[i]
                val end = s.indexOf(ch, i + 1)
                if (end != -1 && end > i + 1) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(s.substring(i + 1, end))
                    }
                    i = end + 1; continue
                }
            }
            // Strikethrough
            if (i + 1 < s.length && s[i] == '~' && s[i + 1] == '~') {
                val end = s.indexOf("~~", i + 2)
                if (end != -1) {
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                        append(s.substring(i + 2, end))
                    }
                    i = end + 2; continue
                }
            }
            // Highlight ==x==
            if (i + 1 < s.length && s[i] == '=' && s[i + 1] == '=') {
                val end = s.indexOf("==", i + 2)
                if (end != -1) {
                    withStyle(
                        SpanStyle(
                            background = Color(0x66FFE066),
                            color = baseColor
                        )
                    ) { append(s.substring(i + 2, end)) }
                    i = end + 2; continue
                }
            }
            // Markdown link [text](url) → inline pill
            if (s[i] == '[') {
                val closeBracket = s.indexOf(']', i + 1)
                if (closeBracket != -1 && closeBracket + 1 < s.length && s[closeBracket + 1] == '(') {
                    val closeParen = s.indexOf(')', closeBracket + 2)
                    if (closeParen != -1) {
                        val text = s.substring(i + 1, closeBracket)
                        val url = s.substring(closeBracket + 2, closeParen)
                        val id = registerLink(text, url)
                        appendInlineContent(id, text)
                        addStringAnnotation("URL", url, length - 1, length)
                        i = closeParen + 1; continue
                    }
                }
            }
            // Auto-link bare URL → inline pill
            if ((i == 0 || !s[i - 1].isLetterOrDigit()) && s.startsWith("http", i)) {
                val m = UrlRegex.matchAt(s, i)
                if (m != null) {
                    val id = registerLink(m.value, m.value)
                    appendInlineContent(id, m.value)
                    addStringAnnotation("URL", m.value, length - 1, length)
                    i += m.value.length; continue
                }
            }
            append(s[i]); i++
        }
    }
    return InlineRendered(annotated, inlineContent)
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
    val linkBg = MaterialTheme.colorScheme.surfaceContainerHigh
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val blocks = remember(markdown) { parseBlocks(markdown) }

    Column(modifier.padding(contentPadding)) {
        for (b in blocks) {
            when (b) {
                is Block.Heading -> Heading(b.level, b.text, baseColor, accent, linkBg)
                is Block.Paragraph -> Paragraph(b.text, baseColor, accent, linkBg)
                is Block.BulletItem -> Bullet(b.depth, b.text, baseColor, accent, linkBg)
                is Block.NumberedItem -> Numbered(b.depth, b.number, b.text, baseColor, accent, linkBg)
                is Block.TaskItem -> TaskItem(b.depth, b.text, b.checked, baseColor, accent, linkBg)
                is Block.Quote -> Quote(b.lines, baseColor, accent, linkBg)
                is Block.CodeBlock -> CodeBlock(b.lang, b.code, baseColor, isDark)
                is Block.Mermaid -> MermaidBlock(b.source, baseColor, accent)
                is Block.MathBlock -> MathBlockBox(b.source, baseColor)
                is Block.Table -> TableBlock(b.header, b.rows, baseColor, accent, linkBg)
                Block.Divider -> SoftDivider(baseColor)
                Block.Blank -> Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun PillText(
    rendered: InlineRendered,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier
) {
    Text(
        text = rendered.annotated,
        style = style,
        color = color,
        inlineContent = rendered.inlineContent,
        modifier = modifier
    )
}

@Composable
private fun Paragraph(text: String, baseColor: Color, accent: Color, linkBg: Color) {
    val pillStyle = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium)
    val rendered = inlineRender(text, baseColor, accent, linkBg, pillStyle)
    PillText(
        rendered = rendered,
        style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp),
        color = baseColor,
        modifier = Modifier.padding(vertical = 3.dp).fillMaxWidth()
    )
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
    val pillStyle = style.copy(fontWeight = FontWeight.Medium)
    val rendered = inlineRender(text, baseColor, accent, linkBg, pillStyle)
    Spacer(Modifier.height(if (level <= 2) 10.dp else 6.dp))
    PillText(
        rendered = rendered,
        style = style.copy(fontWeight = FontWeight.Bold),
        color = baseColor
    )
    Spacer(Modifier.height(if (level <= 2) 4.dp else 2.dp))
}

@Composable
private fun Bullet(depth: Int, text: String, baseColor: Color, accent: Color, linkBg: Color) {
    val rendered = inlineRender(
        text, baseColor, accent, linkBg,
        MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium)
    )
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
        PillText(
            rendered = rendered,
            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp),
            color = baseColor
        )
    }
}

@Composable
private fun Numbered(depth: Int, number: Int, text: String, baseColor: Color, accent: Color, linkBg: Color) {
    val rendered = inlineRender(
        text, baseColor, accent, linkBg,
        MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium)
    )
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
        PillText(
            rendered = rendered,
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
    val rendered = inlineRender(
        text, baseColor, accent, linkBg,
        MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium)
    )
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
        PillText(
            rendered = rendered,
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
            .height(IntrinsicSize.Min)
    ) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(accent.copy(alpha = 0.55f), RoundedCornerShape(2.dp))
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            lines.forEach { l ->
                val rendered = inlineRender(
                    l, baseColor, accent, linkBg,
                    MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium)
                )
                PillText(
                    rendered = rendered,
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
private fun CodeBlock(lang: String, code: String, baseColor: Color, isDark: Boolean) {
    val cs = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    var copied by remember(code) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) { delay(1500); copied = false }
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
                SyntaxHighlight.colorize(code, lang, isDark),
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                color = baseColor
            )
        }
    }
}

@Composable
private fun MathBlockBox(source: String, baseColor: Color) {
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
    val nodes = parseFlow(source)
    if (nodes.isEmpty()) {
        CodeBlock(lang = "flowchart", code = source, baseColor = baseColor, isDark = false)
        return
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(baseColor.copy(alpha = 0.05f))
            .padding(horizontal = 14.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        nodes.forEachIndexed { idx, node ->
            FlowNodeBox(node, baseColor, accent)
            if (idx < nodes.lastIndex) {
                Text(
                    if (node.outEdgeLabel.isNullOrBlank()) "↓" else "↓ ${node.outEdgeLabel}",
                    style = MaterialTheme.typography.labelSmall,
                    color = baseColor.copy(alpha = 0.55f),
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun FlowNodeBox(node: FlowNode, baseColor: Color, accent: Color) {
    val shape: RoundedCornerShape = when (node.type) {
        "start", "end" -> RoundedCornerShape(50)
        "condition" -> RoundedCornerShape(4.dp)
        else -> RoundedCornerShape(10.dp)
    }
    val bg = when (node.type) {
        "start" -> accent.copy(alpha = 0.18f)
        "end" -> accent.copy(alpha = 0.10f)
        "condition" -> Color(0xFFFFE08A).copy(alpha = 0.45f)
        else -> baseColor.copy(alpha = 0.10f)
    }
    Box(
        Modifier
            .clip(shape)
            .background(bg)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            node.label,
            style = MaterialTheme.typography.labelLarge,
            color = baseColor,
            fontWeight = FontWeight.Medium
        )
    }
}

private data class FlowNode(
    val id: String,
    val label: String,
    val type: String,           // "start" / "end" / "condition" / "op"
    val outEdgeLabel: String?
)

private val MermaidEdgeRegex = Regex(
    "([A-Za-z0-9_]+)(?:\\[([^\\]]+)])?\\s*-->(?:\\|([^|]+)\\|)?\\s*([A-Za-z0-9_]+)(?:\\[([^\\]]+)])?"
)
private val FlowDefRegex = Regex(
    "^([A-Za-z0-9_]+)\\s*=>\\s*([A-Za-z]+)\\s*:\\s*(.+)$"
)
private val FlowChainRegex = Regex("([A-Za-z0-9_]+(?:\\([^)]+\\))?)")

/**
 * Lenient flow-chart parser. Handles:
 *  - Mermaid:        `A --> B`, `A[Label] --> B[Label]`, `A -->|edge| B`
 *  - flowchart.js:   `id=>type: label`, then chains like `a->b->c`,
 *                    `cond(yes)->target` (conditional branch).
 *  Branches are flattened into a linear chain — branching topologies
 *  fall back to the source order.
 */
private fun parseFlow(source: String): List<FlowNode> {
    val labels = linkedMapOf<String, String>()
    val types = mutableMapOf<String, String>()
    val edges = mutableListOf<Triple<String, String, String?>>()

    for (raw in source.lines()) {
        val line = raw.trim()
        if (line.isBlank()) continue
        if (line.startsWith("flowchart") || line.startsWith("graph")) continue
        // flowchart.js definition
        val def = FlowDefRegex.matchEntire(line)
        if (def != null) {
            val id = def.groupValues[1]; val type = def.groupValues[2].lowercase()
            val label = def.groupValues[3].trim()
            labels[id] = label
            types[id] = type
            continue
        }
        // flowchart.js chains using `->`
        if (line.contains("->") && !line.contains("-->")) {
            val parts = line.split("->").map { it.trim() }
            for ((idx, raw0) in parts.withIndex()) {
                if (idx == parts.lastIndex) break
                val from = parts[idx]; val to = parts[idx + 1]
                val (fromId, edgeLabel) = parseBranchPart(from)
                val (toId, _) = parseBranchPart(to)
                if (fromId.isNotBlank() && toId.isNotBlank()) {
                    labels.putIfAbsent(fromId, fromId)
                    labels.putIfAbsent(toId, toId)
                    edges += Triple(fromId, toId, edgeLabel)
                }
            }
            continue
        }
        // Mermaid arrow
        val m = MermaidEdgeRegex.find(line)
        if (m != null) {
            val a = m.groupValues[1]; val aLabel = m.groupValues[2].ifBlank { a }
            val edgeLabel = m.groupValues[3].ifBlank { null }
            val b = m.groupValues[4]; val bLabel = m.groupValues[5].ifBlank { b }
            labels.putIfAbsent(a, aLabel); labels.putIfAbsent(b, bLabel)
            edges += Triple(a, b, edgeLabel)
        }
    }
    if (edges.isEmpty()) return emptyList()
    val ordered = mutableListOf<FlowNode>()
    val seen = mutableSetOf<String>()
    for ((from, to, edge) in edges) {
        if (seen.add(from)) {
            ordered += FlowNode(from, labels[from] ?: from, types[from] ?: "op", edge)
        } else if (ordered.isNotEmpty() && edge != null) {
            val idx = ordered.indexOfLast { it.id == from }
            if (idx >= 0) ordered[idx] = ordered[idx].copy(outEdgeLabel = edge)
        }
        if (seen.add(to)) {
            ordered += FlowNode(to, labels[to] ?: to, types[to] ?: "op", null)
        }
    }
    return ordered
}

/** flowchart.js branches look like `cond(yes)`; extract id + branch label. */
private fun parseBranchPart(part: String): Pair<String, String?> {
    val m = Regex("^([A-Za-z0-9_]+)(?:\\(([^)]+)\\))?$").matchEntire(part)
    return if (m != null) {
        m.groupValues[1] to m.groupValues[2].ifBlank { null }
    } else part to null
}

@Composable
private fun TableBlock(
    header: List<String>,
    rows: List<List<String>>,
    baseColor: Color,
    accent: Color,
    linkBg: Color
) {
    val lineColor = baseColor.copy(alpha = 0.18f)
    val cellBg = baseColor.copy(alpha = 0.04f)
    val headerBg = baseColor.copy(alpha = 0.10f)
    val cols = (listOf(header) + rows).maxOf { it.size }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(cellBg)
    ) {
        Box(Modifier.fillMaxWidth().background(headerBg)) {
            TableRow(header.padEnd(cols), baseColor, accent, linkBg, isHeader = true, lineColor = lineColor)
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
    // IntrinsicSize.Min makes the row only as tall as its tallest cell's
    // min intrinsic height. fillMaxHeight() on the column dividers then
    // gives them full row coverage — no more half-height vertical lines
    // when a cell wraps.
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
    ) {
        cells.forEachIndexed { idx, c ->
            if (idx > 0) {
                Box(
                    Modifier
                        .width(0.5.dp)
                        .fillMaxHeight()
                        .background(lineColor)
                )
            }
            Box(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp, vertical = if (isHeader) 9.dp else 7.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                val pillStyle = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium)
                val rendered = inlineRender(c, baseColor, accent, linkBg, pillStyle)
                PillText(
                    rendered = rendered,
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
