package com.biji.notes.sandbox

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import com.biji.notes.nativebridge.NativeGate
import com.biji.notes.nativebridge.NativePty
import com.biji.notes.nativebridge.PtyStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

// =====================================================================
//  对 NativePty 的假设（另一个 agent 在同时写 cpp/pty.cpp + NativePty.kt）
//
//  所有 native 调用都收拢在文件末尾的 private object [Pty] 里，集成时对不上
//  只改那一处，不用翻整个会话逻辑。假设的 API 形状：
//
//    NativePty.available: Boolean            // .so 加载成功
//    NativePty.selfTest(): Boolean           // 会 fork，别在主线程调
//    NativePty.start(cmd, cwd, argv, envp, rows, cols, cellW, cellH): PtyStart
//        PtyStart { masterFd: Int, pid: Int, error: Int, ok: Boolean }
//    NativePty.adoptMaster(fd): ParcelFileDescriptor?   // 成功后 fd 所有权转移
//    NativePty.readMaster(fd, buf): Int      // >0 字节数；0 = EIO/EBADF（正常
//                                            //   终止，pty 上子进程退出就是 EIO
//                                            //   不是 EOF）；<0 = -errno
//    NativePty.writeMaster(fd, bytes): Int   // 处理短写和 EINTR
//    NativePty.setWinSize(pfd, rows, cols, cellW, cellH): Int  // 0 ok，<0 失败
//    NativePty.waitFor(pid): Int             // 0..255 退出码 / -signo / WAIT_FAILED
//    NativePty.signalGroup(pid, sig): Boolean// kill(-pid, sig)
//    NativePty.close(fd): Int
//    NativePty.describeExit(code): String    // 给用户看的中文（含 SIGKILL 那段
//                                            //   phantom process killer 的解释）
//    NativePty.errorName(code): String
//    NativePty.ERR_*, WAIT_FAILED, EXIT_EXEC_FAILED
//
//  三条契约（native 侧保证，这里依赖）：
//   1. 永不抛异常，失败一律负错误码 —— 所以下面没有一处 try/catch 兜 JNI；
//   2. 无全局状态，可并发，fd/pid 的所有权归调用方；
//   3. rows/cols 在 fork 前就写进内核，子进程一出生尺寸就是对的。
// =====================================================================

/**
 * pty 读到的字节往哪儿去。
 *
 * `terminal/TerminalEmulator` 实现它即可 —— 会话层故意不认识模拟器的具体类型：
 * 一来两边可以并行开发，二来降级路径（无 PTY）能把管道输出喂进同一个口子，
 * UI 不必区分。
 *
 * **[append] 的 [bytes] 是会话复用的缓冲区**，返回之后内容会被覆写，实现方要
 * 自己拷走需要留存的部分。调用是串行的（会话内部加了锁），但**不保证**在主线程。
 */
interface TerminalSink {
    fun append(bytes: ByteArray, off: Int, len: Int)

    /** 行列数变了。会话保证先调它、再对 pty 下 TIOCSWINSZ。 */
    fun resize(cols: Int, rows: Int)
}

/** 没接模拟器时用（测试 / 无头场景），字节直接丢掉。 */
object DiscardSink : TerminalSink {
    override fun append(bytes: ByteArray, off: Int, len: Int) = Unit
    override fun resize(cols: Int, rows: Int) = Unit
}

sealed interface TerminalState {
    data object Idle : TerminalState
    data object Starting : TerminalState

    /** [pty] 为 false 表示走的是降级管道路径：没颜色、没真 Ctrl-C。 */
    data class Running(val pid: Int, val pty: Boolean) : TerminalState

    /** [byUser] = 我们自己关的，此时不该把 SIGKILL 那套解释吓唬用户。 */
    data class Exited(val code: Int, val message: String, val byUser: Boolean) : TerminalState

    /** 连降级路径都起不来。 */
    data class Failed(val message: String) : TerminalState
}

/**
 * 一个终端会话 = 一个 PTY + 一个子进程 + 一个 [TerminalSink]（模拟器）。
 *
 * ## 线程模型（每个会话三条，和 Termux 一致）
 *  - **reader**：裸 `Thread`，阻塞在 `read(master)` 上，读到就喂 sink。
 *    不能用 `Dispatchers.IO` —— 它长期挂在 syscall 里，既占死线程池的线程，
 *    又不可能被协程取消打断。
 *  - **waiter**：裸 `Thread`，阻塞在 `waitpid` 上，同上。
 *  - **writer**：协程 + 无界 Channel。写 pty master 在从设备缓冲区满（4 KiB）
 *    且子进程不读时**会阻塞**，所以绝不能在调用者线程（UI）上直接写。
 *
 * ## 关闭顺序（写反就是 fd 复用竞态）
 * `kill(-pid, SIGHUP)` → 宽限 → `SIGKILL` → `waitpid` 返回 → join reader →
 * **最后**才 `pfd.close()`。reader 还阻塞在这个 fd 上时提前关，fd 号会被别的
 * 线程 open 复用，reader 就读到了别人的文件。
 *
 * ## 降级
 * native 不可用 / 自检不过 / 起 pty 失败 —— 一律退到 [InteractiveShell]（管道，
 * 无行规程）。降级路径能敲命令、能看输出，但没有颜色、没有真 SIGINT、全屏程序
 * 用不了，这些代价会明确打到终端上告诉用户，而不是装作一切正常。
 */
class TerminalSession(
    val id: String,
    val folder: String?,
    val layout: ContainerLayout,
    val sink: TerminalSink,
    /** 会话自己的作用域。由 [TerminalSessionManager] 建/销，别传 UI 的 scope。 */
    private val scope: CoroutineScope,
    private val profile: ShellProfile = ShellProfile(layout),
    /** failsafe：跳过 bash 直接用 /system/bin/sh。 */
    private val preferSystemShell: Boolean = false,
    /** 调试用：强行走降级路径，不碰 native。 */
    private val forcePipeFallback: Boolean = false
) {

    private val lifecycleLock = Any()

    /** sink 不是线程安全的（模拟器自己也说了要在锁里用），这里统一串行化。 */
    private val sinkLock = Any()

    private val _state = MutableStateFlow<TerminalState>(TerminalState.Idle)
    val state: StateFlow<TerminalState> = _state.asStateFlow()

    // 这里以前还有一个 `revision: StateFlow<Int>`，每喂一批字节 +1，本意是给 UI
    // 当重绘信号。实际上从来没人订阅过（UI 走的是模拟器自己的 @Volatile revision），
    // 而它挂在「每个 pty read chunk 都调一次」的路上 —— `yes` 那种输出下每秒上千次
    // 的一次 AtomicInteger.incrementAndGet + 一次 StateFlow 写，全是空转。
    // 已删。真正的重绘信号在 sink 那一侧（EmulatorSink.redraw）：sink.append() 本来
    // 就是每一批字节的必经之路，信号顺手就发了，不用会话层再维护第二份计数。

    @Volatile private var ptyRun: PtyRun? = null
    @Volatile private var pipeRun: PipeRun? = null

    /**
     * 有人要求关掉这个会话。
     *
     * 「谁关的」这件事记在**每一轮运行**（[PtyRun.closing]）上而不是只记在会话
     * 上：`restart()` 会先关旧的再起新的，只有一个会话级标志的话，重置那一下
     * 正好会和旧 run 的 waiter 抢，旧会话的退出就被归因成「被系统杀了」，
     * 用户白看一屏 SIGKILL 的解释。会话级这个只管「启动过程中被叫停」。
     */
    @Volatile private var closeRequested = false

    /** bash 起不来自动退系统 shell，只做一次，避免起不来就无限重启。 */
    @Volatile private var autoFellBack = false

    @Volatile private var gridCols = DEFAULT_COLS
    @Volatile private var gridRows = DEFAULT_ROWS
    @Volatile private var cellW = 0
    @Volatile private var cellH = 0

    val cols: Int get() = gridCols
    val rows: Int get() = gridRows

    val alive: Boolean get() = _state.value is TerminalState.Running

    /** 当前是不是真 pty。UI 据此决定要不要显示「兼容模式」角标。 */
    val usingPty: Boolean get() = (_state.value as? TerminalState.Running)?.pty == true

    // -----------------------------------------------------------------
    // 启动
    // -----------------------------------------------------------------

    /**
     * 起会话。可重复调用（已在跑就直接返回），[Idle] / [TerminalState.Exited] /
     * [TerminalState.Failed] 状态下调用等于重开。
     *
     * fork 不能在主线程做，所以这里只切状态就返回，真正的启动在 IO 上跑。
     */
    fun start(
        cols: Int = gridCols,
        rows: Int = gridRows,
        cellWidthPx: Int = cellW,
        cellHeightPx: Int = cellH
    ) {
        synchronized(lifecycleLock) {
            val s = _state.value
            if (s is TerminalState.Starting || s is TerminalState.Running) return
            if (cols > 0) gridCols = cols
            if (rows > 0) gridRows = rows
            cellW = cellWidthPx
            cellH = cellHeightPx
            closeRequested = false
            _state.value = TerminalState.Starting
        }
        scope.launch(Dispatchers.IO) { boot(preferSystemShell) }
    }

    /** 关掉再起一遍。给「进程被杀了 → 重开会话」那个按钮用。 */
    fun restart() {
        scope.launch(Dispatchers.IO) {
            shutdown()
            // 等回收线程把 pty 收干净。最多 2 秒 —— 卡住的多半已经是僵尸，
            // 不能让用户按了没反应。
            val deadline = SystemClock.elapsedRealtime() + RESTART_WAIT_MS
            while (ptyRun != null && SystemClock.elapsedRealtime() < deadline) delay(50)
            autoFellBack = false
            closeRequested = false
            _state.value = TerminalState.Starting
            boot(preferSystemShell)
        }
    }

    /** 在 IO 线程上跑：会 fork，还会做一次最长两秒的自检。 */
    private fun boot(useSystemShell: Boolean) {
        // fork 之前再确认一次：profile.launch() 要读写 rc 文件，这中间用户完全
        // 来得及把会话关掉，起一个马上就要收的子进程没有意义。
        if (closeRequested) {
            _state.value = TerminalState.Exited(0, "会话已关闭", byUser = true)
            return
        }
        val launch = profile.launch(preferSystemShell = useSystemShell)
        for (w in launch.warnings) note(w)

        if (!forcePipeFallback && Pty.usable()) {
            val started = Pty.start(launch, gridCols, gridRows, cellW, cellH)
            if (started.ok) {
                if (attach(started, launch)) return
            } else if (
                !useSystemShell &&
                launch.shellKind == ShellKind.BASH &&
                started.error == NativePty.ERR_EXEC_NOT_FOUND
            ) {
                // 装的 bash 是坏的 / 不在 PATH 上。别让整个终端陪葬。
                note("${launch.executable} 起不来，改用系统 shell。")
                boot(useSystemShell = true)
                return
            } else {
                note("PTY 启动失败（${NativePty.errorName(started.error)}），退到兼容模式。")
            }
        }
        startPipe(launch)
    }

    /** @return false 表示 pty 拿到了但接不上，调用方继续走降级。 */
    private fun attach(started: PtyStart, launch: ShellLaunch): Boolean {
        val pfd = Pty.adopt(started.masterFd)
        if (pfd == null) {
            // fd 没托管成功，所有权还在我们手上，必须自己收干净，
            // 否则一个没人认领的 master 会永远挂着，对应的子进程谁也收不了。
            Pty.close(started.masterFd)
            Pty.signalGroup(started.pid, OsConstants.SIGKILL)
            Pty.waitFor(started.pid)
            note("无法接管伪终端，退到兼容模式。")
            return false
        }
        val run = PtyRun(
            pfd = pfd,
            pid = started.pid,
            usingSystemShell = launch.shellKind == ShellKind.SYSTEM_SH,
            startedAt = SystemClock.elapsedRealtime()
        )
        synchronized(lifecycleLock) { ptyRun = run }

        // MOTD 必须赶在 reader 之前写进 sink：shell 的提示符已经在 pty 缓冲里
        // 躺着了，晚一步就会插到提示符后面。
        profile.motd(launch.executable)?.let { emitText(it) }

        run.writer = scope.launch(Dispatchers.IO) { writerLoop(run) }
        val reader = Thread({ readerLoop(run) }, "biji-pty-read-$id").apply { isDaemon = true }
        run.reader = reader
        reader.start()

        // Running 必须在 waiter 之前推：子进程要是一出生就死（execve 失败），
        // waiter 会先把状态推成 Exited，这里再覆盖回 Running 就成了永远醒不过来
        // 的假活。
        _state.value = TerminalState.Running(pid = started.pid, pty = true)
        Thread({ waiterLoop(run) }, "biji-pty-wait-$id").apply { isDaemon = true }.start()

        // 启动这一路上被叫停了：补一刀，让正常的回收流程把它收走。
        if (closeRequested) shutdown()
        return true
    }

    // -----------------------------------------------------------------
    // PTY 读 / 写 / 回收
    // -----------------------------------------------------------------

    /**
     * **master fd 的唯一关闭点就在这个函数末尾。**
     *
     * 曾经的写法是 waiter 线程 `reader.join(2s)` 超时后就 `pfd.close()`。那是
     * 一个会静默损坏数据的竞态：`close()` 不会唤醒别的线程里正阻塞着的
     * `read()`（阻塞中的 read 已经解析完 fd、握着 struct file），所以 reader
     * 会带着一个**已经被释放、随时可能被别人复用**的 fd 号继续跑循环。
     *
     * 触发时序（真实可复现）：
     *   1. 在终端里 `sleep 300 &` 然后 `exit` —— shell 没了，但后台 job 还
     *      握着 pty 从设备，master 上 read 不到 EIO，reader 一直阻塞；
     *   2. waiter 的 waitpid 立刻返回，join 2 秒后超时，`pfd.close()`；
     *   3. 后台 job 往 tty 写一个字节（`while :; do date; sleep 1; done &`
     *      是最典型的），reader 被唤醒、拿到数据、进入下一轮 `Os.read(fd)`；
     *   4. 这时 fd 号已经被 app 里别的线程 open 走了（数据库、笔记文件、
     *      socket）—— reader 把**别人的文件内容读进终端屏幕**，同时把那份
     *      数据从对方的流里偷走。
     *
     * 所以 fd 的生命周期必须收敛到「读它的那条线程」：reader 退出循环之后
     * 自己关。代价是 reader 万一永远醒不过来就漏一个 fd —— 漏一个 fd 远比
     * 读到别人的文件轻。
     */
    private fun readerLoop(run: PtyRun) {
        // fd 在循环开始前取一次：pfd 关掉之后再 getFileDescriptor 会抛。
        val fd = runCatching { run.pfd.fileDescriptor }.getOrNull()
        if (fd == null) {
            runCatching { run.pfd.close() }
            run.readerDone = true
            return
        }
        val buf = ByteArray(READ_BUF)
        try {
            while (true) {
                val n = Pty.read(fd, buf)
                // 0 = EIO：pty 的最后一个从设备关了，这是子进程结束的**正常**信号，
                // 不是错误，往 UI 上刷报错就是一条假警报。<0 才是真错误，同样只能收摊。
                if (n <= 0) break
                synchronized(sinkLock) { sink.append(buf, 0, n) }
            }
        } finally {
            run.readerDone = true
            runCatching { run.pfd.close() }
        }
    }

    private suspend fun writerLoop(run: PtyRun) {
        val fd = runCatching { run.pfd.fileDescriptor }.getOrNull() ?: return
        for (chunk in run.queue) {
            if (chunk.isEmpty()) continue
            if (Pty.write(fd, chunk) <= 0) break
        }
    }

    private fun waiterLoop(run: PtyRun) {
        val code = Pty.waitFor(run.pid)
        run.reaped = true

        // 让 reader 先把 pty 里残留的输出读完再打退出提示，否则提示会插在最后
        // 一段输出前面。给上界：子进程都没了还读不到 EIO 说明有后台 job 还
        // 攥着从设备，不能为这个把退出提示一直压着不发。
        // **超时也绝不在这里关 pfd** —— fd 归 reader 所有，理由见 readerLoop。
        runCatching { run.reader?.join(READER_JOIN_MS) }
        run.writer?.cancel()
        run.queue.close()

        // 「这一轮还是不是当前那一轮」必须和置空原子地判一次，并且**决定后面
        // 要不要推状态**。restart() 只等 RESTART_WAIT_MS(2s) 就往下走，而这里
        // 光 join 就可能占满 READER_JOIN_MS(2s)：旧 run 的收尾完全可能落在新
        // run 已经 Running 之后。不判就直接 `_state.value = Exited`，用户会看到
        // 刚重开的会话立刻变成「进程结束」，而底下那个 shell 其实活得好好的。
        val current = synchronized(lifecycleLock) {
            if (ptyRun === run) {
                ptyRun = null
                true
            } else {
                false
            }
        }
        if (!current) return

        val byUser = run.closing
        val message = if (byUser) "会话已关闭" else NativePty.describeExit(code)
        note(if (byUser) message else "进程结束 · $message")
        _state.value = TerminalState.Exited(code, message, byUser)

        // execve 失败（127）且是刚起来就死 —— 装的 bash 多半架构不对。
        // 退到系统 shell 重来一次，只做一次。
        if (!byUser && shouldAutoFallback(run, code)) {
            autoFellBack = true
            note("shell 无法执行，改用系统 shell 重开。")
            _state.value = TerminalState.Starting
            boot(useSystemShell = true)
        }
    }

    private fun shouldAutoFallback(run: PtyRun, code: Int): Boolean =
        !autoFellBack &&
            !run.usingSystemShell &&
            code == NativePty.EXIT_EXEC_FAILED &&
            SystemClock.elapsedRealtime() - run.startedAt < AUTO_FALLBACK_WINDOW_MS

    // -----------------------------------------------------------------
    // 输入
    // -----------------------------------------------------------------

    /**
     * 原始字节写进终端。**键的编码是 UI 的事**（方向键要不要发 `ESC O A` 取决于
     * DECCKM，只有模拟器知道），这里只负责搬运。
     *
     * 回车必须是 `\r`（0x0D）不是 `\n`：终端等的是 CR，指望 `icrnl` 会在
     * raw 模式的程序里翻车。
     */
    fun write(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val p = ptyRun
        if (p != null) {
            p.queue.trySend(bytes)
            return
        }
        pipeRun?.let { writePipe(it, bytes) }
    }

    fun write(text: String) {
        if (text.isEmpty()) return
        write(text.toByteArray(Charsets.UTF_8))
    }

    /**
     * Ctrl-C。
     *
     * 有 pty 就只是往 master 写一个 0x03 —— 剩下的是内核的事：行规程认出
     * `VINTR`，把 SIGINT 发给**该终端的前台进程组**。所以正在跑的 `find /`
     * 会停，shell 本身不会。这是「真 Ctrl-C」和现在 `InteractiveShell.interrupt()`
     * （关 stdin，而且关了就再也输入不了）的本质区别。
     */
    fun interrupt() {
        val p = ptyRun
        if (p != null) {
            p.queue.trySend(byteArrayOf(CTRL_C))
            return
        }
        pipeRun?.let { writePipe(it, byteArrayOf(CTRL_C)) }
    }

    /** 给「结束进程」菜单用。发给整个进程组，后台 job 一起收，不留孤儿。 */
    fun sendSignal(signal: Int) {
        val p = ptyRun ?: return
        Pty.signalGroup(p.pid, signal)
    }

    // -----------------------------------------------------------------
    // 尺寸
    // -----------------------------------------------------------------

    /**
     * 行列变了才下 `TIOCSWINSZ` —— 内核在尺寸变化时会给前台进程组发 SIGWINCH，
     * 每帧 layout 都调就是连发 SIGWINCH，vim 会疯狂重绘。
     *
     * 像素尺寸（`ws_xpixel/ws_ypixel`）只有极少数程序读，键盘弹出导致行高微调
     * 就重下一次 ioctl 不值得，所以只记下来，等下次行列真变了一起生效。
     */
    fun resize(cols: Int, rows: Int, cellWidthPx: Int = cellW, cellHeightPx: Int = cellH) {
        if (cols <= 0 || rows <= 0) return
        val gridChanged: Boolean
        synchronized(lifecycleLock) {
            gridChanged = cols != gridCols || rows != gridRows
            gridCols = cols
            gridRows = rows
            cellW = cellWidthPx
            cellH = cellHeightPx
        }
        if (!gridChanged) return
        // 先让模拟器改尺寸再通知内核：SIGWINCH 触发的重绘输出回来时，
        // 缓冲区必须已经是新尺寸，否则那一屏会画错。
        synchronized(sinkLock) { sink.resize(cols, rows) }
        ptyRun?.let { Pty.setWinSize(it.pfd, rows, cols, cellWidthPx, cellHeightPx) }
    }

    // -----------------------------------------------------------------
    // 关闭
    // -----------------------------------------------------------------

    /**
     * 先礼后兵：`SIGHUP` 给整个进程组，宽限期后还活着就 `SIGKILL`。
     *
     * 真正的收尾（join reader、关 master、推 [TerminalState.Exited]）在 waiter
     * 线程里做，这里只负责把子进程送走。升级那步用裸线程而不是协程：调用方
     * （manager）关完会话往往紧接着就 cancel 掉整个 scope，协程会跟着死掉，
     * 宽限期就白等了。
     */
    fun shutdown() {
        closeRequested = true
        val p = ptyRun
        val q = pipeRun
        p?.closing = true
        q?.closing = true
        if (p != null) {
            Pty.signalGroup(p.pid, OsConstants.SIGHUP)
            Thread({
                Thread.sleep(GRACE_MS)
                // reaped 是 waiter 线程置的。不检查就 kill 有 pid 复用风险：
                // 回收之后这个号可能已经属于别人了。
                if (!p.reaped) Pty.signalGroup(p.pid, OsConstants.SIGKILL)
            }, "biji-pty-kill-$id").apply { isDaemon = true }.start()
        }
        if (q != null) {
            synchronized(lifecycleLock) { if (pipeRun === q) pipeRun = null }
            q.collector?.cancel()
            q.watchdog?.cancel()
            runCatching { q.shell.shutdown() }
            val s = _state.value
            if (s !is TerminalState.Exited && s !is TerminalState.Failed) {
                note("会话已关闭")
                _state.value = TerminalState.Exited(0, "会话已关闭", byUser = true)
            }
        }
        if (p == null && q == null) {
            val s = _state.value
            if (s is TerminalState.Starting) {
                _state.value = TerminalState.Exited(0, "会话已关闭", byUser = true)
            }
        }
    }

    /** 子进程（shell）当前的工作目录。header 显示 `cd` 之后的真实路径用。 */
    fun currentCwd(): String? {
        val pid = (_state.value as? TerminalState.Running)?.pid ?: return null
        if (pid <= 0) return null
        return runCatching { Os.readlink("/proc/$pid/cwd") }.getOrNull()
    }

    // -----------------------------------------------------------------
    // 降级路径：没有 PTY 的管道 shell
    // -----------------------------------------------------------------

    /**
     * 用现有的 [InteractiveShell]。它是管道模型，没有行规程，代价很实在：
     *  - 没有回显、没有行编辑 → 下面自己做一个最小的行缓冲；
     *  - 没有前台进程组 → `^C` 发不出 SIGINT；
     *  - `isatty()` 为假 → 工具不出颜色（也正因如此这里不改 TERM，
     *    让它保持 `InteractiveShell` 自己那套）；
     *  - vi / top / ssh 这类全屏程序直接用不了。
     *
     * 这些都在终端里明说，不装作一切正常。
     */
    private fun startPipe(launch: ShellLaunch) {
        if (closeRequested) {
            _state.value = TerminalState.Exited(0, "会话已关闭", byUser = true)
            return
        }
        val workDir = File(launch.cwd).takeIf { it.isDirectory || it.mkdirs() } ?: layout.root
        val shell = InteractiveShell(
            workDir = workDir,
            extraPathDirs = listOf(layout.binDir.absolutePath, layout.execDir.absolutePath),
            scope = scope
        )
        val run = PipeRun(shell)
        synchronized(lifecycleLock) { pipeRun = run }

        // MOTD 先写：InteractiveShell.start() 自己会 emit 一条工作目录，
        // 晚一步 MOTD 就插到它后面去了。
        profile.motd(launch.executable)?.let { emitText(it) }

        // 先接收再启动：InteractiveShell 在 start() 里就会 emit 一条工作目录，
        // 晚接一步那条就只能靠 replay 兜了。
        run.collector = scope.launch(Dispatchers.IO) {
            shell.output.collect { chunk ->
                val pending = run.pendingEcho
                if (pending != null && chunk.text == pending) {
                    // send() 自带的 "$ cmd" 回显。我们已经本地回显过一遍了，
                    // 放过去就是双份。
                    run.pendingEcho = null
                    return@collect
                }
                emitChunk(chunk)
            }
        }
        shell.start()
        if (!shell.alive) {
            synchronized(lifecycleLock) { if (pipeRun === run) pipeRun = null }
            run.collector?.cancel()
            _state.value = TerminalState.Failed("shell 起不来（既没有 PTY，管道也失败了）")
            note("终端起不来：${launch.executable} 和系统 sh 都没能启动。")
            return
        }
        note("兼容模式：无颜色，^C 不发信号，全屏程序用不了。")
        _state.value = TerminalState.Running(pid = -1, pty = false)

        // 管道路径没有 waiter：[InteractiveShell] 只暴露一个 isAlive，进程死了不通知
        // 任何人（它的 output 是 SharedFlow，永远不 complete），所以「死了没有」这件
        // 事只能靠问。但**问的频率没必要是恒定的**：
        //
        //  * 主唤醒源是用户往 shell 里写了东西（[PipeRun.poke]）—— `exit` 是这条路上
        //    最常见的死法，敲完立刻就能问出来，比原来最坏等满 1.5 秒还快；
        //  * 兜底超时从 1.5 秒起，每空转一轮翻一倍到一分钟封顶。刚起来那几秒最容易
        //    死（shell 本身坏了），那段照旧问得勤；一个开着不动的兼容模式会话则会
        //    自己退化到一分钟一次。
        //
        // 净效果：一个静止的降级会话从 0.67 Hz 降到 0.017 Hz，而且用户一动就立刻
        // 回到最灵敏的档位。
        run.watchdog = scope.launch(Dispatchers.IO) {
            var backoff = PIPE_PROBE_MIN_MS
            while (true) {
                val poked = withTimeoutOrNull(backoff) { run.poke.receive() } != null
                if (pipeRun !== run) return@launch
                backoff = if (poked) PIPE_PROBE_MIN_MS
                else (backoff * 2).coerceAtMost(PIPE_PROBE_MAX_MS)
                // 刚写下去的 `exit` 得给它一点时间真的退出，否则这一眼必然看到
                // 「还活着」，然后要等一整个兜底周期才回来看第二眼。
                if (poked) delay(PIPE_EXIT_GRACE_MS)
                if (pipeRun !== run) return@launch
                if (shell.alive) continue
                synchronized(lifecycleLock) { if (pipeRun === run) pipeRun = null }
                run.collector?.cancel()
                val byUser = run.closing
                val message = if (byUser) "会话已关闭" else "shell 已退出"
                note(message)
                _state.value = TerminalState.Exited(0, message, byUser)
                return@launch
            }
        }
    }

    /**
     * 降级路径的输入处理：本地回显 + 退格 + 回车提交整行。
     *
     * 只能做到这个程度 —— 管道那头的 `sh` 是**行缓冲**读的，逐字符写过去它也
     * 要等到换行才动。方向键、Tab 补全、`less` 的翻页在这条路上不可能有，
     * 别在这里越描越黑，PTY 才是解法。
     */
    private fun writePipe(run: PipeRun, bytes: ByteArray) {
        val text = String(bytes, Charsets.UTF_8)
        // 回显攒着一次性写：逐 char 转 UTF-8 会把代理对拆成两个孤儿代理项，
        // emoji 会变成两个 '?'。攒到批次末尾再转就没这个问题，顺便少敲几次模拟器。
        val echo = StringBuilder()
        fun flushEcho() {
            if (echo.isEmpty()) return
            emitText(echo.toString())
            echo.setLength(0)
        }
        for (ch in text) {
            when (ch) {
                '\r', '\n' -> {
                    val cmd = run.line.toString()
                    run.line.setLength(0)
                    echo.append('\n')
                    flushEcho()
                    // send() 自己会 emit 一条 "$ cmd" 回显，上面已经本地回显过
                    // 一遍了，登记下来让 collector 吞掉，否则是双份。
                    run.pendingEcho = "$ $cmd\n"
                    runCatching { run.shell.send(cmd) }
                }
                CTRL_C_CHAR -> {
                    run.line.setLength(0)
                    echo.append("^C\n")
                    flushEcho()
                    if (!run.warnedNoSigint) {
                        run.warnedNoSigint = true
                        note("^C 发不出信号，命令不会停。")
                    }
                    // 还是把 0x03 送进去：个别自己读 stdin 的程序认这个字节。
                    runCatching { run.shell.sendRaw("\\u0003") }
                }
                CTRL_D_CHAR -> {
                    if (!run.warnedNoEof) {
                        run.warnedNoEof = true
                        flushEcho()
                        // InteractiveShell.interrupt() 会关掉 stdin 并把它置 null，
                        // 之后这个会话再也无法输入 —— 宁可不支持 ^D 也不能调它。
                        note("不支持 ^D。结束会话请直接关闭。")
                    }
                }
                BACKSPACE, DELETE -> if (run.line.isNotEmpty()) {
                    run.line.setLength(run.line.length - 1)
                    echo.append("\b \b")
                }
                else -> if (ch.code >= 0x20) {
                    run.line.append(ch)
                    echo.append(ch)
                }
            }
        }
        flushEcho()
        // 用户动过 shell 了 —— 刚提交的可能正是 `exit`。叫 watchdog 去看一眼，
        // 顺便把它的兜底间隔打回最灵敏的一档。
        run.poke.trySend(Unit)
    }

    private fun emitChunk(chunk: InteractiveShell.Chunk) {
        // stderr 在管道模型里是另一条流，混进来就分不清了。既然模拟器认 SGR，
        // 给它套一层红色，至少把「这是错误输出」这个信息保住。
        val text = if (chunk.isStderr) SGR_RED + chunk.text + SGR_RESET else chunk.text
        emitText(text)
    }

    // -----------------------------------------------------------------
    // 往 sink 里塞我们自己的文字
    // -----------------------------------------------------------------

    /**
     * 会话自己说话（MOTD、退出提示、降级说明）。
     *
     * `\n` 一律补成 `\r\n`：模拟器里 LF 只往下走一行**不回列**，直接写 `\n`
     * 会出现阶梯状的输出。pty 那条路的 ONLCR 是内核干的，这条是我们自己来。
     */
    private fun emitText(text: String) {
        if (text.isEmpty()) return
        val normalized = buildString(text.length + 8) {
            var prev = ' '
            for (ch in text) {
                if (ch == '\n' && prev != '\r') append('\r')
                append(ch)
                prev = ch
            }
        }
        val bytes = normalized.toByteArray(Charsets.UTF_8)
        synchronized(sinkLock) { sink.append(bytes, 0, bytes.size) }
    }

    /** 带方括号的会话级提示，和程序输出区分开，并且保证独占一行。 */
    private fun note(text: String) = emitText("\r\n[$text]\r\n")

    // -----------------------------------------------------------------
    // 每一轮运行的资源
    // -----------------------------------------------------------------

    private class PtyRun(
        val pfd: ParcelFileDescriptor,
        val pid: Int,
        val usingSystemShell: Boolean,
        val startedAt: Long
    ) {
        /**
         * 无界：按键是人手速，量小；粘贴大段文本时宁可多占点内存，也不能让
         * `trySend` 失败悄悄吞掉用户的输入。有界 + 满了丢，是最难查的一类 bug。
         */
        val queue = Channel<ByteArray>(Channel.UNLIMITED)

        @Volatile var reader: Thread? = null
        @Volatile var writer: Job? = null

        /** waiter 已经 `waitpid` 成功。之后再 kill 这个 pid 就有复用风险。 */
        @Volatile var reaped = false

        /**
         * reader 已经退出循环并关掉了 [pfd]。
         *
         * 只有 reader 会关这个 fd（见 [readerLoop] 里那段时序说明）；这个标志
         * 让 [restart] 之类的等待逻辑能问「上一轮真的收干净了吗」，而不是靠
         * `ptyRun == null` 猜 —— 后者在 reader 还阻塞着的时候就已经是 null 了。
         */
        @Volatile var readerDone = false

        /** 这一轮是被我们主动关掉的。决定退出提示怎么写。 */
        @Volatile var closing = false
    }

    private class PipeRun(val shell: InteractiveShell) {
        val line = StringBuilder()
        @Volatile var collector: Job? = null
        @Volatile var watchdog: Job? = null

        /**
         * 「用户刚往 shell 里写了东西，去看一眼它还活着没」。
         *
         * CONFLATED：连打十个字只会让 watchdog 醒一次，trySend 也永远不会失败或
         * 阻塞（写入发生在 UI 线程，不能有任何等待）。刻意**不**在输出到达时 poke ——
         * 有输出恰恰证明它活着，那样只会在 `find /` 刷屏时把 watchdog 反复叫醒。
         */
        val poke = Channel<Unit>(Channel.CONFLATED)

        /** 待吞掉的一条 `send()` 自带回显。 */
        @Volatile var pendingEcho: String? = null

        @Volatile var warnedNoSigint = false
        @Volatile var warnedNoEof = false
        @Volatile var closing = false
    }

    private companion object {
        const val DEFAULT_COLS = 80
        const val DEFAULT_ROWS = 24

        /** 一次 read 的上限。pty 内核缓冲一般 4 KiB 一档，8 KiB 足够一次吃干净。 */
        const val READ_BUF = 8 * 1024

        const val GRACE_MS = 1200L
        const val READER_JOIN_MS = 2000L
        const val RESTART_WAIT_MS = 2000L
        /** 降级路径存活探测的兜底间隔：起步值和封顶值，每空转一轮翻倍。 */
        const val PIPE_PROBE_MIN_MS = 1500L
        const val PIPE_PROBE_MAX_MS = 60_000L

        /** 写进去之后等这么久再问「还活着吗」，给 `exit` 留出真正退出的时间。 */
        const val PIPE_EXIT_GRACE_MS = 200L

        /** 起来这么久之内退 127 才算「shell 本身坏了」，之后的 127 是用户命令没找到。 */
        const val AUTO_FALLBACK_WINDOW_MS = 3000L

        const val CTRL_C: Byte = 0x03
        const val CTRL_C_CHAR = '\\u0003'
        const val CTRL_D_CHAR = '\\u0004'
        const val BACKSPACE = '\b'
        const val DELETE = '\\u007F'

        /** SGR 红色，降级路径里用来标出 stderr。 */
        const val SGR_RED = "\\u001B[31m"
        const val SGR_RESET = "\\u001B[0m"
    }
}

// =====================================================================
//  NativePty 适配层 —— 所有 native 调用只出现在这里
// =====================================================================

/**
 * 把会话逻辑和 [NativePty] 的具体签名隔开。
 *
 * native 侧（`cpp/pty.cpp` + `NativePty.kt`）由另一个 agent 并行开发，签名对不上
 * 时**只改这个 object**。门控转发给 [NativeGate.pty]（和另外三个 native 模块
 * 同一个地方），它是 `by lazy`，全进程只自检一次。
 */
private object Pty {

    /**
     * 自检**会 fork 并阻塞最多约两秒**，只能在 IO 线程上触发。[NativeGate.pty]
     * 是 `by lazy`，全进程只跑一次，多个会话同时开也不会各测一遍。
     *
     * 唯一的调用点是 [TerminalSession.boot]，那是 `Dispatchers.IO` —— 别从别处
     * （尤其别从 Compose 的重组里）读它。
     */
    fun usable(): Boolean = NativePty.available && NativeGate.pty

    fun start(launch: ShellLaunch, cols: Int, rows: Int, cellW: Int, cellH: Int): PtyStart =
        NativePty.start(
            cmd = launch.executable,
            cwd = launch.cwd,
            argv = launch.argv.toTypedArray(),
            envp = launch.envp.toTypedArray(),
            rows = rows,
            cols = cols,
            cellWidthPx = cellW,
            cellHeightPx = cellH
        )

    fun adopt(masterFd: Int): ParcelFileDescriptor? = NativePty.adoptMaster(masterFd)

    fun read(fd: java.io.FileDescriptor, buf: ByteArray): Int = NativePty.readMaster(fd, buf)

    fun write(fd: java.io.FileDescriptor, bytes: ByteArray): Int =
        NativePty.writeMaster(fd, bytes)

    fun setWinSize(pfd: ParcelFileDescriptor, rows: Int, cols: Int, cellW: Int, cellH: Int): Int =
        NativePty.setWinSize(pfd, rows, cols, cellW, cellH)

    fun waitFor(pid: Int): Int = NativePty.waitFor(pid)

    fun signalGroup(pid: Int, signal: Int): Boolean = NativePty.signalGroup(pid, signal)

    fun close(fd: Int): Int = NativePty.close(fd)
}
