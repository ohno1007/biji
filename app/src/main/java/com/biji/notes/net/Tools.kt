package com.biji.notes.net

import com.biji.notes.nativebridge.NativeGate
import com.biji.notes.sandbox.LocalSandbox
import com.biji.notes.sandbox.TerminalSessionInfo
import com.biji.notes.sandbox.TerminalSessionManager
import com.biji.notes.terminal.TerminalEmulator
import com.biji.notes.ui.terminal.EmulatorSink
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
 * 分层：
 *  - [browsingDefinitions]：常开的联网工具（search + read_url）。
 *  - [sandboxDefinitions]：文件读写，由设置里的「Developer 工程模式」开。
 *  - [rootShellDefinitions]：run_shell_command，再多一道 root 开关，见 [definitions]。
 *  - [containerDefinitions] / [ToolchainTools] / [terminalDefinitions]：容器、
 *    工具链自装配、用户终端，跟着工程模式一起开。
 *
 * 所有描述都遵守一条规矩：**跨工具共通的事实不在这里讲**（目录布局、noexec、
 * 只跑静态 aarch64 / bionic、catalog→install 的顺序、poll 的游标用法）——
 * 那些统一放在系统提示的 CONTAINER_BRIEF 里讲一次。同一句话在四五个描述里
 * 各写一遍，每一轮请求都要重发一遍，而模型只需要读到一次。
 */
object Tools {

    const val WEB_SEARCH = "web_search"
    const val READ_URL = "read_url"

    const val LIST_DIRECTORY = "list_directory"
    const val READ_FILE = "read_file"
    const val WRITE_FILE = "write_file"
    const val DELETE_PATH = "delete_path"
    const val FS_COPY = "fs_copy"

    // move_path / copy_path / extract_zip 三个已经合并进 fs_copy（见 runFsCopy
    // 上面那段注释）。和 CHECK_ENV 那三个一样：名字和执行分支都留着，历史会话
    // 里已经存在的 tool_call 重放时才不会拿到 "Unknown tool"。
    const val MOVE_PATH = "move_path"
    const val COPY_PATH = "copy_path"
    const val EXTRACT_ZIP = "extract_zip"
    const val RUN_SHELL = "run_shell_command"

    // 下面三个**不再进 definitions()**，被 toolchain_probe / toolchain_catalog /
    // toolchain_install 完全取代。名字保留、执行分支保留，只是为了让历史会话里
    // 已经存在的这三种 tool_call 重放时还能拿到结果，而不是 "Unknown tool"。
    //
    // 保留归保留，**它们的返回文案里不能再有一句话把模型往旧路径上带** ——
    // 重放出来的结果同样会进上下文，一句"装工具用 install_package"就够让模型
    // 在当前这一轮去调一个已经不在列表里的工具，白烧一轮。所以
    // runListPackages / runInstallPackage 的文案末尾都缀了一句已停用的说明。
    //
    // 为什么必须从模型可见列表里拿掉，而不是留着当备选：
    //  - install_package 只认 BijiPkg.catalogue 那 14 条，没有 ELF 校验、没有
    //    smoke test、没有账本；模型挑中它就绕开了整套「装完能不能跑」的把关；
    //  - list_packages 的描述里写着 tcc / git / python / ripgrep / fd，而
    //    catalogue 里**根本没有这几项**（见 BijiPkg.kt:66-190），模型照着描述
    //    要 tcc 只会拿到「没有这个包」，白烧一轮；而 ToolchainCatalog.deadEnds
    //    对这几个名字是有明确替代方案的；
    //  - check_environment 走 sandbox.runShell(folder = null)，PATH 和容器实际
    //    执行时的 PATH 不是同一份，会报告「有」然后跑起来 not found。
    //    toolchain_probe 不 fork shell，自己按容器那份目录列表解析。
    const val CHECK_ENV = "check_environment"
    const val LIST_PKGS = "list_packages"
    const val INSTALL_PKG = "install_package"

    const val CONTAINER_EXEC = "container_exec"
    const val CONTAINER_TASK = "container_task"
    const val CONTAINER_INFO = "container_info"
    const val CONTAINER_MANAGE = "container_manage"
    const val CONTAINER_ENV = "container_env"

    const val TERMINAL_SNAPSHOT = "terminal_snapshot"

    /** Build the full tools list shown to the model, depending on
     *  which feature toggles are on.
     *
     *  [useRoot] 单独控制 run_shell_command 发不发。不能直接删掉它 ——
     *  ContainerGuard 把容器里的 su 拦了（"root 模式请走 run_shell_command"），
     *  所以它是模型**唯一**的 root 通道。但 root 默认是关的，对绝大多数会话
     *  那 800 多字节只是让模型多一个选错的机会：它每次 fork 新 sh，cd 和
     *  export 转头就没，模型拿它跑多步流程必然踩坑。
     *
     *  跟着它一起变的还有 CONTAINER_BRIEF —— 那边提到工具名的那一行也是按
     *  useRoot 拼的。两边必须同进同退：brief 里留着名字而列表里没有，模型就会
     *  去调一个不存在的工具，白烧一轮（Tools.kt:45 那段注释里踩过同样的坑）。 */
    fun definitions(
        webSearch: Boolean,
        sandbox: Boolean,
        useRoot: Boolean = false
    ): List<JsonObject> = buildList {
        if (webSearch) addAll(browsingDefinitions())
        if (sandbox) {
            addAll(sandboxDefinitions())
            if (useRoot) addAll(rootShellDefinitions())
            addAll(containerDefinitions())
            // 工具链自装配（toolchain_*）。同包，不需要 import。
            addAll(ToolchainTools.definitions())
            addAll(terminalDefinitions())
        }
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

    /**
     * 沙箱文件工具。
     *
     * 描述统一压过一遍：目录布局、work/ 是 noexec、catalog→install 的先后顺序
     * 这些**每个工具都在讲一遍**的事实，全部搬进了系统提示的 CONTAINER_BRIEF ——
     * 同一件事在 21 个工具描述里重复四遍，每轮都要重发一次。这里只留每个工具
     * 独有的部分。
     */
    fun sandboxDefinitions(): List<JsonObject> = listOf(
        buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", LIST_DIRECTORY)
                put("description", "列出沙箱里某个目录的文件与子目录。")
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("path", buildJsonObject {
                            put("type", "string")
                            put("description", "目录相对路径，\"\" 表示根")
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
                put("description", "读沙箱里的一个文件，返回 UTF-8 文本。上限 64 KB，超了从头截。")
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
                put("description", "写入或覆盖沙箱里的一个文件。父目录自动创建。")
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
                            put("description", "true = 追加，默认 false = 整体覆盖")
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
                // 没有并进 fs_copy 是**故意的**，见 runFsCopy 上面的注释。
                put("description", "删除沙箱里的文件或目录（目录递归删）。不可逆。")
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
                put("name", FS_COPY)
                put(
                    "description",
                    "沙箱内搬文件。op=move 移动或重命名；op=copy 复制（目录递归）；" +
                        "op=unzip 解压 zip（带 zip-slip 边界检查，返回解出来的全部条目）。目标父目录自动创建。"
                )
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("op", buildJsonObject {
                            put("type", "string")
                            put("enum", buildJsonArray { add("move"); add("copy"); add("unzip") })
                        })
                        put("src", buildJsonObject {
                            put("type", "string")
                            put("description", "源相对路径；op=unzip 时是 .zip 的路径")
                        })
                        put("dst", buildJsonObject {
                            put("type", "string")
                            put("description", "目标相对路径；op=unzip 时是解压进的目录")
                        })
                    })
                    put("required", buildJsonArray { add("op"); add("src"); add("dst") })
                })
            })
        }
    )

    /**
     * root shell。**只在设置里开了 root 时才发给模型**，见 [definitions]。
     *
     * 描述里把"每次新 sh"写在最前面：这是它和 container_exec 唯一真正的区别，
     * 也是模型最容易踩的坑 —— 拿它跑 `cd x && make` 的下一条命令时 cwd 已经回去了。
     */
    fun rootShellDefinitions(): List<JsonObject> = listOf(
        buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", RUN_SHELL)
                put(
                    "description",
                    "以 root 执行一条 shell 命令。**每次都是全新的 sh**，cd / export 转头就没，" +
                        "只适合单条独立命令；多步流程一律用 container_exec。" +
                        "这是唯一的 root 通道（容器里的 su 是被拦掉的），不需要 root 就别用它。" +
                        "默认 30 秒超时，上限 60 秒。危险命令有黑名单（rm -rf /、mkfs、dd of=/dev/、shred、fork bomb、关机重启）。"
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
                            put("description", "工作目录相对路径")
                        })
                        put("timeoutMs", buildJsonObject {
                            put("type", "integer")
                            put("description", "超时毫秒，默认 30000，上限 60000")
                        })
                    })
                    put("required", buildJsonArray { add("command") })
                })
            })
        }
    )

    /**
     * 本地容器。
     *
     * 和 [RUN_SHELL] 的区别（root 打开时两个才会同时出现在列表里）：
     * run_shell_command 每次 fork 一个新 sh，`cd` 和 `export` 转头就没；
     * container_exec 跑在一个**长驻** sh 里，cwd / 变量 / shell 函数都留着，
     * 而且能拿 $BIJI_EXEC 这个可执行目录来跑自己编出来的二进制。
     *
     * 目录布局、noexec、"长活儿用 container_task"这些原来五个描述里各讲一遍的
     * 事，现在只在系统提示的 CONTAINER_BRIEF 里讲一次。
     */
    fun containerDefinitions(): List<JsonObject> = listOf(
        buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", CONTAINER_EXEC)
                put(
                    "description",
                    "在容器的长驻 shell 里执行命令。默认 30 秒超时，上限 120 秒。stdout / stderr 各自上限 12000 字。"
                )
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("command", buildJsonObject {
                            put("type", "string")
                            put("description", "完整 shell 命令，可带管道 / && / 多行")
                        })
                        put("cwd", buildJsonObject {
                            put("type", "string")
                            put("description", "只影响这一条命令的工作目录，相对路径按 work/ 解析；不给就用会话当前 cwd")
                        })
                        put("timeout_ms", buildJsonObject {
                            put("type", "integer")
                            put("description", "超时毫秒，默认 30000，上限 120000")
                        })
                        put("stdin", buildJsonObject {
                            put("type", "string")
                            put("description", "喂给命令标准输入的文本")
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
                        "start 返回 task_id + 头一秒输出（能立刻暴露 command not found）；" +
                        "poll 拉增量；stop 杀掉整棵进程树；list 看有哪些任务。"
                )
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("action", buildJsonObject {
                            put("type", "string")
                            // 用 enum 而不是在 description 里写"start / poll / stop / list"：
                            // 长度差不多，但取值是硬约束，模型传错值的概率低一截。
                            put("enum", buildJsonArray {
                                add("start"); add("poll"); add("stop"); add("list")
                            })
                        })
                        put("command", buildJsonObject {
                            put("type", "string")
                            put("description", "start 必填：要后台跑的命令")
                        })
                        put("name", buildJsonObject {
                            put("type", "string")
                            put("description", "任务名，方便 list 时认出来")
                        })
                        put("cwd", buildJsonObject {
                            put("type", "string")
                            put("description", "相对路径按 work/ 解析")
                        })
                        put("task_id", buildJsonObject {
                            put("type", "string")
                            put("description", "poll / stop 必填")
                        })
                        put("from_stdout", buildJsonObject {
                            put("type", "integer")
                            put("description", "传上次的 next_stdout，首次 0")
                        })
                        put("from_stderr", buildJsonObject {
                            put("type", "integer")
                            put("description", "传上次的 next_stderr，首次 0")
                        })
                        put("max_runtime_ms", buildJsonObject {
                            put("type", "integer")
                            put("description", "默认 15 分钟，上限 1 小时")
                        })
                        put("include_finished", buildJsonObject {
                            put("type", "boolean")
                            put("description", "list 时带上已结束的，默认 true")
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
                // 原来这里还有一句"开工前先调一次"。toolchain_probe 和
                // toolchain_manifest 的描述里也各有一句同样意思的话，三个工具
                // 抢同一个位置，模型开局就会把三个全调一遍：两轮白烧，外加三份
                // 返回（这个工具的返回实测能到 2.5 KB）全部压进上下文。
                // 现在"开工前先调"只留在 toolchain_probe 上。
                put(
                    "description",
                    "容器全景：各目录绝对路径、是否已初始化、磁盘占用与剩余、已装命令清单、" +
                        "持久环境变量（值已掩码）、长驻 shell 与后台任务状态。"
                )
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("refresh", buildJsonObject {
                            put("type", "boolean")
                            put("description", "true = 重新统计磁盘占用（要遍历目录，慢一点）")
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
                    "容器生命周期。init 建目录 + 写 README（幂等）；" +
                        "reset 清空 work/ tmp/ 和任务日志、停掉全部任务，**已装的命令不会被删**；" +
                        "restart_session 重启长驻 shell（卡死或想清掉一堆临时变量时用，cwd 和持久 env 会复原）；" +
                        "close_session 关掉 shell 释放内存，下次 exec 自动再拉起。"
                )
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("action", buildJsonObject {
                            put("type", "string")
                            put("enum", buildJsonArray {
                                add("init"); add("reset"); add("restart_session"); add("close_session")
                            })
                        })
                        put("force", buildJsonObject {
                            put("type", "boolean")
                            put("description", "init 时重写 README")
                        })
                        put("keep_env", buildJsonObject {
                            put("type", "boolean")
                            put("description", "reset 时保留持久环境变量，默认 true")
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
                        "只在当前这轮命令里用的变量直接在 container_exec 里 export 就行。" +
                        "返回掩码后的全量 env —— 名字里带 KEY/TOKEN/SECRET/PASSWORD 的值会被打码。"
                )
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("set", buildJsonObject {
                            put("type", "object")
                            put("description", "要设置的键值对，如 {\"CC\":\"zig cc\"}")
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

    /**
     * 用户的终端。**整套沙箱工具里唯一一个什么都不执行的**。
     *
     * 存在的理由：用户在终端里手敲了命令，然后转头问「这报的什么错」。在此之前
     * 模型只能让他把输出复制粘贴过来 —— 而手机上跨应用选中一屏终端文本是件苦差事。
     *
     * 描述里必须把它和 container_exec 的分工写死，否则模型会拿 container_exec
     * 去"重跑一遍看看"：那是**另一个 shell**，cwd、环境变量、跑了一半的进程
     * 全都不一样，重跑的结果和用户屏幕上那条错误可以毫不相干。
     */
    fun terminalDefinitions(): List<JsonObject> = listOf(
        buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", TERMINAL_SNAPSHOT)
                put(
                    "description",
                    "读用户终端**当前屏幕**的纯文本。只读：不执行命令、也不向终端输入。" +
                        "用户说「看下我终端里报的错」「刚才那条命令输出了什么」时用它 —— " +
                        "那是**用户的** shell，别用 container_exec 重跑一遍，两边的 cwd 和环境不是一回事。"
                )
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("session_id", buildJsonObject {
                            put("type", "string")
                            put("description", "终端会话 id；不给就取本笔记下最近活动的那个，返回里会列出还有哪些")
                        })
                        put("scrollback_lines", buildJsonObject {
                            put("type", "integer")
                            put("description", "在可见屏幕之上再往回取多少行历史，默认 0，上限 1000；输出被后面的命令刷走了才需要加")
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
    private val containers: com.biji.notes.sandbox.AiContainerManager,
    /**
     * 工具链自装配。**必须和设置页拿的是同一个实例**（`ToolchainInstaller.get()`
     * 是进程级单例），否则会出现「AI 说装完了但设置页里没有」。
     */
    private val toolchain: com.biji.notes.sandbox.ToolchainInstaller,
    /**
     * 用户终端的会话表，给 [Tools.TERMINAL_SNAPSHOT] 用。
     *
     * 传的是 **provider 而不是实例**，两个理由：
     *  - `ToolExecutor` 在 app 启动早期就被构造，直接传实例等于顺手把整个终端
     *    子系统（连带 TerminfoDb、ContainerLayout）也初始化了，而绝大多数会话
     *    根本不开终端；
     *  - 终端管理器现在挂在哪还没定（`TerminalRuntime` 里有个进程级单例，
     *    文档说该搬到 `BijiApp.terminals`）。provider 让接线方自己决定，
     *    搬家时这边一行都不用动。
     *
     * 默认返回 null = 这台构建没接终端，工具会明说"没接入"而不是崩。
     */
    private val terminals: () -> TerminalSessionManager? = { null }
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
            Tools.FS_COPY -> runFsCopy(args, projectFolder)
            Tools.RUN_SHELL -> runShellCommand(args, projectFolder, useRoot)
            Tools.CHECK_ENV -> runCheckEnvironment(useRoot)
            Tools.LIST_PKGS -> runListPackages()
            Tools.INSTALL_PKG -> runInstallPackage(args)
            Tools.CONTAINER_EXEC -> runContainerExec(args, projectFolder)
            Tools.CONTAINER_TASK -> runContainerTask(args, projectFolder)
            Tools.CONTAINER_INFO -> runContainerInfo(args, projectFolder)
            Tools.CONTAINER_MANAGE -> runContainerManage(args, projectFolder)
            Tools.CONTAINER_ENV -> runContainerEnv(args, projectFolder)
            Tools.TERMINAL_SNAPSHOT -> runTerminalSnapshot(args, projectFolder)
            // toolchain_* 五件套。extraPath 传容器的 execDir：探测不 fork shell，
            // 自己按「binDir + localDir + execDir + 系统 PATH」解析，和容器实际
            // 执行环境对齐（LocalSandbox 的 PATH 那个老 bug 绕开而不是依赖它）。
            in ToolchainTools.NAMES -> {
                val c = containers.get(projectFolder)
                ToolchainTools.run(
                    call.name, args, toolchain,
                    project = projectFolder,
                    workDir = c.layout.work,
                    extraPath = listOf(c.layout.execDir)
                )
            }
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

    /**
     * [Tools.FS_COPY] = 原来的 move_path + copy_path + extract_zip。
     *
     * 为什么敢合：move 和 copy 的 schema 逐字节相同（src + dst 两个必填），
     * 描述只差三个字；extract_zip 也是 src→dst 的形状，只是参数名叫
     * archive / dest。模型本来就要在这三个里挑一个，加个 op 只是把这次选择
     * 显式化了，没有引入新的判断。
     *
     * 为什么 delete_path 没并进来：它是这堆里**唯一不可逆**的操作。跟 copy
     * 共用一个 op 枚举，等于把"枚举值写错"的后果从多一份文件副本，升级成
     * 删掉用户的目录。省那 92 字节不值这个爆炸半径。
     *
     * 老的三个名字仍由 run() 单独分发（定义已经从 definitions() 里拿掉），
     * 历史会话重放时才不会拿到 "Unknown tool"。
     */
    private suspend fun runFsCopy(args: JsonObject, folder: String?): Pair<String, JsonObject> {
        val op = args["op"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        // 模型见过老的 extract_zip，可能顺手把 archive / dest 带过来。认一下，
        // 比让它多烧一轮参数错误便宜。
        val src = args["src"]?.jsonPrimitive?.contentOrNull
            ?: args["archive"]?.jsonPrimitive?.contentOrNull
        val dst = args["dst"]?.jsonPrimitive?.contentOrNull
            ?: args["dest"]?.jsonPrimitive?.contentOrNull
        if (src.isNullOrBlank() || dst.isNullOrBlank()) {
            return "fs_copy 需要 src 和 dst" to errorJson(Tools.FS_COPY, "Missing src or dst")
        }
        // 三个老实现读的参数名不一样，这里一次性把两套名字都填上，免得再动它们。
        val normalized = buildJsonObject {
            put("src", src); put("dst", dst)
            put("archive", src); put("dest", dst)
        }
        return when (op) {
            "move" -> runMovePath(normalized, folder)
            "copy" -> runCopyPath(normalized, folder)
            "unzip" -> runExtractZip(normalized, folder)
            else -> "op 只能是 move / copy / unzip，收到的是 \"$op\"" to
                errorJson(Tools.FS_COPY, "Unknown op: $op")
        }
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
            // 只有历史会话重放才会走到这里。不缀这句的话，这份 14 条的老目录会
            // 被模型当成"现在能装的东西"，而它既没有 ELF 校验也没有 smoke test，
            // 里面还缺 tcc / git / python 这些 toolchain_catalog 明确有替代方案的项。
            appendLine()
            appendLine("（list_packages / install_package 已停用，只为兼容历史会话保留。" +
                "现在查工具用 toolchain_catalog，装用 toolchain_install。）")
        }
        return forModel to ui
    }

    /** 同 [runListPackages]：只有历史会话重放才会走到这里，返回文案末尾统一
     *  缀 [INSTALL_PKG_RETIRED]，免得重放结果把模型带回旧路径。 */
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
            return (if (ok) "$msg —— run_shell_command 现在可以直接调用 $binName。$INSTALL_PKG_RETIRED" else msg) to ui
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
            ok && links > 0 -> "已安装 ${p.binName}，并展开了 $links 个子命令，run_shell_command 可直接调用。$INSTALL_PKG_RETIRED"
            ok -> "已安装 ${p.binName}，run_shell_command 现在可以直接调用。$INSTALL_PKG_RETIRED"
            else -> "安装失败: $id — " +
                ((pkg.progress.value as? com.biji.notes.sandbox.BijiPkg.Progress.Failed)?.message ?: "未知错误") +
                "。$INSTALL_PKG_RETIRED"
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
                appendLine("提示：bash 不在 PATH 上。biji 自带 toybox 提供基础命令（ls / cat / grep / find / awk / sed / tar / wget 等）已可用。要装编译器 / git / 语言运行时，用 toolchain_catalog 查目录、toolchain_install 装到 \$BIJI_BIN —— 不需要 Termux，也不需要 root。")
            }
            appendLine()
            appendLine(CHECK_ENV_RETIRED)
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
                    // 只截给模型看的这一份；ui 那份留全文，用户在聊天界面里
                    // 还是能看到完整输出。
                    if (r.stdout.isNotEmpty()) {
                        appendLine("--- stdout ---")
                        val out = clipShellOutput(r.stdout)
                        append(out)
                        if (!out.endsWith("\n")) appendLine()
                    }
                    if (r.stderr.isNotEmpty()) {
                        appendLine("--- stderr ---")
                        append(clipShellOutput(r.stderr))
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
            // 空列表时原来写的是"先 install_package 装 toybox"，两处错：
            // install_package 已经从模型可见列表里拿掉了；而且 $BIJI_BIN 空
            // 不等于没命令 —— /system/bin 的 toybox 一直在 PATH 上。
            appendLine("  可用命令 (${i.commands.size}): " +
                (if (i.commands.isEmpty())
                    "\$BIJI_BIN 里还没装东西；/system/bin 的 toybox（ls/cat/grep/sed/find/tar…）仍在 PATH 上。" +
                        "要更全的工具用 toolchain_catalog 查、toolchain_install 装。"
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

    // =================================================================
    //  用户终端（只读）
    // =================================================================

    /**
     * 读某个终端会话的屏幕。
     *
     * ## folder 是硬边界
     * 只认**当前笔记名下**的会话。跨笔记读终端等于让 B 会话的模型看见 A 会话的
     * 项目内容（会话之间的项目目录本来就是隔离的，见 [TerminalSessionManager]），
     * 所以 session_id 指到别人家时宁可报错也不给内容。
     *
     * ## 不抛异常
     * 所有失败（没接终端、没有会话、会话刚被关、自检不过）都变成一句给模型的
     * 中文说明 + 一个 error payload。这条路在流式回复中间跑，抛出去就是整轮对话崩掉。
     */
    private fun runTerminalSnapshot(args: JsonObject, folder: String?): Pair<String, JsonObject> {
        val mgr = terminals() ?: return "这台构建没有接入终端子系统，读不到用户的终端。" to
            errorJson(Tools.TERMINAL_SNAPSHOT, "terminal subsystem not wired")

        // 自检不过就一个字都不给。模拟器坏掉时屏幕内容"看着像对的但其实错位"，
        // 把它当事实塞进上下文，比直接拒绝糟糕得多 —— 模型会照着一份伪造的
        // 报错去改代码。降级契约见 NativeGate.vt。
        if (!NativeGate.vt) {
            return "终端渲染自检未通过（VT 模拟器可能画错位），屏幕内容不可信，本工具已停用。" +
                "要确认命令输出，自己用 container_exec 跑一遍。" to
                errorJson(Tools.TERMINAL_SNAPSHOT, "vt self-test failed")
        }

        val key = TerminalSessionManager.folderKey(folder)
        val mine = mgr.sessions.value.filter { it.folder == key }
        val wanted = args.str("session_id")

        val info: TerminalSessionInfo? = if (wanted != null) {
            mine.firstOrNull { it.id == wanted } ?: run {
                // 存在但不属于本笔记 vs 压根不存在，分开说：前者是边界，
                // 后者多半是模型把 id 记串了，提示词不一样模型的下一步也不一样。
                val msg = if (mgr.session(wanted) != null) {
                    "终端 $wanted 属于另一个笔记，跨笔记读终端是硬边界，不提供。"
                } else {
                    "没有 id 为 $wanted 的终端会话（可能已经被关掉了）。"
                }
                return (msg + "\n" + sessionListText(mine)) to
                    errorJson(Tools.TERMINAL_SNAPSHOT, "no such session", "session_id" to wanted)
            }
        } else {
            // 全局 activeId 可能指向别的笔记，所以先在本笔记的子集里找；
            // 找不到就取最后建的那个（列表按创建顺序，最后一个最贴近"刚才在用的"）。
            mine.firstOrNull { it.id == mgr.activeId.value } ?: mine.lastOrNull()
        }

        if (info == null) {
            return "这个笔记下还没有打开过终端。用户得先进终端页开一个，你才看得到屏幕；" +
                "你自己要执行命令用 container_exec。" to buildJsonObject {
                put("kind", Tools.TERMINAL_SNAPSHOT)
                put("sessions", buildJsonArray {})
                put("error", "no terminal session")
            }
        }

        val session = mgr.session(info.id)
            ?: return "终端 ${info.id} 刚刚被关掉了。" to
                errorJson(Tools.TERMINAL_SNAPSHOT, "session closed", "session_id" to info.id)

        // 会话层只认 TerminalSink，屏幕在 UI 侧那个适配器里。这里做一次向下转型
        // 而不是给 TerminalSink 加成员：会话层故意不认识模拟器（无头场景要能塞
        // DiscardSink），为了一个只读工具去改那条契约不划算。转不成就是无头会话。
        val em: TerminalEmulator = (session.sink as? EmulatorSink)?.emulator
            ?: return "终端 ${info.id} 没有屏幕缓冲（无头会话），读不到内容。" to
                errorJson(Tools.TERMINAL_SNAPSHOT, "no screen buffer", "session_id" to info.id)

        val back = (args.long("scrollback_lines") ?: args.long("scrollback") ?: 0L)
            .coerceIn(0L, MAX_SCROLLBACK_LINES.toLong()).toInt()
        val history = em.historyLines
        val raw = if (back <= 0) em.screenText()
        else em.dumpText((history - back).coerceAtLeast(0), em.totalLines - 1)

        // 屏幕是 24 行的定长网格，命令跑完底下一大片是空的。trimEnd 掉，
        // 否则每次快照都往上下文里灌十几个空行。
        val trimmed = raw.trimEnd()
        val text = if (trimmed.length <= MAX_SNAPSHOT_CHARS) trimmed else {
            // 截头不截尾：用户要看的永远是最近发生的事。
            "…（更早的 ${trimmed.length - MAX_SNAPSHOT_CHARS} 个字符已截断）\n" +
                trimmed.takeLast(MAX_SNAPSHOT_CHARS)
        }
        val cursor = em.cursorPosition
        val cursorRow = cursor ushr 16
        val cursorCol = cursor and 0xFFFF

        val ui = buildJsonObject {
            put("kind", Tools.TERMINAL_SNAPSHOT)
            put("sessionId", info.id)
            put("name", info.name)
            put("alive", info.alive)
            put("usingPty", info.usingPty)
            put("status", info.statusText)
            put("cols", em.columns)
            put("rows", em.screenRows)
            put("scrollbackLines", back)
            put("cursorRow", cursorRow)
            put("cursorCol", cursorCol)
            put("text", text)
            put("sessions", buildJsonArray {
                mine.forEach {
                    add(buildJsonObject {
                        put("id", it.id)
                        put("name", it.name)
                        put("status", it.statusText)
                    })
                }
            })
        }

        val forModel = buildString {
            append("终端 ${info.id}「${info.name}」· ${info.statusText}")
            if (info.alive && !info.usingPty) append(" · 兼容模式（无颜色/无真 Ctrl-C）")
            appendLine(" · ${em.columns}×${em.screenRows} · 光标 行${cursorRow} 列${cursorCol}（0 起）")
            if (mine.size > 1) {
                appendLine("本笔记下另有：" +
                    mine.filter { it.id != info.id }
                        .joinToString("、") { "${it.id}「${it.name}」${it.statusText}" })
            }
            appendLine(if (back > 0) "--- 屏幕 + 往回 $back 行 ---" else "--- 可见屏幕 ---")
            if (text.isEmpty()) appendLine("（空的，这个终端还没有任何输出）") else appendLine(text)
        }
        return forModel to ui
    }

    private fun sessionListText(list: List<TerminalSessionInfo>): String =
        if (list.isEmpty()) "这个笔记下现在没有终端会话。"
        else "本笔记下的终端：" + list.joinToString("、") { "${it.id}「${it.name}」${it.statusText}" }

    private fun human(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format("%.1f GB", bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> String.format("%.1f MB", bytes / (1L shl 20).toDouble())
        bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    /**
     * run_shell_command 的输出上限。
     *
     * LocalSandbox 那边是 `readText()` 全量读，一条 `find /` 就能吐几十 KB，
     * 而工具结果会原样进上下文、并且在这一轮**剩下的每一次**调用里重发一遍 ——
     * 代价是轮数的平方。容器那边早就有这个上限了
     * （AiContainer.DEFAULT_MAX_OUTPUT = 12000），这里对齐，模型已经会处理
     * 带截断标记的输出。
     *
     * 保留头部而不是尾部：命令回显和第一条报错都在前面，编译错误看第一条最有用。
     */
    private fun clipShellOutput(s: String): String =
        if (s.length <= MAX_SHELL_OUTPUT_CHARS) s
        else s.take(MAX_SHELL_OUTPUT_CHARS) +
            "\n…（还有 ${s.length - MAX_SHELL_OUTPUT_CHARS} 个字符被截断。" +
            "要看后面的内容，把命令接上 tail / grep 再跑一次。）"

    private fun errorJson(kind: String, message: String, vararg extra: Pair<String, String>): JsonObject =
        buildJsonObject {
            put("kind", kind)
            put("error", message)
            for ((k, v) in extra) put(k, v)
        }

    private companion object {
        /** 见 [clipShellOutput]。 */
        const val MAX_SHELL_OUTPUT_CHARS = 12_000

        /** 缀在 check_environment 的返回后面。理由同 [INSTALL_PKG_RETIRED]：
         *  它也已经从 definitions() 里拿掉了，只有历史会话重放才会走到，
         *  而重放结果照样进上下文。 */
        const val CHECK_ENV_RETIRED =
            "（注意：check_environment 已停用，本条是历史会话的重放结果。" +
                "现在探测环境走 toolchain_probe —— 它按容器实际 PATH 解析，不 fork shell。）"

        /** 缀在 install_package 每一条返回后面。它只在历史会话重放时出现，
         *  但重放结果照样进上下文，不说清楚模型就会接着用这条已停用的路径。 */
        const val INSTALL_PKG_RETIRED =
            "（注意：install_package 已停用，本条是历史会话的重放结果。现在装工具走 toolchain_install。）"

        /** terminal_snapshot 最多往回翻多少行。模拟器默认回滚就几千行，
         *  全量倒出去足够把一轮上下文吃光。 */
        const val MAX_SCROLLBACK_LINES = 1000

        /** 单次快照的字符上限，超了从头截（保留最近的）。80×24 的一屏约 2 KB，
         *  这个上限相当于几十屏，够看完一次编译报错了。 */
        const val MAX_SNAPSHOT_CHARS = 24_000
    }
}
