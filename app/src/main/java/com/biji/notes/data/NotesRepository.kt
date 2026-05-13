package com.biji.notes.data

import kotlinx.coroutines.flow.Flow

class NotesRepository(private val dao: NoteDao) {

    fun observeAll(): Flow<List<Note>> = dao.observeAll()

    suspend fun get(id: Long): Note? = dao.findById(id)

    suspend fun save(note: Note): Long {
        val stamped = note.copy(updatedAt = System.currentTimeMillis())
        return if (stamped.id == 0L) {
            dao.insert(stamped.copy(createdAt = System.currentTimeMillis()))
        } else {
            dao.update(stamped)
            stamped.id
        }
    }

    suspend fun delete(id: Long) = dao.deleteById(id)
}
