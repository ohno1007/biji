package com.biji.notes.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.biji.notes.data.AppSettings
import com.biji.notes.data.ChatRepository
import com.biji.notes.data.Conversation
import com.biji.notes.data.Message
import com.biji.notes.data.MessageKind
import com.biji.notes.data.Role
import com.biji.notes.data.SettingsRepository
import com.biji.notes.memory.MemoryService
import com.biji.notes.net.BalanceInfo
import com.biji.notes.net.ChatEvent
import com.biji.notes.net.ChatMessageDto
import com.biji.notes.net.DeepSeekClient
import com.biji.notes.net.ModelInfo
import com.biji.notes.net.ToolCall
import com.biji.notes.net.ToolExecutor
import com.biji.notes.net.Tools
import com.biji.notes.notif.ChatNotifier
import com.biji.notes.voice.VoiceRecognizer
import com.biji.notes.voice.VoiceState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** 流式落库最小间隔（毫秒）。攒够这么久才写一次 DB，避免每个
 *  token 触发一轮全列表重组。 */
private const val STREAM_FLUSH_MS = 90L

/**
 * 开发者模式的系统提示。
 *
 * 这里承担的是「讲一次，工具描述里不再重复」的角色：目录布局、work/ 是
 * noexec、只跑静态 aarch64 / bionic、catalog→install 的先后顺序、poll 的游标
 * 用法 —— 这几件事原来在 21 个工具描述里各自重复了 2 到 5 遍，而它们每一轮
 * 请求都要重发一次。搬进来之后 tools 数组小了五千字节，说的还是同一件事。
 *
 * 它必须留在 system 消息里、且内容和这一轮说了什么无关 —— 前缀缓存是按 token
 * 前缀块匹配的，system 里只要有一个字每轮在变，后面整条历史的缓存全作废。
 */
private const val CONTAINER_BRIEF = """本会话有一个专属本地容器（Android 用户态，无 root）。以下是全局约定，各工具描述里不再重复：
- 目录：${'$'}BIJI_WORK 工作区（= read_file / write_file 的 work/ 前缀）、${'$'}BIJI_TMP 临时、${'$'}BIJI_BIN 已装命令、${'$'}BIJI_EXEC 可执行区。**工作区是 noexec 的**，自己编的二进制先 cp 到 ${'$'}BIJI_EXEC，或用 toolchain_manage action=link。
- **本机只跑两种二进制**：静态 aarch64，或 PT_INTERP=/system/bin/linker64 的 bionic 构建。指向 ld-linux / ld-musl 的报 "No such file or directory" —— 缺的是 loader，不是文件。挑包看文件名：-android 最好，-musl 可以，-gnu / .deb / .rpm / Termux 的一律不行。
- 顺序：toolchain_probe 看环境 → toolchain_catalog 查来源 → toolchain_install 装 → container_exec 跑。别凭记忆假设某个命令存在。
- container_exec 是长驻 shell（cd / export / 函数都留到下次）；超 2 分钟的活儿用 container_task action=start 再 poll。所有 poll 都把上次返回的游标原样传回来就拿到增量。
- 工具结果里出现「中间 N 行已折叠」是上下文预算干的，不是命令本身截断的。真要看完整输出就重跑，并且接上 grep / tail 收窄。
- 用户另有一块终端，共用 ${'$'}BIJI_BIN / ${'$'}BIJI_WORK。他说「我这儿报错了」用 terminal_snapshot 读他那块屏，别用 container_exec 重跑 —— 那是不同的 shell。"""

/**
 * 只在设置里开了 root 时才追加到 [CONTAINER_BRIEF] 后面。
 *
 * 和 `Tools.definitions(useRoot = …)` 是**同一个开关**，两边必须同进同退：
 * brief 里留着 run_shell_command 这个名字而工具列表里没有，模型就会去调一个
 * 不存在的工具，白烧一轮。反过来，root 打开时不提它，模型又不知道容器里
 * 那条被拦掉的 su 该怎么绕。
 */
private const val ROOT_SHELL_BRIEF =
    "- 另有 run_shell_command：每次都是全新的 sh（cd / export 转头就没），但它是唯一能带 root 跑的通道 —— 容器里的 su 是被拦掉的。只在确实需要 root 时用它。"


data class ModelsState(
    val loading: Boolean = false,
    val list: List<ModelInfo> = emptyList(),
    val error: String? = null
)

data class BalanceState(
    val loading: Boolean = false,
    val info: BalanceInfo? = null,
    val error: String? = null
)

data class ContextUsage(
    val tokens: Long = 0L,
    val limit: Long = DEFAULT_LIMIT
) {
    val fraction: Float get() = if (limit <= 0L) 0f else (tokens.toFloat() / limit).coerceIn(0f, 1f)
    companion object { const val DEFAULT_LIMIT = 64_000L }
}

/** Per-turn token breakdown returned by the API. We forward
 *  `prompt_cache_hit_tokens` / `prompt_cache_miss_tokens` so the
 *  context-stats popup can show the cache mix. */
data class UsageDetails(
    val prompt: Long = 0L,
    val completion: Long = 0L,
    val total: Long = 0L,
    val cacheHit: Long = 0L,
    val cacheMiss: Long = 0L
)

enum class WorkflowStepState { RUNNING, DONE, ERROR }

data class WorkflowStep(
    val tool: String,        // "web_search" / "read_url" / ...
    val label: String,       // human-readable: "联网搜索: 特朗普"
    val state: WorkflowStepState
)

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(
    private val chat: ChatRepository,
    private val settingsRepo: SettingsRepository,
    private val client: DeepSeekClient,
    private val memory: MemoryService,
    private val toolExec: ToolExecutor,
    private val notifier: ChatNotifier,
    val voice: VoiceRecognizer,
    val sandbox: com.biji.notes.sandbox.LocalSandbox,
    val bootstrap: com.biji.notes.sandbox.BijiBootstrap,
    val pkg: com.biji.notes.sandbox.BijiPkg,
    val containers: com.biji.notes.sandbox.AiContainerManager,
    private val isForeground: () -> Boolean
) : ViewModel() {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    val settings: StateFlow<AppSettings> = settingsRepo.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val conversations: StateFlow<List<Conversation>> = chat.observeConversations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _activeConvoId = MutableStateFlow<Long?>(null)
    val activeConvoId: StateFlow<Long?> = _activeConvoId.asStateFlow()

    val activeMessages: StateFlow<List<Message>> = _activeConvoId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else chat.observeMessages(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Folder name bound to the active conversation. Each conversation
     *  gets its own auto-derived folder ("conv-<id>") unless the user
     *  has renamed it via the top-bar long-press dialog — new chats
     *  start with a fresh, empty workspace. The legacy global "default"
     *  folder is no longer reused. */
    val activeProjectFolder: StateFlow<String> = _activeConvoId
        .flatMapLatest { id ->
            if (id == null) flowOf("")
            else settingsRepo.convoFolder(id).map { stored ->
                if (stored.isBlank()) "conv-$id" else stored
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    /** Persist a project folder name for the currently active
     *  conversation. Empty string clears the binding. */
    fun setActiveProjectFolder(folder: String) {
        val id = _activeConvoId.value ?: return
        viewModelScope.launch { settingsRepo.setConvoFolder(id, folder) }
    }

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _streamError = MutableStateFlow<String?>(null)
    val streamError: StateFlow<String?> = _streamError.asStateFlow()

    private val _toolStatus = MutableStateFlow<String?>(null)
    val toolStatus: StateFlow<String?> = _toolStatus.asStateFlow()

    // Multi-stage workflow panel anchored above the composer. Resets at
    // the start of each user turn; each tool call appends a RUNNING step
    // that flips to DONE / ERROR when the tool finishes. The whole list
    // clears when the streaming turn ends.
    private val _workflow = MutableStateFlow<List<WorkflowStep>>(emptyList())
    val workflow: StateFlow<List<WorkflowStep>> = _workflow.asStateFlow()

    // Most recent usage breakdown, refreshed on every API `usage` event.
    private val _latestUsage = MutableStateFlow(UsageDetails())
    val latestUsage: StateFlow<UsageDetails> = _latestUsage.asStateFlow()

    init {
        // Repopulate the stats popup from the per-convo cache the moment
        // the user switches conversations — otherwise reopening the app
        // shows prompt/completion/cache all at 0 until the next stream
        // finishes (the bug the user's screenshot caught).
        viewModelScope.launch {
            _activeConvoId
                .flatMapLatest { id ->
                    if (id == null) flowOf(longArrayOf(0L, 0L, 0L, 0L, 0L))
                    else settingsRepo.convoUsage(id)
                }
                .collect { arr ->
                    _latestUsage.value = UsageDetails(
                        prompt = arr[0],
                        completion = arr[1],
                        total = arr[2],
                        cacheHit = arr[3],
                        cacheMiss = arr[4]
                    )
                }
        }
    }

    /** Save the in-memory [_latestUsage] under [convoId] so a future
     *  app start can repopulate the popup. */
    private fun persistUsage(convoId: Long) {
        val u = _latestUsage.value
        viewModelScope.launch {
            settingsRepo.setConvoUsage(convoId, u.prompt, u.completion, u.total, u.cacheHit, u.cacheMiss)
        }
    }

    // Per-conversation, app-session-scoped cumulative `total_tokens`
    // summed across every API call that ran in this session. Useful
    // for "how much did this conversation actually cost so far?"
    // Lost on app restart (we don't persist it to keep the schema
    // lean), but accurate while the chat stays open.
    private val _sessionTokenSpent = MutableStateFlow<Map<Long, Long>>(emptyMap())
    val sessionTokenSpent: StateFlow<Map<Long, Long>> = _sessionTokenSpent.asStateFlow()

    private fun bumpSessionSpend(convoId: Long, delta: Long) {
        if (delta <= 0L) return
        val cur = _sessionTokenSpent.value[convoId] ?: 0L
        _sessionTokenSpent.value = _sessionTokenSpent.value + (convoId to cur + delta)
    }

    private val _models = MutableStateFlow(ModelsState())
    val models: StateFlow<ModelsState> = _models.asStateFlow()

    private val _balance = MutableStateFlow(BalanceState())
    val balance: StateFlow<BalanceState> = _balance.asStateFlow()

    private val _openWebUrl = MutableStateFlow<String?>(null)
    val openWebUrl: StateFlow<String?> = _openWebUrl.asStateFlow()

    private val _openEditorPath = MutableStateFlow<String?>(null)
    val openEditorPath: StateFlow<String?> = _openEditorPath.asStateFlow()
    fun openEditor(path: String) { _openEditorPath.value = path }
    fun closeEditor() { _openEditorPath.value = null }

    private val _terminalOpen = MutableStateFlow(false)
    val terminalOpen: StateFlow<Boolean> = _terminalOpen.asStateFlow()
    fun openTerminal() { _terminalOpen.value = true }
    fun closeTerminal() { _terminalOpen.value = false }

    val voiceState: StateFlow<VoiceState> = voice.state
    private val _voiceVisible = MutableStateFlow(false)
    val voiceVisible: StateFlow<Boolean> = _voiceVisible.asStateFlow()

    /** Current conversation's tracked token usage, fed by the model's
     *  reported `usage` after every turn. */
    val contextUsage: StateFlow<ContextUsage> = _activeConvoId
        .flatMapLatest { id ->
            if (id == null) flowOf(ContextUsage())
            else conversations.map { list ->
                val c = list.firstOrNull { it.id == id }
                ContextUsage(
                    tokens = c?.contextTokens ?: 0L,
                    limit = contextLimitFor(c?.model ?: settings.value.model)
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ContextUsage())

    /** 摘要失败后的重试地板：contextTokens 涨过这个值才再试。见 maybeCompactContext。 */
    private val compactRetryFloor = java.util.concurrent.ConcurrentHashMap<Long, Long>()

    private val _compactStatus = MutableStateFlow<String?>(null)
    val compactStatus: StateFlow<String?> = _compactStatus.asStateFlow()

    private var streamJob: Job? = null

    // ---- Navigation -------------------------------------------------------

    fun openConversation(id: Long) {
        _activeConvoId.value = id
        // One-shot cleanup of any phantom empty-assistant rows that earlier
        // streams left behind (no content, no reasoning, no tool data). They
        // would otherwise be invisible in the UI but still take up message
        // slots in subsequent API requests.
        viewModelScope.launch {
            chat.getMessages(id)
                .filter {
                    it.role == Role.ASSISTANT &&
                        it.kind == MessageKind.TEXT &&
                        it.content.isBlank() &&
                        it.reasoning.isNullOrBlank() &&
                        it.toolData == null
                }
                .forEach { chat.deleteMessage(it.id) }
        }
    }
    fun clearActive() { _activeConvoId.value = null }
    fun openWebUrl(url: String) { _openWebUrl.value = url }
    fun closeWebUrl() { _openWebUrl.value = null }

    fun newConversation(open: Boolean = true) {
        viewModelScope.launch {
            val id = chat.createConversation(model = settings.value.model)
            // Inherit the persistent default-thinking flag so a new chat
            // starts pre-toggled.
            if (settings.value.defaultThinking) chat.setThinking(id, true)
            if (open) _activeConvoId.value = id
        }
    }

    fun deleteConversation(id: Long) {
        viewModelScope.launch {
            // 会话没了，它的容器也该收掉：停掉长驻 shell 和后台任务，
            // 顺手删掉内部存储上的可执行暂存区。项目文件本身不动 ——
            // 那是用户的东西，删会话不等于删代码。
            val folder = settingsRepo.convoFolder(id).first()
                .ifBlank { "conv-$id" }
            runCatching { containers.drop(folder) }
            // 用户在这个笔记里开的终端也一起收：它们和 AI 容器是并列的两个
            // 长驻 shell，只收一半的话，被删笔记的 bash（连同它 fork 出去的
            // 编译进程）会一直挂到进程死。peek() 不会把终端子系统建起来 ——
            // 从没开过终端的笔记走到这里应该什么都不做。
            runCatching {
                com.biji.notes.ui.terminal.TerminalRuntime.peek()?.closeFolder(folder)
            }
            chat.deleteConversation(id)
            if (_activeConvoId.value == id) _activeConvoId.value = null
            memory.invalidate()
        }
    }

    fun cancelStream() {
        streamJob?.cancel()
        streamJob = null
        _isStreaming.value = false
        _toolStatus.value = null
    }

    // ---- Voice ------------------------------------------------------------

    fun startVoice() {
        if (!voice.available()) {
            voice.reset()
            _streamError.value = "这台设备没有可用的语音识别"
            return
        }
        _voiceVisible.value = true
        voice.start()
    }

    fun stopVoice() { voice.stop() }
    fun cancelVoice() {
        voice.cancel()
        _voiceVisible.value = false
    }
    fun dismissVoice() {
        voice.reset()
        _voiceVisible.value = false
    }

    // ---- Sending ----------------------------------------------------------

    fun send(userText: String, attachments: List<StagedAttachment> = emptyList()) {
        val text = userText.trim()
        if (text.isEmpty() && attachments.isEmpty()) return

        streamJob = viewModelScope.launch {
            // The OUTERMOST safety net. Any uncaught throwable inside
            // `runChatTurn` (DB hiccup, JSON encoding glitch, tool
            // exec NPE, …) would otherwise propagate out of the coroutine
            // and hit Android's default uncaught-exception handler →
            // the process gets killed. That's what made the chat
            // "randomly" crash when a read/write tool ran. Surface the
            // error as a stream-error pill instead and keep the UI alive.
            try {
                val convoId = _activeConvoId.value ?: run {
                    val id = chat.createConversation(model = settings.value.model)
                    _activeConvoId.value = id
                    id
                }
                // Fold any staged attachments into the visible user
                // message so the model sees both the file metadata and
                // the user's prompt in the same turn.
                val composed = buildString {
                    if (attachments.isNotEmpty()) {
                        appendLine("【附件】")
                        for (a in attachments) {
                            val size = when {
                                a.size >= 1_048_576 -> "%.1f MB".format(a.size / 1_048_576.0)
                                a.size >= 1_024 -> "%.1f KB".format(a.size / 1_024.0)
                                else -> "${a.size} B"
                            }
                            appendLine("- ${a.displayName} (${a.relPath}, $size)")
                        }
                        appendLine()
                    }
                    append(text)
                }.trimEnd()
                val turnText = composed.ifEmpty { "（仅附件）" }
                chat.addMessage(convoId, Role.USER, turnText)
                memory.invalidate()

                _isStreaming.value = true
                _streamError.value = null
                _toolStatus.value = null
                _workflow.value = emptyList()

                try {
                    runChatTurn(convoId, userTurnText = turnText)
                    // 压缩挪到本轮**之后**。放在前面有两个问题：一是用户敲完回车
                    // 要先干等一次 completion（顶栏挂着「正在压缩…」，自己那句话
                    // 还没开始流），二是它读的 contextTokens 是上一轮的旧值。
                    // 放在后面两个都解决：usage 刚写回来是准的，压缩的耗时落在
                    // 用户读答复的时间里，下一轮开始时上下文已经是压好的。
                    // 阈值 78% 留的 22% 余量就是给「本轮先跑完再压」用的。
                    maybeCompactContext(convoId)
                } finally {
                    _isStreaming.value = false
                    _toolStatus.value = null
                    _workflow.value = emptyList()
                }

                runCatching { maybeSummarizeTitle(convoId) }
                runCatching { maybeNotify(convoId) }
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                // User pressed stop / left the screen — propagate so
                // the coroutine cancellation chain stays clean.
                throw cancel
            } catch (t: Throwable) {
                _isStreaming.value = false
                _toolStatus.value = null
                _workflow.value = emptyList()
                _streamError.value = "处理失败: ${t.message ?: t.javaClass.simpleName}"
            }
        }
    }

    private suspend fun runChatTurn(convoId: Long, userTurnText: String) {
        val s = settings.value
        var iters = 0
        while (iters++ < MAX_ITERS) {
            val msgs = buildRequestMessages(convoId, userTurnText)
            val placeholderId = chat.addMessage(convoId, Role.ASSISTANT, "")

            val contentBuf = StringBuilder()
            val reasoningBuf = StringBuilder()
            var toolCalls: List<ToolCall> = emptyList()
            var lastUsageTotal: Long = -1L
            var sawError: String? = null
            // 每个 token 写一次 DB → Flow emit → 整个聊天列表重组 +
            // 末条消息全量 Markdown 重解析，一秒几十次，CPU 直接顶
            // 满。攒够 STREAM_FLUSH_MS 再落库，观感一样但开销降一个
            // 数量级；收尾时无条件 flush 保证不丢字。
            var lastFlushAt = 0L
            var pendingFlush = false
            suspend fun flushStream(force: Boolean) {
                val now = System.currentTimeMillis()
                if (!force && now - lastFlushAt < STREAM_FLUSH_MS) {
                    pendingFlush = true
                    return
                }
                lastFlushAt = now
                pendingFlush = false
                chat.updateAssistantStream(
                    placeholderId,
                    contentBuf.toString(),
                    reasoningBuf.takeIf { it.isNotEmpty() }?.toString()
                )
            }

            client.stream(
                baseUrl = s.baseUrl,
                apiKey = s.apiKey,
                model = s.model,
                messages = msgs,
                temperature = s.temperature,
                tools = Tools.definitions(
                    webSearch = s.webSearch,
                    sandbox = s.developerMode,
                    // root 默认关，关着的时候 run_shell_command 的定义就不发了 ——
                    // 800 多字节，还让模型多一个选错的机会（它每次 fork 新 sh，
                    // 拿它跑多步流程必踩坑）。CONTAINER_BRIEF 那边跟着这同一个开关。
                    useRoot = s.useRoot
                ).takeIf { it.isNotEmpty() }
            ).collect { ev ->
                when (ev) {
                    is ChatEvent.Delta -> {
                        contentBuf.append(ev.content)
                        flushStream(force = false)
                    }
                    is ChatEvent.Reasoning -> {
                        reasoningBuf.append(ev.content)
                        flushStream(force = false)
                    }
                    is ChatEvent.ToolCalls -> { toolCalls = ev.calls }
                    is ChatEvent.Usage -> {
                        lastUsageTotal = ev.total
                        _latestUsage.value = UsageDetails(
                            prompt = ev.prompt,
                            completion = ev.completion,
                            total = ev.total,
                            cacheHit = ev.cacheHit,
                            cacheMiss = ev.cacheMiss
                        )
                        persistUsage(convoId)
                        bumpSessionSpend(convoId, ev.total)
                    }
                    ChatEvent.Done -> Unit
                    is ChatEvent.Error -> { sawError = ev.message }
                }
            }

            // 收尾：把节流期间攒下的最后一段落库。
            if (pendingFlush || contentBuf.isNotEmpty() || reasoningBuf.isNotEmpty()) {
                flushStream(force = true)
            }

            if (lastUsageTotal > 0) {
                chat.setContextTokens(convoId, lastUsageTotal)
            }

            // If the stream produced nothing at all (no content, no reasoning,
            // no tool calls) — likely an API/proxy failure — remove the empty
            // placeholder so it doesn't pollute later request history, and
            // surface the error pill to the user. Always prefer the concrete
            // server error over the generic fallback.
            if (contentBuf.isEmpty() && reasoningBuf.isEmpty() && toolCalls.isEmpty()) {
                chat.deleteMessage(placeholderId)
                _streamError.value = sawError
                    ?: "模型没有返回内容"
                return
            }
            if (sawError != null) _streamError.value = sawError

            if (toolCalls.isEmpty()) return

            chat.setToolData(placeholderId, encodeToolCalls(toolCalls))

            for (call in toolCalls) {
                _toolStatus.value = friendlyToolStatus(call)
                val stepLabel = workflowLabel(call)
                _workflow.value = _workflow.value + WorkflowStep(
                    tool = call.name,
                    label = stepLabel,
                    state = WorkflowStepState.RUNNING
                )
                val (forModel, uiJson) = runCatching {
                    toolExec.run(
                        call,
                        projectFolder = activeProjectFolder.value,
                        useRoot = settings.value.useRoot
                    )
                }
                    .getOrElse { e ->
                        "工具失败: ${e.message}" to buildJsonObject {
                            put("kind", "error")
                            put("message", e.message ?: e.javaClass.simpleName)
                        }
                    }
                val done = uiJson["error"] == null
                _workflow.value = _workflow.value.toMutableList().also { list ->
                    val idx = list.indexOfLast { it.state == WorkflowStepState.RUNNING }
                    if (idx >= 0) {
                        list[idx] = list[idx].copy(
                            state = if (done) WorkflowStepState.DONE else WorkflowStepState.ERROR
                        )
                    }
                }
                chat.addMessage(
                    convoId = convoId,
                    role = Role.TOOL,
                    content = forModel,
                    kind = MessageKind.TOOL_RESULT,
                    toolData = json.encodeToString(JsonObject.serializer(), uiJson),
                    toolCallId = call.id
                )
            }
            _toolStatus.value = null
        }
        // Fell off the end of the loop while the model was still
        // requesting more tools. Force a final tools-less synthesis pass
        // so the user gets a real answer instead of silence.
        // 把本轮用户输入一起传下去：buildRequestMessages 拿它检索记忆，
        // 给空串会检索不到、记忆块凭空消失，请求形状又变一次，白丢一次缓存。
        forceFinalSynthesis(convoId, userTurnText)
    }

    /**
     * Last-resort pass after `runChatTurn` exhausts its tool-call budget.
     * Re-sends the full conversation history with `tools = null` plus a
     * one-shot system nudge instructing the model to wrap up, so the
     * user always gets a textual answer even when the model would have
     * happily kept calling tools forever.
     */
    private suspend fun forceFinalSynthesis(convoId: Long, userTurnText: String) {
        val s = settings.value
        val base = buildRequestMessages(convoId, userTurnText)
        val nudge = ChatMessageDto(
            role = Role.SYSTEM,
            content = "已达到本轮工具调用上限。请基于上面已有的工具结果直接给出最终答复，不要再调用任何工具。"
        )
        val placeholderId = chat.addMessage(convoId, Role.ASSISTANT, "")
        val contentBuf = StringBuilder()
        val reasoningBuf = StringBuilder()
        var sawError: String? = null
        var lastUsageTotal: Long = -1L
        client.stream(
            baseUrl = s.baseUrl,
            apiKey = s.apiKey,
            model = s.model,
            messages = base + nudge,
            temperature = s.temperature,
            tools = null
        ).collect { ev ->
            when (ev) {
                is ChatEvent.Delta -> {
                    contentBuf.append(ev.content)
                    chat.updateAssistantStream(
                        placeholderId,
                        contentBuf.toString(),
                        reasoningBuf.takeIf { it.isNotEmpty() }?.toString()
                    )
                }
                is ChatEvent.Reasoning -> {
                    reasoningBuf.append(ev.content)
                    chat.updateAssistantStream(
                        placeholderId,
                        contentBuf.toString(),
                        reasoningBuf.toString()
                    )
                }
                is ChatEvent.Usage -> {
                    lastUsageTotal = ev.total
                    _latestUsage.value = UsageDetails(
                        prompt = ev.prompt,
                        completion = ev.completion,
                        total = ev.total,
                        cacheHit = ev.cacheHit,
                        cacheMiss = ev.cacheMiss
                    )
                    persistUsage(convoId)
                    bumpSessionSpend(convoId, ev.total)
                }
                is ChatEvent.Error -> { sawError = ev.message }
                else -> Unit
            }
        }
        if (lastUsageTotal > 0) chat.setContextTokens(convoId, lastUsageTotal)
        if (contentBuf.isEmpty() && reasoningBuf.isEmpty()) {
            chat.deleteMessage(placeholderId)
            _streamError.value = sawError
                ?: "工具循环达到上限，模型仍未输出最终答复。请重试或换一个 prompt。"
        } else if (sawError != null) {
            _streamError.value = sawError
        }
    }

    /** Friendly per-step label rendered inside the workflow panel. */
    private fun workflowLabel(call: com.biji.notes.net.ToolCall): String {
        val args = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(call.arguments).jsonObject
        }.getOrNull()
        return when (call.name) {
            Tools.WEB_SEARCH -> {
                val q = args?.get("query")?.let { it as? kotlinx.serialization.json.JsonPrimitive }
                    ?.content.orEmpty()
                if (q.isBlank()) "联网搜索" else "联网搜索 “$q”"
            }
            Tools.READ_URL -> {
                val url = args?.get("url")?.let { it as? kotlinx.serialization.json.JsonPrimitive }
                    ?.content.orEmpty()
                val host = runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("")
                if (host.isBlank()) "网页解析" else "网页解析 $host"
            }
            Tools.LIST_DIRECTORY -> {
                val p = args?.get("path")?.let { it as? kotlinx.serialization.json.JsonPrimitive }
                    ?.content.orEmpty()
                "列目录 ${if (p.isBlank()) "/" else p}"
            }
            Tools.READ_FILE -> {
                val p = args?.get("path")?.let { it as? kotlinx.serialization.json.JsonPrimitive }
                    ?.content.orEmpty()
                "读文件 $p"
            }
            Tools.WRITE_FILE -> {
                val p = args?.get("path")?.let { it as? kotlinx.serialization.json.JsonPrimitive }
                    ?.content.orEmpty()
                "写文件 $p"
            }
            Tools.RUN_SHELL -> {
                val c = args?.get("command")?.let { it as? kotlinx.serialization.json.JsonPrimitive }
                    ?.content.orEmpty()
                "运行 $ ${c.take(48)}${if (c.length > 48) "…" else ""}"
            }
            Tools.CONTAINER_EXEC -> {
                val c = args?.get("command")?.let { it as? kotlinx.serialization.json.JsonPrimitive }
                    ?.content.orEmpty()
                "容器 $ ${c.take(48)}${if (c.length > 48) "…" else ""}"
            }
            Tools.CONTAINER_TASK -> {
                val act = args?.get("action")?.let { it as? kotlinx.serialization.json.JsonPrimitive }
                    ?.content.orEmpty()
                val c = args?.get("command")?.let { it as? kotlinx.serialization.json.JsonPrimitive }
                    ?.content.orEmpty()
                when (act) {
                    "start" -> "后台任务 $ ${c.take(40)}${if (c.length > 40) "…" else ""}"
                    "poll" -> "拉取任务输出"
                    "stop" -> "停止任务"
                    else -> "查看后台任务"
                }
            }
            Tools.CONTAINER_INFO -> "查看容器状态"
            Tools.CONTAINER_MANAGE -> {
                val act = args?.get("action")?.let { it as? kotlinx.serialization.json.JsonPrimitive }
                    ?.content.orEmpty()
                when (act) {
                    "init" -> "初始化容器"
                    "reset" -> "重置容器"
                    else -> "容器会话管理"
                }
            }
            Tools.CONTAINER_ENV -> "容器环境变量"
            else -> call.name
        }
    }

    private suspend fun buildRequestMessages(
        convoId: Long,
        userTurnText: String
    ): List<ChatMessageDto> {
        val s = settings.value
        val convo = chat.getConversation(convoId)
        val live = chat.getLiveMessages(convoId)

        // Always echo reasoning_content back to the API. The official
        // DeepSeek endpoint silently strips it on input, while
        // v4-flash / one-api proxies require it for thinking mode and
        // fail with `HTTP 400 · The reasoning_content in the thinking
        // mode must be passed back to the API.` if it's missing. The
        // safe universal default is to always include it whenever the
        // streamed response carried one.
        val keepReasoning = true

        // 老工具结果折叠。getLiveMessages 没有 LIMIT，toDto 又是原样回传，
        // 于是这一轮里每多调一次工具，前面**所有**工具结果都要重发一遍 ——
        // 开销是调用次数的平方。一条 container_exec 的 stdout 上限就有 12000 字，
        // 8 次调用跑满是 24 万 token，128K 的窗口根本装不下，会在第五六轮
        // 直接吃 HTTP 400。这不只是钱的问题，是编译大工程的会话一定会失败。
        //
        // 保护窗口按**字符预算**算，不按条数。一轮里可能是十条几百字的 ls，
        // 也可能是三条跑满 12000 字的编译输出 —— 按条数保护对后者等于没保护
        // （留 6 条 × 12000 字，8 次循环里根本没有一条会被折叠，二次方原样还在）。
        // 按预算算，两种形状都收敛到同一个上限：每次请求里工具结果那部分
        // 最多 TOOL_KEEP_BUDGET_CHARS 的全文 + 若干条几百字的折叠残留，
        // 整轮的总量从平方掉回线性。
        //
        // 也不能只看预算：预算再紧，模型**刚拿到**的那一两条必须是完整的，
        // 那正是它下一步要读的东西，折掉等于让这一轮白跑。
        //
        // **本轮刚拿到的那一批一律不折**。工具调用可以是并行的（一条
        // assistant 消息带 N 个 tool_calls，runChatTurn 里那个 for 循环挨个跑），
        // 所以"最新的两条"这个下限接不住 N=5 的形状：后三条会在模型第一次
        // 读到它们之前就被折掉，而那正是它这一步要看的编译输出 —— 折了等于
        // 让这一轮白跑，它只能重跑一遍命令。以最后一条带 tool_calls 的
        // assistant 为界，界之后的全文照发；预算只管更早的那些。
        val currentRoundFrom = live.indexOfLast {
            it.role == Role.ASSISTANT && it.toolData != null
        }
        val foldIds = HashSet<Long>()
        var keptCount = 0
        var budget = TOOL_KEEP_BUDGET_CHARS
        for (i in live.indices.reversed()) {
            val m = live[i]
            // 两种消息都吃预算：tool 结果吃 content，assistant 载体吃
            // toolData（write_file 的 content 就在里面）。只管前者的话，
            // 一次 200 KB 的写入会绕开整套预算，之后每轮都原样重发。
            val isResult = m.kind == MessageKind.TOOL_RESULT
            val isCarrier = m.role == Role.ASSISTANT && m.toolData != null
            if (!isResult && !isCarrier) continue
            // 本轮的一律不折。载体自己也算「本轮」—— 它就是这一轮的调用。
            if (currentRoundFrom >= 0 && (if (isCarrier) i >= currentRoundFrom else i > currentRoundFrom)) continue
            val size = if (isResult) m.content.length else (m.toolData?.length ?: 0)
            if (keptCount < TOOL_KEEP_MIN || budget > 0) {
                keptCount++
                budget -= size
            } else {
                foldIds += m.id
            }
        }
        val dtos = live.mapNotNull { toDto(it, keepReasoning, fold = it.id in foldIds) }

        // system 里只放**稳定**的块：内容与这一轮说了什么无关，顺序固定。
        // 这样 system + tools + 全部历史构成一个只增不改的前缀，前缀缓存才有
        // 得命中。
        val systemBlocks = mutableListOf<String>()
        if (s.systemPrompt.isNotBlank()) systemBlocks += s.systemPrompt
        if (convo?.thinking == true && s.model.contains("reason").not()) {
            systemBlocks += "请先在 <think>…</think> 中详细列出你的推理过程，再给最终答案。"
        }
        if (s.webSearch) {
            systemBlocks += "当用户问到需要实时、最新或不确定的信息时，主动调用 web_search；" +
                "当确认某个网页有用时再调用 read_url 抓正文。"
        }
        if (s.developerMode) {
            systemBlocks += if (s.useRoot) "$CONTAINER_BRIEF\n$ROOT_SHELL_BRIEF" else CONTAINER_BRIEF
        }

        // 记忆是 BM25 按**这一轮的用户输入**检索出来的三条片段，每轮都不一样。
        // 它原来是 systemBlocks 的第一项（systemPrompt 默认空、thinking 默认关），
        // 也就是说 prompt 的第 0 个 token 每轮都在变。前缀缓存按 token 前缀块
        // 匹配，第一块 miss 后面全部作废 —— 五千 token 的工具定义、
        // CONTAINER_BRIEF、整条对话历史，一轮都省不下，命中率结构性锁死在 0。
        // 命中的计费大约是未命中的十分之一，长会话里这是十倍的差价，比压缩
        // 工具描述省下的那点大一个数量级。
        //
        // 效果好不好不用猜：DeepSeekClient 已经在解析 prompt_cache_hit_tokens /
        // prompt_cache_miss_tokens，统计弹窗里就能看到 hit/miss 比。
        val memoryText = if (s.longMemory) {
            memory.memoryPrompt(memory.retrieve(userTurnText, excludeConvoId = convoId, k = 3))
        } else null

        val systemDto = if (systemBlocks.isNotEmpty()) {
            ChatMessageDto(role = Role.SYSTEM, content = systemBlocks.joinToString("\n\n"))
        } else null
        val head = listOfNotNull(systemDto)
        if (memoryText == null) return head + dtos

        // 插在**最后一条 user 之前**，不是整个列表末尾：工具循环跑起来之后
        // 列表末尾是 assistant(tool_calls) + tool 结果，往它们中间塞一条 system
        // 会破坏「tool 消息必须紧跟带 tool_calls 的 assistant」这个约束，直接 400。
        // 插在 user 之前还顺带保证：一轮工具循环里这个块的位置和内容都不动，
        // 循环内那 8 次请求彼此的前缀完全一致。
        val at = dtos.indexOfLast { it.role == Role.USER }
        if (at < 0) return head + dtos
        val mem = ChatMessageDto(role = Role.SYSTEM, content = memoryText)
        return head + dtos.subList(0, at) + mem + dtos.subList(at, dtos.size)
    }

    /**
     * 把一条老的工具结果压成「头几行 + 省略标记 + 尾几行」。
     *
     * 留头也留尾是有讲究的：头几行是命令回显和第一条报错（`$ cmd (cwd=…, exit=…)`
     * 那行就在最前面），尾几行是最终结论和退出码；中间那几百行 make 输出模型
     * 基本不回头看。真要看，重跑一次比每轮重发几万 token 便宜得多。
     *
     * 只动发给模型的那一份。数据库里存的还是全文，聊天界面照旧显示完整输出。
     */
    private fun foldToolResult(text: String): String {
        if (text.length <= TOOL_FOLD_MIN_CHARS) return text
        val lines = text.lines()
        if (lines.size <= TOOL_FOLD_HEAD_LINES + TOOL_FOLD_TAIL_LINES + 1) {
            // 行少但单行极长：压过的 JSON、base64、没有换行的日志。按行折没意义，
            // 改成掐两头。
            return clipHeadTail(text)
        }
        val head = lines.take(TOOL_FOLD_HEAD_LINES)
        val tail = lines.takeLast(TOOL_FOLD_TAIL_LINES)
        val omitted = lines.size - head.size - tail.size
        val folded = (head + "…（中间 $omitted 行已折叠，需要完整输出请重跑该命令）…" + tail)
            .joinToString("\n")
        // 按行折**不保证变小**：一份 64 KB 的压缩 JS 可能只有 25 行，折掉中间
        // 7 行还剩 60 KB，TOOL_KEEP_BUDGET_CHARS 就完全不是上限了。所以最后
        // 一定要过一道绝对字符闸。
        return if (folded.length <= TOOL_FOLD_MAX_CHARS) folded else clipHeadTail(folded)
    }

    /** 按字符掐两头，给"行少但每行极长"的内容兜底。 */
    private fun clipHeadTail(text: String): String {
        val head = text.take(TOOL_FOLD_MAX_CHARS * 2 / 3)
        val tail = text.takeLast(TOOL_FOLD_MAX_CHARS / 3)
        val cut = text.length - head.length - tail.length
        if (cut <= 0) return text
        return head + "\n…（中间 $cut 个字符已折叠，需要完整输出请重跑）…\n" + tail
    }

    private fun toDto(m: Message, keepReasoning: Boolean, fold: Boolean = false): ChatMessageDto? = when {
        m.kind == MessageKind.TOOL_RESULT -> ChatMessageDto(
            role = Role.TOOL,
            content = if (fold) foldToolResult(m.content) else m.content,
            toolCallId = m.toolCallId
        )
        m.role == Role.ASSISTANT && m.toolData != null -> ChatMessageDto(
            role = Role.ASSISTANT,
            content = m.content,
            // v4-flash and other thinking-mode proxies require the
            // reasoning_content to be echoed on the assistant turn that
            // owns the tool_calls — otherwise the next request fails
            // with `HTTP 400 · The reasoning_content in the thinking
            // mode must be passed back to the API.`
            reasoningContent = if (keepReasoning && !m.reasoning.isNullOrBlank()) m.reasoning else null,
            // arguments 里的大块正文（write_file 的 content、container_exec 的
            // stdin）是上下文预算里最后一个没有闸的入口：一次写 200 KB 的文件，
            // 那 200 KB 就原样躺在之后**每一次**请求里。
            //
            // 曾经的结论是「不能折」—— 理由是 arguments 必须是一段成立的 JSON，
            // 掐掉中间就不合法了，代理端直接 400。那个理由只否掉了「按字符截断
            // 原始串」这一种做法。**重新解析、只替换某个字段的值、再编码回去**，
            // 出来仍然是合法 JSON，而且字段类型不变（还是 string），schema 校验
            // 也过得去。
            //
            // 只对已经出了预算窗口的老消息做。模型刚写完那一轮看得到原文；
            // 真要回头看更早写了什么，read_file 一句话的事。
            toolCalls = decodeToolCalls(m.toolData, fold)
        )
        m.role == Role.ASSISTANT && m.content.isBlank() && m.toolData == null -> null
        else -> ChatMessageDto(
            role = m.role,
            content = m.content,
            reasoningContent = if (keepReasoning && m.role == Role.ASSISTANT &&
                !m.reasoning.isNullOrBlank()
            ) m.reasoning else null
        )
    }

    /** When the context window starts to fill up, summarise the older half
     *  of the conversation into a single CONTEXT_SUMMARY message and mark
     *  the originals as archived. The compacted summary then becomes the
     *  prefix of subsequent API calls. */
    private suspend fun maybeCompactContext(convoId: Long) {
        val s = settings.value
        if (s.apiKey.isBlank()) return
        val convo = chat.getConversation(convoId) ?: return
        val limit = contextLimitFor(convo.model)
        if (convo.contextTokens.toDouble() < limit * COMPACT_THRESHOLD) return
        compactRetryFloor[convoId]?.let { if (convo.contextTokens < it) return }

        val live = chat.getLiveMessages(convoId)
        // 归档的是一整段**连续前缀** —— 切点之前的所有消息，包括夹在中间的
        // tool 结果。曾经这里归档的是「过滤掉 TOOL_RESULT 之后的子集」，
        // 于是跑过 50 条命令的会话，摘要把寒的压没了，几百 KB 的 stdout
        // 一条不少地留着。
        //
        // 这里只用正文条数做一道「太短就别压」的闸：会话还没几个来回时压缩
        // 得不偿失，摘要本身就要几百 token。
        if (live.count {
                it.kind == MessageKind.TEXT && (it.role == Role.USER || it.role == Role.ASSISTANT)
            } < 8
        ) return

        // 切点按**预算**走，不按条数。
        //
        // 原来是固定「留最后 4 条正文」。问题在于这 4 条的实际体积能差两个数量级：
        // 四句闲聊是几百 token，四轮各带一条跑满的 container_exec（单条 stdout
        // 上限 12000 字符）是两三万。前者压完还剩一点没压干净就又到阈值，
        // 后者一刀砍掉几乎整段可用上下文，模型下一句就开始「忘事」。
        //
        // 改成从末尾往前累加，攒够 limit 的 KEEP_AFTER_COMPACT 就停。这样压完
        // 剩下的量是可预期的，和聊天内容长什么样无关。
        val keepBudget = (limit * KEEP_AFTER_COMPACT).toLong()
        var acc = 0L
        var cut = live.size
        for (i in live.indices.reversed()) {
            acc += estimateTokens(live[i])
            if (acc > keepBudget && i < live.size - MIN_KEEP_MESSAGES) break
            cut = i
        }
        // 预算算下来「一条都不用压」，但 contextTokens 又确实过了阈值 —— 说明占
        // 位的不在消息里（工具定义、系统提示、服务端自己加的东西）。这时候按预算
        // 走会每轮静默返回、上下文继续涨到请求被拒。退回按条数压：留最后
        // MIN_KEEP_MESSAGES 条，保证每次触发都真的往前走一步。
        if (cut == 0) cut = (live.size - MIN_KEEP_MESSAGES).coerceAtLeast(0)
        // 切点不能落在一轮工具调用中间。assistant(tool_calls) 和它后面那几条
        // tool 结果是一对：归档了前者、留下后者，剩下的就是没有配对的 tool 消息，
        // 服务端直接回 400（反过来不会发生，连续前缀保证 assistant 一定在前）。
        while (cut < live.size && live[cut].kind == MessageKind.TOOL_RESULT) cut++
        // 一条都不归档就没有意义；全归档会把用户刚发的那句也吞掉。
        if (cut <= 0 || cut >= live.size) return
        val toArchive = live.take(cut)
        if (toArchive.count { it.kind == MessageKind.TEXT } < 2) return

        _compactStatus.value = "上下文 ${(convo.contextTokens.toDouble() / limit * 100).toInt()}%，正在压缩早期对话…"
        val transcript = toArchive.joinToString("\n\n") { m ->
            val who = when {
                m.kind == MessageKind.CONTEXT_SUMMARY -> "早期摘要"
                m.kind == MessageKind.TOOL_RESULT -> "工具结果"
                m.role == Role.USER -> "用户"
                m.role == Role.ASSISTANT -> "助手"
                else -> m.role
            }
            // 工具结果掐得比正文狠得多：摘要要的是"跑了什么、成没成"，
            // 不是那 12000 字 stdout 本身。
            val cap = if (m.kind == MessageKind.TOOL_RESULT) 300 else 800
            "$who: ${m.content.take(cap)}"
        }

        val summary = client.complete(
            baseUrl = s.baseUrl,
            apiKey = s.apiKey,
            // 用当前会话正在用的模型，而不是写死 "deepseek-chat"。这个 app 允许
            // 改 baseUrl 指到任意兼容端点，那边不一定有这个模型名 —— 一旦 404，
            // 压缩就永远失败，上下文一路涨到请求被服务端拒绝为止。贵一点，
            // 但至少是能跑通的。
            model = s.model,
            system = "请把下面这段多轮对话压缩成一段不超过 500 字的客观摘要，" +
                "保留关键事实、已确认的偏好、未决的问题与代办，去除寒的。直接输出摘要正文，不要加标题。",
            user = transcript
        ).getOrNull()?.takeIf { it.isNotBlank() }

        _compactStatus.value = null
        if (summary.isNullOrBlank()) {
            // 摘要没出来（网络断了、代理不认 deepseek-chat、余额没了…）什么状态
            // 都没改，于是**下一轮进来还是超阈值，再试一次**。用户每发一句话就
            // 白烧一次 completion，而且顶栏闪一下「正在向量化…」再消失。
            // 退避到下次上下文又涨了 5% 才重试。
            compactRetryFloor[convoId] = convo.contextTokens + (limit * 0.05).toLong()
            return
        }
        compactRetryFloor.remove(convoId)

        chat.archive(toArchive.map { it.id })
        // 摘要落在「归档段末尾 / 现存段开头」的交界上。
        //
        // 取 live[cut].createdAt - 1 而不是 toArchive.last().createdAt：排序是
        // (createdAt ASC, id ASC)，而摘要的 id 一定是全表最大的。两条消息落在同
        // 一毫秒时，用后者会让摘要排到第一条现存消息**后面** —— 请求里它就不再是
        // 前缀，模型会先读到半截对话再读到摘要。减一毫秒是唯一不依赖 id 的写法。
        val boundaryTs = (live.getOrNull(cut)?.createdAt ?: (toArchive.last().createdAt + 1)) - 1
        chat.addMessage(
            convoId = convoId,
            role = Role.SYSTEM,
            content = "【早期对话摘要】\n$summary",
            kind = MessageKind.CONTEXT_SUMMARY,
            createdAt = boundaryTs
        )
        // Reset token estimate; the next API call will rewrite it from `usage`.
        chat.setContextTokens(convoId, summary.length.toLong() / 2)
        memory.invalidate()
    }

    /** 粗估一条消息在请求里占多少 token。中英混排按 2 字符 1 token 折 ——
     *  只用来做切点决策，宁可估多不估少，估多的后果只是多压一点。 */
    private fun estimateTokens(m: Message): Long {
        val n = m.content.length + (m.reasoning?.length ?: 0)
        return (n / 2).toLong() + 8   // 每条的角色 / 分隔符开销
    }

    private fun encodeToolCalls(calls: List<ToolCall>): String {
        val arr = buildJsonArray {
            calls.forEach { c ->
                add(buildJsonObject {
                    put("id", c.id)
                    put("name", c.name)
                    put("arguments", c.arguments)
                })
            }
        }
        return arr.toString()
    }

    /**
     * 把 arguments 里超长的字符串字段换成一句说明，其余原样。
     *
     * 不按 key 白名单挑（write_file.content / container_exec.stdin …）：工具会
     * 不断加，白名单必然漏。按**长度**挑更稳 —— 路径、命令名、枚举这些语义字段
     * 天然就短，够得着这个阈值的只可能是正文。
     *
     * 解析失败就原样返回：宁可多花点 token，也不能把一段本来成立的 JSON 弄坏。
     */
    private fun foldToolCallArgs(raw: String): String = runCatching {
        val o = json.parseToJsonElement(raw) as? JsonObject ?: return raw
        var touched = false
        val out = buildJsonObject {
            for ((k, v) in o) {
                val text = (v as? kotlinx.serialization.json.JsonPrimitive)
                    ?.takeIf { it.isString }?.content
                if (text != null && text.length > ARG_FOLD_MIN_CHARS) {
                    touched = true
                    put(k, "（${text.length} 字符，已从上下文中省略）")
                } else {
                    put(k, v)
                }
            }
        }
        if (touched) out.toString() else raw
    }.getOrElse { raw }

    private fun decodeToolCalls(raw: String, fold: Boolean = false): List<ToolCall> = runCatching {
        json.parseToJsonElement(raw)
            .let { it as? kotlinx.serialization.json.JsonArray ?: return emptyList() }
            .mapNotNull { el ->
                val o = (el as? JsonObject) ?: return@mapNotNull null
                val id = o["id"]?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content
                    ?: return@mapNotNull null
                val name = o["name"]?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content
                    ?: return@mapNotNull null
                val args = o["arguments"]?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content
                    ?: ""
                ToolCall(id, name, if (fold) foldToolCallArgs(args) else args)
            }
    }.getOrElse { emptyList() }

    private fun friendlyToolStatus(call: ToolCall): String = when (call.name) {
        Tools.WEB_SEARCH -> "正在联网搜索…"
        Tools.READ_URL -> "正在抓取网页…"
        else -> "正在调用 ${call.name}…"
    }

    private suspend fun maybeSummarizeTitle(convoId: Long) {
        val s = settings.value
        if (s.apiKey.isBlank()) return
        val convo = chat.getConversation(convoId) ?: return
        if (convo.title != "新对话") return

        val turns = chat.getMessages(convoId).filter { it.kind == MessageKind.TEXT }
        val firstUser = turns.firstOrNull { it.role == Role.USER }?.content ?: return
        val firstAssistant = turns.firstOrNull { it.role == Role.ASSISTANT && it.content.isNotBlank() }
            ?.content ?: return

        val title = client.summarizeTitle(s.baseUrl, s.apiKey, firstUser, firstAssistant)
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: firstUser.take(20)
        chat.rename(convoId, title)
    }

    private suspend fun maybeNotify(convoId: Long) {
        if (!settings.value.notify) return
        if (isForeground()) return
        val convo = chat.getConversation(convoId) ?: return
        val lastAssistant = chat.getMessages(convoId)
            .lastOrNull { it.role == Role.ASSISTANT && it.content.isNotBlank() } ?: return
        notifier.notifyDone(
            convoId = convoId,
            title = convo.title,
            preview = lastAssistant.content.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() } ?: lastAssistant.content.take(120)
        )
    }

    fun dismissError() { _streamError.value = null }

    // ---- Settings / models ------------------------------------------------

    fun setApiKey(value: String) = viewModelScope.launch { settingsRepo.setApiKey(value) }
    fun setBaseUrl(value: String) = viewModelScope.launch { settingsRepo.setBaseUrl(value) }
    fun setModel(value: String) = viewModelScope.launch { settingsRepo.setModel(value) }
    /** Toggle the *persistent* default "deep thinking" flag — applies to
     *  every new conversation. Per-conversation override is still
     *  available via [setConversationThinking]. */
    fun setDefaultThinking(on: Boolean) = viewModelScope.launch {
        settingsRepo.setDefaultThinking(on)
    }
    fun setDeveloperMode(on: Boolean) = viewModelScope.launch {
        settingsRepo.setDeveloperMode(on)
    }
    fun setUseRoot(on: Boolean) = viewModelScope.launch { settingsRepo.setUseRoot(on) }

    /**
     * Import the file behind [uri] into the active conversation's
     * project folder and announce it via a USER message so the model
     * sees there's an attachment available to work with. Returns the
     * in-project relative path (e.g. "report.zip") so callers can
     * surface a toast / chip if they want to.
     */
    /** A staged attachment held in composer state — copied to the
     *  conversation folder up-front so the composer chip can show
     *  size, then folded into the next USER message when the user
     *  hits send. */
    data class StagedAttachment(val relPath: String, val size: Long, val displayName: String)

    /** Copy [uri] into the active conversation's folder and return a
     *  [StagedAttachment] for the composer to remember. Doesn't post
     *  anything yet — the chip lives in the composer until the user
     *  sends the message it's attached to. */
    suspend fun stageAttachment(uri: android.net.Uri, displayName: String?): StagedAttachment? {
        val convoId = _activeConvoId.value ?: run {
            val id = chat.createConversation(model = settings.value.model)
            _activeConvoId.value = id
            id
        }
        // Make sure the conversation has a folder by the time we ask
        // the sandbox to write — `activeProjectFolder` flow may not
        // have caught up immediately after a brand-new convo.
        val folder = activeProjectFolder.value.ifEmpty { "conv-$convoId" }
        return runCatching {
            val (relPath, bytes) = sandbox.importUri(folder, uri, displayName)
            StagedAttachment(relPath, bytes, displayName ?: relPath)
        }.getOrElse { err ->
            _streamError.value = "附件上传失败: ${err.message ?: err.javaClass.simpleName}"
            null
        }
    }

    /** Convenience for tests / non-composer call paths that just want
     *  the legacy "drop a file → AI sees a USER notice" behaviour. */
    fun attachFile(uri: android.net.Uri, displayName: String?) {
        viewModelScope.launch {
            val staged = stageAttachment(uri, displayName) ?: return@launch
            send("", listOf(staged))
        }
    }

    /** One-shot root probe — fires `su -c id` and reports the result.
     *  Surfaces the standard Magisk / SuperSU prompt the first time. */
    suspend fun probeRoot(): Boolean = sandbox.probeRoot()
    fun setSystemPrompt(v: String) = viewModelScope.launch { settingsRepo.setSystemPrompt(v) }
    fun setTemperature(v: Float) = viewModelScope.launch { settingsRepo.setTemperature(v) }
    fun setWebSearch(v: Boolean) = viewModelScope.launch { settingsRepo.setWebSearch(v) }
    fun setLongMemory(v: Boolean) = viewModelScope.launch { settingsRepo.setLongMemory(v) }
    fun setNotify(v: Boolean) = viewModelScope.launch { settingsRepo.setNotify(v) }

    fun setConversationThinking(on: Boolean) {
        val id = _activeConvoId.value ?: return
        viewModelScope.launch { chat.setThinking(id, on) }
    }

    fun refreshModels() {
        val s = settings.value
        if (s.apiKey.isBlank()) {
            _models.value = ModelsState(loading = false, list = emptyList(), error = "未配置 API Key")
            return
        }
        _models.value = _models.value.copy(loading = true, error = null)
        viewModelScope.launch {
            val result = client.listModels(s.baseUrl, s.apiKey)
            _models.value = result.fold(
                onSuccess = { ModelsState(loading = false, list = it, error = null) },
                onFailure = { ModelsState(loading = false, list = _models.value.list, error = it.message) }
            )
        }
    }

    /** Auto-refresh models if the cached list is stale. Safe to call on
     *  screen open. */
    fun ensureModelsLoaded() {
        val s = _models.value
        if (s.loading) return
        if (s.list.isNotEmpty()) return
        refreshModels()
    }

    fun refreshBalance() {
        val s = settings.value
        _balance.value = _balance.value.copy(loading = true, error = null)
        viewModelScope.launch {
            val result = client.getBalance(s.baseUrl, s.apiKey)
            _balance.value = result.fold(
                onSuccess = { BalanceState(loading = false, info = it, error = null) },
                onFailure = { BalanceState(loading = false, info = _balance.value.info, error = it.message) }
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        voice.destroy()
    }

    companion object {
        // Max number of tool-call rounds before we force a tools-less
        // final-synthesis pass. Bumped from 4 → 8 because some prompts
        // legitimately need several searches in a row (e.g. "查一下
        // 特朗普现在在哪里" tends to fire 3-5 successive web_search
        // calls before the model has enough to summarise).
        private const val MAX_ITERS = 8
        private const val COMPACT_THRESHOLD = 0.78

        /** 压缩后保留多少上下文（占窗口比例）。0.35 是在「别压太狠导致模型忘事」
         *  和「别压太浅下一轮又触发」之间取的：压完约 35%，离 78% 的阈值还有
         *  一倍多的空间，正常能撑好几轮。 */
        private const val KEEP_AFTER_COMPACT = 0.35

        /** 无论预算怎么算，末尾至少留这么多条 —— 单条超预算（一次 12000 字符的
         *  stdout）时不能把它自己也压掉，否则模型看不到自己刚拿到的结果。 */
        private const val MIN_KEEP_MESSAGES = 4

        /** 最近的工具结果保留全文的总预算（字符）。24000 ≈ 两条跑满的
         *  container_exec（它单条 stdout 上限就是 12000）。这个数直接决定了
         *  一次请求里工具结果那部分的天花板。 */
        private const val TOOL_KEEP_BUDGET_CHARS = 24_000

        /** 预算再紧也至少留这么多条全文。见 buildRequestMessages 里的注释。 */
        private const val TOOL_KEEP_MIN = 2

        /** 低于这个长度的工具结果原样发。折叠标记本身就要二三十字，对一条
         *  几百字的结果省不下什么，反而把"这是完整输出"的信号弄没了。 */
        private const val TOOL_FOLD_MIN_CHARS = 800

        /** arguments 里超过这个长度的字符串字段才折。取 600 是因为一条
         *  write_file 写几百字的配置文件是常态，折了纯属添乱；真正吃预算的是
         *  几十 KB 那一档。 */
        private const val ARG_FOLD_MIN_CHARS = 600

        /** 折叠后保留的头 / 尾行数。头几行是命令回显和第一条报错，尾几行是
         *  最终结论和退出码 —— 中间那段是 make 的滚屏。 */
        private const val TOOL_FOLD_HEAD_LINES = 6
        private const val TOOL_FOLD_TAIL_LINES = 12

        /** 一条折叠后的工具结果的绝对上限。按行折对"25 行 × 每行 2 KB"这种
         *  形状几乎不起作用，没有这道闸 TOOL_KEEP_BUDGET_CHARS 就只是个说法。 */
        private const val TOOL_FOLD_MAX_CHARS = 1_800

        // Realistic per-model context windows. Cover every variant the
        // user might land on — `model` here is the raw model id reported
        // by the chosen API (e.g. "deepseek-chat", "deepseek-v3", "v4-flash",
        // "deepseek-reasoner-v4", etc.). Anything that can't be matched
        // gets a conservative 64 K so the ring still reads correctly
        // instead of being permanently rounded to 0 %.
        private fun contextLimitFor(model: String): Long {
            val m = model.lowercase()
            return when {
                "v4-flash" in m || "v4_flash" in m -> 128_000L
                "v4" in m -> 128_000L                           // DeepSeek-V4 family
                "v3" in m -> 64_000L                            // DeepSeek-V3
                "v2.5" in m || "v2_5" in m -> 32_000L
                "v2" in m -> 32_000L
                "reasoner" in m || "r1" in m -> 64_000L
                "deepseek-chat" in m -> 64_000L
                "coder" in m -> 16_000L                         // older coder snap
                else -> 64_000L
            }
        }

        fun factory(
            chat: ChatRepository,
            settings: SettingsRepository,
            client: DeepSeekClient,
            memory: MemoryService,
            toolExec: ToolExecutor,
            notifier: ChatNotifier,
            voice: VoiceRecognizer,
            sandbox: com.biji.notes.sandbox.LocalSandbox,
            bootstrap: com.biji.notes.sandbox.BijiBootstrap,
            pkg: com.biji.notes.sandbox.BijiPkg,
            containers: com.biji.notes.sandbox.AiContainerManager,
            isForeground: () -> Boolean
        ) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ChatViewModel(
                    chat, settings, client, memory, toolExec, notifier, voice, sandbox,
                    bootstrap, pkg, containers, isForeground
                ) as T
        }
    }
}
