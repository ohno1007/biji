package com.biji.notes.sandbox

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * biji 工具包。装的都是**静态链接**的 ELF —— 不依赖 bionic/glibc，
 * 也不依赖任何外部 app。配合 targetSdk 28（绕开 app 私有目录的
 * W^X 限制），下载 + chmod +x 就能直接 exec。
 *
 * 二进制来源是公开的 static-binaries 仓库，按设备 ABI 选目录。
 */
class BijiPkg(
    private val context: Context,
    private val bootstrap: BijiBootstrap
) {

    enum class Format { RAW, TAR_GZ }

    data class Package(
        val id: String,
        val title: String,
        val description: String,
        val sizeLabel: String,
        val binName: String,
        val url: String,
        val format: Format = Format.RAW,
        /** tar 包里要挖的文件 basename；RAW 忽略。 */
        val tarMember: String? = null,
        /** true = 多合一二进制，装完按 --list 建 applet 软链。 */
        val multicall: Boolean = false
    )

    sealed interface Progress {
        data object Idle : Progress
        data class Downloading(val pkgId: String, val bytes: Long, val total: Long) : Progress
        data class Installing(val pkgId: String) : Progress
        data class Done(val pkgId: String, val extraLinks: Int = 0) : Progress
        data class Failed(val pkgId: String, val message: String) : Progress
    }

    private val _progress = MutableStateFlow<Progress>(Progress.Idle)
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    /** static-binaries 仓库的目录名。 */
    val arch: String = when {
        android.os.Build.SUPPORTED_ABIS.firstOrNull()?.startsWith("arm64") == true -> "aarch64"
        android.os.Build.SUPPORTED_ABIS.any { it.contains("x86_64") } -> "x86_64"
        android.os.Build.SUPPORTED_ABIS.any { it.startsWith("armeabi") } -> "arm"
        android.os.Build.SUPPORTED_ABIS.any { it.startsWith("x86") } -> "i686"
        else -> "aarch64"
    }

    private fun sb(name: String) =
        "https://raw.githubusercontent.com/ryanwoodsmall/static-binaries/master/$arch/$name"

    val catalogue: List<Package> = listOf(
        Package(
            id = "busybox",
            title = "busybox",
            description = "350+ 命令合一：vi / awk / sed / find / tar / unzip / wget / patch / diff / ps 等。装完自动展开成独立命令。",
            sizeLabel = "≈ 2 MB",
            binName = "busybox",
            url = sb("busybox"),
            multicall = true
        ),
        Package(
            id = "coreutils",
            title = "GNU coreutils",
            description = "完整 GNU 版 ls / cp / mv / sort / head / date 等（比 busybox 精简版功能全）。",
            sizeLabel = "≈ 6 MB",
            binName = "coreutils",
            url = sb("coreutils"),
            multicall = true
        ),
        Package(
            id = "bash",
            title = "bash",
            description = "真正的 bash 5：数组、[[ ]]、进程替换、补全。终端会自动优先用它。",
            sizeLabel = "≈ 3 MB",
            binName = "bash",
            url = sb("bash")
        ),
        Package(
            id = "make",
            title = "GNU make",
            description = "标准 Makefile 构建。",
            sizeLabel = "≈ 1 MB",
            binName = "make",
            url = sb("make")
        ),
        Package(
            id = "curl",
            title = "curl",
            description = "静态 curl，带 TLS，可下载任意文件。",
            sizeLabel = "≈ 4 MB",
            binName = "curl",
            url = sb("curl")
        ),
        Package(
            id = "jq",
            title = "jq",
            description = "命令行 JSON 处理器。",
            sizeLabel = "≈ 1 MB",
            binName = "jq",
            url = sb("jq")
        ),
        Package(
            id = "xz",
            title = "xz",
            description = "xz / lzma 压缩解压，解 .tar.xz 用得上。",
            sizeLabel = "≈ 1 MB",
            binName = "xz",
            url = sb("xz")
        ),
        Package(
            id = "less",
            title = "less",
            description = "分页查看长输出。",
            sizeLabel = "≈ 500 KB",
            binName = "less",
            url = sb("less")
        ),
        Package(
            id = "neatvi",
            title = "vi 编辑器",
            description = "轻量 vi，终端里直接改文件。",
            sizeLabel = "≈ 300 KB",
            binName = "vi",
            url = sb("neatvi")
        ),
        Package(
            id = "rsync",
            title = "rsync",
            description = "增量同步 / 备份目录。",
            sizeLabel = "≈ 1 MB",
            binName = "rsync",
            url = sb("rsync")
        ),
        Package(
            id = "tmux",
            title = "tmux",
            description = "终端复用，长任务后台跑。",
            sizeLabel = "≈ 1 MB",
            binName = "tmux",
            url = sb("tmux")
        ),
        Package(
            id = "socat",
            title = "socat / nc",
            description = "网络管道调试。",
            sizeLabel = "≈ 1 MB",
            binName = "socat",
            url = sb("socat")
        ),
        Package(
            id = "mlr",
            title = "miller (mlr)",
            description = "CSV / JSON 流式处理。",
            sizeLabel = "≈ 8 MB",
            binName = "mlr",
            url = sb("mlr")
        ),
        Package(
            id = "ag",
            title = "the_silver_searcher (ag)",
            description = "快速代码全文搜索。",
            sizeLabel = "≈ 1 MB",
            binName = "ag",
            url = sb("ag")
        ),
        Package(
            id = "ccache",
            title = "ccache",
            description = "编译缓存。",
            sizeLabel = "≈ 1 MB",
            binName = "ccache",
            url = sb("ccache")
        )
    )

    fun isInstalled(p: Package): Boolean = isInstalled(p.binName)

    fun isInstalled(binName: String): Boolean =
        File(bootstrap.binDir, binName).let { it.exists() && it.canExecute() }

    /** 已装的全部命令名（含 multicall 展开出来的软链）。 */
    fun installedBinaries(): List<String> =
        bootstrap.binDir.listFiles()
            ?.filter { it.canExecute() }
            ?.map { it.name }
            ?.sorted()
            .orEmpty()

    suspend fun install(p: Package): Boolean = withContext(Dispatchers.IO) {
        try {
            _progress.value = Progress.Downloading(p.id, 0L, 0L)
            bootstrap.binDir.mkdirs()
            val tmp = File(bootstrap.binDir, "${p.binName}.part")
            if (!download(p.url, tmp) { read, total ->
                    _progress.value = Progress.Downloading(p.id, read, total)
                }) return@withContext false

            _progress.value = Progress.Installing(p.id)
            val target = File(bootstrap.binDir, p.binName)
            when (p.format) {
                Format.RAW -> {
                    target.delete()
                    if (!tmp.renameTo(target)) {
                        tmp.copyTo(target, overwrite = true); tmp.delete()
                    }
                }
                Format.TAR_GZ -> {
                    val wanted = p.tarMember ?: p.binName
                    val ok = GZIPInputStream(tmp.inputStream()).use { extractTarBinary(it, wanted, target) }
                    tmp.delete()
                    if (!ok) {
                        _progress.value = Progress.Failed(p.id, "tar 里没有 $wanted")
                        return@withContext false
                    }
                }
            }
            chmodX(target)
            if (!isElf(target)) {
                target.delete()
                _progress.value = Progress.Failed(p.id, "下载到的不是可执行文件（可能是 404 页面）")
                return@withContext false
            }
            val links = if (p.multicall) linkApplets(target) else 0
            _progress.value = Progress.Done(p.id, links)
            true
        } catch (e: Exception) {
            _progress.value = Progress.Failed(p.id, e.message ?: e.javaClass.simpleName)
            false
        }
    }

    /** 任意 URL 装成 [binName]。给「自定义工具」和 AI 用 —— AI 可以
     *  先联网搜到某个静态二进制的直链，再调这个装上。 */
    suspend fun installCustom(binName: String, url: String): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            val safe = binName.trim().replace(Regex("""[^A-Za-z0-9._-]"""), "")
            if (safe.isEmpty()) return@withContext false to "文件名非法"
            val p = Package(
                id = "custom:$safe", title = safe, description = "",
                sizeLabel = "", binName = safe, url = url
            )
            val ok = install(p)
            if (ok) true to "已安装 $safe"
            else false to ((_progress.value as? Progress.Failed)?.message ?: "安装失败")
        }

    suspend fun uninstall(p: Package) = withContext(Dispatchers.IO) {
        uninstallBinary(p.binName)
    }

    /** 删掉一个命令；如果它是 multicall 主体，连带清掉指向它的软链。 */
    suspend fun uninstallBinary(binName: String) = withContext(Dispatchers.IO) {
        val main = File(bootstrap.binDir, binName)
        bootstrap.binDir.listFiles()?.forEach { f ->
            val linkTarget = runCatching { android.system.Os.readlink(f.absolutePath) }.getOrNull()
            if (linkTarget != null &&
                (linkTarget == main.absolutePath || linkTarget.substringAfterLast('/') == binName)
            ) f.delete()
        }
        main.delete()
        Unit
    }

    // ---- helpers ------------------------------------------------------

    private fun chmodX(f: File) {
        runCatching { android.system.Os.chmod(f.absolutePath, 0b111_101_101) }
        runCatching { f.setExecutable(true, false) }
    }

    /** 头 4 字节是不是 \x7fELF —— 挡住把 404 HTML 当二进制装进去。 */
    private fun isElf(f: File): Boolean = runCatching {
        f.inputStream().use { input ->
            val h = ByteArray(4)
            input.read(h) == 4 && h[0] == 0x7F.toByte() &&
                h[1] == 'E'.code.toByte() && h[2] == 'L'.code.toByte() && h[3] == 'F'.code.toByte()
        }
    }.getOrDefault(false)

    /** multicall 二进制装完后，按它自己报的 applet 列表建软链，
     *  这样 `vi` / `wget` / `unzip` 直接能敲。 */
    private fun linkApplets(main: File): Int {
        val names = listOf(
            listOf(main.absolutePath, "--list"),
            listOf(main.absolutePath, "--help")
        ).firstNotNullOfOrNull { cmd ->
            runCatching {
                val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().use { it.readText() }
                p.waitFor()
                out.split(Regex("[\\s,]+"))
                    .map { it.trim().substringAfterLast('/') }
                    .filter { it.isNotBlank() && it.all { c -> c.isLetterOrDigit() || c == '-' || c == '_' || c == '.' } }
                    .distinct()
                    .takeIf { it.size >= 10 }
            }.getOrNull()
        } ?: return 0

        var n = 0
        for (name in names) {
            if (name == main.name) continue
            val link = File(bootstrap.binDir, name)
            if (link.exists()) continue
            val ok = runCatching {
                android.system.Os.symlink(main.absolutePath, link.absolutePath); true
            }.getOrDefault(false)
            if (!ok) {
                runCatching {
                    link.writeText("#!/system/bin/sh\nexec ${main.absolutePath} $name \"\$@\"\n")
                    chmodX(link)
                }
            }
            n++
        }
        return n
    }

    private fun download(
        url: String,
        dst: File,
        onProgress: (Long, Long) -> Unit
    ): Boolean {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
        }
        conn.connect()
        if (conn.responseCode !in 200..299) {
            _progress.value = Progress.Failed("", "HTTP ${conn.responseCode}")
            return false
        }
        val total = conn.contentLengthLong.coerceAtLeast(0L)
        dst.outputStream().use { out ->
            conn.inputStream.use { input ->
                val buf = ByteArray(64 * 1024)
                var read = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    read += n
                    onProgress(read, total)
                }
            }
        }
        return true
    }

    /** 极简 ustar：挖出 basename == [wanted] 的第一个普通文件。 */
    private fun extractTarBinary(input: InputStream, wanted: String, target: File): Boolean {
        val header = ByteArray(512)
        while (true) {
            var read = 0
            while (read < 512) {
                val n = input.read(header, read, 512 - read)
                if (n <= 0) return false
                read += n
            }
            if (header.all { it == 0.toByte() }) return false
            val name = String(header, 0, 100, Charsets.US_ASCII).trimEnd(0.toChar())
            val size = String(header, 124, 12, Charsets.US_ASCII)
                .trimEnd(0.toChar(), ' ').trim().toLongOrNull(8) ?: 0L
            val type = header[156]
            val padded = ((size + 511) / 512) * 512
            val isFile = type == '0'.code.toByte() || type == 0.toByte()
            if (isFile && name.substringAfterLast('/', name) == wanted) {
                target.delete()
                target.outputStream().use { out ->
                    var left = size
                    val buf = ByteArray(64 * 1024)
                    while (left > 0) {
                        val n = input.read(buf, 0, minOf(left.toInt(), buf.size))
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        left -= n
                    }
                }
                skip(input, padded - size)
                return true
            }
            skip(input, padded)
        }
    }

    private fun skip(input: InputStream, bytes: Long) {
        var left = bytes
        val buf = ByteArray(8192)
        while (left > 0) {
            val n = input.read(buf, 0, minOf(left.toInt(), buf.size))
            if (n <= 0) return
            left -= n
        }
    }
}
