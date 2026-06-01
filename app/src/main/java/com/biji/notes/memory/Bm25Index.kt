package com.biji.notes.memory

import kotlin.math.ln

/**
 * Tiny in-memory BM25 ranker. Holds an inverted index plus per-document
 * stats, rebuilt cheaply from the message corpus (we rebuild whenever the
 * conversation set changes; with a few thousand docs this is microseconds).
 */
class Bm25Index<T>(
    docs: List<Pair<T, String>>,
    private val k1: Double = 1.5,
    private val b: Double = 0.75
) {
    private data class Doc<T>(val key: T, val tokens: List<String>, val tf: Map<String, Int>)

    private val documents: List<Doc<T>>
    private val df: Map<String, Int>
    private val avgdl: Double
    private val n: Int

    init {
        val parsed = docs.map { (k, text) ->
            val toks = Tokenizer.tokenize(text)
            Doc(k, toks, toks.groupingBy { it }.eachCount())
        }
        documents = parsed
        n = parsed.size
        avgdl = if (n == 0) 1.0 else parsed.sumOf { it.tokens.size }.toDouble() / n
        val dfMap = HashMap<String, Int>()
        for (d in parsed) for (t in d.tf.keys) dfMap.merge(t, 1) { old, _ -> old + 1 }
        df = dfMap
    }

    fun search(query: String, k: Int): List<Pair<T, Double>> {
        if (n == 0) return emptyList()
        val qTokens = Tokenizer.tokenize(query).toHashSet()
        if (qTokens.isEmpty()) return emptyList()

        val scored = documents.map { d ->
            val len = d.tokens.size.coerceAtLeast(1)
            var s = 0.0
            for (t in qTokens) {
                val tf = d.tf[t] ?: continue
                val dfT = df[t] ?: continue
                val idf = ln((n - dfT + 0.5) / (dfT + 0.5) + 1.0)
                val num = tf * (k1 + 1)
                val den = tf + k1 * (1 - b + b * len / avgdl)
                s += idf * (num / den)
            }
            d.key to s
        }
        return scored
            .filter { it.second > 0.0 }
            .sortedByDescending { it.second }
            .take(k)
    }
}
