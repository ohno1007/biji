package com.biji.notes.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore("biji-settings")

data class AppSettings(
    val apiKey: String = "",
    val baseUrl: String = "https://api.deepseek.com",
    val thinking: Boolean = false,
    val systemPrompt: String = "",
    val temperature: Float = 1.0f
) {
    val model: String get() = if (thinking) "deepseek-reasoner" else "deepseek-chat"
}

class SettingsRepository(private val ctx: Context) {

    private object K {
        val API_KEY = stringPreferencesKey("api_key")
        val BASE_URL = stringPreferencesKey("base_url")
        val THINKING = booleanPreferencesKey("thinking")
        val SYSTEM_PROMPT = stringPreferencesKey("system_prompt")
        val TEMPERATURE = stringPreferencesKey("temperature")
    }

    val settings: Flow<AppSettings> = ctx.settingsStore.data.map { p ->
        AppSettings(
            apiKey = p[K.API_KEY].orEmpty(),
            baseUrl = p[K.BASE_URL]?.ifBlank { null } ?: "https://api.deepseek.com",
            thinking = p[K.THINKING] ?: false,
            systemPrompt = p[K.SYSTEM_PROMPT].orEmpty(),
            temperature = p[K.TEMPERATURE]?.toFloatOrNull() ?: 1.0f
        )
    }

    suspend fun setApiKey(value: String) =
        ctx.settingsStore.edit { it[K.API_KEY] = value.trim() }.let { }

    suspend fun setBaseUrl(value: String) =
        ctx.settingsStore.edit {
            it[K.BASE_URL] = value.trim().ifBlank { "https://api.deepseek.com" }
        }.let { }

    suspend fun setThinking(value: Boolean) =
        ctx.settingsStore.edit { it[K.THINKING] = value }.let { }

    suspend fun setSystemPrompt(value: String) =
        ctx.settingsStore.edit { it[K.SYSTEM_PROMPT] = value }.let { }

    suspend fun setTemperature(value: Float) =
        ctx.settingsStore.edit { it[K.TEMPERATURE] = value.toString() }.let { }
}
