package com.biji.notes.ui.terminal

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.biji.notes.sandbox.BijiBootstrap
import com.biji.notes.sandbox.ContainerLayout
import com.biji.notes.sandbox.LocalSandbox
import com.biji.notes.sandbox.TerminalSession
import com.biji.notes.sandbox.TerminalSessionManager
import com.biji.notes.sandbox.TerminalSink
import com.biji.notes.nativebridge.NativeGate
import com.biji.notes.terminal.TerminalEmulator
import com.biji.notes.terminal.TerminalHost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

// =====================================================================
//  UI ↔ 会话层 / 模拟器层的**唯一**接缝。
//
//  会话层（com.biji.notes.sandbox.TerminalSession* ）和模拟器层
//  （com.biji.notes.terminal.* ）由别的 agent 并行开发。整个 ui/terminal 包里
//  **只有这个文件**认识那两层的具体类型，其余文件一律只跟 [TerminalBinding]
//  打交道。签名对不上时改这一个文件就够，不用翻渲染 / 手势 / 按键那几千行。
//
//  ## 假设的会话层 API
//    class TerminalSessionManager(
//        layoutProvider: (String?) -> ContainerLayout,
//        sinkFactory: (cols: Int, rows: Int) -> TerminalSink)
//      .sessions: StateFlow<List<TerminalSessionInfo>>   // 抽屉列表
//      .activeId: StateFlow<String?>
//      .ensure(folder, cols, rows): String               // 该 folder 下没有才建
//      .folderKey(folder): String                        // 归一化，过滤列表用
//      .create(folder, name, cols, rows, systemShell): String
//      .session(id): TerminalSession?
//      .activate(id) / .rename(id, name) / .close(id)
//
//    class TerminalSession(id, folder, layout, val sink: TerminalSink, ...)
//      .state: StateFlow<TerminalState>                  // Idle/Starting/Running/Exited/Failed
//      .write(ByteArray) / .interrupt() / .restart() / .shutdown()
//      .resize(cols, rows, cellWidthPx, cellHeightPx)
//      .currentCwd(): String?
//
//    interface TerminalSink { append(bytes, off, len); resize(cols, rows) }
//
//  ## 假设的模拟器 API
//    class TerminalEmulator(cols, rows, scrollbackLines, maxScrollbackBytes, host)
//      .revision: Int（@Volatile，每次屏幕变化 +1；UI 收到重绘信号后读它，
//                       用来判断这次信号有没有带来真正的画面变化）
//      .snapshot(out: TerminalSnapshot, topLine = -1, rowCount = -1)
//      .feed(bytes, off, len) / .resize(cols, rows) / .reset()
//      .encodeKey(key, mods, codePoint): ByteArray?      // 依赖 DECCKM 等模式
//      .encodePaste(text) / .encodeMouse(...) / .encodeFocus(...)
//      .scrollTo/scrollBy/scrollToBottom, .viewportTop, .atBottom, .totalLines
//      .selectionText(l0, c0, l1, c1) / .screenText()
//    interface TerminalHost { onResponse/onTitle/onBell/onWorkingDirectory/onClipboard }
//
//  ## 三条依赖的契约
//   1. 会话层不抛异常，失败塞进 state；所以下面没有一处 try/catch 兜它。
//   2. 模拟器的所有 public 方法自带锁，可以从 UI 线程和读线程同时调。
//   3. TerminalHost 的回调发生在**读线程且持模拟器的锁**——回调实现里绝不能
//      反过来调模拟器（自锁），也不能做耗时操作。
// =====================================================================

/**
 * 把 [TerminalEmulator] 接到会话层的 [TerminalSink] 上。
 *
 * 存在的理由：会话层故意不认识模拟器（两边并行开发，而且降级路径要把管道
 * 输出喂进同一个口子），模拟器也不认识会话（它只吐字节，不知道 pty 是什么）。
 * 这个类是两者之间那张便签。
 *
 * ## 为什么要缓冲「还没接上 writer 时的回复」
 * 会话层是先建 sink、再建 session（sink 是构造参数），所以 sink 出生时拿不到
 * 可以写回去的对象，只能等 `create()` 返回后由 [attach] 补上。中间这一小段
 * 时间里，shell 完全可能已经发出 DA（`ESC[c`）在等答复——终端不回，程序就
 * 卡在那里等超时。把这段时间的回复攒着，[attach] 时一次性倒出去。
 */
class EmulatorSink(cols: Int, rows: Int) : TerminalSink {

    /** 窗口标题（OSC 0/2）。header 显示它，比 `workDir.name` 准得多。 */
    private val _title = MutableStateFlow<String?>(null)
    val title: StateFlow<String?> = _title.asStateFlow()

    /** OSC 7 上报的 cwd。shell 的 PS1 里带 OSC 7 时，`cd` 之后立刻更新。 */
    private val _cwd = MutableStateFlow<String?>(null)
    val cwd: StateFlow<String?> = _cwd.asStateFlow()

    /** OSC 52：程序要求把内容放进剪贴板。UI 侧观察它并真的写进去。 */
    private val _clipboard = MutableStateFlow<String?>(null)
    val clipboard: StateFlow<String?> = _clipboard.asStateFlow()

    /** BEL 计数。手机上响铃没意义，UI 侧拿它做一次短振动。 */
    private val _bell = MutableStateFlow(0)
    val bell: StateFlow<Int> = _bell.asStateFlow()

    private val pendingLock = Any()
    private val pending = ArrayList<ByteArray>(4)

    @Volatile
    private var writer: ((ByteArray) -> Unit)? = null

    val emulator: TerminalEmulator = TerminalEmulator(
        cols = cols,
        rows = rows,
        host = object : TerminalHost {
            // 注意：以下全部在读线程 + 模拟器的锁里跑。只做「放进队列 / 改一个
            // StateFlow」这种不阻塞、不回调模拟器的动作。
            override fun onResponse(data: ByteArray) = respond(data)
            override fun onTitle(title: String) {
                _title.value = title.take(MAX_TITLE_LEN)
            }

            override fun onBell() {
                _bell.value = _bell.value + 1
            }

            override fun onWorkingDirectory(uri: String) {
                _cwd.value = parseFileUri(uri)
            }

            override fun onClipboard(text: String) {
                _clipboard.value = text
            }
        }
    )

    /**
     * 重绘信号。**渲染层唯一的唤醒源。**
     *
     * 值本身没有意义（只是个自增序号，好让 StateFlow 认为「变了」），要不要真的
     * 重画由 `emulator.revision` 说了算。用 StateFlow 是图它天然合并：UI 挂在帧
     * 边界上的那十几毫秒里来了一百个 chunk，醒过来只会看到一次，「多次输出合并
     * 成一帧」这个老行为一个字都没丢。
     *
     * 换掉的是「UI 每帧轮询 emulator.revision」。那条路的代价不在轮询本身，在
     * Recomposer：只要有 `withFrameNanos` 的等待者，它就一直向 Choreographer 要
     * vsync —— 终端页开着、屏幕上一个字都不动，主线程照样被钉在 60-120 Hz 上空转。
     * 现在没输出就没有等待者，那条 vsync 链整个停掉。
     */
    private val _redraw = MutableStateFlow(0)
    val redraw: StateFlow<Int> = _redraw.asStateFlow()

    /**
     * 屏幕内容变了，叫醒渲染。
     *
     * pty 读线程（出字节）和 UI 线程（滚屏 / reset / resize）都会调，所以必须走
     * [update] 的 CAS 而不是 `value = value + 1`：后者丢一次自增就可能让 StateFlow
     * 判定「值没变」而不发信号，那一帧就永远不画了。以前每帧都轮询，丢信号看不
     * 出来；现在信号就是全部，丢一个就是屏幕卡住。
     */
    fun bumpRedraw() {
        _redraw.update { it + 1 }
    }

    override fun append(bytes: ByteArray, off: Int, len: Int) {
        emulator.feed(bytes, off, len)
        bumpRedraw()
    }

    override fun resize(cols: Int, rows: Int) {
        emulator.resize(cols, rows)
        bumpRedraw()
    }

    /** 会话建好之后补接写回通道。幂等——`ensure()` 每次进页面都会调一遍。 */
    fun attach(session: TerminalSession) {
        val w: (ByteArray) -> Unit = { session.write(it) }
        val flush: List<ByteArray>
        synchronized(pendingLock) {
            writer = w
            flush = if (pending.isEmpty()) emptyList() else ArrayList(pending)
            pending.clear()
        }
        for (b in flush) w(b)
    }

    private fun respond(data: ByteArray) {
        val w = writer
        if (w != null) {
            w(data)
            return
        }
        synchronized(pendingLock) {
            // 上界：万一 writer 永远接不上（会话起失败），也不能无限攒。
            if (pending.size < MAX_PENDING_REPLIES) pending.add(data)
        }
    }

    /** 剪贴板事件消费掉，避免同一份内容被反复写进系统剪贴板。 */
    fun consumeClipboard() {
        _clipboard.value = null
    }

    private fun parseFileUri(uri: String): String? {
        // OSC 7 的格式是 file://<host>/<path>，host 通常是主机名或空。
        val idx = uri.indexOf("://")
        if (idx < 0) return uri.ifEmpty { null }
        val rest = uri.substring(idx + 3)
        val slash = rest.indexOf('/')
        if (slash < 0) return null
        // 只做 %XX 反转义：shell 会把空格之类转义掉，不还原的话路径对不上。
        return unescape(rest.substring(slash))
    }

    private fun unescape(s: String): String {
        if (s.indexOf('%') < 0) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val v = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (v != null) {
                    sb.append(v.toChar())
                    i += 3
                    continue
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    private companion object {
        const val MAX_TITLE_LEN = 120
        const val MAX_PENDING_REPLIES = 8
    }
}

/**
 * 进程级的会话注册表。
 *
 * **本该挂在 `BijiApp` 的 `by lazy` 上**（`TerminalSessionManager` 的文档就是
 * 这么写的），但 BijiApp 不在本次改动范围内，所以先在这里做一个等价的进程级
 * 单例：生命周期同样是「和进程一样长」，Compose 只 attach/detach，不 own。
 * 关键效果和挂在 Application 上完全一致 —— 用户退出终端页，正在编译的 shell
 * 不会被 `onDispose` 杀掉，输出也不丢。
 *
 * 接线到 BijiApp 时把 [manager] 换成 `app.terminals` 一行转发即可，其余代码
 * 不用动。
 */
object TerminalRuntime {

    @Volatile
    private var instance: TerminalSessionManager? = null

    private val lock = Any()

    /**
     * @param sandbox / [bootstrap] 只在拿不到 `BijiApp` 时用来兜底构造布局
     *        （单元测试 / Compose 预览）。正常路径走 `BijiApp.aiContainers`。
     */
    fun manager(
        context: Context,
        sandbox: LocalSandbox,
        bootstrap: BijiBootstrap
    ): TerminalSessionManager {
        // 终端子系统里唯一能拿到 Context 的地方，所以 terminfo 的初始化挂在这。
        // 只是存一下 applicationContext，不做 IO —— 真正铺盘发生在
        // ShellProfile.environment()，那条路在会话的 Dispatchers.IO 上。
        com.biji.notes.sandbox.TerminfoDb.attach(context)
        instance?.let { return it }
        val app = context.applicationContext
        return synchronized(lock) {
            instance ?: TerminalSessionManager(
                layoutProvider = { folder -> layoutFor(app, sandbox, bootstrap, folder) },
                sinkFactory = { cols, rows ->
                    EmulatorSink(cols, rows).also { sink ->
                        // 模拟器自检不过时**不**拒绝开终端 —— 用户多半正想用它
                        // 查为什么坏了。但代价要明说：自检失败的症状是"画出来的
                        // 东西看着像对的、其实错位"，不写这行用户会拿着一屏错位
                        // 的输出当真。和会话层降级到管道时的处理一致，不装作
                        // 一切正常。AI 那条路（terminal_snapshot）是直接停用的，
                        // 两边的取舍不同：人能看出屏幕怪，模型不能。
                        // 直接喂模拟器（而不是 sink.append）是因为这是一条内部提示，
                        // 不该走 sink 的字节路径；但重绘信号得自己补一下，否则这行字
                        // 要等到 shell 第一次输出才被画出来。
                        if (!NativeGate.vt) {
                            sink.emulator.feed("\r\n[渲染自检未通过，显示可能错位]\r\n")
                            sink.bumpRedraw()
                        }
                    }
                }
            ).also { instance = it }
        }
    }

    /**
     * 终端和 AI 容器**必须共用同一份 [ContainerLayout]**。
     *
     * 这不是洁癖：AI 装完 `jq`，用户在终端里 `which jq` 立刻看得见同一个
     * `$BIJI_BIN/jq`，「AI 自由装配」才是可验证的。两边各建一份布局的话，
     * 用户会看到 AI 说装好了而终端里 command not found，而且这种 bug 极难查。
     * 顺带解掉终端 cwd 落在 noexec 卷上的老问题（AI 容器早就用 `filesDir/exec`
     * 绕开了，终端一直没有）。
     */
    private fun layoutFor(
        app: Context,
        sandbox: LocalSandbox,
        bootstrap: BijiBootstrap,
        folder: String?
    ): ContainerLayout {
        val fromApp = runCatching {
            (app as com.biji.notes.BijiApp).aiContainers.get(folder).layout
        }.getOrNull()
        if (fromApp != null) return fromApp
        // 兜底：按 AiContainerManager 的同一套 key 归一化自己拼一份。归一化规则
        // 抄它的（trim + 去斜杠 + 空则 default），错一个字就是两套目录。
        val key = folder?.trim()?.trim('/')?.ifEmpty { null } ?: "default"
        return ContainerLayout(
            root = sandbox.projectRoot(key),
            binDir = bootstrap.binDir,
            execDir = File(File(app.filesDir, "exec"), key)
        )
    }

    /**
     * [folder] 名下没有会话就建一个；建完立刻把 sink 的写回通道接上。
     *
     * 复用是**按 folder** 找的（会话层保证），所以从另一个笔记进来不会拿到
     * 上一个笔记的 shell。这里只负责接线。
     */
    fun ensure(
        manager: TerminalSessionManager,
        folder: String?,
        cols: Int,
        rows: Int
    ): String {
        val id = manager.ensure(folder = folder, cols = cols, rows = rows)
        wire(manager, id)
        return id
    }

    fun create(
        manager: TerminalSessionManager,
        folder: String?,
        cols: Int,
        rows: Int,
        systemShell: Boolean = false
    ): String {
        val id = manager.create(
            folder = folder,
            cols = cols,
            rows = rows,
            systemShell = systemShell
        )
        wire(manager, id)
        return id
    }

    private fun wire(manager: TerminalSessionManager, id: String) {
        val session = manager.session(id) ?: return
        (session.sink as? EmulatorSink)?.attach(session)
    }

    fun binding(manager: TerminalSessionManager, id: String?): TerminalBinding? {
        val sid = id ?: return null
        val session = manager.session(sid) ?: return null
        val sink = session.sink as? EmulatorSink ?: return null
        return TerminalBinding(sid, session, sink)
    }

    /**
     * 已经建过就给，没建过给 null，**不负责建**。
     *
     * 专给「删笔记时顺手收掉它的终端」这类清理路径用：为了收东西反而把整个
     * 终端子系统建起来是本末倒置 —— 没开过终端就没有什么可收的。
     */
    fun peek(): TerminalSessionManager? = instance

    /** 会话列表按 folder 过滤：抽屉里只能出现**当前笔记**名下的终端。 */
    fun sessionsIn(
        all: List<com.biji.notes.sandbox.TerminalSessionInfo>,
        folder: String?
    ): List<com.biji.notes.sandbox.TerminalSessionInfo> {
        val key = TerminalSessionManager.folderKey(folder)
        return all.filter { it.folder == key }
    }

    // -----------------------------------------------------------------
    // 字号：存在自己的 SharedPreferences 里
    //
    // 不去动 SettingsRepository（不在改动范围内），而且终端字号是个「跟着手指
    // 捏合实时变」的量，DataStore 的异步写在这条路径上只会添乱。
    // -----------------------------------------------------------------

    private const val PREFS = "biji_terminal_ui"
    private const val KEY_FONT_PX = "font_px"

    fun loadFontPx(context: Context, fallback: Float): Float {
        // 读不出来就用默认值。字号存不下来最多是「下次进来字小了」，
        // 绝不该因为它让整个终端页起不来。
        val v = runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat(KEY_FONT_PX, 0f)
        }.getOrDefault(0f)
        return if (v <= 0f) fallback else v
    }

    fun saveFontPx(context: Context, px: Float) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putFloat(KEY_FONT_PX, px).apply()
        }
    }
}

/**
 * UI 侧唯一持有的句柄：一个会话 + 它的模拟器。
 *
 * [Stable] 而不是 [androidx.compose.runtime.Immutable]：内容会变（模拟器一直在
 * 被写），但**引用本身**在会话不变时是稳定的，Compose 可以据此跳过重组。
 * 屏幕内容不走 Compose state —— 走 [redraw] 信号 + 只失效 draw 阶段，
 * 见 `TerminalCanvas`。
 */
@Stable
class TerminalBinding(
    val id: String,
    private val session: TerminalSession,
    val sink: EmulatorSink
) {
    val emulator: TerminalEmulator get() = sink.emulator

    val state get() = session.state

    /** 每次屏幕变化 +1。收到 [redraw] 信号后读一次，变了才重新取快照。 */
    val revision: Int get() = emulator.revision

    /**
     * 重绘信号，见 [EmulatorSink.redraw]。
     *
     * 它覆盖的是「模拟器被写过了」这件事：pty 出字节、滚屏、reset、resize 全在内。
     * **任何绕过这个类直接动 `emulator` 的写操作都会漏掉信号**，症状是屏幕停在
     * 上一帧不动直到下一次有输出 —— 所以模拟器的写入口一律收在下面这几个方法里，
     * 新增写操作时记得跟着 bump。
     */
    val redraw: StateFlow<Int> get() = sink.redraw

    val columns: Int get() = emulator.columns
    val screenRows: Int get() = emulator.screenRows
    val totalLines: Int get() = emulator.totalLines
    val historyLines: Int get() = emulator.historyLines
    val viewportTop: Int get() = emulator.viewportTop
    val atBottom: Boolean get() = emulator.atBottom

    /**
     * 一次按键。
     *
     * 编码在模拟器里做：方向键发 `ESC[A` 还是 `ESC OA` 取决于 DECCKM，
     * 那个模式只有模拟器知道。把它同步到 UI 必然产生「UI 拿着陈旧模式」的
     * bug，症状是 vi 里方向键偶发失灵。
     */
    fun key(key: Int, mods: Int = 0, codePoint: Int = -1) {
        val bytes = emulator.encodeKey(key, mods, codePoint) ?: return
        sendAndFollow(bytes)
    }

    /** 一段文本（输入法上屏）。逐码点走 encodeKey，`\n` 会被转成 `\r`。 */
    fun text(s: String) {
        if (s.isEmpty()) return
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            when {
                // 终端等的是 CR。指望 `icrnl` 会在 raw 模式的程序里翻车 ——
                // vim 收到 LF 会当成「另起一行」而不是「确认」。
                cp == '\n'.code || cp == '\r'.code ->
                    key(TerminalEmulator.KEY_ENTER)
                cp == '\t'.code -> key(TerminalEmulator.KEY_TAB)
                cp == 0x7F -> key(TerminalEmulator.KEY_BACKSPACE)
                else -> key(TerminalEmulator.KEY_CHAR, codePoint = cp)
            }
        }
    }

    fun paste(s: String) {
        if (s.isEmpty()) return
        sendAndFollow(emulator.encodePaste(s))
    }

    fun raw(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        sendAndFollow(bytes)
    }

    /**
     * 真 Ctrl-C：往 pty 写一个 0x03，内核的行规程把 SIGINT 发给**前台进程组**。
     * 所以跑着的 `find /` 会停、shell 本身不会。会话层已经处理好了降级路径。
     */
    fun interrupt() {
        session.interrupt()
        scrollToBottom()
    }

    fun mouse(button: Int, col: Int, row: Int, pressed: Boolean, motion: Boolean = false): Boolean {
        val bytes = emulator.encodeMouse(button, col, row, pressed, motion) ?: return false
        session.write(bytes)
        return true
    }

    fun focusChanged(focused: Boolean) {
        emulator.encodeFocus(focused)?.let { session.write(it) }
    }

    fun resize(cols: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int) {
        // 只调会话层：它会先让模拟器改尺寸、再下 TIOCSWINSZ，顺序反了的话
        // SIGWINCH 触发的重绘会按旧尺寸画。
        session.resize(cols, rows, cellWidthPx, cellHeightPx)
    }

    fun restart() = session.restart()

    // 下面这四个是 UI 侧对模拟器的写操作。它们不经过 sink（sink 只认「有字节
    // 进来」），所以得自己把重绘信号敲一下，否则用户滚屏时屏幕不动。
    fun reset() {
        emulator.reset()
        sink.bumpRedraw()
    }

    fun scrollTo(line: Int) {
        emulator.scrollTo(line)
        sink.bumpRedraw()
    }

    fun scrollBy(delta: Int) {
        emulator.scrollBy(delta)
        sink.bumpRedraw()
    }

    fun scrollToBottom() {
        emulator.scrollToBottom()
        sink.bumpRedraw()
    }

    fun selectionText(line0: Int, col0: Int, line1: Int, col1: Int): String =
        emulator.selectionText(line0, col0, line1, col1)

    fun screenText(): String = emulator.screenText()

    fun cwd(): String? = sink.cwd.value ?: session.currentCwd()

    /**
     * 容器根目录。只用来把 cwd 显示成 `~/work` —— app 私有目录的绝对路径长到
     * 顶栏一行放不下，直接显示的话用户只能看见一串 `/storage/emulated/0/Android`，
     * 真正有用的最后两级反而被省略号吃掉。
     */
    val homePath: String = session.layout.root.absolutePath

    fun shortenPath(path: String): String = when {
        path == homePath -> "~"
        path.startsWith("$homePath/") -> "~" + path.substring(homePath.length)
        else -> path
    }

    /** 用户敲键 = 明确的「我要看最新输出」，所以顺手回到底部（Termux 同样）。 */
    private fun sendAndFollow(bytes: ByteArray) {
        session.write(bytes)
        if (!emulator.atBottom) scrollToBottom()
    }
}

/**
 * 拿到当前活动会话的句柄。没有会话就建一个。
 *
 * 会话本身活在 [TerminalRuntime] 里（进程级），这里只是「取」，
 * **绝不在 onDispose 里关会话** —— 那正是现在这套 UI 最大的毛病：
 * 切个页面就把正在编译的 shell 杀了。
 */
@Composable
fun rememberTerminalBinding(
    sandbox: LocalSandbox,
    bootstrap: BijiBootstrap,
    folder: String
): TerminalHandles {
    val context = LocalContext.current
    val manager = remember(sandbox, bootstrap) {
        TerminalRuntime.manager(context, sandbox, bootstrap)
    }
    // 第一次进来先按 80x24 起，UI 量出真实行列后再 resize。这条顺序不能反：
    // 等布局完成再起 shell 的话，用户会先看到一片空白。
    // key 里带 folder：换了笔记要重新 ensure 一次，否则屏幕上还挂着上一个笔记的 shell。
    remember(manager, folder) { TerminalRuntime.ensure(manager, folder, 80, 24) }

    val globalActiveId by manager.activeId.collectAsState()
    val allSessions by manager.sessions.collectAsState()
    // 会话数到上限、又腾不出位置时 create()/ensure() 返回空串，原因写在这儿。
    // 不收的话用户看到的是一块永远停在「会话启动中…」的空屏 —— 建不出来
    // 不会重试（下面那个 LaunchedEffect 的 key 不会再变），也没有任何提示。
    val lastError by manager.lastError.collectAsState()

    // 注册表是进程级的、装着所有笔记的终端；这个页面只认自己 folder 的那一批。
    // 不过滤的话抽屉里会列出别人的会话，点一下就把别的笔记的 shell 拉到眼前。
    val sessions = remember(allSessions, folder) {
        TerminalRuntime.sessionsIn(allSessions, folder)
    }
    // activeId 是全局的一个值，可能正指着别的 folder（比如别处刚 create 过）。
    // 认不出来就当没有，交给下面的 LaunchedEffect 补一个自己的。
    val activeId = globalActiveId?.takeIf { id -> sessions.any { it.id == id } }

    // 用户把最后一个会话也关了 —— 补一个新的，否则页面就停在「会话启动中」
    // 死在那里，除了退出没有别的出路。会话**结束**（进程退出）不会走到这里，
    // 那种情况条目还在列表里，用户得能读到最后一屏输出再决定重开还是关掉。
    // key 里带**全局**会话数：到了 MAX_SESSIONS 时 ensure() 会失败并返回空串，
    // 此时 sessions（本 folder 的）一直是空、activeId 一直是 null，光靠它们两个
    // 这个 effect 永远不会再跑一次。用户照着提示去别的笔记关掉一个终端回来后，
    // 变的正是全局那个数 —— 不带上它，页面就永久停在那条错误上。
    LaunchedEffect(manager, folder, sessions.isEmpty(), activeId == null, allSessions.size) {
        if (sessions.isEmpty() || activeId == null) {
            TerminalRuntime.ensure(manager, folder, 80, 24)
        }
    }
    val binding = remember(manager, activeId, sessions.size) {
        TerminalRuntime.binding(manager, activeId)
    }

    // 焦点上报（?1004）：vim / tmux 靠它决定要不要重绘、要不要自动保存。
    DisposableEffect(binding) {
        binding?.focusChanged(true)
        onDispose { binding?.focusChanged(false) }
    }

    return TerminalHandles(manager, binding, sessions, activeId, folder, lastError)
}

/**
 * [rememberTerminalBinding] 的返回值。会话列表和当前句柄一起给出去。
 *
 * [sessions] / [activeId] 都**已经按 [folder] 过滤**过了，UI 拿到手直接渲染，
 * 不用再操心隔离；[newSession] 建出来的也一定落在同一个 folder 上。
 */
@Stable
class TerminalHandles(
    val manager: TerminalSessionManager,
    val binding: TerminalBinding?,
    val sessions: List<com.biji.notes.sandbox.TerminalSessionInfo>,
    val activeId: String?,
    val folder: String,
    /**
     * 最近一次建会话失败的原因，没有就是 null。
     *
     * 会话数是有全局上限的（[TerminalSessionManager.MAX_SESSIONS] / 单笔记
     * [TerminalSessionManager.MAX_PER_FOLDER]），到顶又没有可回收的会话时
     * `create()` / `ensure()` 返回空串而**不抛异常**。UI 必须把这条读出来，
     * 否则「按 ＋ 没反应」和「空屏停在会话启动中」都是无声的。
     */
    val lastError: String? = null
) {
    /**
     * 建一个新会话。**到上限建不出来时返回 false**，原因见 [lastError]。
     *
     * 返回值不能省：`create()` 失败是返回空串而不是抛异常，调用方不看返回值
     * 就等于「按了＋没反应」——而这条路径恰恰是用户手动触发的，最需要反馈。
     */
    fun newSession(cols: Int, rows: Int, systemShell: Boolean = false): Boolean =
        TerminalRuntime.create(manager, folder, cols, rows, systemShell).isNotEmpty()

    fun activate(id: String) = manager.activate(id)

    fun rename(id: String, name: String) = manager.rename(id, name)

    fun close(id: String) = manager.close(id)
}
