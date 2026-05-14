package com.biji.notes.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore("biji-settings")

const val MODEL_CHAT = "deepseek-chat"
const val MODEL_REASONER = "deepseek-reasoner"

data class AppSettings(
    val apiKey: String = "",
    val baseUrl: String = "https://api.deepseek.com",
    val model: String = MODEL_CHAT,
    val systemPrompt: String = "",
    val temperature: Float = 1.0f,
    val webSearch: Boolean = true,
    val longMemory: Boolean = true,
    val notify: Boolean = true,
    /** Whether *new* conversations are created with the `<think>` instruction
     *  prepended. Independent from the active model — non-reasoner chats can
     *  still opt into thinking mode. Persisted in DataStore. */
    val defaultThinking: Boolean = false,
    /** Enable the developer / engineering sandbox tool trio
     *  (read_file / write_file / run_shell_command). When off, the
     *  assistant can't touch the local filesystem. */
    val developerMode: Boolean = false,
    /** Route every shell command through `su -c …`. The user has to
     *  flip this on explicitly *and* the OS has to grant superuser via
     *  Magisk / SuperSU. Off by default. */
    val useRoot: Boolean = false
) {
    /** True if the *currently selected model* itself does thinking (i.e.
     *  deepseek-reasoner). For per-conversation thinking, look at the
     *  `Conversation.thinking` column. */
    val modelIsReasoner: Boolean get() = model == MODEL_REASONER
}

class SettingsRepository(private val ctx: Context) {

    private object K {
        val API_KEY = stringPreferencesKey("api_key")
        val BASE_URL = stringPreferencesKey("base_url")
        val MODEL = stringPreferencesKey("model")
        val SYSTEM_PROMPT = stringPreferencesKey("system_prompt")
        val TEMPERATURE = stringPreferencesKey("temperature")
        val WEB_SEARCH = booleanPreferencesKey("web_search")
        val LONG_MEMORY = booleanPreferencesKey("long_memory")
        val NOTIFY = booleanPreferencesKey("notify")
        val DEFAULT_THINKING = booleanPreferencesKey("default_thinking")
        val DEVELOPER_MODE = booleanPreferencesKey("developer_mode")
        val USE_ROOT = booleanPreferencesKey("use_root")
    }

    val settings: Flow<AppSettings> = ctx.settingsStore.data.map { p ->
        AppSettings(
            apiKey = p[K.API_KEY].orEmpty(),
            baseUrl = p[K.BASE_URL]?.ifBlank { null } ?: "https://api.deepseek.com",
            model = p[K.MODEL]?.ifBlank { null } ?: MODEL_CHAT,
            systemPrompt = p[K.SYSTEM_PROMPT].orEmpty(),
            temperature = p[K.TEMPERATURE]?.toFloatOrNull() ?: 1.0f,
            webSearch = p[K.WEB_SEARCH] ?: true,
            longMemory = p[K.LONG_MEMORY] ?: true,
            notify = p[K.NOTIFY] ?: true,
            defaultThinking = p[K.DEFAULT_THINKING] ?: false,
            developerMode = p[K.DEVELOPER_MODE] ?: false,
            useRoot = p[K.USE_ROOT] ?: false
        )
    }

    suspend fun setApiKey(v: String) { ctx.settingsStore.edit { it[K.API_KEY] = v.trim() } }
    suspend fun setBaseUrl(v: String) {
        ctx.settingsStore.edit {
            it[K.BASE_URL] = v.trim().ifBlank { "https://api.deepseek.com" }
        }
    }
    suspend fun setModel(v: String) {
        ctx.settingsStore.edit { it[K.MODEL] = v.trim().ifBlank { MODEL_CHAT } }
    }
    suspend fun setSystemPrompt(v: String) {
        ctx.settingsStore.edit { it[K.SYSTEM_PROMPT] = v }
    }
    suspend fun setTemperature(v: Float) {
        ctx.settingsStore.edit { it[K.TEMPERATURE] = v.toString() }
    }
    suspend fun setWebSearch(v: Boolean) { ctx.settingsStore.edit { it[K.WEB_SEARCH] = v } }
    suspend fun setLongMemory(v: Boolean) { ctx.settingsStore.edit { it[K.LONG_MEMORY] = v } }
    suspend fun setNotify(v: Boolean) { ctx.settingsStore.edit { it[K.NOTIFY] = v } }
    suspend fun setDefaultThinking(v: Boolean) {
        ctx.settingsStore.edit { it[K.DEFAULT_THINKING] = v }
    }
    suspend fun setDeveloperMode(v: Boolean) {
        ctx.settingsStore.edit { it[K.DEVELOPER_MODE] = v }
    }
    suspend fun setUseRoot(v: Boolean) {
        ctx.settingsStore.edit { it[K.USE_ROOT] = v }
    }

    // -----------------------------------------------------------------
    // Per-conversation project folder.
    //
    // Each conversation can pick its own working folder name. The
    // assistant's read/write tools are restricted to that folder (a
    // subdirectory under `projects/<folder>` in app-private storage),
    // while the shell tool runs in the global environment with the
    // folder as the default cwd. Empty / null means "no project bound";
    // tools fall back to the legacy `projects/default` root.
    // -----------------------------------------------------------------

    private fun convoFolderKey(convoId: Long) =
        stringPreferencesKey("convo_folder_$convoId")

    /** Stream the folder name bound to [convoId]. Empty string when
     *  none has been set. */
    fun convoFolder(convoId: Long): Flow<String> =
        ctx.settingsStore.data.map { it[convoFolderKey(convoId)].orEmpty() }

    suspend fun setConvoFolder(convoId: Long, folder: String) {
        val cleaned = folder.trim().trim('/')
        ctx.settingsStore.edit {
            if (cleaned.isEmpty()) it.remove(convoFolderKey(convoId))
            else it[convoFolderKey(convoId)] = cleaned
        }
    }
}
