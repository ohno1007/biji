package com.biji.notes.net

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
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

    private val factory = EventSources.createFactory(http)

    /**
     * Streams a chat completion. `model = "deepseek-reasoner"` enables the
     * reasoning model whose chunks may carry a `reasoning_content` field;
     * `"deepseek-chat"` is the standard one. Emits granular events until
     * either [ChatEvent.Done] or [ChatEvent.Error] terminates the stream.
     */
    fun stream(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessageDto>,
        temperature: Float? = null
    ): Flow<ChatEvent> = callbackFlow {
        if (apiKey.isBlank()) {
            trySend(ChatEvent.Error("未配置 API Key，请到「设置」填入。"))
            close()
            return@callbackFlow
        }

        val body = json
            .encodeToString(
                ChatRequest(
                    model = model,
                    messages = messages,
                    stream = true,
                    // Reasoner ignores temperature; harmless to omit.
                    temperature = if (model == "deepseek-reasoner") null else temperature
                )
            )
            .toRequestBody("application/json".toMediaType())

        val url = baseUrl.trimEnd('/') + "/v1/chat/completions"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/event-stream")
            .header("Authorization", "Bearer $apiKey")
            .post(body)
            .build()

        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                if (data == "[DONE]") {
                    trySend(ChatEvent.Done); close(); return
                }
                runCatching {
                    val obj = json.parseToJsonElement(data).jsonObject
                    val choices = obj["choices"]?.jsonArray ?: return@runCatching
                    val delta = choices.firstOrNull()?.jsonObject
                        ?.get("delta")?.jsonObject
                    val reasoning = delta?.get("reasoning_content")?.jsonPrimitive?.contentOrNull
                    val content = delta?.get("content")?.jsonPrimitive?.contentOrNull
                    if (!reasoning.isNullOrEmpty()) trySend(ChatEvent.Reasoning(reasoning))
                    if (!content.isNullOrEmpty()) trySend(ChatEvent.Delta(content))
                }
            }

            override fun onClosed(eventSource: EventSource) {
                trySend(ChatEvent.Done); close()
            }

            override fun onFailure(
                eventSource: EventSource,
                t: Throwable?,
                response: Response?
            ) {
                val msg = buildString {
                    if (response != null) {
                        append("HTTP ${response.code}")
                        val errBody = runCatching { response.body?.string() }.getOrNull()
                        if (!errBody.isNullOrBlank()) {
                            append(": ")
                            append(parseErrorMessage(errBody))
                        }
                    } else if (t != null) {
                        append(t.message ?: t.javaClass.simpleName)
                    } else {
                        append("网络错误")
                    }
                }
                trySend(ChatEvent.Error(msg))
                close()
            }
        }

        val source = factory.newEventSource(request, listener)
        awaitClose { source.cancel() }
    }

    private fun parseErrorMessage(raw: String): String =
        runCatching {
            val o = json.parseToJsonElement(raw).jsonObject
            o["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull ?: raw
        }.getOrElse { raw }
}
