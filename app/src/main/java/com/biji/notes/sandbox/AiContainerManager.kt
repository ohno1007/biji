package com.biji.notes.sandbox

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

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
 *
 * ## 数量上限自己兜着，不指望调用方
 * 每个活容器大约 = 长驻会话缓冲 3.8 MB + 后台任务环形缓冲 ~4.4 MB ≈ 8 MB，
 * 外加两条读取协程。以前唯一的回收入口 `reapIdle()` 只在 app 退到后台时被
 * 调一次（`BijiApp.kt`），用户一直待在前台就永不回收，笔记切得多就是一路涨。
 * 现在 [get] 每次都会顺手做两件事：到点了就跑一轮闲置回收、超了
 * [MAX_CONTAINERS] 就按 LRU 挤掉最久没用的。两件都是异步的 ——
 * [get] 在热路径上，不能因为要收摊就变慢或者变成 suspend。
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

    /** 回收用的自有作用域：[get] 是同步的，收摊却是 suspend 的。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 同一时刻只跑一轮回收，免得两次 [get] 撞在一起重复 shutdown。 */
    private val sweepLock = Mutex()

    private val lastSweepAt = AtomicLong(0L)

    fun get(folder: String?): AiContainer {
        val key = folder?.trim()?.trim('/')?.ifEmpty { null } ?: DEFAULT_KEY
        val c = containers.computeIfAbsent(key) { k ->
            AiContainer(
                folder = k,
                layout = ContainerLayout(
                    root = rootProvider(k),
                    binDir = binDir,
                    execDir = File(execBase, k)
                )
            )
        }
        maybeSweep(key)
        return c
    }

    /**
     * 节流后的后台收摊。[key] 是本次刚要用的容器，**无论如何不能收它** ——
     * 调用方手里已经攥着这个引用了，收掉等于把它的 scope cancel 掉，
     * 表现是「AI 说在跑，实际什么都没发生」。
     */
    private fun maybeSweep(key: String) {
        val now = System.currentTimeMillis()
        val prev = lastSweepAt.get()
        // 已经超上限时缩短间隔，但**不能取消间隔** —— 一轮挤不动（都在跑任务）
        // 的话，不设间隔就是每次 get 都白起一个协程。
        val gap = if (containers.size > MAX_CONTAINERS) OVER_CAP_SWEEP_MS else SWEEP_INTERVAL_MS
        if (now - prev < gap) return
        if (!lastSweepAt.compareAndSet(prev, now)) return
        scope.launch {
            sweepLock.withLock {
                runCatching { reapIdle(keep = key) }
                runCatching { trimToCap(key) }
            }
        }
    }

    /**
     * 超了 [MAX_CONTAINERS] 就按 lastUsedAt 从旧到新挤，直到回到上限。
     *
     * 三条不碰的：本次正在用的那个、有后台任务在跑的、以及 [MIN_EVICT_IDLE_MS]
     * 内动过的（一个刚做完一轮工具调用的容器，模型下一句多半还要用它，
     * 收掉就是白白重建一遍长驻 shell）。全都不满足条件时**宁可超上限** ——
     * 超一点内存，好过把用户正在编译的活儿掐了。下一次 [get] 再试。
     */
    private suspend fun trimToCap(keepKey: String) {
        if (containers.size <= MAX_CONTAINERS) return
        val now = System.currentTimeMillis()
        val victims = containers.entries
            .filter { it.key != keepKey && now - it.value.lastUsedAt >= MIN_EVICT_IDLE_MS }
            .sortedBy { it.value.lastUsedAt }
        for ((key, c) in victims) {
            if (containers.size <= MAX_CONTAINERS) return
            // 两种"正在干活"都要躲开：后台任务（container_task）和前台 exec。
            // 后者以前漏掉了 —— lastUsedAt 是 exec 开始时打的，一条 120 秒的
            // 编译跑到第 61 秒就越过了 MIN_EVICT_IDLE_MS，shutdown() 会把长驻
            // shell 连 scope 一起收掉，表现是编译无缘无故断在半路。
            if (c.busy) continue
            if (c.listTasks(includeFinished = false).isNotEmpty()) continue
            if (!containers.remove(key, c)) continue
            runCatching { c.shutdown() }
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
    suspend fun reapIdle(idleMs: Long = DEFAULT_IDLE_MS, keep: String? = null) {
        val now = System.currentTimeMillis()
        for ((key, c) in containers) {
            // keep 是调用方此刻正握着的容器。lastUsedAt 只有容器自己的操作会
            // 更新，光 get() 不更新，所以"闲置 20 分钟后刚被取出来"的容器会
            // 正好撞上这一轮回收，被收掉的是一个别人马上要用的实例。
            if (key == keep) continue
            if (now - c.lastUsedAt < idleMs) continue
            if (c.busy) continue
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

        /**
         * 同时活着的容器数上限。每个约 8 MB 堆（会话 3.8 + 任务缓冲 4.4），
         * 4 个 ≈ 32 MB。选 4 不是 1：用户在几个笔记之间来回切是常态，
         * 每切一次就重建长驻 shell（重跑 .env、重定位 cwd）体感很差。
         */
        const val MAX_CONTAINERS = 4

        /** 两轮后台回收之间的最小间隔。[get] 在热路径上，不能每次都扫。 */
        const val SWEEP_INTERVAL_MS = 60 * 1000L

        /** 超上限时的间隔，短一点，好让挤出尽快生效。 */
        const val OVER_CAP_SWEEP_MS = 5 * 1000L

        /** 刚用过的容器不参与 LRU 挤出，见 [trimToCap]。 */
        const val MIN_EVICT_IDLE_MS = 60 * 1000L
    }
}
