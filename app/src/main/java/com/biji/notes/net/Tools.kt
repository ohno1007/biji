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
    const val DELETE_PATH = "delete_path"
    const val MOVE_PATH = "move_path"
    const val COPY_PATH = "copy_path"
    const val EXTRACT_ZIP = "extract_zip"
    const val RUN_SHELL = "run_shell_command"
    const val CHECK_ENV = "check_environment"

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
                put("name", DELETE_PATH)
                put(
                    "description",
                    "删除本地项目沙箱中的文件或目录（目录会递归删除）。返回是否删除成功。"
                )
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("path", buildJsonObject {
                            put("type", "string")
                            put("description", "要删除的相对路径")
                        })
                    })
                    put("required", buildJsonArray { add("path") })
                })
            })
        },
        buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", MOVE_PATH)
                put(
                    "description",
                    "在沙箱内移动或重命名文件 / 目录。src 与 dst 都是相对路径。会自动创建目标父目录。"
                )
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("src", buildJsonObject {
                            put("type", "string"); put("description", "源相对路径")
                        })
                        put("dst", buildJsonObject {
                            put("type", "string"); put("description", "目标相对路径")
                        })
                    })
                    put("required", buildJsonArray { add("src"); add("dst") })
                })
            })
        },
        buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", COPY_PATH)
                put(
                    "description",
                    "在沙箱内复制文件或目录。会自动创建目标父目录。目录会递归复制。"
                )
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("src", buildJsonObject {
                            put("type", "string"); put("description", "源相对路径")
                        })
                        put("dst", buildJsonObject {
                            put("type", "string"); put("description", "目标相对路径")
                        })
                    })
                    put("required", buildJsonArray { add("src"); add("dst") })
                })
            })
        },
        buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", EXTRACT_ZIP)
                put(
                    "description",
                    "在沙箱内解压一个 zip 归档。不依赖 shell / Termux —— 用 Java 自带 ZipInputStream，强制走沙箱边界检查（zip-slip 防护）。返回解压出的全部条目。"
                )
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("archive", buildJsonObject {
                            put("type", "string"); put("description", "归档相对路径，例如 foo.zip")
                        })
                        put("dest", buildJsonObject {
                            put("type", "string"); put("description", "目标目录相对路径")
                        })
                    })
                    put("required", buildJsonArray { add("archive"); add("dest") })
                })
            })
        },
        buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", CHECK_ENV)
                put(
                    "description",
                    "探测当前设备的开发环境：是否 root、Termux 是否安装、Android shell 与（如果可用的话）Termux 中的常见工具链（gcc / clang / cmake / make / git / python / python3 / node / ndk-build）是否在 PATH 上。在需要编译、调试或调用任何非系统二进制之前先调用一次，避免向 run_shell_command 发出注定失败的命令。无参数。"
                )
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {})
                    put("required", buildJsonArray {})
                })
            })
        },
        buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", RUN_SHELL)
                put(
                    "description",
                    "通过 `sh -c` 执行 shell 命令。biji 用 Termux 同款的用户态做法：把一份自带的多工具二进制（toybox 之类）下载到 app-private 的 bootstrap/bin 目录，再把这个目录 prepend 到 PATH 上 —— 因此 ls / cat / grep / find / sed / awk / tar / wget 等基础命令在没装 Termux、没 root 时也能用。Root 模式还会再把 Termux 的 /data/data/com.termux/files/usr/bin 加到 PATH（如果用户在 Termux 里 pkg install 了 gcc / clang / cmake / make / git / python，这时就能直接调用）。命令本身不受沙箱限制（除危险命令黑名单：rm -rf /、mkfs、dd of=/dev/、shred、fork bomb、关机/重启）。30 秒超时；返回 stdout / stderr / exit 码。不确定工具是否可用就先 check_environment。"
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
    suspend fun run(
        call: ToolCall,
        projectFolder: String? = null,
        useRoot: Boolean = false
    ): Pair<String, JsonObject> {
        val args = runCatching { json.parseToJsonElement(call.arguments).jsonObject }
            .getOrElse { JsonObject(emptyMap()) }
        return when (call.name) {
            Tools.WEB_SEARCH -> runWebSearch(args)
            Tools.READ_URL -> runReadUrl(args)
            Tools.LIST_DIRECTORY -> runListDirectory(args, projectFolder)
            Tools.READ_FILE -> runReadFile(args, projectFolder)
            Tools.WRITE_FILE -> runWriteFile(args, projectFolder)
            Tools.DELETE_PATH -> runDeletePath(args, projectFolder)
            Tools.MOVE_PATH -> runMovePath(args, projectFolder)
            Tools.COPY_PATH -> runCopyPath(args, projectFolder)
            Tools.EXTRACT_ZIP -> runExtractZip(args, projectFolder)
            Tools.RUN_SHELL -> runShellCommand(args, projectFolder, useRoot)
            Tools.CHECK_ENV -> runCheckEnvironment(useRoot)
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

    private suspend fun runDeletePath(args: JsonObject, folder: String?): Pair<String, JsonObject> {
        val path = args["path"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return runCatching { sandbox.deleteEntry(folder, path) }.fold(
            onSuccess = { ok ->
                val ui = buildJsonObject {
                    put("kind", Tools.DELETE_PATH)
                    put("path", path)
                    put("deleted", ok)
                }
                (if (ok) "已删除: $path" else "未找到: $path") to ui
            },
            onFailure = { err ->
                val msg = err.message ?: err.javaClass.simpleName
                "delete_path 失败: $msg" to errorJson(Tools.DELETE_PATH, msg, "path" to path)
            }
        )
    }

    private suspend fun runMovePath(args: JsonObject, folder: String?): Pair<String, JsonObject> {
        val src = args["src"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val dst = args["dst"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return runCatching { sandbox.moveEntry(folder, src, dst) }.fold(
            onSuccess = {
                val ui = buildJsonObject {
                    put("kind", Tools.MOVE_PATH); put("src", src); put("dst", dst)
                }
                "已移动: $src -> $dst" to ui
            },
            onFailure = { err ->
                val msg = err.message ?: err.javaClass.simpleName
                "move_path 失败: $msg" to errorJson(Tools.MOVE_PATH, msg, "src" to src, "dst" to dst)
            }
        )
    }

    private suspend fun runCopyPath(args: JsonObject, folder: String?): Pair<String, JsonObject> {
        val src = args["src"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val dst = args["dst"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return runCatching { sandbox.copyEntry(folder, src, dst) }.fold(
            onSuccess = {
                val ui = buildJsonObject {
                    put("kind", Tools.COPY_PATH); put("src", src); put("dst", dst)
                }
                "已复制: $src -> $dst" to ui
            },
            onFailure = { err ->
                val msg = err.message ?: err.javaClass.simpleName
                "copy_path 失败: $msg" to errorJson(Tools.COPY_PATH, msg, "src" to src, "dst" to dst)
            }
        )
    }

    private suspend fun runExtractZip(args: JsonObject, folder: String?): Pair<String, JsonObject> {
        val archive = args["archive"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val dest = args["dest"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return runCatching { sandbox.extractZip(folder, archive, dest) }.fold(
            onSuccess = { entries ->
                val ui = buildJsonObject {
                    put("kind", Tools.EXTRACT_ZIP)
                    put("archive", archive)
                    put("dest", dest)
                    put("count", entries.size)
                    put("entries", buildJsonArray {
                        for (e in entries.take(200)) {
                            add(buildJsonObject {
                                put("path", e.path)
                                put("isDirectory", e.isDirectory)
                                put("sizeBytes", e.sizeBytes)
                            })
                        }
                    })
                }
                val forModel = buildString {
                    appendLine("解压完成: $archive -> $dest (${entries.size} 项)")
                    entries.take(50).forEach { appendLine("  ${if (it.isDirectory) "[dir]" else "${it.sizeBytes}B"}  ${it.path}") }
                    if (entries.size > 50) appendLine("  …还有 ${entries.size - 50} 项")
                }
                forModel to ui
            },
            onFailure = { err ->
                val msg = err.message ?: err.javaClass.simpleName
                "extract_zip 失败: $msg" to errorJson(
                    Tools.EXTRACT_ZIP, msg, "archive" to archive, "dest" to dest
                )
            }
        )
    }

    private suspend fun runCheckEnvironment(useRoot: Boolean): Pair<String, JsonObject> {
        // Single shell invocation so we don't pay 1 fork + 1 su prompt
        // per binary. `command -v` is POSIX, present in toybox/busybox
        // and Termux's bash alike.
        val tools = listOf(
            "sh", "ls", "cat", "grep", "awk", "sed", "find", "tar", "curl", "wget",
            "gcc", "clang", "g++", "cmake", "make", "ninja", "ar", "ld", "ndk-build",
            "git", "python", "python3", "pip", "pip3", "node", "npm", "ruby", "java"
        )
        val script = tools.joinToString("\n") {
            "printf '%s\\t' '$it'; command -v $it 2>/dev/null || echo NONE"
        }
        val r = runCatching {
            sandbox.runShell(folder = null, command = script, asRoot = useRoot, timeoutMs = 8_000L)
        }.getOrNull()
        val map = mutableMapOf<String, String?>()
        r?.stdout?.lineSequence()?.forEach { line ->
            val idx = line.indexOf('\t')
            if (idx > 0) {
                val name = line.substring(0, idx)
                val path = line.substring(idx + 1).trim()
                map[name] = if (path == "NONE" || path.isEmpty()) null else path
            }
        }
        val termuxLikely = map.values.any { it != null && it.startsWith("/data/data/com.termux/") }
        val ui = buildJsonObject {
            put("kind", Tools.CHECK_ENV)
            put("useRoot", useRoot)
            put("termuxDetected", termuxLikely)
            put("tools", buildJsonObject {
                for (t in tools) {
                    val path = map[t]
                    put(t, buildJsonObject {
                        put("present", path != null)
                        if (path != null) put("path", path)
                    })
                }
            })
            r?.let {
                put("exitCode", it.exitCode)
                if (it.stderr.isNotEmpty()) put("stderr", it.stderr.take(2000))
            }
        }
        val forModel = buildString {
            appendLine("环境探测 (root=${if (useRoot) "on" else "off"}, Termux=${if (termuxLikely) "detected" else "not visible"}):")
            for (t in tools) {
                val path = map[t]
                appendLine("  ${if (path != null) "✓" else "✗"} $t  ${path.orEmpty()}")
            }
            if (!termuxLikely && !useRoot) {
                appendLine()
                appendLine("提示：用户没开 Root 模式，所以 Termux 里的 pkg install 工具（gcc / cmake 等）不可见。基础 toybox 命令（ls / cat / grep / find / awk / sed）仍然可用。如需调用 native 编译器，建议指导用户在「设置 → 工程模式 → Root 模式」里授权 su，并确保 Termux 已 pkg install 相应工具。")
            } else if (!termuxLikely && useRoot) {
                appendLine()
                appendLine("提示：root 已授予，但没检测到 Termux 路径。可建议用户安装 Termux 并 `pkg install build-essential cmake clang make git python`。")
            }
        }
        return forModel to ui
    }

    private suspend fun runShellCommand(
        args: JsonObject,
        folder: String?,
        useRoot: Boolean
    ): Pair<String, JsonObject> {
        val cmd = args["command"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val cwd = args["cwd"]?.jsonPrimitive?.contentOrNull
        val timeoutMs = args["timeoutMs"]?.jsonPrimitive?.intOrNull?.toLong()?.coerceIn(500, 60_000)
            ?: 30_000L
        if (cmd.isBlank()) {
            return "command 不能为空" to errorJson(Tools.RUN_SHELL, "Empty command")
        }
        return runCatching { sandbox.runShell(folder, cmd, cwd, timeoutMs, asRoot = useRoot) }.fold(
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
                    if (r.ranAsRoot) put("ranAsRoot", true)
                }
                val forModel = buildString {
                    appendLine("$ ${if (r.ranAsRoot) "sudo " else ""}${r.command}    (cwd=${r.workingDir}, exit=${r.exitCode}${if (r.timedOut) ", TIMEOUT" else ""}${if (r.blocked) ", BLOCKED" else ""}, ${r.durationMs}ms)")
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
