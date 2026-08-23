package com.biji.notes.sandbox

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedWriter
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 后台任务：编译、`pip install`、跑测试、起服务这类几十秒到几十分钟的
 * 活儿。它们**不走长驻会话** —— 一条长命令霸着会话的 stdin，AI 就再也
 * 发不出第二条命令了。每个任务自己一个进程、自己一对读取协程。
 *
 * 输出用环形缓冲兜底：`make -j` 或者一个跑飞的循环能在几秒里吐出上百
 * MB，全存内存必然 OOM。每条流各留 [RING_CAPACITY] 字符，超了丢最早的，
 * 并且如实告诉调用方「前面丢了多少」。同时旁路写一份到
 * `.tasks/<id>/stdout.log`（自带上限），AI 想翻旧账可以直接读文件。
 *
 * 取输出用绝对游标（[TaskPoll.nextStdout] / [TaskPoll.nextStderr]）做增量：
 * 轮询一个长任务时只拉新增的那段，不用每次重读全量 —— 工具轮次预算很
 * 紧，这一点比什么都重要。
 *
 * 线程安全：任务表是 [ConcurrentHashMap]，每条流的环形缓冲内部
 * `@Synchronized`，任务状态字段全 `@Volatile`。没有可变全局状态。
 *
 * ## 内存 / 磁盘的硬上限（默认就该是安全的）
 * 每个容器最多同时留 [MAX_CONCURRENT] 个运行中 + [KEEP_FINISHED] 个已结束
 * 的条目。环形缓冲分冷热两档：运行中和刚结束的 [KEEP_HOT] 条按
 * [RING_CAPACITY] 留，更早的缩到 [COOL_RING_CAPACITY]（结果模型早取走了，
 * 真要翻旧账去读 `logDir` 里的文件）。算下来每容器：
 *   (6 + 2) × 2 流 × 128K 字符 × 2 B + 6 × 2 流 × 16K 字符 × 2 B ≈ 4.4 MB。
 * 落盘侧每条流封顶 [LOG_CAP] 字符，14 个条目 × 2 流 ≈ 14 MB（纯 ASCII）／
 * 42 MB（全中文，UTF-8 一字三字节）。
 */
internal class ContainerTasks(
    private val layout: ContainerLayout,
    private val scope: CoroutineScope
) {

    private val tasks = ConcurrentHashMap<String, Task>()
    private val counter = AtomicLong(0L)

    /**
     * 本实例的出生时间。清孤儿目录时只认比它更老的目录 —— sweep 和 [start]
     * 可能并发，用时间戳挡住"刚建出来的目录被自己扫掉"。
     */
    private val bornAt = System.currentTimeMillis()

    init {
        // 任务进程随 app 一起死，所以冷启动后 `.tasks/` 底下的目录全是死的。
        // 而 [reap] 遍历的是**内存里**的表，重启后表是空的，它一个都够不着 ——
        // 这就是"每次冷启动留一批目录、永不回收"的洞。开机扫一次补上。
        // 放协程里：构造函数在 AiContainer 的初始化路径上，不能做阻塞 I/O。
        scope.launch(Dispatchers.IO) { sweepOrphans() }
    }

    // ---- 启动 ----------------------------------------------------------

    suspend fun start(
        command: String,
        name: String?,
        cwd: String?,
        maxRuntimeMs: Long,
        headWaitMs: Long
    ): TaskStartResult = withContext(Dispatchers.IO) {
        val running = tasks.values.count { it.state == TaskState.RUNNING }
        if (running >= MAX_CONCURRENT) {
            return@withContext TaskStartResult(
                taskId = "", pid = -1, state = TaskState.FAILED, headOutput = "",
                error = "并发任务已达上限 $MAX_CONCURRENT，先 stop 掉一个再来"
            )
        }
        reap()

        val id = "t" + counter.incrementAndGet() + "-" + (System.currentTimeMillis() % 100000)
        val dir = File(layout.tasksDir, id).also { it.mkdirs() }
        val pidFile = File(dir, "pid")
        val workDir = when {
            cwd.isNullOrBlank() -> layout.work
            cwd.startsWith("/") -> File(cwd)
            else -> File(layout.work, cwd)
        }
        if (!workDir.isDirectory) {
            return@withContext TaskStartResult(
                taskId = "", pid = -1, state = TaskState.FAILED, headOutput = "",
                error = "工作目录不存在：${workDir.absolutePath}"
            )
        }

        // `echo $$` 把这层 sh 的 pid 落盘 —— stop 的时候要顺着它找子孙进程，
        // 只 destroyForcibly() 顶层 sh 会把 make 派生的一堆 cc 留成孤儿。
        val script = buildString {
            append("echo ").append('$').append('$')
            append(" > ").append(ContainerProc.shellQuote(pidFile.absolutePath)).append('\n')
            append(command).append('\n')
        }
        val pb = ProcessBuilder("sh", "-c", script)
            .directory(workDir)
            .redirectErrorStream(false)
        layout.applyEnv(pb)
        val proc = try {
            pb.start()
        } catch (e: Throwable) {
            return@withContext TaskStartResult(
                taskId = "", pid = -1, state = TaskState.FAILED, headOutput = "",
                error = "无法启动任务进程：${e.message ?: e.javaClass.simpleName}"
            )
        }

        val t = Task(
            id = id,
            name = name?.takeIf { it.isNotBlank() } ?: command.trim().take(40),
            command = command,
            workDir = workDir.absolutePath,
            startedAt = System.currentTimeMillis(),
            process = proc,
            dir = dir,
            maxRuntimeMs = maxRuntimeMs
        )
        t.jobs = listOf(
            pump(proc.inputStream, t.out, File(dir, "stdout.log")),
            pump(proc.errorStream, t.err, File(dir, "stderr.log"))
        )
        tasks[id] = t
        t.writeMeta()
        supervise(t)

        // 先睡一小会儿再回：绝大多数「command not found」「permission
        // denied」都在头一秒里发生，带着头部输出返回能让 AI 立刻发现，
        // 不用再花一个工具轮次去 poll。
        if (headWaitMs > 0) delay(headWaitMs.coerceAtMost(3_000L))
        runCatching { t.pid = pidFile.readText().trim().toInt() }
        val head = t.out.read(0L, HEAD_CHARS).text + t.err.read(0L, HEAD_CHARS).text
        TaskStartResult(
            taskId = id,
            pid = t.pid,
            state = t.state,
            headOutput = head,
            exitCode = t.exitCode
        )
    }

    // ---- 增量取输出 ----------------------------------------------------

    fun poll(id: String, fromStdout: Long, fromStderr: Long, maxChars: Int): TaskPoll? {
        val t = tasks[id] ?: return null
        val o = t.out.read(fromStdout, maxChars)
        val e = t.err.read(fromStderr, maxChars)
        // 已结束、并且这次已经把两条流都读到末尾 —— 缓冲可以放心缩了，见 [reap]。
        if (t.state != TaskState.RUNNING && !o.clipped && !e.clipped) t.drained = true
        return TaskPoll(
            taskId = t.id,
            state = t.state,
            exitCode = t.exitCode,
            stdout = o.text,
            stderr = e.text,
            nextStdout = o.next,
            nextStderr = e.next,
            droppedStdout = o.dropped,
            droppedStderr = e.dropped,
            moreAvailable = o.clipped || e.clipped,
            finished = t.state != TaskState.RUNNING,
            durationMs = t.duration()
        )
    }

    // ---- 停止 ----------------------------------------------------------

    suspend fun stop(id: String): TaskInfo? {
        val t = tasks[id] ?: return null
        if (t.state == TaskState.RUNNING) {
            t.stopRequested = true
            killTree(t)
            // 给 supervise 一点时间把状态落定，让返回值不是「还在跑」。
            withTimeoutOrNull(1_500L) {
                while (t.state == TaskState.RUNNING) delay(50)
            }
        }
        return t.info()
    }

    suspend fun stopAll(): Int {
        var n = 0
        for (t in tasks.values) {
            if (t.state == TaskState.RUNNING) {
                stop(t.id)
                n++
            }
        }
        return n
    }

    fun list(includeFinished: Boolean): List<TaskInfo> =
        tasks.values
            .filter { includeFinished || it.state == TaskState.RUNNING }
            .sortedBy { it.startedAt }
            .map { it.info() }

    fun runningCount(): Int = tasks.values.count { it.state == TaskState.RUNNING }

    /** reset 用：清掉全部任务记录（进程必须已经停了）。 */
    fun forget() = tasks.clear()

    // ---- 内部 ----------------------------------------------------------

    private fun pump(stream: InputStream, ring: RingLog, logFile: File): Job =
        scope.launch(Dispatchers.IO) {
            val reader = InputStreamReader(stream, Charsets.UTF_8)
            var writer: BufferedWriter? = runCatching { logFile.bufferedWriter() }.getOrNull()
            var written = 0L
            val buf = CharArray(8 * 1024)
            try {
                while (true) {
                    val n = reader.read(buf)
                    if (n <= 0) break
                    val s = String(buf, 0, n)
                    ring.append(s)
                    val w = writer
                    if (w != null && written < LOG_CAP) {
                        runCatching {
                            w.write(s)
                            written += n
                            if (written >= LOG_CAP) {
                                w.write("\n…[日志文件到达 $LOG_CAP 字符上限，后续只进环形缓冲]\n")
                                w.flush()
                                w.close()
                                writer = null
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
            } finally {
                runCatching { writer?.flush() }
                runCatching { writer?.close() }
            }
        }

    private fun supervise(t: Task) {
        scope.launch(Dispatchers.IO) {
            val deadline = if (t.maxRuntimeMs > 0) t.startedAt + t.maxRuntimeMs else Long.MAX_VALUE
            while (t.process.isAlive) {
                if (System.currentTimeMillis() >= deadline) {
                    t.timedOut = true
                    killTree(t)
                    break
                }
                if (t.pid <= 0) runCatching { t.pid = File(t.dir, "pid").readText().trim().toInt() }
                delay(POLL_MS)
            }
            // 等两条 pump 把管道里剩下的读干净再定状态，否则最后几行会丢。
            withTimeoutOrNull(3_000L) { t.jobs.forEach { it.join() } }
            val code = runCatching { t.process.exitValue() }.getOrDefault(-1)
            t.exitCode = code
            t.finishedAt = System.currentTimeMillis()
            t.state = when {
                t.stopRequested -> TaskState.KILLED
                t.timedOut -> TaskState.TIMEOUT
                else -> TaskState.EXITED
            }
            t.writeMeta()
            // 已结束的集合刚变过，这里是唯一不依赖"下一次 start"就能收缩缓冲的
            // 时机。只在 start 里 prune 的老写法，意味着最后一批任务的 26 MB
            // 会一直挂到容器被回收。
            reap()
        }
    }

    private suspend fun killTree(t: Task) {
        if (t.pid <= 0) runCatching { t.pid = File(t.dir, "pid").readText().trim().toInt() }
        if (t.pid > 0) ContainerProc.killTree(t.pid, includeSelf = true)
        runCatching { t.process.destroyForcibly() }
    }

    /**
     * 已结束任务的三档回收，按 finishedAt 从旧到新：
     *  - 最新的 [KEEP_HOT] 条：环形缓冲原样留着，模型多半正要来取结果；
     *  - 往前到第 [KEEP_FINISHED] 条：缩到 [COOL_RING_CAPACITY]。缩掉的部分
     *    照旧计进 `dropped`，调用方能看出自己错过了多少，全文在 `logDir`；
     *  - 更早的：连条目带目录一起删。
     *
     * 缩容有个明摆着的代价：模型连开三个任务、只在最后才回头读第一个的输出，
     * 会读到被截过的尾巴。换来的是每容器 26 MB → 4.4 MB，且 dropped 计数
     * 和落盘日志兜着，不是静默丢数据。
     */
    private fun reap() {
        val done = tasks.values.filter { it.state != TaskState.RUNNING }.sortedBy { it.finishedAt }
        if (done.size > KEEP_FINISHED) {
            for (t in done.take(done.size - KEEP_FINISHED)) {
                tasks.remove(t.id)
                runCatching { t.dir.deleteRecursively() }
            }
        }
        val kept = if (done.size > KEEP_FINISHED) done.drop(done.size - KEEP_FINISHED) else done
        if (kept.size <= KEEP_HOT) return
        for (t in kept.take(kept.size - KEEP_HOT)) {
            // **没被读干净过的不缩。** reap() 现在也挂在任务落定那一刻上，而
            // container_task 一次能起到 MAX_CONCURRENT = 6 个：模型连开 4 个编译、
            // 它们前后脚结束，KEEP_HOT = 2 意味着最早那两个会在模型第一次 poll
            // **之前**就被砍到 16K —— 省下来的那点内存换掉的是它这一步要读的
            // 编译错误。读到末尾过一次（[Task.drained]）才算"凉了"。
            // 上限仍然成立：没读的最多攒到 KEEP_FINISHED 条，再往前连目录一起删。
            if (!t.drained) continue
            t.out.shrink(COOL_RING_CAPACITY)
            t.err.shrink(COOL_RING_CAPACITY)
        }
    }

    /**
     * 删掉 `.tasks/` 下不属于本进程的任务目录。
     *
     * 判据很硬：任务进程是本进程 fork 出来的，app 一死它们全没了，所以磁盘上
     * 任何"内存表里没有、而且比本实例还老"的目录都是上一次运行的残骸。
     * 一次编译的日志目录 8–24 MB，冷启动一次留一批，以前没有任何人管它。
     */
    private fun sweepOrphans() {
        val dirs = layout.tasksDir.listFiles() ?: return
        for (d in dirs) {
            if (!d.isDirectory) continue
            if (tasks.containsKey(d.name)) continue
            // 时钟回拨 / 时间戳在未来的目录这轮先放过，下次冷启动 bornAt 更大就收掉。
            if (runCatching { d.lastModified() }.getOrDefault(0L) >= bornAt) continue
            runCatching { d.deleteRecursively() }
        }
    }

    private class Task(
        val id: String,
        val name: String,
        val command: String,
        val workDir: String,
        val startedAt: Long,
        val process: Process,
        val dir: File,
        val maxRuntimeMs: Long
    ) {
        val out = RingLog(RING_CAPACITY)
        val err = RingLog(RING_CAPACITY)

        @Volatile var state: TaskState = TaskState.RUNNING
        @Volatile var exitCode: Int? = null
        @Volatile var finishedAt: Long = 0L
        @Volatile var pid: Int = -1
        @Volatile var stopRequested: Boolean = false
        @Volatile var timedOut: Boolean = false
        @Volatile var jobs: List<Job> = emptyList()

        /** 结束之后被 poll 读到过末尾。没有这一笔就把缓冲缩掉，等于在模型
         *  第一次看结果之前先把结果删了一半。见 [ContainerTasks.reap]。 */
        @Volatile var drained: Boolean = false

        fun duration(): Long =
            (if (finishedAt > 0) finishedAt else System.currentTimeMillis()) - startedAt

        fun info() = TaskInfo(
            taskId = id, name = name, command = command, workDir = workDir,
            state = state, exitCode = exitCode, pid = pid,
            startedAt = startedAt, durationMs = duration(),
            stdoutChars = out.total, stderrChars = err.total,
            droppedChars = out.droppedTotal + err.droppedTotal,
            logDir = dir.absolutePath
        )

        /** 落一份纯 key=value 的元数据，app 被系统杀掉重启后还能看到
         *  这里跑过什么、日志在哪。进程本身随 app 死，这点必须写清楚。 */
        fun writeMeta() {
            runCatching {
                File(dir, "meta").writeText(
                    buildString {
                        appendLine("id=$id")
                        appendLine("name=${name.replace('\n', ' ')}")
                        appendLine("command=${command.replace('\n', ' ')}")
                        appendLine("workDir=$workDir")
                        appendLine("startedAt=$startedAt")
                        appendLine("state=$state")
                        appendLine("exitCode=${exitCode ?: ""}")
                    },
                    Charsets.UTF_8
                )
            }
        }
    }

    /**
     * 环形缓冲：按 chunk 整段淘汰，配一个单调递增的绝对游标。
     * 淘汰整段（而不是切半段）让游标算术保持简单 —— [oldest] 之前的
     * 内容一律标成 dropped，调用方能明确知道自己错过了多少。
     */
    private class RingLog(capacity: Int) {
        private class Chunk(val start: Long, val text: String)

        /** 可缩不可涨：任务结束后由 [reap] 调小，见 [shrink]。 */
        private var capacity = capacity

        private val chunks = ArrayDeque<Chunk>()
        private var size = 0
        private var oldest = 0L

        @Volatile var total: Long = 0L
            private set

        @Volatile var droppedTotal: Long = 0L
            private set

        @Synchronized fun append(s: String) {
            if (s.isEmpty()) return
            chunks.addLast(Chunk(total, s))
            total += s.length
            size += s.length
            trim()
        }

        /** 调小容量并立刻吐出多余的段。已经比它小就是空操作。 */
        @Synchronized fun shrink(newCapacity: Int) {
            if (newCapacity >= capacity) return
            capacity = if (newCapacity < 0) 0 else newCapacity
            trim()
        }

        // 整段淘汰，[chunks] 是 ArrayDeque，removeFirst 是 O(1)：每个字符
        // 一生只被搬进搬出一次，摊还 O(1)。
        private fun trim() {
            while (size > capacity && chunks.size > 1) {
                val head = chunks.removeFirst()
                size -= head.text.length
                oldest = head.start + head.text.length
                droppedTotal += head.text.length
            }
        }

        @Synchronized fun read(from: Long, max: Int): RingRead {
            val start = if (from < oldest) oldest else from
            val dropped = if (from < oldest) oldest - from else 0L
            if (start >= total || max <= 0) return RingRead("", start, dropped, false)
            val sb = StringBuilder()
            for (c in chunks) {
                val end = c.start + c.text.length
                if (end <= start) continue
                val off = if (c.start >= start) 0 else (start - c.start).toInt()
                sb.append(c.text, off, c.text.length)
                if (sb.length >= max) break
            }
            val clipped = sb.length > max
            val text = if (clipped) sb.substring(0, max) else sb.toString()
            return RingRead(text, start + text.length, dropped, clipped || start + text.length < total)
        }
    }

    private class RingRead(
        val text: String,
        val next: Long,
        val dropped: Long,
        val clipped: Boolean
    )

    companion object {
        const val MAX_CONCURRENT = 6

        /**
         * 运行中 / 刚结束的任务每条流留多少**字符**（Java char = 2 B，
         * 所以 128K 字符 = 256 KB 堆）。原来是 256K 字符，两条流就是 1 MB，
         * 乘上 6 运行 + 20 保留 = 26 MB／容器，而其中绝大多数是模型早就读过
         * 一遍的编译日志。128K 字符 ≈ 一屏 80 列的 1600 行，够看清一次
         * `make` 失败的完整尾巴。
         */
        const val RING_CAPACITY = 128 * 1024

        /** 结果已经"凉了"的任务缩到这个尺寸，够看最后几十行报错。 */
        const val COOL_RING_CAPACITY = 16 * 1024

        /** 最近几条已结束任务保持满缓冲 —— 模型通常紧接着就来 poll。 */
        const val KEEP_HOT = 2

        /**
         * 落盘日志每条流的字符上限。写的是 UTF-8，中文一字三字节，所以
         * 512K 字符最坏 1.5 MB／流。原来的 4M 字符最坏是 12 MB／流，
         * 乘上任务数就是 208–624 MB／容器。
         */
        const val LOG_CAP = 512 * 1024

        /** 已结束任务的条目上限（连带它的日志目录）。 */
        const val KEEP_FINISHED = 8

        const val HEAD_CHARS = 1_500
        private const val POLL_MS = 200L
    }
}
