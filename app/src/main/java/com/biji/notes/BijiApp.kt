package com.biji.notes

import android.app.Application
import com.biji.notes.data.ChatDatabase
import com.biji.notes.data.ChatRepository
import com.biji.notes.data.SettingsRepository
import com.biji.notes.net.DeepSeekClient

class BijiApp : Application() {
    val database: ChatDatabase by lazy { ChatDatabase.create(this) }
    val chatRepository: ChatRepository by lazy { ChatRepository(database.chatDao()) }
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }
    val deepSeekClient: DeepSeekClient by lazy { DeepSeekClient() }
}
