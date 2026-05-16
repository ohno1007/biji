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
    /** proot 自己需要一个能写入的临时目录做 glue rootfs；
     *  Android 上 /tmp 不存在，没设 PROOT_TMP_DIR 它就直接 bail
     *  并 fatal error。指到我们 sandbox 内的目录就行。 */
    val tmpDir: File get() = File(rootDir, "tmp").also {
        it.mkdirs()
        runCatching { android.system.Os.chmod(it.absolutePath, 0b111_101_101) }
    }
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
            // 1) proot 本体 + proot-loader（小辅助二进制，proot 自己
            //    fork 子进程时要 execve 它），都在 proot.deb 里。
            _progress.value = Progress.Downloading(0L, 0L)
            val prootUrl = url ?: run {
                resolveDebUrl("proot")
                    ?: return@withContext false
            }
            val prootDeb = File(rootDir, "proot.deb")
            if (!downloadTo(prootUrl, prootDeb)) return@withContext false
            _progress.value = Progress.Extracting
            val prootOk = extractDebSingleFile(
                prootDeb,
                "data/data/com.termux/files/usr/bin/proot",
                binary
            )
            // proot-loader 路径会变（proot-loader / proot-loader-32 等），
            // 用前缀挖整个 libexec/proot 目录到我们 rootDir。
            extractDebPrefixToDir(
                prootDeb,
                "data/data/com.termux/files/usr/libexec/proot/",
                rootDir
            )
            prootDeb.delete()
            if (!prootOk) {
                _progress.value = Progress.Failed("tar 里找不到 proot 主程序")
                return@withContext false
            }
            runCatching { android.system.Os.chmod(binary.absolutePath, 0b111_101_101) }
            // 给 loader 也 +x
            rootDir.listFiles()?.forEach { f ->
                if (f.isFile && f.name.startsWith("proot-loader")) {
                    runCatching { android.system.Os.chmod(f.absolutePath, 0b111_101_101) }
                }
            }

            // 2) proot 依赖 libtalloc.so.2，Termux bootstrap 不自带。
            _progress.value = Progress.Downloading(0L, 0L)
            val tallocUrl = resolveDebUrl("libtalloc")
                ?: return@withContext false
            val tallocDeb = File(rootDir, "libtalloc.deb")
            if (!downloadTo(tallocUrl, tallocDeb)) return@withContext false
            _progress.value = Progress.Extracting
            val n = extractDebPrefixToDir(
                tallocDeb,
                "data/data/com.termux/files/usr/lib/",
                termux.libDir
            )
            tallocDeb.delete()
            if (n == 0) {
                _progress.value = Progress.Failed("libtalloc 里没解出文件")
                return@withContext false
            }

            // 提前把 tmpDir 建出来。proot 启动时找不到的话 fatal。
            tmpDir.mkdirs()

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
            "-b", "${tmpDir.absolutePath}:/data/data/com.termux/files/usr/tmp",
            "-w", "/data/data/com.termux/files/home",
            bash, "-c", command
        )
    }

    /** 跑 proot 时 ProcessBuilder 的 env 必须带上的几个变量。
     *  最关键的是 PROOT_TMP_DIR —— 没它 proot 在初始化时就 fatal。
     *  PROOT_LOADER 让 proot 找到 proot-loader 辅助二进制。 */
    fun envFor(): Map<String, String> {
        val out = mutableMapOf("PROOT_TMP_DIR" to tmpDir.absolutePath)
        val loader = File(rootDir, "proot-loader")
        if (loader.exists()) out["PROOT_LOADER"] = loader.absolutePath
        val loader32 = File(rootDir, "proot-loader-32")
        if (loader32.exists()) out["PROOT_LOADER_32"] = loader32.absolutePath
        return out
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

    /** 从 .deb 里挖单个文件到目标位置。 */
    private fun extractDebSingleFile(deb: File, target: String, dst: File): Boolean {
        val dataTar = extractArEntry(deb, "data.tar.xz") ?: return false
        val ok = XZInputStream(dataTar.inputStream()).use { xin ->
            extractTarMember(xin, target, dst)
        }
        dataTar.delete()
        return ok
    }

    /** 从 .deb 里挖所有以 [srcPrefix] 开头的条目（文件和软链）摊到
     *  [dstDir]。保留软链。返回挖出的条目数。 */
    private fun extractDebPrefixToDir(deb: File, srcPrefix: String, dstDir: File): Int {
        val dataTar = extractArEntry(deb, "data.tar.xz") ?: return 0
        var count = 0
        dstDir.mkdirs()
        XZInputStream(dataTar.inputStream()).use { xin ->
            val header = ByteArray(512)
            while (true) {
                val read = readFully(xin, header)
                if (read < 512) break
                if (header.all { it == 0.toByte() }) break
                val rawName = String(header, 0, 100, Charsets.US_ASCII).trimEnd(0.toChar())
                val sizeOctal = String(header, 124, 12, Charsets.US_ASCII)
                    .trimEnd(0.toChar(), ' ').trim()
                val size = sizeOctal.toLongOrNull(8) ?: 0L
                val padded = ((size + 511) / 512) * 512
                val type = header[156]
                val linkName = String(header, 157, 100, Charsets.US_ASCII).trimEnd(0.toChar())
                val fullName = rawName.removePrefix("./")

                val inPrefix = fullName.startsWith(srcPrefix)
                val target: File? = if (inPrefix) {
                    val rel = fullName.substring(srcPrefix.length).trimStart('/')
                    if (rel.isEmpty()) null else File(dstDir, rel)
                } else null

                val isDir = type == '5'.code.toByte()
                val isSym = type == '2'.code.toByte()
                val isFile = !isDir && !isSym // 把 type='0'/0 和 '7' 也算文件

                if (inPrefix && target != null) {
                    when {
                        isDir -> {
                            target.mkdirs()
                            // 目录必须 0755 才能进；mkdirs 默认 umask
                            // 可能给成 0700，显式 chmod 兜底。
                            runCatching {
                                android.system.Os.chmod(target.absolutePath, 0b111_101_101)
                            }
                        }
                        isSym -> {
                            target.parentFile?.mkdirs()
                            target.delete()
                            runCatching {
                                android.system.Os.symlink(linkName, target.absolutePath)
                            }
                            count++
                        }
                        else -> {
                            target.parentFile?.also { p ->
                                p.mkdirs()
                                runCatching {
                                    android.system.Os.chmod(p.absolutePath, 0b111_101_101)
                                }
                            }
                            target.delete()
                            target.outputStream().use { out -> copyExactly(xin, out, size) }
                            // 大部分文件 0644；.so / 可执行用 0755。
                            val perm = if (target.name.endsWith(".so") ||
                                target.name.contains(".so.")) 0b111_101_101 else 0b110_100_100
                            runCatching {
                                android.system.Os.chmod(target.absolutePath, perm)
                            }
                            count++
                        }
                    }
                }
                // 只有 type='0'/'7' 也就是普通文件项目才会真带数据。
                // 已经在上面写出的也走这里 skip 掉对应的 padding。
                if (isFile) {
                    // 若我们刚才已经写了 size 字节，剩 padded-size 跳过；
                    // 没写就跳整个 padded。两种情况整合：把不属于已读
                    // 的剩余字节跳掉。
                    val alreadyConsumed = if (inPrefix && target != null && !isDir && !isSym) size else 0L
                    val skip = padded - alreadyConsumed
                    if (skip > 0) xin.skip(skip)
                }
            }
        }
        dataTar.delete()
        return count
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
