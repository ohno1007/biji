package com.biji.notes.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class Conversation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String = "新对话",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val model: String = "deepseek-chat",

    /** Rolling token total of the last API response for this conversation,
     *  i.e. how many tokens we just put through the model. Used to drive the
     *  context-usage ring and the auto-compaction trigger. */
    @ColumnInfo(name = "context_tokens", defaultValue = "0")
    val contextTokens: Long = 0,

    /** Per-conversation flag: explicitly request the model to reveal
     *  reasoning. For deepseek-reasoner this is always true (it can't be
     *  disabled). For deepseek-chat / proxy models, we add a system
     *  instruction. */
    @ColumnInfo(name = "thinking", defaultValue = "0")
    val thinking: Boolean = false
)

object Role {
    const val USER = "user"
    const val ASSISTANT = "assistant"
    const val SYSTEM = "system"
    const val TOOL = "tool"
}

object MessageKind {
    const val TEXT = "text"
    const val TOOL_CALL = "tool_call"
    const val TOOL_RESULT = "tool_result"
    /** Model-generated compaction of older messages, replacing them in the
     *  API request. Persisted as a system-role message so it counts toward
     *  context without polluting normal history rendering. */
    const val CONTEXT_SUMMARY = "context_summary"
}

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = Conversation::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("conversationId")]
)
data class Message(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val role: String,
    val content: String,
    @ColumnInfo(name = "reasoning") val reasoning: String? = null,
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "kind", defaultValue = MessageKind.TEXT)
    val kind: String = MessageKind.TEXT,

    @ColumnInfo(name = "tool_data")
    val toolData: String? = null,

    @ColumnInfo(name = "tool_call_id")
    val toolCallId: String? = null,

    /** True when this message has been compacted into a CONTEXT_SUMMARY and
     *  should be skipped when building API requests (still visible in UI). */
    @ColumnInfo(name = "archived", defaultValue = "0")
    val archived: Boolean = false
)
