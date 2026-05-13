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
    val model: String = "deepseek-chat"
)

object Role {
    const val USER = "user"
    const val ASSISTANT = "assistant"
    const val SYSTEM = "system"
    const val TOOL = "tool"
}

object MessageKind {
    /** Plain user/assistant/system text. */
    const val TEXT = "text"

    /** An assistant message whose only purpose is to carry tool_calls JSON. */
    const val TOOL_CALL = "tool_call"

    /** A tool-role message whose content is the tool's structured result. */
    const val TOOL_RESULT = "tool_result"
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

    /** [MessageKind] discriminator – defaults to TEXT for back-compat. */
    @ColumnInfo(name = "kind", defaultValue = MessageKind.TEXT)
    val kind: String = MessageKind.TEXT,

    /** Tool-call JSON (when [kind] == TOOL_CALL) or tool-result JSON
     *  (when [kind] == TOOL_RESULT). null for plain text messages. */
    @ColumnInfo(name = "tool_data")
    val toolData: String? = null,

    /** For tool messages, links back to the original tool_call id. */
    @ColumnInfo(name = "tool_call_id")
    val toolCallId: String? = null
)
