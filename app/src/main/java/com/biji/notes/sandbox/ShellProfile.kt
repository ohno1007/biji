package com.biji.notes.sandbox

import java.io.File

/**
 * 「用哪个 shell、带什么环境、第一次进来看到什么」——终端会话的启动侧配置。
 *
 * 和 [ContainerLayout.applyEnv] 是**同一份目录布局的两种口径**，故意分开写：
 *  - `applyEnv` 服务的是 AI 的一问一答（`TERM=dumb`，输出给模型读，越干净越好）；
 *  - 这里服务的是人眼终端（真 PTY + 模拟器，`TERM=xterm-256color`，要颜色）。
 * 除了 TERM 和 rc 这两处，两边的 PATH / HOME / BIJI_* 必须逐字一致 —— 「AI 装完
 * jq，用户在终端里 `which jq` 立刻看得见同一个 $BIJI_BIN/jq」是这套东西存在的
 * 意义，两边环境一旦漂移就全废了。
 *
 * ## TERM 和 TERMINFO 是两件事
 * 光有 `TERM=xterm-256color` 没用：那只是个名字，ncurses 拿它去 terminfo 数据库
 * 里查能力表，而 Android 上**根本没有** `/usr/share/terminfo`，查不到就直接
 * `missing or unsuitable terminal` 退出 —— less / tmux / htop 全是开机即死。
 * 所以这里还要把 [TerminfoDb] 铺出来的目录导成 `TERMINFO`。铺不出来时不导，
 * 退回原来的行为并往 [ShellLaunch.warnings] 里塞一条，终端本身照起。
 *
 * ## rc 文件的分工（踩过的坑写在这里）
 *  - `~/.bijirc` 是**我们生成的**，每次启动无条件重写。用户改了也会被冲掉，
 *    文件头第一行就写清楚这件事。
 *  - `~/.bashrc` 只在**不存在时**创建一次，之后永远不碰。用户的个性化放这儿，
 *    `.bijirc` 末尾负责 source 它。
 *  - bash 用 `--rcfile` 引导。**不能用 `-l`**：login shell 会去读 `/etc/profile`
 *    和 `~/.bash_profile` 然后**完全忽略 `--rcfile`**，rc 就白写了。
 *  - mksh（Android 的 `/system/bin/sh`）不认 `--rcfile`，它读环境变量 `ENV`
 *    指向的文件，所以 [environment] 里两条路都铺上。
 */
class ShellProfile(private val layout: ContainerLayout) {

    /** 会话的 HOME。和 AI 容器同一个根，两边 `cd ~` 落在同一处。 */
    val home: File get() = layout.root

    /** 生成的启动脚本；bash 走 `--rcfile`，sh 走 `$ENV`。 */
    val rcFile: File get() = File(layout.root, ".bijirc")

    /** 用户自己的定制。我们只在缺失时创建一次，之后不再写。 */
    val userRcFile: File get() = File(layout.root, ".bashrc")

    /** 存在即不打 MOTD。抄 Termux 的 `~/.hushlogin`，老用户的肌肉记忆。 */
    val hushLoginFile: File get() = File(layout.root, ".hushlogin")

    // -----------------------------------------------------------------
    // 一次性算出「怎么起」
    // -----------------------------------------------------------------

    /**
     * 把 shell 选择、rc 落盘、环境构造合成一步，[TerminalSession] 只调这个。
     *
     * @param preferSystemShell 强制退到 `/system/bin/sh`。用在两个地方：装出来的
     *        bash 是坏的（架构不对 / 下了半个文件）导致 exec 失败之后的自动重试，
     *        以及用户主动开的 failsafe 会话。
     */
    fun launch(
        preferSystemShell: Boolean = false,
        extraEnv: Map<String, String> = emptyMap()
    ): ShellLaunch {
        layout.mkdirs()
        val pick = resolveShell(preferSystemShell)
        // 一次会话只问一次：ensure() 内部有进程级缓存，但 rc 和 env 两处都要用
        // 同一个结果，分别问会在「第一次铺设正好失败」时给出自相矛盾的两份配置。
        val terminfo = TerminfoDb.ensure()
        val rc = ensureRcFiles(terminfo)
        val env = environment(pick, rc, extraEnv, terminfo)
        // cwd 用 work/ 而不是 root/：root 下面全是 .env / .state / .tasks 这类
        // 内务文件，一进来 ls 满屏点开头的东西，用户会以为进错地方了。
        val cwd = layout.work.takeIf { it.isDirectory || it.mkdirs() } ?: layout.root
        return ShellLaunch(
            executable = pick.path,
            argv = pick.argv,
            envp = env,
            cwd = cwd.absolutePath,
            shellKind = pick.kind,
            rcPath = rc.rcPath,
            warnings = pick.warnings + rc.warnings + terminfo.warnings
        )
    }

    // -----------------------------------------------------------------
    // shell 选择
    // -----------------------------------------------------------------

    /**
     * 优先 `$BIJI_BIN/bash`（有 readline，方向键 / Tab 补全 / 历史全靠它），
     * 退到 `$BIJI_EXEC/bash`，最后 `/system/bin/sh`（Android 上是 mksh，任何
     * 设备都有，是真正的兜底）。
     *
     * 故意**不用** toybox 的 `sh` applet：它的交互能力比 mksh 还弱，宁可用系统的。
     */
    fun resolveShell(preferSystemShell: Boolean = false): ShellPick {
        val warnings = mutableListOf<String>()
        if (!preferSystemShell) {
            for (dir in listOf(layout.binDir, layout.execDir)) {
                val f = File(dir, "bash")
                if (!f.isFile) continue
                if (!f.canExecute()) {
                    warnings += "${f.absolutePath} 没有执行权限，跳过"
                    continue
                }
                if (!looksRunnable(f)) {
                    warnings += "${f.absolutePath} 不像可执行文件（不是 ELF 也不是脚本），跳过"
                    continue
                }
                return ShellPick(
                    path = f.absolutePath,
                    // -i 显式写上：子进程的 stdin 已经是 pts，bash 本来就会进交互
                    // 模式，但 --rcfile 只在**交互且非 login** 时才被读取，写死更稳。
                    argv = listOf("bash", "--rcfile", rcFile.absolutePath, "-i"),
                    kind = ShellKind.BASH,
                    warnings = warnings.toList()
                )
            }
        }
        val sys = SYSTEM_SHELLS.firstOrNull { File(it).canExecute() }
        if (sys == null) {
            // 到这一步基本等于系统坏了，但还是给一个不会 NPE 的结果，
            // 让失败发生在 execve 并被子进程写到终端上，用户看得见原因。
            warnings += "找不到任何系统 shell（/system/bin/sh 都没有），启动大概率会失败"
            return ShellPick("/system/bin/sh", listOf("sh"), ShellKind.SYSTEM_SH, warnings.toList())
        }
        return ShellPick(sys, listOf(sys.substringAfterLast('/')), ShellKind.SYSTEM_SH, warnings.toList())
    }

    /**
     * 只看前两个字节：ELF 魔数或 `#!`。
     *
     * 挡不住「架构不对的 ELF」（arm64 的包装到 armv7 机器上），那种只能等
     * `execve` 报 ENOEXEC —— 子进程会把失败原因写到 pts 上，会话层看到退出码
     * 127 会自动退回系统 shell。这里挡的是更常见的「下了半个文件 / 下到一个
     * HTML 错误页」。
     */
    private fun looksRunnable(f: File): Boolean = runCatching {
        f.inputStream().use { input ->
            val head = ByteArray(4)
            val n = input.read(head)
            if (n < 2) return@runCatching false
            val elf = n >= 4 && head[0] == 0x7F.toByte() &&
                head[1] == 'E'.code.toByte() && head[2] == 'L'.code.toByte() &&
                head[3] == 'F'.code.toByte()
            val script = head[0] == '#'.code.toByte() && head[1] == '!'.code.toByte()
            elf || script
        }
    }.getOrDefault(false)

    // -----------------------------------------------------------------
    // 环境变量
    // -----------------------------------------------------------------

    /**
     * **完整**环境 —— pty 那条路不继承 JVM 的 environ，PATH / HOME / TERM 一个
     * 都不写就真的一个都没有。
     *
     * 顺序上后写的覆盖先写的：容器持久化的 `.env` 能覆盖我们的默认值（用户/AI
     * 存进去的就该生效），但 [extraEnv] 排在最后，会话层要钉死某个值时说了算。
     */
    fun environment(
        pick: ShellPick,
        rc: RcFiles,
        extraEnv: Map<String, String> = emptyMap(),
        terminfo: TerminfoDb.Terminfo = TerminfoDb.ensure()
    ): List<String> {
        val systemPath = System.getenv("PATH") ?: "/system/bin:/system/xbin"
        val env = LinkedHashMap<String, String>()
        env["PATH"] = listOf(
            layout.binDir.absolutePath, layout.execDir.absolutePath, systemPath
        ).joinToString(":")
        env["HOME"] = layout.root.absolutePath
        env["SHELL"] = pick.path
        env["PWD"] = layout.work.absolutePath
        env["TMPDIR"] = layout.tmp.absolutePath
        env["TMP"] = layout.tmp.absolutePath
        env["LANG"] = "C.UTF-8"
        env["LC_ALL"] = "C.UTF-8"
        // 和 AiContainer 的 TERM=dumb 是**有意**不同的：那边输出给模型读，这边有
        // 真模拟器在渲染 SGR/CUP。写 256color 之前请确认解析器确实吃得下这些序列，
        // 否则满屏 ESC 垃圾比没颜色更糟。
        env["TERM"] = "xterm-256color"
        env["COLORTERM"] = "truecolor"
        // 铺失败就一个都不设：设一个指向空目录的 TERMINFO 比不设更糟 ——
        // ncurses 找不到就往下一个数据库走，而设了空目录只是多走一次 stat。
        // 真正的区别在诊断上：环境里有 TERMINFO 会让人以为库是好的。
        terminfo.dir?.let {
            env["TERMINFO"] = it.absolutePath
            // 给「读 TERMINFO_DIRS 但不读 TERMINFO」的非 ncurses 实现留的后路。
            // 不带尾冒号：ncurses 里空元素才代表编译期默认目录，而我们并不想
            // 屏蔽它 —— 用户以后自己装一份完整 terminfo 树照样能被找到。
            env["TERMINFO_DIRS"] = it.absolutePath
        }
        env["BIJI_CONTAINER"] = layout.root.absolutePath
        env["BIJI_WORK"] = layout.work.absolutePath
        env["BIJI_TMP"] = layout.tmp.absolutePath
        env["BIJI_BIN"] = layout.binDir.absolutePath
        env["BIJI_EXEC"] = layout.execDir.absolutePath
        // 给 rc 和用户脚本一个「我在人眼终端里」的判据 —— AI 那条路没有这个变量。
        env["BIJI_TERMINAL"] = "1"

        for ((k, v) in layout.readEnv()) {
            if (ContainerSession.VALID_KEY.matches(k)) env[k] = v
        }
        // mksh / dash 系读 $ENV 引导交互式 rc；bash 走 --rcfile，多这一条无害
        // （bash 只在非交互 POSIX 模式下才理会 ENV）。
        if (rc.rcPath != null && pick.kind != ShellKind.BASH) env["ENV"] = rc.rcPath
        for ((k, v) in extraEnv) {
            if (ContainerSession.VALID_KEY.matches(k)) env[k] = v
        }
        return env.map { (k, v) -> "$k=$v" }
    }

    // -----------------------------------------------------------------
    // rc 文件
    // -----------------------------------------------------------------

    /**
     * 落盘 `~/.bijirc`（每次重写）和 `~/.bashrc`（仅首次创建）。
     *
     * 写失败不是致命错误：没有 rc 的 shell 照样能用，只是没颜色提示符和别名。
     * 所以失败塞进 [RcFiles.warnings] 让会话层打到终端上，而不是抛出去。
     */
    fun ensureRcFiles(terminfo: TerminfoDb.Terminfo = TerminfoDb.ensure()): RcFiles {
        val warnings = mutableListOf<String>()
        val ok = runCatching {
            layout.root.mkdirs()
            rcFile.writeText(renderRc(terminfo), Charsets.UTF_8)
            true
        }.getOrElse {
            warnings += "写不了 ${rcFile.absolutePath}：${it.message ?: it.javaClass.simpleName}"
            false
        }
        if (!userRcFile.exists()) {
            runCatching { userRcFile.writeText(USER_RC_TEMPLATE, Charsets.UTF_8) }
        }
        return RcFiles(
            rcPath = if (ok) rcFile.absolutePath else null,
            userRcPath = userRcFile.absolutePath.takeIf { userRcFile.isFile },
            warnings = warnings.toList()
        )
    }

    private fun renderRc(terminfo: TerminfoDb.Terminfo): String = buildString {
        appendLine("# 这个文件由 biji 每次启动终端时**自动重写**，改了会丢。")
        appendLine("# 自己的定制写到 ${userRcFile.absolutePath}，本文件末尾会 source 它。")
        appendLine()
        appendLine("export PATH=${sq(pathValue())}")
        appendLine("export HOME=${sq(layout.root.absolutePath)}")
        appendLine("export TMPDIR=${sq(layout.tmp.absolutePath)}")
        appendLine("export LANG=C.UTF-8")
        appendLine("export LC_ALL=C.UTF-8")
        appendLine("export TERM=xterm-256color")
        // rc 里也写一遍：用户在会话里手动 `su`、`env -i bash`、或者跑一个自己
        // 清空过环境的脚本时，envp 那份就没了，rc 是第二道保险。
        terminfo.dir?.let {
            appendLine("export TERMINFO=${sq(it.absolutePath)}")
            appendLine("export TERMINFO_DIRS=${sq(it.absolutePath)}")
        }
        appendLine("export BIJI_CONTAINER=${sq(layout.root.absolutePath)}")
        appendLine("export BIJI_WORK=${sq(layout.work.absolutePath)}")
        appendLine("export BIJI_TMP=${sq(layout.tmp.absolutePath)}")
        appendLine("export BIJI_BIN=${sq(layout.binDir.absolutePath)}")
        appendLine("export BIJI_EXEC=${sq(layout.execDir.absolutePath)}")
        appendLine()
        appendLine("alias ll='ls -l'")
        appendLine("alias la='ls -la'")
        appendLine("# 自己编出来的二进制要跑，先拷到 \$BIJI_EXEC（work/ 所在的卷是 noexec）")
        appendLine("alias runx='f() { cp \"\$1\" \"\$BIJI_EXEC/\" && \"\$BIJI_EXEC/\$(basename \"\$1\")\"; }; f'")
        appendLine()
        appendLine("if [ -n \"\$BASH_VERSION\" ]; then")
        // \[ \] 只有 bash 认，作用是告诉 readline「这段不占宽度」；不加的话
        // 一行敲长了光标位置会算错，退格会吃掉提示符。mksh 没有等价物，所以
        // 下面那条分支干脆不上色。
        appendLine("  PS1='\\[\\033[1;32m\\]\\w\\[\\033[0m\\] \\$ '")
        appendLine("  HISTFILE=\"\$HOME/.bash_history\"; HISTSIZE=2000; HISTFILESIZE=5000")
        appendLine("  shopt -s checkwinsize 2>/dev/null")
        // `..` 这种带点的别名 bash 收，mksh 会报错，所以只在 bash 分支里定义。
        appendLine("  alias ..='cd ..'")
        appendLine("  [ -f ${sq(userRcFile.absolutePath)} ] && . ${sq(userRcFile.absolutePath)}")
        appendLine("else")
        appendLine("  PS1='\$PWD \$ '")
        appendLine("fi")
    }

    private fun pathValue(): String {
        val systemPath = System.getenv("PATH") ?: "/system/bin:/system/xbin"
        return listOf(layout.binDir.absolutePath, layout.execDir.absolutePath, systemPath)
            .joinToString(":")
    }

    /** 单引号包起来，内部的 `'` 按 shell 惯例拆成 `'\''`。路径里有空格也不会炸。 */
    private fun sq(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    // -----------------------------------------------------------------
    // MOTD
    // -----------------------------------------------------------------

    /**
     * 首屏一行：shell 是哪个、装了多少命令。
     *
     * 原来还有两行讲「工具在哪」「缺东西找谁装」——那些是第一次才有用的信息，
     * 之后每开一个会话都要再看一遍，纯噪音。返回 null 表示用户建了
     * `~/.hushlogin`，一个字都别打。
     */
    fun motd(shellPath: String? = null): String? {
        if (hushLoginFile.exists()) return null
        val shellName = (shellPath ?: resolveShell().path).substringAfterLast('/')
        val tools = installedTools()
        val toolLine = when {
            tools.extras.isNotEmpty() && tools.applets > 0 ->
                "toybox ${tools.applets} 个命令 + ${tools.extras.joinToString(" ")}"
            tools.applets > 0 -> "toybox ${tools.applets} 个命令"
            tools.extras.isNotEmpty() -> tools.extras.joinToString(" ")
            else -> "还没装任何工具"
        }
        return "biji · $shellName · $toolLine\n"
    }

    /**
     * 数 bin 目录里到底有什么。
     *
     * toybox 的 applet 是指回 toybox 本体的软链，`canonicalPath` 会归一到同一个
     * 文件——据此把「多功能二进制展开出来的一堆名字」和「真正另外装的包」分开，
     * 否则 MOTD 会变成两百个命令名的清单。
     */
    private fun installedTools(): Tools = runCatching {
        val files = layout.binDir.listFiles()?.filter { it.isFile && it.canExecute() } ?: emptyList()
        val multicall = File(layout.binDir, "toybox").takeIf { it.isFile }
            ?.let { runCatching { it.canonicalPath }.getOrNull() }
        var applets = 0
        val extras = mutableListOf<String>()
        for (f in files) {
            val canon = runCatching { f.canonicalPath }.getOrNull()
            if (multicall != null && canon == multicall) {
                applets++
            } else {
                extras += f.name
            }
        }
        Tools(applets, extras.sorted().take(MOTD_MAX_TOOLS))
    }.getOrDefault(Tools(0, emptyList()))

    private data class Tools(val applets: Int, val extras: List<String>)

    companion object {
        /** mksh 是 Android 上 `/system/bin/sh` 的真身，任何设备都有。 */
        private val SYSTEM_SHELLS = listOf("/system/bin/sh", "/system/bin/mksh")

        private const val MOTD_MAX_TOOLS = 12

        private val USER_RC_TEMPLATE = """
            # 你的终端定制写在这里，biji 只在这个文件不存在时创建一次，之后永远不动它。
            # ~/.bijirc 里是自动生成的 PATH / PS1 / 别名，那个文件每次启动都会被重写。
        """.trimIndent() + "\n"
    }
}

/** 挑中的 shell。[argv] 含 argv[0]，可以直接喂给 `execve`。 */
data class ShellPick(
    val path: String,
    val argv: List<String>,
    val kind: ShellKind,
    val warnings: List<String> = emptyList()
)

enum class ShellKind { BASH, SYSTEM_SH }

/** rc 落盘结果。[rcPath] 为 null 表示没写成，shell 照样能起，只是没配置。 */
data class RcFiles(
    val rcPath: String?,
    val userRcPath: String?,
    val warnings: List<String> = emptyList()
)

/** [ShellProfile.launch] 的产物，[TerminalSession] 直接照着 fork/exec。 */
data class ShellLaunch(
    val executable: String,
    val argv: List<String>,
    val envp: List<String>,
    val cwd: String,
    val shellKind: ShellKind,
    val rcPath: String?,
    val warnings: List<String>
)
