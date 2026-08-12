package com.biji.notes.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
 * Whether [index]'s common line lies in the "neighborhood" of an
 * actual addition / removal (within ±2 lines). Used to tint adjacent
 * context with a faint green so reviewers can see the touched block.
 */
internal fun nearChange(lines: List<DiffLine>, index: Int, window: Int = 2): Boolean {
    if (lines[index].type != DiffType.COMMON) return false
    val lo = (index - window).coerceAtLeast(0)
    val hi = (index + window).coerceAtMost(lines.size - 1)
    for (i in lo..hi) {
        val t = lines[i].type
        if (t == DiffType.ADDED || t == DiffType.REMOVED) return true
    }
    return false
}

/**
 * Simple line-based LCS diff. Good enough for showing what the AI
 * changed in a single file — not trying to compete with `git diff`'s
 * Myers refinement, just produce a readable +/- view.
 */
internal fun lineDiff(before: String, after: String): List<DiffLine> {
    // C++ 版走 Myers O(ND)。下面这套 Kotlin 实现是 O(n*m) DP —— 4000 行
    // 就要 64 MB 的 dp 数组、约 390 ms（实测外推），再大直接 OOM。
    if (com.biji.notes.nativebridge.NativeGate.text) {
        com.biji.notes.nativebridge.NativeText.lineDiff(before, after)?.let { nd ->
            val out = ArrayList<DiffLine>(nd.size)
            for (i in 0 until nd.size) {
                val t = when (nd.typeAt(i)) {
                    com.biji.notes.nativebridge.NativeText.OP_ADDED -> DiffType.ADDED
                    com.biji.notes.nativebridge.NativeText.OP_REMOVED -> DiffType.REMOVED
                    else -> DiffType.COMMON
                }
                out.add(DiffLine(t, nd.textAt(i)))
            }
            return out
        }
    }
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
    // Faint green tint for context lines (COMMON) that sit ±2 rows from
    // an actual change — surfaces the "touched neighborhood" without
    // looking like the line was itself modified.
    val nearBg = if (isDark) Color(0xFF142A1A).copy(alpha = 0.55f)
    else Color(0xFFEEFAF1)

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
        // Plain Column (not Lazy) — DiffView is rendered inside chat
        // LazyColumn items which give children infinite vertical
        // constraints, illegal for nested LazyColumns. The diffs we
        // get are typically small (chat tool-results, not full
        // codebases) so non-lazy is fine.
        Column(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(hScroll)
        ) {
            diff.take(2000).forEachIndexed { idx, d ->
                val isNear = d.type == DiffType.COMMON && nearChange(diff, idx)
                val (bg, fg, prefix) = when (d.type) {
                    DiffType.COMMON ->
                        Triple(if (isNear) nearBg else Color.Transparent, cs.onSurface, "  ")
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
            if (diff.size > 2000) {
                Text(
                    "（diff 超过 2000 行，已截断）",
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
}
