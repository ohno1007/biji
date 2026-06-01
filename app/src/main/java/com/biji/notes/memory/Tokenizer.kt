package com.biji.notes.memory

/**
 * Crude bigram-and-word tokenizer that copes with Chinese without needing
 * a real segmenter:
 *  - Runs of CJK ideographs collapse to 2-character shingles (车厢 ⇒ ["车厢"]).
 *  - Runs of ASCII letters / digits become lowercase tokens.
 *  - Everything else is whitespace.
 *
 * Good enough for BM25 retrieval over a few thousand messages.
 */
object Tokenizer {

    fun tokenize(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val out = ArrayList<String>(text.length / 2 + 4)
        var i = 0
        val s = text.lowercase()
        while (i < s.length) {
            val c = s[i]
            when {
                c.isWhitespace() -> i++
                isCjk(c) -> {
                    var j = i
                    while (j < s.length && isCjk(s[j])) j++
                    val run = s.substring(i, j)
                    if (run.length == 1) {
                        out += run
                    } else {
                        for (k in 0..run.length - 2) out += run.substring(k, k + 2)
                    }
                    i = j
                }
                c.isLetterOrDigit() -> {
                    var j = i
                    while (j < s.length && s[j].isLetterOrDigit() && !isCjk(s[j])) j++
                    out += s.substring(i, j)
                    i = j
                }
                else -> i++
            }
        }
        return out
    }

    private fun isCjk(c: Char): Boolean {
        val code = c.code
        return (code in 0x4E00..0x9FFF) ||   // CJK Unified Ideographs
            (code in 0x3400..0x4DBF) ||   // Extension A
            (code in 0x3000..0x303F) ||   // Punctuation? ignore
            false
    }
}
