package com.biji.notes.sandbox

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

// =====================================================================
//  对外数据类型
// =====================================================================

data class ExecResult(
    val command: String,
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val cwd: String,
    val durationMs: Long,
    val timedOut: Boolean,
    val truncated: Boolean,
    val blocked: Boolean = false,
    val blockedReason: String? = null,
    /** shell 在这次调用里被（重）拉起过 —— 上一轮的 cwd / 变量可能已丢。 */
    val sessionRestarted: Boolean = false,
    /** 非空表示这次根本没跑起来；调用方应当降级或告诉用户。 */
    val error: String? = null
)

enum class TaskState { RUNNING, EXITED, KILLED, TIMEOUT, FAILED }

data class TaskStartResult(
    val taskId: String,
    val pid: Int,
    val state: TaskState,
    /** 启动后头 ~1 秒的输出，用来立刻暴露 command not found 这类早死。 */
    val headOutput: String,
    val exitCode: Int? = null,
    val error: String? = null
)

data class TaskInfo(
    val taskId: String,
    val name: String,
    val command: String,
    val workDir: String,
    val state: TaskState,
    val exitCode: Int?,
    val pid: Int,
    val startedAt: Long,
    val durationMs: Long,
    val stdoutChars: Long,
    val stderrChars: Long,
    val droppedChars: Long,
    val logDir: String
)

data class TaskPoll(
    val taskId: String,
    val state: TaskState,
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    /** 下次 poll 传回来的游标，实现零重复的增量拉取。 */
    val nextStdout: Long,
    val nextStderr: Long,
    /** 因为环形缓冲滚掉而永久错过的字符数。 */
    val droppedStdout: Long,
    val droppedStderr: Long,
    val moreAvailable: Boolean,
    val finished: Boolean,
    val durationMs: Long
)

data class InitResult(
    val root: String,
    val created: List<String>,
    val skipped: List<String>,
    val readmePath: String,
    val error: String? = null
)

data class ResetResult(
    val deletedEntries: Int,
    val freedBytes: Long,
    val stoppedTasks: Int,
    val keptBinDir: String,
    val keptCommands: Int,
    val envCleared: Boolean,
    val error: String? = null
)

data class ContainerInfo(
    val folder: String,
    val root: String,
    val workDir: String,
    val tmpDir: String,
    val binDir: String,
    val execDir: String,
    val initialized: Boolean,
    val workEntries: Int,
    val workBytes: Long,
    val tmpBytes: Long,
    val logBytes: Long,
    val freeDiskBytes: Long,
    val walkTruncated: Boolean,
    val commands: List<String>,
    /** 值已掩码，可以直接给模型看。 */
    val env: Map<String, String>,
    val sessionAlive: Boolean,
    val sessionCwd: String,
    val sessionPid: Int,
    val sessionUptimeMs: Long,
    val tasks: List<TaskInfo>
)

data class SessionStatus(
    val alive: Boolean,
    val cwd: String,
    val pid: Int,
    val uptimeMs: Long,
    val error: String? = null
)

// =====================================================================
//  目录布局
// =====================================================================

/**
 * 一个容器的磁盘布局。
 *
 * ```
 * <root>/                 = LocalSandbox.projectRoot(folder)，和 read_file /
 *   ├── work/               write_file / list_directory 看到的是同一棵树，
 *   ├── tmp/                所以 AI 用 work/main.c 这种路径两边都通。
 *   ├── bin  -> <binDir>  （软链，best-effort；真正的目录是 BijiBootstrap.binDir）
 *   ├── .env              持久化环境变量（KEY=value，一行一条）
 *   ├── .state            cwd 等会话状态
 *   ├── .tasks/<id>/      后台任务的 pid / meta / stdout.log / stderr.log
 *   └── README.md         init 写的「这个容器里有什么」
 * ```
 *
 * [execDir] 单独开在**内部存储**（filesDir）下。原因很实际：projectRoot
 * 在 external files 上，绝大多数设备那层 FUSE/sdcardfs 是 noexec 的 ——
 * 在 work/ 里 `tcc -o hello hello.c` 能编译出来，却 exec 不动。内部存储
 * 没这个限制（targetSdk 28 也没有 W^X 强制，bootstrap/bin 就是这么跑起来
 * 的）。所以容器把它作为 `$BIJI_EXEC` 暴露出去，README 里明说：要跑自己
 * 编出来的二进制，先拷到 $BIJI_EXEC。
 */
class ContainerLayout(
    val root: File,
    val binDir: File,
    val execDir: File
) {
    val work: File get() = File(root, "work")
    val tmp: File get() = File(root, "tmp")
    val binLink: File get() = File(root, "bin")
    val envFile: File get() = File(root, ".env")
    val stateFile: File get() = File(root, ".state")
    val tasksDir: File get() = File(root, ".tasks")
    val readme: File get() = File(root, "README.md")
    val stdinFile: File get() = File(tmp, ".biji-stdin")

    fun mkdirs() {
        runCatching {
            root.mkdirs(); work.mkdirs(); tmp.mkdirs(); tasksDir.mkdirs()
            binDir.mkdirs(); execDir.mkdirs()
        }
    }

    /** 容器内允许写的绝对路径前缀，交给 [ContainerGuard]。 */
    fun writeRoots(): List<String> = listOf(
        canon(root), canon(execDir), canon(binDir)
    ).distinct()

    fun isInside(path: String): Boolean {
        val t = runCatching { File(path).canonicalPath }.getOrNull() ?: return false
        val r = canon(root)
        return t == r || t.startsWith("$r/")
    }

    private fun canon(f: File): String =
        runCatching { f.canonicalPath }.getOrDefault(f.absolutePath)

    /**
     * 容器的基础环境。长驻会话和后台任务共用这一份 —— 两边环境不一致
     * 是最难查的一类 bug（「我在 session 里能跑，job 里就 command not
     * found」）。
     */
    fun applyEnv(pb: ProcessBuilder) {
        val env = pb.environment()
        val existing = env["PATH"] ?: System.getenv("PATH") ?: "/system/bin:/system/xbin"
        env["PATH"] = listOf(binDir.absolutePath, execDir.absolutePath).joinToString(":") +
            ":" + existing
        env["HOME"] = root.absolutePath
        env["TMPDIR"] = tmp.absolutePath
        env["TMP"] = tmp.absolutePath
        env["LANG"] = "C.UTF-8"
        env["LC_ALL"] = "C.UTF-8"
        // 没有 PTY，TERM=dumb 让 ls / git / gcc 之类别吐 ANSI 转义 ——
        // 输出是给模型读的，不是给终端渲染的。
        env["TERM"] = "dumb"
        env["BIJI_CONTAINER"] = root.absolutePath
        env["BIJI_WORK"] = work.absolutePath
        env["BIJI_TMP"] = tmp.absolutePath
        env["BIJI_BIN"] = binDir.absolutePath
        env["BIJI_EXEC"] = execDir.absolutePath
        for ((k, v) in readEnv()) {
            if (ContainerSession.VALID_KEY.matches(k)) env[k] = v
        }
    }

    // ---- .env / .state -------------------------------------------------

    fun readEnv(): Map<String, String> = readKv(envFile)

    fun writeEnv(map: Map<String, String>) = writeKv(envFile, map)

    fun readState(): Map<String, String> = readKv(stateFile)

    fun writeState(patch: Map<String, String>) {
        val merged = readKv(stateFile).toMutableMap()
        merged.putAll(patch)
        writeKv(stateFile, merged)
    }

    private fun readKv(f: File): Map<String, String> = runCatching {
        if (!f.isFile) return@runCatching emptyMap<String, String>()
        f.readLines(Charsets.UTF_8).mapNotNull { line ->
            if (line.isBlank() || line.startsWith("#")) return@mapNotNull null
            val i = line.indexOf('=')
            if (i <= 0) return@mapNotNull null
            line.substring(0, i) to unescape(line.substring(i + 1))
        }.toMap()
    }.getOrDefault(emptyMap())

    private fun writeKv(f: File, map: Map<String, String>) {
        runCatching {
            f.parentFile?.mkdirs()
            f.writeText(
                map.entries.joinToString("\n") { (k, v) -> "$k=${escape(v)}" } + "\n",
                Charsets.UTF_8
            )
        }
    }

    private fun escape(v: String) =
        v.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r")

    private fun unescape(v: String): String {
        val sb = StringBuilder(v.length)
        var i = 0
        while (i < v.length) {
            val c = v[i]
            if (c == '\\' && i + 1 < v.length) {
                when (v[i + 1]) {
                    'n' -> { sb.append('\n'); i += 2; continue }
                    'r' -> { sb.append('\r'); i += 2; continue }
                    '\\' -> { sb.append('\\'); i += 2; continue }
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }
}

// =====================================================================
//  容器
// =====================================================================

/**
 * 一个会话一个「本地容器」：持久 shell + 后台任务 + 可重置的工作区。
 *
 * 设计立场（三条，决定了下面所有取舍）：
 *
 * 1. **它是运行时，不是安全边界。** 命令跑在 app 自己的 uid 下，Android
 *    的应用沙箱才是真正的墙。[ContainerGuard] 只拦毁灭性命令和明显往容
 *    器外写的意图，拦不住铁了心要绕的写法 —— 这一点必须对上层说清楚，
 *    不能让人误以为开了容器就万事大吉。
 *
 * 2. **不抛异常。** 每个 public 方法都把失败塞进返回值的 `error` 字段
 *    （或 `blocked`/`FAILED` 状态）。工具层照抄进 errorJson 即可，
 *    `run()` 永远不会因为容器炸掉而崩。
 *
 * 3. **无可变全局状态。** 每个容器一份实例状态，会话内串行靠 [Mutex]，
 *    任务表是 ConcurrentHashMap，跨容器零共享 —— 一个会话的长任务不会
 *    卡住另一个会话。
 *
 * 所有阻塞动作都在 `Dispatchers.IO` 上。
 */
class AiContainer internal constructor(
    val folder: String,
    val layout: ContainerLayout
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val session = ContainerSession(layout, scope)
    private val tasks = ContainerTasks(layout, scope)

    /** init / reset 这种会动目录结构的操作互斥；exec 不走它。 */
    private val structureMutex = Mutex()

    @Volatile private var usageCache: Pair<Long, Usage>? = null

    @Volatile var lastUsedAt: Long = System.currentTimeMillis()
        private set

    private fun touch() { lastUsedAt = System.currentTimeMillis() }

    // ---- 容器管理 ------------------------------------------------------

    /** 建目录 + 写 README。已存在的东西不覆盖，除非 [force]。 */
    suspend fun init(force: Boolean = false): InitResult = withContext(Dispatchers.IO) {
        touch()
        structureMutex.withLock {
            runCatching {
                val created = mutableListOf<String>()
                val skipped = mutableListOf<String>()
                for (d in listOf(layout.work, layout.tmp, layout.tasksDir)) {
                    if (d.isDirectory) skipped += d.name + "/" else if (d.mkdirs()) created += d.name + "/"
                }
                layout.binDir.mkdirs()
                layout.execDir.mkdirs()
                // bin 软链只是给人看的方便入口，PATH 里用的是绝对路径。
                // external 存储多半不支持 symlink，失败就算了，不报错。
                if (!layout.binLink.exists()) {
                    val ok = runCatching {
                        android.system.Os.symlink(
                            layout.binDir.absolutePath, layout.binLink.absolutePath
                        )
                        true
                    }.getOrDefault(false)
                    if (ok) created += "bin -> ${layout.binDir.absolutePath}"
                    else skipped += "bin（该文件系统不支持软链，用 \$BIJI_BIN 访问）"
                }
                if (!layout.envFile.exists()) { layout.writeEnv(emptyMap()); created += ".env" }
                else skipped += ".env"
                if (!layout.stateFile.exists()) {
                    layout.writeState(mapOf(ContainerSession.STATE_CWD to layout.work.absolutePath))
                    created += ".state"
                } else skipped += ".state"

                if (force || !layout.readme.isFile) {
                    layout.readme.writeText(renderReadme(), Charsets.UTF_8)
                    created += layout.readme.name
                } else skipped += layout.readme.name

                usageCache = null
                InitResult(
                    root = layout.root.absolutePath,
                    created = created,
                    skipped = skipped,
                    readmePath = layout.readme.absolutePath
                )
            }.getOrElse { e ->
                InitResult(
                    root = layout.root.absolutePath, created = emptyList(), skipped = emptyList(),
                    readmePath = layout.readme.absolutePath,
                    error = e.message ?: e.javaClass.simpleName
                )
            }
        }
    }

    /**
     * 清空 work/ 和 tmp/ 以及任务日志，**保留已装的 bin 目录**。
     *
     * bin 本来就在 BijiBootstrap.binDir（app 内部存储）里，容器根下只有
     * 一个软链，所以「保留」是天然的：这里既不删软链也不碰真目录。装一
     * 次 gcc 十几 MB，reset 一次就得重下的话没人敢按。
     */
    suspend fun reset(keepEnv: Boolean = true): ResetResult = withContext(Dispatchers.IO) {
        touch()
        structureMutex.withLock {
            runCatching {
                val stopped = tasks.stopAll()
                tasks.forget()
                // 先掐掉可能正在跑的前台命令，否则 close() 要排队等它超时。
                session.interruptForeground()
                session.close()

                var entries = 0
                var bytes = 0L
                for (dir in listOf(layout.work, layout.tmp, layout.tasksDir)) {
                    val kids = dir.listFiles() ?: continue
                    for (f in kids) {
                        val u = measure(f, MAX_WALK_ENTRIES)
                        entries += u.entries
                        bytes += u.bytes
                        f.deleteRecursively()
                    }
                }
                layout.mkdirs()
                if (!keepEnv) layout.writeEnv(emptyMap())
                layout.writeState(mapOf(ContainerSession.STATE_CWD to layout.work.absolutePath))
                runCatching { layout.readme.writeText(renderReadme(), Charsets.UTF_8) }
                usageCache = null

                ResetResult(
                    deletedEntries = entries,
                    freedBytes = bytes,
                    stoppedTasks = stopped,
                    keptBinDir = layout.binDir.absolutePath,
                    keptCommands = installedCommands().size,
                    envCleared = !keepEnv
                )
            }.getOrElse { e ->
                ResetResult(
                    deletedEntries = 0, freedBytes = 0L, stoppedTasks = 0,
                    keptBinDir = layout.binDir.absolutePath, keptCommands = 0,
                    envCleared = false, error = e.message ?: e.javaClass.simpleName
                )
            }
        }
    }

    /** 磁盘占用 / 已装命令 / 运行中的任务 / 会话状态，一次给全。 */
    suspend fun info(refresh: Boolean = false): ContainerInfo = withContext(Dispatchers.IO) {
        touch()
        val usage = usage(refresh)
        ContainerInfo(
            folder = folder,
            root = layout.root.absolutePath,
            workDir = layout.work.absolutePath,
            tmpDir = layout.tmp.absolutePath,
            binDir = layout.binDir.absolutePath,
            execDir = layout.execDir.absolutePath,
            initialized = layout.work.isDirectory && layout.readme.isFile,
            workEntries = usage.entries,
            workBytes = usage.bytes,
            tmpBytes = usage.tmpBytes,
            logBytes = usage.logBytes,
            freeDiskBytes = runCatching { layout.root.usableSpace }.getOrDefault(0L),
            walkTruncated = usage.truncated,
            commands = installedCommands(),
            env = maskedEnv(),
            sessionAlive = session.alive,
            sessionCwd = session.cwd.ifBlank { layout.work.absolutePath },
            sessionPid = session.shellPid,
            sessionUptimeMs = if (session.alive) System.currentTimeMillis() - session.startedAt else 0L,
            tasks = tasks.list(includeFinished = true)
        )
    }

    // ---- 持久 shell ----------------------------------------------------

    /**
     * 在持久会话里跑一条命令。cwd、export 的变量、定义过的 shell 函数
     * 都会留到下一次调用。
     *
     * [cwd] 只影响这一条命令（相对 work/ 或绝对路径）；不给就用会话
     * 当前的 cwd。[timeoutMs] 到点会杀掉这条命令的子孙进程但**保住会话
     * 本身**，超过 [MAX_EXEC_TIMEOUT_MS] 的活儿请用 [startTask]。
     */
    suspend fun exec(
        command: String,
        cwd: String? = null,
        timeoutMs: Long = DEFAULT_EXEC_TIMEOUT_MS,
        stdin: String? = null,
        maxOutputChars: Int = DEFAULT_MAX_OUTPUT
    ): ExecResult {
        touch()
        val blocked = guard(command)
        if (blocked != null) return blocked
        return runCatching {
            layout.mkdirs()
            session.run(
                command = command,
                cwdOverride = cwd?.takeIf { it.isNotBlank() }?.let { resolveCwd(it) },
                timeoutMs = timeoutMs.coerceIn(MIN_EXEC_TIMEOUT_MS, MAX_EXEC_TIMEOUT_MS),
                stdin = stdin,
                maxOutputChars = maxOutputChars.coerceIn(512, HARD_MAX_OUTPUT)
            )
        }.getOrElse { e ->
            ExecResult(
                command = command, stdout = "", stderr = "", exitCode = -1,
                cwd = session.cwd, durationMs = 0L, timedOut = false, truncated = false,
                error = e.message ?: e.javaClass.simpleName
            )
        }
    }

    suspend fun sessionStatus(): SessionStatus {
        touch()
        return SessionStatus(
            alive = session.alive,
            cwd = session.cwd.ifBlank { layout.work.absolutePath },
            pid = session.shellPid,
            uptimeMs = if (session.alive) System.currentTimeMillis() - session.startedAt else 0L
        )
    }

    /** 重启 shell（cwd / .env 会按持久化的内容复原），丢掉函数和未导出变量。 */
    suspend fun restartSession(): SessionStatus {
        touch()
        val ok = runCatching { session.restart() }.getOrDefault(false)
        val s = sessionStatus()
        return if (ok) s else s.copy(error = "shell 无法启动")
    }

    suspend fun closeSession(): SessionStatus {
        touch()
        runCatching { session.close() }
        return sessionStatus()
    }

    // ---- 环境变量 ------------------------------------------------------

    /**
     * 设置 / 删除持久环境变量。写进 .env（重启会话后仍在），会话活着
     * 时立刻 export 生效。返回**掩码后**的全量 env，可直接给模型。
     */
    suspend fun updateEnv(
        set: Map<String, String> = emptyMap(),
        unset: List<String> = emptyList()
    ): Map<String, String> = withContext(Dispatchers.IO) {
        touch()
        runCatching {
            val current = layout.readEnv().toMutableMap()
            for ((k, v) in set) if (ContainerSession.VALID_KEY.matches(k)) current[k] = v
            for (k in unset) current.remove(k)
            layout.writeEnv(current)
            session.applyEnv(set.filterKeys { ContainerSession.VALID_KEY.matches(it) }, unset)
        }
        maskedEnv()
    }

    fun maskedEnv(): Map<String, String> =
        layout.readEnv().mapValues { (k, v) -> if (isSecret(k)) mask(v) else v }

    private fun isSecret(key: String): Boolean {
        val u = key.uppercase()
        return SECRET_HINTS.any { u.contains(it) }
    }

    private fun mask(v: String): String =
        if (v.length <= 4) "***" else v.take(2) + "***" + "(${v.length})"

    // ---- 后台任务 ------------------------------------------------------

    suspend fun startTask(
        command: String,
        name: String? = null,
        cwd: String? = null,
        maxRuntimeMs: Long = DEFAULT_TASK_RUNTIME_MS,
        headWaitMs: Long = DEFAULT_HEAD_WAIT_MS
    ): TaskStartResult {
        touch()
        val blocked = guard(command)
        if (blocked != null) {
            return TaskStartResult(
                taskId = "", pid = -1, state = TaskState.FAILED, headOutput = "",
                error = "拒绝执行：${blocked.blockedReason}"
            )
        }
        return runCatching {
            layout.mkdirs()
            tasks.start(
                command = command,
                name = name,
                cwd = cwd?.takeIf { it.isNotBlank() }?.let { resolveCwd(it) },
                maxRuntimeMs = maxRuntimeMs.coerceIn(1_000L, MAX_TASK_RUNTIME_MS),
                headWaitMs = headWaitMs
            )
        }.getOrElse { e ->
            TaskStartResult(
                taskId = "", pid = -1, state = TaskState.FAILED, headOutput = "",
                error = e.message ?: e.javaClass.simpleName
            )
        }
    }

    fun pollTask(
        taskId: String,
        fromStdout: Long = 0L,
        fromStderr: Long = 0L,
        maxChars: Int = DEFAULT_MAX_OUTPUT
    ): TaskPoll? {
        touch()
        return runCatching {
            tasks.poll(taskId, fromStdout, fromStderr, maxChars.coerceIn(256, HARD_MAX_OUTPUT))
        }.getOrNull()
    }

    suspend fun stopTask(taskId: String): TaskInfo? {
        touch()
        return runCatching { tasks.stop(taskId) }.getOrNull()
    }

    fun listTasks(includeFinished: Boolean = true): List<TaskInfo> =
        runCatching { tasks.list(includeFinished) }.getOrDefault(emptyList())

    // ---- 收摊 ----------------------------------------------------------

    suspend fun shutdown() {
        runCatching { tasks.stopAll() }
        runCatching { session.interruptForeground() }
        runCatching { session.close() }
        runCatching { scope.cancel() }
    }

    // ---- 内部 ----------------------------------------------------------

    private fun guard(command: String): ExecResult? {
        val reason = ContainerGuard.check(command, layout.writeRoots()) ?: return null
        return ExecResult(
            command = command, stdout = "", stderr = "拒绝执行：$reason",
            exitCode = -1, cwd = session.cwd.ifBlank { layout.work.absolutePath },
            durationMs = 0L, timedOut = false, truncated = false,
            blocked = true, blockedReason = reason
        )
    }

    /** 相对路径按 work/ 解析；绝对路径必须落在容器内，否则退回 work/。 */
    private fun resolveCwd(p: String): String {
        val f = if (p.startsWith("/")) File(p) else File(layout.work, p)
        val canon = runCatching { f.canonicalPath }.getOrDefault(f.absolutePath)
        return if (layout.isInside(canon)) canon else layout.work.absolutePath
    }

    private fun installedCommands(): List<String> =
        runCatching {
            layout.binDir.listFiles()
                ?.filter { it.canExecute() && !it.name.endsWith(".part") }
                ?.map { it.name }
                ?.sorted()
                .orEmpty()
        }.getOrDefault(emptyList())

    private class Usage(
        val entries: Int,
        val bytes: Long,
        val tmpBytes: Long,
        val logBytes: Long,
        val truncated: Boolean
    )

    /** 目录统计缓存 30 秒 —— info 可能被连着问好几次，几万个文件走一次
     *  walk 就够了。缓存是不可变值 + @Volatile 引用，最坏情况只是两个
     *  协程各走一遍，不会读到半成品。 */
    private fun usage(refresh: Boolean): Usage {
        val now = System.currentTimeMillis()
        if (!refresh) {
            usageCache?.let { (at, u) -> if (now - at < USAGE_TTL_MS) return u }
        }
        val work = measure(layout.work, MAX_WALK_ENTRIES)
        val tmp = measure(layout.tmp, MAX_WALK_ENTRIES)
        val logs = measure(layout.tasksDir, MAX_WALK_ENTRIES)
        val u = Usage(
            entries = work.entries,
            bytes = work.bytes,
            tmpBytes = tmp.bytes,
            logBytes = logs.bytes,
            truncated = work.truncated || tmp.truncated || logs.truncated
        )
        usageCache = now to u
        return u
    }

    private class Measured(val entries: Int, val bytes: Long, val truncated: Boolean)

    /** 迭代式遍历（不递归，免得深目录爆栈），带条目上限。 */
    private fun measure(root: File, maxEntries: Int): Measured {
        if (!root.exists()) return Measured(0, 0L, false)
        if (root.isFile) return Measured(1, root.length(), false)
        var entries = 0
        var bytes = 0L
        val stack = ArrayDeque<File>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            if (entries >= maxEntries) return Measured(entries, bytes, true)
            val d = stack.removeLast()
            val kids = d.listFiles() ?: continue
            for (f in kids) {
                entries++
                if (f.isDirectory) stack.addLast(f) else bytes += f.length()
                if (entries >= maxEntries) return Measured(entries, bytes, true)
            }
        }
        return Measured(entries, bytes, false)
    }

    private fun renderReadme(): String {
        val cmds = installedCommands()
        return buildString {
            appendLine("# biji 本地容器")
            appendLine()
            appendLine("这是当前会话专属的运行环境，重启 app 后依然在。")
            appendLine()
            appendLine("## 目录")
            appendLine()
            appendLine("| 路径 | 变量 | 用途 |")
            appendLine("| --- | --- | --- |")
            appendLine("| `${layout.work.absolutePath}` | `\$BIJI_WORK` | 项目区，放代码和产物。`read_file` / `write_file` 用 `work/xxx` 也能访问同一批文件 |")
            appendLine("| `${layout.tmp.absolutePath}` | `\$BIJI_TMP` / `\$TMPDIR` | 临时文件，reset 会清 |")
            appendLine("| `${layout.binDir.absolutePath}` | `\$BIJI_BIN` | 已装的命令行工具，已在 PATH 上；reset **不会**清 |")
            appendLine("| `${layout.execDir.absolutePath}` | `\$BIJI_EXEC` | 可执行区（内部存储）。自己编出来的二进制要跑，先拷到这里 |")
            appendLine()
            appendLine("## 注意")
            appendLine()
            appendLine("- `work/` 在共享存储上，多数设备挂载为 noexec：**在那里 chmod +x 也执行不了**。")
            appendLine("  编译产物请 `cp foo \$BIJI_EXEC/ && \$BIJI_EXEC/foo`。")
            appendLine("- shell 是持久的：`cd`、`export`、定义的函数、`source venv/bin/activate` 都会留到下一条命令。")
            appendLine("- 超过 ${MAX_EXEC_TIMEOUT_MS / 1000} 秒的活儿用后台任务跑，别占着会话。")
            appendLine("- 环境变量写在 `.env`，会话重启后自动恢复。")
            appendLine("- 没有 root，也没有 PTY：交互式命令（vi、top、需要按 y 确认的）不要用，改用非交互参数。")
            appendLine()
            appendLine("## 当前可用命令 (${cmds.size})")
            appendLine()
            if (cmds.isEmpty()) {
                appendLine("（空 —— 先用 install_package / list_packages 装 toybox 等基础工具）")
            } else {
                appendLine("```")
                appendLine(cmds.joinToString(" "))
                appendLine("```")
            }
        }
    }

    companion object {
        const val DEFAULT_EXEC_TIMEOUT_MS = 30_000L
        const val MIN_EXEC_TIMEOUT_MS = 1_000L
        const val MAX_EXEC_TIMEOUT_MS = 120_000L
        const val DEFAULT_TASK_RUNTIME_MS = 900_000L
        const val MAX_TASK_RUNTIME_MS = 3_600_000L
        const val DEFAULT_HEAD_WAIT_MS = 1_200L
        const val DEFAULT_MAX_OUTPUT = 12_000
        const val HARD_MAX_OUTPUT = 200_000
        private const val USAGE_TTL_MS = 30_000L
        private const val MAX_WALK_ENTRIES = 50_000

        private val SECRET_HINTS =
            listOf("KEY", "TOKEN", "SECRET", "PASSWORD", "PASSWD", "CREDENTIAL", "AUTH")
    }
}
