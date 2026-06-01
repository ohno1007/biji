package com.biji.notes.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

sealed interface ChatEvent {
    data class Delta(val content: String) : ChatEvent
    data class Reasoning(val content: String) : ChatEvent
    data class ToolCalls(val calls: List<ToolCall>) : ChatEvent
    data class Usage(
        val prompt: Long,
        val completion: Long,
        val total: Long,
        /** Tokens that hit DeepSeek's prefix cache (free or discounted).
         *  Mirrors the `prompt_cache_hit_tokens` field. */
        val cacheHit: Long = 0L,
        /** Tokens that missed the cache. Mirrors
         *  `prompt_cache_miss_tokens`. */
        val cacheMiss: Long = 0L
    ) : ChatEvent
    data object Done : ChatEvent
    data class Error(val message: String) : ChatEvent
}

data class ChatMessageDto(
    val role: String,
    val content: String? = null,
    val reasoningContent: String? = null,
    val toolCalls: List<ToolCall>? = null,
    val toolCallId: String? = null
)

data class ModelInfo(val id: String, val ownedBy: String? = null)
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
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun stream(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessageDto>,
        temperature: Float? = null,
        tools: List<JsonObject>? = null
    ): Flow<ChatEvent> = callbackFlow {
        if (apiKey.isBlank()) {
            trySend(ChatEvent.Error("未配置 API Key，请到「设置」填入。"))
            close(); return@callbackFlow
        }

        val payload = buildRequestBody(model, messages, temperature, stream = true, tools = tools)
        val request = Request.Builder()
            .url(chatUrl(baseUrl))
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

                    val toolBuf = ToolCallBuffer()
                    when {
                        contentType.contains("event-stream") ->
                            readSse(body.source(), toolBuf, this@callbackFlow::trySend)
                        // Some proxies stream NDJSON / chunked JSON-lines instead
                        // of real SSE. Sniff and parse line-by-line.
                        contentType.contains("ndjson") ||
                            contentType.contains("jsonl") ||
                            contentType.contains("application/stream+json") ->
                            readNdjson(body.source(), toolBuf, this@callbackFlow::trySend)
                        else -> {
                            val text = body.string()
                            if (text.lineSequence().take(4).any { it.trimStart().startsWith("data:") }) {
                                // SSE mislabelled as text/plain or application/json.
                                readSseFromString(text, toolBuf, this@callbackFlow::trySend)
                            } else {
                                emitCompleteJson(text, toolBuf, this@callbackFlow::trySend)
                            }
                        }
                    }
                    val calls = toolBuf.build()
                    if (calls.isNotEmpty()) trySend(ChatEvent.ToolCalls(calls))
                    trySend(ChatEvent.Done)
                }
            } catch (_: CancellationException) {
                // user cancelled
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

    /** Tiny non-streaming round-trip used to summarise a conversation title
     *  with the model. Returns the trimmed text content or an error. */
    suspend fun summarizeTitle(
        baseUrl: String,
        apiKey: String,
        userText: String,
        assistantText: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val msgs = listOf(
                ChatMessageDto(
                    role = "system",
                    content = "为下面这段对话起一个 6-12 个汉字的中文标题。只输出标题文本，不要标点、引号或前缀。"
                ),
                ChatMessageDto(
                    role = "user",
                    content = "用户问: ${userText.take(400)}\n助手答: ${assistantText.take(800)}"
                )
            )
            val payload = buildRequestBody("deepseek-chat", msgs, 0.6f, stream = false, tools = null)
            val req = Request.Builder()
                .url(chatUrl(baseUrl))
                .header("Authorization", "Bearer $apiKey")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error(formatHttpError(resp.code, text))
                val obj = json.parseToJsonElement(text).jsonObject
                obj["choices"]?.jsonArray?.firstOrNull()
                    ?.jsonObject?.get("message")?.jsonObject
                    ?.get("content")?.jsonPrimitive?.contentOrNull
                    ?.lines()?.firstOrNull { it.isNotBlank() }
                    ?.trim()
                    ?.trim('"', '"', '"', '“', '”', '《', '》', '【', '】', ' ', '.')
                    ?.take(24)
                    ?: error("无 content")
            }
        }
    }

    // ---- Request building -------------------------------------------------

    private fun buildRequestBody(
        model: String,
        messages: List<ChatMessageDto>,
        temperature: Float?,
        stream: Boolean,
        tools: List<JsonObject>?
    ): String {
        val obj = buildJsonObject {
            put("model", model)
            put("stream", stream)
            if (stream) {
                put("stream_options", buildJsonObject { put("include_usage", true) })
            }
            if (temperature != null && !model.contains("reason", ignoreCase = true)) {
                put("temperature", temperature.toDouble())
            }
            put("messages", buildJsonArray {
                messages.forEach { m -> add(messageToJson(m)) }
            })
            if (!tools.isNullOrEmpty()) {
                put("tools", buildJsonArray { tools.forEach { add(it) } })
                put("tool_choice", "auto")
            }
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    private fun messageToJson(m: ChatMessageDto): JsonObject = buildJsonObject {
        put("role", m.role)
        // OpenAI/DeepSeek spec: when an assistant message has tool_calls and
        // no actual text, content must be null/omitted; sending "" makes some
        // strict proxies reject the whole request (-> empty response).
        val hasToolCalls = !m.toolCalls.isNullOrEmpty()
        val text = m.content
        when {
            hasToolCalls && text.isNullOrBlank() -> { /* omit content entirely */ }
            else -> put("content", text ?: "")
        }
        // Some non-official proxies (e.g. v4-flash) require reasoning_content
        // to be echoed back when present; official DeepSeek strips it.
        // Caller decides per request via baseUrl heuristic.
        m.reasoningContent?.takeIf { it.isNotBlank() }?.let { put("reasoning_content", it) }
        if (hasToolCalls) {
            put("tool_calls", buildJsonArray {
                m.toolCalls!!.forEach { c ->
                    add(buildJsonObject {
                        put("id", c.id)
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", c.name)
                            put("arguments", c.arguments)
                        })
                    })
                }
            })
        }
        if (m.toolCallId != null) put("tool_call_id", m.toolCallId)
    }

    private fun chatUrl(baseUrl: String): String =
        baseUrl.trimEnd('/').removeSuffix("/v1") + "/v1/chat/completions"

    // ---- SSE / non-SSE parsing -------------------------------------------

    private fun readSse(
        src: okio.BufferedSource,
        toolBuf: ToolCallBuffer,
        emit: (ChatEvent) -> Unit
    ) {
        val buf = StringBuilder()
        while (true) {
            val line = src.readUtf8Line() ?: break
            if (line.isEmpty()) {
                if (flushSseBuffer(buf, toolBuf, emit)) return
            } else if (line.startsWith("data:")) {
                val payload = line.removePrefix("data:").trimStart()
                if (buf.isNotEmpty()) buf.append('\n')
                buf.append(payload)
            }
            // Other lines (`event:`, `id:`, `retry:`, `:` comments) are ignored.
        }
        // EOF without trailing blank line: flush whatever's pending so the
        // last chunk isn't lost (some proxies skip the terminating CRLFCRLF).
        flushSseBuffer(buf, toolBuf, emit)
    }

    /** Returns true if the event was `[DONE]` and the caller should stop. */
    private fun flushSseBuffer(
        buf: StringBuilder,
        toolBuf: ToolCallBuffer,
        emit: (ChatEvent) -> Unit
    ): Boolean {
        val data = buf.toString()
        buf.clear()
        if (data.isEmpty()) return false
        if (data == "[DONE]") return true
        parseStreamChunk(data, toolBuf, emit)
        return false
    }

    private fun readSseFromString(
        text: String,
        toolBuf: ToolCallBuffer,
        emit: (ChatEvent) -> Unit
    ) {
        val buf = StringBuilder()
        for (line in text.lineSequence()) {
            if (line.isEmpty()) {
                if (flushSseBuffer(buf, toolBuf, emit)) return
            } else if (line.startsWith("data:")) {
                val payload = line.removePrefix("data:").trimStart()
                if (buf.isNotEmpty()) buf.append('\n')
                buf.append(payload)
            }
        }
        flushSseBuffer(buf, toolBuf, emit)
    }

    private fun readNdjson(
        src: okio.BufferedSource,
        toolBuf: ToolCallBuffer,
        emit: (ChatEvent) -> Unit
    ) {
        while (true) {
            val line = src.readUtf8Line() ?: break
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed == "[DONE]" || trimmed == "data: [DONE]") return
            val payload = if (trimmed.startsWith("data:")) trimmed.removePrefix("data:").trim() else trimmed
            parseStreamChunk(payload, toolBuf, emit)
        }
    }

    private fun parseStreamChunk(
        data: String,
        toolBuf: ToolCallBuffer,
        emit: (ChatEvent) -> Unit
    ) {
        runCatching {
            val obj = (json.parseToJsonElement(data) as? JsonObject) ?: return@runCatching
            // Some proxies surface upstream errors as `{"error":{"message":"…"}}`
            // inside an otherwise-200 stream chunk. Forward them so the user
            // sees the real reason instead of "model returned nothing".
            obj.objOrNull("error")?.let { err ->
                val msg = err.stringOrNull("message") ?: err.toString()
                emit(ChatEvent.Error(msg))
                return@runCatching
            }
            // Usage may appear on the last chunk (or alongside choices) when
            // stream_options.include_usage = true. Emit it whenever present.
            // v4-flash and one-api proxies often send `"usage": null` on
            // early chunks — `as?` makes that case a silent skip instead of
            // a hard cast failure.
            obj.objOrNull("usage")?.let { u ->
                emit(parseUsage(u))
            }
            // Some proxies (notably deepseek-v4-flash) send leading metadata
            // chunks with no `choices` at all, then start streaming. Skip
            // anything that doesn't carry a choice.
            val choice = obj.arrOrNull("choices")
                ?.firstOrNull()
                ?.let { it as? JsonObject }
                ?: return@runCatching
            val delta = choice.objOrNull("delta") ?: choice.objOrNull("message")
            if (delta != null) {
                val reasoning = delta.stringOrNull("reasoning_content")
                val content = delta.stringOrNull("content")
                if (!reasoning.isNullOrEmpty()) emit(ChatEvent.Reasoning(reasoning))
                if (!content.isNullOrEmpty()) emit(ChatEvent.Delta(content))
                delta.arrOrNull("tool_calls")?.let { toolBuf.absorb(it) }
            }
        }.onFailure { t ->
            // Only surface *real* parse failures (malformed JSON, etc.); the
            // common JsonNull-shaped chunks are handled above without
            // reaching this branch.
            emit(ChatEvent.Error("流块解析失败：${t.message ?: t.javaClass.simpleName}\n片段: ${data.take(160)}"))
        }
    }

    private fun JsonObject.objOrNull(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.arrOrNull(key: String): JsonArray? = this[key] as? JsonArray
    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
    private fun JsonObject.longOrNull(key: String): Long? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull?.toLong()

    /**
     * Parse a `usage` object into a [ChatEvent.Usage] regardless of
     * which vendor convention the proxy follows.
     *
     * DeepSeek's native API ships separate `prompt_cache_hit_tokens`
     * and `prompt_cache_miss_tokens`. OpenAI's spec (and many proxies
     * — one-api / openrouter / v4-flash) uses a nested
     * `prompt_tokens_details.cached_tokens` instead, with no explicit
     * miss field. When only one side is reported, infer the other from
     * the prompt total so the popup actually shows useful numbers
     * instead of 缓存命中 = 0.
     */
    private fun parseUsage(u: JsonObject): ChatEvent.Usage {
        val prompt = u.longOrNull("prompt_tokens") ?: 0L
        val completion = u.longOrNull("completion_tokens") ?: 0L
        val total = u.longOrNull("total_tokens") ?: (prompt + completion)
        val rawHit = u.longOrNull("prompt_cache_hit_tokens")
            ?: u.objOrNull("prompt_tokens_details")?.longOrNull("cached_tokens")
            ?: u.objOrNull("prompt_tokens_details")?.longOrNull("cache_hit_tokens")
        val rawMiss = u.longOrNull("prompt_cache_miss_tokens")
            ?: u.objOrNull("prompt_tokens_details")?.longOrNull("cache_miss_tokens")
        val hit = rawHit ?: 0L
        val miss = when {
            rawMiss != null -> rawMiss
            rawHit != null && prompt > 0L -> (prompt - hit).coerceAtLeast(0L)
            else -> 0L
        }
        val hitFinal = when {
            rawHit != null -> hit
            rawMiss != null && prompt > 0L -> (prompt - miss).coerceAtLeast(0L)
            else -> 0L
        }
        return ChatEvent.Usage(
            prompt = prompt,
            completion = completion,
            total = total,
            cacheHit = hitFinal,
            cacheMiss = miss
        )
    }

    private fun emitCompleteJson(
        text: String,
        toolBuf: ToolCallBuffer,
        emit: (ChatEvent) -> Unit
    ) {
        runCatching {
            val obj = (json.parseToJsonElement(text) as? JsonObject) ?: run {
                emit(ChatEvent.Error("响应不是 JSON 对象：${text.take(160)}"))
                return
            }
            obj.objOrNull("error")?.let { err ->
                emit(ChatEvent.Error(err.stringOrNull("message") ?: err.toString()))
                return
            }
            obj.objOrNull("usage")?.let { u ->
                emit(parseUsage(u))
            }
            val choice = obj.arrOrNull("choices")?.firstOrNull()?.let { it as? JsonObject }
            if (choice == null) {
                emit(ChatEvent.Error("响应不是合法的 chat.completion 结构：${text.take(160)}"))
                return
            }
            val msg = choice.objOrNull("message") ?: choice.objOrNull("delta")
            val content = msg?.stringOrNull("content").orEmpty()
            val reasoning = msg?.stringOrNull("reasoning_content")
            if (!reasoning.isNullOrEmpty()) emit(ChatEvent.Reasoning(reasoning))
            if (content.isNotEmpty()) emit(ChatEvent.Delta(content))
            msg?.arrOrNull("tool_calls")?.let { toolBuf.absorb(it) }
        }.onFailure {
            emit(ChatEvent.Error("响应解析失败：${it.message ?: it.javaClass.simpleName}"))
        }
    }

    /** Plain non-streaming completion used for context compaction. */
    suspend fun complete(
        baseUrl: String,
        apiKey: String,
        model: String,
        system: String,
        user: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val msgs = listOf(
                ChatMessageDto(role = "system", content = system),
                ChatMessageDto(role = "user", content = user)
            )
            val payload = buildRequestBody(model, msgs, 0.4f, stream = false, tools = null)
            val req = Request.Builder()
                .url(chatUrl(baseUrl))
                .header("Authorization", "Bearer $apiKey")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error(formatHttpError(resp.code, text))
                val obj = json.parseToJsonElement(text).jsonObject
                obj["choices"]?.jsonArray?.firstOrNull()
                    ?.jsonObject?.get("message")?.jsonObject
                    ?.get("content")?.jsonPrimitive?.contentOrNull
                    ?.trim().orEmpty()
            }
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

    // ---- Models / Balance -------------------------------------------------

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
                        ModelInfo(id = id, ownedBy = o["owned_by"]?.jsonPrimitive?.contentOrNull)
                    }.sortedBy { it.id }
                }
            }
        }

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

/**
 * Accumulator for the streamed tool_calls deltas. Each chunk may supply only
 * partial fields (id / name in the first chunk, arguments fragments in
 * later ones, keyed by `index`).
 */
private class ToolCallBuffer {
    private val byIndex = sortedMapOf<Int, MutableMap<String, String>>()

    fun absorb(arr: JsonArray) {
        for (entry in arr) {
            val obj = entry.jsonObject
            val idx = obj["index"]?.jsonPrimitive?.intOrNull ?: 0
            val slot = byIndex.getOrPut(idx) { mutableMapOf() }
            obj["id"]?.jsonPrimitive?.contentOrNull?.let { slot["id"] = it }
            val fn = obj["function"]?.jsonObject
            fn?.get("name")?.jsonPrimitive?.contentOrNull?.let { slot["name"] = it }
            fn?.get("arguments")?.jsonPrimitive?.contentOrNull?.let { frag ->
                slot["arguments"] = (slot["arguments"].orEmpty()) + frag
            }
        }
    }

    fun build(): List<ToolCall> = byIndex.values.mapNotNull { m ->
        val id = m["id"] ?: return@mapNotNull null
        val name = m["name"] ?: return@mapNotNull null
        ToolCall(id = id, name = name, arguments = m["arguments"].orEmpty())
    }
}
