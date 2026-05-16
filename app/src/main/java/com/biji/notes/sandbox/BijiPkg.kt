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
import java.util.zip.GZIPInputStream

/** App-private `pkg` —— 用预编译的单文件二进制扩展 [BijiBootstrap]
 *  的 bin 目录。每个包对应一个 URL，下载后 chmod +x 即可执行。 */
class BijiPkg(
    private val context: Context,
    private val bootstrap: BijiBootstrap
) {

    enum class Format { RAW, GZIP, TAR_GZ }

    data class Package(
        val id: String,
        val title: String,
        val description: String,
        val sizeLabel: String,
        val binName: String,
        val url: String,
        val format: Format = Format.RAW
    )

    sealed interface Progress {
        data object Idle : Progress
        data class Downloading(val pkgId: String, val bytes: Long, val total: Long) : Progress
        data class Installing(val pkgId: String) : Progress
        data class Done(val pkgId: String) : Progress
        data class Failed(val pkgId: String, val message: String) : Progress
    }

    private val _progress = MutableStateFlow<Progress>(Progress.Idle)
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    /** Curated catalogue —— 选静态编译、无路径依赖的小工具。
     *  URL 指向公开的 GitHub release / 镜像。 */
    val catalogue: List<Package> = listOf(
        Package(
            id = "tcc",
            title = "Tiny C Compiler",
            description = "tcc — 一个 ~300 KB 的 C 编译器。能跑大部分 C 代码。",
            sizeLabel = "≈ 300 KB",
            binName = "tcc",
            url = "https://github.com/clearlinux-pkgs/tcc-android/releases/download/0.9.27/tcc-aarch64"
        ),
        Package(
            id = "ripgrep",
            title = "ripgrep (rg)",
            description = "比 grep 快十倍的递归正则搜索。",
            sizeLabel = "≈ 6 MB",
            binName = "rg",
            url = "https://github.com/BurntSushi/ripgrep/releases/download/14.1.0/ripgrep-14.1.0-aarch64-unknown-linux-gnu.tar.gz",
            format = Format.TAR_GZ
        ),
        Package(
            id = "fd",
            title = "fd",
            description = "现代版 find。",
            sizeLabel = "≈ 3 MB",
            binName = "fd",
            url = "https://github.com/sharkdp/fd/releases/download/v10.2.0/fd-v10.2.0-aarch64-unknown-linux-gnu.tar.gz",
            format = Format.TAR_GZ
        ),
        Package(
            id = "bat",
            title = "bat",
            description = "带语法高亮的 cat。",
            sizeLabel = "≈ 5 MB",
            binName = "bat",
            url = "https://github.com/sharkdp/bat/releases/download/v0.24.0/bat-v0.24.0-aarch64-unknown-linux-gnu.tar.gz",
            format = Format.TAR_GZ
        ),
        Package(
            id = "jq",
            title = "jq",
            description = "命令行 JSON 处理器。",
            sizeLabel = "≈ 600 KB",
            binName = "jq",
            url = "https://github.com/jqlang/jq/releases/download/jq-1.7.1/jq-linux-arm64"
        ),
        Package(
            id = "curl",
            title = "curl (静态)",
            description = "静态编译的 curl，支持 HTTPS。",
            sizeLabel = "≈ 8 MB",
            binName = "curl",
            url = "https://github.com/moparisthebest/static-curl/releases/download/v8.10.1/curl-aarch64"
        )
    )

    fun isInstalled(p: Package): Boolean =
        File(bootstrap.binDir, p.binName).let { it.exists() && it.canExecute() }

    /** 单独装某个包。 */
    suspend fun install(p: Package): Boolean = withContext(Dispatchers.IO) {
        try {
            _progress.value = Progress.Downloading(p.id, 0L, 0L)
            bootstrap.binDir.mkdirs()
            val tmp = File(bootstrap.binDir, "${p.binName}.part")
            val conn = (URL(p.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 60_000
                instanceFollowRedirects = true
            }
            conn.connect()
            if (conn.responseCode !in 200..299) {
                _progress.value = Progress.Failed(p.id, "HTTP ${conn.responseCode}")
                return@withContext false
            }
            val total = conn.contentLengthLong.coerceAtLeast(0L)
            tmp.outputStream().use { out ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        read += n
                        _progress.value = Progress.Downloading(p.id, read, total)
                    }
                }
            }
            _progress.value = Progress.Installing(p.id)
            val target = File(bootstrap.binDir, p.binName)
            when (p.format) {
                Format.RAW -> {
                    if (target.exists()) target.delete()
                    tmp.renameTo(target)
                }
                Format.GZIP -> {
                    target.outputStream().use { out ->
                        GZIPInputStream(tmp.inputStream()).use { it.copyTo(out) }
                    }
                    tmp.delete()
                }
                Format.TAR_GZ -> {
                    // tar.gz 里一般打了一层 release 目录，找到匹配 binName 的可执行文件
                    val tar = GZIPInputStream(tmp.inputStream())
                    val found = extractTarForBinary(tar, p.binName, target)
                    tmp.delete()
                    if (!found) {
                        _progress.value = Progress.Failed(p.id, "tar 里没找到 ${p.binName}")
                        return@withContext false
                    }
                }
            }
            target.setExecutable(true, false)
            _progress.value = Progress.Done(p.id)
            true
        } catch (e: Exception) {
            _progress.value = Progress.Failed(p.id, e.message ?: e.javaClass.simpleName)
            false
        }
    }

    suspend fun uninstall(p: Package) = withContext(Dispatchers.IO) {
        File(bootstrap.binDir, p.binName).delete()
    }

    /** 极简 tar 解析 —— 找到名字以 [wanted] 结尾且可执行的条目，写到 [target]。
     *  够用来抽 ripgrep / fd / bat 这种「一个 release 目录 + 一个二进制」结构。 */
    private fun extractTarForBinary(
        input: java.io.InputStream,
        wanted: String,
        target: File
    ): Boolean {
        val buf = ByteArray(512)
        while (true) {
            var read = 0
            while (read < 512) {
                val n = input.read(buf, read, 512 - read)
                if (n <= 0) return false
                read += n
            }
            // 全 0 头表示归档结束
            if (buf.all { it == 0.toByte() }) return false
            val name = String(buf, 0, 100, Charsets.US_ASCII).trimEnd(0.toChar())
            val sizeOctal = String(buf, 124, 12, Charsets.US_ASCII)
                .trimEnd(0.toChar(), ' ').trim()
            val size = sizeOctal.toLongOrNull(8) ?: 0L
            val type = buf[156]
            val padded = ((size + 511) / 512) * 512
            val baseName = name.substringAfterLast('/', name)
            if (type == '0'.code.toByte() || type == 0.toByte()) {
                if (baseName == wanted) {
                    if (target.exists()) target.delete()
                    target.outputStream().use { out ->
                        var left = size
                        val chunk = ByteArray(64 * 1024)
                        while (left > 0) {
                            val want = minOf(left.toInt(), chunk.size)
                            val n = input.read(chunk, 0, want)
                            if (n <= 0) break
                            out.write(chunk, 0, n)
                            left -= n
                        }
                    }
                    // 跳过尾部 padding
                    val tail = padded - size
                    var skipped = 0L
                    val skipBuf = ByteArray(512)
                    while (skipped < tail) {
                        val n = input.read(skipBuf, 0, minOf((tail - skipped).toInt(), 512))
                        if (n <= 0) break
                        skipped += n
                    }
                    return true
                }
            }
            // 跳过该条目
            var skipped = 0L
            val skipBuf = ByteArray(4096)
            while (skipped < padded) {
                val want = minOf((padded - skipped).toInt(), skipBuf.size)
                val n = input.read(skipBuf, 0, want)
                if (n <= 0) return false
                skipped += n
            }
        }
    }
}
