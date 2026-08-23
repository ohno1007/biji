package com.biji.notes.sandbox

/**
 * 容器命令闸门。
 *
 * 定位说明（很重要，别把它当安全边界）：这是**护栏**，不是沙箱。
 * 容器里的命令跑在 app 自己的 uid 下，能读的地方 shell 都能读；真正
 * 的隔离得靠 Android 的 uid 沙箱本身。这里只拦两类东西：
 *
 *  1. 明显毁灭性的命令形状（等价于 LocalSandbox.isDangerousCommandReason，
 *     自带一份实现，不去动那个文件）；
 *  2. **明显想往容器外写**的意图 —— 输出重定向、以及 rm / mv / cp / tee /
 *     dd of= / sed -i 这类写命令的目标是容器外的绝对路径。
 *
 * 只对**绝对路径**判定。相对路径一律放行：cwd 永远在容器内，而且
 * `cd /` 之后的相对路径我们也追不动 —— 追不动的就明说追不动，别假装。
 * 带 `$` 的 token 同样放行（静态展不开变量），宁可漏也不要误伤：这是个
 * 「让 AI 自由发挥」的容器，误拦比漏拦更讨厌。
 */
internal object ContainerGuard {

    /** 命令超过这个长度直接拒 —— 正常命令不会有 64 KB。 */
    const val MAX_COMMAND_CHARS = 64 * 1024

    /**
     * 返回一句人话说明为什么拒绝，没问题时返回 null。
     *
     * [writeRoots] 是允许写入的绝对路径前缀（容器根、exec 目录、bin
     * 目录、/data/local/tmp 等），由 [AiContainer] 组装后传进来。
     */
    fun check(command: String, writeRoots: List<String>): String? {
        val c = command.trim()
        if (c.isEmpty()) return "命令为空"
        if (c.length > MAX_COMMAND_CHARS) return "命令过长（${c.length} 字符，上限 $MAX_COMMAND_CHARS）"
        destructiveReason(c)?.let { return it }
        escapeReason(c, writeRoots)?.let { return it }
        return null
    }

    // ---- 1. 毁灭性形状 -------------------------------------------------

    private val RM_RF = Regex("""\brm\s+(-[a-zA-Z]*r[a-zA-Z]*f|-[a-zA-Z]*f[a-zA-Z]*r|-rf|-fr)\b""")
    private val RM_RF_TARGET =
        Regex("""\brm\s+-[rRfF]+\s+(--no-preserve-root\s+)?(/[^\s|;&]*|[$]HOME|~|\*)""")
    private val MKFS = Regex("""\bmkfs(\.[a-zA-Z0-9]+)?\b""")
    private val DD_DEV = Regex("""\bdd\b[^|;&]*\bof=/dev/""")
    private val SHRED = Regex("""\bshred\b""")
    private val CHMOD_ROOT = Regex("""\bchmod\s+[-0-7]+\s+/(?:\s|$)""")
    private val CHOWN_ROOT = Regex("""\bchown\s+[^|;&]*\s+/(?:\s|$)""")
    private val POWER = Regex("""\b(reboot|shutdown|halt|poweroff)\b""")
    private val SU = Regex("""(?:^|[\s;|&(])su\b(?!\w)""")
    private val PKG_MGR = Regex("""\bpm\s+(uninstall|clear|disable)\b""")

    private fun destructiveReason(c: String): String? {
        if (RM_RF.containsMatchIn(c) && RM_RF_TARGET.containsMatchIn(c)) return "rm -rf 落在系统根或 \$HOME 上"
        if (MKFS.containsMatchIn(c)) return "mkfs 会格式化设备"
        if (DD_DEV.containsMatchIn(c)) return "dd 直接写块设备"
        if (SHRED.containsMatchIn(c)) return "shred 不可恢复擦除"
        if (c.replace(" ", "").contains(":(){:|:&};:")) return "fork bomb"
        if (CHMOD_ROOT.containsMatchIn(c)) return "chmod 改根目录权限"
        if (CHOWN_ROOT.containsMatchIn(c)) return "chown 改根目录归属"
        if (POWER.containsMatchIn(c)) return "关机 / 重启"
        // 这条会**原样进模型的上下文**，所以不能点名 run_shell_command：
        // 它现在只在设置里开了 root 时才发给模型（Tools.definitions(useRoot)），
        // 关着的时候照这句去调，模型拿到的是一个非 root 的 shell —— 它要的是
        // root，得到的却是一次静默降级。root 开着时该怎么走，由系统提示里的
        // ROOT_SHELL_BRIEF 负责讲，那一条和工具列表是同一个开关。
        if (SU.containsMatchIn(c)) return "容器内不提供 root（su / sudo 都不可用）"
        if (PKG_MGR.containsMatchIn(c)) return "pm 卸载 / 清数据会动到设备上的其它应用"
        return null
    }

    // ---- 2. 越界写入意图 -----------------------------------------------

    /** `> path` / `>> path` / `2> path`；`>&1` 这种 fd 复制不算。 */
    private val REDIRECT = Regex("""(?:^|[\s;|&(])\d*>>?\s*("[^"]*"|'[^']*'|[^\s;|&<>()]+)""")

    /** 目标 = 全部非选项参数。 */
    private val TARGET_ALL = setOf(
        "rm", "rmdir", "unlink", "touch", "truncate", "mkdir",
        "chmod", "chown", "chgrp", "tee"
    )

    /** 目标 = 最后一个非选项参数（前面的都是源，读取而已）。 */
    private val TARGET_LAST = setOf("cp", "mv", "ln", "install", "rsync")

    /** 只有带 -i / --in-place 时才是写。 */
    private val TARGET_IF_INPLACE = setOf("sed", "perl", "ruby", "gawk")

    private val TOKEN = Regex("""'[^']*'|"[^"]*"|\S+""")
    private val SEGMENT = Regex("""[;\n]|\|\||&&|\||&""")
    private val ENV_ASSIGN = Regex("""^[A-Za-z_][A-Za-z0-9_]*=""")

    private fun escapeReason(c: String, writeRoots: List<String>): String? {
        // 2a. 输出重定向
        for (m in REDIRECT.findAll(c)) {
            val raw = m.groupValues[1]
            if (raw.startsWith("&")) continue           // >&2 / >&1
            val t = unquote(raw)
            outsideReason(t, writeRoots)?.let { return "输出重定向写到容器外：$it" }
        }
        // 2b. 写类命令的目标参数
        for (seg in c.split(SEGMENT)) {
            val tokens = TOKEN.findAll(seg).map { it.value }.toList()
            if (tokens.isEmpty()) continue
            var i = 0
            while (i < tokens.size && ENV_ASSIGN.containsMatchIn(tokens[i])) i++   // FOO=bar cmd
            if (i >= tokens.size) continue
            val cmd = unquote(tokens[i]).substringAfterLast('/')
            val rest = tokens.drop(i + 1).map { unquote(it) }
            if (cmd == "dd") {
                rest.firstOrNull { it.startsWith("of=") }?.let { of ->
                    outsideReason(of.removePrefix("of="), writeRoots)
                        ?.let { return "dd 写到容器外：$it" }
                }
                continue
            }
            val args = rest.filterNot { it.startsWith("-") }
            val targets: List<String> = when {
                cmd in TARGET_ALL -> args
                cmd in TARGET_LAST -> listOfNotNull(args.lastOrNull())
                cmd in TARGET_IF_INPLACE &&
                    rest.any { it == "-i" || it.startsWith("-i") || it == "--in-place" } -> args
                else -> emptyList()
            }
            for (t in targets) {
                outsideReason(t, writeRoots)?.let { return "$cmd 写到容器外：$it" }
            }
        }
        return null
    }

    /** 目标在容器外时返回它本身，否则 null。只看绝对路径。 */
    private fun outsideReason(rawTarget: String, writeRoots: List<String>): String? {
        val t = rawTarget.trim()
        if (t.isEmpty()) return null
        if (t.startsWith("$")) return null              // 变量，静态展不开
        if (!t.startsWith("/")) return null             // 相对路径，cwd 在容器内
        if (ALWAYS_OK.any { t == it || t.startsWith("$it/") }) return null
        val norm = normalize(t)
        if (writeRoots.any { norm == it || norm.startsWith("$it/") }) return null
        return t
    }

    /** 允许写的固定位置：丢弃流和公共临时目录。 */
    private val ALWAYS_OK = listOf(
        "/dev/null", "/dev/stdout", "/dev/stderr", "/dev/tty", "/dev/fd",
        "/proc/self/fd", "/data/local/tmp", "/tmp"
    )

    /** 纯字符串归一化 `.` / `..` —— 不碰文件系统，也就不受符号链接影响。 */
    private fun normalize(path: String): String {
        val parts = ArrayList<String>()
        for (p in path.split('/')) {
            when (p) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(p)
            }
        }
        return "/" + parts.joinToString("/")
    }

    private fun unquote(s: String): String = when {
        s.length >= 2 && s.startsWith("'") && s.endsWith("'") -> s.substring(1, s.length - 1)
        s.length >= 2 && s.startsWith("\"") && s.endsWith("\"") -> s.substring(1, s.length - 1)
        else -> s
    }
}
