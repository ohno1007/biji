package com.biji.notes.sandbox

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.Writer

/**
 * 长驻 shell 会话 —— 容器的「有状态」那一半。
 *
 * ## 为什么选长驻进程 + 哨兵，而不是「每次新起进程 + 从 .state/.env 恢复」
 *
 * 方案 (b)（每次新 sh，把 cwd/env 存盘再恢复）只能救回 **cwd 和导出过的
 * 变量**。真实工作流里丢掉的恰恰是别的东西：shell 函数、alias、`set -e`
 * / `set -o pipefail`、未 export 的局部变量、`source venv/bin/activate` 之
 * 后那一坨（它同时改 PATH、定义 deactivate 函数、设 VIRTUAL_ENV）、以及
 * `trap`。而且每条命令都要付一次 fork+exec 的钱。
 *
 * 所以主路径走 (a)：一个 `sh` 从头活到尾，命令通过 stdin 喂进去，用哨兵
 * 行切分每条命令的输出并带回 exit code。(b) 不是被丢掉，而是降级成
 * **持久化层**：每条命令结束都把 cwd 写进 .state，显式设置的变量写进
 * .env；进程被 Android 杀掉（或命令把 shell 自己搞死）之后重启会话时按
 * 它们复原。两者叠加 = 活着的时候什么都不丢，死了之后还能捡回大半。
 *
 * ## 不死锁的读法
 *
 * 三条铁律：
 *  1. stdout 和 stderr 各有一个**常驻**协程在读，从会话启动读到进程结束。
 *     绝不在等命令结束时才去读 —— 那是经典死锁：管道 64 KB 缓冲写满，
 *     子进程阻塞在 write()，我们阻塞在 waitFor()，两边一起躺平。
 *  2. 不靠 EOF 判断命令结束（长驻进程根本不会给 EOF），只认哨兵。副作用
 *     是 `cmd &` 这种后台任务不会卡住我们 —— 它把管道拿着不放也无所谓。
 *  3. 命令的 stdin 恒定重定向自一个文件（调用方给的 stdin 内容，或空文
 *     件）。否则一句 `cat` 就会把我们后面写进去的哨兵当成输入吃掉，
 *     控制通道直接串味。
 *
 * 单个会话内命令必须串行 —— stdin 是一条独木桥。用 [mutex] 保证；多个
 * 会话之间互不相干（各自一把锁，不存在全局锁）。
 */
internal class ContainerSession(
    private val layout: ContainerLayout,
    private val scope: CoroutineScope
) {

    private val mutex = Mutex()

    @Volatile private var process: Process? = null
    @Volatile private var writer: Writer? = null
    @Volatile private var stdout = StreamBuffer()
    @Volatile private var stderr = StreamBuffer()
    @Volatile private var signal = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var pumps: List<Job> = emptyList()
    @Volatile private var startFailure: String? = null

    @Volatile var shellPid: Int = -1
        private set

    @Volatile var cwd: String = ""
        private set

    @Volatile var startedAt: Long = 0L
        private set

    private var seq: Long = 0L

    val alive: Boolean get() = process?.isAlive == true

    // ---- 对外入口 ------------------------------------------------------

    suspend fun run(
        command: String,
        cwdOverride: String?,
        timeoutMs: Long,
        stdin: String?,
        maxOutputChars: Int
    ): ExecResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val restarted = ensureStarted()
            val fail = startFailure
            if (fail != null) {
                return@withLock ExecResult(
                    command = command, stdout = "", stderr = "", exitCode = -1,
                    cwd = cwd, durationMs = 0L, timedOut = false, truncated = false,
                    sessionRestarted = restarted, error = fail
                )
            }
            val script = buildString {
                if (!cwdOverride.isNullOrBlank()) {
                    append("cd ")
                    append(ContainerProc.shellQuote(cwdOverride))
                    append(" || exit 1\n")
                }
                append(command)
            }
            val r = exec(script, timeoutMs, stdin, maxOutputChars)
            r.copy(command = command, sessionRestarted = restarted || r.sessionRestarted)
        }
    }

    /** 会话活着时立刻生效的 export / unset；会话没起来就只落盘（由调用方做）。 */
    suspend fun applyEnv(set: Map<String, String>, unset: List<String>) {
        if (set.isEmpty() && unset.isEmpty()) return
        val script = buildString {
            for ((k, v) in set) {
                if (!VALID_KEY.matches(k)) continue
                append("export ").append(k).append('=').append(ContainerProc.shellQuote(v)).append('\n')
            }
            for (k in unset) {
                if (!VALID_KEY.matches(k)) continue
                append("unset ").append(k).append('\n')
            }
            append(':')
        }
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (process?.isAlive == true) exec(script, 5_000L, null, 2_000)
                Unit
            }
        }
    }

    /**
     * 掐掉当前前台命令（保住 shell）。reset / shutdown 之前先叫一下，
     * 免得为了拿会话锁干等一条还剩 100 秒超时的命令。
     */
    suspend fun interruptForeground() {
        val pid = shellPid
        if (pid > 0) runCatching { ContainerProc.killTree(pid, includeSelf = false) }
    }

    suspend fun restart(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            stop()
            ensureStarted()
            startFailure == null
        }
    }

    suspend fun close() = withContext(Dispatchers.IO) {
        mutex.withLock { stop() }
    }

    // ---- 生命周期 ------------------------------------------------------

    /** 返回 true 表示这次调用真的（重新）拉起了 shell。 */
    private suspend fun ensureStarted(): Boolean {
        if (process?.isAlive == true) return false
        stop()
        start()
        if (startFailure != null) return true
        // 握手：跑一条空命令，顺带把 shell 自己的 pid 和 cwd 拿回来，
        // 也验证哨兵协议在这台设备的 sh 上确实能用。
        val hs = exec(":", HANDSHAKE_TIMEOUT_MS, null, 4_000)
        if (hs.timedOut || hs.error != null) {
            startFailure = "shell 握手失败（${hs.error ?: "超时"}）"
            stop()
        }
        return true
    }

    private fun start() {
        startFailure = null
        layout.mkdirs()
        val restoreCwd = layout.readState()[STATE_CWD]
            ?.takeIf { it.isNotBlank() && layout.isInside(it) && java.io.File(it).isDirectory }
            ?: layout.work.absolutePath

        val pb = ProcessBuilder("sh")
            .directory(layout.work)
            .redirectErrorStream(false)
        layout.applyEnv(pb)
        val p = try {
            pb.start()
        } catch (e: Throwable) {
            startFailure = "无法启动 shell：${e.message ?: e.javaClass.simpleName}"
            return
        }
        process = p
        stdout = StreamBuffer()
        stderr = StreamBuffer()
        signal = Channel(Channel.CONFLATED)
        writer = OutputStreamWriter(p.outputStream, Charsets.UTF_8)
        pumps = listOf(
            pump(p.inputStream, stdout, signal),
            pump(p.errorStream, stderr, signal)
        )
        startedAt = System.currentTimeMillis()
        cwd = restoreCwd

        // 恢复层：cwd + 上次显式设置过的环境变量。命令写不进去（管道已断）
        // 也不抛 —— 后面的握手会超时，走重启/报错路径。
        val prelude = buildString {
            append("cd ").append(ContainerProc.shellQuote(restoreCwd))
            append(" 2>/dev/null || cd ").append(ContainerProc.shellQuote(layout.work.absolutePath))
            append('\n')
            for ((k, v) in layout.readEnv()) {
                if (!VALID_KEY.matches(k)) continue
                append("export ").append(k).append('=').append(ContainerProc.shellQuote(v)).append('\n')
            }
        }
        runCatching {
            writer?.write(prelude)
            writer?.flush()
        }
    }

    private fun stop() {
        runCatching { writer?.close() }
        runCatching { process?.destroyForcibly() }
        pumps.forEach { it.cancel() }
        runCatching { signal.close() }
        pumps = emptyList()
        writer = null
        process = null
        shellPid = -1
    }

    private fun pump(stream: InputStream, sink: StreamBuffer, sig: Channel<Unit>): Job =
        scope.launch(Dispatchers.IO) {
            // InputStreamReader 自己会攒住被切断的多字节序列，不会吐半个汉字。
            val reader = InputStreamReader(stream, Charsets.UTF_8)
            val buf = CharArray(8 * 1024)
            try {
                while (true) {
                    val n = reader.read(buf)
                    if (n <= 0) break
                    sink.append(String(buf, 0, n))
                    sig.trySend(Unit)
                }
            } catch (_: Throwable) {
                // 进程被杀 / 管道关闭都走这里，静默收工。
            } finally {
                sig.trySend(Unit)   // 唤醒可能在等哨兵的人，让它去看到进程已死
            }
        }

    // ---- 哨兵协议 ------------------------------------------------------

    /**
     * 发一条命令并等它的哨兵。调用方必须已持有 [mutex]。
     *
     * 写进 shell 的脚本长这样（token 每条命令重随一次）：
     * ```
     * { <命令> } < <stdin 文件>
     * __biji_rc=$?
     * echo "<SOH>B2<STX>token<ETX>" 1>&2      # stderr 哨兵先发
     * echo "<SOH>B1<STX>token<STX>$rc<STX>$$<STX>$PWD<ETX>"
     * ```
     * 几个细节：
     *  - 用 `{ }` 而不是 `( )`：花括号不 fork 子 shell，命令里的 `cd`、
     *    `export`、函数定义才会留在会话里。
     *  - 控制字符 SOH/STX/ETX + 随机 token 双保险，命令输出里撞上的概率
     *    可以忽略；stdout / stderr 用不同标签（B1/B2），万一用户在会话里
     *    执行了 `exec 2>&1`，stdout 的扫描也不会被 stderr 哨兵带偏。
     *  - stderr 哨兵先发，等 stdout 哨兵到手时它基本已经在缓冲里了。
     */
    private suspend fun exec(
        script: String,
        timeoutMs: Long,
        stdin: String?,
        maxOutputChars: Int
    ): ExecResult {
        val p = process
        val w = writer
        if (p == null || w == null || !p.isAlive) {
            return ExecResult(
                command = script, stdout = "", stderr = "", exitCode = -1, cwd = cwd,
                durationMs = 0L, timedOut = false, truncated = false,
                error = startFailure ?: "shell 未运行"
            )
        }
        val token = "${System.nanoTime().toString(36)}${(seq++).toString(36)}"
        val outMarker = "${SOH}B1$STX$token"
        val errMarker = "${SOH}B2$STX$token"

        val stdinFile = layout.stdinFile
        runCatching {
            stdinFile.parentFile?.mkdirs()
            stdinFile.writeText(stdin.orEmpty(), Charsets.UTF_8)
        }

        // 上一条命令超时后残留的尾巴（或后台任务的零星输出）不该算进这条
        // 命令的结果里，丢掉但记个数。
        val leftover = stdout.drain().length + stderr.drain().length

        val d = '$'
        val payload = buildString {
            append("{\n").append(script).append("\n} < ")
            append(ContainerProc.shellQuote(stdinFile.absolutePath)).append('\n')
            append("__biji_rc=$d?\n")
            append("echo \"$errMarker$ETX\" 1>&2\n")
            append("echo \"$outMarker$STX${d}{__biji_rc}$STX$d$d$STX${d}{PWD}$ETX\"\n")
        }
        val started = System.currentTimeMillis()
        val wrote = runCatching {
            w.write(payload)
            w.flush()
        }.isSuccess
        if (!wrote) {
            return ExecResult(
                command = script, stdout = "", stderr = "", exitCode = -1, cwd = cwd,
                durationMs = System.currentTimeMillis() - started, timedOut = false,
                truncated = false, error = "写入 shell stdin 失败（会话已断）"
            )
        }

        var timedOut = false
        var restarted = false
        var hit = awaitMarker(stdout, outMarker, timeoutMs)
        if (hit == null) {
            // 分两种情况，不能混为一谈：
            //
            // (1) shell 自己没了。`exit` / `kill $$` / OOM killer 都会这样，
            //     而且 `{ }` 不 fork，一句 exit 就把会话带走。awaitMarker 靠
            //     process.isAlive 立刻发现，不会干等满 timeoutMs。这不是超时，
            //     报成超时会让模型以为「命令太慢」而去加时间，方向全错。
            // (2) 真超时。这时只收前台命令的子孙进程，**保住 shell 本身** ——
            //     否则「持久会话」名存实亡。子进程一死，shell 会继续执行
            //     __biji_rc 那几行，哨兵随即到达，这就是重新对齐。
            val shellDied = process?.isAlive != true
            if (!shellDied) {
                timedOut = true
                ContainerProc.killTree(shellPid, includeSelf = false)
                hit = awaitMarker(stdout, outMarker, RESYNC_GRACE_MS)
            }
            if (hit == null) {
                // 要么 shell 死了，要么对不齐（卡在未闭合的引号里之类），
                // 都只能重启。cwd / 变量按 .state / .env 复原。
                val tailOut = stdout.drain()
                val tailErr = stderr.drain()
                stop()
                start()
                restarted = true
                return ExecResult(
                    command = script,
                    stdout = clip(tailOut, maxOutputChars),
                    stderr = clip(tailErr, maxOutputChars),
                    exitCode = -1, cwd = cwd,
                    durationMs = System.currentTimeMillis() - started,
                    timedOut = !shellDied,
                    truncated = tailOut.length > maxOutputChars,
                    sessionRestarted = true,
                    error = if (shellDied)
                        "shell 进程已退出（命令里可能有 exit / kill \$\$），已重启会话；" +
                            "cwd 和 .env 里的变量已复原，但函数和未导出变量丢了"
                    else
                        "命令超时且会话无法重新对齐，已重启 shell（cwd / 变量按 .state/.env 恢复）"
                )
            }
        }
        // stderr 哨兵正常情况下已经在缓冲里；给一点点宽限就够。会话里
        // 执行过 `exec 2>&1` 的话它永远不会来，等满 400ms 后按现有内容收。
        val errHit = awaitMarker(stderr, errMarker, STDERR_GRACE_MS)
        val errText = errHit?.before ?: stderr.drain()

        val fields = hit.payload.split(STX)
        val rc = fields.getOrNull(2)?.trim()?.toIntOrNull() ?: -1
        fields.getOrNull(3)?.trim()?.toIntOrNull()?.let { shellPid = it }
        val newCwd = fields.getOrNull(4)?.trim()
        if (!newCwd.isNullOrBlank()) {
            cwd = newCwd
            layout.writeState(mapOf(STATE_CWD to newCwd, STATE_PID to shellPid.toString()))
        }

        val outDropped = stdout.takeDropped()
        val errDropped = stderr.takeDropped()
        val rawOut = hit.before
        val rawErr = errText
        val truncated = rawOut.length > maxOutputChars || rawErr.length > maxOutputChars ||
            outDropped > 0 || errDropped > 0
        val note = buildString {
            if (outDropped > 0 || errDropped > 0) {
                append("\n…[会话缓冲上限 ${StreamBuffer.HARD_CAP} 字符，丢弃了最早的 ")
                append(outDropped + errDropped).append(" 字符]")
            }
            if (leftover > 0) append("\n…[丢弃了上一条命令残留的 $leftover 字符]")
        }
        return ExecResult(
            command = script,
            stdout = clip(rawOut, maxOutputChars) + note,
            stderr = clip(rawErr, maxOutputChars),
            exitCode = if (timedOut) TIMEOUT_EXIT else rc,
            cwd = cwd,
            durationMs = System.currentTimeMillis() - started,
            timedOut = timedOut,
            truncated = truncated,
            sessionRestarted = restarted
        )
    }

    private class Hit(val before: String, val payload: String)

    private suspend fun awaitMarker(buf: StreamBuffer, marker: String, timeoutMs: Long): Hit? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            buf.consumeMarker(marker)?.let { return it }
            val remain = deadline - System.currentTimeMillis()
            if (remain <= 0) return null
            if (process?.isAlive != true) {
                // 进程没了，给 pump 一点收尾时间再看最后一眼。
                runCatching { withTimeoutOrNull(120L) { signal.receive() } }
                return buf.consumeMarker(marker)
            }
            // 200ms 封顶：即使某次唤醒信号被别人取走（stdout / stderr 两个
            // 等待共用一条 conflated 通道），也不会永远睡下去。
            runCatching { withTimeoutOrNull(remain.coerceAtMost(POLL_MS)) { signal.receive() } }
        }
    }

    private fun clip(s: String, max: Int): String =
        if (s.length <= max) s
        else s.take(max) + "\n…[输出截断，本条共 ${s.length} 字符]"

    /**
     * 一条流的滚动缓冲。加了硬上限：`yes` 这类命令一秒能吐几十 MB，
     * 没上限的话在等哨兵的过程中直接把堆吃穿。超限丢最早的部分并计数。
     */
    private class StreamBuffer {
        private val sb = StringBuilder()
        private var dropped = 0L

        @Synchronized fun append(s: String) {
            sb.append(s)
            if (sb.length > HARD_CAP) {
                val cut = sb.length - HARD_CAP
                sb.delete(0, cut)
                dropped += cut
            }
        }

        @Synchronized fun takeDropped(): Long {
            val d = dropped
            dropped = 0L
            return d
        }

        @Synchronized fun consumeMarker(marker: String): Hit? {
            val s = sb.indexOf(marker)
            if (s < 0) return null
            val e = sb.indexOf(ETX.toString(), s + marker.length)
            if (e < 0) return null
            val before = sb.substring(0, s)
            val payload = sb.substring(s, e)
            sb.delete(0, e + 1)
            return Hit(before, payload)
        }

        @Synchronized fun drain(): String {
            val r = sb.toString()
            sb.setLength(0)
            return r
        }

        companion object {
            const val HARD_CAP = 1_000_000
        }
    }

    companion object {
        private const val SOH = ''
        private const val STX = ''
        private const val ETX = ''

        private const val POLL_MS = 200L
        private const val STDERR_GRACE_MS = 400L
        private const val RESYNC_GRACE_MS = 3_000L
        private const val HANDSHAKE_TIMEOUT_MS = 8_000L

        /** 超时命令的 exit code —— 和 128+SIGKILL 对齐，方便模型识别。 */
        const val TIMEOUT_EXIT = 137

        const val STATE_CWD = "cwd"
        const val STATE_PID = "pid"

        val VALID_KEY = Regex("""^[A-Za-z_][A-Za-z0-9_]*$""")
    }
}
