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
import com.biji.notes.sandbox.BijiBootstrap
import com.biji.notes.sandbox.LocalSandbox
import kotlinx.coroutines.launch

class BijiApp : Application() {

    val database: ChatDatabase by lazy { ChatDatabase.create(this) }
    val chatRepository: ChatRepository by lazy { ChatRepository(database.chatDao()) }
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }
    val deepSeekClient: DeepSeekClient by lazy { DeepSeekClient() }
    val webSearchService: WebSearchService by lazy { WebSearchService() }
    val bootstrap: BijiBootstrap by lazy { BijiBootstrap(this) }
    val pkg: com.biji.notes.sandbox.BijiPkg by lazy {
        com.biji.notes.sandbox.BijiPkg(this, bootstrap)
    }
    val localSandbox: LocalSandbox by lazy { LocalSandbox(this, bootstrap) }

    /**
     * 每个会话一个本地容器（持久 shell + 后台任务 + 可重置工作区）。
     *
     * 挂在 Application 上而不是某个 Compose 树里 —— 用户离开终端页时
     * 容器不能跟着没，AI 那边还拿着它在编译东西。
     */
    val aiContainers: com.biji.notes.sandbox.AiContainerManager by lazy {
        com.biji.notes.sandbox.AiContainerManager(
            rootProvider = localSandbox::projectRoot,
            binDir = bootstrap.binDir,
            execBase = java.io.File(filesDir, "exec")
        )
    }

    val toolExecutor: ToolExecutor by lazy {
        ToolExecutor(webSearchService, localSandbox, pkg, aiContainers)
    }
    val memoryService: MemoryService by lazy { MemoryService(chatRepository) }
    val chatNotifier: ChatNotifier by lazy { ChatNotifier(this) }
    val voiceRecognizer: com.biji.notes.voice.VoiceRecognizer by lazy {
        com.biji.notes.voice.VoiceRecognizer(this)
    }

    private val appScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )

    @Volatile private var foreground: Boolean = false
    fun isForeground(): Boolean = foreground

    override fun onCreate() {
        super.onCreate()
        // Capture any uncaught exception (compose, coroutines, JNI) to a
        // file the user can view from Settings. We don't suppress the
        // crash itself — the OS still kills the process normally — but
        // the trace survives across the relaunch.
        CrashHandler.install(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { foreground = true }
            override fun onStop(owner: LifecycleOwner) {
                foreground = false
                // 回后台时收一遍闲置容器：长驻 sh 不费 CPU，但每个都占
                // 一份内存 + 两个读取协程。有后台任务的容器不会被动。
                appScope.launch { runCatching { aiContainers.reapIdle() } }
            }
        })
    }
}
