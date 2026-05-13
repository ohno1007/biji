package com.biji.notes.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

sealed interface ChatEvent {
    data class Delta(val content: String) : ChatEvent
    data class Reasoning(val content: String) : ChatEvent
    data object Done : ChatEvent
    data class Error(val message: String) : ChatEvent
}

@Serializable
data class ChatMessageDto(
    val role: String,
    val content: String
)

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessageDto>,
    val stream: Boolean = true,
    val temperature: Float? = null
)

data class ModelInfo(
    val id: String,
    val ownedBy: String? = null
)

data class BalanceInfo(
    val isAvailable: Boolean,
    val currency: String,
    val totalBalance: String,
    val grantedBalance: String,
    val toppedUpBalance: String
)

class DeepSeekClient {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        // Streaming responses are open-ended; readTimeout = 0 disables it
        // for the duration of a chat.
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun stream(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessageDto>,
        temperature: Float? = null
    ): Flow<ChatEvent> = callbackFlow {
        if (apiKey.isBlank()) {
            trySend(ChatEvent.Error("未配置 API Key，请到「设置」填入。"))
            close(); return@callbackFlow
        }

        val payload = json.encodeToString(
            ChatRequest(
                model = model,
                messages = messages,
                stream = true,
                temperature = if (model.contains("reason", ignoreCase = true)) null else temperature
            )
        )
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/').removeSuffix("/v1") + "/v1/chat/completions")
            .header("Accept", "text/event-stream")
            .header("Authorization", "Bearer $apiKey")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        val call = http.newCall(request)

        val job = launch(Dispatchers.IO) {
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        val raw = runCatching { response.body?.string().orEmpty() }.getOrDefault("")
                        trySend(ChatEvent.Error(formatHttpError(response.code, raw)))
                        return@use
                    }

                    val body = response.body ?: run {
                        trySend(ChatEvent.Error("空响应体"))
                        return@use
                    }
                    val contentType = (response.header("Content-Type") ?: "").lowercase()

                    if (contentType.contains("event-stream")) {
                        readSse(body.source(), this@callbackFlow::trySend)
                    } else {
                        // Server ignored stream=true (proxies / non-streaming
                        // gateways do this). Parse the whole completion in one
                        // shot so the user sees the answer instead of raw JSON.
                        val text = body.string()
                        emitCompleteJson(text, this@callbackFlow::trySend)
                    }
                    trySend(ChatEvent.Done)
                }
            } catch (_: CancellationException) {
                // user clicked stop — nothing to report
            } catch (e: Throwable) {
                trySend(ChatEvent.Error(e.message ?: e.javaClass.simpleName))
            } finally {
                close()
            }
        }

        awaitClose {
            call.cancel()
            job.cancel()
        }
    }

    private fun readSse(
        src: okio.BufferedSource,
        emit: (ChatEvent) -> Unit
    ) {
        val buf = StringBuilder()
        while (true) {
            val line = src.readUtf8Line() ?: break
            if (line.isEmpty()) {
                val data = buf.toString()
                buf.clear()
                if (data.isEmpty()) continue
                if (data == "[DONE]") return
                parseStreamChunk(data, emit)
            } else if (line.startsWith("data:")) {
                val payload = line.removePrefix("data:").trimStart()
                if (buf.isNotEmpty()) buf.append('\n')
                buf.append(payload)
            }
            // Ignore "event:", "id:", and comment ":" lines.
        }
    }

    private fun parseStreamChunk(data: String, emit: (ChatEvent) -> Unit) {
        runCatching {
            val obj = json.parseToJsonElement(data).jsonObject
            val choice = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: return@runCatching
            // Many OpenAI-compatible servers stream "delta"; some (mis)stream
            // a full "message" per chunk — handle both.
            val delta = choice["delta"]?.jsonObject ?: choice["message"]?.jsonObject
            if (delta != null) {
                val reasoning = delta["reasoning_content"]?.jsonPrimitive?.contentOrNull
                val content = delta["content"]?.jsonPrimitive?.contentOrNull
                if (!reasoning.isNullOrEmpty()) emit(ChatEvent.Reasoning(reasoning))
                if (!content.isNullOrEmpty()) emit(ChatEvent.Delta(content))
            }
        }
    }

    private fun emitCompleteJson(text: String, emit: (ChatEvent) -> Unit) {
        runCatching {
            val obj = json.parseToJsonElement(text).jsonObject
            val choice = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            if (choice == null) {
                emit(ChatEvent.Error("响应不是合法的 chat.completion 结构"))
                return
            }
            val msg = choice["message"]?.jsonObject ?: choice["delta"]?.jsonObject
            val content = msg?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
            val reasoning = msg?.get("reasoning_content")?.jsonPrimitive?.contentOrNull
            if (!reasoning.isNullOrEmpty()) emit(ChatEvent.Reasoning(reasoning))
            if (content.isNotEmpty()) emit(ChatEvent.Delta(content))
            if (content.isEmpty() && reasoning.isNullOrEmpty()) {
                emit(ChatEvent.Error("响应体没有 content 字段"))
            }
        }.onFailure {
            emit(ChatEvent.Error("响应解析失败：${it.message ?: it.javaClass.simpleName}"))
        }
    }

    private fun formatHttpError(code: Int, raw: String): String {
        val parsed = runCatching {
            json.parseToJsonElement(raw).jsonObject["error"]?.jsonObject
                ?.get("message")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        val tail = parsed ?: raw.take(220).ifBlank { "" }
        return if (tail.isBlank()) "HTTP $code" else "HTTP $code · $tail"
    }

    // ---- Models -----------------------------------------------------------

    suspend fun listModels(baseUrl: String, apiKey: String): Result<List<ModelInfo>> =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) return@withContext Result.failure(IllegalStateException("未配置 API Key"))
            runCatching {
                val req = Request.Builder()
                    .url(baseUrl.trimEnd('/').removeSuffix("/v1") + "/v1/models")
                    .header("Authorization", "Bearer $apiKey")
                    .get().build()
                http.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) error(formatHttpError(resp.code, text))
                    val arr: JsonArray = json.parseToJsonElement(text).jsonObject["data"]?.jsonArray
                        ?: error("响应缺少 data 字段")
                    arr.mapNotNull { el: JsonElement ->
                        val o: JsonObject = el.jsonObject
                        val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        ModelInfo(
                            id = id,
                            ownedBy = o["owned_by"]?.jsonPrimitive?.contentOrNull
                        )
                    }.sortedBy { it.id }
                }
            }
        }

    // ---- Balance ----------------------------------------------------------

    suspend fun getBalance(baseUrl: String, apiKey: String): Result<BalanceInfo> =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) return@withContext Result.failure(IllegalStateException("未配置 API Key"))
            runCatching {
                val req = Request.Builder()
                    .url(baseUrl.trimEnd('/').removeSuffix("/v1") + "/user/balance")
                    .header("Authorization", "Bearer $apiKey")
                    .get().build()
                http.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) error(formatHttpError(resp.code, text))
                    val obj = json.parseToJsonElement(text).jsonObject
                    val avail = obj["is_available"]?.jsonPrimitive?.booleanOrNull ?: false
                    val info = obj["balance_infos"]?.jsonArray?.firstOrNull()?.jsonObject
                    BalanceInfo(
                        isAvailable = avail,
                        currency = info?.get("currency")?.jsonPrimitive?.contentOrNull ?: "CNY",
                        totalBalance = info?.get("total_balance")?.jsonPrimitive?.contentOrNull ?: "-",
                        grantedBalance = info?.get("granted_balance")?.jsonPrimitive?.contentOrNull ?: "-",
                        toppedUpBalance = info?.get("topped_up_balance")?.jsonPrimitive?.contentOrNull ?: "-"
                    )
                }
            }
        }
}
