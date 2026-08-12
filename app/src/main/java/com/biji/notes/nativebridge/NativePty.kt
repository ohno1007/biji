package com.biji.notes.nativebridge

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.FileDescriptor
import java.io.InterruptedIOException

/**
 * 真 PTY 的 Kotlin 侧入口，对应 `cpp/pty.cpp`。
 *
 * ## 为什么要这个
 * 现在的 `sandbox/InteractiveShell` 用 `ProcessBuilder` + 管道。管道没有行
 * 规程（N_TTY），0x03 只是一个普通字节 —— `interrupt()` 只能关 stdin 假装
 * 中断。换成 pty 之后，下面这些是内核白送的：
 *  - `^C`/`^\`/`^Z` → 行规程发 SIGINT/SIGQUIT/SIGTSTP 给**前台进程组**；
 *  - `^D` EOF、`^U`/`^W` 行编辑、作业控制（`fg`/`bg`，靠 TIOCSPGRP）；
 *  - `isatty(1)` 为真 ⇒ 程序肯出彩色、肯走交互分支（vim/top/fzf 才能用）；
 *  - 窗口尺寸变化由内核自动发 SIGWINCH，不用我们手动 kill。
 *
 * ## 契约（三条，改动前先读）
 *  - **不抛异常**。native 侧一律返回负错误码（[ERR_BAD_ARGS] 等），Kotlin
 *    侧再把 `UnsatisfiedLinkError` 之类兜成 [ERR_UNAVAILABLE]。任何失败都
 *    应该让调用方降级回 `InteractiveShell`，而不是把 UI 打崩。
 *  - **fd 与 pid 的所有权在调用方**。native 不持有任何全局状态，可以被多个
 *    会话并发调用；谁拿到 [PtyStart.masterFd] 谁负责关（见 [adoptMaster]）。
 *  - **参数用绝对路径**。命令名不含 `/` 时 native 会用 [envp] 里的 `PATH`
 *    搜索（不是父进程的 PATH —— bionic 的 `execvpe` 恰恰读错了那个）。
 *
 * ## 线程
 *  - [start] / [selfTest] 会 fork，别在主线程调；
 *  - [waitFor] 长期阻塞在 syscall 上，**必须用裸 `Thread`**，不要放
 *    `Dispatchers.IO`（占死线程池且无法被协程取消打断）；
 *  - 读线程同理。写线程可以用协程 + Channel。
 *
 * ## 已知的运行期风险：Android 12+ 的 phantom process killer
 * 系统会扫 `/proc` 找 app fork 出来的子进程，全系统共享一个数量上限，超了
 * 就 SIGKILL 最老的；app 在后台时子进程 CPU 高也会被杀。这**不是 PTY 引入
 * 的** —— 现在的 `ProcessBuilder` 一样中招。[describeExit] 会把 `-9` 翻译成
 * 给用户看的解释，别让人以为是我们的 bug。
 */
object NativePty {

    /** .so 是否加载成功。为 false 时所有方法返回失败值，调用方应走管道实现。 */
    val available: Boolean = runCatching { System.loadLibrary("bijinative") }.isSuccess

    /** 错误码/返回布局版本。native 改布局会 +1（见 `cpp/pty.h` kPtyFormatVersion）。 */
    const val PTY_FORMAT_VERSION = 1

    // ---- native 返回的错误码，必须与 cpp/pty.h 的 Err 逐个对应 ----

    /** 入参 null / 数组超限 / rows,cols <= 0 / 字符串里有内嵌 U+0000。属于我们自己的 bug。 */
    const val ERR_BAD_ARGS = -1
    /** native 侧 `new (std::nothrow)` 失败。 */
    const val ERR_ALLOC = -2
    /** `open("/dev/ptmx")` 失败。**EACCES 就是 OEM 收紧 SELinux 策略的信号**。 */
    const val ERR_OPEN_PTMX = -3
    /** `unlockpt`（ioctl TIOCSPTLCK）失败。 */
    const val ERR_UNLOCKPT = -4
    /** `ptsname_r`（ioctl TIOCGPTN）失败。 */
    const val ERR_PTSNAME = -5
    /** 自检时 `open("/dev/pts/N")` 失败。 */
    const val ERR_OPEN_PTS = -6
    /** `fork` 失败。EAGAIN = 进程数到顶，[start] 内部会自动重试一次。 */
    const val ERR_FORK = -7
    /** 命令名不含 `/`，且在 [envp][start] 的 PATH 里找不到可执行文件。 */
    const val ERR_EXEC_NOT_FOUND = -8
    /** 自检时 `ioctl(TIOCSWINSZ)` 失败。 */
    const val ERR_WIN_SIZE = -9

    // ---- Kotlin 侧独有（native 到不了的失败）----

    /** .so 没加载上，或调用时 `UnsatisfiedLinkError`。 */
    const val ERR_UNAVAILABLE = -100
    /** JNI 调用抛了别的东西（理论上不该发生）。 */
    const val ERR_JNI = -101

    // ---- waitpid 的哨兵。退出码 0..255、信号 -1..-64，都离 Int.MIN_VALUE 很远 ----

    /** `waitpid` 本身失败（ECHILD：已经被回收过，或根本不是我们的子进程）。 */
    const val WAIT_FAILED = Int.MIN_VALUE
    /** 仅 [waitNoHang]：子进程还活着。 */
    const val WAIT_RUNNING = Int.MIN_VALUE + 1

    // ---- 子进程在 exec 之前自杀时用的退出码（shell 惯例）----

    /** 子进程连 slave 都打不开 —— 没有终端，报不出话。 */
    const val EXIT_PTS_FAILED = 126
    /** `execve` 失败。原因已经由子进程写到 pts 上，用户在终端里直接看得见。 */
    const val EXIT_EXEC_FAILED = 127

    // ---- 与 cpp/pty.h 一致的入参上限 ----

    /** argv / envp 的最大条数。 */
    const val MAX_ARG_ITEMS = 1024
    /**
     * 单条参数的最大 UTF-16 code unit 数。
     * 取 32K 而不是 64K：内核的 `MAX_ARG_STRLEN` 是 128 KiB，一个 unit 最多
     * 产生 3 字节 UTF-8，32K × 3 = 96 KiB，任何输入都撞不上那条线。
     */
    const val MAX_ARG_UNITS = 32 * 1024

    // ---- JNI 入口。不要直接调，走下面的包装。 ----

    private external fun nCreateSubprocess(
        cmd: String,
        cwd: String?,
        argv: Array<String>,
        envp: Array<String>,
        rows: Int,
        cols: Int,
        cellWidthPx: Int,
        cellHeightPx: Int,
        outPid: IntArray
    ): Int

    private external fun nSetWinSize(
        fd: Int, rows: Int, cols: Int, cellWidthPx: Int, cellHeightPx: Int
    ): Int

    private external fun nWaitFor(pid: Int): Int
    private external fun nWaitNoHang(pid: Int): Int
    private external fun nClose(fd: Int): Int
    private external fun nSelfTest(): Int

    /**
     * 开一个 pty，fork 出子进程并 `execve`。
     *
     * 子进程在 exec 之前会：解开 JVM 屏蔽的信号 → `setsid()` → 打开 slave 并
     * 使其成为**控制终端** → dup 到 0/1/2 → 关掉所有继承来的 fd → `chdir`。
     * 它同时是新会话的首进程和进程组长，所以 [signalGroup] 能一把收掉整棵树。
     *
     * @param cmd  命令。**推荐绝对路径**；不含 `/` 时按 [envp] 里的 PATH 搜索
     *             （绝不会用 JVM 进程的 PATH）。
     * @param cwd  子进程 chdir 目标，null = 不切。切失败不致命，照样 exec。
     * @param argv **必须含 argv[0]**。条数 <= [MAX_ARG_ITEMS]，单条 <= [MAX_ARG_UNITS]。
     * @param envp `"KEY=VALUE"` 形式的**完整**环境 —— 不继承 JVM 的 environ。
     *             想要 PATH/HOME/TERM/LANG 就自己全部写上。
     * @param rows/cols 终端行列数。fork 前就设进内核，子进程一出生尺寸就是对的
     *                  （否则 ncurses/vim 看到 0×0 会按 80×24 兜底然后整屏画错）。
     *                  <= 0 会被夹到 1：Compose 首帧还没测量出来时确实可能是 0，
     *                  为这个让整个会话起不来不划算，后面 [setWinSize] 会纠正。
     * @param cellWidthPx/cellHeightPx 字符宽高，只用于 `ws_xpixel/ws_ypixel`，可传 0。
     *
     * 会 fork，**别在主线程调**。
     */
    fun start(
        cmd: String,
        cwd: String?,
        argv: Array<String>,
        envp: Array<String>,
        rows: Int,
        cols: Int,
        cellWidthPx: Int = 0,
        cellHeightPx: Int = 0
    ): PtyStart {
        if (!available) return PtyStart.failure(ERR_UNAVAILABLE)
        if (cmd.isEmpty() || argv.isEmpty()) return PtyStart.failure(ERR_BAD_ARGS)
        if (argv.size > MAX_ARG_ITEMS || envp.size > MAX_ARG_ITEMS) {
            return PtyStart.failure(ERR_BAD_ARGS)
        }

        val r = rows.coerceAtLeast(1)
        val c = cols.coerceAtLeast(1)
        val outPid = IntArray(1)

        var fd = callCreate(cmd, cwd, argv, envp, r, c, cellWidthPx, cellHeightPx, outPid)
        // fork 的 EAGAIN 通常是瞬时的（系统进程数刚好到顶）。重试一次不加延迟：
        // 真要 sleep 也该由会话层决定，这里不替调用方阻塞。
        if (fd == ERR_FORK) {
            fd = callCreate(cmd, cwd, argv, envp, r, c, cellWidthPx, cellHeightPx, outPid)
        }
        if (fd < 0) return PtyStart.failure(fd)

        val pid = outPid[0]
        if (pid <= 0) {
            // 理论上到不了：native 返回 >= 0 时一定写了正 pid。真到了这里也
            // 必须把 fd 关掉 —— 否则一个没人认领的 master fd 会永远挂着，
            // 而它对应的子进程谁也收不了。
            close(fd)
            return PtyStart.failure(ERR_JNI)
        }
        return PtyStart(masterFd = fd, pid = pid, error = 0)
    }

    private fun callCreate(
        cmd: String, cwd: String?, argv: Array<String>, envp: Array<String>,
        rows: Int, cols: Int, cellW: Int, cellH: Int, outPid: IntArray
    ): Int = try {
        nCreateSubprocess(cmd, cwd, argv, envp, rows, cols, cellW, cellH, outPid)
    } catch (_: UnsatisfiedLinkError) {
        ERR_UNAVAILABLE
    } catch (_: Throwable) {
        ERR_JNI
    }

    /**
     * 改窗口尺寸。内核只在尺寸**真的变化**时给前台进程组发 SIGWINCH，
     * 所以调用方必须自己去抖：只有 `(rows, cols)` 变了才调 —— 每帧 layout
     * 都调会连发 SIGWINCH，vim 会疯狂重绘。
     *
     * @return 0 成功；<0 失败（`-errno`）。**失败不致命**，只是尺寸没同步，忽略即可。
     */
    fun setWinSize(
        masterFd: Int, rows: Int, cols: Int, cellWidthPx: Int = 0, cellHeightPx: Int = 0
    ): Int {
        if (!available) return ERR_UNAVAILABLE
        if (masterFd < 0 || rows <= 0 || cols <= 0) return ERR_BAD_ARGS
        return try {
            nSetWinSize(masterFd, rows, cols, cellWidthPx, cellHeightPx)
        } catch (_: UnsatisfiedLinkError) {
            ERR_UNAVAILABLE
        } catch (_: Throwable) {
            ERR_JNI
        }
    }

    /** [setWinSize] 的便利重载：[pfd] 还没关的时候用它的 fd。 */
    fun setWinSize(
        pfd: ParcelFileDescriptor, rows: Int, cols: Int,
        cellWidthPx: Int = 0, cellHeightPx: Int = 0
    ): Int = runCatching {
        setWinSize(pfd.fd, rows, cols, cellWidthPx, cellHeightPx)
    }.getOrDefault(ERR_JNI)

    /**
     * 阻塞等子进程结束。**必须在专用的裸 `Thread` 上调**（会长期挂在 syscall 上）。
     *
     * 每个 pid 只能成功 wait 一次 —— 回收之后 pid 立即可被系统复用，重复 wait
     * 可能等到一个毫不相干的进程。
     *
     * 顺便说明为什么这条路和现有的 `ProcessBuilder` 能长期共存：libcore 给每个
     * `Process` 起一个 reaper 线程，调的是**定向** `waitpid(pid)` 而不是
     * `waitpid(-1)`，两边不会互相偷走退出状态。也正因如此，**不要**装进程级的
     * SIGCHLD handler。
     *
     * @return 0..255 = 正常退出码；-1..-64 = 被信号杀（值 = -signo）；
     *         [WAIT_FAILED] = waitpid 失败。翻译成人话用 [describeExit]。
     */
    fun waitFor(pid: Int): Int {
        if (!available) return WAIT_FAILED
        if (pid <= 0) return WAIT_FAILED
        return try {
            nWaitFor(pid)
        } catch (_: Throwable) {
            WAIT_FAILED
        }
    }

    /** [waitFor] 的非阻塞版（WNOHANG）。还活着返回 [WAIT_RUNNING]。 */
    fun waitNoHang(pid: Int): Int {
        if (!available) return WAIT_FAILED
        if (pid <= 0) return WAIT_FAILED
        return try {
            nWaitNoHang(pid)
        } catch (_: Throwable) {
            WAIT_FAILED
        }
    }

    /**
     * 关一个裸 fd。**已经交给 [adoptMaster] 的 fd 不要用这个关** —— 那是
     * `ParcelFileDescriptor` 的活，双关会误伤别的线程刚 open 的同号 fd。
     *
     * @return 0 或 -errno。
     */
    fun close(fd: Int): Int {
        if (!available) return ERR_UNAVAILABLE
        if (fd < 0) return ERR_BAD_ARGS
        return try {
            nClose(fd)
        } catch (_: Throwable) {
            ERR_JNI
        }
    }

    // -----------------------------------------------------------------
    // master fd 的读写
    //
    // 【选型：ParcelFileDescriptor.adoptFd + android.system.Os.read/write】
    // 理由三条：
    //  1. 全是公开 API。Termux 走的是反射 FileDescriptor.descriptor 私有字段
    //     （失败就 System.exit(1)），在 targetSdk 28 上属灰名单 —— 既然
    //     adoptFd 从 API 13 就是公开的且效果完全一样，没理由引入那个脆弱点。
    //  2. PFD 是**唯一属主**，配 CloseGuard 泄漏时能报警；fd 的生命周期收敛
    //     到一次 pfd.close()，不会出现"两个流各自 close 同一个 fd"。
    //  3. ErrnoException.errno 让我们能精确区分 EINTR（重试）/ EIO（子进程
    //     全没了 = **正常终止**）/ EBADF（我们自己关的）。这个区分是刚需：
    //     Linux 上 pty master 的所有 slave 关闭后 read() 返回 **-1/EIO 而不是
    //     0/EOF**，当成错误刷到 UI 上就是一条假报错。
    //
    // 【两个 Android 特有的坑】
    //  - `FileInputStream(FileDescriptor)` 在 Android 上 isFdOwner = false，
    //    close() **不会**真的关 fd（和桌面 JVM 相反）。想包一层流是安全的。
    //  - 但**绝不要**用 `AutoCloseInputStream` + `AutoCloseOutputStream` 各包
    //    一个：它俩都会关 PFD，先关的那个把另一个的 fd 抽走。
    //
    // 【备选方案】native read/write + eventfd + poll 双 fd，好处是能干净地
    // 唤醒阻塞中的 reader。目前不需要：正常关闭是先 kill 子进程 → slave 关闭
    // → reader 自然收到 EIO 退出。真在真机上观察到关闭竞态再升级。
    // -----------------------------------------------------------------

    /**
     * 把 native 返回的裸 fd 交给 `ParcelFileDescriptor` 托管。
     *
     * **成功之后就不要再碰这个 int fd 了**（尤其别调 [close]）：所有权已经
     * 转移，之后只允许 `pfd.close()`。返回 null 表示托管失败，此时 fd 仍然
     * 是调用方的，需要自己 [close]。
     */
    fun adoptMaster(masterFd: Int): ParcelFileDescriptor? {
        if (masterFd < 0) return null
        return runCatching { ParcelFileDescriptor.adoptFd(masterFd) }.getOrNull()
    }

    /**
     * 从 master 读。EINTR 内部重试。
     *
     * @return >0 读到的字节数；**0 = 子进程侧已全部关闭（EIO/EOF），属于正常
     *         终止，不要往 UI 刷错误**；<0 = -errno（真错误），未知异常为 -1。
     */
    fun readMaster(fd: FileDescriptor, buf: ByteArray, off: Int = 0, len: Int = buf.size - off): Int {
        if (len <= 0) return 0
        while (true) {
            try {
                val n = Os.read(fd, buf, off, len)
                return if (n <= 0) 0 else n
            } catch (e: ErrnoException) {
                when (e.errno) {
                    OsConstants.EINTR -> Unit  // 重来
                    // EIO：最后一个 slave 关闭了 —— 这是 pty 上"子进程结束"的
                    // 正常信号。EBADF：我们自己把 fd 关了（会话在收尾）。
                    OsConstants.EIO, OsConstants.EBADF -> return 0
                    else -> return -e.errno
                }
            } catch (e: InterruptedIOException) {
                // 线程被 interrupt 打断。已经落进 buf 的字节不能丢，
                // 否则终端上会缺一段输出（而且是随机的，极难查）。
                return if (e.bytesTransferred > 0) e.bytesTransferred else 0
            } catch (_: Throwable) {
                return -1
            }
        }
    }

    /**
     * 往 master 写（= 用户按键）。处理短写和 EINTR。
     *
     * @return 实际写出的字节数（正常等于 [len]）；一个字节都没写出去时返回
     *         -errno。fd 已关闭时返回 0/负值，调用方当作会话结束即可。
     */
    fun writeMaster(
        fd: FileDescriptor, bytes: ByteArray, off: Int = 0, len: Int = bytes.size - off
    ): Int {
        if (len <= 0) return 0
        var done = 0
        while (done < len) {
            try {
                val n = Os.write(fd, bytes, off + done, len - done)
                if (n <= 0) break
                done += n
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EINTR) continue
                return if (done > 0) done else -e.errno
            } catch (e: InterruptedIOException) {
                // 部分写出去了就如实返回，调用方据此决定剩下的怎么办 ——
                // 谎报"全写完了"会让用户按的键悄悄丢一半。
                done += e.bytesTransferred
                return done
            } catch (_: Throwable) {
                return if (done > 0) done else -1
            }
        }
        return done
    }

    /**
     * 给**整个进程组**发信号（`kill(-pid, sig)`）。子进程是会话/组长，所以
     * 这样能把它拉起来的后台 job 一并收掉，不留孤儿。
     *
     * 推荐收尾顺序（顺序写反就是 fd 复用竞态）：
     * ```
     * signalGroup(pid, OsConstants.SIGHUP)   // 先礼
     * // 等 ~1s，还活着就
     * signalGroup(pid, OsConstants.SIGKILL)  // 后兵
     * waitFor(pid)                           // 必须等它真的没了
     * pfd.close()                            // 【此时才】关 master
     * ```
     * 最后一步不能提前：reader 线程还阻塞在这个 fd 上，提前关会让 fd 号被别的
     * 线程 open 复用，reader 就读到了别人的文件。
     *
     * 顺带一提，关掉 master 本身也是一种 kill —— 最后一个 master fd 关闭时
     * 内核会给前台进程组发 SIGHUP，所以即使 kill 全失败，收尾也兜得住。
     *
     * @return false 表示信号没发出去（多半是 ESRCH：进程已经没了）。
     */
    fun signalGroup(pid: Int, signal: Int): Boolean {
        if (pid <= 0) return false
        return runCatching {
            Os.kill(-pid, signal)
            true
        }.getOrDefault(false)
    }

    /** 只发给子进程本身（不含它的后台 job）。一般用 [signalGroup]。 */
    fun signalProcess(pid: Int, signal: Int): Boolean {
        if (pid <= 0) return false
        return runCatching {
            Os.kill(pid, signal)
            true
        }.getOrDefault(false)
    }

    /**
     * 把 [waitFor] 的返回值翻译成能直接显示给用户的中文。
     *
     * `-9`（SIGKILL）那条尤其重要：Android 12+ 的 phantom process killer 会
     * 直接杀掉 app fork 出来的子进程，用户看到的就是 Termux 里那句
     * `[Process completed (signal 9)]`。不解释清楚，所有人都会以为是我们的 bug。
     */
    fun describeExit(code: Int): String = when {
        code == WAIT_FAILED -> "无法获取退出状态（可能已被回收）"
        code == WAIT_RUNNING -> "仍在运行"
        code == EXIT_EXEC_FAILED -> "命令无法执行（退出码 127，详情见上面终端里的 exec 失败信息）"
        code == EXIT_PTS_FAILED -> "无法打开伪终端从设备（退出码 126）"
        code >= 0 -> "退出码 $code"
        code == -OsConstants.SIGHUP -> "终端已关闭（SIGHUP）"
        code == -OsConstants.SIGINT -> "被 Ctrl-C 中断（SIGINT）"
        code == -OsConstants.SIGQUIT -> "被 Ctrl-\\ 退出（SIGQUIT）"
        code == -OsConstants.SIGSEGV -> "程序崩溃（SIGSEGV）"
        code == -OsConstants.SIGKILL ->
            "进程被系统强制结束（SIGKILL）。Android 12 及以上会限制 app 派生的" +
                "后台进程数量与 CPU 占用，这不是命令本身的错误。" +
                "可以重开会话；也可以用 adb 关掉该限制：\n" +
                "  Android 12： adb shell device_config put activity_manager " +
                "max_phantom_processes 2147483647\n" +
                "  Android 12L/13： adb shell settings put global " +
                "settings_enable_monitor_phantom_procs false\n" +
                "  Android 14+： 开发者选项里关闭「停用子进程限制」"
        else -> "被信号 ${-code} 终止"
    }

    /** 把错误码变成日志里认得出的名字。不做 UI 展示，只给 Log 用。 */
    fun errorName(code: Int): String = when (code) {
        0 -> "OK"
        ERR_BAD_ARGS -> "ERR_BAD_ARGS"
        ERR_ALLOC -> "ERR_ALLOC"
        ERR_OPEN_PTMX -> "ERR_OPEN_PTMX(SELinux?)"
        ERR_UNLOCKPT -> "ERR_UNLOCKPT"
        ERR_PTSNAME -> "ERR_PTSNAME"
        ERR_OPEN_PTS -> "ERR_OPEN_PTS"
        ERR_FORK -> "ERR_FORK"
        ERR_EXEC_NOT_FOUND -> "ERR_EXEC_NOT_FOUND"
        ERR_WIN_SIZE -> "ERR_WIN_SIZE"
        ERR_UNAVAILABLE -> "ERR_UNAVAILABLE"
        ERR_JNI -> "ERR_JNI"
        else -> "ERR($code)"
    }

    // -----------------------------------------------------------------
    // 自检
    // -----------------------------------------------------------------

    private const val SELF_TEST_MARKER = "biji-pty-ok"
    private const val SELF_TEST_TIMEOUT_MS = 2000L
    private const val SELF_TEST_POLL_MS = 200

    /**
     * 门控自检。**会 fork 并阻塞最多约 2 秒，别在主线程调。**
     *
     * 两级：
     *  1. `nSelfTest()` 走一遍 open(/dev/ptmx) → unlockpt → ptsname_r →
     *     open(slave) → TIOCSWINSZ。必须真跑 unlockpt/ptsname_r（TIOCSPTLCK
     *     和 TIOCGPTN）—— 这两个 ioctl 正是 OEM 魔改 SELinux 策略时最可能被
     *     收窄掉的，只 open 一下就返回成功等于没测。
     *  2. 真起一个子进程跑 `echo`，从 master 把内容读回来。这才能证明
     *     fork/setsid/控制终端/dup2/execve 整条链在这台设备上是通的。
     *
     * 用 `/system/bin/echo`；万一某个 ROM 没有（toybox 布局被改过），退回
     * `/system/bin/sh -c echo`。两个都不行才判失败。
     *
     * 注意读回来的是 `"biji-pty-ok\r\n"` —— pty 处于 cooked 模式，ONLCR 会把
     * `\n` 变成 `\r\n`。所以这里判**包含**而不是相等。
     */
    fun selfTest(): Boolean = runCatching {
        if (!available) return@runCatching false
        if (nSelfTest() != 0) return@runCatching false
        echoRoundTrip("/system/bin/echo", arrayOf("echo", SELF_TEST_MARKER)) ||
            echoRoundTrip("/system/bin/sh", arrayOf("sh", "-c", "echo $SELF_TEST_MARKER"))
    }.getOrDefault(false)

    private fun echoRoundTrip(cmd: String, argv: Array<String>): Boolean {
        val started = start(
            cmd = cmd,
            cwd = "/",
            argv = argv,
            // 自检故意给一份最小环境：连 PATH 都写死，免得被容器环境影响结论。
            envp = arrayOf("PATH=/system/bin", "TERM=dumb", "LANG=C"),
            rows = 24,
            cols = 80
        )
        if (!started.ok) return false

        val pfd = adoptMaster(started.masterFd)
        if (pfd == null) {
            close(started.masterFd)
            reap(started.pid)
            return false
        }

        var hit = false
        try {
            val fd = pfd.fileDescriptor
            val buf = ByteArray(256)
            val sb = StringBuilder()
            val deadline = SystemClock.elapsedRealtime() + SELF_TEST_TIMEOUT_MS
            while (!hit && SystemClock.elapsedRealtime() < deadline) {
                // poll 而不是直接 read：read 会一直阻塞，自检不能没有上界。
                if (!waitReadable(fd, SELF_TEST_POLL_MS)) continue
                val n = readMaster(fd, buf)
                if (n <= 0) break  // 0 = EIO，子进程已经结束
                sb.append(String(buf, 0, n, Charsets.UTF_8))
                hit = sb.contains(SELF_TEST_MARKER)
            }
        } finally {
            // 先收进程再关 fd，顺序见 signalGroup 的说明。
            reap(started.pid)
            runCatching { pfd.close() }
        }
        return hit
    }

    /** 自检收尾：直接 SIGKILL（echo 早该退了，还活着说明卡住了）再回收。 */
    private fun reap(pid: Int) {
        signalGroup(pid, OsConstants.SIGKILL)
        waitFor(pid)
    }

    private fun waitReadable(fd: FileDescriptor, timeoutMs: Int): Boolean = runCatching {
        val p = StructPollfd()
        p.fd = fd
        p.events = OsConstants.POLLIN.toShort()
        if (Os.poll(arrayOf(p), timeoutMs) <= 0) return@runCatching false
        // POLLHUP 也算"可读"：子进程退出时往往只剩 HUP，此时 read 会返回 EIO，
        // 正好让上面的循环干净地退出，而不是空转到超时。
        (p.revents.toInt() and (OsConstants.POLLIN or OsConstants.POLLHUP)) != 0
    }.getOrDefault(false)
}

/**
 * [NativePty.start] 的结果。
 *
 * 只有 `val` + Int 字段，Compose 编译器会自动推断为 stable，可以安全地放进
 * `remember` / state 里，不需要 `@Immutable`。
 *
 * [masterFd] 的所有权归调用方：要么交给 [NativePty.adoptMaster]，要么自己
 * [NativePty.close]，**二选一**。
 */
data class PtyStart(
    val masterFd: Int,
    val pid: Int,
    /** 0 = 成功；否则是 `NativePty.ERR_*`。 */
    val error: Int
) {
    val ok: Boolean get() = error == 0 && masterFd >= 0 && pid > 0

    internal companion object {
        fun failure(error: Int) = PtyStart(masterFd = -1, pid = -1, error = error)
    }
}
