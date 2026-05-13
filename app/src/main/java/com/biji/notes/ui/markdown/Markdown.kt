package com.biji.notes.ui.markdown

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import androidx.compose.foundation.background

private sealed interface Block {
    data class Heading(val level: Int, val text: String) : Block
    data class Paragraph(val text: String) : Block
    data class BulletItem(val text: String) : Block
    data class NumberedItem(val number: Int, val text: String) : Block
    data class Quote(val text: String) : Block
    data class CodeBlock(val lang: String, val code: String) : Block
    data object Divider : Block
    data object Blank : Block
}

private fun parseBlocks(source: String): List<Block> {
    val out = mutableListOf<Block>()
    val lines = source.replace("\r\n", "\n").split("\n")
    var i = 0
    var orderedCounter = 0
    while (i < lines.size) {
        val raw = lines[i]
        val line = raw.trimEnd()
        when {
            line.isBlank() -> {
                out += Block.Blank
                orderedCounter = 0
                i++
            }
            line.startsWith("```") -> {
                val lang = line.removePrefix("```").trim()
                val buf = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimEnd().startsWith("```")) {
                    buf.appendLine(lines[i])
                    i++
                }
                if (i < lines.size) i++
                out += Block.CodeBlock(lang, buf.toString().trimEnd('\n'))
                orderedCounter = 0
            }
            line.matches(Regex("^-{3,}$|^_{3,}$|^\\*{3,}$")) -> {
                out += Block.Divider
                i++
                orderedCounter = 0
            }
            line.startsWith("#") -> {
                val match = Regex("^(#{1,6})\\s+(.*)$").matchEntire(line)
                if (match != null) {
                    val (h, t) = match.destructured
                    out += Block.Heading(h.length, t.trim())
                } else {
                    out += Block.Paragraph(line)
                }
                i++
                orderedCounter = 0
            }
            line.trimStart().startsWith("> ") -> {
                out += Block.Quote(line.trimStart().removePrefix("> "))
                i++
                orderedCounter = 0
            }
            line.trimStart().let { it.startsWith("- ") || it.startsWith("* ") } -> {
                out += Block.BulletItem(line.trimStart().drop(2))
                i++
                orderedCounter = 0
            }
            Regex("^\\d+\\.\\s+.*").matches(line.trimStart()) -> {
                orderedCounter++
                out += Block.NumberedItem(orderedCounter, line.trimStart().substringAfter('.').trimStart())
                i++
            }
            else -> {
                out += Block.Paragraph(line)
                i++
                orderedCounter = 0
            }
        }
    }
    return out
}

private fun inline(source: String, baseColor: Color, accent: Color): AnnotatedString = buildAnnotatedString {
    var i = 0
    val s = source
    while (i < s.length) {
        when {
            // code span
            s[i] == '`' -> {
                val end = s.indexOf('`', i + 1)
                if (end == -1) { append(s[i]); i++ } else {
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = baseColor.copy(alpha = 0.10f),
                            color = baseColor.copy(alpha = 0.95f)
                        )
                    ) { append(s.substring(i + 1, end)) }
                    i = end + 1
                }
            }
            // bold **text**
            i + 1 < s.length && s[i] == '*' && s[i + 1] == '*' -> {
                val end = s.indexOf("**", i + 2)
                if (end == -1) { append(s[i]); i++ } else {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                        append(inline(s.substring(i + 2, end), baseColor, accent))
                    }
                    i = end + 2
                }
            }
            // italic *text*
            s[i] == '*' -> {
                val end = s.indexOf('*', i + 1)
                if (end == -1) { append(s[i]); i++ } else {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(inline(s.substring(i + 1, end), baseColor, accent))
                    }
                    i = end + 1
                }
            }
            // link [text](url)
            s[i] == '[' -> {
                val closeBracket = s.indexOf(']', i + 1)
                if (closeBracket != -1 && closeBracket + 1 < s.length && s[closeBracket + 1] == '(') {
                    val closeParen = s.indexOf(')', closeBracket + 2)
                    if (closeParen != -1) {
                        val text = s.substring(i + 1, closeBracket)
                        val url = s.substring(closeBracket + 2, closeParen)
                        pushStringAnnotation("URL", url)
                        withStyle(
                            SpanStyle(
                                color = accent,
                                textDecoration = TextDecoration.Underline
                            )
                        ) { append(text) }
                        pop()
                        i = closeParen + 1
                    } else { append(s[i]); i++ }
                } else { append(s[i]); i++ }
            }
            else -> { append(s[i]); i++ }
        }
    }
}

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
                is Block.Heading -> {
                    val style = when (b.level) {
                        1 -> MaterialTheme.typography.headlineLarge
                        2 -> MaterialTheme.typography.headlineMedium
                        3 -> MaterialTheme.typography.titleLarge
                        else -> MaterialTheme.typography.titleMedium
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(inline(b.text, baseColor, accent), style = style, color = baseColor)
                    Spacer(Modifier.height(4.dp))
                }
                is Block.Paragraph -> {
                    Text(
                        inline(b.text, baseColor, accent),
                        style = MaterialTheme.typography.bodyLarge,
                        color = baseColor,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
                is Block.BulletItem -> Row(verticalAlignment = Alignment.Top) {
                    Text("•", color = accent, modifier = Modifier.padding(end = 8.dp, top = 2.dp))
                    Text(inline(b.text, baseColor, accent), style = MaterialTheme.typography.bodyLarge, color = baseColor)
                }
                is Block.NumberedItem -> Row(verticalAlignment = Alignment.Top) {
                    Text("${b.number}.", color = accent, modifier = Modifier.padding(end = 8.dp, top = 2.dp))
                    Text(inline(b.text, baseColor, accent), style = MaterialTheme.typography.bodyLarge, color = baseColor)
                }
                is Block.Quote -> Row(Modifier.padding(vertical = 4.dp)) {
                    Box(
                        Modifier
                            .width(3.dp)
                            .height(22.dp)
                            .background(accent.copy(alpha = 0.6f), RoundedCornerShape(2.dp))
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        inline(b.text, baseColor, accent),
                        style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic),
                        color = baseColor.copy(alpha = 0.85f)
                    )
                }
                is Block.CodeBlock -> Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(baseColor.copy(alpha = 0.08f))
                        .padding(12.dp)
                ) {
                    Text(
                        b.code,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        color = baseColor
                    )
                }
                Block.Divider -> Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp)
                        .height(1.dp)
                        .background(baseColor.copy(alpha = 0.15f))
                )
                Block.Blank -> Spacer(Modifier.height(6.dp))
            }
        }
    }
}
