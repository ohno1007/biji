package com.biji.notes

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.biji.notes.data.ChatDatabase
import com.biji.notes.data.ChatRepository
import com.biji.notes.data.SettingsRepository
import com.biji.notes.memory.MemoryService
import com.biji.notes.net.DeepSeekClient
import com.biji.notes.net.ToolExecutor
import com.biji.notes.net.WebSearchService
import com.biji.notes.notif.ChatNotifier

class BijiApp : Application() {

    val database: ChatDatabase by lazy { ChatDatabase.create(this) }
    val chatRepository: ChatRepository by lazy { ChatRepository(database.chatDao()) }
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }
    val deepSeekClient: DeepSeekClient by lazy { DeepSeekClient() }
    val webSearchService: WebSearchService by lazy { WebSearchService() }
    val toolExecutor: ToolExecutor by lazy { ToolExecutor(webSearchService) }
    val memoryService: MemoryService by lazy { MemoryService(chatRepository) }
    val chatNotifier: ChatNotifier by lazy { ChatNotifier(this) }

    @Volatile private var foreground: Boolean = false
    fun isForeground(): Boolean = foreground

    override fun onCreate() {
        super.onCreate()
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { foreground = true }
            override fun onStop(owner: LifecycleOwner) { foreground = false }
        })
    }
}
