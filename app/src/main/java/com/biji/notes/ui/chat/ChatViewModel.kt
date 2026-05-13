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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(
    private val chat: ChatRepository,
    private val settingsRepo: SettingsRepository,
    private val client: DeepSeekClient,
    private val memory: MemoryService,
    private val toolExec: ToolExecutor,
    private val notifier: ChatNotifier,
    private val voice: VoiceRecognizer,
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

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _streamError = MutableStateFlow<String?>(null)
    val streamError: StateFlow<String?> = _streamError.asStateFlow()

    private val _toolStatus = MutableStateFlow<String?>(null)
    val toolStatus: StateFlow<String?> = _toolStatus.asStateFlow()

    private val _models = MutableStateFlow(ModelsState())
    val models: StateFlow<ModelsState> = _models.asStateFlow()

    private val _balance = MutableStateFlow(BalanceState())
    val balance: StateFlow<BalanceState> = _balance.asStateFlow()

    private val _openWebUrl = MutableStateFlow<String?>(null)
    val openWebUrl: StateFlow<String?> = _openWebUrl.asStateFlow()

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

    fun openConversation(id: Long) { _activeConvoId.value = id }
    fun clearActive() { _activeConvoId.value = null }
    fun openWebUrl(url: String) { _openWebUrl.value = url }
    fun closeWebUrl() { _openWebUrl.value = null }

    fun newConversation(open: Boolean = true) {
        viewModelScope.launch {
            val id = chat.createConversation(model = settings.value.model)
            if (open) _activeConvoId.value = id
        }
    }

    fun deleteConversation(id: Long) {
        viewModelScope.launch {
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

    fun send(userText: String) {
        val text = userText.trim()
        if (text.isEmpty()) return

        streamJob = viewModelScope.launch {
            val convoId = _activeConvoId.value ?: run {
                val id = chat.createConversation(model = settings.value.model)
                _activeConvoId.value = id
                id
            }
            chat.addMessage(convoId, Role.USER, text)
            memory.invalidate()

            _isStreaming.value = true
            _streamError.value = null
            _toolStatus.value = null

            try {
                maybeCompactContext(convoId)
                runChatTurn(convoId, userTurnText = text)
            } finally {
                _isStreaming.value = false
                _toolStatus.value = null
            }

            maybeSummarizeTitle(convoId)
            maybeNotify(convoId)
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

            client.stream(
                baseUrl = s.baseUrl,
                apiKey = s.apiKey,
                model = s.model,
                messages = msgs,
                temperature = s.temperature,
                tools = if (s.webSearch) Tools.definitions() else null
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
                    is ChatEvent.ToolCalls -> { toolCalls = ev.calls }
                    is ChatEvent.Usage -> { lastUsageTotal = ev.total }
                    ChatEvent.Done -> Unit
                    is ChatEvent.Error -> { _streamError.value = ev.message }
                }
            }

            if (lastUsageTotal > 0) {
                chat.setContextTokens(convoId, lastUsageTotal)
            }

            if (toolCalls.isEmpty()) return

            chat.setToolData(placeholderId, encodeToolCalls(toolCalls))

            for (call in toolCalls) {
                _toolStatus.value = friendlyToolStatus(call)
                val (forModel, uiJson) = runCatching { toolExec.run(call) }
                    .getOrElse { e ->
                        "工具失败: ${e.message}" to buildJsonObject {
                            put("kind", "error")
                            put("message", e.message ?: e.javaClass.simpleName)
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
    }

    private suspend fun buildRequestMessages(
        convoId: Long,
        userTurnText: String
    ): List<ChatMessageDto> {
        val s = settings.value
        val convo = chat.getConversation(convoId)
        val live = chat.getLiveMessages(convoId)

        val keepReasoning = !s.baseUrl.contains("api.deepseek.com", ignoreCase = true)
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
    fun setThinking(on: Boolean) = viewModelScope.launch { settingsRepo.setThinking(on) }
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
        private const val MAX_ITERS = 4
        private const val COMPACT_THRESHOLD = 0.78

        private fun contextLimitFor(model: String): Long = when {
            model.contains("reason", ignoreCase = true) -> 64_000L
            model.contains("v4-flash", ignoreCase = true) -> 32_000L
            else -> 64_000L
        }

        fun factory(
            chat: ChatRepository,
            settings: SettingsRepository,
            client: DeepSeekClient,
            memory: MemoryService,
            toolExec: ToolExecutor,
            notifier: ChatNotifier,
            voice: VoiceRecognizer,
            isForeground: () -> Boolean
        ) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ChatViewModel(
                    chat, settings, client, memory, toolExec, notifier, voice, isForeground
                ) as T
        }
    }
}
