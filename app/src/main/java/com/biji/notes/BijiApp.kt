package com.biji.notes

import android.app.Application
import com.biji.notes.data.NotesDatabase
import com.biji.notes.data.NotesRepository

class BijiApp : Application() {
    val database: NotesDatabase by lazy { NotesDatabase.create(this) }
    val repository: NotesRepository by lazy { NotesRepository(database.noteDao()) }
}
