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
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter

/**
 * A long-lived `sh` process that biji's Terminal screen drives like a
 * real PTY. Stdin / stdout / stderr are kept open across commands so
 * shell state (cwd, env vars, exported functions) survives between
 * inputs — same model as Termux's terminal, just without the visual
 * fanciness of a real PTY (no ANSI cursor, but ANSI colours still
 * survive into the rendered text).
 *
 * Output lines flow through [output] as they arrive (stdout-first,
 * stderr-tagged) so the UI can render them live. Sending an empty
 * line is allowed — the shell will echo a prompt.
 */
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
    private var stdinWriter: PrintWriter? = null
    private var stdoutJob: Job? = null
    private var stderrJob: Job? = null

    val alive: Boolean get() = process?.isAlive == true

    fun start() {
        if (alive) return
        workDir.mkdirs()
        val env = buildPathEnv()
        val pb = ProcessBuilder("sh")
            .directory(workDir)
            .redirectErrorStream(false)
        pb.environment().putAll(env)
        // `PS1` so the prompt is non-empty and recognisable as a
        // ready signal. Force interactive shell flags.
        process = pb.start()
        val p = process ?: return
        stdinWriter = PrintWriter(OutputStreamWriter(p.outputStream, Charsets.UTF_8), true)
        stdoutJob = scope.launch(Dispatchers.IO) { pump(p.inputStream.bufferedReader(), false) }
        stderrJob = scope.launch(Dispatchers.IO) { pump(p.errorStream.bufferedReader(), true) }
        // Surface a banner so the user sees the shell is alive.
        scope.launch { _output.emit(Chunk("biji-shell · ${workDir.absolutePath}\n", false)) }
    }

    private fun buildPathEnv(): Map<String, String> {
        val current = System.getenv("PATH") ?: "/system/bin:/system/xbin"
        val merged = (extraPathDirs + current).joinToString(":")
        return mapOf(
            "PATH" to merged,
            "HOME" to workDir.absolutePath,
            "TERM" to "xterm-256color",
            "LANG" to "C.UTF-8"
        )
    }

    private suspend fun pump(reader: BufferedReader, stderr: Boolean) {
        try {
            val buf = CharArray(4096)
            while (scope.isActive) {
                val n = reader.read(buf)
                if (n <= 0) break
                emit(String(buf, 0, n), stderr)
            }
        } catch (_: Exception) {
            // closed
        }
    }

    private suspend fun emit(text: String, stderr: Boolean) {
        _output.emit(Chunk(text, stderr))
    }

    /** Push a line of input. The user's command shows up echoed in
     *  the output stream so the terminal looks like a real session. */
    fun send(line: String) {
        scope.launch { _output.emit(Chunk("$ $line\n", false)) }
        stdinWriter?.println(line)
        stdinWriter?.flush()
    }

    /** Best-effort interrupt: tears down stdin so the current foreground
     *  job in `sh` sees EOF, which usually wakes it from a `read` and
     *  lets the next command run. We can't deliver a real SIGINT
     *  without a PTY. */
    fun interrupt() {
        runCatching { stdinWriter?.close() }
        stdinWriter = null
    }

    fun shutdown() {
        runCatching { stdinWriter?.close() }
        runCatching { process?.destroyForcibly() }
        stdoutJob?.cancel()
        stderrJob?.cancel()
        process = null
    }
}
