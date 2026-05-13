package com.biji.notes.memory

import com.biji.notes.data.ChatRepository
import com.biji.notes.data.Conversation
import com.biji.notes.data.Message
import com.biji.notes.data.MessageKind
import com.biji.notes.data.Role
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class MemoryHit(
    val message: Message,
    val conversation: Conversation,
    val score: Double
)

/**
 * Lightweight long-term-memory provider. Indexes every text message in the
 * database via BM25, then on each turn pulls the most relevant ones from
 * *other* conversations into a small "background context" prompt fragment.
 *
 * The index is rebuilt on demand (lazy + invalidated). Cheap because:
 *  - Tokenizer is O(n).
 *  - BM25 scoring is single-pass over docs.
 *  - We cap the corpus at 5,000 most-recent messages.
 */
class MemoryService(private val repo: ChatRepository) {

    private val mutex = Mutex()
    @Volatile private var index: Bm25Index<Long>? = null
    @Volatile private var corpusById: Map<Long, Message> = emptyMap()
    @Volatile private var convosById: Map<Long, Conversation> = emptyMap()
    @Volatile private var dirty = true

    fun invalidate() { dirty = true }

    private suspend fun rebuildIfNeeded() {
        if (!dirty && index != null) return
        mutex.withLock {
            if (!dirty && index != null) return
            val msgs = repo.getCorpus()
            corpusById = msgs.associateBy { it.id }
            convosById = msgs.map { it.conversationId }
                .distinct()
                .mapNotNull { id -> repo.getConversation(id)?.let { id to it } }
                .toMap()
            index = Bm25Index(msgs.map { it.id to it.content })
            dirty = false
        }
    }

    /** Return the top [k] most relevant past messages (from any conversation
     *  other than [excludeConvoId]) for the given query. */
    suspend fun retrieve(
        query: String,
        excludeConvoId: Long?,
        k: Int = 3,
        minScore: Double = 1.5
    ): List<MemoryHit> {
        if (query.isBlank()) return emptyList()
        rebuildIfNeeded()
        val idx = index ?: return emptyList()
        return idx.search(query, k * 4)
            .asSequence()
            .mapNotNull { (id, score) ->
                val msg = corpusById[id] ?: return@mapNotNull null
                if (msg.conversationId == excludeConvoId) return@mapNotNull null
                if (msg.role != Role.ASSISTANT && msg.role != Role.USER) return@mapNotNull null
                if (msg.kind != MessageKind.TEXT) return@mapNotNull null
                val convo = convosById[msg.conversationId] ?: return@mapNotNull null
                MemoryHit(msg, convo, score)
            }
            .filter { it.score >= minScore }
            .distinctBy { it.conversation.id }
            .take(k)
            .toList()
    }

    /** Build the system-prompt fragment that injects long-term memory hits.
     *  Returns null when there's nothing useful to inject. */
    fun memoryPrompt(hits: List<MemoryHit>): String? {
        if (hits.isEmpty()) return null
        val sb = StringBuilder()
        sb.appendLine("【长期记忆 · 来自历史对话的相关片段，仅供参考，不要复述原文】")
        hits.forEachIndexed { i, hit ->
            val who = if (hit.message.role == Role.USER) "用户" else "助手"
            val snippet = hit.message.content
                .replace('\n', ' ')
                .trim()
                .take(180)
            sb.appendLine("${i + 1}. [${hit.conversation.title}] $who: $snippet")
        }
        return sb.toString().trim()
    }
}
