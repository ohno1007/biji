package com.biji.notes.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal enum class DiffType { COMMON, ADDED, REMOVED }
internal data class DiffLine(val type: DiffType, val text: String)

/**
 * Simple line-based LCS diff. Good enough for showing what the AI
 * changed in a single file — not trying to compete with `git diff`'s
 * Myers refinement, just produce a readable +/- view.
 */
internal fun lineDiff(before: String, after: String): List<DiffLine> {
    val a = before.split("\n")
    val b = after.split("\n")
    val n = a.size
    val m = b.size
    // DP table of LCS lengths.
    val dp = Array(n + 1) { IntArray(m + 1) }
    for (i in n - 1 downTo 0) {
        for (j in m - 1 downTo 0) {
            dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1
            else maxOf(dp[i + 1][j], dp[i][j + 1])
        }
    }
    val out = mutableListOf<DiffLine>()
    var i = 0
    var j = 0
    while (i < n && j < m) {
        when {
            a[i] == b[j] -> {
                out += DiffLine(DiffType.COMMON, a[i]); i++; j++
            }
            dp[i + 1][j] >= dp[i][j + 1] -> {
                out += DiffLine(DiffType.REMOVED, a[i]); i++
            }
            else -> {
                out += DiffLine(DiffType.ADDED, b[j]); j++
            }
        }
    }
    while (i < n) { out += DiffLine(DiffType.REMOVED, a[i]); i++ }
    while (j < m) { out += DiffLine(DiffType.ADDED, b[j]); j++ }
    return out
}

@Composable
internal fun DiffView(
    before: String,
    after: String,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    val isDark = cs.background.luminance() < 0.5f
    val addedBg = if (isDark) Color(0xFF1B3A23) else Color(0xFFD7F4DE)
    val addedFg = if (isDark) Color(0xFF7EE2A0) else Color(0xFF1A6F32)
    val removedBg = if (isDark) Color(0xFF4A1F22) else Color(0xFFFAD2D5)
    val removedFg = if (isDark) Color(0xFFE07C84) else Color(0xFFB42E3F)

    val diff = remember(before, after) { lineDiff(before, after) }
    val hScroll = rememberScrollState()

    Column(modifier.fillMaxWidth()) {
        // Summary header
        val added = diff.count { it.type == DiffType.ADDED }
        val removed = diff.count { it.type == DiffType.REMOVED }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                "+$added 行",
                color = addedFg,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.labelLarge
            )
            Box(Modifier.width(12.dp))
            Text(
                "-$removed 行",
                color = removedFg,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.labelLarge
            )
        }
        LazyColumn(Modifier.fillMaxWidth().horizontalScroll(hScroll)) {
            items(diff.size, key = { it }) { idx ->
                val d = diff[idx]
                val (bg, fg, prefix) = when (d.type) {
                    DiffType.COMMON -> Triple(Color.Transparent, cs.onSurface, "  ")
                    DiffType.ADDED -> Triple(addedBg, addedFg, "+ ")
                    DiffType.REMOVED -> Triple(removedBg, removedFg, "- ")
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(bg)
                        .padding(horizontal = 12.dp, vertical = 1.dp)
                ) {
                    Text(
                        prefix + d.text,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = if (d.type == DiffType.COMMON) cs.onSurface else fg
                    )
                }
            }
        }
    }
}
