package com.biji.notes.sandbox

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** App-private toybox install. [binDir] always sits on the shell PATH. */
class BijiBootstrap(private val context: Context) {

    val rootDir: File get() = File(context.filesDir, "bootstrap").also { it.mkdirs() }
    val binDir: File get() = File(rootDir, "bin").also { it.mkdirs() }
    val installed: Boolean
        get() = (binDir.listFiles()?.any { it.canExecute() } == true)
    val multicallBinary: File get() = File(binDir, "toybox")

    sealed interface Progress {
        data object Idle : Progress
        data class Downloading(val fraction: Float, val bytes: Long, val total: Long) : Progress
        data class Linking(val current: Int, val total: Int) : Progress
        data class Done(val appletCount: Int) : Progress
        data class Failed(val message: String) : Progress
    }

    private val _progress = MutableStateFlow<Progress>(Progress.Idle)
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    suspend fun install(url: String? = null): Boolean = withContext(Dispatchers.IO) {
        val effectiveUrl = url ?: defaultUrlForAbi()
        try {
            // 1. Download.
            _progress.value = Progress.Downloading(0f, 0L, 0L)
            val conn = (URL(effectiveUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
            }
            conn.connect()
            if (conn.responseCode !in 200..299) {
                _progress.value = Progress.Failed(
                    "下载失败: HTTP ${conn.responseCode} — $effectiveUrl"
                )
                return@withContext false
            }
            val total = conn.contentLengthLong.coerceAtLeast(0L)
            val tmp = File(binDir, "toybox.part")
            tmp.outputStream().use { out ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(16 * 1024)
                    var read = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        read += n
                        _progress.value = Progress.Downloading(
                            if (total > 0) read.toFloat() / total else 0f,
                            read, total
                        )
                    }
                }
            }
            val finalBin = multicallBinary
            if (finalBin.exists()) finalBin.delete()
            tmp.renameTo(finalBin)
            finalBin.setExecutable(true, false)

            val applets = listApplets(finalBin)
            applets.forEachIndexed { i, name ->
                if (name == "toybox" || name.isBlank()) return@forEachIndexed
                _progress.value = Progress.Linking(i + 1, applets.size)
                linkApplet(finalBin, name)
            }
            _progress.value = Progress.Done(applets.size)
            true
        } catch (e: Exception) {
            _progress.value = Progress.Failed(
                e.message ?: e.javaClass.simpleName
            )
            false
        }
    }

    suspend fun uninstall() = withContext(Dispatchers.IO) {
        rootDir.deleteRecursively()
        rootDir.mkdirs()
        binDir.mkdirs()
        _progress.value = Progress.Idle
    }

    private fun listApplets(bin: File): List<String> {
        val flavours = listOf(
            listOf(bin.absolutePath, "--long"),   // toybox
            listOf(bin.absolutePath, "--list")    // busybox
        )
        for (cmd in flavours) {
            val raw = runCatching {
                val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().use { it.readText() }
                p.waitFor()
                out
            }.getOrDefault("")
            val names = raw.lines()
                .map { it.trim().substringAfterLast('/') }
                .filter { it.isNotBlank() && !it.contains(' ') }
                .distinct()
            if (names.size >= 5) return names
        }
        return emptyList()
    }

    private fun linkApplet(target: File, name: String) {
        val link = File(binDir, name)
        if (link.exists() || link.canRead()) return
        val tried = runCatching {
            android.system.Os.symlink(target.absolutePath, link.absolutePath)
            true
        }.getOrDefault(false)
        if (!tried) {
            link.writeText("#!/system/bin/sh\nexec ${target.absolutePath} $name \"\$@\"\n")
            link.setExecutable(true, false)
        }
    }

    fun defaultUrlForAbi(): String {
        val abi = (android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a").lowercase()
        val arch = when {
            abi.startsWith("arm64") || abi.contains("aarch64") -> "aarch64"
            abi.startsWith("armeabi") || abi.contains("armv7") -> "armv7l"
            abi.contains("x86_64") -> "x86_64"
            abi.startsWith("x86") -> "i686"
            else -> "aarch64"
        }
        return "https://landley.net/toybox/bin/toybox-$arch"
    }
}
