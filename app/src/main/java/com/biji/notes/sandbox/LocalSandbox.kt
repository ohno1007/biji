package com.biji.notes.sandbox

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Per-conversation project workspace plus a global shell.
 *
 * File-system reads / writes / listings are scoped to a per-conversation
 * project folder living under `<external files>/projects/<folder>`.
 * Each conversation can pick its own folder name (see
 * SettingsRepository.setConvoFolder); paths handed to read_file /
 * write_file / list_directory are resolved relative to that folder and
 * cannot escape it.
 *
 * `runShell`, by contrast, runs in the global Android environment — it
 * uses the project folder as the default cwd but the command itself can
 * touch anywhere the app process can reach. A small denylist refuses
 * the obviously-destructive shapes (`rm -rf /`, fork bombs, `mkfs`,
 * `dd of=/dev/`, `shred`).
 */
class LocalSandbox(private val context: Context) {

    /** Set of relative paths (prefixed with `<folder>/`) the assistant
     *  has written / created during this app session. The project drawer
     *  surfaces a small ⚡ badge next to these. */
    private val _aiEditedPaths = MutableStateFlow<Set<String>>(emptySet())
    val aiEditedPaths: StateFlow<Set<String>> = _aiEditedPaths.asStateFlow()

    /** Pre-edit snapshots of each file the assistant has modified this
     *  session, keyed by `<folder>/<relpath>`. The snapshot is the
     *  on-disk content captured *before* the write applies — earliest
     *  per session wins. */
    private val preEditSnapshots = mutableMapOf<String, String>()

    private fun editKey(folder: String?, path: String): String {
        val f = (folder ?: DEFAULT_FOLDER).trim('/')
        val p = path.trimStart('/')
        return "$f/$p"
    }

    fun markAiEdited(folder: String?, path: String) {
        _aiEditedPaths.value = _aiEditedPaths.value + editKey(folder, path)
    }

    fun captureSnapshot(folder: String?, path: String, content: String) {
        val key = editKey(folder, path)
        if (key !in preEditSnapshots) preEditSnapshots[key] = content
    }

    fun snapshotBefore(folder: String?, path: String): String? =
        preEditSnapshots[editKey(folder, path)]

    fun clearAiEdited() {
        _aiEditedPaths.value = emptySet()
        preEditSnapshots.clear()
    }

    /** Base directory that contains every per-conversation project root. */
    val projectsBase: File
        get() {
            val base = context.getExternalFilesDir(null) ?: context.filesDir
            return File(base, "projects").also { it.mkdirs() }
        }

    /** Project root for [folder]. Falls back to `projects/default` when
     *  the folder is null or blank. Created lazily; survives across runs. */
    fun projectRoot(folder: String?): File {
        val name = folder?.trim()?.trim('/')?.ifEmpty { null } ?: DEFAULT_FOLDER
        return File(projectsBase, name).also { it.mkdirs() }
    }

    /** Map a user-supplied relative path to a real [File] inside the
     *  [folder]'s project root. Throws [SecurityException] if the
     *  resolved path would escape. */
    fun resolveInProject(folder: String?, path: String): File {
        val root = projectRoot(folder)
        val cleaned = path.trim().trimStart('/')
        val target = File(root, cleaned).canonicalFile
        val rootCanon = root.canonicalFile.path
        if (target.path != rootCanon && !target.path.startsWith("$rootCanon/")) {
            throw SecurityException("Path escapes project: $path")
        }
        return target
    }

    data class FileEntry(
        val name: String,
        val path: String,
        val isDirectory: Boolean,
        val sizeBytes: Long,
        val lastModified: Long
    )

    suspend fun listDirectory(folder: String?, path: String): List<FileEntry> =
        withContext(Dispatchers.IO) {
            val dir = resolveInProject(folder, path)
            if (!dir.exists()) error("Path not found: $path")
            if (!dir.isDirectory) error("Not a directory: $path")
            val root = projectRoot(folder)
            (dir.listFiles() ?: emptyArray()).map { f ->
                FileEntry(
                    name = f.name,
                    path = f.relativeTo(root).path,
                    isDirectory = f.isDirectory,
                    sizeBytes = if (f.isDirectory) -1L else f.length(),
                    lastModified = f.lastModified()
                )
            }.sortedWith(compareBy({ !it.isDirectory }, { it.name }))
        }

    suspend fun readFile(
        folder: String?,
        path: String,
        maxBytes: Int = 64 * 1024
    ): String = withContext(Dispatchers.IO) {
        val file = resolveInProject(folder, path)
        if (!file.exists()) error("File not found: $path")
        if (file.isDirectory) error("Path is a directory: $path")
        val bytes = file.readBytes()
        if (bytes.size <= maxBytes) String(bytes, Charsets.UTF_8)
        else String(bytes.copyOfRange(0, maxBytes), Charsets.UTF_8) +
            "\n…[truncated at $maxBytes bytes, total ${bytes.size}]"
    }

    suspend fun writeFile(
        folder: String?,
        path: String,
        content: String,
        append: Boolean = false
    ) {
        withContext(Dispatchers.IO) {
            val file = resolveInProject(folder, path)
            file.parentFile?.mkdirs()
            if (append) file.appendText(content, Charsets.UTF_8)
            else file.writeText(content, Charsets.UTF_8)
        }
    }

    /**
     * Copy whatever sits behind [uri] into the active project folder
     * as a binary blob, picking a non-clobbering filename derived from
     * the picker's display name (or the URI's last segment). Returns
     * the in-project relative path plus the final byte count so the
     * chat can post a "attached foo.zip (12 KB)" notice the AI sees.
     */
    suspend fun importUri(
        folder: String?,
        uri: android.net.Uri,
        displayName: String?
    ): Pair<String, Long> = withContext(Dispatchers.IO) {
        val cleanName = (displayName ?: uri.lastPathSegment ?: "attachment.bin")
            .substringAfterLast('/')
            .replace(Regex("""[^A-Za-z0-9._\- ]"""), "_")
            .ifBlank { "attachment.bin" }
        val root = projectRoot(folder)
        // Avoid stomping on an existing file with the same name.
        var target = File(root, cleanName)
        if (target.exists()) {
            val base = cleanName.substringBeforeLast('.', cleanName)
            val ext = cleanName.substringAfterLast('.', "").let { if (it.isBlank()) "" else ".$it" }
            var n = 1
            while (target.exists()) {
                target = File(root, "$base-$n$ext")
                n++
            }
        }
        target.parentFile?.mkdirs()
        val bytes = context.contentResolver.openInputStream(uri).use { input ->
            if (input == null) error("cannot open content uri")
            target.outputStream().use { out -> input.copyTo(out) }
        }
        markAiEdited(folder, target.relativeTo(root).path)
        target.relativeTo(root).path to bytes
    }

    data class ShellResult(
        val command: String,
        val workingDir: String,
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
        val durationMs: Long,
        val timedOut: Boolean,
        /** True if the denylist rejected the command before exec. */
        val blocked: Boolean = false,
        val blockedReason: String? = null,
        /** True if this command was executed through `su -c …`. */
        val ranAsRoot: Boolean = false
    )

    /**
     * Probe for Magisk / SuperSU style root. Returns true if `su -c id`
     * completes within a short window with a `uid=0(` line. Suppressed
     * exceptions just count as "no root". This call DOES trigger the
     * superuser prompt on rooted devices the first time, which is the
     * intended behaviour for the Settings "申请 root 权限" button.
     */
    suspend fun probeRoot(timeoutMs: Long = 8_000L): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
                val finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
                if (!finished) {
                    p.destroyForcibly()
                    return@runCatching false
                }
                val out = p.inputStream.bufferedReader().use { it.readText() }
                p.exitValue() == 0 && out.contains("uid=0(")
            }.getOrDefault(false)
        }

    /**
     * Detect whether Termux is installed at its canonical sandbox path.
     * Needs root because the Termux app's private data dir isn't
     * world-readable on stock Android. Returns the bin path on success
     * (so the caller can splice it into PATH), null otherwise.
     */
    suspend fun detectTermuxBin(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val p = ProcessBuilder("su", "-c", "test -x $TERMUX_BIN/sh && echo OK")
                .redirectErrorStream(true).start()
            val finished = p.waitFor(4_000, TimeUnit.MILLISECONDS)
            if (!finished) { p.destroyForcibly(); return@runCatching null }
            val out = p.inputStream.bufferedReader().use { it.readText() }
            if (p.exitValue() == 0 && out.contains("OK")) TERMUX_BIN else null
        }.getOrDefault(null)
    }

    /**
     * Run a shell command in the global Android environment via
     * `sh -c`. The project folder is the default cwd. The command is
     * NOT restricted to the project — the user explicitly opted out of
     * sandbox semantics for shell — but we refuse a handful of
     * obviously-destructive shapes via [isDangerousCommand].
     *
     * When [asRoot] is true the command is launched through `su -c`
     * instead. This still respects the denylist (so the model can't
     * `rm -rf /` even with elevation). If `su` is missing the call
     * comes back with a non-zero exit and a stderr noting that root
     * isn't available.
     */
    suspend fun runShell(
        folder: String?,
        command: String,
        cwd: String? = null,
        timeoutMs: Long = 30_000L,
        asRoot: Boolean = false
    ): ShellResult = coroutineScope {
        withContext(Dispatchers.IO) {
            val reason = isDangerousCommandReason(command)
            val workDir = when {
                cwd.isNullOrBlank() -> projectRoot(folder)
                else -> runCatching { resolveInProject(folder, cwd) }
                    .getOrElse { File(cwd) }
            }
            if (!workDir.exists()) workDir.mkdirs()
            val workDirDisplay = workDir.canonicalPath
            if (reason != null) {
                return@withContext ShellResult(
                    command = command,
                    workingDir = workDirDisplay,
                    stdout = "",
                    stderr = "拒绝执行：$reason",
                    exitCode = -1,
                    durationMs = 0L,
                    timedOut = false,
                    blocked = true,
                    blockedReason = reason,
                    ranAsRoot = asRoot
                )
            }
            val start = System.currentTimeMillis()
            val pb = if (asRoot) {
                // Wrap the user's command so the inner shell:
                //  - lands in the chosen cwd (`su` resets it),
                //  - has Termux's bin/lib prepended to PATH/LD_LIBRARY_PATH
                //    (otherwise `gcc / cmake / git / python` etc. that
                //    the user installed via `pkg install …` are
                //    invisible — that's the bug the user hit),
                //  - has HOME pointed at Termux's home so tooling (git
                //    config, python pip, ssh) finds its dotfiles.
                val envPrelude = buildString {
                    append("export PATH=$TERMUX_BIN:${'$'}PATH; ")
                    append("export LD_LIBRARY_PATH=$TERMUX_LIB:${'$'}{LD_LIBRARY_PATH:-}; ")
                    append("export HOME=$TERMUX_HOME; ")
                }
                ProcessBuilder(
                    "su",
                    "-c",
                    "$envPrelude cd ${shellQuote(workDirDisplay)} && $command"
                )
            } else {
                ProcessBuilder("sh", "-c", command).directory(workDir)
            }
            pb.redirectErrorStream(false)
            val process = try {
                pb.start()
            } catch (e: Exception) {
                return@withContext ShellResult(
                    command = command,
                    workingDir = workDirDisplay,
                    stdout = "",
                    stderr = if (asRoot) "无法启动 su：${e.message ?: e.javaClass.simpleName}（设备未 root？）"
                    else (e.message ?: e.javaClass.simpleName),
                    exitCode = -1,
                    durationMs = System.currentTimeMillis() - start,
                    timedOut = false,
                    ranAsRoot = asRoot
                )
            }
            val outFuture = async(Dispatchers.IO) {
                process.inputStream.bufferedReader().use { it.readText() }
            }
            val errFuture = async(Dispatchers.IO) {
                process.errorStream.bufferedReader().use { it.readText() }
            }
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) process.destroyForcibly()
            val exit = if (finished) process.exitValue() else -1
            ShellResult(
                command = command,
                workingDir = workDirDisplay,
                stdout = outFuture.await(),
                stderr = errFuture.await(),
                exitCode = exit,
                durationMs = System.currentTimeMillis() - start,
                timedOut = !finished,
                ranAsRoot = asRoot
            )
        }
    }

    private fun shellQuote(s: String): String =
        "'" + s.replace("'", "'\\''") + "'"

    companion object {
        const val DEFAULT_FOLDER = "default"
        /** Termux's canonical bin path under app-private data. Splicing
         *  this into PATH lets us call `gcc / cmake / clang / make / git
         *  / python` etc. that the user installed via `pkg install …` —
         *  the directory itself is unreadable to other apps on stock
         *  Android, so calls only land if biji is running as root. */
        const val TERMUX_BIN = "/data/data/com.termux/files/usr/bin"
        const val TERMUX_LIB = "/data/data/com.termux/files/usr/lib"
        const val TERMUX_HOME = "/data/data/com.termux/files/home"

        /** Return a short human-readable reason if [cmd] looks
         *  unambiguously destructive, else null. Matches on the raw
         *  text — we intentionally don't try to lex the shell, just
         *  catch a handful of obvious shapes. */
        fun isDangerousCommand(cmd: String): Boolean = isDangerousCommandReason(cmd) != null

        fun isDangerousCommandReason(cmd: String): String? {
            val c = cmd.trim()
            // rm -rf on a filesystem root or HOME
            val rmRf = Regex("""\brm\s+(-[a-zA-Z]*r[a-zA-Z]*f|-[a-zA-Z]*f[a-zA-Z]*r|-rf|-fr)\b""")
            if (rmRf.containsMatchIn(c)) {
                val targets = Regex("""\brm\s+-[rRfF]+\s+(--no-preserve-root\s+)?(/[^\s|;&]*|[$]HOME|~|\*)""")
                if (targets.containsMatchIn(c)) return "rm -rf 在系统根或 \$HOME"
            }
            if (Regex("""\bmkfs(\.[a-zA-Z0-9]+)?\b""").containsMatchIn(c)) return "mkfs 会格式化设备"
            if (Regex("""\bdd\b[^|;&]*\bof=/dev/""").containsMatchIn(c)) return "dd 写入块设备"
            if (Regex("""\bshred\b""").containsMatchIn(c)) return "shred 不可恢复擦除"
            // Classic fork bomb :(){ :|:& };:
            if (c.replace(" ", "").contains(":(){:|:&};:")) return "fork bomb"
            // Stupidly dangerous: chmod 000 /  or  chown -R nobody /
            if (Regex("""\bchmod\s+[-0-7]+\s+/(?:\s|$)""").containsMatchIn(c)) return "chmod 改根目录权限"
            if (Regex("""\bchown\s+[^|;&]*\s+/(?:\s|$)""").containsMatchIn(c)) return "chown 改根目录归属"
            // Reboot / shutdown
            if (Regex("""\b(reboot|shutdown|halt|poweroff)\b""").containsMatchIn(c)) return "关机 / 重启"
            return null
        }
    }
}
