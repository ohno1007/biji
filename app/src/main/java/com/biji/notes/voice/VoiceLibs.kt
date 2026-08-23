package com.biji.notes.voice

import android.content.Context
import com.sun.jna.NativeLibrary
import org.vosk.LibVosk
import org.vosk.LogLevel
import java.io.DataInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import java.util.zip.ZipInputStream

/**
 * libvosk.so 的运行时安装器。
 *
 * 这个 .so 解压后 8.86 MB，而 APK 里 .so 是 stored（extractNativeLibs=false），
 * 一字节不压 —— 它一个人就占了 14.4 MB 包体的 61.7%。而离线语音本来就是个
 * 可选功能：不下 40 MB+ 的模型就用不了，模型都要现下，.so 跟着一起下不多这一步。
 *
 * 三件事值得先说清楚，因为都踩过：
 *
 * 1. **为什么能从 filesDir dlopen。** AOSP sepolicy commit b3624743 把
 *    "从 app 私有目录执行代码" 这条禁令只加在 targetSdk >= 29 上，原文
 *    "API versions <= 28 are uneffected"。它一次同时管 execute_no_trans
 *    (execve) 和 execute (mmap PROT_EXEC，也就是 dlopen)。本项目 targetSdk
 *    钉死在 28 是为了跑 zig/curl 这些二进制，dlopen 是同一条豁免顺带保住的
 *    —— 不是两码事，改 targetSdk 的时候这里会一起塌。
 *    （另外 Android 10 文档里那句 dlopen 警告说的是带 text relocation 的 .so，
 *    libvosk.so 没有 TEXTREL，不适用。）
 *
 * 2. **Vosk 走的不是 System.loadLibrary。** org.vosk.LibVosk 的静态块是
 *    `Native.register(LibVosk.class, "vosk")`，JNA 的加载路径。JNA 的
 *    NativeLibrary.loadLibrary 第一步就查 addSearchPath() 登记的路径，
 *    所以只要在**碰 LibVosk 这个类之前**登记好目录就行，不用动
 *    jna.library.path 那个全局 system property。
 *    jnidispatch 是另一回事：JNA 自己的 .so 只有 157 KB，留在 APK 里没动。
 *
 * 3. **必须懒加载。** 原来 OfflineVoiceRecognizer 的 init{} 里有一句
 *    LibVosk.setLogLevel，构造它就等于 dlopen 8.86 MB —— 冷启动路径上白白
 *    做 1.6 万条重定位、常驻一百多 KB 脏页，不管用不用语音。现在整个 native
 *    的触碰点收敛到本文件的 load()，只有装模型和真正开录音才会走到。
 *
 * 失败一律返回错误字符串（null = 成功），不往 UI 层抛异常。
 */
object VoskNativeLib {

    /** 解压后的确定尺寸与摘要，实测自 vosk-android:0.3.47 的 AAR。 */
    private const val SIZE = 8_862_928L
    private const val SHA256 = "29ddc0282af7e44c790f2a7dfbcf7ed9e557361dc1acf609142c89b792d714e1"

    /**
     * AAR 里 jni/arm64-v8a/libvosk.so 这个条目的 local header 起止偏移。
     *
     * 只是**加速用的提示**，不是正确性依赖：拉回来之后照样解析 local file
     * header，比对条目名/方法/CRC/长度，最后再核 SHA-256。偏移漂了或者拿到
     * 的是别的东西，校验一定不过，不会写出一个坏 .so。
     * Maven Central 的已发布构件是不可变的，所以这对 0.3.47 是稳的。
     */
    private const val ENTRY_FROM = 15_809L
    private const val ENTRY_TO = 2_885_557L
    private const val ENTRY_NAME = "jni/arm64-v8a/libvosk.so"

    /**
     * 下载源。整包 12.3 MB（四个 ABI），Range 只拉 arm64 那 2.87 MB。
     *
     * 注意版本号跟 build.gradle.kts 里的 vosk-android 版本是**绑死**的：
     * Java 层从 AAR 编进 APK，native 从这里下，两边版本必须一致。升版本要
     * 同时改这里的 URL、SIZE、SHA256 和 ENTRY_FROM/TO（重新量一遍 AAR）。
     * 万一忘了，表现是 dlopen 或符号解析失败 → 走降级，不会是静默的错结果。
     */
    private val SOURCES = listOf(
        "https://repo1.maven.org/maven2/com/alphacephei/vosk-android/0.3.47/vosk-android-0.3.47.aar",
        "https://repo.maven.apache.org/maven2/com/alphacephei/vosk-android/0.3.47/vosk-android-0.3.47.aar",
        "https://maven.aliyun.com/repository/central/com/alphacephei/vosk-android/0.3.47/vosk-android-0.3.47.aar"
    )

    private const val ZIP_LOCAL_SIG = 0x04034b50
    private const val FLAG_DATA_DESCRIPTOR = 1 shl 3
    private const val METHOD_DEFLATE = 8

    fun dir(ctx: Context): File = File(ctx.filesDir, "vosk-native")

    fun libFile(ctx: Context): File = File(dir(ctx), "libvosk.so")

    /** 文件在且长度对。长度不对说明上次写了一半，当成没装。 */
    fun present(ctx: Context): Boolean =
        libFile(ctx).let { it.isFile && it.length() == SIZE }

    // 加载只允许试一次：LibVosk 的静态块炸过之后，这个类在本进程里就永久
    // 处于 erroneous 状态（再碰只会拿到 NoClassDefFoundError），重试没有意义。
    @Volatile
    private var attempted = false

    @Volatile
    private var loadError: String? = null

    /** 试过并且失败了 —— 调用方据此把自己降级掉，别再往离线引擎上撞。 */
    val broken: Boolean get() = attempted && loadError != null

    /**
     * 确保 .so 就位。已就位直接返回。返回 null 表示可用，否则是给用户看的原因。
     * 阻塞 IO，调用方自己切线程。
     *
     * @param onProgress (已解出字节, 总字节)
     */
    fun ensure(ctx: Context, onProgress: (Long, Long) -> Unit): String? {
        val target = libFile(ctx)
        if (present(ctx)) return null

        val dir = dir(ctx)
        if (!dir.isDirectory && !dir.mkdirs()) return "无法创建 ${dir.name} 目录"
        // 半截文件比没有更糟：present() 只看长度，正好写到 8862928 字节被打断
        // 就会被当成完好的。所以一律写临时文件，校验通过再 rename。
        val tmp = File(dir, "libvosk.so.part")

        var lastErr = "没有可用的下载源"
        for (url in SOURCES) {
            tmp.delete()
            val err = fetch(url, tmp, onProgress)
            if (err != null) {
                lastErr = err
                continue
            }
            target.delete()
            if (!tmp.renameTo(target)) {
                tmp.delete()
                return "写入 ${target.name} 失败"
            }
            // Android 14 起 System.load() 加载可写文件会告警，targetSdk 37 起变硬错误。
            // JNA 走的是 jnidispatch 里的裸 dlopen，绕过了 Java 层这个检查，但设只读
            // 是零成本的将来保险（SoLoader 也是这么做的）。
            target.setReadOnly()
            return null
        }
        tmp.delete()
        return lastErr
    }

    /**
     * 登记搜索路径并触发 dlopen。null = 成功。
     *
     * 顺序不能反：addSearchPath 必须在**第一次碰 LibVosk 这个类**之前，
     * 因为 dlopen 发生在它的静态块里。
     */
    @Synchronized
    fun load(ctx: Context): String? {
        if (attempted) return loadError
        attempted = true

        val f = libFile(ctx)
        if (!f.isFile) {
            loadError = "离线语音库尚未下载"
            return loadError
        }
        loadError = try {
            NativeLibrary.addSearchPath("vosk", f.parentFile!!.absolutePath)
            LibVosk.setLogLevel(LogLevel.WARNINGS)   // 这一句才真正触发 dlopen
            null
        } catch (t: Throwable) {
            // UnsatisfiedLinkError / ExceptionInInitializerError 都是 Error 不是
            // Exception，catch (e: Exception) 在这里是接不住的 —— 这是个坑。
            "${t.javaClass.simpleName}: ${t.message ?: "无详情"}"
        }
        return loadError
    }

    /** 连模型一起卸载时清掉。已经 dlopen 过的映射不会因为删文件而失效，
     *  本进程内继续能用，重启后才真的没了 —— 无所谓，那时模型也没了。 */
    fun remove(ctx: Context) {
        dir(ctx).deleteRecursively()
    }

    // ---- 下载实现 ----

    private fun fetch(url: String, tmp: File, onProgress: (Long, Long) -> Unit): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("Range", "bytes=$ENTRY_FROM-$ENTRY_TO")
                // 不设的话 HttpURLConnection 默认要 gzip 并透明解码，那字节偏移
                // 就跟 Range 对不上了。要 identity。
                setRequestProperty("Accept-Encoding", "identity")
            }
            conn.connect()
            when (val code = conn.responseCode) {
                HttpURLConnection.HTTP_PARTIAL ->
                    conn.inputStream.use { readRanged(it, tmp, onProgress) }
                // 有的镜像/中间代理不认 Range，直接回整包。慢十倍但能成，
                // 不值得为此报错。
                HttpURLConnection.HTTP_OK ->
                    conn.inputStream.use { readWholeAar(it, tmp, onProgress) }
                else -> "HTTP $code"
            }
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /** Range 命中：流的开头就是那个条目的 local file header，自描述得很干净。 */
    private fun readRanged(src: InputStream, tmp: File, onProgress: (Long, Long) -> Unit): String? {
        val din = DataInputStream(src)
        val head = ByteArray(30)
        din.readFully(head)
        if (le32(head, 0) != ZIP_LOCAL_SIG.toLong()) return "响应不是 zip 条目头"

        val flag = le16(head, 6)
        val method = le16(head, 8)
        val crcExpect = le32(head, 14)
        val csize = le32(head, 18)
        val usize = le32(head, 22)
        val nameLen = le16(head, 26)
        val extraLen = le16(head, 28)

        // bit 3 置位意味着长度写在数据后面的 data descriptor 里，头里的是 0，
        // 那就没法边下边核了。Maven 上这个 AAR 不是这么打的，真遇上就换下一个源。
        if (flag and FLAG_DATA_DESCRIPTOR != 0) return "条目使用 data descriptor"
        if (method != METHOD_DEFLATE) return "条目压缩方式 $method 非 deflate"
        if (usize != SIZE) return "条目长度 $usize 与预期不符"

        val nameBytes = ByteArray(nameLen)
        din.readFully(nameBytes)
        val name = String(nameBytes, Charsets.UTF_8)
        if (name != ENTRY_NAME) return "偏移落在 $name 上，不是目标条目"
        if (extraLen > 0) din.readFully(ByteArray(extraLen))

        // 截断到 csize：Range 的右端点是照旧版偏移写死的，多给几个字节
        // 就会喂到下一个条目头上去。显式截断才有确定行为。
        val capped = Capped(src, csize)
        val inflater = Inflater(true)   // raw deflate，zip 条目里没有 zlib 包头
        return try {
            InflaterInputStream(capped, inflater, 64 * 1024).use { zin ->
                verifyingCopy(zin, tmp, crcExpect, onProgress)
            }
        } finally {
            inflater.end()
        }
    }

    /** 服务端无视 Range：整包 12.3 MB 扫过去挑条目。ZipInputStream 自己核 CRC。 */
    private fun readWholeAar(src: InputStream, tmp: File, onProgress: (Long, Long) -> Unit): String? =
        ZipInputStream(src).use { zin ->
            var err: String? = "整包里没有 $ENTRY_NAME"
            while (true) {
                val e = zin.nextEntry ?: break
                if (e.name != ENTRY_NAME) {
                    zin.closeEntry()
                    continue
                }
                // 读到 -1 时 ZipInputStream 自己会核 CRC，不匹配直接抛 ZipException。
                err = verifyingCopy(zin, tmp, crcExpect = -1L, onProgress = onProgress)
                break
            }
            err
        }

    /**
     * 边写边算 CRC32 + SHA-256。crcExpect < 0 表示上游（ZipInputStream）已经
     * 自己核过 CRC 了，这里只核长度和摘要。
     */
    private fun verifyingCopy(
        src: InputStream,
        tmp: File,
        crcExpect: Long,
        onProgress: (Long, Long) -> Unit
    ): String? {
        val crc = CRC32()
        val sha = MessageDigest.getInstance("SHA-256")
        var written = 0L
        tmp.outputStream().use { out: OutputStream ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = src.read(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
                crc.update(buf, 0, n)
                sha.update(buf, 0, n)
                written += n
                if (written > SIZE) return "解出的数据超长"
                onProgress(written, SIZE)
            }
        }
        if (written != SIZE) return "解出 $written 字节，预期 $SIZE"
        if (crcExpect >= 0 && crc.value != crcExpect) return "CRC 校验失败"
        val got = sha.digest().joinToString("") { "%02x".format(it) }
        if (got != SHA256) return "SHA-256 校验失败"
        return null
    }

    private fun le16(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8)

    private fun le32(b: ByteArray, i: Int): Long =
        (b[i].toLong() and 0xff) or
            ((b[i + 1].toLong() and 0xff) shl 8) or
            ((b[i + 2].toLong() and 0xff) shl 16) or
            ((b[i + 3].toLong() and 0xff) shl 24)

    /** 只放行前 [limit] 字节。 */
    private class Capped(src: InputStream, private val limit: Long) : FilterInputStream(src) {
        private var seen = 0L

        override fun read(): Int {
            if (seen >= limit) return -1
            val b = `in`.read()
            if (b >= 0) seen++
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (seen >= limit) return -1
            val room = minOf(len.toLong(), limit - seen).toInt()
            val n = `in`.read(b, off, room)
            if (n > 0) seen += n
            return n
        }

        override fun available(): Int =
            minOf(`in`.available().toLong(), (limit - seen).coerceAtLeast(0L)).toInt()
    }
}
