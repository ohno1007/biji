package com.biji.notes.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
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
    val temperature: Float = 1.0f
) {
    val thinking: Boolean get() = model == MODEL_REASONER
}

class SettingsRepository(private val ctx: Context) {

    private object K {
        val API_KEY = stringPreferencesKey("api_key")
        val BASE_URL = stringPreferencesKey("base_url")
        val MODEL = stringPreferencesKey("model")
        val SYSTEM_PROMPT = stringPreferencesKey("system_prompt")
        val TEMPERATURE = stringPreferencesKey("temperature")
    }

    val settings: Flow<AppSettings> = ctx.settingsStore.data.map { p ->
        AppSettings(
            apiKey = p[K.API_KEY].orEmpty(),
            baseUrl = p[K.BASE_URL]?.ifBlank { null } ?: "https://api.deepseek.com",
            model = p[K.MODEL]?.ifBlank { null } ?: MODEL_CHAT,
            systemPrompt = p[K.SYSTEM_PROMPT].orEmpty(),
            temperature = p[K.TEMPERATURE]?.toFloatOrNull() ?: 1.0f
        )
    }

    suspend fun setApiKey(value: String) {
        ctx.settingsStore.edit { it[K.API_KEY] = value.trim() }
    }

    suspend fun setBaseUrl(value: String) {
        ctx.settingsStore.edit {
            it[K.BASE_URL] = value.trim().ifBlank { "https://api.deepseek.com" }
        }
    }

    suspend fun setModel(value: String) {
        ctx.settingsStore.edit { it[K.MODEL] = value.trim().ifBlank { MODEL_CHAT } }
    }

    suspend fun setThinking(on: Boolean) {
        setModel(if (on) MODEL_REASONER else MODEL_CHAT)
    }

    suspend fun setSystemPrompt(value: String) {
        ctx.settingsStore.edit { it[K.SYSTEM_PROMPT] = value }
    }

    suspend fun setTemperature(value: Float) {
        ctx.settingsStore.edit { it[K.TEMPERATURE] = value.toString() }
    }
}
