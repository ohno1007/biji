package com.biji.notes.sandbox

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/** Termux 上游 bootstrap —— 把 termux-packages/releases 里的
 *  bootstrap-aarch64.zip 抓到 app-private，结构跟 Termux 一致：
 *      <root>/usr/{bin, lib, etc, var, share, tmp}
 *      <root>/home
 *  Termux 的 zip 不能保存符号链接，所以根目录有个 SYMLINKS.txt
 *  记录 target←source，要在解压完后手动建。 */
class TermuxBootstrap(private val context: Context) {

    val rootDir: File get() = File(context.filesDir, "termux").also { it.mkdirs() }
    val usrDir: File get() = File(rootDir, "usr").also { it.mkdirs() }
    val binDir: File get() = File(usrDir, "bin")
    val libDir: File get() = File(usrDir, "lib")
    val etcDir: File get() = File(usrDir, "etc")
    val tmpDir: File get() = File(usrDir, "tmp").also { it.mkdirs() }
    val homeDir: File get() = File(rootDir, "home").also { it.mkdirs() }

    /** 装上以后这俩都有：bash + applets 索引 SYMLINKS 处理过。 */
    val installed: Boolean
        get() = File(binDir, "bash").exists() || File(binDir, "sh").exists()

    sealed interface Progress {
        data object Idle : Progress
        data class Downloading(val bytes: Long, val total: Long) : Progress
        data class Extracting(val current: Int, val total: Int) : Progress
        data class Linking(val current: Int, val total: Int) : Progress
        data class Done(val sizeBytes: Long) : Progress
        data class Failed(val message: String) : Progress
    }

    private val _progress = MutableStateFlow<Progress>(Progress.Idle)
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    fun defaultUrlForAbi(): String {
        val abi = (android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a").lowercase()
        val arch = when {
            abi.startsWith("arm64") || abi.contains("aarch64") -> "aarch64"
            abi.startsWith("armeabi") || abi.contains("armv7") -> "arm"
            abi.contains("x86_64") -> "x86_64"
            abi.startsWith("x86") -> "i686"
            else -> "aarch64"
        }
        // Termux 把 "latest" 软链到最新 release。
        return "https://github.com/termux/termux-packages/releases/latest/download/bootstrap-$arch.zip"
    }

    suspend fun install(url: String? = null): Boolean = withContext(Dispatchers.IO) {
        try {
            _progress.value = Progress.Downloading(0L, 0L)
            // 之前装坏过会留下 0644 权限的目录（比如 lib/apt/，给错了
            // 没 x 位），deleteRecursively 进不去就静默跳过，新文件
            // 落到同名目录里直接 EACCES。先把树全开 0755 才能干净
            // 删掉。
            openUpPerms(rootDir)
            rootDir.deleteRecursively()
            usrDir.mkdirs()
            binDir.mkdirs()
            libDir.mkdirs()
            etcDir.mkdirs()
            tmpDir.mkdirs()
            homeDir.mkdirs()

            val effectiveUrl = url ?: defaultUrlForAbi()
            val zipFile = File(rootDir, "bootstrap.zip")
            if (!download(effectiveUrl, zipFile)) return@withContext false

            // 先数条目
            val entryNames = mutableListOf<String>()
            ZipInputStream(zipFile.inputStream()).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    entryNames += e.name
                    zin.closeEntry()
                }
            }
            val total = entryNames.size

            // 真解压
            var idx = 0
            ZipInputStream(zipFile.inputStream()).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    idx++
                    _progress.value = Progress.Extracting(idx, total)
                    if (e.name == "SYMLINKS.txt") {
                        // 单独读到 etc 下，下面用
                        File(usrDir, "etc/SYMLINKS.txt").also { it.parentFile?.mkdirs() }
                            .outputStream().use { out -> zin.copyTo(out) }
                        zin.closeEntry()
                        continue
                    }
                    val target = File(usrDir, e.name)
                    if (e.isDirectory) {
                        target.mkdirs()
                        // 提前 chmod 0755 —— 默认 umask 可能给 0700。
                        // 等装完最后一并 chmod 太晚，extractor 这一
                        // 路上 open 会因为父目录没 x 位 EACCES。
                        runCatching { android.system.Os.chmod(target.absolutePath, 0b111_101_101) }
                    } else {
                        target.parentFile?.also { p ->
                            p.mkdirs()
                            runCatching { android.system.Os.chmod(p.absolutePath, 0b111_101_101) }
                        }
                        target.outputStream().use { os ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = zin.read(buf)
                                if (n <= 0) break
                                os.write(buf, 0, n)
                            }
                        }
                    }
                    zin.closeEntry()
                }
            }
            zipFile.delete()

            // SYMLINKS.txt 每行 "target←source"（符号链接的目标
            // 和源），用 Os.symlink 重建。
            val symlinksFile = File(usrDir, "etc/SYMLINKS.txt")
            if (symlinksFile.exists()) {
                val lines = symlinksFile.readLines()
                lines.forEachIndexed { i, raw ->
                    val parts = raw.split("←")
                    if (parts.size != 2) return@forEachIndexed
                    val (targetName, linkName) = parts
                    val link = File(usrDir, linkName)
                    _progress.value = Progress.Linking(i + 1, lines.size)
                    link.parentFile?.mkdirs()
                    if (link.exists()) link.delete()
                    runCatching { android.system.Os.symlink(targetName, link.absolutePath) }
                }
            }

            // 把所有 bin/* 标可执行
            // Os.chmod 直接走 chmod() 系统调用，比 File.setExecutable
            // 可靠。规则：目录一律 0755（必须有 x 位才能 cd 进），
            // bin/ 下文件 0755，lib/ 下二级文件 0755（.so 共享库），
            // 其它文件 0644。
            usrDir.walkTopDown().forEach { f ->
                if (!f.exists()) return@forEach
                val mode = when {
                    f.isDirectory -> 0b111_101_101                                // 0755
                    f.parentFile == binDir -> 0b111_101_101                       // 0755
                    f.parentFile?.parentFile == libDir -> 0b111_101_101           // 0755 (.so 等)
                    else -> 0b110_100_100                                         // 0644
                }
                runCatching { android.system.Os.chmod(f.absolutePath, mode) }
            }
            // home / 整个 rootDir 也得 0755 —— proot bind 时 guest
            // 的 /data/data/com.termux/files 要能 cd 进去。
            listOf(rootDir, homeDir, usrDir, tmpDir, etcDir, libDir, binDir).forEach {
                if (it.exists())
                    runCatching { android.system.Os.chmod(it.absolutePath, 0b111_101_101) }
            }

            val totalBytes = rootDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            _progress.value = Progress.Done(totalBytes)
            true
        } catch (e: Exception) {
            _progress.value = Progress.Failed(e.message ?: e.javaClass.simpleName)
            false
        }
    }

    suspend fun uninstall() = withContext(Dispatchers.IO) {
        // 卸载前先把可能存在的 /data/data/com.termux 软链清掉，
        // 否则那个 dangling symlink 留着碍事。
        runCatching {
            ProcessBuilder("su", "-c", "rm -f /data/data/com.termux/files/usr /data/data/com.termux/files/home")
                .redirectErrorStream(true).start().waitFor(4_000, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
        openUpPerms(rootDir)
        rootDir.deleteRecursively()
        rootDir.mkdirs()
        _progress.value = Progress.Idle
    }

    /** 递归把整棵树的权限放宽到能读写遍历，方便 deleteRecursively
     *  之后真的能进每个子目录。先 chmod 当前节点，再 list 子项 —
     *  否则 listFiles 在没 x 位的目录上直接返回 null，子文件抓不到。 */
    private fun openUpPerms(root: File) {
        if (!root.exists()) return
        if (root.isDirectory) {
            runCatching { android.system.Os.chmod(root.absolutePath, 0b111_101_101) }
            root.listFiles()?.forEach { openUpPerms(it) }
        } else {
            runCatching { android.system.Os.chmod(root.absolutePath, 0b110_100_100) }
        }
    }

    /** Termux 的 apt / dpkg / gcc 把 prefix 硬编码到
     *  /data/data/com.termux/files/usr —— 任何打开配置 / 共享
     *  库 / 头文件的 open() 都直奔那个固定路径，LD_PRELOAD 拦不
     *  到。唯一不用 proot 的解决方法就是用 root 把
     *  /data/data/com.termux 的 usr / home 软链回我们的目录。 */
    suspend fun linkAsTermuxPrefix(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (!installed) return@withContext false to "终端环境未安装"
        // set -e 保证任何子命令失败都让脚本非零退出；exit-code 单独
        // 判断比解 stdout 可靠 —— Android 上 /data/data 和 /data/user/0
        // 是软链关系，readlink 输出怎么形式不一定。
        val cmd = buildString {
            append("set -e; ")
            append("mkdir -p /data/data/com.termux/files; ")
            append("ln -sfn ${usrDir.absolutePath} /data/data/com.termux/files/usr; ")
            append("ln -sfn ${homeDir.absolutePath} /data/data/com.termux/files/home; ")
            append("[ -e /data/data/com.termux/files/usr/bin ]; ")
            append("echo BIJI_LINK_OK; ")
        }
        val p = try {
            ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        } catch (e: Exception) {
            return@withContext false to "无法启动 su: ${e.message ?: e.javaClass.simpleName}"
        }
        val finished = p.waitFor(15_000, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!finished) { p.destroyForcibly(); return@withContext false to "su 超时（10s 没响应）" }
        val out = p.inputStream.bufferedReader().use { it.readText() }
        val ok = p.exitValue() == 0
        val info = when {
            out.isNotBlank() -> out
            ok -> "exit=0"
            else -> "exit=${p.exitValue()}（可能被超级用户管理器拒绝）"
        }
        if (ok) {
            runCatching { linkMarker.writeText(usrDir.absolutePath) }
        }
        ok to info
    }

    suspend fun unlinkTermuxPrefix(): Boolean = withContext(Dispatchers.IO) {
        val cmd = "rm -f /data/data/com.termux/files/usr /data/data/com.termux/files/home; echo BIJI_UNLINK_OK"
        val ok = runCatching {
            val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
            p.waitFor(8_000, java.util.concurrent.TimeUnit.MILLISECONDS) && p.exitValue() == 0
        }.getOrDefault(false)
        if (ok) linkMarker.delete()
        ok
    }

    /** 软链是否已建好。biji 进程不在 com.termux 沙箱里，
     *  /data/data/com.termux 整个对我们是不可读的（连 stat 都拒），
     *  所以不能用 File.exists/canonicalPath 判断 —— 那俩永远 false。
     *  改记一个我们自己的标记文件，linkAsTermuxPrefix 成功时落地。 */
    private val linkMarker: File get() = File(rootDir, ".link-ok")

    fun linkedAsTermuxPrefix(): Boolean = installed && linkMarker.exists()

    /** 跑一条 shell 命令，环境按 [envFor] 注入，让 apt / dpkg 跑起来。
     *  返回 (exitCode, stdout, stderr)。给「一键装编译器」UI 用。 */
    suspend fun runOnce(
        command: String,
        proot: ProotBootstrap? = null,
        timeoutMs: Long = 600_000L,
        onLine: (String, Boolean) -> Unit = { _, _ -> }
    ): Triple<Int, String, String> = kotlinx.coroutines.coroutineScope {
        withContext(Dispatchers.IO) {
            if (!installed) return@withContext Triple(-1, "", "终端环境未安装")
            val shellFile = preferredShell()
            runCatching { android.system.Os.chmod(shellFile.absolutePath, 0b111_101_101) }
            if (!shellFile.canExecute()) {
                return@withContext Triple(-1, "", "shell 不可执行: ${shellFile.absolutePath}（可能是 Android W^X 限制）")
            }
            val useProot = proot?.installed == true
            val pb = if (useProot) {
                ProcessBuilder(proot!!.wrapForProot(command))
            } else {
                ProcessBuilder(shellFile.absolutePath, "-c", command)
            }
            pb.directory(homeDir).redirectErrorStream(false)
            val env = pb.environment()
            envFor().forEach { (k, v) -> env[k] = v }
            if (useProot) {
                proot!!.envFor().forEach { (k, v) -> env[k] = v }
            } else {
                val exec = File(libDir, "libtermux-exec.so")
                if (exec.exists()) env["LD_PRELOAD"] = exec.absolutePath
            }
            val p = try {
                pb.start()
            } catch (e: java.io.IOException) {
                return@withContext Triple(
                    -1, "",
                    "无法启动 ${shellFile.name}: ${e.message ?: e.javaClass.simpleName}"
                )
            }
            val out = StringBuilder()
            val err = StringBuilder()
            val outJob = async(Dispatchers.IO) {
                p.inputStream.bufferedReader().forEachLine {
                    synchronized(out) { out.appendLine(it) }
                    onLine(it, false)
                }
            }
            val errJob = async(Dispatchers.IO) {
                p.errorStream.bufferedReader().forEachLine {
                    synchronized(err) { err.appendLine(it) }
                    onLine(it, true)
                }
            }
            val finished = p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!finished) p.destroyForcibly()
            outJob.await(); errJob.await()
            Triple(if (finished) p.exitValue() else -1, out.toString(), err.toString())
        }
    }

    private suspend fun download(url: String, dst: File): Boolean = withContext(Dispatchers.IO) {
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

    /** 给 ProcessBuilder 用的完整环境变量。 */
    fun envFor(): Map<String, String> {
        val basePath = "/system/bin:/system/xbin"
        val termuxPath = "${binDir.absolutePath}:$basePath"
        val prefix = usrDir.absolutePath
        return mapOf(
            "PREFIX" to prefix,
            "TERMUX_PREFIX" to prefix,
            "HOME" to homeDir.absolutePath,
            "PATH" to termuxPath,
            "LD_LIBRARY_PATH" to libDir.absolutePath,
            "TMPDIR" to tmpDir.absolutePath,
            "TERM" to "xterm-256color",
            "LANG" to "C.UTF-8",
            "ANDROID_DATA" to "/data",
            "ANDROID_ROOT" to "/system",
            "TERMUX_VERSION" to "biji",
            "BASH_ENV" to File(usrDir, "etc/bash.bashrc").absolutePath
        )
    }

    /** 推荐用的 shell 路径 —— 装了 bash 用 bash，没装用 sh。 */
    fun preferredShell(): File {
        val bash = File(binDir, "bash")
        val sh = File(binDir, "sh")
        return when {
            bash.exists() && bash.canExecute() -> bash
            sh.exists() && sh.canExecute() -> sh
            else -> File("/system/bin/sh")
        }
    }
}
