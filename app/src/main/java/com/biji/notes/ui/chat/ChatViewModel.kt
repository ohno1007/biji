package com.biji.notes.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.biji.notes.data.AppSettings
import com.biji.notes.data.ChatRepository
import com.biji.notes.data.Conversation
import com.biji.notes.data.Message
import com.biji.notes.data.Role
import com.biji.notes.data.SettingsRepository
import com.biji.notes.net.BalanceInfo
import com.biji.notes.net.ChatEvent
import com.biji.notes.net.ChatMessageDto
import com.biji.notes.net.DeepSeekClient
import com.biji.notes.net.ModelInfo
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
    private val client: DeepSeekClient
) : ViewModel() {

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

    private val _models = MutableStateFlow(ModelsState())
    val models: StateFlow<ModelsState> = _models.asStateFlow()

    private val _balance = MutableStateFlow(BalanceState())
    val balance: StateFlow<BalanceState> = _balance.asStateFlow()

    private var streamJob: Job? = null

    fun openConversation(id: Long) { _activeConvoId.value = id }
    fun clearActive() { _activeConvoId.value = null }

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
        }
    }

    fun cancelStream() {
        streamJob?.cancel()
        streamJob = null
        _isStreaming.value = false
    }

    fun send(userText: String) {
        val text = userText.trim()
        if (text.isEmpty()) return
        val s = settings.value

        viewModelScope.launch {
            val convoId = _activeConvoId.value ?: run {
                val newId = chat.createConversation(model = s.model)
                _activeConvoId.value = newId
                newId
            }

            chat.addMessage(convoId, Role.USER, text)

            val convo = chat.getConversation(convoId)
            if (convo != null && convo.title == "新对话") {
                chat.rename(convoId, text.take(24))
            }

            val historyDto = chat.getMessages(convoId).map { ChatMessageDto(it.role, it.content) }
            val withSystem = if (s.systemPrompt.isNotBlank()) {
                listOf(ChatMessageDto(Role.SYSTEM, s.systemPrompt)) + historyDto
            } else historyDto

            val placeholderId = chat.addMessage(convoId, Role.ASSISTANT, "")
            _isStreaming.value = true
            _streamError.value = null

            val contentBuf = StringBuilder()
            val reasoningBuf = StringBuilder()

            streamJob = viewModelScope.launch {
                client.stream(
                    baseUrl = s.baseUrl,
                    apiKey = s.apiKey,
                    model = s.model,
                    messages = withSystem,
                    temperature = s.temperature
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
                        ChatEvent.Done -> { _isStreaming.value = false }
                        is ChatEvent.Error -> {
                            _streamError.value = ev.message
                            _isStreaming.value = false
                        }
                    }
                }
            }
        }
    }

    fun dismissError() { _streamError.value = null }

    // ---- Settings actions -----------------------------------------------

    fun setApiKey(value: String) = viewModelScope.launch { settingsRepo.setApiKey(value) }
    fun setBaseUrl(value: String) = viewModelScope.launch { settingsRepo.setBaseUrl(value) }
    fun setModel(value: String) = viewModelScope.launch { settingsRepo.setModel(value) }
    fun setThinking(on: Boolean) = viewModelScope.launch { settingsRepo.setThinking(on) }
    fun setSystemPrompt(value: String) =
        viewModelScope.launch { settingsRepo.setSystemPrompt(value) }
    fun setTemperature(value: Float) =
        viewModelScope.launch { settingsRepo.setTemperature(value) }

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
        fun factory(
            chat: ChatRepository,
            settings: SettingsRepository,
            client: DeepSeekClient
        ) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ChatViewModel(chat, settings, client) as T
        }
    }
}
