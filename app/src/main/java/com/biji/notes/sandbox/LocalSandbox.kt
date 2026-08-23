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
class LocalSandbox(
    private val context: Context,
    private val bootstrap: BijiBootstrap? = null
) {

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

    /** folder -> 目录名。归一化规则是全 app 共享的一份，[AiContainerManager]
     *  和 [TerminalSessionManager] 算出来的必须是同一个字符串。 */
    private fun folderKey(folder: String?): String =
        folder?.trim()?.trim('/')?.ifEmpty { null } ?: DEFAULT_FOLDER

    /** Project root for [folder]. Falls back to `projects/default` when
     *  the folder is null or blank. Created lazily; survives across runs. */
    fun projectRoot(folder: String?): File =
        File(projectsBase, folderKey(folder)).also { it.mkdirs() }

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
    suspend fun deleteEntry(folder: String?, path: String): Boolean =
        withContext(Dispatchers.IO) {
            val target = resolveInProject(folder, path)
            if (!target.exists()) false
            else target.deleteRecursively()
        }

    suspend fun copyEntry(folder: String?, src: String, dst: String) =
        withContext(Dispatchers.IO) {
            val from = resolveInProject(folder, src)
            val to = resolveInProject(folder, dst)
            if (!from.exists()) error("Source not found: $src")
            to.parentFile?.mkdirs()
            if (from.isDirectory) from.copyRecursively(to, overwrite = true)
            else from.copyTo(to, overwrite = true)
        }

    suspend fun moveEntry(folder: String?, src: String, dst: String) =
        withContext(Dispatchers.IO) {
            val from = resolveInProject(folder, src)
            val to = resolveInProject(folder, dst)
            if (!from.exists()) error("Source not found: $src")
            to.parentFile?.mkdirs()
            if (!from.renameTo(to)) {
                // Cross-fs fallback.
                if (from.isDirectory) {
                    from.copyRecursively(to, overwrite = true)
                    from.deleteRecursively()
                } else {
                    from.copyTo(to, overwrite = true)
                    from.delete()
                }
            }
        }

    data class ExtractedEntry(val path: String, val sizeBytes: Long, val isDirectory: Boolean)

    /**
     * Pure-Kotlin zip extractor — no shell, no Termux. Honours the
     * sandbox: every entry's resolved path is checked to stay under
     * the project root (defeats zip-slip). Returns the list of
     * extracted entries so the AI can confirm what landed where.
     */
    suspend fun extractZip(
        folder: String?,
        archivePath: String,
        destDir: String
    ): List<ExtractedEntry> = withContext(Dispatchers.IO) {
        val archive = resolveInProject(folder, archivePath)
        val outBase = resolveInProject(folder, destDir)
        if (!archive.exists()) error("Archive not found: $archivePath")
        outBase.mkdirs()
        val out = mutableListOf<ExtractedEntry>()
        java.util.zip.ZipInputStream(archive.inputStream()).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                // Re-resolve through resolveInProject to enforce the
                // sandbox boundary on each entry path.
                val target = resolveInProject(folder, "$destDir/${e.name}")
                if (e.isDirectory) {
                    target.mkdirs()
                    out += ExtractedEntry(
                        target.relativeTo(projectRoot(folder)).path, 0L, true
                    )
                } else {
                    target.parentFile?.mkdirs()
                    var size = 0L
                    target.outputStream().use { os ->
                        val buf = ByteArray(8192)
                        while (true) {
                            val n = zin.read(buf)
                            if (n <= 0) break
                            os.write(buf, 0, n)
                            size += n
                        }
                    }
                    out += ExtractedEntry(
                        target.relativeTo(projectRoot(folder)).path, size, false
                    )
                    markAiEdited(folder, target.relativeTo(projectRoot(folder)).path)
                }
                zin.closeEntry()
            }
        }
        out
    }

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
     * `$BIJI_EXEC`：AI 自己编出来的东西的落脚点，必须在**内部存储**上。
     * 项目目录在共享存储里，那卷是 noexec 的 —— 在那儿 chmod +x 完再执行
     * 只会拿到 Permission denied，这个坑踩过不止一次。
     *
     * 路径规则和 [AiContainerManager] 拼 execDir 的方式逐字一致
     * （`filesDir/exec/<key>`），差一个字符就是两套目录，表现为
     * 「AI 装的东西 run_shell_command 里 not found」。
     */
    fun execDir(folder: String?): File {
        val base = File(context.filesDir, "exec")
        return File(base, folderKey(folder)).also { it.mkdirs() }
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
     *
     * root 与非 root 拿到的是**同一份环境**（PATH / HOME / BIJI_*）：差别只在
     * 谁来执行，不在能找到什么命令。以前 root 分支会额外挂上 Termux 的目录，
     * 于是同一条命令 asRoot 开和不开跑出两种结果，非常难查。
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
            // 环境按**我们自己的容器**来，root 与非 root 完全同一份。
            //
            // 以前 asRoot 会往 PATH 上拼 Termux 的 usr/bin、再把 LD_LIBRARY_PATH
            // 和 HOME 指到 Termux 里去。那条路必须死掉：Termux 的包把
            // `/data/data/com.termux/files/usr` 硬编码在二进制里，借过来跑不是
            // not found 就是 loader 崩，这个坑本项目已经踩过；而且它要求用户装了
            // Termux，等于把我们的开发环境挂在别人家的 app 上。
            //
            // asRoot 这个开关本身留着 —— 用户手机真有 root 时 `su -c` 依然有意义
            // （能读到 app 私有目录之外的东西），只是别再假设 Termux 存在。
            val home = projectRoot(folder)
            val execDirEnv = execDir(folder)
            // 容器那边靠 ContainerLayout.mkdirs() 建这两个目录，但 run_shell_command
            // 可能先于任何容器被调到。导出一个不存在的 $BIJI_WORK 只会让模型
            // 第一条 `cd "$BIJI_WORK"` 就失败，顺手建掉。
            val workDirEnv = File(home, "work").also { it.mkdirs() }
            val tmpDirEnv = File(home, "tmp").also { it.mkdirs() }
            val binDir = bootstrap?.binDir?.absolutePath?.takeIf { it.isNotBlank() }
            val pathParts = buildList {
                binDir?.let { add(it) }
                add(execDirEnv.absolutePath)
            }
            val envPrelude = buildString {
                if (binDir != null) append("export BIJI_BIN=${shellQuote(binDir)}; ")
                append("export BIJI_EXEC=${shellQuote(execDirEnv.absolutePath)}; ")
                append("export BIJI_WORK=${shellQuote(workDirEnv.absolutePath)}; ")
                append("export BIJI_TMP=${shellQuote(tmpDirEnv.absolutePath)}; ")
                append("export PATH=${shellQuote(pathParts.joinToString(":"))}:${'$'}PATH; ")
                // su 进去 HOME 会变成 /root（或干脆没有），工具往那儿写配置就
                // 散落在容器外面。指回项目根，和 ContainerLayout.applyEnv 一致 ——
                // 「session 里能跑、job 里 not found」那类问题都出在两边环境不一样。
                append("export HOME=${shellQuote(home.absolutePath)}; ")
            }
            val pb = if (asRoot) {
                ProcessBuilder(
                    "su",
                    "-c",
                    "$envPrelude cd ${shellQuote(workDirDisplay)} && $command"
                )
            } else {
                ProcessBuilder("sh", "-c", "$envPrelude$command").directory(workDir)
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
