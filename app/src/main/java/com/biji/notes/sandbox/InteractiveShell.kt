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
 *  cwd / 环境 / 函数都跟着会话走。用系统 sh 启，PATH 里加我们
 *  toybox bootstrap 的目录。 */
class InteractiveShell(
    private val workDir: File,
    private val extraPathDirs: List<String> = emptyList(),
    private val scope: CoroutineScope
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
        val pb = ProcessBuilder("sh")
            .directory(workDir)
            .redirectErrorStream(false)
        val env = pb.environment()
        val current = env["PATH"] ?: System.getenv("PATH") ?: "/system/bin:/system/xbin"
        env["PATH"] = (extraPathDirs + current).joinToString(":")
        env["TERM"] = "xterm-256color"
        env["LANG"] = "C.UTF-8"
        env["HOME"] = workDir.absolutePath
        process = try {
            pb.start()
        } catch (e: java.io.IOException) {
            scope.launch {
                _output.emit(Chunk("无法启动 shell: ${e.message ?: e.javaClass.simpleName}\n", true))
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
