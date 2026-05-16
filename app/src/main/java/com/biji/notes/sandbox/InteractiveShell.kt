package com.biji.notes.sandbox

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter

/** 长连接 shell — stdin/stdout/stderr 在多条命令之间保持打开，
 *  cwd / 环境 / 函数都跟着会话走。装了 Termux bootstrap 就用
 *  termux/usr/bin/bash 启，没装就用系统 sh。装了 proot 就把 bash
 *  包到 proot 里跑，apt / gcc 在硬编码 prefix 路径下也能正常工作。 */
class InteractiveShell(
    private val workDir: File,
    private val extraPathDirs: List<String> = emptyList(),
    private val scope: CoroutineScope,
    private val termux: TermuxBootstrap? = null,
    private val proot: ProotBootstrap? = null
) {

    data class Chunk(val text: String, val isStderr: Boolean = false)

    private val _output = MutableSharedFlow<Chunk>(
        replay = 256,
        extraBufferCapacity = 1024
    )
    val output: SharedFlow<Chunk> = _output.asSharedFlow()

    private var process: Process? = null
    private var stdin: OutputStream? = null
    private var stdinWriter: PrintWriter? = null
    private var stdoutJob: Job? = null
    private var stderrJob: Job? = null

    val alive: Boolean get() = process?.isAlive == true

    fun start() {
        if (alive) return
        workDir.mkdirs()
        val termuxOn = termux?.installed == true
        val prootOn = termuxOn && proot?.installed == true
        val shellPath = termux?.takeIf { termuxOn }?.preferredShell()?.absolutePath ?: "sh"
        val args = when {
            prootOn -> proot!!.wrapForProot("exec bash -l")
            termuxOn -> listOf(shellPath, "-l")
            else -> listOf(shellPath)
        }
        val pb = ProcessBuilder(args)
            .directory(workDir)
            .redirectErrorStream(false)
        val env = pb.environment()
        val current = env["PATH"] ?: System.getenv("PATH") ?: "/system/bin:/system/xbin"
        val pathParts = buildList {
            if (termuxOn) add(termux!!.binDir.absolutePath)
            addAll(extraPathDirs)
            add(current)
        }
        env["PATH"] = pathParts.joinToString(":")
        env["TERM"] = "xterm-256color"
        env["LANG"] = "C.UTF-8"
        env["ANDROID_DATA"] = "/data"
        env["ANDROID_ROOT"] = "/system"
        if (termuxOn) {
            termux!!.envFor().forEach { (k, v) ->
                if (k != "PATH") env[k] = v
            }
            env["TERMUX_PREFIX"] = termux.usrDir.absolutePath
            env["SHELL"] = shellPath
            if (prootOn) {
                // proot 自身需要 PROOT_TMP_DIR；不要再 LD_PRELOAD
                // libtermux-exec —— proot 已经在 syscall 层改写路径。
                proot!!.envFor().forEach { (k, v) -> env[k] = v }
            } else {
                val exec = java.io.File(termux.libDir, "libtermux-exec.so")
                if (exec.exists()) env["LD_PRELOAD"] = exec.absolutePath
            }
        } else {
            env["HOME"] = workDir.absolutePath
        }
        // Termux 二进制装在 app-private 里，targetSdk≥29 时直接
        // ProcessBuilder.start() 会 EACCES。我们 targetSdk 28 绕开
        // 了，但仍可能遇到 chmod 没生效之类的边缘情况 —— 把异常
        // 转成红字提示，别再让会话整个挂掉。
        if (termuxOn) {
            runCatching { android.system.Os.chmod(shellPath, 0b111_101_101) }
        }
        process = try {
            pb.start()
        } catch (e: java.io.IOException) {
            scope.launch {
                _output.emit(Chunk("无法启动 shell: ${e.message ?: e.javaClass.simpleName}\n", true))
                _output.emit(Chunk("路径: $shellPath\n", true))
            }
            return
        }
        val p = process ?: return
        stdin = p.outputStream
        stdinWriter = PrintWriter(OutputStreamWriter(p.outputStream, Charsets.UTF_8), true)
        stdoutJob = scope.launch(Dispatchers.IO) { pump(p.inputStream.bufferedReader(), false) }
        stderrJob = scope.launch(Dispatchers.IO) { pump(p.errorStream.bufferedReader(), true) }
        scope.launch { _output.emit(Chunk("→ ${workDir.absolutePath}\n", false)) }
    }

    private suspend fun pump(reader: BufferedReader, stderr: Boolean) {
        try {
            val buf = CharArray(4096)
            while (scope.isActive) {
                val n = reader.read(buf)
                if (n <= 0) break
                _output.emit(Chunk(String(buf, 0, n), stderr))
            }
        } catch (_: Exception) {
        }
    }

    /** 整行命令，自带回显 + 换行。 */
    fun send(line: String) {
        scope.launch { _output.emit(Chunk("$ $line\n", false)) }
        stdinWriter?.println(line)
        stdinWriter?.flush()
    }

    /** 原始字节直接写入 stdin —— 给 Esc / ^C / ^D / 箭头键用。
     *  支持几个简写：空字符串 = 不发，"^X" = Ctrl-X，"[A" 等
     *  = CSI 转义（前面自动补 ESC）。 */
    fun sendRaw(seq: String) {
        if (seq.isEmpty()) return
        val bytes: ByteArray = when {
            seq.length == 2 && seq[0] == '^' -> {
                // ^A..^Z → 1..26
                val c = seq[1].uppercaseChar()
                byteArrayOf((c.code - 'A'.code + 1).toByte())
            }
            seq.startsWith("[") -> byteArrayOf(0x1B, *seq.toByteArray(Charsets.UTF_8))
            seq == "ESC" -> byteArrayOf(0x1B)
            else -> seq.toByteArray(Charsets.UTF_8)
        }
        runCatching {
            stdin?.write(bytes)
            stdin?.flush()
        }
    }

    /** 关 stdin 让前台命令看到 EOF —— 没 PTY 没法真的发 SIGINT。 */
    fun interrupt() {
        runCatching { stdinWriter?.close() }
        stdinWriter = null
        stdin = null
    }

    fun shutdown() {
        runCatching { stdinWriter?.close() }
        runCatching { process?.destroyForcibly() }
        stdoutJob?.cancel()
        stderrJob?.cancel()
        process = null
    }
}
