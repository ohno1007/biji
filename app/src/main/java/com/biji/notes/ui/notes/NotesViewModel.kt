package com.biji.notes.ui.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.biji.notes.data.Note
import com.biji.notes.data.NotesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class NotesUiState(
    val all: List<Note> = emptyList(),
    val visible: List<Note> = emptyList(),
    val query: String = ""
)

class NotesViewModel(private val repo: NotesRepository) : ViewModel() {

    private val query = MutableStateFlow("")

    val state: StateFlow<NotesUiState> = combine(repo.observeAll(), query) { list, q ->
        val filtered = if (q.isBlank()) list else list.filter { n ->
            n.title.contains(q, ignoreCase = true) ||
                n.content.contains(q, ignoreCase = true)
        }
        NotesUiState(all = list, visible = filtered, query = q)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotesUiState())

    fun setQuery(value: String) { query.value = value }

    suspend fun load(id: Long): Note? = repo.get(id)

    fun save(note: Note, onSaved: (Long) -> Unit = {}) {
        viewModelScope.launch {
            val id = repo.save(note)
            onSaved(id)
        }
    }

    fun delete(id: Long) {
        if (id <= 0L) return
        viewModelScope.launch { repo.delete(id) }
    }

    companion object {
        fun factory(repo: NotesRepository) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                NotesViewModel(repo) as T
        }
    }
}
