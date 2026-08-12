package com.biji.notes.sandbox

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 「一个会话一个容器」的注册表。
 *
 * 必须挂在 **app 作用域**（BijiApp 的 by lazy）上，不能像现在的
 * `InteractiveShell` 那样绑在 Compose 的 `remember` 里 —— 那样用户一
 * 离开终端页 `onDispose` 就 shutdown，AI 手里的容器跟着蒸发，而且 AI
 * 侧根本拿不到实例。
 *
 * 用 [ConcurrentHashMap] + `computeIfAbsent`：多个会话可以并发拿各自的
 * 容器，互不加锁；同一个 folder 并发进来也只会建一个实例。
 *
 * 依赖注入成两个 lambda / File 而不是直接吃 LocalSandbox，是为了不去动
 * 那个文件，也让容器不知道「会话」「项目」这些上层概念。
 */
class AiContainerManager(
    /** folder -> 容器根目录。接线时传 `localSandbox::projectRoot`。 */
    private val rootProvider: (String?) -> File,
    /** BijiBootstrap.binDir —— 已装工具复用同一份，不复制。 */
    private val binDir: File,
    /** 可执行暂存区的父目录，必须在**内部存储**（context.filesDir）下。 */
    private val execBase: File
) {

    private val containers = ConcurrentHashMap<String, AiContainer>()

    fun get(folder: String?): AiContainer {
        val key = folder?.trim()?.trim('/')?.ifEmpty { null } ?: DEFAULT_KEY
        return containers.computeIfAbsent(key) { k ->
            AiContainer(
                folder = k,
                layout = ContainerLayout(
                    root = rootProvider(k),
                    binDir = binDir,
                    execDir = File(execBase, k)
                )
            )
        }
    }

    fun active(): List<AiContainer> = containers.values.toList()

    /** 会话被删除时调用：停容器 + 删掉它在内部存储上的可执行暂存区。 */
    suspend fun drop(folder: String?) = withContext(Dispatchers.IO) {
        val key = folder?.trim()?.trim('/')?.ifEmpty { null } ?: DEFAULT_KEY
        containers.remove(key)?.let { runCatching { it.shutdown() } }
        runCatching { File(execBase, key).deleteRecursively() }
        Unit
    }

    /**
     * 回收空闲容器：长驻 `sh` 什么都不干时几乎不占 CPU，但每个都吃一份
     * 内存和两个读取协程。**有后台任务在跑的容器永远不回收** —— 把人家
     * 正在编译的活儿掐掉就太蠢了。
     */
    suspend fun reapIdle(idleMs: Long = DEFAULT_IDLE_MS) {
        val now = System.currentTimeMillis()
        for ((key, c) in containers) {
            if (now - c.lastUsedAt < idleMs) continue
            if (c.listTasks(includeFinished = false).isNotEmpty()) continue
            containers.remove(key, c)
            runCatching { c.shutdown() }
        }
    }

    suspend fun shutdownAll() {
        val all = containers.values.toList()
        containers.clear()
        for (c in all) runCatching { c.shutdown() }
    }

    companion object {
        const val DEFAULT_KEY = "default"
        const val DEFAULT_IDLE_MS = 15 * 60 * 1000L
    }
}
