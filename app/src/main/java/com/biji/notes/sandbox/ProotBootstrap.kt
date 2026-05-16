package com.biji.notes.sandbox

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.tukaani.xz.XZInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** 抓 Termux 的 proot.deb，挖出 usr/bin/proot 单独放到我们的目录。
 *  装好以后所有 shell 命令都通过 proot 跑，靠它伪造
 *  /data/data/com.termux/files/usr 这个硬编码路径 —— apt / dpkg
 *  / gcc 看到的都是它们以为的 Termux prefix，但实际读写的是
 *  biji 自己的目录。完全用户态，不要 root。 */
class ProotBootstrap(
    private val context: Context,
    private val termux: TermuxBootstrap
) {

    val rootDir: File get() = File(context.filesDir, "proot").also { it.mkdirs() }
    val binary: File get() = File(rootDir, "proot")
    val installed: Boolean get() = binary.exists() && binary.canExecute()

    sealed interface Progress {
        data object Idle : Progress
        data class Downloading(val bytes: Long, val total: Long) : Progress
        data object Extracting : Progress
        data object Done : Progress
        data class Failed(val message: String) : Progress
    }

    private val _progress = MutableStateFlow<Progress>(Progress.Idle)
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    /** Termux 主源根。具体 proot deb 文件名版本会变，所以我们
     *  动态从 dists/stable Packages.xz 索引里查当前 Filename。 */
    private val termuxApt = "https://packages.termux.dev/apt/termux-main"

    suspend fun install(url: String? = null): Boolean = withContext(Dispatchers.IO) {
        try {
            rootDir.mkdirs()
            // 没指定 URL 时去查 Termux 索引拿当前 proot 的 Filename。
            val resolved = url ?: run {
                _progress.value = Progress.Downloading(0L, 0L)
                resolveDebUrl("proot")
                    ?: run {
                        _progress.value = Progress.Failed("Termux 索引里找不到 proot 包")
                        return@withContext false
                    }
            }
            _progress.value = Progress.Downloading(0L, 0L)
            val deb = File(rootDir, "proot.deb")
            if (!downloadTo(resolved, deb)) return@withContext false

            _progress.value = Progress.Extracting
            // .deb 是个 ar 归档，里面有 control.tar.xz / data.tar.xz。
            // 找 data.tar.xz，xz 解出 tar 流，再从 tar 里挖出
            // ./data/data/com.termux/files/usr/bin/proot。
            val dataTarXz = extractArEntry(deb, "data.tar.xz")
                ?: run {
                    _progress.value = Progress.Failed("deb 里没有 data.tar.xz")
                    return@withContext false
                }
            val ok = XZInputStream(dataTarXz.inputStream()).use { xin ->
                extractTarMember(xin, "data/data/com.termux/files/usr/bin/proot", binary)
            }
            dataTarXz.delete()
            deb.delete()
            if (!ok) {
                _progress.value = Progress.Failed("tar 里找不到 proot")
                return@withContext false
            }
            runCatching { android.system.Os.chmod(binary.absolutePath, 0b111_101_101) }
            _progress.value = Progress.Done
            true
        } catch (e: Exception) {
            _progress.value = Progress.Failed(e.message ?: e.javaClass.simpleName)
            false
        }
    }

    suspend fun uninstall() = withContext(Dispatchers.IO) {
        rootDir.deleteRecursively()
        rootDir.mkdirs()
        _progress.value = Progress.Idle
    }

    /** 把一条命令包到 proot 里跑：把 biji 的 termux/usr / termux/home
     *  绑到 Termux 的硬编码路径，cwd 落在 home，命令通过 bash -c。 */
    fun wrapForProot(command: String): List<String> {
        val usr = termux.usrDir.absolutePath
        val home = termux.homeDir.absolutePath
        val bash = "/data/data/com.termux/files/usr/bin/bash"
        return listOf(
            binary.absolutePath,
            "--link2symlink",
            "-0",                       // 在 proot 里以 uid 0 出现，让 apt / dpkg 高兴
            "-b", "/proc",
            "-b", "/dev",
            "-b", "/sys",
            "-b", "$usr:/data/data/com.termux/files/usr",
            "-b", "$home:/data/data/com.termux/files/home",
            "-w", "/data/data/com.termux/files/home",
            bash, "-c", command
        )
    }

    /** 查 Termux 主源 dists/stable Packages 索引找 Filename 字段。
     *  按 .xz → .gz → 无压缩 顺序试，每一步都把失败原因记到
     *  Progress.Failed 上让用户看见。 */
    private suspend fun resolveDebUrl(packageName: String): String? =
        withContext(Dispatchers.IO) {
            val arch = abiToTermuxArch()
            val base = "$termuxApt/dists/stable/main/binary-$arch/Packages"
            val triedErrors = mutableListOf<String>()
            val variants = listOf<Pair<String, (java.io.InputStream) -> String>>(
                ".xz" to { i -> XZInputStream(i).use { String(it.readBytes(), Charsets.UTF_8) } },
                ".gz" to { i -> java.util.zip.GZIPInputStream(i).use { String(it.readBytes(), Charsets.UTF_8) } },
                "" to { i -> String(i.readBytes(), Charsets.UTF_8) }
            )
            attempts@ for ((suffix, decoder) in variants) {
                val url = base + suffix
                var raw: String? = null
                try {
                    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15_000
                        readTimeout = 60_000
                        instanceFollowRedirects = true
                    }
                    conn.connect()
                    if (conn.responseCode !in 200..299) {
                        triedErrors += "$url → HTTP ${conn.responseCode}"
                        continue@attempts
                    }
                    raw = conn.inputStream.use { decoder(it) }
                } catch (t: Throwable) {
                    triedErrors += "$url → ${t.message ?: t.javaClass.simpleName}"
                    continue@attempts
                }
                if (raw == null) continue@attempts
                val sections = raw.split(Regex("\\r?\\n\\r?\\n"))
                val sect = sections.firstOrNull { s ->
                    s.lineSequence().any { l ->
                        l.startsWith("Package:") &&
                            l.substringAfter("Package:").trim() == packageName
                    }
                }
                if (sect == null) {
                    triedErrors += "$url → 索引里没有 $packageName"
                    continue@attempts
                }
                val filename = sect.lineSequence()
                    .firstOrNull { it.startsWith("Filename:") }
                    ?.substringAfter(":")
                    ?.trim()
                if (filename == null) {
                    triedErrors += "$url → 段里没有 Filename"
                    continue@attempts
                }
                return@withContext "$termuxApt/$filename"
            }
            _progress.value = Progress.Failed(
                triedErrors.lastOrNull() ?: "找不到 $packageName"
            )
            null
        }

    private fun abiToTermuxArch(): String {
        val abi = (android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a").lowercase()
        return when {
            abi.startsWith("arm64") || abi.contains("aarch64") -> "aarch64"
            abi.startsWith("armeabi") || abi.contains("armv7") -> "arm"
            abi.contains("x86_64") -> "x86_64"
            abi.startsWith("x86") -> "i686"
            else -> "aarch64"
        }
    }

    private suspend fun downloadTo(url: String, dst: File): Boolean = withContext(Dispatchers.IO) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
        }
        conn.connect()
        if (conn.responseCode !in 200..299) {
            _progress.value = Progress.Failed("HTTP ${conn.responseCode} — $url")
            return@withContext false
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
                    _progress.value = Progress.Downloading(read, total)
                }
            }
        }
        true
    }

    // --- minimal ar / tar helpers --------------------------------------

    /** ar 归档：8 字节魔数 "!<arch>\n" 后面是一组 60 字节文件头 +
     *  数据（偶字节对齐）。这里只挑名字匹配的那一项写出到临时文件。 */
    private fun extractArEntry(deb: File, name: String): File? {
        deb.inputStream().use { input ->
            val magic = ByteArray(8)
            if (input.read(magic) != 8) return null
            if (String(magic, Charsets.US_ASCII) != "!<arch>\n") return null
            val header = ByteArray(60)
            while (true) {
                val read = readFully(input, header)
                if (read < 60) return null
                val entryName = String(header, 0, 16, Charsets.US_ASCII).trimEnd(' ', '/')
                val size = String(header, 48, 10, Charsets.US_ASCII).trim().toLong()
                val padded = if (size % 2 == 0L) size else size + 1
                if (entryName == name) {
                    val out = File(deb.parentFile, "_ar_$name")
                    out.outputStream().use { dst ->
                        copyExactly(input, dst, size)
                    }
                    if (size != padded) input.skip(padded - size)
                    return out
                }
                input.skip(padded)
            }
        }
    }

    /** 在 tar 流里找完整路径匹配的成员，写到 dst。返回是否找到。 */
    private fun extractTarMember(input: InputStream, target: String, dst: File): Boolean {
        val buf = ByteArray(512)
        while (true) {
            val n = readFully(input, buf)
            if (n < 512) return false
            if (buf.all { it == 0.toByte() }) return false
            val rawName = String(buf, 0, 100, Charsets.US_ASCII).trimEnd(0.toChar())
            val prefix = String(buf, 345, 155, Charsets.US_ASCII).trimEnd(0.toChar(), ' ')
            val fullName = (if (prefix.isNotEmpty()) "$prefix/$rawName" else rawName)
                .removePrefix("./")
            val sizeOctal = String(buf, 124, 12, Charsets.US_ASCII)
                .trimEnd(0.toChar(), ' ').trim()
            val size = sizeOctal.toLongOrNull(8) ?: 0L
            val padded = ((size + 511) / 512) * 512
            val type = buf[156]
            val isFile = type == '0'.code.toByte() || type == 0.toByte()
            if (isFile && fullName == target.removePrefix("./")) {
                dst.parentFile?.mkdirs()
                if (dst.exists()) dst.delete()
                dst.outputStream().use { out -> copyExactly(input, out, size) }
                val tail = padded - size
                if (tail > 0) input.skip(tail)
                return true
            }
            input.skip(padded)
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var got = 0
        while (got < buf.size) {
            val n = input.read(buf, got, buf.size - got)
            if (n <= 0) return got
            got += n
        }
        return got
    }

    private fun copyExactly(input: InputStream, out: java.io.OutputStream, size: Long) {
        var left = size
        val buf = ByteArray(64 * 1024)
        while (left > 0) {
            val want = minOf(left.toInt(), buf.size)
            val n = input.read(buf, 0, want)
            if (n <= 0) break
            out.write(buf, 0, n)
            left -= n
        }
    }
}
