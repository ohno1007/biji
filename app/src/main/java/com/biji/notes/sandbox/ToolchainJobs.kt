package com.biji.notes.sandbox

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/** 一个安装任务里的一步。多步是给 `toolchain_manifest action=ensure` 用的
 *  —— 「帮我搭个 C 环境」一句话可能要连装三四个大件，它们该是一个可轮询、
 *  可整体取消的东西，而不是四次各自阻塞几分钟的调用。 */
data class ToolchainStep(val label: String, val request: InstallRequest)

enum class ToolchainJobState {
    QUEUED, RUNNING, DONE, FAILED, CANCELLED;

    val finished: Boolean get() = this == DONE || this == FAILED || this == CANCELLED
}

/** poll 的返回值。日志用绝对游标 [nextCursor] 做增量，和 container_task
 *  一个约定：把上次拿到的值原样传回来就不会重复读。 */
data class ToolchainJobView(
    val id: String,
    val title: String,
    val state: ToolchainJobState,
    val stepLabel: String,
    val stepIndex: Int,
    val stepCount: Int,
    /** queued / downloading / extracting / verifying / done */
    val phase: String,
    /** -1 = 这个阶段算不出百分比（目录里没登记安装后体积时解包就是这样）。 */
    val percent: Int,
    val bytes: Long,
    val totalBytes: Long,
    /** 解包时已写出的条目数。体积未知时它是唯一能证明「还在动」的东西。 */
    val entries: Int,
    val elapsedMs: Long,
    val queuedAhead: Int,
    val message: String,
    val log: String,
    val nextCursor: Long,
    val droppedChars: Long,
    val moreAvailable: Boolean,
    val results: List<InstallResult>
)

/**
 * 工具链安装的「启动 + 轮询」调度层。
 *
 * 为什么要有它：装 zig 要下 48.8 MB、解出 230 MB、往内部存储写两万多个
 * 文件，同步做完是分钟级。全程占着一次工具调用的后果有两个 —— 模型这一轮
 * 几分钟不吽声，用户看到的就是 AI 卡死；而且没有任何保活，用户一锁屏进程
 * 就可能被冻结甚至被杀，环境留在半装状态。所以大件一律「立刻返回 job_id，
 * 之后 poll」，形状照抄 [ContainerTasks]：绝对游标增量拉、可取消。
 *
 * **串行**：一条网络管道、一块闪存、一条进程级的 [ToolchainInstaller.progress]
 * 流，并排装两个大件只会更慢，而且进度归不了因。排队中的 job 状态是 QUEUED，
 * poll 会告诉模型前面还有几个。
 *
 * 生命周期：job 跑在这里的进程级 scope 上，**不挂在调用方的协程下面** ——
 * 工具调用返回后安装还得继续。保活由 [ToolchainService] 负责，见 [ensureService]。
 */
object ToolchainJobs {

    // =================================================================
    //  同步 / 异步的阈值
    // =================================================================

    /**
     * 小件的判据：下载 ≤ 8 MB **且** 装完 ≤ 24 MB。
     *
     * 算的是墙上时间，不是拍脑袋：一次工具调用超过十几秒模型这一轮就哑了，
     * 所以内联做完的活儿必须稳定压在这个量级内。移动网络保守按 2 MB/s，
     * 8 MB ≈ 4 s；解包 + 往内部存储写按 8 MB/s（小文件多时更慢），24 MB ≈ 3 s；
     * 再加 smoke test 一次 exec ≈ 1 s —— 最坏 ~10 s，可以接受。
     *
     * 按这条线切目录：rg(1.9/5.2)、fd、jq、toybox、busybox、make、curl 走同步，
     * 一次调用装完，不用多花两个轮次；gitoxide(10.2/26)、uv(19.3/48)、
     * bun(33/85.6)、zig(48.8/230)、go(60.8/280) 走后台。
     *
     * 手工 url 安装（没有目录条目，体积未知）当小件处理：绝大多数是单文件
     * 裸二进制。万一猜错了也不会卡死 —— 内联等够 [INLINE_WAIT_SMALL_MS] 还没
     * 完就地降级成后台 job，模型照样拿到 job_id。
     */
    const val SMALL_DOWNLOAD_BYTES = 8L * 1024 * 1024
    const val SMALL_INSTALLED_BYTES = 24L * 1024 * 1024

    /** 小件的内联等待预算。超了就降级成后台 job，不阻塞到底。 */
    const val INLINE_WAIT_SMALL_MS = 20_000L

    /**
     * 大件也等一下再返回，理由和 container_task 的 headWaitMs 一样：
     * HTTP 404、磁盘不够、用户关了自动安装这类失败都发生在头一秒里，
     * 带着结论返回能省掉模型一次白跑的 poll。
     */
    const val INLINE_WAIT_LARGE_MS = 1_200L

    /**
     * 起前台服务之前先等这么久。装 jq 两秒就完了，为它闪一下通知栏很吵；
     * 超过这个时间的才值得保活。这段窗口里进程还不至于被冻。
     */
    private const val SERVICE_DELAY_MS = 1_500L

    /** 进度日志留这么多字符。这里一秒钟撑死几行，不是 make -j 那种输出洪水。 */
    private const val LOG_CAPACITY = 32 * 1024

    /** 已结束的 job 只留最近这些条，别让表无限长。 */
    private const val KEEP_FINISHED = 12

    // =================================================================
    //  状态
    // =================================================================

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, JobEntry>()
    private val counter = AtomicLong(0L)

    /** 单槽：同一时刻只跑一个安装。 */
    private val slot = Mutex()

    private val _active = MutableStateFlow<List<ToolchainJobView>>(emptyList())

    /** 没结束的 job（含排队中）。[ToolchainService] 靠它刷通知、并在空了之后收掉自己。 */
    val active: StateFlow<List<ToolchainJobView>> = _active.asStateFlow()

    @Volatile private var appContext: Context? = null

    /** 由 [ToolchainContextProvider] 在进程启动时喂进来。 */
    fun attach(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    // =================================================================
    //  启动
    // =================================================================

    fun isSmall(steps: List<ToolchainStep>): Boolean = steps.all { s ->
        val e = s.request.catalogId?.let { ToolchainCatalog.resolve(it) }
            ?: return@all true          // 体积未知 → 当小件，超时会自动降级
        e.downloadBytes <= SMALL_DOWNLOAD_BYTES && e.installedBytes <= SMALL_INSTALLED_BYTES
    }

    /**
     * 起一个安装 job，内联等一会儿。
     *
     * 返回时 [ToolchainJobView.state] 已经是终态就说明这一次调用里装完了，
     * 调用方直接把 [ToolchainJobView.results] 当同步结果用；还在跑就把
     * job_id 交给模型去 poll。两条路的差别只有「等多久」。
     *
     * [forceBackground] 给模型的 background=true 用：它有时比体积表更清楚
     * 这次会很慢（比如网络很差）。
     */
    suspend fun start(
        installer: ToolchainInstaller,
        steps: List<ToolchainStep>,
        title: String,
        forceBackground: Boolean = false
    ): ToolchainJobView {
        prune()
        val seq = counter.incrementAndGet()
        val id = "i$seq-" + (System.currentTimeMillis() % 100000)
        val entry = JobEntry(id, seq, title, steps)
        jobs[id] = entry
        entry.log.append("排队：$title（${steps.size} 步）")
        publish()

        val handle = scope.launch { runJob(installer, entry) }
        entry.handle = handle
        handle.invokeOnCompletion { cause -> entry.settle(cause); publish() }

        // 保活：等一小会儿再拉服务起来，短安装不该在通知栏闪一下。
        scope.launch {
            delay(SERVICE_DELAY_MS)
            if (!entry.state.finished) ensureService()
        }

        val budget = if (forceBackground || !isSmall(steps)) INLINE_WAIT_LARGE_MS
        else INLINE_WAIT_SMALL_MS
        withTimeoutOrNull(budget) { entry.done.await() }
        return entry.view(0L, LOG_HEAD_CHARS)
    }

    private suspend fun runJob(installer: ToolchainInstaller, entry: JobEntry) {
        try {
            runSteps(installer, entry)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // 这个 catch 是后台化**必须**补上的：安装以前跑在工具调用的协程里，
            // ChatViewModel 那句 runCatching { toolExec.run(...) } 兜住一切；
            // 现在 job 跑在下面这个进程级 scope 上，它没有 CoroutineExceptionHandler，
            // 漏出去一个 Throwable 就直接进 uncaughtExceptionHandler —— 整个 app 挂掉，
            // 而且是在用户锁屏走开的时候挂。
            //
            // 安装器自己已经把所有 Exception 变成了 InstallResult，所以这里接到的
            // 只会是它管不到的部分（Error、账本落盘、进度回调里的意外）。塞一条
            // 失败结果进去，settle() 就会把 job 判成 FAILED，模型 poll 得到原因。
            entry.message = t.message ?: t.javaClass.simpleName
            entry.results += InstallResult(
                ok = false,
                name = entry.steps.getOrNull(entry.stepIndex)?.label ?: entry.title,
                message = "安装中断（内部错误）：${entry.message}"
            )
            entry.log.append("✗ 安装中断：${entry.message}")
        }
    }

    private suspend fun runSteps(installer: ToolchainInstaller, entry: JobEntry) {
        // 拿到槽之前状态一直是 QUEUED，poll 会如实说「前面还有几个」，
        // 而不是让模型以为下载卡住了。
        slot.withLock {
            entry.state = ToolchainJobState.RUNNING
            entry.startedAt = System.currentTimeMillis()
            entry.phase = "downloading"
            publish()
            for ((i, step) in entry.steps.withIndex()) {
                entry.stepIndex = i
                entry.bytes = 0L
                entry.total = 0L
                entry.phase = "downloading"
                entry.log.append("[${i + 1}/${entry.steps.size}] 开始装 ${step.label}")
                publish()
                val r = InstallerBridge.install(installer, step.request) { p ->
                    entry.onProgress(p)
                }
                entry.results += r
                entry.log.append(if (r.ok) "✓ ${step.label} ${r.message}" else "✗ ${step.label} ${r.message}")
                if (!r.ok) {
                    // 一步失败就停：ensure 出来的后几步通常依赖前几步，
                    // 硬着头皮装完只会把失败原因埋进一堆输出里。
                    entry.message = r.message
                    if (entry.steps.size > 1 && i < entry.steps.size - 1) {
                        entry.log.append("后面 ${entry.steps.size - i - 1} 步没做 —— 先解决这个再继续。")
                    }
                    break
                }
                publish()
            }
        }
    }

    // =================================================================
    //  轮询 / 取消
    // =================================================================

    fun poll(id: String, cursor: Long, maxChars: Int): ToolchainJobView? =
        jobs[id]?.view(cursor, maxChars)

    /**
     * 取消。只 cancel 协程 —— 安装本身按「可取消的 suspend 函数」的约定在
     * 下一个挂起点退出并回滚（约定见 [InstallerBridge]）。状态立刻标成
     * CANCELLED，通知栏和模型不用等 IO 真正断掉才看到。
     */
    suspend fun cancel(id: String): ToolchainJobView? {
        val entry = jobs[id] ?: return null
        if (!entry.state.finished) {
            entry.cancelRequested = true
            entry.handle?.cancel(CancellationException("用户/模型取消了安装"))
            withTimeoutOrNull(1_500L) {
                while (!entry.state.finished) delay(50)
            }
        }
        return entry.view(0L, LOG_HEAD_CHARS)
    }

    suspend fun cancelAll(): Int {
        var n = 0
        for (e in jobs.values) if (!e.state.finished) { cancel(e.id); n++ }
        return n
    }

    fun list(includeFinished: Boolean = true): List<ToolchainJobView> =
        jobs.values
            .filter { includeFinished || !it.state.finished }
            .sortedBy { it.seq }
            .map { it.view(Long.MAX_VALUE, 0) }

    private fun prune() {
        val done = jobs.values.filter { it.state.finished }.sortedBy { it.finishedAt }
        if (done.size <= KEEP_FINISHED) return
        done.take(done.size - KEEP_FINISHED).forEach { jobs.remove(it.id) }
    }

    private fun publish() {
        _active.value = jobs.values
            .filter { !it.state.finished }
            .sortedBy { it.seq }
            .map { it.view(Long.MAX_VALUE, 0) }
    }

    // =================================================================
    //  保活
    // =================================================================

    /**
     * 目标是「用户锁屏出去泡杯茶，回来环境是装好的」。前台服务 + partial
     * wakelock 都在 [ToolchainService] 里。
     *
     * targetSdk = 28 在这里正好占了便宜：Android 12 起「后台不许起前台服务」
     * 和 Android 14 起 foregroundServiceType 强制，两条都是按 targetSdk 门控的，
     * 对我们不生效。所以聊天在后台跑着也能把服务拉起来。
     *
     * 拉不起来（某些 OEM 的省电策略）就算了，安装照跑，只是没保活 ——
     * 这一层绝不能把异常甩给上面。
     */
    private fun ensureService() {
        val ctx = appContext ?: return
        runCatching {
            ContextCompat.startForegroundService(ctx, Intent(ctx, ToolchainService::class.java))
        }
    }

    // =================================================================
    //  文案（poll 的文本和通知栏共用一份，省得两边说法不一样）
    // =================================================================

    fun progressLine(v: ToolchainJobView): String = when (v.state) {
        ToolchainJobState.QUEUED ->
            if (v.queuedAhead > 0) "排队中，前面还有 ${v.queuedAhead} 个" else "排队中"
        ToolchainJobState.RUNNING -> buildString {
            append(
                when (v.phase) {
                    "downloading" -> "下载"
                    "extracting" -> "解包"
                    "verifying" -> "校验"
                    else -> "准备"
                }
            )
            if (v.percent >= 0) append(" ${v.percent}%")
            if (v.totalBytes > 0) {
                append(" · ${ToolchainInstaller.human(v.bytes)}/${ToolchainInstaller.human(v.totalBytes)}")
            } else if (v.entries > 0) {
                append(" · ${v.entries} 个文件")
            }
            if (v.stepCount > 1) append(" · 第 ${v.stepIndex + 1}/${v.stepCount} 个")
            append(" · ").append(v.elapsedMs / 1000).append("s")
        }
        ToolchainJobState.DONE -> "已完成"
        ToolchainJobState.FAILED -> "失败：${v.message}"
        ToolchainJobState.CANCELLED -> "已取消"
    }

    private const val LOG_HEAD_CHARS = 2_000

    // =================================================================
    //  内部
    // =================================================================

    private class JobEntry(
        val id: String,
        val seq: Long,
        val title: String,
        val steps: List<ToolchainStep>
    ) {
        val createdAt = System.currentTimeMillis()
        val results = CopyOnWriteArrayList<InstallResult>()
        val log = LogRing(LOG_CAPACITY)
        val done = CompletableDeferred<Unit>()

        @Volatile var startedAt = 0L
        @Volatile var finishedAt = 0L
        @Volatile var state = ToolchainJobState.QUEUED
        @Volatile var stepIndex = 0
        @Volatile var phase = "queued"
        @Volatile var bytes = 0L
        @Volatile var total = 0L
        @Volatile var entries = 0
        @Volatile var message = ""
        @Volatile var handle: Job? = null
        @Volatile var cancelRequested = false

        /** 进度事件一秒能来几十条，全写日志会把环形缓冲冲干净、也会让
         *  通知栏一直重画。只在「阶段变了」或「进度跨过 5% 的档」时才动。 */
        @Volatile private var lastBucket = ""

        fun onProgress(p: ToolchainProgress) {
            when (p) {
                is ToolchainProgress.Downloading -> {
                    phase = "downloading"; bytes = p.bytes; total = p.total; entries = 0
                }
                is ToolchainProgress.Extracting -> {
                    // total 是目录里登记的估计值，可能为 0（手工 url 安装）——
                    // 那种时候只有 entries 能证明还在动，别把它扔了。
                    phase = "extracting"; bytes = p.bytes; total = p.total; entries = p.entries
                }
                is ToolchainProgress.Verifying -> {
                    phase = "verifying"; bytes = 0L; total = 0L; entries = 0
                }
                // 终态由 job 自己定。全局流上的 Done/Failed/Idle 可能是别人
                // （比如 probe 里的 bootstrapIfEmpty）留下的，认了会误报。
                else -> return
            }
            val pct = percent()
            // 解包体积未知时按条目数分档，否则两万个文件一条日志都不会有。
            val bucket = when {
                pct >= 0 -> "$phase:${pct / 5}"
                phase == "extracting" -> "x:${entries / 2000}"
                else -> phase
            }
            if (bucket == lastBucket) return
            lastBucket = bucket
            log.append(
                buildString {
                    append(steps.getOrNull(stepIndex)?.label ?: title).append(' ')
                    append(
                        when (phase) {
                            "downloading" -> "下载"
                            "extracting" -> "解包"
                            else -> "校验"
                        }
                    )
                    if (pct >= 0) append(" $pct%")
                    if (total > 0) {
                        append("（${ToolchainInstaller.human(bytes)}/${ToolchainInstaller.human(total)}）")
                    } else if (entries > 0) {
                        append("（$entries 个文件）")
                    }
                }
            )
            ToolchainJobs.publish()
        }

        /** 下载和解包都能算百分比；解包那个是按目录里的估计体积算的，
         *  只配画进度条，判完成得看 job 的终态。 */
        fun percent(): Int =
            if ((phase == "downloading" || phase == "extracting") && total > 0) {
                ((bytes * 100) / total).toInt().coerceIn(0, 100)
            } else -1

        fun settle(cause: Throwable?) {
            finishedAt = System.currentTimeMillis()
            state = when {
                cause is CancellationException -> ToolchainJobState.CANCELLED
                cause != null -> {
                    message = cause.message ?: cause.javaClass.simpleName
                    ToolchainJobState.FAILED
                }
                results.any { !it.ok } -> ToolchainJobState.FAILED
                // cancelRequested 排在最后，而且要求「步子确实没走完」。
                // 它和 cause 不是一回事：cancel() 是先置标志再 cancel 协程的，
                // 如果协程其实已经跑完、只是 invokeOnCompletion 还没轮到，
                // 光看标志就会把一次**装成了、账本也记了**的安装报成「已取消」，
                // 模型于是以为什么都没发生，回头再装一遍。
                cancelRequested && results.size < steps.size -> ToolchainJobState.CANCELLED
                else -> ToolchainJobState.DONE
            }
            if (state == ToolchainJobState.CANCELLED) {
                // 多步 job（manifest ensure）取消时，前面几步是**真的装完了**
                // 并且进了账本的，只有当前这步被回滚。一句「没有留下半成品」
                // 会让模型以为整批都没装。
                val ok = results.count { it.ok }
                log.append(
                    if (ok > 0) "已取消。当前这步已回滚；在此之前装完的 $ok 个仍然有效，账本里记着。"
                    else "已取消，半成品由安装器自己回滚。"
                )
            }
            phase = "done"
            done.complete(Unit)
        }

        /** [from] 传 Long.MAX_VALUE = 只要状态不要日志（通知栏和 list 用）。 */
        fun view(from: Long, maxChars: Int): ToolchainJobView {
            val r = if (maxChars <= 0) LogRead("", log.total, 0L, false) else log.read(from, maxChars)
            val ahead = if (state == ToolchainJobState.QUEUED) {
                ToolchainJobs.jobs.values.count { !it.state.finished && it.seq < seq }
            } else 0
            return ToolchainJobView(
                id = id,
                title = title,
                state = state,
                stepLabel = steps.getOrNull(stepIndex)?.label ?: title,
                stepIndex = stepIndex,
                stepCount = steps.size,
                phase = phase,
                percent = percent(),
                bytes = bytes,
                totalBytes = total,
                entries = entries,
                elapsedMs = (if (finishedAt > 0) finishedAt else System.currentTimeMillis()) -
                    (if (startedAt > 0) startedAt else createdAt),
                queuedAhead = ahead,
                message = message,
                log = r.text,
                nextCursor = r.next,
                droppedChars = r.dropped,
                moreAvailable = r.more,
                results = results.toList()
            )
        }
    }

    private class LogRead(val text: String, val next: Long, val dropped: Long, val more: Boolean)

    /**
     * 按字符数封顶的日志缓冲 + 单调递增的绝对游标。
     *
     * 比 ContainerTasks 那个分段环形简单得多，因为这里的写入量差着几个
     * 数量级：一秒几行进度，不是编译器的输出洪水。整段 delete 的 O(n) 拷贝
     * 在 32 KB 上可以忽略。
     */
    private class LogRing(private val capacity: Int) {
        private val sb = StringBuilder()

        /** 写进去过的总字符数 = 游标的上界。缓冲里留下的只是它的尾巴。 */
        @Volatile var total: Long = 0L
            private set

        @Synchronized fun append(line: String) {
            val s = if (line.endsWith("\n")) line else line + "\n"
            sb.append(s)
            total += s.length
            if (sb.length > capacity) sb.delete(0, sb.length - capacity)
        }

        @Synchronized fun read(from: Long, max: Int): LogRead {
            val oldest = total - sb.length
            val start = from.coerceIn(oldest, total)
            val skipped = if (from < oldest) oldest - from else 0L
            val off = (start - oldest).toInt()
            val all = if (off >= sb.length) "" else sb.substring(off)
            val clipped = all.length > max
            val text = if (clipped) all.substring(0, max) else all
            return LogRead(text, start + text.length, skipped, start + text.length < total)
        }
    }
}

/**
 * 对 [ToolchainInstaller] 的**唯一**接触面。
 *
 * 之所以单独拎出来：安装器那边正在把进度改成持续上报，改完这个文件里
 * 只有这一处要跟着动。这里对上层承诺两件事 ——
 *  1. install 是**可取消**的 suspend 函数，取消后不留半成品（回滚是安装器的事）；
 *  2. 跑的过程中会有进度事件回调出来。
 *
 * 现在的实现是「旁听进程级的 [ToolchainInstaller.progress] StateFlow」。
 * 下载和解包都已经是持续上报了，缺的只是**归属** —— 事件里没说这是谁的
 * 安装。[ToolchainJobs] 把安装串行化了，同一时刻只有一个 install 在跑，
 * 所以对得上。唯一的例外是 toolchain_probe 里的 bootstrapIfEmpty()，
 * 它不走 job —— 最坏情况是进度条闪一下别人的数字，终态不受影响
 * （onProgress 只认三个中间态，Done/Failed/Idle 一律不认）。
 *
 * 等安装器给出带回调的重载（install(req) { progress -> ... }），把下面
 * 换成直接调用、删掉旁听协程即可，其余代码一行不用改。
 */
internal object InstallerBridge {

    suspend fun install(
        installer: ToolchainInstaller,
        request: InstallRequest,
        onProgress: (ToolchainProgress) -> Unit
    ): InstallResult = coroutineScope {
        val watcher = launch { installer.progress.collect { onProgress(it) } }
        try {
            installer.install(request)
        } finally {
            watcher.cancel()
        }
    }
}

/**
 * 只为了拿一个 Application Context 而存在。
 *
 * 工具层 [com.biji.notes.net.ToolchainTools] 的入口签名里没有 Context
 * （那是 ToolExecutor 的调用约定，不归这轮改），而起前台服务必须有一个。
 * ContentProvider 的 onCreate 在 Application.onCreate 之前就被系统调用，
 * 是拿早期 Context 的标准做法（androidx.startup、Firebase 都这么干），
 * 代价是几微秒。
 */
class ToolchainContextProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        context?.let { ToolchainJobs.attach(it) }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}
