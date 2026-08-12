package com.biji.notes.net

import com.biji.notes.sandbox.LocalSandbox
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
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
    const val LIST_PKGS = "list_packages"
    const val INSTALL_PKG = "install_package"

    const val CONTAINER_EXEC = "container_exec"
    const val CONTAINER_TASK = "container_task"
    const val CONTAINER_INFO = "container_info"
    const val CONTAINER_MANAGE = "container_manage"
    const val CONTAINER_ENV = "container_env"

    /** Build the full tools list shown to the model, depending on
     *  which feature toggles are on. */
    fun definitions(webSearch: Boolean, sandbox: Boolean): List<JsonObject> = buildList {
        if (webSearch) addAll(browsingDefinitions())
        if (sandbox) { addAll(sandboxDefinitions()); addAll(containerDefinitions()) }
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
                    "在沙箱内解压一个 zip 归档。纯 Java ZipInputStream，强制走沙箱边界检查（zip-slip 防护）。返回解压出的全部条目。"
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
                put("name", LIST_PKGS)
                put(
                    "description",
                    "列出 biji 自带工具包目录的所有可安装项（busybox / tcc / make / git / python / ripgrep / fd / jq / curl 等），每项包含 id / 大小 / 已装状态。无参数。"
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
                put("name", INSTALL_PKG)
                put(
                    "description",
                    "安装命令行工具到 bootstrap/bin，装完立刻能被 run_shell_command 调用。两种用法：(1) package_id = list_packages 里的 id，装内置目录里的工具；(2) url + bin_name = 直接装任意**静态链接**的 aarch64 Linux 二进制（可以先用 web_search 找到某个工具的 GitHub release 直链，注意必须是 static / musl 构建的裸二进制，不能是 .deb/.rpm，动态链接的在 Android 上跑不起来）。"
                )
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("package_id", buildJsonObject {
                            put("type", "string")
                            put("description", "list_packages 里的 id（和 url 二选一）")
                        })
                        put("url", buildJsonObject {
                            put("type", "string")
                            put("description", "静态二进制的直链下载地址（和 package_id 二选一）")
                        })
                        put("bin_name", buildJsonObject {
                            put("type", "string")
                            put("description", "用 url 时必填：装好后的命令名，比如 \"rg\"")
                        })
                    })
                    put("required", buildJsonArray {})
                })
            })
        },
        buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", CHECK_ENV)
                put(
                    "description",
                    "探测当前 shell 环境：是否 root、biji 的终端环境是否就绪、常见工具链（sh / bash / git / python / gcc / clang / cmake / make / node 等）哪些在 PATH 上。在调用 run_shell_command 前先问一次，避免发出注定失败的命令。无参数。"
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
                    "执行 shell 命令。PATH 含 biji 下载的 toybox bootstrap (ls / cat / grep / find / sed / awk / tar / wget 等) 和 Android 系统 /system/bin。30 秒超时；返回 stdout / stderr / exit 码。命令本身不受沙箱限制（除危险命令黑名单：rm -rf /、mkfs、dd of=/dev/、shred、fork bomb、关机/重启）。不确定工具是否可用就先 check_environment。"
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

    /**
     * 本地容器。和 [RUN_SHELL] 的区别值得说清楚，否则模型会乱挑：
     * run_shell_command 每次 fork 一个新 sh，`cd` 和 `export` 转头就没；
     * container_exec 跑在一个**长驻** sh 里，cwd / 变量 / shell 函数都留着，
     * 而且能拿 $BIJI_EXEC 这个可执行目录来跑自己编出来的二进制。
     */
    fun containerDefinitions(): List<JsonObject> = listOf(
        buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", CONTAINER_EXEC)
                put(
                    "description",
                    "在本会话的本地容器里执行命令。容器是一个**长驻 shell**：cd 过去的目录、export 的变量、定义的函数下一次调用还在，" +
                        "适合「进目录 → 编译 → 跑」这种多步流程。目录布局：\$BIJI_WORK 工作区（和 read_file/write_file 看到的是同一棵树，" +
                        "相对路径就写 work/xxx）、\$BIJI_TMP 临时区、\$BIJI_BIN 已装命令、\$BIJI_EXEC 可执行暂存区。" +
                        "**自己编出来的二进制必须先拷到 \$BIJI_EXEC 才能跑** —— 工作区在外部存储上是 noexec 的。" +
                        "默认 30 秒超时，上限 120 秒；编译内核那种长活儿用 container_task。"
                )
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("command", buildJsonObject {
                            put("type", "string")
                            put("description", "完整 shell 命令，可以带管道 / && / 多行")
                        })
                        put("cwd", buildJsonObject {
                            put("type", "string")
                            put("description", "（可选）只影响这一条命令的工作目录；相对路径按 work/ 解析。不给就用会话当前 cwd。")
                        })
                        put("timeout_ms", buildJsonObject {
                            put("type", "integer")
                            put("description", "（可选）超时毫秒，默认 30000，上限 120000")
                        })
                        put("stdin", buildJsonObject {
                            put("type", "string")
                            put("description", "（可选）喂给命令标准输入的文本")
                        })
                    })
                    put("required", buildJsonArray { add("command") })
                })
            })
        },
        buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", CONTAINER_TASK)
                put(
                    "description",
                    "容器后台任务：跑超过 exec 超时的活儿（编译大工程、下载大文件、跑服务）。" +
                        "action=start 启动并返回 task_id + 头一秒输出（能立刻暴露 command not found）；" +
                        "action=poll 增量拉输出，把上次返回的 next_stdout/next_stderr 原样传回来就不会重复；" +
                        "action=stop 杀掉整棵进程树；action=list 看有哪些任务。"
                )
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("action", buildJsonObject {
                            put("type", "string")
                            put("description", "start / poll / stop / list")
                        })
                        put("command", buildJsonObject {
                            put("type", "string")
                            put("description", "action=start 时必填：要后台跑的命令")
                        })
                        put("name", buildJsonObject {
                            put("type", "string")
                            put("description", "（可选）给任务起个名字，方便 list 时认出来")
                        })
                        put("cwd", buildJsonObject {
                            put("type", "string")
                            put("description", "（可选）任务工作目录，相对路径按 work/ 解析")
                        })
                        put("task_id", buildJsonObject {
                            put("type", "string")
                            put("description", "action=poll / stop 时必填")
                        })
                        put("from_stdout", buildJsonObject {
                            put("type", "integer")
                            put("description", "poll 的 stdout 游标，传上次返回的 next_stdout；第一次给 0")
                        })
                        put("from_stderr", buildJsonObject {
                            put("type", "integer")
                            put("description", "poll 的 stderr 游标，传上次返回的 next_stderr；第一次给 0")
                        })
                        put("max_runtime_ms", buildJsonObject {
                            put("type", "integer")
                            put("description", "（可选）start 时的最长运行时间，默认 15 分钟，上限 1 小时")
                        })
                        put("include_finished", buildJsonObject {
                            put("type", "boolean")
                            put("description", "（可选）action=list 时是否带上已结束的任务，默认 true")
                        })
                    })
                    put("required", buildJsonArray { add("action") })
                })
            })
        },
        buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", CONTAINER_INFO)
                put(
                    "description",
                    "容器全景：各目录绝对路径、是否已初始化、磁盘占用与剩余空间、已装命令清单、持久环境变量（值已掩码）、长驻 shell 状态、后台任务列表。" +
                        "开工前先调一次，比一条条 command -v 省事。"
                )
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("refresh", buildJsonObject {
                            put("type", "boolean")
                            put("description", "（可选）true = 重新统计磁盘占用（要遍历目录，慢一点）")
                        })
                    })
                    put("required", buildJsonArray {})
                })
            })
        },
        buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", CONTAINER_MANAGE)
                put(
                    "description",
                    "容器生命周期。action=init 建目录 + 写 README（幂等，第一次用容器时调）；" +
                        "action=reset 清空 work/ tmp/ 和任务日志、停掉全部任务，**已装的命令不会被删**；" +
                        "action=restart_session 重启长驻 shell（卡死或想清掉一堆临时变量时用，cwd 和持久 env 会复原）；" +
                        "action=close_session 关掉 shell 释放内存，下次 exec 会自动再拉起来。"
                )
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("action", buildJsonObject {
                            put("type", "string")
                            put("description", "init / reset / restart_session / close_session")
                        })
                        put("force", buildJsonObject {
                            put("type", "boolean")
                            put("description", "（可选）action=init 时重写 README")
                        })
                        put("keep_env", buildJsonObject {
                            put("type", "boolean")
                            put("description", "（可选）action=reset 时是否保留持久环境变量，默认 true")
                        })
                    })
                    put("required", buildJsonArray { add("action") })
                })
            })
        },
        buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", CONTAINER_ENV)
                put(
                    "description",
                    "读写容器的**持久**环境变量（写进 .env，重启 shell 后仍在；容器活着时立刻 export 生效）。" +
                        "只想在当前这轮命令里用的变量直接在 container_exec 里 export 就行，不用调这个。" +
                        "返回掩码后的全量 env —— 名字里带 KEY/TOKEN/SECRET/PASSWORD 的值会被打码。"
                )
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("set", buildJsonObject {
                            put("type", "object")
                            put("description", "要设置的键值对，例如 {\"CC\":\"tcc\"}")
                        })
                        put("unset", buildJsonObject {
                            put("type", "array")
                            put("description", "要删除的变量名列表")
                            put("items", buildJsonObject { put("type", "string") })
                        })
                    })
                    put("required", buildJsonArray {})
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
    private val sandbox: LocalSandbox,
    private val pkg: com.biji.notes.sandbox.BijiPkg,
    private val containers: com.biji.notes.sandbox.AiContainerManager
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
            Tools.LIST_PKGS -> runListPackages()
            Tools.INSTALL_PKG -> runInstallPackage(args)
            Tools.CONTAINER_EXEC -> runContainerExec(args, projectFolder)
            Tools.CONTAINER_TASK -> runContainerTask(args, projectFolder)
            Tools.CONTAINER_INFO -> runContainerInfo(args, projectFolder)
            Tools.CONTAINER_MANAGE -> runContainerManage(args, projectFolder)
            Tools.CONTAINER_ENV -> runContainerEnv(args, projectFolder)
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

    private fun runListPackages(): Pair<String, JsonObject> {
        val pkgs = pkg.catalogue
        val ui = buildJsonObject {
            put("kind", Tools.LIST_PKGS)
            put("packages", buildJsonArray {
                pkgs.forEach { p ->
                    add(buildJsonObject {
                        put("id", p.id)
                        put("title", p.title)
                        put("description", p.description)
                        put("size", p.sizeLabel)
                        put("bin", p.binName)
                        put("installed", pkg.isInstalled(p))
                    })
                }
            })
        }
        val forModel = buildString {
            appendLine("设备架构: ${pkg.arch}")
            val bins = pkg.installedBinaries()
            if (bins.isNotEmpty()) {
                appendLine("已可用命令 (${bins.size}): " + bins.joinToString(" ").take(1200))
            }
            appendLine("biji 工具包目录：")
            pkgs.forEach { p ->
                val tag = if (pkg.isInstalled(p)) "✓" else " "
                appendLine("  $tag ${p.id.padEnd(10)} ${p.sizeLabel.padEnd(10)} ${p.title} — ${p.description}")
            }
        }
        return forModel to ui
    }

    private suspend fun runInstallPackage(args: JsonObject): Pair<String, JsonObject> {
        val id = args["package_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val url = args["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val binName = args["bin_name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        // 自定义 URL 分支：AI 自己在网上找到的静态二进制。
        if (id.isBlank() && url.isNotBlank()) {
            if (binName.isBlank()) {
                return "用 url 安装时必须给 bin_name" to
                    errorJson(Tools.INSTALL_PKG, "missing bin_name", "url" to url)
            }
            val (ok, msg) = pkg.installCustom(binName, url)
            val ui = buildJsonObject {
                put("kind", Tools.INSTALL_PKG)
                put("bin", binName); put("url", url); put("installed", ok)
            }
            return (if (ok) "$msg —— run_shell_command 现在可以直接调用 $binName。" else msg) to ui
        }
        val p = pkg.catalogue.firstOrNull { it.id == id }
            ?: return "没有这个包: $id（也可以用 url + bin_name 装任意静态二进制）" to
                errorJson(Tools.INSTALL_PKG, "unknown", "package_id" to id)
        val ok = runCatching { pkg.install(p) }.getOrDefault(false)
        val ui = buildJsonObject {
            put("kind", Tools.INSTALL_PKG)
            put("package_id", id)
            put("installed", ok)
        }
        val links = (pkg.progress.value as? com.biji.notes.sandbox.BijiPkg.Progress.Done)?.extraLinks ?: 0
        val msg = when {
            ok && links > 0 -> "已安装 ${p.binName}，并展开了 $links 个子命令，run_shell_command 可直接调用。"
            ok -> "已安装 ${p.binName}，run_shell_command 现在可以直接调用。"
            else -> "安装失败: $id — " +
                ((pkg.progress.value as? com.biji.notes.sandbox.BijiPkg.Progress.Failed)?.message ?: "未知错误")
        }
        return msg to ui
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
            val haveBashEnv = map["bash"] != null
            appendLine("环境探测 (root=${if (useRoot) "on" else "off"}, biji 终端环境=${if (haveBashEnv) "ready" else "not installed"}):")
            for (t in tools) {
                val path = map[t]
                appendLine("  ${if (path != null) "✓" else "✗"} $t  ${path.orEmpty()}")
            }
            if (!haveBashEnv) {
                appendLine()
                appendLine("提示：bash 不在 PATH 上。biji 自带 toybox 提供基础命令（ls / cat / grep / find / awk / sed / tar / wget 等）已可用。要想用 gcc / git / python 等重型工具，需要用户自己装 Termux 并开启 Root 模式让 biji 调用。")
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

    // =================================================================
    //  本地容器
    // =================================================================

    private fun JsonObject.str(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.bool(key: String): Boolean? =
        this[key]?.jsonPrimitive?.booleanOrNull

    private fun JsonObject.long(key: String): Long? =
        this[key]?.jsonPrimitive?.longOrNull

    private suspend fun runContainerExec(args: JsonObject, folder: String?): Pair<String, JsonObject> {
        val cmd = args.str("command").orEmpty()
        if (cmd.isBlank()) {
            return "command 不能为空" to errorJson(Tools.CONTAINER_EXEC, "Empty command")
        }
        val r = containers.get(folder).exec(
            command = cmd,
            cwd = args.str("cwd"),
            timeoutMs = args.long("timeout_ms") ?: args.long("timeoutMs")
                ?: com.biji.notes.sandbox.AiContainer.DEFAULT_EXEC_TIMEOUT_MS,
            stdin = args.str("stdin")
        )
        val ui = buildJsonObject {
            put("kind", Tools.CONTAINER_EXEC)
            put("command", r.command)
            put("workingDir", r.cwd)
            put("exitCode", r.exitCode)
            put("durationMs", r.durationMs)
            put("timedOut", r.timedOut)
            put("stdout", r.stdout)
            put("stderr", r.stderr)
            if (r.truncated) put("truncated", true)
            if (r.blocked) {
                put("blocked", true)
                r.blockedReason?.let { put("blockedReason", it) }
            }
            if (r.sessionRestarted) put("sessionRestarted", true)
            r.error?.let { put("error", it) }
        }
        val forModel = buildString {
            append("$ ${r.command}    (cwd=${r.cwd}, exit=${r.exitCode}")
            if (r.timedOut) append(", TIMEOUT")
            if (r.blocked) append(", BLOCKED")
            if (r.truncated) append(", 输出已截断")
            appendLine(", ${r.durationMs}ms)")
            if (r.sessionRestarted) appendLine("（shell 在本次调用中被重新拉起，上一轮的 cwd / 未持久化变量已丢失）")
            r.error?.let { appendLine("执行失败: $it") }
            if (r.blocked) appendLine("命令被拒绝执行：${r.blockedReason ?: "危险命令"}")
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
        return forModel to ui
    }

    private suspend fun runContainerTask(args: JsonObject, folder: String?): Pair<String, JsonObject> {
        val c = containers.get(folder)
        return when (args.str("action")?.lowercase()) {
            "start" -> {
                val cmd = args.str("command").orEmpty()
                if (cmd.isBlank()) {
                    return "action=start 需要 command" to
                        errorJson(Tools.CONTAINER_TASK, "Empty command", "action" to "start")
                }
                val r = c.startTask(
                    command = cmd,
                    name = args.str("name"),
                    cwd = args.str("cwd"),
                    maxRuntimeMs = args.long("max_runtime_ms")
                        ?: com.biji.notes.sandbox.AiContainer.DEFAULT_TASK_RUNTIME_MS
                )
                val ui = buildJsonObject {
                    put("kind", Tools.CONTAINER_TASK)
                    put("action", "start")
                    put("taskId", r.taskId)
                    put("pid", r.pid)
                    put("state", r.state.name)
                    put("command", cmd)
                    put("headOutput", r.headOutput)
                    r.exitCode?.let { put("exitCode", it) }
                    r.error?.let { put("error", it) }
                }
                val forModel = buildString {
                    if (r.error != null) {
                        appendLine("任务启动失败: ${r.error}")
                    } else {
                        appendLine("任务已启动 task_id=${r.taskId} pid=${r.pid} 状态=${r.state}")
                        if (r.state != com.biji.notes.sandbox.TaskState.RUNNING) {
                            appendLine("（已经结束了，exit=${r.exitCode}）")
                        } else {
                            appendLine("用 container_task action=poll task_id=${r.taskId} from_stdout=0 from_stderr=0 拉输出。")
                        }
                    }
                    if (r.headOutput.isNotBlank()) {
                        appendLine("--- 起步输出 ---")
                        append(r.headOutput)
                    }
                }
                forModel to ui
            }

            "poll" -> {
                val id = args.str("task_id").orEmpty()
                val p = c.pollTask(
                    taskId = id,
                    fromStdout = args.long("from_stdout") ?: 0L,
                    fromStderr = args.long("from_stderr") ?: 0L
                ) ?: return "没有这个任务: $id" to
                    errorJson(Tools.CONTAINER_TASK, "unknown task", "task_id" to id)
                val ui = buildJsonObject {
                    put("kind", Tools.CONTAINER_TASK)
                    put("action", "poll")
                    put("taskId", p.taskId)
                    put("state", p.state.name)
                    p.exitCode?.let { put("exitCode", it) }
                    put("stdout", p.stdout)
                    put("stderr", p.stderr)
                    put("nextStdout", p.nextStdout)
                    put("nextStderr", p.nextStderr)
                    put("finished", p.finished)
                    put("moreAvailable", p.moreAvailable)
                    put("durationMs", p.durationMs)
                    if (p.droppedStdout > 0) put("droppedStdout", p.droppedStdout)
                    if (p.droppedStderr > 0) put("droppedStderr", p.droppedStderr)
                }
                val forModel = buildString {
                    append("任务 ${p.taskId} 状态=${p.state}")
                    p.exitCode?.let { append(" exit=$it") }
                    appendLine(" 已运行 ${p.durationMs}ms")
                    if (p.droppedStdout > 0 || p.droppedStderr > 0) {
                        appendLine("（输出太快，有 ${p.droppedStdout + p.droppedStderr} 个字符被环形缓冲滚掉了）")
                    }
                    if (p.stdout.isNotEmpty()) {
                        appendLine("--- stdout ---")
                        append(p.stdout)
                        if (!p.stdout.endsWith("\n")) appendLine()
                    }
                    if (p.stderr.isNotEmpty()) {
                        appendLine("--- stderr ---")
                        append(p.stderr)
                        if (!p.stderr.endsWith("\n")) appendLine()
                    }
                    if (!p.finished) {
                        appendLine("下次 poll 传 from_stdout=${p.nextStdout} from_stderr=${p.nextStderr}" +
                            if (p.moreAvailable) "（还有更多输出没拉完，可以立刻再 poll 一次）" else "")
                    }
                }
                forModel to ui
            }

            "stop" -> {
                val id = args.str("task_id").orEmpty()
                val t = c.stopTask(id)
                    ?: return "没有这个任务: $id" to
                        errorJson(Tools.CONTAINER_TASK, "unknown task", "task_id" to id)
                val ui = buildJsonObject {
                    put("kind", Tools.CONTAINER_TASK)
                    put("action", "stop")
                    put("taskId", t.taskId)
                    put("state", t.state.name)
                    t.exitCode?.let { put("exitCode", it) }
                }
                "任务 ${t.taskId} 已停止，状态=${t.state}" to ui
            }

            "list" -> {
                val includeFinished = args.bool("include_finished") ?: true
                val list = c.listTasks(includeFinished)
                val ui = buildJsonObject {
                    put("kind", Tools.CONTAINER_TASK)
                    put("action", "list")
                    put("tasks", buildJsonArray { list.forEach { add(taskJson(it)) } })
                }
                val forModel = if (list.isEmpty()) "当前没有后台任务。" else buildString {
                    appendLine("后台任务 (${list.size}):")
                    list.forEach {
                        appendLine("  ${it.taskId}  ${it.state}${it.exitCode?.let { c2 -> "(exit=$c2)" } ?: ""}  " +
                            "${it.durationMs}ms  ${it.name}  $ ${it.command.take(120)}")
                    }
                }
                forModel to ui
            }

            else -> "action 必须是 start / poll / stop / list 之一" to
                errorJson(Tools.CONTAINER_TASK, "bad action")
        }
    }

    private fun taskJson(t: com.biji.notes.sandbox.TaskInfo): JsonObject = buildJsonObject {
        put("taskId", t.taskId)
        put("name", t.name)
        put("command", t.command)
        put("workDir", t.workDir)
        put("state", t.state.name)
        t.exitCode?.let { put("exitCode", it) }
        put("pid", t.pid)
        put("startedAt", t.startedAt)
        put("durationMs", t.durationMs)
    }

    private suspend fun runContainerInfo(args: JsonObject, folder: String?): Pair<String, JsonObject> {
        val i = containers.get(folder).info(refresh = args.bool("refresh") ?: false)
        val ui = buildJsonObject {
            put("kind", Tools.CONTAINER_INFO)
            put("folder", i.folder)
            put("root", i.root)
            put("workDir", i.workDir)
            put("tmpDir", i.tmpDir)
            put("binDir", i.binDir)
            put("execDir", i.execDir)
            put("initialized", i.initialized)
            put("workEntries", i.workEntries)
            put("workBytes", i.workBytes)
            put("tmpBytes", i.tmpBytes)
            put("logBytes", i.logBytes)
            put("freeDiskBytes", i.freeDiskBytes)
            put("sessionAlive", i.sessionAlive)
            put("sessionCwd", i.sessionCwd)
            put("sessionPid", i.sessionPid)
            put("sessionUptimeMs", i.sessionUptimeMs)
            put("commands", buildJsonArray { i.commands.forEach { add(it) } })
            put("env", buildJsonObject { i.env.forEach { (k, v) -> put(k, v) } })
            put("tasks", buildJsonArray { i.tasks.forEach { add(taskJson(it)) } })
        }
        val forModel = buildString {
            appendLine("容器 \"${i.folder}\"${if (i.initialized) "" else "（尚未 init）"}")
            appendLine("  work   \$BIJI_WORK = ${i.workDir}  (${i.workEntries} 项 / ${human(i.workBytes)}${if (i.walkTruncated) "，统计已截断" else ""})")
            appendLine("  tmp    \$BIJI_TMP  = ${i.tmpDir}  (${human(i.tmpBytes)})")
            appendLine("  bin    \$BIJI_BIN  = ${i.binDir}")
            appendLine("  exec   \$BIJI_EXEC = ${i.execDir}   ← 自编译的二进制拷到这里才能跑")
            appendLine("  任务日志 ${human(i.logBytes)}，磁盘剩余 ${human(i.freeDiskBytes)}")
            appendLine("  长驻 shell: " + if (i.sessionAlive)
                "运行中 pid=${i.sessionPid} cwd=${i.sessionCwd} 已存活 ${i.sessionUptimeMs / 1000}s"
            else "未启动（下次 container_exec 会自动拉起）")
            appendLine("  可用命令 (${i.commands.size}): " +
                (if (i.commands.isEmpty()) "无 —— 先 install_package 装 toybox"
                else i.commands.joinToString(" ").take(1500)))
            if (i.env.isNotEmpty()) {
                appendLine("  持久环境变量: " + i.env.entries.joinToString(", ") { "${it.key}=${it.value}" }.take(800))
            }
            val running = i.tasks.filter { it.state == com.biji.notes.sandbox.TaskState.RUNNING }
            if (i.tasks.isNotEmpty()) {
                appendLine("  后台任务 ${i.tasks.size} 个（运行中 ${running.size}）:")
                i.tasks.take(20).forEach {
                    appendLine("    ${it.taskId}  ${it.state}  ${it.name}  $ ${it.command.take(100)}")
                }
            }
        }
        return forModel to ui
    }

    private suspend fun runContainerManage(args: JsonObject, folder: String?): Pair<String, JsonObject> {
        val c = containers.get(folder)
        return when (args.str("action")?.lowercase()) {
            "init" -> {
                val r = c.init(force = args.bool("force") ?: false)
                val ui = buildJsonObject {
                    put("kind", Tools.CONTAINER_MANAGE)
                    put("action", "init")
                    put("root", r.root)
                    put("readme", r.readmePath)
                    put("created", buildJsonArray { r.created.forEach { add(it) } })
                    put("skipped", buildJsonArray { r.skipped.forEach { add(it) } })
                    r.error?.let { put("error", it) }
                }
                val forModel = if (r.error != null) "容器初始化失败: ${r.error}" else buildString {
                    appendLine("容器已就绪: ${r.root}")
                    if (r.created.isNotEmpty()) appendLine("  新建: ${r.created.joinToString(", ")}")
                    if (r.skipped.isNotEmpty()) appendLine("  已存在: ${r.skipped.joinToString(", ")}")
                    appendLine("说明文件: ${r.readmePath}（用 container_exec \"cat \$BIJI_CONTAINER/README.md\" 可以读）")
                }
                forModel to ui
            }

            "reset" -> {
                val r = c.reset(keepEnv = args.bool("keep_env") ?: true)
                val ui = buildJsonObject {
                    put("kind", Tools.CONTAINER_MANAGE)
                    put("action", "reset")
                    put("deletedEntries", r.deletedEntries)
                    put("freedBytes", r.freedBytes)
                    put("stoppedTasks", r.stoppedTasks)
                    put("keptCommands", r.keptCommands)
                    put("envCleared", r.envCleared)
                    r.error?.let { put("error", it) }
                }
                val forModel = if (r.error != null) "容器重置失败: ${r.error}" else
                    "容器已重置：删除 ${r.deletedEntries} 项 / 释放 ${human(r.freedBytes)}，" +
                        "停止 ${r.stoppedTasks} 个任务；保留 ${r.keptCommands} 个已装命令" +
                        (if (r.envCleared) "，环境变量已清空。" else "，环境变量保留。")
                forModel to ui
            }

            "restart_session", "restart" -> {
                val s = c.restartSession()
                sessionResult("restart_session", s)
            }

            "close_session", "close" -> {
                val s = c.closeSession()
                sessionResult("close_session", s)
            }

            else -> "action 必须是 init / reset / restart_session / close_session 之一" to
                errorJson(Tools.CONTAINER_MANAGE, "bad action")
        }
    }

    private fun sessionResult(
        action: String,
        s: com.biji.notes.sandbox.SessionStatus
    ): Pair<String, JsonObject> {
        val ui = buildJsonObject {
            put("kind", Tools.CONTAINER_MANAGE)
            put("action", action)
            put("alive", s.alive)
            put("cwd", s.cwd)
            put("pid", s.pid)
            put("uptimeMs", s.uptimeMs)
            s.error?.let { put("error", it) }
        }
        val forModel = when {
            s.error != null -> "$action 失败: ${s.error}"
            s.alive -> "shell 运行中 pid=${s.pid} cwd=${s.cwd}"
            else -> "shell 已关闭，下次 container_exec 会自动重新拉起。"
        }
        return forModel to ui
    }

    private suspend fun runContainerEnv(args: JsonObject, folder: String?): Pair<String, JsonObject> {
        val set = (args["set"] as? JsonObject)?.mapNotNull { (k, v) ->
            v.jsonPrimitive.contentOrNull?.let { k to it }
        }?.toMap().orEmpty()
        val unset = (args["unset"] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            .orEmpty()
        val env = containers.get(folder).updateEnv(set, unset)
        val ui = buildJsonObject {
            put("kind", Tools.CONTAINER_ENV)
            put("env", buildJsonObject { env.forEach { (k, v) -> put(k, v) } })
        }
        val forModel = buildString {
            if (set.isNotEmpty()) appendLine("已设置: ${set.keys.joinToString(", ")}")
            if (unset.isNotEmpty()) appendLine("已删除: ${unset.joinToString(", ")}")
            if (env.isEmpty()) appendLine("当前没有持久环境变量。")
            else {
                appendLine("持久环境变量 (${env.size})：")
                env.forEach { (k, v) -> appendLine("  $k=$v") }
            }
        }
        return forModel to ui
    }

    private fun human(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format("%.1f GB", bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> String.format("%.1f MB", bytes / (1L shl 20).toDouble())
        bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun errorJson(kind: String, message: String, vararg extra: Pair<String, String>): JsonObject =
        buildJsonObject {
            put("kind", kind)
            put("error", message)
            for ((k, v) in extra) put(k, v)
        }
}
