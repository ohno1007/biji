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
 * Local app-scoped sandbox: read/write/list inside the app's private
 * external files dir and run small shell commands inside the same
 * tree. All paths are resolved relative to [root] and checked to make
 * sure they never escape it (no `../` traversal, no absolute paths
 * pointing outside the sandbox).
 *
 * The user can populate this dir with their own files via the system
 * file picker (it's under `Android/data/com.biji.notes/files/projects/
 * default`) and the assistant gets read / write / shell access to it
 * via the tool calls in [com.biji.notes.net.Tools].
 */
class LocalSandbox(private val context: Context) {

    /** Set of relative paths the assistant has written / created during
     *  this app session. The project-drawer surfaces a small ⚡ badge
     *  next to these so the user can see what changed. */
    private val _aiEditedPaths = MutableStateFlow<Set<String>>(emptySet())
    val aiEditedPaths: StateFlow<Set<String>> = _aiEditedPaths.asStateFlow()

    /** Pre-edit snapshots of each file the assistant has modified this
     *  session, keyed by relative path. Captured *before* the write
     *  applies, so the diff view can compare on-disk → on-disk-after. */
    private val preEditSnapshots = mutableMapOf<String, String>()

    fun markAiEdited(path: String) {
        _aiEditedPaths.value = _aiEditedPaths.value + path.trimStart('/')
    }

    /** Stash the current content of [path] (if any) so a later
     *  [snapshotBefore] retrieval can render a diff. Idempotent on the
     *  same path within a session — we keep the *earliest* snapshot so
     *  the diff always traces back to the user's original file. */
    fun captureSnapshot(path: String, content: String) {
        val key = path.trimStart('/')
        if (key !in preEditSnapshots) preEditSnapshots[key] = content
    }

    fun snapshotBefore(path: String): String? =
        preEditSnapshots[path.trimStart('/')]

    fun clearAiEdited() {
        _aiEditedPaths.value = emptySet()
        preEditSnapshots.clear()
    }

    /** Project root. Created lazily; survives across runs. */
    val root: File
        get() {
            val base = context.getExternalFilesDir(null)
                ?: context.filesDir
            return File(base, "projects/default").also { it.mkdirs() }
        }

    /** Map a user-supplied relative path to a real [File] inside [root].
     *  Throws [SecurityException] if the resolved path would escape. */
    fun resolveInRoot(path: String): File {
        val cleaned = path.trim().trimStart('/')
        val target = File(root, cleaned).canonicalFile
        val rootCanon = root.canonicalFile.path
        if (target.path != rootCanon && !target.path.startsWith("$rootCanon/")) {
            throw SecurityException("Path escapes sandbox: $path")
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

    suspend fun listDirectory(path: String): List<FileEntry> = withContext(Dispatchers.IO) {
        val dir = resolveInRoot(path)
        if (!dir.exists()) error("Path not found: $path")
        if (!dir.isDirectory) error("Not a directory: $path")
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

    suspend fun readFile(path: String, maxBytes: Int = 64 * 1024): String =
        withContext(Dispatchers.IO) {
            val file = resolveInRoot(path)
            if (!file.exists()) error("File not found: $path")
            if (file.isDirectory) error("Path is a directory: $path")
            val bytes = file.readBytes()
            if (bytes.size <= maxBytes) String(bytes, Charsets.UTF_8)
            else String(bytes.copyOfRange(0, maxBytes), Charsets.UTF_8) +
                "\n…[truncated at $maxBytes bytes, total ${bytes.size}]"
        }

    suspend fun writeFile(path: String, content: String, append: Boolean = false) {
        withContext(Dispatchers.IO) {
            val file = resolveInRoot(path)
            file.parentFile?.mkdirs()
            if (append) file.appendText(content, Charsets.UTF_8)
            else file.writeText(content, Charsets.UTF_8)
        }
    }

    data class ShellResult(
        val command: String,
        val workingDir: String,
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
        val durationMs: Long,
        val timedOut: Boolean
    )

    /**
     * Run a shell command via `sh -c` inside the sandbox. stdout and
     * stderr are drained on separate threads to avoid pipe deadlock for
     * large outputs. Times out after [timeoutMs] and reports the
     * partial output.
     */
    suspend fun runShell(
        command: String,
        cwd: String? = null,
        timeoutMs: Long = 30_000L
    ): ShellResult = coroutineScope {
        withContext(Dispatchers.IO) {
            val workDir = if (cwd.isNullOrBlank()) root else resolveInRoot(cwd)
            if (!workDir.exists()) workDir.mkdirs()
            val start = System.currentTimeMillis()
            val process = ProcessBuilder("sh", "-c", command)
                .directory(workDir)
                .redirectErrorStream(false)
                .start()
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
                workingDir = workDir.relativeToOrSelf(root).path.ifEmpty { "." },
                stdout = outFuture.await(),
                stderr = errFuture.await(),
                exitCode = exit,
                durationMs = System.currentTimeMillis() - start,
                timedOut = !finished
            )
        }
    }
}
