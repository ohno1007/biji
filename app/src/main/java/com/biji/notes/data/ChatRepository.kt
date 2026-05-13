package com.biji.notes.data

import kotlinx.coroutines.flow.Flow

class ChatRepository(private val dao: ChatDao) {

    fun observeConversations(): Flow<List<Conversation>> = dao.observeConversations()
    fun observeMessages(convoId: Long): Flow<List<Message>> = dao.observeMessages(convoId)

    suspend fun getConversation(id: Long): Conversation? = dao.getConversation(id)
    suspend fun getMessages(id: Long): List<Message> = dao.getMessages(id)

    suspend fun createConversation(title: String = "新对话", model: String): Long {
        val now = System.currentTimeMillis()
        return dao.insertConversation(
            Conversation(title = title, createdAt = now, updatedAt = now, model = model)
        )
    }

    suspend fun rename(id: Long, title: String) = dao.renameConversation(id, title)
    suspend fun touch(id: Long) = dao.touchConversation(id)
    suspend fun deleteConversation(id: Long) = dao.deleteConversation(id)

    suspend fun addMessage(
        convoId: Long,
        role: String,
        content: String,
        reasoning: String? = null
    ): Long {
        val id = dao.insertMessage(
            Message(
                conversationId = convoId,
                role = role,
                content = content,
                reasoning = reasoning
            )
        )
        dao.touchConversation(convoId)
        return id
    }

    suspend fun updateAssistantStream(id: Long, content: String, reasoning: String?) =
        dao.updateMessageBody(id, content, reasoning)
}
