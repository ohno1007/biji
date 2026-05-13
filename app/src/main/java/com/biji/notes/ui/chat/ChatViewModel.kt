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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(
    private val chat: ChatRepository,
    private val settingsRepo: SettingsRepository,
    private val client: DeepSeekClient,
    private val memory: MemoryService,
    private val toolExec: ToolExecutor,
    private val notifier: ChatNotifier,
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

    private var streamJob: Job? = null

    // -- Navigation ---------------------------------------------------------

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

    // -- Sending ------------------------------------------------------------

    fun send(userText: String) {
        val text = userText.trim()
        if (text.isEmpty()) return
        val s = settings.value

        streamJob = viewModelScope.launch {
            val convoId = _activeConvoId.value ?: run {
                val id = chat.createConversation(model = s.model)
                _activeConvoId.value = id
                id
            }
            chat.addMessage(convoId, Role.USER, text)
            memory.invalidate()

            _isStreaming.value = true
            _streamError.value = null
            _toolStatus.value = null

            try {
                runChatTurn(convoId, userTurnText = text)
            } finally {
                _isStreaming.value = false
                _toolStatus.value = null
            }

            // Auto-title once we actually have an answer.
            maybeSummarizeTitle(convoId)
            maybeNotify(convoId)
        }
    }

    /**
     * Drive one user turn through the model, including any tool-call rounds.
     * The model can request tools (web_search / read_url), we run them, then
     * feed the results back and keep streaming. Capped at MAX_ITERS.
     */
    private suspend fun runChatTurn(convoId: Long, userTurnText: String) {
        val s = settings.value
        var iters = 0
        while (iters++ < MAX_ITERS) {
            val msgs = buildRequestMessages(convoId, userTurnText)
            val placeholderId = chat.addMessage(convoId, Role.ASSISTANT, "")

            val contentBuf = StringBuilder()
            val reasoningBuf = StringBuilder()
            var toolCalls: List<ToolCall> = emptyList()

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
                    is ChatEvent.ToolCalls -> {
                        toolCalls = ev.calls
                    }
                    ChatEvent.Done -> Unit
                    is ChatEvent.Error -> {
                        _streamError.value = ev.message
                    }
                }
            }

            if (toolCalls.isEmpty()) return

            // Persist the assistant-with-tool_calls record by upgrading the
            // placeholder we just streamed into (which is empty content +
            // possibly some reasoning).
            chat.setToolData(placeholderId, encodeToolCalls(toolCalls))
            // Tag the placeholder as a tool_call message so we can format the
            // history correctly next iteration.
            // (Cheap update: re-insert via setToolData + we treat any
            //  assistant message with toolData as a tool_call.)

            // Execute each tool sequentially, persisting a tool-result row.
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
            // Loop back: model will see the tool results and continue.
        }
    }

    /** Build the messages payload for one stream() call from the convo's
     *  current state in the DB, augmented with the long-term memory
     *  fragment and the user-configured system prompt. */
    private suspend fun buildRequestMessages(
        convoId: Long,
        userTurnText: String
    ): List<ChatMessageDto> {
        val s = settings.value
        val history = chat.getMessages(convoId)
        val dtos = history.mapNotNull(::toDto)

        val systemBlocks = mutableListOf<String>()
        if (s.systemPrompt.isNotBlank()) systemBlocks += s.systemPrompt
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

    private fun toDto(m: Message): ChatMessageDto? = when {
        m.kind == MessageKind.TOOL_RESULT -> ChatMessageDto(
            role = Role.TOOL,
            content = m.content,
            toolCallId = m.toolCallId
        )
        // An assistant message with toolData stored is the tool-call record:
        // re-emit it with the tool_calls field set.
        m.role == Role.ASSISTANT && m.toolData != null -> ChatMessageDto(
            role = Role.ASSISTANT,
            content = m.content,
            toolCalls = decodeToolCalls(m.toolData)
        )
        // Skip the empty assistant placeholder while we're rebuilding history
        // for a re-call after tools: the live placeholder lives in DB but is
        // the one being streamed *into*. It always sits at the tail.
        m.role == Role.ASSISTANT && m.content.isBlank() && m.toolData == null -> null
        else -> ChatMessageDto(role = m.role, content = m.content)
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
        if (convo.title != "新对话" &&
            !convo.title.startsWith(convo.title.take(8))) return

        // We summarise only when there's a real (user + assistant) turn pair.
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

    // ---- Settings actions ------------------------------------------------

    fun setApiKey(value: String) = viewModelScope.launch { settingsRepo.setApiKey(value) }
    fun setBaseUrl(value: String) = viewModelScope.launch { settingsRepo.setBaseUrl(value) }
    fun setModel(value: String) = viewModelScope.launch { settingsRepo.setModel(value) }
    fun setThinking(on: Boolean) = viewModelScope.launch { settingsRepo.setThinking(on) }
    fun setSystemPrompt(v: String) = viewModelScope.launch { settingsRepo.setSystemPrompt(v) }
    fun setTemperature(v: Float) = viewModelScope.launch { settingsRepo.setTemperature(v) }
    fun setWebSearch(v: Boolean) = viewModelScope.launch { settingsRepo.setWebSearch(v) }
    fun setLongMemory(v: Boolean) = viewModelScope.launch { settingsRepo.setLongMemory(v) }
    fun setNotify(v: Boolean) = viewModelScope.launch { settingsRepo.setNotify(v) }

    fun refreshModels() {
        val s = settings.value
        _models.value = _models.value.copy(loading = true, error = null)
        viewModelScope.launch {
            val result = client.listModels(s.baseUrl, s.apiKey)
            _models.value = result.fold(
                onSuccess = { ModelsState(loading = false, list = it, error = null) },
                onFailure = { ModelsState(loading = false, list = _models.value.list, error = it.message) }
            )
        }
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

    companion object {
        private const val MAX_ITERS = 4

        fun factory(
            chat: ChatRepository,
            settings: SettingsRepository,
            client: DeepSeekClient,
            memory: MemoryService,
            toolExec: ToolExecutor,
            notifier: ChatNotifier,
            isForeground: () -> Boolean
        ) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ChatViewModel(chat, settings, client, memory, toolExec, notifier, isForeground) as T
        }
    }
}
