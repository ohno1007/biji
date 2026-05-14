package com.biji.notes.net

import com.biji.notes.sandbox.LocalSandbox
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The model-visible catalogue of tools.
 *
 * Two layers:
 *  - [browsingDefinitions]: always-on web tools (search + read_url).
 *  - [sandboxDefinitions]: developer-mode trio (read / write / shell)
 *    enabled by the "Developer 工程模式" toggle in Settings.
 */
object Tools {

    const val WEB_SEARCH = "web_search"
    const val READ_URL = "read_url"

    const val LIST_DIRECTORY = "list_directory"
    const val READ_FILE = "read_file"
    const val WRITE_FILE = "write_file"
    const val RUN_SHELL = "run_shell_command"

    /** Build the full tools list shown to the model, depending on
     *  which feature toggles are on. */
    fun definitions(webSearch: Boolean, sandbox: Boolean): List<JsonObject> = buildList {
        if (webSearch) addAll(browsingDefinitions())
        if (sandbox) addAll(sandboxDefinitions())
    }

    fun browsingDefinitions(): List<JsonObject> = listOf(
        buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", WEB_SEARCH)
                put(
                    "description",
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
                put(
                    "description",
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

    fun sandboxDefinitions(): List<JsonObject> = listOf(
        buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", LIST_DIRECTORY)
                put(
                    "description",
                    "列出本地项目沙箱中某个目录里的文件与子目录。路径相对于沙箱根。空字符串表示根。"
                )
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("path", buildJsonObject {
                            put("type", "string")
                            put("description", "目录相对路径，例如 \"src\" 或 \"\" 表示根。")
                        })
                    })
                    put("required", buildJsonArray {})
                })
            })
        },
        buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", READ_FILE)
                put(
                    "description",
                    "读取本地项目沙箱中的一个文件（最多 64 KB），返回 UTF-8 文本。"
                )
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("path", buildJsonObject {
                            put("type", "string")
                            put("description", "文件相对路径")
                        })
                    })
                    put("required", buildJsonArray { add("path") })
                })
            })
        },
        buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", WRITE_FILE)
                put(
                    "description",
                    "写入或覆盖本地项目沙箱中的一个文件。父目录自动创建。"
                )
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("path", buildJsonObject {
                            put("type", "string")
                            put("description", "文件相对路径")
                        })
                        put("content", buildJsonObject {
                            put("type", "string")
                            put("description", "要写入的完整 UTF-8 文本")
                        })
                        put("append", buildJsonObject {
                            put("type", "boolean")
                            put("description", "true = 追加在末尾，false = 整体覆盖。默认 false。")
                        })
                    })
                    put("required", buildJsonArray { add("path"); add("content") })
                })
            })
        },
        buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", RUN_SHELL)
                put(
                    "description",
                    "在全局 Android 进程环境中通过 `sh -c` 执行 shell 命令。默认 cwd 为当前会话绑定的项目目录；命令本身不受沙箱限制（除非匹配危险命令黑名单：rm -rf /、mkfs、dd of=/dev/、shred、fork bomb、关机/重启）。30 秒超时；返回 stdout / stderr / exit 码。"
                )
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("command", buildJsonObject {
                            put("type", "string")
                            put("description", "完整 shell 命令字符串")
                        })
                        put("cwd", buildJsonObject {
                            put("type", "string")
                            put("description", "（可选）工作目录相对路径")
                        })
                        put("timeoutMs", buildJsonObject {
                            put("type", "integer")
                            put("description", "（可选）超时毫秒数，默认 30000，上限 60000")
                        })
                    })
                    put("required", buildJsonArray { add("command") })
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
    private val search: WebSearchService,
    private val sandbox: LocalSandbox
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    /** Execute a tool against the project folder bound to the active
     *  conversation. [projectFolder] is the folder name selected for
     *  that conversation (null/empty falls back to the default root).
     *
     *  Returns:
     *   first  = a human-readable JSON content for the `tool` message
     *            (sent back to the model)
     *   second = a structured JSON payload for the chat UI to render
     *            (search results / extracted article / shell run).  */
    suspend fun run(call: ToolCall, projectFolder: String? = null): Pair<String, JsonObject> {
        val args = runCatching { json.parseToJsonElement(call.arguments).jsonObject }
            .getOrElse { JsonObject(emptyMap()) }
        return when (call.name) {
            Tools.WEB_SEARCH -> runWebSearch(args)
            Tools.READ_URL -> runReadUrl(args)
            Tools.LIST_DIRECTORY -> runListDirectory(args, projectFolder)
            Tools.READ_FILE -> runReadFile(args, projectFolder)
            Tools.WRITE_FILE -> runWriteFile(args, projectFolder)
            Tools.RUN_SHELL -> runShellCommand(args, projectFolder)
            else -> "Unknown tool: ${call.name}" to buildJsonObject {
                put("kind", "error")
                put("message", "Unknown tool: ${call.name}")
            }
        }
    }

    private suspend fun runWebSearch(args: JsonObject): Pair<String, JsonObject> {
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
        return forModel to ui
    }

    private suspend fun runReadUrl(args: JsonObject): Pair<String, JsonObject> {
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
        return forModel to ui
    }

    private suspend fun runListDirectory(args: JsonObject, folder: String?): Pair<String, JsonObject> {
        val path = args["path"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return runCatching { sandbox.listDirectory(folder, path) }.fold(
            onSuccess = { entries ->
                val ui = buildJsonObject {
                    put("kind", Tools.LIST_DIRECTORY)
                    put("path", path)
                    put("entries", buildJsonArray {
                        entries.forEach { e ->
                            add(buildJsonObject {
                                put("name", e.name)
                                put("path", e.path)
                                put("isDirectory", e.isDirectory)
                                put("sizeBytes", e.sizeBytes)
                                put("lastModified", e.lastModified)
                            })
                        }
                    })
                }
                val forModel = buildString {
                    appendLine("目录 \"$path\" 内容 (${entries.size} 项):")
                    entries.forEach { e ->
                        val tag = if (e.isDirectory) "[dir]" else "${e.sizeBytes}B"
                        appendLine("  $tag  ${e.path}")
                    }
                }
                forModel to ui
            },
            onFailure = { err ->
                val msg = err.message ?: err.javaClass.simpleName
                "list_directory 失败: $msg" to errorJson(Tools.LIST_DIRECTORY, msg)
            }
        )
    }

    private suspend fun runReadFile(args: JsonObject, folder: String?): Pair<String, JsonObject> {
        val path = args["path"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return runCatching { sandbox.readFile(folder, path) }.fold(
            onSuccess = { body ->
                val ui = buildJsonObject {
                    put("kind", Tools.READ_FILE)
                    put("path", path)
                    put("bytes", body.length)
                    put("content", body)
                }
                buildString {
                    appendLine("文件 \"$path\" (${body.length} bytes):")
                    appendLine("------")
                    append(body)
                } to ui
            },
            onFailure = { err ->
                val msg = err.message ?: err.javaClass.simpleName
                "read_file 失败: $msg" to errorJson(Tools.READ_FILE, msg, "path" to path)
            }
        )
    }

    private suspend fun runWriteFile(args: JsonObject, folder: String?): Pair<String, JsonObject> {
        val path = args["path"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val content = args["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val append = args["append"]?.jsonPrimitive?.contentOrNull?.equals("true", true) ?: false
        // Capture the pre-edit content so the editor can render a diff
        // afterwards. Silently swallow read errors — if the file
        // doesn't exist yet the snapshot is just empty.
        val priorContent = runCatching { sandbox.readFile(folder, path, maxBytes = 256 * 1024) }
            .getOrDefault("")
        sandbox.captureSnapshot(folder, path, priorContent)
        // Cap each side at 32 KB when round-tripping through the DB so a
        // huge file rewrite can't bloat the chat message blob.
        val capBytes = 32 * 1024
        val beforeCapped = priorContent.take(capBytes)
        val afterCapped = content.take(capBytes)
        val truncated = priorContent.length > capBytes || content.length > capBytes
        return runCatching { sandbox.writeFile(folder, path, content, append) }.fold(
            onSuccess = {
                sandbox.markAiEdited(folder, path)
                val ui = buildJsonObject {
                    put("kind", Tools.WRITE_FILE)
                    put("path", path)
                    put("bytes", content.length)
                    put("append", append)
                    put("before", beforeCapped)
                    put("after", afterCapped)
                    put("truncated", truncated)
                }
                val verb = if (append) "追加" else "写入"
                "$verb 完成: $path (${content.length} bytes)" to ui
            },
            onFailure = { err ->
                val msg = err.message ?: err.javaClass.simpleName
                "write_file 失败: $msg" to errorJson(Tools.WRITE_FILE, msg, "path" to path)
            }
        )
    }

    private suspend fun runShellCommand(args: JsonObject, folder: String?): Pair<String, JsonObject> {
        val cmd = args["command"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val cwd = args["cwd"]?.jsonPrimitive?.contentOrNull
        val timeoutMs = args["timeoutMs"]?.jsonPrimitive?.intOrNull?.toLong()?.coerceIn(500, 60_000)
            ?: 30_000L
        if (cmd.isBlank()) {
            return "command 不能为空" to errorJson(Tools.RUN_SHELL, "Empty command")
        }
        return runCatching { sandbox.runShell(folder, cmd, cwd, timeoutMs) }.fold(
            onSuccess = { r ->
                val ui = buildJsonObject {
                    put("kind", Tools.RUN_SHELL)
                    put("command", r.command)
                    put("workingDir", r.workingDir)
                    put("exitCode", r.exitCode)
                    put("durationMs", r.durationMs)
                    put("timedOut", r.timedOut)
                    put("stdout", r.stdout)
                    put("stderr", r.stderr)
                    if (r.blocked) {
                        put("blocked", true)
                        r.blockedReason?.let { put("blockedReason", it) }
                    }
                }
                val forModel = buildString {
                    appendLine("$ ${r.command}    (cwd=${r.workingDir}, exit=${r.exitCode}${if (r.timedOut) ", TIMEOUT" else ""}${if (r.blocked) ", BLOCKED" else ""}, ${r.durationMs}ms)")
                    if (r.blocked) {
                        appendLine("命令被拒绝执行：${r.blockedReason ?: "危险命令"}")
                    }
                    if (r.stdout.isNotEmpty()) {
                        appendLine("--- stdout ---")
                        append(r.stdout)
                        if (!r.stdout.endsWith("\n")) appendLine()
                    }
                    if (r.stderr.isNotEmpty()) {
                        appendLine("--- stderr ---")
                        append(r.stderr)
                    }
                }
                forModel to ui
            },
            onFailure = { err ->
                val msg = err.message ?: err.javaClass.simpleName
                "run_shell_command 失败: $msg" to errorJson(Tools.RUN_SHELL, msg)
            }
        )
    }

    private fun errorJson(kind: String, message: String, vararg extra: Pair<String, String>): JsonObject =
        buildJsonObject {
            put("kind", kind)
            put("error", message)
            for ((k, v) in extra) put(k, v)
        }
}
