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

/** biji 自己的工具包管理器。每个工具都是单文件静态二进制 / 单个
 *  从 GitHub release 拉的 tar.gz 里抽出来。下载到 bootstrap/bin
 *  并 chmod +x。完全不依赖 Termux、proot、apt。 */
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
        val format: Format = Format.RAW,
        /** tar 包里要挖的二进制名 (basename)。RAW/GZIP 忽略。 */
        val tarMember: String? = null
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

    /** 收录的工具。URL 均指向公开 GitHub release / 静态构建主页。
     *  arm64 为主；用户大部分都是 arm64 设备。 */
    val catalogue: List<Package> = listOf(
        Package(
            id = "busybox",
            title = "busybox",
            description = "比 toybox 更全的多 applet 二进制 (make / vi / sh / awk / sed / wget / ftp / nc / xz / unzip 等 350+ 命令)。",
            sizeLabel = "≈ 1 MB",
            binName = "busybox",
            url = "https://github.com/meefik/busybox/releases/download/v1.36.1/busybox-arm64"
        ),
        Package(
            id = "tcc",
            title = "Tiny C Compiler",
            description = "完整的 C 编译器，单二进制不到 1 MB。能编译大部分标准 C 代码。",
            sizeLabel = "≈ 800 KB",
            binName = "tcc",
            url = "https://github.com/leleliu008/uppm-formula/releases/download/tcc-0.9.27/tcc-0.9.27-android-aarch64.tar.xz",
            format = Format.TAR_GZ,
            tarMember = "tcc"
        ),
        Package(
            id = "make",
            title = "GNU make",
            description = "静态构建的 GNU make。配合 tcc 能跑标准 Makefile。",
            sizeLabel = "≈ 1 MB",
            binName = "make",
            url = "https://github.com/leleliu008/uppm-formula/releases/download/make-4.4.1/make-4.4.1-android-aarch64.tar.xz",
            format = Format.TAR_GZ,
            tarMember = "make"
        ),
        Package(
            id = "git",
            title = "git",
            description = "完整静态 git，支持 HTTPS clone / push。",
            sizeLabel = "≈ 8 MB",
            binName = "git",
            url = "https://github.com/nikhilm/git-portable/releases/download/v2.42.0/git-static-arm64",
            format = Format.RAW
        ),
        Package(
            id = "python",
            title = "Python 3",
            description = "Python 3 单文件解释器（部分标准库），用来跑脚本。",
            sizeLabel = "≈ 12 MB",
            binName = "python3",
            url = "https://github.com/indygreg/python-build-standalone/releases/download/20240224/cpython-3.11.8+20240224-aarch64-unknown-linux-musl-install_only.tar.gz",
            format = Format.TAR_GZ,
            tarMember = "python3.11"
        ),
        Package(
            id = "ripgrep",
            title = "ripgrep (rg)",
            description = "比 grep 快十倍的递归正则搜索。",
            sizeLabel = "≈ 6 MB",
            binName = "rg",
            url = "https://github.com/BurntSushi/ripgrep/releases/download/14.1.0/ripgrep-14.1.0-aarch64-unknown-linux-gnu.tar.gz",
            format = Format.TAR_GZ,
            tarMember = "rg"
        ),
        Package(
            id = "fd",
            title = "fd",
            description = "现代版 find。",
            sizeLabel = "≈ 3 MB",
            binName = "fd",
            url = "https://github.com/sharkdp/fd/releases/download/v10.2.0/fd-v10.2.0-aarch64-unknown-linux-gnu.tar.gz",
            format = Format.TAR_GZ,
            tarMember = "fd"
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
                    val wanted = p.tarMember ?: p.binName
                    val ok = GZIPInputStream(tmp.inputStream()).use { gz ->
                        extractTarBinary(gz, wanted, target)
                    }
                    tmp.delete()
                    if (!ok) {
                        _progress.value = Progress.Failed(p.id, "tar 里没找到 $wanted")
                        return@withContext false
                    }
                }
            }
            runCatching { android.system.Os.chmod(target.absolutePath, 0b111_101_101) }
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

    /** 给定 URL 直接拉成单文件，文件名取 binName。给「自定义工具」用。 */
    suspend fun installRaw(binName: String, url: String): Boolean = withContext(Dispatchers.IO) {
        val p = Package(id = "custom_$binName", title = binName,
            description = "", sizeLabel = "", binName = binName, url = url)
        install(p)
    }

    /** 极简 ustar 解析：找名字以 [wanted] 结尾的可执行条目，写到 dst。
     *  适合「release tarball 里一个目录 + 一个目标二进制」的常见结构。 */
    private fun extractTarBinary(
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
            if (buf.all { it == 0.toByte() }) return false
            val name = String(buf, 0, 100, Charsets.US_ASCII).trimEnd(0.toChar())
            val sizeOctal = String(buf, 124, 12, Charsets.US_ASCII)
                .trimEnd(0.toChar(), ' ').trim()
            val size = sizeOctal.toLongOrNull(8) ?: 0L
            val type = buf[156]
            val padded = ((size + 511) / 512) * 512
            val baseName = name.substringAfterLast('/', name)
            if ((type == '0'.code.toByte() || type == 0.toByte()) && baseName == wanted) {
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
