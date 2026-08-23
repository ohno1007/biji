package com.biji.notes.sandbox

import android.content.Context
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tukaani.xz.MemoryLimitException
import org.tukaani.xz.XZInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

// =====================================================================
//  ELF 探测
// =====================================================================

/**
 * 一个文件能不能在这台设备上 exec 起来的全部依据。
 *
 * 只看头 4 字节的 magic 是不够的 —— 那挡得住 404 HTML，挡不住
 * x86_64 的二进制（exec 时 ENOEXEC），更挡不住动态链接 glibc 的构建：
 * 后者报错是 "No such file or directory"，说的是**缺 loader**，
 * 而不是缺你那个文件，人和模型都会被这句话带偏半小时。
 */
@Immutable
data class ElfInfo(
    val path: String,
    val isElf: Boolean,
    val bits: Int,
    val machine: String,
    val type: String,
    val interp: String?,
    val needed: List<String>,
    val sizeBytes: Long,
    val executableBit: Boolean,
    val shebang: String?,
    /** 非空 = 跑不起来，内容是可以直接甩给模型的下一步指导。 */
    val problem: String?
) {
    /** static / bionic / dynamic / script / unknown */
    val linkage: String
        get() = when {
            !isElf && shebang != null -> "script"
            !isElf -> "unknown"
            interp == null -> "static"
            interp == ANDROID_LINKER64 || interp == ANDROID_LINKER32 -> "bionic"
            else -> "dynamic"
        }

    val runnable: Boolean get() = problem == null

    companion object {
        const val ANDROID_LINKER64 = "/system/bin/linker64"
        const val ANDROID_LINKER32 = "/system/bin/linker"
    }
}

/** 纯字节操作，不 fork 任何进程 —— 每次安装都要跑，起个 readelf 太贵，
 *  而且这台设备上根本没有 readelf。 */
object ElfProbe {

    private const val PT_LOAD = 1
    private const val PT_DYNAMIC = 2
    private const val PT_INTERP = 3
    private const val DT_NULL = 0L
    private const val DT_NEEDED = 1L
    private const val DT_STRTAB = 5L

    fun probe(f: File): ElfInfo {
        val size = runCatching { f.length() }.getOrDefault(0L)
        val execBit = runCatching { f.canExecute() }.getOrDefault(false)
        if (!f.isFile) {
            return ElfInfo(
                f.absolutePath, false, 0, "?", "?", null, emptyList(), 0, false, null,
                "文件不存在：${f.absolutePath}"
            )
        }
        return runCatching { readElf(f, size, execBit) }.getOrElse { e ->
            ElfInfo(
                f.absolutePath, false, 0, "?", "?", null, emptyList(), size, execBit, null,
                "读取失败：${e.message ?: e.javaClass.simpleName}"
            )
        }
    }

    private fun readElf(f: File, size: Long, execBit: Boolean): ElfInfo {
        RandomAccessFile(f, "r").use { raf ->
            val head = ByteArray(64)
            val n = raf.read(head)
            if (n < 16 || head[0] != 0x7F.toByte() || head[1] != 'E'.code.toByte() ||
                head[2] != 'L'.code.toByte() || head[3] != 'F'.code.toByte()
            ) {
                val shebang = if (n >= 2 && head[0] == '#'.code.toByte() && head[1] == '!'.code.toByte()) {
                    String(head, 0, n.coerceAtMost(64), Charsets.US_ASCII)
                        .lineSequence().firstOrNull()?.trim()
                } else null
                val hint = when {
                    shebang != null -> null // 脚本是合法的入口，交给上层判断
                    n >= 9 && String(head, 0, 9, Charsets.US_ASCII).startsWith("<!DOCTYPE",
                        ignoreCase = true) ->
                        "下载到的是 HTML 页面，不是二进制 —— 多半是 404 / 需要登录 / URL 指向了 release 页面而不是 asset 直链。"
                    n >= 2 && head[0] == 0x1F.toByte() && head[1] == 0x8B.toByte() ->
                        "这是 gzip 数据，不是裸二进制。format 该填 tar.gz。"
                    n >= 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() ->
                        "这是 zip 数据，不是裸二进制。format 该填 zip。"
                    n >= 6 && head[0] == 0xFD.toByte() && head[1] == '7'.code.toByte() ->
                        "这是 xz 数据，不是裸二进制。format 该填 tar.xz。"
                    else -> "不是 ELF 可执行文件（头 4 字节不是 \\x7fELF）。"
                }
                return ElfInfo(
                    f.absolutePath, false, 0, "?", "?", null, emptyList(), size, execBit,
                    shebang, hint
                )
            }
            val cls = head[4].toInt()          // 1 = 32 位, 2 = 64 位
            val data = head[5].toInt()         // 1 = 小端
            val bits = if (cls == 2) 64 else 32
            if (data != 1) {
                return ElfInfo(
                    f.absolutePath, true, bits, "?", "?", null, emptyList(), size, execBit, null,
                    "大端 ELF，Android/aarch64 是小端，跑不了。"
                )
            }
            val eType = u16(head, 16)
            val eMachine = u16(head, 18)
            val typeName = when (eType) {
                1 -> "REL"; 2 -> "EXEC"; 3 -> "DYN"; 4 -> "CORE"; else -> "TYPE$eType"
            }
            val machineName = machineName(eMachine)

            val phoff: Long
            val phentsize: Int
            val phnum: Int
            if (bits == 64) {
                phoff = u64(head, 32)
                phentsize = u16(head, 54)
                phnum = u16(head, 56)
            } else {
                phoff = u32(head, 28)
                phentsize = u16(head, 42)
                phnum = u16(head, 44)
            }

            var interp: String? = null
            var dynOff = -1L
            var dynSize = 0L
            val loads = ArrayList<Triple<Long, Long, Long>>() // vaddr, filesz, offset

            if (phoff > 0 && phentsize >= 8 && phnum in 1..512 && phoff < size) {
                val ph = ByteArray(phentsize)
                for (i in 0 until phnum) {
                    raf.seek(phoff + i.toLong() * phentsize)
                    if (raf.read(ph) != phentsize) break
                    val pType = u32(ph, 0).toInt()
                    val pOffset: Long
                    val pVaddr: Long
                    val pFilesz: Long
                    if (bits == 64) {
                        if (phentsize < 56) break
                        pOffset = u64(ph, 8); pVaddr = u64(ph, 16); pFilesz = u64(ph, 32)
                    } else {
                        if (phentsize < 32) break
                        pOffset = u32(ph, 4); pVaddr = u32(ph, 8); pFilesz = u32(ph, 16)
                    }
                    when (pType) {
                        PT_INTERP -> if (pFilesz in 1..4096 && pOffset + pFilesz <= size) {
                            val b = ByteArray(pFilesz.toInt())
                            raf.seek(pOffset)
                            raf.readFully(b)
                            interp = String(b, Charsets.UTF_8).trimEnd(' ')
                        }
                        PT_DYNAMIC -> { dynOff = pOffset; dynSize = pFilesz }
                        PT_LOAD -> loads += Triple(pVaddr, pFilesz, pOffset)
                    }
                }
            }

            val needed = if (dynOff >= 0) {
                runCatching { readNeeded(raf, bits, dynOff, dynSize, loads, size) }
                    .getOrDefault(emptyList())
            } else emptyList()

            val problem = diagnose(machineName, bits, interp)
            return ElfInfo(
                path = f.absolutePath, isElf = true, bits = bits, machine = machineName,
                type = typeName, interp = interp, needed = needed, sizeBytes = size,
                executableBit = execBit, shebang = null, problem = problem
            )
        }
    }

    /** 判「这东西在本机能不能跑」。措辞按「模型读完就知道下一步该干嘛」写。 */
    private fun diagnose(machine: String, bits: Int, interp: String?): String? {
        val hostArch = deviceArch()
        if (machine != hostArch) {
            return "这是 $machine 的二进制，本机是 $hostArch —— exec 会直接 ENOEXEC。" +
                "去 release 页面挑 aarch64 / arm64 的 asset。"
        }
        if (bits != 64) return "32 位构建，本机是 arm64-v8a，换 aarch64 的 asset。"
        if (interp == null) return null
        if (interp == ElfInfo.ANDROID_LINKER64 || interp == ElfInfo.ANDROID_LINKER32) return null
        return when {
            interp.contains("ld-linux") ->
                "动态链接到 glibc（PT_INTERP=$interp），Android 上没有这个 loader，跑起来会报极具误导性的 " +
                    "\"No such file or directory\"。换 aarch64-unknown-linux-**musl** 的 asset。"
            interp.contains("ld-musl") ->
                "动态链接到 musl（PT_INTERP=$interp），本机没有这个 loader。换静态构建。"
            else ->
                "动态链接到 $interp，本机没有这个 loader。找静态构建（static / musl static）或 -android 构建。"
        }
    }

    fun deviceArch(): String = when {
        android.os.Build.SUPPORTED_ABIS.firstOrNull()?.startsWith("arm64") == true -> "aarch64"
        android.os.Build.SUPPORTED_ABIS.any { it.contains("x86_64") } -> "x86-64"
        android.os.Build.SUPPORTED_ABIS.any { it.startsWith("armeabi") } -> "arm"
        android.os.Build.SUPPORTED_ABIS.any { it.startsWith("x86") } -> "i386"
        else -> "aarch64"
    }

    private fun readNeeded(
        raf: RandomAccessFile,
        bits: Int,
        dynOff: Long,
        dynSize: Long,
        loads: List<Triple<Long, Long, Long>>,
        fileSize: Long
    ): List<String> {
        if (dynSize <= 0 || dynOff + dynSize > fileSize || dynSize > 1 shl 20) return emptyList()
        val entSize = if (bits == 64) 16 else 8
        val buf = ByteArray(dynSize.toInt())
        raf.seek(dynOff)
        raf.readFully(buf)
        var strtabVaddr = -1L
        val neededOffsets = ArrayList<Long>()
        var p = 0
        while (p + entSize <= buf.size) {
            val tag: Long
            val value: Long
            if (bits == 64) { tag = u64(buf, p); value = u64(buf, p + 8) }
            else { tag = u32(buf, p); value = u32(buf, p + 4) }
            if (tag == DT_NULL) break
            when (tag) {
                DT_NEEDED -> neededOffsets += value
                DT_STRTAB -> strtabVaddr = value
            }
            p += entSize
        }
        if (strtabVaddr < 0 || neededOffsets.isEmpty()) return emptyList()
        // strtab 记的是虚拟地址，要靠 PT_LOAD 换算成文件偏移。
        val load = loads.firstOrNull { (v, sz, _) -> strtabVaddr >= v && strtabVaddr < v + sz }
            ?: return emptyList()
        val strtabOff = load.third + (strtabVaddr - load.first)
        return neededOffsets.take(32).mapNotNull { off ->
            val at = strtabOff + off
            if (at < 0 || at >= fileSize) return@mapNotNull null
            runCatching {
                raf.seek(at)
                val sb = StringBuilder()
                while (sb.length < 128) {
                    val c = raf.read()
                    if (c <= 0) break
                    sb.append(c.toChar())
                }
                sb.toString().takeIf { it.isNotEmpty() }
            }.getOrNull()
        }
    }

    private fun machineName(m: Int): String = when (m) {
        0x03 -> "i386"; 0x28 -> "arm"; 0x3E -> "x86-64"; 0xB7 -> "aarch64"
        0xF3 -> "riscv"; 0x08 -> "mips"; 0x15 -> "ppc64"; else -> "machine-0x%x".format(m)
    }

    private fun u16(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or
            ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)

    private fun u64(b: ByteArray, o: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (b[o + i].toLong() and 0xFF)
        return v
    }
}

// =====================================================================
//  tar / zip 读取
// =====================================================================

internal data class TarEntry(
    val name: String,
    val size: Long,
    val mode: Int,
    val type: Char,
    val linkName: String
)

/**
 * ustar + GNU longname + PAX path 的 tar 读取器。
 *
 * 现有的 `BijiPkg.extractTarBinary` 只挖第一个 basename 匹配的普通文件，
 * 多成员、目录树、strip-components 一概不支持，Zig（两万多个条目）
 * 和 Go（整棵 GOROOT）都是它装不了的。
 */
internal class TarReader(private val input: InputStream) {

    private var remaining = 0L
    private var padding = 0L
    private var pendingLongName: String? = null
    private var pendingPaxPath: String? = null

    /** 下一个条目；null = 到头了。调用前会自动跳掉上一个条目没读完的数据。 */
    fun next(): TarEntry? {
        skipRest()
        while (true) {
            val header = ByteArray(512)
            if (!readFully(header)) return null
            if (header.all { it == 0.toByte() }) return null
            var name = cstr(header, 0, 100)
            val mode = octal(header, 100, 8).toInt()
            val size = numeric(header, 124, 12)
            val type = header[156].toInt().toChar().let { if (it == ' ') '0' else it }
            val linkName = cstr(header, 157, 100)
            val prefix = if (isPosixUstar(header)) cstr(header, 345, 155) else ""
            if (prefix.isNotEmpty()) name = "$prefix/$name"

            remaining = size
            padding = ((512 - (size % 512)) % 512)

            when (type) {
                'L' -> { pendingLongName = readCurrentAsString().trimEnd(' '); continue }
                'K' -> { readCurrentAsString(); continue }   // GNU longlink，用不上
                'x', 'X' -> { pendingPaxPath = parsePaxPath(readCurrentAsString()); continue }
                'g' -> { readCurrentAsString(); continue }   // PAX 全局头
                else -> Unit
            }
            pendingLongName?.let { name = it; pendingLongName = null }
            pendingPaxPath?.let { name = it; pendingPaxPath = null }
            return TarEntry(name, size, mode, type, linkName)
        }
    }

    /**
     * 把当前条目的内容倒进 [out]，返回实际写出的字节数。
     *
     * [onChunk] 每写一块调一次（参数是这一块的字节数）：zig 那个 152 MiB
     * 的单文件如果只在条目结束时报一次进度，进度条会在同一个数字上停两
     * 分钟；取消检查也得挂在这里，否则一个大文件中途根本取消不掉。
     */
    fun copyCurrentTo(out: java.io.OutputStream, onChunk: (Int) -> Unit = {}): Long {
        val buf = ByteArray(64 * 1024)
        var written = 0L
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(remaining, buf.size.toLong()).toInt())
            if (n <= 0) break
            out.write(buf, 0, n)
            remaining -= n
            written += n
            onChunk(n)
        }
        return written
    }

    private fun readCurrentAsString(): String {
        val out = ByteArrayOutputStream()
        copyCurrentTo(out)
        skipRest()
        return out.toString("UTF-8")
    }

    private fun skipRest() {
        var left = remaining + padding
        remaining = 0; padding = 0
        val buf = ByteArray(64 * 1024)
        while (left > 0) {
            val n = input.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
            if (n <= 0) return
            left -= n
        }
    }

    private fun readFully(b: ByteArray): Boolean {
        var off = 0
        while (off < b.size) {
            val n = input.read(b, off, b.size - off)
            if (n <= 0) return false
            off += n
        }
        return true
    }

    /** 只有 POSIX ustar 的 prefix 字段是路径前缀；GNU 格式（magic 是
     *  "ustar  " 带两个空格）那块存的是 atime/ctime，当成路径拼上去
     *  会拼出一串垃圾文件名。GNU 的长路径走 'L' 条目，不需要 prefix。 */
    private fun isPosixUstar(h: ByteArray): Boolean =
        h[257] == 'u'.code.toByte() && h[258] == 's'.code.toByte() &&
            h[259] == 't'.code.toByte() && h[260] == 'a'.code.toByte() &&
            h[261] == 'r'.code.toByte() && h[262] == 0.toByte()

    private fun cstr(b: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && b[end] != 0.toByte()) end++
        return String(b, off, end - off, Charsets.UTF_8)
    }

    private fun octal(b: ByteArray, off: Int, len: Int): Long =
        String(b, off, len, Charsets.US_ASCII)
            .trim(' ', ' ').takeIf { it.isNotEmpty() }?.toLongOrNull(8) ?: 0L

    /** GNU 对 >8 GB 的文件用 base-256 编码（首字节 0x80），虽然这里
     *  遇不到，但解错会把整条流的偏移带歪，成本很低就顺手处理了。 */
    private fun numeric(b: ByteArray, off: Int, len: Int): Long {
        if (b[off].toInt() and 0x80 != 0) {
            var v = 0L
            for (i in 1 until len) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
            return v
        }
        return octal(b, off, len)
    }

    private fun parsePaxPath(records: String): String? {
        // 每条记录形如 "<十进制长度> path=xxx\n"
        for (line in records.split('\n')) {
            val i = line.indexOf(' ')
            if (i <= 0) continue
            val kv = line.substring(i + 1)
            if (kv.startsWith("path=")) return kv.removePrefix("path=").trim()
        }
        return null
    }
}

// =====================================================================
//  安装
// =====================================================================

@Immutable
sealed interface ToolchainProgress {
    data object Idle : ToolchainProgress
    data class Downloading(val name: String, val bytes: Long, val total: Long) : ToolchainProgress

    /**
     * 解包中。zig 是两万多个条目、230 MB，只在开始时报一次的话，设置页
     * 上「正在解包 zig」会一动不动地挂好几分钟，用户和 AI 都分不清是在
     * 干活还是卡死了。
     *
     * [entries] 已写出的条目数，[bytes] 已写出的字节数（解压后）。
     * [total] 是目录里登记的安装后体积，**只是量级估计**：上游换个版本
     * 就不准，只配拿来画进度条，不能拿来判完成。归档本身不带解压后总长，
     * 所以目录里没登记时它是 0，这时候就只报个流水号。
     */
    data class Extracting(
        val name: String,
        val entries: Int = 0,
        val bytes: Long = 0L,
        val total: Long = 0L
    ) : ToolchainProgress {
        /** 0f..1f；[total] 未知时返回 null，UI 该退回不确定式进度条。 */
        val fraction: Float?
            get() = if (total > 0L) (bytes.toFloat() / total).coerceIn(0f, 1f) else null
    }

    data class Verifying(val name: String) : ToolchainProgress
    data class Done(val name: String) : ToolchainProgress
    data class Failed(val name: String, val message: String) : ToolchainProgress
}

@Immutable
data class InstallRequest(
    val catalogId: String? = null,
    val url: String? = null,
    val binName: String? = null,
    val format: String = "auto",
    val members: List<String> = emptyList(),
    val stripComponents: Int = 0,
    val prefix: String? = null,
    val links: List<String> = emptyList(),
    val wrapper: WrapperSpec? = null,
    /** 主入口之外的别名 wrapper（`cc` → `zig cc` 这种）。见 [WrapperSpec.args]。 */
    val wrappers: List<WrapperSpec> = emptyList(),
    val multicall: Boolean = false,
    val sha256: String? = null,
    val expectArch: String = "aarch64",
    /** static / bionic / any。any = 静态或 bionic 都收，其它一律拒。 */
    val expectLinkage: String = "any",
    val smokeArg: String? = null,
    val smokeContains: String? = null,
    /** 交互式 TUI 和纯库文件没有能跑的 --version，跑了只会白等超时。 */
    val skipSmoke: Boolean = false,
    val note: String = "",
    val installedBy: String = "ai"
)

@Immutable
data class InstallResult(
    val ok: Boolean,
    val name: String,
    val message: String,
    val commands: List<String> = emptyList(),
    val sizeBytes: Long = 0L,
    val elf: ElfInfo? = null,
    val smokeOk: Boolean = false,
    val smokeOutput: String = "",
    val installPath: String = "",
    val sourceUrl: String = "",
    /** true = 不是装不了，是策略要求先问用户。 */
    val needsConfirm: Boolean = false
)

@Immutable
data class CommandLocation(val name: String, val path: String?, val source: String)

@Immutable
data class EnvReport(
    val arch: String,
    val abi: String,
    val sdkInt: Int,
    val rooted: Boolean,
    val binDir: String,
    val optDir: String,
    val localDir: String,
    val toolchainBytes: Long,
    val freeDiskBytes: Long,
    val budgetBytes: Long,
    val commands: List<CommandLocation>,
    val installed: List<InstalledTool>,
    val policy: ToolchainPolicy
)

@Immutable
data class DiskEntry(val name: String, val bytes: Long, val prefix: String?, val commands: Int)

@Immutable
data class ManageResult(
    val ok: Boolean,
    val message: String,
    val freedBytes: Long = 0L,
    val commands: List<String> = emptyList()
)

/**
 * 「AI 自己把开发环境装起来」的执行层。
 *
 * 和 [BijiPkg] 的分工：BijiPkg 是给设置页那个固定目录用的老路径，只会
 * 装单文件裸二进制；这里管的是完整装配 —— 多种归档格式、从归档里挖指定
 * 成员、整棵树装到 opt/、wrapper、软链、ELF 校验、smoke test、账本、
 * 磁盘预算、卸载。两者共用同一个 bin 目录（[BijiBootstrap.binDir]，
 * 已经在容器和终端的 PATH 上），所以谁装的都能直接敲。
 *
 * 三条硬规矩：
 * 1. **不抛异常**。每个 public 方法都把失败塞进返回值，而且错误消息要能
 *    指导下一步（「换 -musl 那个 asset」而不是「安装失败」）。
 * 2. **要么装好要么什么都没变**。先在 tmp 里下载 + 解包 + 校验，全过了
 *    才动 bin / opt；smoke 没过就整包回滚。半成品比装不上更坑。
 * 3. **按名字加锁**。两个 tool call 同时装同一个东西不会互相踩临时文件。
 */
class ToolchainInstaller(
    context: Context,
    private val bootstrap: BijiBootstrap
) {

    private val appContext = context.applicationContext

    /** 和 bootstrap 共用 —— 这是唯一在 PATH 上的目录。 */
    val binDir: File get() = bootstrap.binDir

    /**
     * 工具链自己的根，**故意不放在 bootstrap 下面**。
     *
     * 原因原本是「BijiBootstrap.uninstall() 会 deleteRecursively 整个 bootstrap，
     * 连带清空 opt/ 和账本」。那个方法已经删了（见 [BijiBootstrap] 的文件头），
     * 但结论照旧成立、而且是更强的那条：账本和它记的资产不该跟别人的目录
     * 同生共死 —— 谁在 bootstrap 下面加一次「清空重来」，账本就和真实文件系统
     * 对不上，而这种不一致没有任何异常，只会表现成 `toolchain_probe` 说装着、
     * shell 里 command not found。
     */
    val root: File get() = File(appContext.filesDir, "toolchain").also { it.mkdirs() }
    val optDir: File get() = File(root, "opt").also { it.mkdirs() }
    val cacheDir: File get() = File(root, "cache").also { it.mkdirs() }
    val tmpDir: File get() = File(root, "tmp").also { it.mkdirs() }

    /** AI 自己编出来的二进制落脚点。work/ 在共享存储上是 noexec 的，
     *  必须拷到内部存储才 exec 得动。 */
    val localDir: File get() = File(root, "local").also { it.mkdirs() }

    val registry: ToolchainRegistry by lazy { ToolchainRegistry(File(root, "manifest.json")) }

    private val _progress = MutableStateFlow<ToolchainProgress>(ToolchainProgress.Idle)
    val progress: StateFlow<ToolchainProgress> = _progress.asStateFlow()

    private val locks = ConcurrentHashMap<String, Mutex>()

    /** 见 [installing]。 */
    private val inFlight = java.util.concurrent.atomic.AtomicInteger(0)

    private fun lockFor(name: String): Mutex = locks.computeIfAbsent(name) { Mutex() }

    // ---- 策略 -----------------------------------------------------------

    val policy: ToolchainPolicy get() = registry.manifest.policy

    fun setPolicy(p: ToolchainPolicy) = registry.setPolicy(p)

    // =================================================================
    //  安装
    // =================================================================

    suspend fun install(req0: InstallRequest): InstallResult = withContext(Dispatchers.IO) {
        val entry = req0.catalogId?.let { ToolchainCatalog.resolve(it) }
        if (!req0.catalogId.isNullOrBlank() && entry == null) {
            val dead = ToolchainCatalog.deadEndFor(req0.catalogId)
            return@withContext InstallResult(
                ok = false, name = req0.catalogId,
                message = dead ?: ("内置目录里没有 \"${req0.catalogId}\"。先用 toolchain_catalog action=search 查，" +
                    "确实没有再 web_search 找直链，然后用 url + bin_name 装 —— " +
                    "挑 asset 时认准 aarch64-unknown-linux-musl 或 -android，别碰 -gnu。")
            )
        }
        val req = merge(req0, entry)
        val name = req.binName?.takeIf { it.isNotBlank() }
            ?: return@withContext InstallResult(
                ok = false, name = "",
                message = "用 url 安装时必须给 bin_name（装完要敲的命令名）。"
            )
        val safe = sanitize(name)
        if (safe.isEmpty()) {
            return@withContext InstallResult(
                ok = false, name = name, message = "bin_name 只能是字母数字和 . _ + - 。"
            )
        }
        val url = req.url
        if (url.isNullOrBlank()) {
            return@withContext InstallResult(
                ok = false, name = safe,
                message = "既没给 catalog_id 也没给 url，不知道该装什么。"
            )
        }
        if (!url.startsWith("https://")) {
            return@withContext InstallResult(
                ok = false, name = safe,
                message = "只接受 https 直链（拿到的是 $url）。"
            )
        }

        val pol = policy
        if (!pol.autoInstall) {
            return@withContext InstallResult(
                ok = false, name = safe, needsConfirm = true,
                message = "用户在设置里关掉了「允许 AI 自动安装工具」。别硬试，" +
                    "跟用户说一句「需要装 $safe，请到设置 → 开发环境里打开自动安装」，然后换个不依赖它的做法继续。"
            )
        }
        // 磁盘闸门。目录里有体积估计的按估计算，没有的按下载体积的 3 倍
        // 粗估（解压后一般 2–3 倍），宁可早拦也别装到一半没空间。
        val expectBytes = entry?.installedBytes ?: 0L
        val used = currentUsageBytes()
        if (pol.budgetBytes != ToolchainPolicy.UNLIMITED && used + expectBytes > pol.budgetBytes) {
            return@withContext InstallResult(
                ok = false, name = safe,
                message = "超出磁盘预算：已用 ${human(used)}，这个包还要 ${human(expectBytes)}，" +
                    "预算是 ${human(pol.budgetBytes)}。先 toolchain_manage action=disk 看谁在占地方，" +
                    "remove 掉用不上的，或让用户在设置里把预算调大。"
            )
        }
        val free = runCatching { root.usableSpace }.getOrDefault(Long.MAX_VALUE)
        if (expectBytes > 0 && free < expectBytes + 64L * 1024 * 1024) {
            return@withContext InstallResult(
                ok = false, name = safe,
                message = "设备剩余空间不够：还剩 ${human(free)}，这个包要 ${human(expectBytes)}。" +
                    "先清点东西再装。"
            )
        }

        lockFor(safe).withLock {
            inFlight.incrementAndGet()
            try {
                doInstall(safe, url, req, entry)
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }

    /**
     * 有没有安装正在跑。[gc] / [wipe] 拿它决定「现在动手会不会把别人正在写的
     * 东西删掉」。
     *
     * 安装后台化之前不需要这个：install 全程占着那一次工具调用，别的工具根本
     * 排不进来。现在 job 跑在 [ToolchainJobs] 的进程级 scope 上，模型完全可能
     * 一边 poll 一边喊 `toolchain_manage action=gc`，用户也可能同时去设置页点
     * 「全部清空并重建」—— 两条路都会删到正在写的文件。
     */
    val installing: Boolean get() = inFlight.get() > 0

    private fun merge(req: InstallRequest, e: CatalogEntry?): InstallRequest {
        if (e == null) return req
        return req.copy(
            url = req.url ?: e.url,
            binName = req.binName ?: e.binName,
            format = if (req.format == "auto") e.format else req.format,
            members = req.members.ifEmpty { e.members },
            stripComponents = if (req.stripComponents == 0) e.stripComponents else req.stripComponents,
            prefix = req.prefix ?: e.prefix,
            links = req.links.ifEmpty { e.links },
            wrapper = req.wrapper ?: e.wrapper,
            wrappers = req.wrappers.ifEmpty { e.wrappers },
            multicall = req.multicall || e.multicall,
            expectLinkage = if (req.expectLinkage == "any") e.linkage else req.expectLinkage,
            skipSmoke = req.skipSmoke || e.skipSmoke,
            note = req.note.ifBlank { e.summary }
        )
    }

    private suspend fun doInstall(
        name: String,
        url: String,
        req: InstallRequest,
        entry: CatalogEntry?
    ): InstallResult {
        val stamp = System.nanoTime().toString(36)
        val dl = File(tmpDir, "dl-$name-$stamp.part")
        val stage = File(tmpDir, "stage-$name-$stamp")
        val created = ArrayList<File>()   // 提交之后建的东西，失败要按这个回滚
        val ctx = currentCoroutineContext()
        try {
            _progress.value = ToolchainProgress.Downloading(name, 0, 0)
            val d = download(url, dl, ctx) { got, total ->
                _progress.value = ToolchainProgress.Downloading(name, got, total)
            }
            if (d.error != null) {
                return fail(name, d.error, url)
            }
            if (!req.sha256.isNullOrBlank() && !d.sha256.equals(req.sha256.trim(), true)) {
                return fail(
                    name,
                    "sha256 对不上：期望 ${req.sha256.trim()}，实际 ${d.sha256}。" +
                        "要么 URL 指错了，要么上游换了构建 —— 别装。",
                    url
                )
            }

            // 目录里登记的解压后体积，只用来画进度条（归档本身不带总长）。
            val expectInstalled = entry?.installedBytes ?: 0L
            _progress.value = ToolchainProgress.Extracting(name, total = expectInstalled)
            val format = detectFormat(req.format, url, dl)
            if (format == "html") {
                return fail(
                    name,
                    "下载到的是 HTML 页面（${human(d.bytes)}），不是文件。" +
                        "多半给的是 release 网页地址而不是 asset 直链 —— " +
                        "GitHub 的直链形如 /releases/download/<tag>/<file>。",
                    url
                )
            }
            stage.mkdirs()
            // zip 条目不带 Unix 权限位，整树装完得自己按内容补执行位；
            // tar 有 mode 字段，解包时就设好了，不必再走一遍两万个文件。
            val needTreeChmod = format == "zip"
            val ex = try {
                extract(dl, format, req, stage, name) { entries, bytes ->
                    _progress.value = ToolchainProgress.Extracting(name, entries, bytes, expectInstalled)
                }
            } catch (e: CancellationException) {
                throw e                      // 取消不是「解包失败」，交给下面的 catch 统一清场
            } catch (e: MemoryLimitException) {
                // 单独接：库给的是一句英文 + KiB 数字，混在「解包失败」里模型
                // 会当成偶发错误，拿同一个 URL 再试一遍 —— 而这个失败是确定性的。
                return fail(
                    name,
                    "这个 .tar.xz 声明的 LZMA2 字典要 ${e.memoryNeeded / 1024} MB 内存，" +
                        "超过解包上限 ${XZ_MEMORY_LIMIT_KIB / 1024} MB —— 手机上撑不住，重试没用。" +
                        "换同一个 release 里的 .tar.gz / .zip asset。",
                    url
                )
            } catch (e: Exception) {
                return fail(
                    name,
                    "解包失败（format=$format）：${e.message ?: e.javaClass.simpleName}",
                    url
                )
            }
            if (ex.files.isEmpty()) {
                val listing = ex.seen.take(30).joinToString("\n  ")
                return fail(
                    name,
                    "归档里没有匹配 members 的成员。members=${req.members}，" +
                        "归档里实际有：\n  $listing${if (ex.seen.size > 30) "\n  …（共 ${ex.seen.size} 条）" else ""}\n" +
                        "改 members 的 glob 重试（\"*/rg\" 匹配一层目录下的 rg，\"**\" 表示整棵树）。",
                    url
                )
            }

            // ---- 校验：必须在动 bin / opt 之前 --------------------------
            _progress.value = ToolchainProgress.Verifying(name)
            val primaryStaged = pickPrimary(stage, ex.files, name, req)
            if (primaryStaged == null) {
                return fail(
                    name,
                    "解包出来了 ${ex.files.size} 个文件，但找不到叫 \"$name\" 的入口：" +
                        ex.files.take(10).joinToString(", ") { it.name } +
                        "。bin_name 要和归档里那个可执行文件的名字对上，或者用 wrapper / links 指明入口。",
                    url
                )
            }
            val elf = ElfProbe.probe(primaryStaged)
            val verdict = verify(elf, req)
            if (verdict != null) return fail(name, verdict, url)

            // ---- 提交 ----------------------------------------------------
            val commands = ArrayList<String>()
            val installPath: String
            if (!req.prefix.isNullOrBlank()) {
                val target = File(optDir, sanitize(req.prefix))
                if (target.exists()) target.deleteRecursively()
                target.parentFile?.mkdirs()
                if (!stage.renameTo(target)) {
                    stage.copyRecursively(target, overwrite = true)
                    stage.deleteRecursively()
                }
                created += target
                installPath = target.absolutePath
                if (needTreeChmod) chmodTree(target)
            } else {
                binDir.mkdirs()
                for (f in ex.files) {
                    val dst = File(binDir, f.name)
                    dst.delete()
                    if (!f.renameTo(dst)) {
                        f.copyTo(dst, overwrite = true)
                        f.delete()
                    }
                    chmodX(dst)
                    created += dst
                    commands += dst.name
                }
                installPath = File(binDir, name).absolutePath
            }

            // 软链 + wrapper：prefix 模式下这是唯一的对外入口。
            for (spec in req.links) {
                val made = makeLink(spec) ?: continue
                created += made
                commands += made.name
            }
            val prefixName = req.prefix?.let { sanitize(it) } ?: name
            req.wrapper?.let { w ->
                val made = writeWrapper(w, prefixName, name)
                created += made
                commands += made.name
            }
            // 别名 wrapper：`cc` / `gcc` 这些名字必须真的存在于 PATH 上，
            // Makefile 的 $(CC) 和 ./configure 只认名字，不会去查「zig 装了没」。
            for (w in req.wrappers) {
                val made = writeWrapper(w, prefixName, name)
                created += made
                commands += made.name
            }
            if (commands.none { it == name } && File(binDir, name).exists()) {
                commands += name
            }

            if (req.multicall) {
                val main = File(binDir, name)
                val extra = linkApplets(main)
                created += extra
                commands += extra.map { it.name }
            }

            // ---- smoke test ---------------------------------------------
            // 不跑的话，「装了个 x86_64 二进制」这种事要等到 AI 真正用它的
            // 时候才炸，而那时错误落在另一个工具调用里，模型很难归因。
            val entryCmd = File(binDir, req.wrapper?.name?.let { sanitize(it) } ?: name)
            val smoke = when {
                req.skipSmoke -> Smoke(true, "", "按目录标记跳过验活（交互式程序或库文件）")
                !entryCmd.exists() -> Smoke(true, "", "没有可执行入口，跳过验活")
                else -> smokeTest(entryCmd, req.smokeArg, req.smokeContains)
            }
            if (!smoke.ok) {
                created.forEach { runCatching { it.deleteRecursively() } }
                return fail(
                    name,
                    "装是装上了，但跑不起来 —— 已经整包回滚，别拿它去干活。\n" +
                        "${smoke.detail}\n" +
                        "ELF 检查：${describe(elf)}\n" +
                        "换个来源重试（认准静态 musl 或 -android 构建），或者换条技术路线。",
                    url
                )
            }

            // 软链要排掉：busybox 展开三百个 applet 全是指向同一个二进制的
            // 软链，isFile 对它们都是 true、length() 返回的是目标的大小，
            // 直接 sum 会把 2 MB 的 busybox 算成 600 MB，磁盘预算和设置页
            // 上的数字跟着一起错。
            val sizeBytes = if (!req.prefix.isNullOrBlank()) measure(File(installPath))
            else created.filter { it.isFile && !isSymlink(it) }.sumOf { it.length() }

            registry.record(
                InstalledTool(
                    name = name,
                    catalogId = entry?.id,
                    version = smoke.version,
                    url = url,
                    sizeBytes = sizeBytes,
                    installedAt = System.currentTimeMillis(),
                    installedBy = req.installedBy,
                    commands = commands.distinct(),
                    prefix = req.prefix?.let { sanitize(it) },
                    linkage = elf.linkage,
                    smokeOk = true,
                    smokeOutput = smoke.version,
                    note = req.note
                )
            )
            _progress.value = ToolchainProgress.Done(name)
            return InstallResult(
                ok = true, name = name,
                message = "已装好 $name（${human(sizeBytes)}，来源 ${hostOf(url)}）。" +
                    "container_exec / 终端里现在可以直接敲：" +
                    commands.distinct().take(12).joinToString(" "),
                commands = commands.distinct(),
                sizeBytes = sizeBytes,
                elf = elf,
                smokeOk = true,
                smokeOutput = smoke.version,
                installPath = installPath,
                sourceUrl = url
            )
        } catch (e: CancellationException) {
            // 用户划走 / ViewModel 被清掉都会走到这里。半装状态比装不上更坑：
            // bin 里留着能敲的 cc、opt 里躺着半棵 zig，但账本里没有记录，
            // 下次 ensure 一看「命令在」就当装好了。所以先把已落地的入口
            // 全删掉（stage 和下载残留由 finally 收），再把取消原样抛出去 ——
            // 咽掉它会让上层的 withContext 以为任务正常结束。
            created.forEach { runCatching { it.deleteRecursively() } }
            _progress.value = ToolchainProgress.Idle
            throw e
        } catch (e: OutOfMemoryError) {
            created.forEach { runCatching { it.deleteRecursively() } }
            return fail(
                name,
                "内存不够解这个包。.tar.xz 的字典可能到 64 MB —— 换同一个 release 里的 .tar.gz asset 试试。",
                url
            )
        } catch (e: Exception) {
            created.forEach { runCatching { it.deleteRecursively() } }
            return fail(name, e.message ?: e.javaClass.simpleName, url)
        } finally {
            runCatching { dl.delete() }
            runCatching { if (stage.exists()) stage.deleteRecursively() }
        }
    }

    private fun fail(name: String, msg: String, url: String): InstallResult {
        _progress.value = ToolchainProgress.Failed(name, msg)
        return InstallResult(ok = false, name = name, message = msg, sourceUrl = url)
    }

    /** 校验结论 —— 返回 null 表示可以装。 */
    private fun verify(elf: ElfInfo, req: InstallRequest): String? {
        if (!elf.isElf) {
            if (elf.shebang != null) return null   // 脚本入口，放行
            return elf.problem ?: "不是可执行文件。"
        }
        if (req.expectArch.isNotBlank() && req.expectArch != "any" &&
            !elf.machine.equals(req.expectArch, true)
        ) {
            return elf.problem
                ?: "架构不对：期望 ${req.expectArch}，实际 ${elf.machine}。"
        }
        elf.problem?.let { return it }
        return when (req.expectLinkage) {
            "static" -> if (elf.linkage == "static") null
            else "期望静态链接，实际是 ${elf.linkage}（PT_INTERP=${elf.interp}）。" +
                "换 -musl 的静态 asset。"
            "bionic" -> if (elf.linkage == "bionic") null
            else "期望 Android 原生（bionic）构建，实际是 ${elf.linkage}。挑 -android 后缀的 asset。"
            else -> null
        }
    }

    private fun describe(e: ElfInfo): String = buildString {
        if (!e.isElf) { append(e.shebang?.let { "脚本，$it" } ?: "非 ELF"); return@buildString }
        append("ELF${e.bits} ${e.machine} ${e.type} ${e.linkage}")
        e.interp?.let { append(" interp=$it") }
        if (e.needed.isNotEmpty()) append(" needs=${e.needed.joinToString(",")}")
    }

    // ---- 下载 -----------------------------------------------------------

    private class Downloaded(
        val bytes: Long,
        val sha256: String,
        val contentType: String,
        val error: String?
    )

    /** [ctx] 只用来在读循环里查取消 —— 一个 400 MB 的包不检查的话，
     *  用户点了取消之后还得等它下完才有反应。 */
    private fun download(
        url: String,
        dst: File,
        ctx: kotlin.coroutines.CoroutineContext,
        onProgress: (Long, Long) -> Unit
    ): Downloaded {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "biji-toolchain/1")
                setRequestProperty("Accept", "*/*")
            }
            conn.connect()
            val code = conn.responseCode
            if (code !in 200..299) {
                return Downloaded(
                    0, "", "",
                    "HTTP $code${conn.responseMessage?.let { " $it" } ?: ""}。" +
                        if (code == 404) "URL 不对 —— 版本号或文件名多半变了，去 release 页面重新确认 asset 名。"
                        else "换个来源或稍后重试。"
                )
            }
            val total = conn.contentLengthLong.coerceAtLeast(0L)
            if (total > MAX_DOWNLOAD_BYTES) {
                return Downloaded(
                    0, "", "",
                    "这个文件 ${human(total)}，超过单包上限 ${human(MAX_DOWNLOAD_BYTES)}，不下。"
                )
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var read = 0L
            dst.parentFile?.mkdirs()
            FileOutputStream(dst).use { out ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(128 * 1024)
                    while (true) {
                        ctx.ensureActive()
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        read += n
                        if (read > MAX_DOWNLOAD_BYTES) {
                            return Downloaded(
                                read, "", "",
                                "下载超过 ${human(MAX_DOWNLOAD_BYTES)} 上限，已中止。"
                            )
                        }
                        onProgress(read, total)
                    }
                }
            }
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            return Downloaded(read, hex, conn.contentType.orEmpty(), null)
        } catch (e: CancellationException) {
            // 必须先于下面那个 catch —— 被它接住的话取消会变成一条
            // 「下载失败」的错误消息，调用方以为是网络问题然后重试。
            throw e
        } catch (e: Exception) {
            return Downloaded(
                0, "", "",
                "下载失败：${e.message ?: e.javaClass.simpleName}（$url）"
            )
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    // ---- 解包 -----------------------------------------------------------

    private class Extracted(val files: List<File>, val seen: List<String>)

    private fun detectFormat(declared: String, url: String, f: File): String {
        val magic = ByteArray(8)
        val n = runCatching { f.inputStream().use { it.read(magic) } }.getOrDefault(0)
        // 魔数优先于后缀：后缀骗人的情况（.zip 里其实是 tar.gz）比魔数骗人多。
        if (n >= 4) {
            if (magic[0] == 0x7F.toByte() && magic[1] == 'E'.code.toByte() &&
                magic[2] == 'L'.code.toByte() && magic[3] == 'F'.code.toByte()
            ) return "raw"
            if (magic[0] == 0x1F.toByte() && magic[1] == 0x8B.toByte()) return "tar.gz"
            if (magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte()) return "zip"
            if (n >= 6 && magic[0] == 0xFD.toByte() && magic[1] == '7'.code.toByte() &&
                magic[2] == 'z'.code.toByte() && magic[3] == 'X'.code.toByte()
            ) return "tar.xz"
            val text = String(magic, 0, n, Charsets.US_ASCII).lowercase()
            if (text.startsWith("<!doct") || text.startsWith("<html") || text.startsWith("<?xml")) {
                return "html"
            }
        }
        if (declared != "auto" && declared.isNotBlank()) return declared
        val u = url.lowercase().substringBefore('?')
        return when {
            u.endsWith(".tar.gz") || u.endsWith(".tgz") || u.endsWith(".apk") -> "tar.gz"
            u.endsWith(".tar.xz") || u.endsWith(".txz") -> "tar.xz"
            u.endsWith(".zip") -> "zip"
            u.endsWith(".tar") -> "tar"
            else -> "raw"
        }
    }

    /**
     * [onProgress] 参数是 (已写出条目数, 已写出字节数)，已经在 [Ticker] 里
     * 按时间片节流过了 —— 逐条目直发的话 zig 会往 StateFlow 里塞两万多次，
     * Compose 每次都得重组一遍设置页。
     */
    private suspend fun extract(
        src: File,
        format: String,
        req: InstallRequest,
        stage: File,
        name: String,
        onProgress: (Int, Long) -> Unit
    ): Extracted {
        return when (format) {
            "raw" -> {
                val dst = File(stage, name)
                src.copyTo(dst, overwrite = true)
                chmodX(dst)
                onProgress(1, dst.length())
                Extracted(listOf(dst), listOf(name))
            }
            "zip" -> extractZip(src, req, stage, onProgress)
            else -> {
                val raw = src.inputStream().buffered(1 shl 16)
                val stream: InputStream = when (format) {
                    "tar.gz", "apk" -> GZIPInputStream(raw, 1 shl 16)
                    // 这里曾经挂着一份手写的 LZMA2 解码器。LZMA2 解错的样子是
                    // **静默产出坏字节**（不报错、长度还对），坏在编译器二进制
                    // 里要等到某次 zig cc 莫名其妙崩掉才暴露，几乎无法归因。
                    // 换成 org.tukaani:xz：默认验 CRC，坏数据当场 IOException。
                    // 内存上限必须显式给（见 XZ_MEMORY_LIMIT_KIB）——
                    // 默认的「不限」会让一个字典离谱的包把整个进程 OOM 掉。
                    "tar.xz" -> XZInputStream(raw, XZ_MEMORY_LIMIT_KIB)
                    "tar" -> raw
                    else -> throw IOException("不支持的格式 \"$format\"（认识 raw / tar.gz / tar.xz / zip / apk）")
                }
                stream.use { extractTar(it, req, stage, onProgress) }
            }
        }
    }

    private suspend fun extractTar(
        input: InputStream,
        req: InstallRequest,
        stage: File,
        onProgress: (Int, Long) -> Unit
    ): Extracted {
        // 取一次 context 给下面那些非挂起回调用：ensureActive() 是
        // CoroutineContext 上的普通扩展，不需要挂起点，所以取消能在
        // 一个 152 MB 的条目**拷到一半时**生效，而不是等它写完。
        val ctx = currentCoroutineContext()
        val tick = Ticker(onProgress)
        val reader = TarReader(input)
        val out = ArrayList<File>()
        val seen = ArrayList<String>()
        val flat = req.prefix.isNullOrBlank()
        val patterns = req.members.ifEmpty { listOf(req.binName ?: "*") }
        while (true) {
            ctx.ensureActive()
            val e = reader.next() ?: break
            if (seen.size < MAX_LISTING) seen += e.name
            if (e.type == '5') continue                    // 目录：按需在写文件时建
            if (e.type != '0' && e.type != ' ' && e.type != '2') continue
            if (!matchesAny(patterns, e.name)) continue
            val rel = strip(e.name, req.stripComponents) ?: continue
            val dst = safeChild(stage, if (flat) rel.substringAfterLast('/') else rel) ?: continue
            if (e.type == '2') {
                // tar 里的软链：目标在同一棵树里才有意义，扁平模式下没意义，跳过。
                if (!flat) runCatching {
                    dst.parentFile?.mkdirs()
                    android.system.Os.symlink(e.linkName, dst.absolutePath)
                }
                continue
            }
            dst.parentFile?.mkdirs()
            FileOutputStream(dst).use { fo ->
                reader.copyCurrentTo(fo) { n ->
                    ctx.ensureActive()
                    tick.add(n)
                }
            }
            if (e.mode and 0b001_001_001 != 0) chmodX(dst) else maybeChmodX(dst)
            out += dst
            tick.entryDone()
        }
        tick.flush()
        return Extracted(out, seen)
    }

    private suspend fun extractZip(
        src: File,
        req: InstallRequest,
        stage: File,
        onProgress: (Int, Long) -> Unit
    ): Extracted {
        val ctx = currentCoroutineContext()
        val tick = Ticker(onProgress)
        val out = ArrayList<File>()
        val seen = ArrayList<String>()
        val flat = req.prefix.isNullOrBlank()
        val patterns = req.members.ifEmpty { listOf(req.binName ?: "*") }
        ZipInputStream(src.inputStream().buffered(1 shl 16)).use { zin ->
            while (true) {
                ctx.ensureActive()
                val e = zin.nextEntry ?: break
                if (seen.size < MAX_LISTING) seen += e.name
                if (e.isDirectory) { zin.closeEntry(); continue }
                if (!matchesAny(patterns, e.name)) { zin.closeEntry(); continue }
                val rel = strip(e.name, req.stripComponents)
                if (rel == null) { zin.closeEntry(); continue }
                val dst = safeChild(stage, if (flat) rel.substringAfterLast('/') else rel)
                if (dst == null) { zin.closeEntry(); continue }
                dst.parentFile?.mkdirs()
                FileOutputStream(dst).use { fo ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        ctx.ensureActive()
                        val n = zin.read(buf)
                        if (n <= 0) break
                        fo.write(buf, 0, n)
                        tick.add(n)
                    }
                }
                zin.closeEntry()
                // zip 里没有 Unix 权限位（ZipInputStream 不给外部属性），
                // 只能按内容判断：ELF 或 #! 开头的就给执行权限。
                maybeChmodX(dst)
                out += dst
                tick.entryDone()
            }
        }
        tick.flush()
        return Extracted(out, seen)
    }

    /**
     * 解包进度节流器。一秒钟刷八次足够让人看出「它在动」，再密只是白白
     * 发热。[flush] 补最后一次 —— 少了它进度条会永远停在差一点的位置。
     */
    private class Ticker(private val sink: (Int, Long) -> Unit) {
        private var entries = 0
        private var bytes = 0L
        private var lastAt = 0L
        private var lastBytes = -1L

        fun add(n: Int) { bytes += n; maybeEmit() }

        fun entryDone() { entries++; maybeEmit() }

        fun flush() { if (bytes != lastBytes) sink(entries, bytes) }

        private fun maybeEmit() {
            val now = System.currentTimeMillis()
            if (now - lastAt < INTERVAL_MS) return
            lastAt = now
            lastBytes = bytes
            sink(entries, bytes)
        }

        private companion object { const val INTERVAL_MS = 120L }
    }

    /** 归档路径 → glob。`**` 跨目录，`*` 不跨，裸名字自动当成 `**\/名字`。 */
    private fun matchesAny(patterns: List<String>, path: String): Boolean =
        patterns.any { globMatch(it, path) }

    private fun globMatch(pattern: String, path: String): Boolean {
        val p = if (pattern.contains('/')) pattern else "**/$pattern"
        val re = StringBuilder("^")
        var i = 0
        while (i < p.length) {
            val c = p[i]
            when {
                c == '*' && i + 2 < p.length && p[i + 1] == '*' && p[i + 2] == '/' -> {
                    re.append("(?:[^/]+/)*"); i += 3
                }
                c == '*' && i + 1 < p.length && p[i + 1] == '*' -> { re.append(".*"); i += 2 }
                c == '*' -> { re.append("[^/]*"); i++ }
                c == '?' -> { re.append("[^/]"); i++ }
                else -> { re.append(Regex.escape(c.toString())); i++ }
            }
        }
        re.append("$")
        return runCatching { Regex(re.toString()).matches(path.trimStart('.', '/')) }
            .getOrDefault(false)
    }

    private fun strip(path: String, n: Int): String? {
        val clean = path.trim().trimStart('/').removePrefix("./")
        if (clean.isEmpty()) return null
        if (n <= 0) return clean
        val parts = clean.split('/')
        if (parts.size <= n) return null
        return parts.drop(n).joinToString("/")
    }

    /** 路径穿越防护：解开的东西必须落在 stage 目录里面。 */
    private fun safeChild(base: File, rel: String): File? {
        if (rel.isBlank()) return null
        if (rel.split('/').any { it == ".." }) return null
        val f = File(base, rel)
        val b = runCatching { base.canonicalPath }.getOrDefault(base.absolutePath)
        val c = runCatching { f.canonicalPath }.getOrDefault(f.absolutePath)
        return if (c == b || c.startsWith("$b/")) f else null
    }

    private fun pickPrimary(
        stage: File,
        files: List<File>,
        name: String,
        req: InstallRequest
    ): File? {
        if (!req.prefix.isNullOrBlank()) {
            // 整树安装：入口由 wrapper.exec 或第一条 link 指出来。
            req.wrapper?.let { w ->
                val rel = w.exec.substringAfter("\$PREFIX/", "").substringBefore(' ')
                if (rel.isNotBlank()) File(stage, rel).takeIf { it.isFile }?.let { return it }
            }
            req.links.firstOrNull()?.let { spec ->
                val target = spec.substringAfter("=>", "").trim()
                val rel = target.removePrefix("opt/").substringAfter('/')
                File(stage, rel).takeIf { it.isFile }?.let { return it }
            }
            return File(stage, name).takeIf { it.isFile }
                ?: File(stage, "bin/$name").takeIf { it.isFile }
                ?: files.firstOrNull { it.name == name }
                // 纯载荷包（musl loader 那种没有可执行入口的）也得挑一个出来
                // 做 ELF 校验，否则等于什么都没验。挑最大的那个最接近主体。
                ?: files.maxByOrNull { it.length() }
        }
        return files.firstOrNull { it.name == name } ?: files.singleOrNull()
    }

    // ---- 软链 / wrapper --------------------------------------------------

    /** spec 形如 `"zig=>opt/zig/zig"` 或 `"git=>gix"`，右边相对
     *  toolchain root；不带 `opt/` 前缀的当成 bin 目录里的兄弟命令。 */
    private fun makeLink(spec: String): File? {
        val i = spec.indexOf("=>")
        val linkName = sanitize(if (i > 0) spec.substring(0, i) else spec)
        val targetRel = if (i > 0) spec.substring(i + 2).trim() else return null
        if (linkName.isEmpty() || targetRel.isEmpty()) return null
        val target = when {
            targetRel.startsWith("/") -> File(targetRel)
            targetRel.contains('/') -> File(root, targetRel)
            else -> File(binDir, targetRel)
        }
        if (!target.exists()) return null
        val link = File(binDir, linkName)
        link.delete()
        val ok = runCatching {
            android.system.Os.symlink(target.absolutePath, link.absolutePath); true
        }.getOrDefault(false)
        if (!ok) {
            // 软链建不了就退化成一行 sh —— 功能一样，只是多一次 fork。
            link.writeText("#!/system/bin/sh\nexec ${shq(target.absolutePath)} \"\$@\"\n")
        }
        chmodX(link)
        return link
    }

    /**
     * wrapper 脚本。zig 要 ZIG_GLOBAL_CACHE_DIR、go 要 GOROOT/GOCACHE，
     * 动态 musl 二进制要 ld-musl loader 前缀 —— 这些不设好，装了也跑不动。
     *
     * 占位符在这里就展开成绝对路径，脚本里不留 `$BIJI_*`：终端、容器
     * 会话、后台任务、smoke test 四种调用方式的 env 不保证一致。
     */
    private fun writeWrapper(w: WrapperSpec, prefixName: String, cacheName: String): File {
        val prefixDir = File(optDir, sanitize(prefixName))
        val cache = File(cacheDir, sanitize(cacheName))
        val env = w.env.mapValues { (_, v) -> expand(v, prefixDir, cache) }
        val exec = expand(w.exec, prefixDir, cache)
        // 固定前置参数每个单独引号包起来，而不是拼成一整串塞进 exec：
        // `zig cc` 只是两个词还好，可 `--sysroot /a b/c` 这种带空格的
        // 拼进去就散架了，而 shq 之后 sh 一定按一个词传下去。
        val fixed = w.args.joinToString("") { " " + shq(expand(it, prefixDir, cache)) }
        val script = buildString {
            appendLine("#!/system/bin/sh")
            appendLine("# 由 biji toolchain 生成，删掉这个文件不影响 opt/ 里的主体")
            for ((k, v) in env) {
                if (v.startsWith(root.absolutePath)) appendLine("mkdir -p ${shq(v)} 2>/dev/null")
                appendLine("export $k=${shq(v)}")
            }
            // "$@" 必须带引号：不带的话 `cc -o "my file" x.c` 里的空格会被
            // 重新拆词，编出来的产物名字莫名其妙少一半。
            appendLine("exec $exec$fixed \"\$@\"")
        }
        val f = File(binDir, sanitize(w.name.ifBlank { cacheName }))
        f.delete()
        f.writeText(script, Charsets.UTF_8)
        chmodX(f)
        return f
    }

    private fun expand(s: String, prefixDir: File, cache: File): String = s
        .replace("\$PREFIX", prefixDir.absolutePath)
        .replace("\$CACHE", cache.absolutePath)
        .replace("\$BIJI_OPT", optDir.absolutePath)
        .replace("\$BIJI_BIN", binDir.absolutePath)
        .replace("\$BIJI_ROOT", root.absolutePath)
        .replace("\$BIJI_TMP", tmpDir.absolutePath)

    /** multicall 二进制装完按它自己报的 applet 列表建软链。 */
    private fun linkApplets(main: File): List<File> {
        if (!main.isFile) return emptyList()
        val attempts = listOf(listOf("--list"), listOf("--long"), listOf("--help"), emptyList())
        val names = attempts.firstNotNullOfOrNull { flags ->
            val r = exec(listOf(main.absolutePath) + flags, 8_000L)
            r.output.split(Regex("[\\\\s,]+"))
                .map { it.trim().substringAfterLast('/') }
                .filter { n ->
                    n.isNotBlank() && n.length <= 24 &&
                        n.all { c -> c.isLetterOrDigit() || c == '-' || c == '_' || c == '.' }
                }
                .distinct()
                .takeIf { it.size >= 10 }
        } ?: return emptyList()
        val made = ArrayList<File>()
        for (n in names) {
            if (n == main.name) continue
            val link = File(binDir, n)
            if (link.exists()) continue
            val ok = runCatching {
                android.system.Os.symlink(main.absolutePath, link.absolutePath); true
            }.getOrDefault(false)
            if (!ok) {
                runCatching {
                    link.writeText("#!/system/bin/sh\nexec ${shq(main.absolutePath)} $n \"\$@\"\n")
                }
            }
            chmodX(link)
            made += link
        }
        return made
    }

    // ---- 执行 / smoke ----------------------------------------------------

    private class Run(val exitCode: Int, val output: String, val error: String?)

    private class Smoke(val ok: Boolean, val version: String, val detail: String)

    /**
     * 装完立刻跑一次。不跑的话，「装了个 x86_64 二进制」这种事要等到 AI
     * 真正用它的时候才炸，而那时错误落在另一个工具调用里，模型很难归因。
     */
    private fun smokeTest(bin: File, arg: String?, contains: String?): Smoke {
        if (!bin.exists()) return Smoke(false, "", "入口 ${bin.absolutePath} 不存在。")
        val args = buildList {
            arg?.takeIf { it.isNotBlank() }?.let { add(it) }
            add("--version"); add("-V"); add("version"); add("--help")
        }.distinct()
        var last = "没有任何输出"
        for (a in args) {
            val r = exec(listOf(bin.absolutePath, a), SMOKE_TIMEOUT_MS)
            if (r.error != null) {
                last = r.error
                if (r.error.contains("ENOEXEC", true) || r.error.contains("Exec format")) {
                    return Smoke(false, "", "exec 直接失败：${r.error}（架构不对）")
                }
                continue
            }
            val line = r.output.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            if (r.exitCode == 0 && line.isNotEmpty()) {
                if (!contains.isNullOrBlank() && !r.output.contains(contains, true)) {
                    return Smoke(false, line, "跑起来了但输出里没有期望的 \"$contains\"：$line")
                }
                return Smoke(true, line.take(200), "")
            }
            if (line.isNotEmpty()) last = "退出码 ${r.exitCode}：$line"
        }
        return Smoke(false, "", "试过 ${args.joinToString(" / ")} 都没能正常返回。最后一次：$last")
    }

    /**
     * 起一个子进程收输出。读输出必须和 waitFor 并行，不然管道缓冲区
     * 满了双方互等 —— 这是经典死锁，`--help` 输出长一点就能踩到。
     */
    private fun exec(cmd: List<String>, timeoutMs: Long, workDir: File? = null): Run {
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        applyEnv(pb)
        workDir?.let { if (it.isDirectory) pb.directory(it) }
        val p = try {
            pb.start()
        } catch (e: Exception) {
            return Run(-1, "", e.message ?: e.javaClass.simpleName)
        }
        val sb = StringBuilder()
        val reader = Thread {
            runCatching {
                p.inputStream.use { ins ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        synchronized(sb) {
                            if (sb.length < MAX_PROBE_OUTPUT) {
                                sb.append(String(buf, 0, n, Charsets.UTF_8))
                            }
                        }
                    }
                }
            }
        }
        reader.isDaemon = true
        reader.start()
        val finished = runCatching { p.waitFor(timeoutMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        if (!finished) runCatching { p.destroyForcibly() }
        runCatching { reader.join(500) }
        val text = synchronized(sb) { sb.toString() }
        return if (finished) Run(p.exitValue(), text, null)
        else Run(-1, text, "超时（${timeoutMs}ms）未退出，已强杀")
    }

    private fun applyEnv(pb: ProcessBuilder) {
        val env = pb.environment()
        val existing = env["PATH"] ?: System.getenv("PATH") ?: "/system/bin:/system/xbin"
        env["PATH"] = listOf(binDir.absolutePath, localDir.absolutePath)
            .joinToString(":") + ":" + existing
        env["HOME"] = root.absolutePath
        env["TMPDIR"] = tmpDir.absolutePath
        env["LANG"] = "C.UTF-8"
        env["TERM"] = "dumb"
        env["BIJI_BIN"] = binDir.absolutePath
        env["BIJI_OPT"] = optDir.absolutePath
    }

    // =================================================================
    //  管理
    // =================================================================

    /** 卸载。只删账本登记过的东西 —— 不按名字猜，猜错会误删用户的文件。 */
    suspend fun remove(name: String, purge: Boolean): ManageResult =
        withContext(Dispatchers.IO) {
            val t = registry.find(name)
                ?: return@withContext ManageResult(
                    false,
                    "清单里没有 \"$name\"。toolchain_manifest action=get 看看实际装了什么；" +
                        "如果它是 bootstrap 自带的 toybox applet，那不归这里管。"
                )
            lockFor(t.name).withLock {
                var freed = 0L
                for (c in t.commands) {
                    val f = File(binDir, c)
                    freed += runCatching { if (f.isFile) f.length() else 0L }.getOrDefault(0L)
                    runCatching { f.delete() }
                }
                t.prefix?.let { p ->
                    val d = File(optDir, p)
                    freed += measure(d)
                    runCatching { d.deleteRecursively() }
                }
                val local = File(localDir, t.name)
                if (local.exists()) {
                    freed += measure(local)
                    runCatching { local.deleteRecursively() }
                }
                if (purge) {
                    val c = File(cacheDir, t.name)
                    freed += measure(c)
                    runCatching { c.deleteRecursively() }
                }
                registry.forget(t.name)
                ManageResult(
                    true,
                    "已卸载 ${t.name}，释放 ${human(freed)}（清掉了 ${t.commands.size} 个命令入口" +
                        "${if (purge) " 和缓存目录" else ""}）。",
                    freed, t.commands
                )
            }
        }

    /**
     * 建入口。AI 自己 `zig cc` 编出来的二进制走的也是这条路：
     * work/ 在共享存储上多半 noexec，先拷进内部存储再建软链，
     * 之后当普通命令敲就行。
     */
    suspend fun link(
        name: String,
        target: String,
        wrapperEnv: Map<String, String>,
        workDir: File?
    ): ManageResult = withContext(Dispatchers.IO) {
        val safe = sanitize(name)
        if (safe.isEmpty()) return@withContext ManageResult(false, "name 只能是字母数字和 . _ + - 。")
        val src = resolveTarget(target, workDir)
            ?: return@withContext ManageResult(
                false,
                "找不到 \"$target\"。可以给绝对路径、work/ 下的相对路径、opt/xxx/yyy，或者 bin 里已有的命令名。"
            )
        val elf = ElfProbe.probe(src)
        if (elf.problem != null && elf.shebang == null) {
            return@withContext ManageResult(false, "\"$target\" 跑不起来：${elf.problem}")
        }
        // 目标在内部存储外面（work/ 就是）就必须拷进来，否则 exec 不动。
        val needCopy = !isInsideInternal(src)
        val realTarget = if (needCopy) {
            val dst = File(localDir, safe)
            dst.parentFile?.mkdirs()
            runCatching { src.copyTo(dst, overwrite = true) }
                .getOrElse { return@withContext ManageResult(false, "拷贝失败：${it.message}") }
            chmodX(dst)
            dst
        } else {
            chmodX(src); src
        }
        val entry = if (wrapperEnv.isEmpty()) {
            makeLink("$safe=>${realTarget.absolutePath}")
                ?: return@withContext ManageResult(false, "建入口失败。")
        } else {
            writeWrapper(
                WrapperSpec(safe, wrapperEnv, shq(realTarget.absolutePath)),
                safe, safe
            )
        }
        val smoke = smokeTest(entry, null, null)
        registry.record(
            InstalledTool(
                name = safe,
                catalogId = null,
                version = smoke.version,
                url = "local:${realTarget.absolutePath}",
                sizeBytes = measure(realTarget),
                installedAt = System.currentTimeMillis(),
                installedBy = "ai",
                commands = listOf(safe),
                prefix = null,
                linkage = elf.linkage,
                smokeOk = smoke.ok,
                smokeOutput = smoke.version,
                note = "本地产物"
            )
        )
        ManageResult(
            true,
            "已经可以直接敲 $safe（指向 ${realTarget.absolutePath}" +
                "${if (needCopy) "，从 work/ 拷进了可执行区" else ""}）。" +
                if (!smoke.ok) " 注意：验活没通过（${smoke.detail}），如果它本来就不支持 --version 可以忽略。" else "",
            commands = listOf(safe)
        )
    }

    suspend fun unlink(name: String): ManageResult = withContext(Dispatchers.IO) {
        val safe = sanitize(name)
        val f = File(binDir, safe)
        if (!f.exists()) return@withContext ManageResult(false, "$safe 不在 bin 目录里。")
        val ok = runCatching { f.delete() }.getOrDefault(false)
        registry.find(safe)?.let { t ->
            if (t.commands == listOf(safe) && t.prefix == null) registry.forget(t.name)
            else registry.record(t.copy(commands = t.commands - safe))
        }
        ManageResult(ok, if (ok) "已删掉入口 $safe（主体还在）。" else "删不掉 $safe。")
    }

    /** 清下载残留和指向空气的软链。 */
    suspend fun gc(): ManageResult = withContext(Dispatchers.IO) {
        // 有安装在跑就一步都不能扫：`dl-*.part` / `stage-*` 正是它此刻在写的
        // 临时文件；而 opt 下那个「没被账本认领」的目录，很可能正是它刚刚
        // rename 过去、record 还没执行的成品（记账是最后一步）。gc 分不清
        // 「残留」和「在建」，删下去就是把 230 MB 从活人手里抽走，而且安装
        // 那边只会报一句莫名其妙的 IO 错。
        if (installing) {
            return@withContext ManageResult(
                false,
                "现在有安装正在跑，gc 分不清哪些是残留、哪些是它正在写的东西，这次不动手。" +
                    "等安装结束（toolchain_install action=poll 看状态）再清一次。",
                0L
            )
        }
        var freed = 0L
        var n = 0
        tmpDir.listFiles()?.forEach { f ->
            if (f.name.startsWith("dl-") || f.name.startsWith("stage-") ||
                f.name.endsWith(".part")
            ) {
                freed += measure(f)
                if (runCatching { f.deleteRecursively() }.getOrDefault(false)) n++
            }
        }
        binDir.listFiles()?.forEach { f ->
            val link = runCatching { android.system.Os.readlink(f.absolutePath) }.getOrNull()
            if (link != null && !File(link).exists()) {
                if (runCatching { f.delete() }.getOrDefault(false)) n++
            }
        }
        // opt 下没有被任何账本条目认领的目录 —— 装到一半崩了会留下这种。
        val claimed = registry.manifest.tools.mapNotNull { it.prefix }.toSet()
        optDir.listFiles()?.forEach { d ->
            if (d.isDirectory && d.name !in claimed) {
                freed += measure(d)
                if (runCatching { d.deleteRecursively() }.getOrDefault(false)) n++
            }
        }
        ManageResult(true, "清掉 $n 项残留，释放 ${human(freed)}。", freed)
    }

    /**
     * 按 mtime 清工具链的**编译缓存**（`cache/` 下面 —— zig 的
     * `ZIG_GLOBAL_CACHE_DIR`、go 的 `GOCACHE` 都指在这儿）。
     *
     * 为什么不并进 [gc]：gc 清的是「装到一半的残骸」，那种东西删了纯赚；
     * 编译缓存是**活数据**，删了下次重编会变慢，而且如果此刻正好有
     * `container_exec` 在跑 `zig build`，删掉它正在读的条目会让那次构建直接报错。
     * gc 的 `installing` 闸只挡工具链安装，挡不住用户 / 模型自己的构建。
     *
     * 所以这个函数**只在冷启动时调**：那一刻进程刚起来，容器任务是随
     * app 一起死的（见 ContainerTasks.sweepOrphans 的同一条推理），
     * 不可能有构建在读这些文件。加上 [maxAgeMs] 的年龄门槛，删掉的
     * 只可能是上一次运行留下、之后再没被碰过的条目。
     *
     * 只按**顶层条目**判年龄，不逐文件递归：zig / go 的缓存是内容寻址的，
     * 一个条目目录内部动一下，目录自己的 mtime 就会更新；逐文件走一遍
     * 几十万个小文件反而是冷启动路径上的一笔实打实的开销。
     *
     * 缓存被删的后果只有「下次重编慢一点」，不会产出错的结果 —— 这是
     * 缓存的定义。所以这里宁可删过头，不留一个长到几百 MB 的目录。
     */
    suspend fun gcCaches(maxAgeMs: Long = CACHE_MAX_AGE_MS): ManageResult =
        withContext(Dispatchers.IO) {
            if (installing) {
                return@withContext ManageResult(false, "有安装正在跑，这次不清缓存。", 0L)
            }
            val cutoff = System.currentTimeMillis() - maxAgeMs
            var freed = 0L
            var n = 0
            cacheDir.listFiles()?.forEach { top ->
                // 每个工具一个子目录（zig/ go/ …）。再往下一层才是缓存条目，
                // 那一层才是该按年龄挑的粒度 —— 按 top 判的话，zig 目录只要
                // 今天用过一次，里面躺了半年的条目就一个都清不掉。
                val entries = top.listFiles() ?: return@forEach
                for (e in entries) {
                    val touched = runCatching { e.lastModified() }.getOrDefault(0L)
                    // lastModified() 返回 0 = stat 失败，别当成「老得不能再老」删掉。
                    if (touched in 1 until cutoff) {
                        val sz = measure(e)
                        if (runCatching { e.deleteRecursively() }.getOrDefault(false)) {
                            freed += sz
                            n++
                        }
                    }
                }
            }
            ManageResult(true, "清掉 $n 项陈旧缓存，释放 ${human(freed)}。", freed)
        }

    fun diskReport(): Pair<List<DiskEntry>, Long> {
        val tools = registry.manifest.tools.map { t ->
            val bytes = when {
                t.prefix != null -> measure(File(optDir, t.prefix))
                else -> t.commands.sumOf { c ->
                    val f = File(binDir, c)
                    runCatching { if (f.isFile) f.length() else 0L }.getOrDefault(0L)
                }
            }
            DiskEntry(t.name, bytes, t.prefix, t.commands.size)
        }.sortedByDescending { it.bytes }
        return tools to runCatching { root.usableSpace }.getOrDefault(0L)
    }

    /** 全部清空。用户在设置里点「全部清空并重建」走这里，不碰 work/。 */
    suspend fun wipe(): ManageResult = withContext(Dispatchers.IO) {
        // 同 [gc]：这里会 deleteRecursively 掉 opt / tmp 再 clearAll 账本。
        // 和一次在跑的安装撞上的话，那次安装的 record 会落在 clearAll 之后 ——
        // 账本里留下一条记录，而它指的文件刚被删光，正是本项目最怕的那种
        // 「probe 说装着、shell 里 command not found」。
        if (installing) {
            return@withContext ManageResult(
                false,
                "有安装正在跑，现在清空会把它删成半截、账本还会对不上。" +
                    "先等它结束或者取消掉（toolchain_install action=cancel），再清。",
                0L
            )
        }
        var freed = 0L
        for (t in registry.manifest.tools) {
            for (c in t.commands) {
                val f = File(binDir, c)
                freed += runCatching { if (f.isFile) f.length() else 0L }.getOrDefault(0L)
                runCatching { f.delete() }
            }
        }
        freed += measure(optDir) + measure(cacheDir) + measure(localDir) + measure(tmpDir)
        runCatching { optDir.deleteRecursively() }
        runCatching { cacheDir.deleteRecursively() }
        runCatching { localDir.deleteRecursively() }
        runCatching { tmpDir.deleteRecursively() }
        registry.clearAll()
        ManageResult(true, "开发环境已清空，释放 ${human(freed)}。work/ 里的项目文件没有动。", freed)
    }

    // =================================================================
    //  探测
    // =================================================================

    /**
     * 一次性把「架构 / 磁盘 / PATH 上有什么」全捠回来。
     *
     * 不 fork shell 做 `command -v`：一是 30 次 fork 很贵，二是 shell 的
     * PATH 和实际执行环境未必一致（老的 check_environment 就栽在这上面），
     * 自己按同一份目录列表解析反而更准。[extraPath] 传容器的 execDir。
     */
    fun probeEnv(extraCommands: List<String>, extraPath: List<File>): EnvReport {
        val dirs = buildList {
            add(binDir); add(localDir)
            addAll(extraPath)
            (System.getenv("PATH") ?: "/system/bin:/system/xbin").split(':')
                .filter { it.isNotBlank() }.forEach { add(File(it)) }
        }.distinctBy { it.absolutePath }

        val names = (DEFAULT_PROBE_COMMANDS + extraCommands).distinct()
        val found = names.map { n ->
            val hit = dirs.firstNotNullOfOrNull { d ->
                val f = File(d, n)
                if (runCatching { f.exists() && f.canExecute() }.getOrDefault(false)) f else null
            }
            CommandLocation(
                name = n,
                path = hit?.absolutePath,
                source = when {
                    hit == null -> "缺"
                    hit.absolutePath.startsWith(binDir.absolutePath) -> "biji"
                    hit.absolutePath.startsWith(localDir.absolutePath) -> "自编"
                    else -> "系统"
                }
            )
        }
        return EnvReport(
            arch = ElfProbe.deviceArch(),
            abi = android.os.Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
            sdkInt = android.os.Build.VERSION.SDK_INT,
            rooted = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su").any { File(it).exists() },
            binDir = binDir.absolutePath,
            optDir = optDir.absolutePath,
            localDir = localDir.absolutePath,
            toolchainBytes = currentUsageBytes(),
            freeDiskBytes = runCatching { root.usableSpace }.getOrDefault(0L),
            budgetBytes = policy.budgetBytes,
            commands = found,
            installed = registry.manifest.tools.sortedByDescending { it.sizeBytes },
            policy = policy
        )
    }

    /** 检查一个具体文件 / 命令。[smoke] 会真的跑一次。 */
    fun inspect(pathOrName: String, smoke: Boolean, extraPath: List<File>): Pair<ElfInfo, String> {
        val f = resolveCommand(pathOrName, extraPath)
            ?: return ElfProbe.probe(File(pathOrName)) to
                "PATH 上没有 \"$pathOrName\"，也不是一个存在的路径。"
        val info = ElfProbe.probe(f)
        if (!smoke || info.problem != null) return info to ""
        val r = smokeTest(f, null, null)
        return info to if (r.ok) "验活通过：${r.version}" else "验活失败：${r.detail}"
    }

    fun resolveCommand(nameOrPath: String, extraPath: List<File> = emptyList()): File? {
        val n = nameOrPath.trim()
        if (n.isEmpty()) return null
        val expanded = n
            .replace("\$BIJI_BIN", binDir.absolutePath)
            .replace("\$BIJI_OPT", optDir.absolutePath)
        if (expanded.contains('/')) {
            val f = File(expanded)
            return if (f.exists()) f else null
        }
        val dirs = buildList {
            add(binDir); add(localDir); addAll(extraPath)
            (System.getenv("PATH") ?: "/system/bin:/system/xbin").split(':')
                .filter { it.isNotBlank() }.forEach { add(File(it)) }
        }
        return dirs.firstNotNullOfOrNull { d ->
            File(d, expanded).takeIf { runCatching { it.exists() }.getOrDefault(false) }
        }
    }

    /**
     * 第一次要用 shell 时的静默兜底：bin 目录空的话装一份 toybox。
     * 这件事不该是用户设置页里的一个待办事项。
     */
    suspend fun bootstrapIfEmpty(): InstallResult? = withContext(Dispatchers.IO) {
        val hasAny = runCatching { binDir.listFiles()?.any { it.canExecute() } == true }
            .getOrDefault(false)
        if (hasAny) return@withContext null
        val e = ToolchainCatalog.byId("toybox") ?: return@withContext null
        install(
            InstallRequest(
                catalogId = e.id, installedBy = "auto",
                note = "基础命令集，环境为空时自动补上"
            )
        )
    }

    // ---- 杂项 -----------------------------------------------------------

    /**
     * 占用量取账本里记的值，**不走文件系统**。装了 zig 之后 opt/ 下是两万
     * 多个条目，而这个数字在每次 probe / 每次装东西前都要算一遍 —— 为了一个
     * 显示用的数字去 walk 它，代价和收益完全不成比例。真要精确排查占用
     * 用 [diskReport]，那是「谁在占地方」专用的，调用频率低得多。
     */
    fun currentUsageBytes(): Long = registry.manifest.totalBytes

    private fun isSymlink(f: File): Boolean =
        runCatching { android.system.Os.readlink(f.absolutePath) != null }.getOrDefault(false)

    private fun isInsideInternal(f: File): Boolean {
        val internal = runCatching { appContext.filesDir.canonicalPath }
            .getOrDefault(appContext.filesDir.absolutePath)
        val c = runCatching { f.canonicalPath }.getOrDefault(f.absolutePath)
        return c.startsWith("$internal/")
    }

    private fun resolveTarget(target: String, workDir: File?): File? {
        val t = target.trim()
        if (t.isEmpty()) return null
        val expanded = t
            .replace("\$BIJI_BIN", binDir.absolutePath)
            .replace("\$BIJI_OPT", optDir.absolutePath)
            .replace("\$BIJI_ROOT", root.absolutePath)
        val candidates = buildList {
            add(File(expanded))
            if (!expanded.startsWith("/")) {
                workDir?.let { add(File(it, expanded)) }
                add(File(root, expanded))
                add(File(binDir, expanded))
                add(File(localDir, expanded))
            }
        }
        return candidates.firstOrNull { runCatching { it.isFile }.getOrDefault(false) }
    }

    private fun chmodX(f: File) {
        runCatching { android.system.Os.chmod(f.absolutePath, 0b111_101_101) }
        runCatching { f.setExecutable(true, false) }
    }

    /** 内容像可执行的就给执行位。zip 不带权限位，全靠这个。 */
    private fun maybeChmodX(f: File) {
        val head = ByteArray(4)
        val n = runCatching { f.inputStream().use { it.read(head) } }.getOrDefault(0)
        if (n >= 4 && head[0] == 0x7F.toByte() && head[1] == 'E'.code.toByte()) chmodX(f)
        else if (n >= 2 && head[0] == '#'.code.toByte() && head[1] == '!'.code.toByte()) chmodX(f)
    }

    private fun chmodTree(dir: File) {
        val stack = ArrayDeque<File>()
        stack.addLast(dir)
        var n = 0
        while (stack.isNotEmpty() && n < 60_000) {
            val d = stack.removeLast()
            val kids = d.listFiles() ?: continue
            for (f in kids) {
                n++
                if (f.isDirectory) stack.addLast(f) else maybeChmodX(f)
            }
        }
    }

    private fun measure(f: File): Long {
        if (!f.exists()) return 0L
        if (f.isFile) return f.length()
        var bytes = 0L
        var n = 0
        val stack = ArrayDeque<File>()
        stack.addLast(f)
        while (stack.isNotEmpty() && n < 100_000) {
            val d = stack.removeLast()
            val kids = d.listFiles() ?: continue
            for (k in kids) {
                n++
                if (k.isDirectory) stack.addLast(k) else bytes += k.length()
            }
        }
        return bytes
    }

    /** 命令名白名单。`+` 是后加的：`c++` / `g++` / `clang++` 都得留住，
     *  被削成 `c` 之后 wrapper 会覆盖掉别人的命令，比装不上更糟。
     *  这里仍然挡死 `/`、空格和一切 shell 元字符 —— 名字要直接拼进
     *  bin 目录的路径里。 */
    private fun sanitize(s: String) = s.trim().replace(Regex("""[^A-Za-z0-9._+-]"""), "")

    private fun shq(s: String) = "'" + s.replace("'", "'\\''") + "'"

    private fun hostOf(url: String) = runCatching { URL(url).host }.getOrDefault(url.take(40))

    companion object {
        const val MAX_DOWNLOAD_BYTES = 400L * 1024 * 1024
        const val SMOKE_TIMEOUT_MS = 6_000L

        /**
         * 编译缓存条目多久没被碰过就算陈旧。见 [gcCaches]。
         *
         * 7 天：比任何一次「连着几天在同一个项目上折腾」都长，所以正在用的
         * 缓存不会被误伤；又短到不至于让一个玩过一次 zig 的用户，永远背着
         * 几百 MB 再也不会命中的条目。
         */
        const val CACHE_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
        private const val MAX_PROBE_OUTPUT = 64 * 1024
        private const val MAX_LISTING = 400

        /**
         * 解 .tar.xz 时肯给的内存上限（KiB，org.tukaani 的单位就是 KiB）。
         *
         * 不用库默认的「不限」：LZMA2 的字典大小写在流头里，解码器**一开头就
         * 按它 new 一个等大的 byte[]**。`xz -9`（上游最常用的）是 64 MiB，
         * 65640 KiB 就够；但归档是从网上下回来的，一个 `--lzma2=dict=1536MiB`
         * 的包会让我们在手机上直接申请 1.5 GB —— 那不是「解包失败」，那是
         * 整个进程 OOM，还可能顺手打死别的线程正在做的分配。
         *
         * 给到 128 MiB：比 xz -9e 高一倍的余量，同时把「包太离谱」变成一条
         * 有具体数字的 MemoryLimitException（它是 IOException），走正常失败路径。
         * 真出了 OOM 也还有 doInstall 里那个 catch 兜着。
         */
        private const val XZ_MEMORY_LIMIT_KIB = 128 * 1024

        /** 探测默认要看的命令。一次调用问完，比模型连发十条 command -v 便宜。 */
        val DEFAULT_PROBE_COMMANDS = listOf(
            "sh", "bash", "ls", "cat", "grep", "sed", "awk", "find", "tar", "unzip", "xz",
            "curl", "wget", "git", "gix", "python", "python3", "node", "bun", "cc", "gcc",
            "clang", "zig", "go", "make", "ninja", "cmake", "rg", "fd", "jq", "uv"
        )

        @Volatile
        private var shared: ToolchainInstaller? = null

        /**
         * 进程级单例。工具执行器和设置页必须拿到**同一个**实例，否则
         * 安装进度、账本状态两边看到的是两份，UI 上会出现「AI 说装完了
         * 但设置页里没有」。用 applicationContext，不持有 Activity。
         */
        fun get(context: Context): ToolchainInstaller {
            shared?.let { return it }
            return synchronized(this) {
                shared ?: ToolchainInstaller(
                    context.applicationContext,
                    BijiBootstrap(context.applicationContext)
                ).also { shared = it }
            }
        }

        /** 已经有 BijiBootstrap 实例时用这个，避免 bin 目录被两个对象各自 mkdirs。 */
        fun get(context: Context, bootstrap: BijiBootstrap): ToolchainInstaller {
            shared?.let { return it }
            return synchronized(this) {
                shared ?: ToolchainInstaller(context.applicationContext, bootstrap)
                    .also { shared = it }
            }
        }

        fun human(bytes: Long): String = when {
            bytes >= 1L shl 30 -> String.format("%.1f GB", bytes / (1L shl 30).toDouble())
            bytes >= 1L shl 20 -> String.format("%.1f MB", bytes / (1L shl 20).toDouble())
            bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
