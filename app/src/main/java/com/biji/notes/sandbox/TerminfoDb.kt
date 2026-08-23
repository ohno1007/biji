package com.biji.notes.sandbox

import android.content.Context
import java.io.File

/**
 * 把 APK 里编译好的 terminfo 条目铺到内部存储，供 [ShellProfile] 导出
 * `TERMINFO`。
 *
 * ## 为什么必须自带一份
 * Android 系统里**没有** `/usr/share/terminfo`，也没有任何 terminfo 数据库。
 * 而 ncurses 起手就是 `setupterm()`：查不到 `$TERM` 对应的条目就直接
 * `missing or unsuitable terminal` 退出。于是 less / tmux / htop / 带 ncurses
 * 的 vim 在我们这儿是**开机即死**，光设 `TERM=xterm-256color` 没有任何用
 * —— TERM 只是个名字，能力表在数据库里。
 * （不吃 terminfo 的程序不受影响：toybox top、neatvi、busybox vi 自己写死
 * 转义序列，所以以前的现状是「一半能跑」，不是全崩。）
 *
 * ## ncurses 的查找顺序（决定了我们只需要设 TERMINFO）
 * 1. `$TERMINFO` —— 单个目录，优先级最高；
 * 2. `$HOME/.terminfo`；
 * 3. `$TERMINFO_DIRS` —— 冒号分隔，**空元素表示编译期默认目录**；
 * 4. 编译期写死的 TERMINFO_DIRS 列表；
 * 5. 编译期写死的 TERMINFO 目录（桌面上就是 `/usr/share/terminfo`）。
 *
 * 我们设 1 和 3。设 3 是给「读 TERMINFO_DIRS 但不读 TERMINFO」的非 ncurses
 * 实现留的后路；两个都设也**不会**屏蔽掉 4/5，用户以后自己装一份完整的
 * terminfo 树照样能被找到。故意不碰 `$HOME/.terminfo`：HOME 在外部存储上，
 * 部分 ROM 的 sdcardfs 大小写不敏感，`x/` 和 `X/` 会撞车。
 *
 * ## 目录式 terminfo 的两种叶子目录写法（这里最容易踩坑）
 * ncurses 用条目名的**首字符**分桶，但桶名有两种拼法，取决于**编译期**的
 * `MIXEDCASE_FILENAMES`（见 ncurses 的 `LEAF_FMT`）：
 *
 *  - 文件系统大小写敏感（Linux 上的默认构建）：`<dir>/x/xterm-256color`；
 *  - 大小写不敏感（macOS / Cygwin 上做的构建）：`<dir>/78/xterm-256color`，
 *    `78` 是 `'x'` 的十六进制。
 *
 * 关键在于这是**编译进二进制的常量，不是运行时探测**：一个 `%02x` 的构建
 * 永远不会去看 `x/`，反之亦然（本地拿 ncurses 6.4 实测过：只放 `78/` 时它
 * 直接跳过整个目录，静默落到下一个数据库）。而 `$BIJI_BIN` 里的 tmux / less
 * 是从各处下回来的预编译包，我们控制不了它是哪种构建 —— 所以**两种拼法各
 * 铺一份**，多出来的十几 KB 换「不会莫名其妙找不到」。
 *
 * ## 布局
 * ```
 * filesDir/terminfo/
 *   .version                       版本戳，内容 = VERSION，最后一个写
 *   x/xterm-256color  78/xterm-256color
 *   x/xterm           78/xterm            （同一份字节，下同）
 *   x/xterm-color     78/xterm-color
 *   v/vt100           76/vt100
 *   s/screen          73/screen           ← 这条是 tmux pane 专用的另一份字节
 *   s/screen-256color 73/screen-256color
 *   t/tmux-256color   74/tmux-256color
 *   d/dumb            64/dumb             ← 这条是**真的** dumb，不是别名
 * ```
 * 用复制而不是软链：一共十几个文件、二十来 KB，省下的那点空间不值得为
 * 「某些文件系统不支持 symlink」再写一条降级路径。
 *
 * `dumb` 单独编译成一条最小条目（就是 ncurses 自带的那条，字节一致）。它
 * **不能**是 xterm 条目的别名：`TERM=dumb` 在本项目里有语义 ——
 * [ContainerLayout.applyEnv] 用它表示「输出是给模型读的，别上色」，别名过去
 * 等于让 AI 那条路的输出突然长出 ANSI 转义。
 *
 * ## 线程 / 失败
 * [ensure] 幂等且带缓存，第一次会做十几次小文件 IO —— 唯一的调用点是
 * [ShellProfile.environment]，那条路在 `TerminalSession.boot` 的
 * `Dispatchers.IO` 上，别从主线程调。
 *
 * 任何一步失败都**不抛异常**，塞进 [Terminfo.warnings] 由会话层打到终端上，
 * 同时 [Terminfo.dir] 给 null —— 此时 [ShellProfile] 干脆不导出 TERMINFO，
 * 退回本次改动之前的行为（TERM 设了但没库，ncurses 程序照旧报
 * `missing or unsuitable terminal`）。终端本身照样起得来。
 */
object TerminfoDb {

    /**
     * 铺设结果。[dir] 为 null 表示不可用（原因在 [warnings] 里）。
     *
     * [warnings] 非空但 [dir] 可用的情况也存在：主条目铺好了、某个别名没写成，
     * 那种情况下终端能用，只是 `TERM=vt100` 这类兜底名字可能查不到。
     */
    data class Terminfo(val dir: File?, val warnings: List<String> = emptyList())

    /**
     * 改这个值会触发**全量重铺**（先删目录再写）。
     *
     * 换了 assets 里的字节就必须跟着 +1，否则老设备上留着旧条目 —— 那种 bug
     * 的表现是「我改了 terminfo 但设备上没生效」，查起来很费劲。
     */
    const val VERSION = "2"

    private const val DIR_NAME = "terminfo"
    private const val STAMP_NAME = ".version"
    private const val ASSET_DIR = "terminfo"

    /** 这一条铺不成就等于白铺：TERM 就是它。 */
    private const val PRIMARY_NAME = "xterm-256color"

    /** terminfo 二进制的魔数（传统 16 位格式，0432 八进制），低字节在前。 */
    private const val MAGIC_LO = 0x1A
    private const val MAGIC_HI = 0x01

    /**
     * assets 里的文件 → 要铺成哪些名字。
     *
     * 名字必须和条目内部 names 段里的别名对得上（`SOURCE.ti` 第一行），不然
     * `infocmp` 之类的工具看着会很困惑；ncurses 本身不校验这个，但没理由让它
     * 不一致。
     */
    private val ENTRIES = listOf(
        "xterm-256color" to listOf("xterm-256color", "xterm", "xterm-color", "vt100"),
        // screen / tmux 的三个名字**单独一条**，不是上面那条的别名。
        // tmux 会把 pane 里的 TERM 设成 screen / tmux-256color，没有条目的话
        // tmux 起得来但里面每个 ncurses 程序都开机即死 —— 所以必须有。
        // 但 pane 里的程序说话的对象是 **tmux 的模拟器**，不是我们的：
        // ncurses 自带的 tmux-256color 不声明 bce / ech / rep，我们也不能替
        // tmux 声明。声明了的后果是「看着像对的其实错的」那一类 —— bce 会让
        // htop / vim 的彩色面板留下默认色空洞，ech 会让该擦掉的字留在屏幕上，
        // rep 会让一长串重复字符只画出一个。取消这三条的理由写在 SOURCE.ti 里。
        "screen" to listOf("screen", "screen-256color", "tmux-256color"),
        "dumb" to listOf("dumb")
    )

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var cached: Terminfo? = null

    private val lock = Any()

    /**
     * 交一个 Context 进来。幂等，只留 applicationContext（不持有 Activity）。
     *
     * 之所以要这一步：[ShellProfile] 手里只有 [ContainerLayout]，没有 Context，
     * 而读 assets 必须有 AssetManager。终端子系统唯一拿得到 Context 的入口是
     * `ui/terminal/TerminalRuntime.manager()`，在那儿调一次即可。没人调的话
     * [ensure] 会返回不可用 + 一条说明，终端照常启动。
     */
    fun attach(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    /**
     * 保证条目已经铺好，返回可以塞进 `TERMINFO` 的目录。
     *
     * 结果进程内缓存：铺一次之后每次开会话只是读一个 @Volatile 字段。
     */
    fun ensure(context: Context? = null): Terminfo {
        if (context != null) attach(context)
        cached?.let { return it }
        return synchronized(lock) {
            cached ?: install().also {
                // 「还没人 attach 过」不进缓存 —— 那不是铺设失败，只是调用顺序
                // 还没轮到。缓存了的话，之后 attach 上来也永远拿不到 terminfo，
                // 而且完全静默：终端照样起，只是每次多一行警告，没人会想到是
                // 一次早到的 ensure() 把失败结果焊死了。
                if (appContext != null) cached = it
            }
        }
    }

    // -----------------------------------------------------------------

    private fun install(): Terminfo {
        val ctx = appContext
            ?: return Terminfo(null, listOf("terminfo 没初始化（没人调过 TerminfoDb.attach），ncurses 程序会报 missing or unsuitable terminal"))

        val root = File(ctx.filesDir, DIR_NAME)
        val stamp = File(root, STAMP_NAME)
        // 版本没变就直接用。这里只认戳不查文件：真要挨个 stat 十几个文件，
        // 每开一个会话都得走一遍，而戳是**最后**写的，有戳就意味着上一次铺
        // 完整地跑完了。
        val current = runCatching { stamp.readText().trim() }.getOrNull()
        if (current == VERSION) return Terminfo(root)

        // 版本不对（或者上次铺到一半崩了）：整个删掉重来，不做增量。
        // 增量的话，「上个版本多出来的那个别名文件」会一直留在设备上。
        runCatching { root.deleteRecursively() }
        if (!root.mkdirs() && !root.isDirectory) {
            return Terminfo(null, listOf("建不了 ${root.absolutePath}，TERMINFO 这次不导出"))
        }

        val warnings = mutableListOf<String>()
        var primaryOk = false
        for ((asset, names) in ENTRIES) {
            val bytes = readAsset(ctx, asset)
            if (bytes == null) {
                warnings += "assets/$ASSET_DIR/$asset 读不出来或不是 terminfo 二进制，跳过"
                continue
            }
            for (name in names) {
                var wroteOne = false
                for (leaf in leavesOf(name)) {
                    val f = File(File(root, leaf), name)
                    val ok = runCatching {
                        f.parentFile?.mkdirs()
                        f.writeBytes(bytes)
                        true
                    }.getOrDefault(false)
                    if (ok) wroteOne = true
                }
                if (!wroteOne) warnings += "写不了 terminfo 条目 $name"
                else if (name == PRIMARY_NAME) primaryOk = true
            }
        }

        if (!primaryOk) {
            runCatching { root.deleteRecursively() }
            warnings += "主条目 $PRIMARY_NAME 没铺成，TERMINFO 这次不导出"
            return Terminfo(null, warnings)
        }

        // 戳最后写：中途被杀（Android 随时可能回收进程）就没有戳，下次启动
        // 重铺一遍，而不是留下一个缺文件的库让程序在运行时莫名其妙地失败。
        val stamped = runCatching { stamp.writeText(VERSION); true }.getOrDefault(false)
        if (!stamped) warnings += "版本戳写不了，下次启动会重铺一遍（不影响这次使用）"
        return Terminfo(root, warnings)
    }

    /**
     * 读一条 assets 里的条目，顺手验魔数。
     *
     * 验魔数不是洁癖：二进制资源被构建流程「修好」过（换行符转换、文本编码
     * 转换）是真实发生过的事故，而坏掉的 terminfo 的表现是程序启动时报一句
     * 语焉不详的错。这里挡住，至少错误信息指向正确的地方。
     */
    private fun readAsset(ctx: Context, name: String): ByteArray? = runCatching {
        val bytes = ctx.assets.open("$ASSET_DIR/$name").use { it.readBytes() }
        val ok = bytes.size > 12 &&
            (bytes[0].toInt() and 0xFF) == MAGIC_LO &&
            (bytes[1].toInt() and 0xFF) == MAGIC_HI
        if (ok) bytes else null
    }.getOrNull()

    /**
     * 条目名对应的两种叶子目录名：首字符本身，和它的两位小写十六进制。
     *
     * 不用 `String.format("%02x")`：那个跟 Locale 走，阿拉伯语环境下会输出
     * 非 ASCII 数字，目录名直接就错了（`TerminalEmulator.rgbSpec` 那儿踩过
     * 同一个坑）。
     */
    private fun leavesOf(name: String): List<String> {
        val c = name.firstOrNull() ?: return emptyList()
        val code = c.code
        if (code !in 0x21..0x7E) return emptyList()   // 条目名不该有这以外的字符
        val hex = "" + HEX[(code shr 4) and 0xF] + HEX[code and 0xF]
        return listOf(c.toString(), hex)
    }

    private val HEX = charArrayOf(
        '0', '1', '2', '3', '4', '5', '6', '7',
        '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
    )
}
