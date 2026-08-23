package com.biji.notes.sandbox

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * 多终端会话的注册表（Termux 抽屉里那份列表）。
 *
 * ## 必须挂在 app 作用域
 * 和 [AiContainerManager] 一样放进 `BijiApp` 的 `by lazy`：
 * ```
 * val terminals by lazy {
 *     TerminalSessionManager(
 *         // 和 AI 容器**共用同一份布局**：AI 装完 jq，用户在终端里 which jq
 *         // 立刻看得见同一个 $BIJI_BIN/jq。两边各建一份布局是最难查的一类 bug。
 *         layoutProvider = { folder -> aiContainers.get(folder).layout },
 *         sinkFactory = { cols, rows -> TerminalEmulator(cols, rows) }
 *     )
 * }
 * ```
 * 绝不能绑在 Compose 的 `remember` 上 —— 那样用户切个页面 `onDispose` 就把
 * 正在编译的 shell 杀了，输出也全丢（现在 `TerminalScreen` 就是这个毛病）。
 *
 * ## 会话退出后不自动移除
 * 进程死了（哪怕是被 Android 12+ 的 phantom process killer 杀的）也把条目留在
 * 列表里，标成已结束。用户得能读到最后那屏输出、知道为什么死的、再按重开。
 * 列表项真正消失只发生在 [close]，或者撞上数量上限被当作回收对象（见下）——
 * 死会话是第一顺位的回收对象，正因为它除了那屏输出什么都不剩。
 *
 * ## folder 是硬隔离边界
 * 每个笔记会话有自己的项目目录（自己的 [ContainerLayout]），所以**终端也必须
 * 按 folder 分开**：从会话 B 进终端绝不能落在会话 A 的 shell 上（那意味着 B 的
 * 用户直接看见并能改 A 的项目文件）。同一个 folder 底下可以有任意多个会话
 * （抽屉里那份列表，Termux 的语义），跨 folder 一个都不共享。
 * 归一化统一走 [folderKey]，和 [AiContainerManager] 用的是同一条规则 ——
 * 差一个字符就是两套目录。
 *
 * ## 会话数有上限
 * 一个会话满载约 2.3 MB（回滚缓冲封顶 2 MB + 屏幕 + style 表，见
 * `terminal/TerminalBuffer.kt`），而"退出后不自动移除"意味着死会话也照样
 * 占着这一份。所以这里有两道闸：全局 [MAX_SESSIONS]、单 folder
 * [MAX_PER_FOLDER]。超了的处置见 [create] / [ensure]，一句话是
 * **先回收死的，回收不出来就拒绝，绝不掐用户正开着的 shell**。
 */
class TerminalSessionManager(
    private val layoutProvider: (String?) -> ContainerLayout,
    /**
     * 建屏幕缓冲。`terminal/TerminalEmulator` 实现 [TerminalSink] 即可，
     * 会话层不认识它的具体类型。默认丢弃，方便无 UI 的场景先跑通。
     */
    private val sinkFactory: (cols: Int, rows: Int) -> TerminalSink = { _, _ -> DiscardSink }
) {

    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val lock = Any()

    /** 保序：抽屉里的顺序就是创建顺序，用 LinkedHashMap 而不是 ConcurrentHashMap。 */
    private val entries = LinkedHashMap<String, Entry>()

    private val idSeq = AtomicInteger(0)
    private val nameSeq = AtomicInteger(0)

    /**
     * folderKey -> 该 folder 上次活动的会话 id。
     *
     * 只有一个全局 [_activeId] 是不够的：用户在 A 里开着三个终端、切到 B、
     * 再切回 A，应该落回他离开 A 时的那一个，而不是「A 名下最后建的那个」。
     * 同 [entries] 一把锁保护。
     */
    private val lastActiveByFolder = HashMap<String, String>()

    private val _sessions = MutableStateFlow<List<TerminalSessionInfo>>(emptyList())
    val sessions: StateFlow<List<TerminalSessionInfo>> = _sessions.asStateFlow()

    private val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    /**
     * 最近一次「没能建成会话」的原因，UI 想提示就读它。
     *
     * 建会话失败不抛异常（这一层的规矩：失败塞进返回值），[create] 返回空串，
     * 但空串本身说不清为什么，所以原因放这儿。成功一次就清空。
     */
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    // -----------------------------------------------------------------
    // 增删
    // -----------------------------------------------------------------

    /**
     * 新建并**立即启动**一个会话，返回它的 id。
     *
     * @param folder 归属的笔记 / 项目，决定用哪份 [ContainerLayout]。
     * @param name   null 就自动排号（「终端 1」「终端 2」）。
     * @param systemShell failsafe 会话：跳过装出来的 bash 直接用 `/system/bin/sh`。
     *        装的 bash 把终端搞崩的时候，这是唯一的自救入口。
     * @return 新会话 id；**到了数量上限且腾不出位置时返回空串**，原因写进
     *         [lastError]。用户按的是「新建终端」，不是「杀掉我正在跑的编译」，
     *         所以这条路径只回收已结束的会话，回收不到就直说建不了。
     */
    fun create(
        folder: String? = null,
        name: String? = null,
        cols: Int = DEFAULT_COLS,
        rows: Int = DEFAULT_ROWS,
        systemShell: Boolean = false
    ): String = createInternal(folder, name, cols, rows, systemShell, allowLiveReclaim = false)

    private fun createInternal(
        folder: String?,
        name: String?,
        cols: Int,
        rows: Int,
        systemShell: Boolean,
        allowLiveReclaim: Boolean
    ): String {
        makeRoom(folderKey(folder), allowLiveReclaim)?.let {
            _lastError.value = it
            return ""
        }
        _lastError.value = null
        val id = "t${idSeq.incrementAndGet()}"
        val safeCols = if (cols > 0) cols else DEFAULT_COLS
        val safeRows = if (rows > 0) rows else DEFAULT_ROWS
        val key = folderKey(folder)
        val layout = layoutProvider(folder)
        // 每个会话一个子作用域：关会话时连它的 writer / 轮询协程一起收掉，
        // 不用挨个 cancel，也不会漏。
        val scope = CoroutineScope(
            SupervisorJob(managerScope.coroutineContext[Job]) + Dispatchers.IO
        )
        val session = TerminalSession(
            id = id,
            folder = folder,
            layout = layout,
            sink = sinkFactory(safeCols, safeRows),
            scope = scope,
            preferSystemShell = systemShell
        )
        val entry = Entry(
            session = session,
            scope = scope,
            folderKey = key,
            name = name?.trim()?.ifEmpty { null } ?: "终端 ${nameSeq.incrementAndGet()}",
            lastActiveAt = System.currentTimeMillis()
        )
        synchronized(lock) {
            entries[id] = entry
            lastActiveByFolder[key] = id
        }
        _activeId.value = id

        // 状态一变就刷列表：抽屉要显示「运行中 / 已结束」，不然用户看不出
        // 哪个会话已经死了。挂在会话自己的作用域上，[close] 之后跟着一起死，
        // 挂 managerScope 就是每建一个会话漏一个永不结束的协程。
        scope.launch { session.state.collect { publish() } }

        session.start(cols = safeCols, rows = safeRows)
        publish()
        return id
    }

    /**
     * [folder] 名下没有会话就建一个，有就挑一个复用并置为活动。终端页进来用这个。
     *
     * **只在 [folder] 内部找**。以前这里认的是全局 `activeId`，于是从会话 B 打开
     * 终端会直接复用会话 A 的 shell —— 连带 A 的 [ContainerLayout]，也就是 A 的
     * 项目目录。用户看到的是「新建的会话里躺着上一个会话的文件」，而「每个会话
     * 从头开始」是这个产品的硬要求，不是偏好。
     */
    fun ensure(
        folder: String? = null,
        cols: Int = DEFAULT_COLS,
        rows: Int = DEFAULT_ROWS
    ): String {
        val key = folderKey(folder)
        val existing = synchronized(lock) {
            // 先认「这个 folder 上次用的那个」；它可能已经被 close 掉了，所以
            // 复核它还在表里、而且 folder 没变（id 不复用，但便宜的复核值得做）。
            val remembered = lastActiveByFolder[key]?.takeIf { entries[it]?.folderKey == key }
            // 兜底取最后建的那个：抽屉是按创建顺序排的，最后一个最贴近「刚才在用的」。
            remembered ?: entries.entries.lastOrNull { it.value.folderKey == key }?.key
        }
        if (existing != null) {
            activate(existing)
            return existing
        }
        // 曾经这里比 [create] 多放一档：允许收别的 folder 下闲了 10 分钟的**活**
        // 会话，理由是「建不出来页面就永远停在空屏」。那条理由现在不成立了 ——
        // 失败原因会经由 [lastError] 显示在终端页上，用户知道发生了什么。
        //
        // 而代价是实打实的：`lastActiveAt` 只在 [activate] 时更新，也就是说
        // 「用户没切过去」= 闲置，跟那个会话里是不是正在跑一个三十分钟的编译
        // 毫无关系。开着 8 个终端、其中一个在后台构建、用户去了第 9 个笔记，
        // 这一档就会**静默杀掉那个构建**。省 2.3 MB 不值这个代价。
        return createInternal(
            folder = folder, name = null, cols = cols, rows = rows,
            systemShell = false, allowLiveReclaim = false
        )
    }

    // -----------------------------------------------------------------
    // 数量上限
    // -----------------------------------------------------------------

    /**
     * 给 [folder] 腾一个位置。腾出来（或本来就够）返回 null，腾不出来返回
     * 给用户看的原因。
     *
     * 回收顺序：**已结束的最久没碰过的那个** 优先 —— 它只剩一屏可读的输出，
     * 代价最小；[allowLive] 时再往下找别的 folder 里长期没动的活会话。
     * 循环安全：每轮 [close] 必定移掉一个条目，[pickVictim] 找不到人就退出。
     */
    private fun makeRoom(key: String, allowLive: Boolean): String? {
        while (true) {
            val victim = synchronized(lock) {
                val perFolder = entries.count { it.value.folderKey == key }
                if (entries.size < MAX_SESSIONS && perFolder < MAX_PER_FOLDER) return null
                pickVictim(key, allowLive)
            } ?: return roomError(key)
            close(victim)
        }
    }

    /** 调用方必须持 [lock]。 */
    private fun pickVictim(key: String, allowLive: Boolean): String? {
        val perFolderFull = entries.count { it.value.folderKey == key } >= MAX_PER_FOLDER
        // 卡的是单 folder 上限时，只有收同 folder 的才管用 —— 收别人的收多少
        // 次这个 folder 的计数都不变，那就成死循环了。
        val pool =
            if (perFolderFull) entries.entries.filter { it.value.folderKey == key }
            else entries.entries.toList()

        pool.filter { !isLive(it.value) }.minByOrNull { it.value.lastActiveAt }?.let { return it.key }
        if (!allowLive) return null

        val now = System.currentTimeMillis()
        return pool
            .filter { it.value.folderKey != key && now - it.value.lastActiveAt >= LIVE_RECLAIM_IDLE_MS }
            .minByOrNull { it.value.lastActiveAt }
            ?.key
    }

    private fun roomError(key: String): String {
        val perFolder = synchronized(lock) { entries.count { it.value.folderKey == key } }
        return if (perFolder >= MAX_PER_FOLDER) {
            "这个笔记下已经有 $MAX_PER_FOLDER 个终端在跑，先关掉一个再新建"
        } else {
            // 全局上限被别的笔记占满时，出路不在这个页面的抽屉里（那里只列
            // 本笔记的会话），所以得说清楚去哪儿关。
            "终端总数已达上限 $MAX_SESSIONS，且都还活着。到别的笔记里关掉一个不用的终端再回来"
        }
    }

    private fun isLive(e: Entry): Boolean =
        when (e.session.state.value) {
            is TerminalState.Exited, is TerminalState.Failed -> false
            else -> true
        }

    /**
     * 关掉并从列表里移除。
     *
     * 作用域延后再 cancel：[TerminalSession.shutdown] 之后还有回收要走（宽限期
     * 里的 SIGKILL 升级、waiter 收尾），立刻 cancel 会把这些半途掐掉，留下僵尸。
     */
    fun close(id: String) {
        val entry = synchronized(lock) { entries.remove(id) } ?: return
        // 接班的必须是**同一个 folder** 名下的，一个都没有就留空、让 UI 重开一个。
        // 以前这里取的是全表最后一条：用户关掉 B 的最后一个终端，屏幕上直接冒出
        // A 的 shell（连带 A 的项目目录），比空屏危险得多。
        val heir = synchronized(lock) {
            val next = entries.entries.lastOrNull { it.value.folderKey == entry.folderKey }?.key
            // 只在被关掉的正是「上次活动的那个」时才改指针，否则会把用户
            // 真正停留的会话挤掉；folder 空了就整条删，别在表里攒垃圾。
            if (lastActiveByFolder[entry.folderKey] == id) {
                if (next != null) lastActiveByFolder[entry.folderKey] = next
                else lastActiveByFolder.remove(entry.folderKey)
            }
            next
        }
        if (_activeId.value == id) _activeId.value = heir
        runCatching { entry.session.shutdown() }
        managerScope.launch {
            delay(SCOPE_CANCEL_DELAY_MS)
            runCatching { entry.scope.cancel() }
        }
        publish()
    }

    /** 会话所属的笔记被删了：把它名下的终端一起收掉。 */
    fun closeFolder(folder: String?) {
        // 按归一化后的 key 比，不比原始字符串 —— `"a"` / `"a/"` / `" a"` 指的是
        // 同一个项目目录，用原始串比会漏掉一部分，留下一堆指向已删目录的终端。
        val key = folderKey(folder)
        val ids = synchronized(lock) {
            entries.filterValues { it.folderKey == key }.keys.toList()
        }
        for (id in ids) close(id)
    }

    fun closeAll() {
        val ids = synchronized(lock) { entries.keys.toList() }
        for (id in ids) close(id)
    }

    // -----------------------------------------------------------------
    // 查 / 改
    // -----------------------------------------------------------------

    fun session(id: String): TerminalSession? = synchronized(lock) { entries[id]?.session }

    fun active(): TerminalSession? = _activeId.value?.let { session(it) }

    fun activate(id: String) {
        synchronized(lock) {
            val entry = entries[id] ?: return
            lastActiveByFolder[entry.folderKey] = id
            entry.lastActiveAt = System.currentTimeMillis()
        }
        _activeId.value = id
        publish()
    }

    /** 空名字忽略：一个没名字的条目在抽屉里等于消失了。 */
    fun rename(id: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        synchronized(lock) { entries[id]?.name = trimmed.take(MAX_NAME_LEN) }
        publish()
    }

    /** 会话数。前台服务要不要挂着看这个。 */
    val count: Int get() = synchronized(lock) { entries.size }

    // -----------------------------------------------------------------

    private fun publish() {
        val list = synchronized(lock) {
            entries.map { (id, e) ->
                val st = e.session.state.value
                TerminalSessionInfo(
                    id = id,
                    name = e.name,
                    folder = e.folderKey,
                    alive = st is TerminalState.Running,
                    usingPty = (st as? TerminalState.Running)?.pty == true,
                    pid = (st as? TerminalState.Running)?.pid ?: -1,
                    statusText = statusText(st)
                )
            }
        }
        _sessions.value = list
    }

    private fun statusText(st: TerminalState): String = when (st) {
        is TerminalState.Idle -> "未启动"
        is TerminalState.Starting -> "启动中"
        // 兼容模式要在列表里就看得见，不然用户不知道为什么这个会话没颜色。
        is TerminalState.Running -> if (st.pty) "运行中" else "运行中（兼容模式）"
        is TerminalState.Exited -> if (st.byUser) "已关闭" else "已结束"
        is TerminalState.Failed -> "启动失败"
    }

    private class Entry(
        val session: TerminalSession,
        val scope: CoroutineScope,
        /** 归一化后的归属，隔离判断只认它，不认 `session.folder` 那个原始串。 */
        val folderKey: String,
        @Volatile var name: String,
        /**
         * 上次被切到前台（或刚建出来）的时刻。回收时按它排 LRU。
         * 记的不是「上次有输出」：一个在跑 `make` 的会话不该因为用户切走了
         * 就排到队尾，但它是活的，活会话本来就只在最后一档才会被动。
         */
        @Volatile var lastActiveAt: Long
    )

    companion object {
        private const val DEFAULT_COLS = 80
        private const val DEFAULT_ROWS = 24
        private const val MAX_NAME_LEN = 40

        /**
         * 全局会话数上限。每个满载约 2.3 MB（回滚 2 MB + 屏幕 + style 表），
         * 8 个 ≈ 18 MB，是手机上能接受的量；抽屉里排 8 条以上本来也没法用。
         */
        const val MAX_SESSIONS = 8

        /** 单个笔记下的上限。一个项目开到 4 个终端已经很够了。 */
        const val MAX_PER_FOLDER = 4

        /**
         * [ensure] 走投无路时，才会去收别的 folder 下闲了这么久的活会话。
         * 10 分钟没切过去，用户多半已经忘了它 —— 而当前这个笔记的终端页
         * 正等着一个能用的会话，两害相权。
         */
        const val LIVE_RECLAIM_IDLE_MS = 10 * 60 * 1000L

        /** 留给宽限期 SIGKILL + waiter 收尾的时间，比会话里的宽限期长一点。 */
        private const val SCOPE_CANCEL_DELAY_MS = 3000L

        /**
         * folder -> 目录 key。**必须和 [AiContainerManager] 那份逐字一致** ——
         * 两边算出来的 key 一旦有偏差，终端和 AI 容器就落在两个目录上，
         * 表现为「AI 说文件写好了，终端里 ls 不到」，这类 bug 极难查。
         * UI 侧过滤会话列表也调这个，别在别处再抄一遍。
         */
        fun folderKey(folder: String?): String =
            folder?.trim()?.trim('/')?.ifEmpty { null } ?: AiContainerManager.DEFAULT_KEY
    }
}

/**
 * 抽屉列表用的只读快照。
 *
 * 全是不可变基本类型，[Immutable] 让 Compose 跳过整棵子树的重组 —— 直接把
 * [TerminalSession] 塞进列表项会让它变成 unstable，每次输出都重组一遍列表。
 */
@Immutable
data class TerminalSessionInfo(
    val id: String,
    val name: String,
    /** 归一化后的 folder key（见 [TerminalSessionManager.folderKey]），不是用户输入的原串。 */
    val folder: String?,
    val alive: Boolean,
    val usingPty: Boolean,
    val pid: Int,
    val statusText: String
)
