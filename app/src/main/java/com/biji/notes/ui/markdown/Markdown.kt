package com.biji.notes.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.ui.draw.rotate
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
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
private val LatexBlockOpen = Regex("^\\\\\\[\\s*$")
private val LatexBlockClose = Regex("^\\\\]\\s*$")
private val HrRegex = Regex("^(-{3,}|_{3,}|\\*{3,})\\s*$")
private val HeadingRegex = Regex("^(#{1,6})\\s+(.*)$")
private val OrderedRegex = Regex("^(\\d+)\\.\\s+(.*)$")
private val TaskRegex = Regex("^[-*+]\\s+\\[([ xX])]\\s+(.*)$")
private val BulletRegex = Regex("^[-*+]\\s+(.*)$")
private val TablePipeRow = Regex("^\\|.*\\|\\s*$")
private val TableSeparator = Regex("^\\|\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)+\\|\\s*$")

private fun indentDepth(line: String): Int {
    var i = 0; var count = 0
    while (i < line.length && line[i] == ' ') { i++; count++ }
    return count / 2
}

/**
 * 优先走 C++ 版（markdown_parser.cpp，与下面的 Kotlin 版逐行对齐、
 * 差分对拍过 36 万篇随机文档）。native 不可用 / 输入超限 / 返回结构
 * 不合法时整体降级到 [parseBlocks]。
 *
 * native 侧一个字符串都不跨 JNI —— 返回的全是原串上的 UTF-16 offset，
 * 这里按需 substring。
 */
private fun parseBlocksFast(source: String): List<Block> {
    if (!com.biji.notes.nativebridge.NativeGate.markdown) return parseBlocks(source)
    val nb = com.biji.notes.nativebridge.NativeMarkdown.parseBlocks(source)
        ?: return parseBlocks(source)
    val out = ArrayList<Block>(nb.count)
    for (i in 0 until nb.count) {
        out += when (nb.type(i)) {
            com.biji.notes.nativebridge.NativeMarkdown.TYPE_BLANK -> Block.Blank
            com.biji.notes.nativebridge.NativeMarkdown.TYPE_DIVIDER -> Block.Divider
            com.biji.notes.nativebridge.NativeMarkdown.TYPE_HEADING ->
                Block.Heading(nb.arg0(i), nb.inlineText(i))
            com.biji.notes.nativebridge.NativeMarkdown.TYPE_PARAGRAPH ->
                Block.Paragraph(nb.paragraph(i))
            com.biji.notes.nativebridge.NativeMarkdown.TYPE_BULLET ->
                Block.BulletItem(nb.arg0(i), nb.inlineText(i))
            com.biji.notes.nativebridge.NativeMarkdown.TYPE_TASK ->
                Block.TaskItem(nb.arg0(i), nb.inlineText(i), nb.checked(i))
            com.biji.notes.nativebridge.NativeMarkdown.TYPE_NUMBERED ->
                Block.NumberedItem(nb.arg0(i), nb.arg1(i), nb.inlineText(i))
            com.biji.notes.nativebridge.NativeMarkdown.TYPE_QUOTE ->
                Block.Quote(nb.quoteLines(i))
            com.biji.notes.nativebridge.NativeMarkdown.TYPE_CODE ->
                Block.CodeBlock(nb.lang(i), nb.code(i))
            com.biji.notes.nativebridge.NativeMarkdown.TYPE_MERMAID ->
                Block.Mermaid(nb.body(i))
            com.biji.notes.nativebridge.NativeMarkdown.TYPE_MATH ->
                Block.MathBlock(nb.body(i))
            com.biji.notes.nativebridge.NativeMarkdown.TYPE_TABLE ->
                Block.Table(nb.tableHeader(i), nb.tableRows(i))
            else -> return parseBlocks(source)
        }
    }
    return out
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
            orderedCounters.clear(); i++; continue
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
        // LaTeX-style block math: a line containing only `\[` opens, a
        // line containing only `\]` closes. This is what the deepseek
        // models actually emit for display math.
        if (LatexBlockOpen.matches(trimmed)) {
            val buf = StringBuilder(); i++
            while (i < lines.size && !LatexBlockClose.matches(lines[i].trimStart())) {
                buf.appendLine(lines[i]); i++
            }
            if (i < lines.size) i++
            out += Block.MathBlock(buf.toString().trimEnd('\n'))
            orderedCounters.clear(); continue
        }
        // Single-line `\[ … \]` shorthand — fall through to a paragraph
        // and let the inline math handler render it.
        val fence = FenceRegex.matchEntire(trimmed)
        if (fence != null) {
            val lang = fence.groupValues[1]
            val buf = StringBuilder(); i++
            while (i < lines.size && !FenceRegex.matches(lines[i].trimStart())) {
                buf.appendLine(lines[i]); i++
            }
            if (i < lines.size) i++
            val body = buf.toString().trimEnd('\n')
            val isFlowSyntax = body.contains("=>") && body.contains("->")
            out += when {
                lang.equals("mermaid", ignoreCase = true) -> Block.Mermaid(body)
                lang.equals("flow", ignoreCase = true) || lang.equals("flowchart", true) ->
                    Block.Mermaid(body)
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
// Inline parser — emits AnnotatedString with "LINK" annotations. The
// renderer paints rounded backgrounds behind each link annotation in
// drawBehind, so links wrap naturally across line breaks while still
// looking like real pills.
// =====================================================================

private val UrlRegex = Regex("https?://[\\w\\-./%?=&#:+~]+")
internal const val LINK_TAG = "LINK"

private fun inline(
    source: String,
    baseColor: Color,
    accent: Color
): AnnotatedString = runCatching { inlineImpl(source, baseColor, accent) }
    .getOrElse { AnnotatedString(source) }

private fun inlineImpl(
    source: String,
    baseColor: Color,
    accent: Color
): AnnotatedString = buildAnnotatedString {
    var i = 0
    val s = source
    while (i < s.length) {
        if (s[i] == '\n') { append('\n'); i++; continue }
        // LaTeX bracket math: `\(...\)` inline, `\[...\]` single-line
        // block-style inline (the multi-line variant becomes a Block
        // up in parseBlocks). These are what most modern LLMs emit
        // for math, in preference to dollar-sign syntax.
        if (i + 1 < s.length && s[i] == '\\' && (s[i + 1] == '(' || s[i + 1] == '[')) {
            val isBlock = s[i + 1] == '['
            val closer = if (isBlock) "\\]" else "\\)"
            val end = s.indexOf(closer, i + 2)
            if (end != -1 && end > i + 2) {
                val raw = s.substring(i + 2, end)
                val alpha = if (isBlock) 0.08f else 0.06f
                withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Serif,
                        fontStyle = FontStyle.Italic,
                        color = baseColor,
                        background = baseColor.copy(alpha = alpha)
                    )
                ) {
                    if (isBlock) append(' ')
                    append(latexToUnicode(raw))
                    if (isBlock) append(' ')
                }
                i = end + 2; continue
            }
        }
        // Inline math $$...$$ on a single line — common shorthand when
        // the assistant wraps a one-liner formula. Parsed before single $.
        if (i + 1 < s.length && s[i] == '$' && s[i + 1] == '$') {
            val end = s.indexOf("$$", i + 2)
            if (end != -1 && end > i + 2) {
                val raw = s.substring(i + 2, end)
                withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Serif,
                        fontStyle = FontStyle.Italic,
                        color = baseColor,
                        background = baseColor.copy(alpha = 0.08f)
                    )
                ) { append(' '); append(latexToUnicode(raw)); append(' ') }
                i = end + 2; continue
            }
        }
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
        if (i + 2 < s.length && s[i] == '*' && s[i + 1] == '*' && s[i + 2] == '*') {
            val end = s.indexOf("***", i + 3)
            if (end != -1) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) {
                    append(inline(s.substring(i + 3, end), baseColor, accent))
                }
                i = end + 3; continue
            }
        }
        if (i + 1 < s.length && s[i] == '*' && s[i + 1] == '*') {
            val end = s.indexOf("**", i + 2)
            if (end != -1) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(inline(s.substring(i + 2, end), baseColor, accent))
                }
                i = end + 2; continue
            }
        }
        if (s[i] == '*' || s[i] == '_') {
            val ch = s[i]
            val end = s.indexOf(ch, i + 1)
            if (end != -1 && end > i + 1) {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(inline(s.substring(i + 1, end), baseColor, accent))
                }
                i = end + 1; continue
            }
        }
        if (i + 1 < s.length && s[i] == '~' && s[i + 1] == '~') {
            val end = s.indexOf("~~", i + 2)
            if (end != -1) {
                withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                    append(inline(s.substring(i + 2, end), baseColor, accent))
                }
                i = end + 2; continue
            }
        }
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
        // Markdown link
        if (s[i] == '[') {
            val closeBracket = s.indexOf(']', i + 1)
            if (closeBracket != -1 && closeBracket + 1 < s.length && s[closeBracket + 1] == '(') {
                val closeParen = s.indexOf(')', closeBracket + 2)
                if (closeParen != -1) {
                    val text = s.substring(i + 1, closeBracket)
                    val url = s.substring(closeBracket + 2, closeParen)
                    val startIdx = length
                    withStyle(SpanStyle(color = accent, fontWeight = FontWeight.Medium)) {
                        append(text)
                    }
                    addStringAnnotation(LINK_TAG, url, startIdx, length)
                    i = closeParen + 1; continue
                }
            }
        }
        // Bare http(s) URL
        if ((i == 0 || !s[i - 1].isLetterOrDigit()) && s.startsWith("http", i)) {
            val m = UrlRegex.matchAt(s, i)
            if (m != null) {
                val startIdx = length
                withStyle(SpanStyle(color = accent, fontWeight = FontWeight.Medium)) {
                    append(m.value)
                }
                addStringAnnotation(LINK_TAG, m.value, startIdx, length)
                i += m.value.length; continue
            }
        }
        append(s[i]); i++
    }
}

// =====================================================================
// PillText — Text with custom rounded backgrounds drawn behind every
// LINK-tagged span, plus tap detection that maps offsets back to the
// underlying URL.
// =====================================================================

/** inline() 会跑一堆正则 + latexToUnicode，流式输出时每个 token 都
 *  会触发重组 —— 不缓存的话 CPU 直接烧起来。 */
@Composable
private fun rememberInline(text: String, baseColor: Color, accent: Color) =
    remember(text, baseColor, accent) { inline(text, baseColor, accent) }

@Composable
internal fun PillText(
    annotated: AnnotatedString,
    style: TextStyle,
    color: Color,
    linkBg: Color,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val density = LocalDensity.current
    val cornerPx = with(density) { 8.dp.toPx() }
    val horizontalPadPx = with(density) { 4.dp.toPx() }
    val verticalShrinkPx = with(density) { 2.dp.toPx() }

    val links = remember(annotated) {
        annotated.getStringAnnotations(LINK_TAG, 0, annotated.length)
    }

    Text(
        text = annotated,
        style = style,
        color = color,
        onTextLayout = { layout = it },
        modifier = modifier
            .drawBehind {
                val l = layout ?: return@drawBehind
                for (ann in links) {
                    drawLinkPills(
                        layout = l,
                        start = ann.start,
                        end = ann.end,
                        bg = linkBg,
                        corner = cornerPx,
                        horizontalPad = horizontalPadPx,
                        verticalShrink = verticalShrinkPx
                    )
                }
            }
            .pointerInput(annotated) {
                detectTapGestures { pos ->
                    val l = layout ?: return@detectTapGestures
                    val charIndex = l.getOffsetForPosition(pos)
                    val hit = links.firstOrNull { charIndex in it.start until it.end }
                    if (hit != null) onOpenUrl(hit.item)
                }
            }
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLinkPills(
    layout: TextLayoutResult,
    start: Int,
    end: Int,
    bg: Color,
    corner: Float,
    horizontalPad: Float,
    verticalShrink: Float
) {
    if (end <= start) return
    val first = layout.getLineForOffset(start)
    val last = layout.getLineForOffset(end - 1)
    for (line in first..last) {
        val lineStartChar = maxOf(start, layout.getLineStart(line))
        val lineEndChar = minOf(end, layout.getLineEnd(line, visibleEnd = true))
        if (lineEndChar <= lineStartChar) continue
        val leftPx = layout.getHorizontalPosition(lineStartChar, true)
        val rightPx = layout.getHorizontalPosition(lineEndChar, true)
        val topPx = layout.getLineTop(line)
        val bottomPx = layout.getLineBottom(line)
        drawRoundRect(
            color = bg,
            topLeft = Offset(
                x = leftPx - horizontalPad,
                y = topPx + verticalShrink
            ),
            size = Size(
                width = rightPx - leftPx + horizontalPad * 2,
                height = bottomPx - topPx - verticalShrink * 2
            ),
            cornerRadius = CornerRadius(corner, corner)
        )
    }
}

// =====================================================================
// Renderer
// =====================================================================

@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onOpenUrl: (String) -> Unit = {}
) {
    val baseColor = LocalContentColor.current
    val accent = MaterialTheme.colorScheme.primary
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    // Stronger, dark-mode-aware link background — Claude-style warm
    // pill in light, warm brown in dark. Visible at a glance.
    val linkBg = if (isDark) Color(0xFF3D3324) else Color(0xFFE8D9B5)
    // If parseBlocks throws on a malformed input (e.g. unclosed math
    // fence, weird Unicode), fall back to a single Paragraph instead
    // of letting the whole chat composition crash and become unusable
    // forever — reopening the chat would just hit the same exception.
    val blocks = remember(markdown) {
        runCatching { parseBlocksFast(markdown) }
            .getOrElse { listOf(Block.Paragraph(markdown)) }
    }

    Column(modifier.padding(contentPadding)) {
        for (b in blocks) {
            when (b) {
                is Block.Heading -> Heading(b.level, b.text, baseColor, accent, linkBg, onOpenUrl)
                is Block.Paragraph -> Paragraph(b.text, baseColor, accent, linkBg, onOpenUrl)
                is Block.BulletItem -> Bullet(b.depth, b.text, baseColor, accent, linkBg, onOpenUrl)
                is Block.NumberedItem ->
                    Numbered(b.depth, b.number, b.text, baseColor, accent, linkBg, onOpenUrl)
                is Block.TaskItem ->
                    TaskItem(b.depth, b.text, b.checked, baseColor, accent, linkBg, onOpenUrl)
                is Block.Quote -> Quote(b.lines, baseColor, accent, linkBg, onOpenUrl)
                is Block.CodeBlock -> CodeBlock(b.lang, b.code, baseColor, isDark)
                is Block.Mermaid -> MermaidBlock(b.source, baseColor, accent)
                is Block.MathBlock -> MathBlockBox(b.source, baseColor)
                is Block.Table -> TableBlock(b.header, b.rows, baseColor, accent, linkBg, onOpenUrl)
                Block.Divider -> SoftDivider(baseColor)
                Block.Blank -> Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun Paragraph(text: String, baseColor: Color, accent: Color, linkBg: Color, onOpenUrl: (String) -> Unit) {
    PillText(
        annotated = rememberInline(text, baseColor, accent),
        style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp),
        color = baseColor,
        linkBg = linkBg,
        onOpenUrl = onOpenUrl,
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
private fun Heading(
    level: Int, text: String,
    baseColor: Color, accent: Color, linkBg: Color, onOpenUrl: (String) -> Unit
) {
    val style = when (level) {
        1 -> MaterialTheme.typography.headlineMedium
        2 -> MaterialTheme.typography.headlineSmall
        3 -> MaterialTheme.typography.titleLarge
        4 -> MaterialTheme.typography.titleMedium
        5 -> MaterialTheme.typography.titleSmall
        else -> MaterialTheme.typography.labelLarge
    }
    Spacer(Modifier.height(if (level <= 2) 10.dp else 6.dp))
    PillText(
        annotated = rememberInline(text, baseColor, accent),
        style = style.copy(fontWeight = FontWeight.Bold),
        color = baseColor,
        linkBg = linkBg,
        onOpenUrl = onOpenUrl
    )
    Spacer(Modifier.height(if (level <= 2) 4.dp else 2.dp))
}

@Composable
private fun Bullet(
    depth: Int, text: String,
    baseColor: Color, accent: Color, linkBg: Color, onOpenUrl: (String) -> Unit
) {
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
            annotated = rememberInline(text, baseColor, accent),
            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp),
            color = baseColor,
            linkBg = linkBg,
            onOpenUrl = onOpenUrl
        )
    }
}

@Composable
private fun Numbered(
    depth: Int, number: Int, text: String,
    baseColor: Color, accent: Color, linkBg: Color, onOpenUrl: (String) -> Unit
) {
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
            annotated = rememberInline(text, baseColor, accent),
            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp),
            color = baseColor,
            linkBg = linkBg,
            onOpenUrl = onOpenUrl
        )
    }
}

@Composable
private fun TaskItem(
    depth: Int, text: String, checked: Boolean,
    baseColor: Color, accent: Color, linkBg: Color, onOpenUrl: (String) -> Unit
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
        PillText(
            annotated = rememberInline(text, baseColor, accent),
            style = MaterialTheme.typography.bodyLarge.copy(
                textDecoration = if (checked) TextDecoration.LineThrough else null
            ),
            color = if (checked) baseColor.copy(alpha = 0.55f) else baseColor,
            linkBg = linkBg,
            onOpenUrl = onOpenUrl
        )
    }
}

@Composable
private fun Quote(
    lines: List<String>,
    baseColor: Color, accent: Color, linkBg: Color, onOpenUrl: (String) -> Unit
) {
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
                PillText(
                    annotated = rememberInline(l, baseColor, accent),
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontStyle = FontStyle.Italic,
                        lineHeight = 24.sp
                    ),
                    color = baseColor.copy(alpha = 0.78f),
                    linkBg = linkBg,
                    onOpenUrl = onOpenUrl
                )
            }
        }
    }
}

@Composable
private fun CodeBlock(lang: String, code: String, baseColor: Color, isDark: Boolean) {
    val cs = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    // 这几个 UI 状态原来拿整段 code 当 remember 的 key。流式输出时
    // code 每次 flush 都在变长，状态就被反复重建 —— 超过 12 行的代码
    // 块每秒被强制折叠十来次，用户根本没法看着它写完。改用「语言 +
    // 前 64 个字符」当 key：追加内容时它不变，不同代码块之间又足够
    // 区分，于是流式期间折叠/展开状态稳定保留。
    val blockKey = remember(lang, code.take(64)) { lang + " " + code.take(64) }
    var copied by remember(blockKey) { mutableStateOf(false) }
    // 行数要跟着内容走：短块写着写着变长了，isLong 得能翻成 true。
    val isLong = remember(code) { code.count { it == '\n' } >= 12 }
    // 初值只在 blockKey 变化时求一次 —— 短块流式写长之后仍然保持
    // 展开，不会在用户眼皮底下自己收起来。
    var expanded by remember(blockKey) { mutableStateOf(!isLong) }
    var previewing by remember(blockKey) { mutableStateOf(false) }
    val langLower = lang.lowercase()
    val canPreview = langLower in setOf("html", "htm", "xml", "svg")
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
                .bouncyClickable(pressedScale = 0.995f) { expanded = !expanded }
                .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Chevron rotates between collapsed (→) and expanded (↓).
            val chevronRotation by androidx.compose.animation.core.animateFloatAsState(
                targetValue = if (expanded) 90f else 0f,
                animationSpec = androidx.compose.animation.core.spring(
                    dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy
                ),
                label = "codeChev"
            )
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = if (expanded) "折叠" else "展开",
                modifier = Modifier
                    .size(14.dp)
                    .rotate(chevronRotation),
                tint = baseColor.copy(alpha = 0.55f)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                lang.ifBlank { "code" } + if (!expanded) "  ·  ${code.count { it == '\n' } + 1} 行" else "",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = baseColor.copy(alpha = 0.55f),
                modifier = Modifier.weight(1f)
            )
            if (canPreview) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .bouncyClickable(pressedScale = 0.96f) { previewing = true }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.PlayArrow,
                        contentDescription = "预览",
                        modifier = Modifier.size(12.dp),
                        tint = cs.primary
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "预览",
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.primary,
                        fontWeight = FontWeight.Medium
                    )
                }
                Spacer(Modifier.width(4.dp))
            }
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
        androidx.compose.animation.AnimatedVisibility(visible = expanded) {
            Column {
                HorizontalDivider(thickness = 0.5.dp, color = borderColor)
                val hScroll = rememberScrollState()
                Box(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(hScroll)
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Text(
                        remember(code, lang, isDark) { SyntaxHighlight.colorize(code, lang, isDark) },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        color = baseColor
                    )
                }
            }
        }
    }
    if (previewing) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { previewing = false },
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnClickOutside = true
            )
        ) {
            Box(
                Modifier
                    .fillMaxWidth(0.95f)
                    .fillMaxHeight(0.85f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(cs.surface)
            ) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "网页预览",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = cs.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .bouncyClickable(pressedScale = 0.9f) { previewing = false },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = "关闭",
                                modifier = Modifier.size(18.dp),
                                tint = cs.onSurfaceVariant
                            )
                        }
                    }
                    HorizontalDivider(thickness = 0.5.dp, color = cs.outlineVariant)
                    androidx.compose.ui.viewinterop.AndroidView(
                        factory = { ctx ->
                            android.webkit.WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                            }
                        },
                        update = { wv ->
                            wv.loadDataWithBaseURL(
                                null, code, "text/html", "UTF-8", null
                            )
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

@Composable
private fun MathBlockBox(source: String, baseColor: Color) {
    val text = remember(source) { latexToUnicode(source).trim() }
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
    val cs = MaterialTheme.colorScheme
    val isDark = cs.background.luminance() < 0.5f
    val shape: RoundedCornerShape = when (node.type) {
        "start", "end" -> RoundedCornerShape(50)
        "condition" -> RoundedCornerShape(4.dp)
        else -> RoundedCornerShape(10.dp)
    }
    val bg = when (node.type) {
        "start" -> accent.copy(alpha = 0.18f)
        "end" -> accent.copy(alpha = 0.10f)
        "condition" ->
            if (isDark) Color(0xFF55481E).copy(alpha = 0.85f)
            else Color(0xFFFFE08A).copy(alpha = 0.55f)
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
    val type: String,
    val outEdgeLabel: String?
)

private val MermaidEdgeRegex = Regex(
    "([A-Za-z0-9_]+)(?:\\[([^\\]]+)])?\\s*-->(?:\\|([^|]+)\\|)?\\s*([A-Za-z0-9_]+)(?:\\[([^\\]]+)])?"
)
private val FlowDefRegex = Regex("^([A-Za-z0-9_]+)\\s*=>\\s*([A-Za-z]+)\\s*:\\s*(.+)$")

private fun parseFlow(source: String): List<FlowNode> {
    val labels = linkedMapOf<String, String>()
    val types = mutableMapOf<String, String>()
    val edges = mutableListOf<Triple<String, String, String?>>()
    for (raw in source.lines()) {
        val line = raw.trim()
        if (line.isBlank()) continue
        if (line.startsWith("flowchart") || line.startsWith("graph")) continue
        val def = FlowDefRegex.matchEntire(line)
        if (def != null) {
            val id = def.groupValues[1]; val type = def.groupValues[2].lowercase()
            val label = def.groupValues[3].trim()
            labels[id] = label; types[id] = type; continue
        }
        if (line.contains("->") && !line.contains("-->")) {
            val parts = line.split("->").map { it.trim() }
            for (idx in parts.indices) {
                if (idx == parts.lastIndex) break
                val (fromId, edgeLabel) = parseBranchPart(parts[idx])
                val (toId, _) = parseBranchPart(parts[idx + 1])
                if (fromId.isNotBlank() && toId.isNotBlank()) {
                    labels.putIfAbsent(fromId, fromId)
                    labels.putIfAbsent(toId, toId)
                    edges += Triple(fromId, toId, edgeLabel)
                }
            }
            continue
        }
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

private fun parseBranchPart(part: String): Pair<String, String?> {
    val m = Regex("^([A-Za-z0-9_]+)(?:\\(([^)]+)\\))?$").matchEntire(part)
    return if (m != null) m.groupValues[1] to m.groupValues[2].ifBlank { null } else part to null
}

@Composable
private fun TableBlock(
    header: List<String>,
    rows: List<List<String>>,
    baseColor: Color,
    accent: Color,
    linkBg: Color,
    onOpenUrl: (String) -> Unit
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
            TableRow(header.padEnd(cols), baseColor, accent, linkBg, onOpenUrl,
                isHeader = true, lineColor = lineColor)
        }
        rows.forEach { r ->
            HorizontalDivider(thickness = 0.5.dp, color = lineColor)
            TableRow(r.padEnd(cols), baseColor, accent, linkBg, onOpenUrl,
                isHeader = false, lineColor = lineColor)
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
    onOpenUrl: (String) -> Unit,
    isHeader: Boolean,
    lineColor: Color
) {
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
                PillText(
                    annotated = rememberInline(c, baseColor, accent),
                    style = if (isHeader)
                        MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                    else
                        MaterialTheme.typography.bodyMedium,
                    color = baseColor,
                    linkBg = linkBg,
                    onOpenUrl = onOpenUrl
                )
            }
        }
    }
}
