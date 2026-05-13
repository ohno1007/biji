package com.biji.notes.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The model-visible catalogue of tools. We expose just two – web search and
 * page reader – because together they cover "find current info" and "look
 * at a specific page" without overwhelming the model with choices.
 */
object Tools {

    const val WEB_SEARCH = "web_search"
    const val READ_URL = "read_url"

    fun definitions(): List<JsonObject> = listOf(
        buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", WEB_SEARCH)
                put("description",
                    "联网搜索。需要查找实时、最新、未知信息时调用。返回若干网页结果（标题/URL/摘要）。"
                )
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("query", buildJsonObject {
                            put("type", "string")
                            put("description", "搜索关键词")
                        })
                    })
                    put("required", buildJsonArray { add("query") })
                })
            })
        },
        buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", READ_URL)
                put("description",
                    "抓取并提取一个 URL 的正文内容，用于在已有搜索结果或用户给定链接的基础上读取详细信息。"
                )
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("url", buildJsonObject {
                            put("type", "string")
                            put("description", "完整网页 URL")
                        })
                    })
                    put("required", buildJsonArray { add("url") })
                })
            })
        }
    )
}

/** A finalised tool call assembled from streamed deltas. */
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String
)

class ToolExecutor(
    private val search: WebSearchService
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    /** Execute a tool. Returns:
     *   first  = a human-readable JSON content for the `tool` message
     *            (sent back to the model)
     *   second = a structured JSON payload for the chat UI to render
     *            (search results / extracted article).  */
    suspend fun run(call: ToolCall): Pair<String, JsonObject> {
        val args = runCatching { json.parseToJsonElement(call.arguments).jsonObject }
            .getOrElse { JsonObject(emptyMap()) }
        return when (call.name) {
            Tools.WEB_SEARCH -> {
                val q = args["query"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val results = search.search(q)
                val ui = buildJsonObject {
                    put("kind", Tools.WEB_SEARCH)
                    put("query", q)
                    put("results", buildJsonArray {
                        results.forEach { r ->
                            add(buildJsonObject {
                                put("title", r.title)
                                put("url", r.url)
                                put("snippet", r.snippet)
                                put("source", r.source)
                            })
                        }
                    })
                }
                val forModel = if (results.isEmpty()) {
                    "搜索没有返回结果（可能被限流或网络不可达）。建议告诉用户当前无法联网搜索。"
                } else buildString {
                    appendLine("搜索关键词: $q")
                    results.forEachIndexed { i, r ->
                        appendLine("[${i + 1}] ${r.title}")
                        appendLine("URL: ${r.url}")
                        if (r.snippet.isNotBlank()) appendLine("摘要: ${r.snippet}")
                        appendLine()
                    }
                }
                forModel to ui
            }

            Tools.READ_URL -> {
                val url = args["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val art = search.read(url)
                val ui = buildJsonObject {
                    put("kind", Tools.READ_URL)
                    put("url", url)
                    if (art != null) {
                        art.title?.let { put("title", it) }
                        art.byline?.let { put("byline", it) }
                        art.siteName?.let { put("siteName", it) }
                        art.excerpt?.let { put("excerpt", it) }
                        put("textContent", art.textContent)
                    } else {
                        put("error", "无法抓取或解析该 URL")
                    }
                }
                val forModel = if (art == null) {
                    "无法抓取或解析 $url。"
                } else buildString {
                    art.title?.let { appendLine("标题: $it") }
                    art.siteName?.let { appendLine("站点: $it") }
                    art.byline?.let { appendLine("作者: $it") }
                    appendLine()
                    append(art.textContent)
                }
                forModel to ui
            }

            else -> "Unknown tool: ${call.name}" to buildJsonObject {
                put("kind", "error")
                put("message", "Unknown tool: ${call.name}")
            }
        }
    }
}
