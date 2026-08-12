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

/** 开发者模式下附加的一段说明。不写这段的话模型会一直挑
 *  run_shell_command —— 它每次 fork 新 sh，cd/export 全丢，多步流程必崩。 */
private const val CONTAINER_BRIEF = """本会话有一个专属的本地容器（Android 用户态，无 root），优先用它做开发：

- container_exec 跑在长驻 shell 里，cd / export / shell 函数 / source venv 都会保留到下一次调用；run_shell_command 每次都是全新的 sh，只适合单条独立命令。
- 目录：${'$'}BIJI_WORK 工作区（等同 read_file / write_file 里的 work/ 前缀）、${'$'}BIJI_TMP 临时、${'$'}BIJI_BIN 已装命令、${'$'}BIJI_EXEC 可执行区。
- 工作区在共享存储上是 noexec 的：自己编出来的二进制要先 cp 到 ${'$'}BIJI_EXEC 再执行。
- 没有 PTY，别用 vi / top / 需要交互确认的命令，一律走非交互参数。
- 超过 2 分钟的活儿（编译、下载大文件、跑服务）用 container_task action=start，然后 poll 拉增量输出。
- 缺工具先 list_packages / install_package；只认静态链接的 aarch64 二进制。
- 不清楚环境就先 container_info，一次拿到目录、已装命令、磁盘和任务状态。"""

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
            _streamError.value = "本设备未安装支持的语音识别服务。请到系统设置 → 应用 → 默认应用 → 语音助手 启用 Google 或厂商语音识别，或者直接打字。"
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
                    maybeCompactContext(convoId)
                    runChatTurn(convoId, userTurnText = turnText)
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
                    sandbox = s.developerMode
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
                    ?: "模型未返回任何内容。请检查模型是否支持你当前选择的功能（联网工具、思考模式），或换个 Base URL/模型重试。"
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
        forceFinalSynthesis(convoId)
    }

    /**
     * Last-resort pass after `runChatTurn` exhausts its tool-call budget.
     * Re-sends the full conversation history with `tools = null` plus a
     * one-shot system nudge instructing the model to wrap up, so the
     * user always gets a textual answer even when the model would have
     * happily kept calling tools forever.
     */
    private suspend fun forceFinalSynthesis(convoId: Long) {
        val s = settings.value
        val base = buildRequestMessages(convoId, "")
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
        val dtos = live.mapNotNull { toDto(it, keepReasoning) }

        val systemBlocks = mutableListOf<String>()
        if (s.systemPrompt.isNotBlank()) systemBlocks += s.systemPrompt
        if (convo?.thinking == true && s.model.contains("reason").not()) {
            systemBlocks += "请先在 <think>…</think> 中详细列出你的推理过程，再给最终答案。"
        }
        if (s.longMemory) {
            val hits = memory.retrieve(userTurnText, excludeConvoId = convoId, k = 3)
            memory.memoryPrompt(hits)?.let { systemBlocks += it }
        }
        if (s.webSearch) {
            systemBlocks += "当用户问到需要实时、最新或不确定的信息时，主动调用 web_search；" +
                "当确认某个网页有用时再调用 read_url 抓正文。"
        }
        if (s.developerMode) {
            systemBlocks += CONTAINER_BRIEF
        }
        val systemDto = if (systemBlocks.isNotEmpty()) {
            ChatMessageDto(role = Role.SYSTEM, content = systemBlocks.joinToString("\n\n"))
        } else null

        return listOfNotNull(systemDto) + dtos
    }

    private fun toDto(m: Message, keepReasoning: Boolean): ChatMessageDto? = when {
        m.kind == MessageKind.TOOL_RESULT -> ChatMessageDto(
            role = Role.TOOL,
            content = m.content,
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
            toolCalls = decodeToolCalls(m.toolData)
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

        val live = chat.getLiveMessages(convoId)
        val candidates = live.filter {
            it.kind == MessageKind.TEXT && (it.role == Role.USER || it.role == Role.ASSISTANT)
        }
        if (candidates.size < 8) return

        val keepN = 4
        val toArchive = candidates.dropLast(keepN)
        if (toArchive.size < 2) return

        _compactStatus.value = "上下文已 ${(convo.contextTokens.toDouble() / limit * 100).toInt()}%, 正在向量化早期内容…"
        val transcript = toArchive.joinToString("\n\n") { m ->
            val who = when (m.role) {
                Role.USER -> "用户"
                Role.ASSISTANT -> "助手"
                else -> m.role
            }
            "$who: ${m.content.take(800)}"
        }

        val summary = client.complete(
            baseUrl = s.baseUrl,
            apiKey = s.apiKey,
            model = "deepseek-chat",
            system = "请把下面这段多轮对话压缩成一段不超过 500 字的客观摘要，" +
                "保留关键事实、已确认的偏好、未决的问题与代办，去除寒暄。直接输出摘要正文，不要加标题。",
            user = transcript
        ).getOrNull()?.takeIf { it.isNotBlank() }

        _compactStatus.value = null
        if (summary.isNullOrBlank()) return

        chat.archive(toArchive.map { it.id })
        val firstTs = toArchive.first().createdAt - 1
        chat.addMessage(
            convoId = convoId,
            role = Role.SYSTEM,
            content = "【早期对话摘要】\n$summary",
            kind = MessageKind.CONTEXT_SUMMARY,
            createdAt = firstTs
        )
        // Reset token estimate; the next API call will rewrite it from `usage`.
        chat.setContextTokens(convoId, summary.length.toLong() / 2)
        memory.invalidate()
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

    private fun decodeToolCalls(raw: String): List<ToolCall> = runCatching {
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
                ToolCall(id, name, args)
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
