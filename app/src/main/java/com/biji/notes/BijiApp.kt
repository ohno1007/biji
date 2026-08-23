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

    /**
     * 工具链装配器。进程级单例（[com.biji.notes.sandbox.ToolchainInstaller.get]），
     * 设置页 / ToolExecutor / 这里的自动 gc 拿的都是同一个实例 —— 不共享的话
     * 安装进度和账本会变成两份，UI 上表现为「AI 说装完了但设置页里没有」。
     */
    val toolchainInstaller: com.biji.notes.sandbox.ToolchainInstaller by lazy {
        com.biji.notes.sandbox.ToolchainInstaller.get(this, bootstrap)
    }

    val toolExecutor: ToolExecutor by lazy {
        ToolExecutor(
            webSearchService, localSandbox, pkg, aiContainers,
            toolchainInstaller,
            // terminal_snapshot 读的是**用户自己那块屏幕**，所以必须拿到终端 UI
            // 正在用的那一张会话表。这里走 TerminalRuntime.manager（进程级单例，
            // 和 TerminalScreen 走的是同一个入口）而不是在这儿另 new 一个：
            // 各建一张表的话工具会永远回「这个笔记下还没有打开过终端」，而且
            // 不抛任何异常，是个纯静默的错。
            //
            // 传 lambda 不传实例：ToolExecutor 在 Application 早期就构造，直接
            // 求值等于顺手把终端子系统（TerminfoDb、ContainerLayout）也拉起来，
            // 而绝大多数会话根本不开终端。
            terminals = {
                com.biji.notes.ui.terminal.TerminalRuntime.manager(this, localSandbox, bootstrap)
            }
        )
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
                sweepToolchain()
            }
        })
        // 冷启动扫一次：装到一半被杀（OOM / 用户划掉）留下的 dl-*.part 和
        // stage-* 是几百 MB 级的，而进程已经死了，没有任何别的路径会回收它们。
        sweepToolchain(caches = true)
    }

    // ------------------------------------------------------------------
    // 工具链残留回收
    // ------------------------------------------------------------------

    /** 上次跑 gc 的时刻。0 = 还没跑过。 */
    @Volatile private var lastToolchainGc = 0L

    /**
     * 清工具链的下载残留（`dl-*.part` / `stage-*` / 悬空软链）。
     *
     * 在这儿挂钩是因为 [com.biji.notes.sandbox.ToolchainInstaller.gc] 原来**只有
     * 模型主动调 `toolchain_manage action=gc` 这一个入口** —— 也就是说，除非 AI
     * 恰好想起来清一次，安装中途被杀留下的半个 zig（两三百 MB）会永远躺在
     * 私有目录里，用户在系统设置里只看得到「应用占用 1.2 GB」。
     *
     * 两个触发点：冷启动一次（收上一次运行的残骸），回后台一次（收本次运行的）。
     * 节流到 6 小时一次 —— 反复按 Home 不该反复扫盘，而残留是按「安装被中断」
     * 这种低频事件产生的，扫得再勤也没有额外收益。
     *
     * 安全性由 gc() 自己保证：它开头就检查 `installing`，有安装在跑就整个跳过，
     * 不会把正在写的临时文件当成残留删掉。
     */
    private fun sweepToolchain(caches: Boolean = false) {
        val now = System.currentTimeMillis()
        val last = lastToolchainGc
        if (last != 0L && now - last < TOOLCHAIN_GC_INTERVAL_MS) return
        lastToolchainGc = now
        appScope.launch {
            runCatching { toolchainInstaller.gc() }
            // 编译缓存（zig / go 的 cache 目录，几百 MB 起步）**只在冷启动清**。
            // 它和 gc 清的残骸不是一类东西：缓存是活数据，而且此刻可能正有
            // container_exec 在跑 zig build 读着它 —— gc 自己的 installing 闸只
            // 挡工具链安装，挡不住用户的构建。冷启动那一刻进程刚起来、容器任务
            // 随上个进程一起死了，是唯一能确定没人在读的时机。
            if (caches) runCatching { toolchainInstaller.gcCaches() }
        }
    }

    private companion object {
        /** 工具链 gc 的节流间隔。见 [sweepToolchain]。 */
        const val TOOLCHAIN_GC_INTERVAL_MS = 6L * 60 * 60 * 1000
    }
}
