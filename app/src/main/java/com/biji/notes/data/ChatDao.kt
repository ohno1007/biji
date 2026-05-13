package com.biji.notes.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun observeConversations(): Flow<List<Conversation>>

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    suspend fun getConversation(id: Long): Conversation?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversation(c: Conversation): Long

    @Update
    suspend fun updateConversation(c: Conversation)

    @Query("UPDATE conversations SET title = :title, updatedAt = :now WHERE id = :id")
    suspend fun renameConversation(id: Long, title: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE conversations SET updatedAt = :now WHERE id = :id")
    suspend fun touchConversation(id: Long, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversation(id: Long)

    @Query("SELECT * FROM messages WHERE conversationId = :id ORDER BY createdAt ASC, id ASC")
    fun observeMessages(id: Long): Flow<List<Message>>

    @Query("SELECT * FROM messages WHERE conversationId = :id ORDER BY createdAt ASC, id ASC")
    suspend fun getMessages(id: Long): List<Message>

    @Insert
    suspend fun insertMessage(m: Message): Long

    @Update
    suspend fun updateMessage(m: Message)

    @Query("UPDATE messages SET content = :content, reasoning = :reasoning WHERE id = :id")
    suspend fun updateMessageBody(id: Long, content: String, reasoning: String?)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteMessage(id: Long)

    @Query("SELECT * FROM messages ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getRecentForIndex(limit: Int = 5000): List<Message>

    @Query("UPDATE messages SET tool_data = :data WHERE id = :id")
    suspend fun setToolData(id: Long, data: String?)
}
