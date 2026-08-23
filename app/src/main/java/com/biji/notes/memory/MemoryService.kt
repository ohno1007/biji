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
    val score: Double,
    /** 命中的是**当前**会话里被压缩掉的那段，不是别的历史对话。 */
    val sameConversation: Boolean = false
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
                // 当前会话原则上排除（那些内容已经在请求前缀里了，再塞一遍是浪费），
                // 但**已归档的部分是例外** —— 它恰恰已经不在前缀里，压缩之后模型
                // 唯一的线索只剩那段五百字摘要。让它能被检索回来，压缩就从「丢了」
                // 变成「按需取回」。
                if (msg.conversationId == excludeConvoId && !msg.archived) return@mapNotNull null
                if (msg.role != Role.ASSISTANT && msg.role != Role.USER) return@mapNotNull null
                if (msg.kind != MessageKind.TEXT) return@mapNotNull null
                val convo = convosById[msg.conversationId] ?: return@mapNotNull null
                MemoryHit(msg, convo, score, msg.conversationId == excludeConvoId)
            }
            .filter { it.score >= minScore }
            // 原来是 distinctBy { conversation.id }，一个会话只留一条。放开归档
            // 命中之后这条规则会让「当前会话的三段历史」互相挤掉，只剩一段。
            // 改成每个会话最多两条，既不会被一个会话刷屏，也能带回足够上下文。
            .groupBy { it.conversation.id }
            .flatMap { (_, v) -> v.take(2) }
            .sortedByDescending { it.score }
            .take(k)
    }

    /** Build the system-prompt fragment that injects long-term memory hits.
     *  Returns null when there's nothing useful to inject. */
    fun memoryPrompt(hits: List<MemoryHit>): String? {
        if (hits.isEmpty()) return null
        val sb = StringBuilder()
        sb.appendLine("【相关片段，仅供参考，不要复述原文】")
        hits.forEachIndexed { i, hit ->
            val who = if (hit.message.role == Role.USER) "用户" else "助手"
            val snippet = hit.message.content
                .replace('\n', ' ')
                .trim()
                .take(180)
            // 同一个会话的命中要标成「本对话早期」而不是会话标题 —— 标成标题的话
            // 模型会以为那是另一场对话里说的，可能反过来跟用户确认「你之前在
            // 某某对话里提过…」，而其实就是这一场。
            val src = if (hit.sameConversation) "本对话早期" else hit.conversation.title
            sb.appendLine("${i + 1}. [$src] $who: $snippet")
        }
        return sb.toString().trim()
    }
}
