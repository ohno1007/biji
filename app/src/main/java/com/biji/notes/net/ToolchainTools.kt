package com.biji.notes.net

import com.biji.notes.sandbox.CatalogEntry
import com.biji.notes.sandbox.ElfInfo
import com.biji.notes.sandbox.InstallRequest
import com.biji.notes.sandbox.InstallResult
import com.biji.notes.sandbox.InstalledTool
import com.biji.notes.sandbox.ToolchainCatalog
import com.biji.notes.sandbox.ToolchainInstaller
import com.biji.notes.sandbox.ToolchainJobState
import com.biji.notes.sandbox.ToolchainJobView
import com.biji.notes.sandbox.ToolchainJobs
import com.biji.notes.sandbox.ToolchainStep
import com.biji.notes.sandbox.WrapperSpec
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
import java.io.File

/**
 * 「开发环境由 AI 自主装配」的工具层。
 *
 * 五个工具而不是十五个：和现有 `container_*` 一样用 action 收敛。工具
 * 列表一膨胀模型就选不动了 —— 与其给它十五个精确的小工具，不如给五个
 * 它一定挑得对的大工具。
 *
 * 独立成 object 而不是塞进 [Tools]：那个文件已经 1300 行了，而且这套
 * 工具的 schema、执行、文案是一整块，放一起改起来才不用来回翻。
 * Tools.kt 只要在 definitions() 里 addAll、在 run() 里 when 一行转发。
 */
object ToolchainTools {

    const val PROBE = "toolchain_probe"
    const val CATALOG = "toolchain_catalog"
    const val INSTALL = "toolchain_install"
    const val MANAGE = "toolchain_manage"
    const val MANIFEST = "toolchain_manifest"

    val NAMES = listOf(PROBE, CATALOG, INSTALL, MANAGE, MANIFEST)

    fun handles(name: String): Boolean = name in NAMES

    /** 一次 poll 最多回多少进度日志。进度是几十字一行的中文，4000 字够看
     *  十几分钟的历史，再多就是浪费上下文。 */
    private const val MAX_LOG_CHARS = 4_000

    // =================================================================
    //  schema
    // =================================================================

    fun definitions(): List<JsonObject> = listOf(
        probeDef(), catalogDef(), installDef(), manageDef(), manifestDef()
    )

    private fun fn(name: String, description: String, params: JsonObject): JsonObject =
        buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", name)
                put("description", description)
                put("parameters", params)
            })
        }

    // 下面五段描述都压过一遍。原则：**跨工具重复的事实一句都不留**，
    // 统一搬进系统提示的 CONTAINER_BRIEF —— "只跑静态 aarch64 或 bionic"
    // 在 probe 和 catalog 里各讲了一遍、"work/ 是 noexec" 在三处、
    // "poll 传上次的游标" 在五处、"catalog 查来源 → install 装" 在四处。
    // 每轮请求都要把这些重复内容重发一次，而它们讲一遍就够了。
    //
    // 唯一保留"先调一次"措辞的是 probe：container_info 和 toolchain_manifest
    // 原来也各有一句，三个工具抢同一个位置，模型开局会把三个全调一遍。

    private fun probeDef() = fn(
        PROBE,
        """
        探测开发环境。**动手干活之前先调一次**，别凭记忆假设某个命令存在。

        action=env：一次拿到架构、bin / opt 目录、工具链占用与磁盘剩余、已装工具清单，
        以及一批常用命令在 PATH 上的解析结果。比十条 `command -v` 便宜，而且 PATH
        和 container_exec 实际执行时是同一份。
        action=inspect：检查一个文件能不能在本机跑 —— ELF 类型、有没有 PT_INTERP、
        DT_NEEDED、权限位；smoke=true 还会真跑一次拿版本。装完的东西一定用它验一下。
        """.trimIndent(),
        buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { add("env"); add("inspect") })
                    put("description", "默认 env")
                })
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "inspect 必填。绝对路径、\$BIJI_BIN/xxx、或裸命令名（先按 PATH 解析）")
                })
                put("smoke", buildJsonObject {
                    put("type", "boolean")
                    put("description", "是否真的执行一次验活，默认 true。依次试 --version / -V / --help，6 秒超时")
                })
                put("extra_commands", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject { put("type", "string") })
                    put("description", "env 时额外想探测的命令名，追加在默认清单后")
                })
            })
            put("required", buildJsonArray {})
        }
    )

    private fun catalogDef() = fn(
        CATALOG,
        """
        查内置的**已验证下载源目录**。装任何工具先查这里，别直接 web_search ——
        每条都实测过 ELF 头，网上搜来的多半是 glibc 动态构建，装上跑不了。

        action=search：按命令名、用途或语言找，中英文都认。
        action=resolve：拿某一条的完整安装参数（直接给 toolchain_install 传 catalog_id 更省事）。
        action=list：列全部，带已装标记。

        目录里**没有**、别找了：tcc / gcc / clang（装 zig，用 zig cc）、cmake / ninja
        （改 Makefile 或 build.zig）、Node.js 官方与 Deno（用 bun）。搜这些名字目录会
        直接给替代方案。目录里查不到才 web_search。
        """.trimIndent(),
        buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { add("search"); add("resolve"); add("list") })
                    put("description", "默认 search")
                })
                put("q", buildJsonObject {
                    put("type", "string")
                    put("description", "search 的关键词")
                })
                put("id", buildJsonObject {
                    put("type", "string")
                    put("description", "resolve 的条目 id，如 \"zig\" / \"ripgrep\" / \"bun\"")
                })
                put("category", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        listOf(
                            "shell", "coreutils", "compiler", "runtime", "vcs",
                            "search", "archive", "net", "build", "all"
                        ).forEach { add(it) }
                    })
                    put("description", "按类别过滤")
                })
            })
            put("required", buildJsonArray {})
        }
    )

    private fun installDef() = fn(
        INSTALL,
        """
        下载安装命令行工具，装完立刻在 container_exec / 终端的 PATH 上。
        **搭环境靠它，别让用户去设置里点。**
        最省事只传 catalog_id；手工用法给 url + bin_name。

        **大件异步**：下载超 8 MB 或装完超 24 MB 的（zig / go / bun / uv / gitoxide）只返回
        job_id，之后 action=poll。跑在前台服务里，锁屏不断。**别原地空转 poll**，先去干别的。
        小件一次调用装完。

        **多文件工具（zig / go）必须用 prefix**：整棵树落到 opt/<prefix>/，再用 links 或
        wrapper 暴露入口，别把两万个文件倒进 bin 目录。

        安装是**原子**的：下载 → sha256 → 解包 → ELF 校验 → chmod → smoke test，任何一步
        失败整体回滚并给出具体原因。装完**一定**看 smoke，没过就换来源或换路线。

        起大件前先看 toolchain_probe 的剩余磁盘（zig 解压后 230 MB、go 280 MB、bun 86 MB），
        并一句话告诉用户装什么、多大，然后接着干别的，不用停下等批准。安装串行，排队时
        poll 会回 state=queued。
        """.trimIndent(),
        buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        listOf("install", "poll", "cancel", "list").forEach { add(it) }
                    })
                    put("description", "默认 install")
                })
                put("job_id", buildJsonObject {
                    put("type", "string")
                    put("description", "poll / cancel 必填，来自启动时的返回")
                })
                put("cursor", buildJsonObject {
                    put("type", "integer")
                    put("description", "传上次的 next_cursor，首次 0")
                })
                put("background", buildJsonObject {
                    put("type", "boolean")
                    put("description", "强制后台化；小件默认同步装完，网络差时设 true 立刻拿 job_id")
                })
                put("catalog_id", buildJsonObject {
                    put("type", "string")
                    put("description", "catalog 里的 id（也认命令名，传 \"git\" 命中 gitoxide）。给了它就不用给 url/format/members")
                })
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "直链，必须 https（和 catalog_id 二选一）")
                })
                put("bin_name", buildJsonObject {
                    put("type", "string")
                    put("description", "装好后的主命令名，如 \"rg\"。用 url 时必填")
                })
                put("format", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        listOf("raw", "tar.gz", "tar.xz", "zip", "apk", "auto").forEach { add(it) }
                    })
                    put("description", "默认 auto，按魔数判断")
                })
                put("members", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject { put("type", "string") })
                    put("description", "从归档里挖哪些成员，支持 glob，如 [\"*/rg\"]、[\"**\"]")
                })
                put("strip_components", buildJsonObject {
                    put("type", "integer")
                    put("description", "去掉归档路径前 N 层，默认 0")
                })
                put("prefix", buildJsonObject {
                    put("type", "string")
                    put("description", "非空则整棵树装到 opt/<prefix>/ 而不是直接进 bin")
                })
                put("links", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject { put("type", "string") })
                    put("description", "bin 里建的软链，\"名=>目标\"，如 [\"gofmt=>opt/go/bin/gofmt\"]。带 / 的目标相对工具链根，不带 / 的当 bin 里的兄弟命令")
                })
                put("wrapper", buildJsonObject {
                    put("type", "object")
                    put("description", "sh 包装脚本：{\"name\":\"zig\",\"env\":{...},\"exec\":\"\$PREFIX/zig\"}，可用 \$PREFIX / \$CACHE")
                })
                put("multicall", buildJsonObject {
                    put("type", "boolean")
                    put("description", "装完跑 --list/--help 拿 applet 列表批量建链（busybox / toybox），默认 false")
                })
                put("sha256", buildJsonObject {
                    put("type", "string")
                    put("description", "强烈建议给，对不上直接拒装")
                })
                put("expect", buildJsonObject {
                    put("type", "object")
                    put("description", "校验期望：{\"arch\":\"aarch64\",\"linkage\":\"static|bionic|any\",\"smoke_arg\":\"--version\",\"smoke_contains\":\"ripgrep\"}")
                })
                put("note", buildJsonObject {
                    put("type", "string")
                    put("description", "一句话说明为什么装它，写进环境清单")
                })
            })
            put("required", buildJsonArray {})
        }
    )

    private fun manageDef() = fn(
        MANAGE,
        """
        已装工具的收拾工作。

        action=link：建软链或 wrapper。**你自己用 zig cc / go build 编出来的二进制也用这个** ——
        link 会把产物拷进内部可执行区并建入口，之后当普通命令敲。
        action=unlink：只删入口，留主体。
        action=remove：卸载，连带清 opt 目录、软链、wrapper 和账本。装错的、smoke 没过的立刻 remove。
        action=gc：清下载残留和指向空气的软链，返回释放了多少。
        action=disk：按工具列占用排行 + 剩余空间。

        **坑**：remove 不碰 work/ 里的项目文件，清工作区用 container_manage action=reset。
        有安装正在跑时 gc 会被拒绝 —— 它分不清「半截解压目录」和「正在解压的目录」。
        """.trimIndent(),
        buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        listOf("link", "unlink", "remove", "gc", "disk").forEach { add(it) }
                    })
                })
                put("name", buildJsonObject {
                    put("type", "string")
                    put("description", "link 时的命令名；unlink / remove 时要处理的工具名")
                })
                put("target", buildJsonObject {
                    put("type", "string")
                    put("description", "link 必填：真正要执行的东西。work/ 下的相对路径、opt 下的路径、或绝对路径")
                })
                put("wrapper_env", buildJsonObject {
                    put("type", "object")
                    put("description", "给 wrapper 注入的环境变量；给了就生成 wrapper 而不是软链")
                })
                put("purge", buildJsonObject {
                    put("type", "boolean")
                    put("description", "remove 时连缓存目录（zig-cache / GOCACHE）一起删，默认 false")
                })
            })
            put("required", buildJsonArray { add("action") })
        }
    )

    private fun manifestDef() = fn(
        MANIFEST,
        """
        环境清单 —— 「这套环境有什么、这个项目要什么」的账本，跨会话持久。

        action=get：读清单。
        action=require：登记「当前项目需要哪些工具」，只记不装，用户在设置里看得到。
        action=ensure：**一句话把环境搭起来**。传一组工具名，已装的跳过、缺的自动查目录并安装。
        用户说「搭个 C 开发环境」时用它，别一个个 install。

        **坑**：ensure 可能要下几百 MB。缺的里有大件时它只启动并返回 job_id，进度用
        toolchain_install action=poll 查。总量超过 200 MB 先跟用户说一声。
        """.trimIndent(),
        buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        listOf("get", "require", "ensure").forEach { add(it) }
                    })
                    put("description", "默认 get")
                })
                put("tools", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject { put("type", "string") })
                    put("description", "require / ensure 的工具名列表，如 [\"zig\",\"make\",\"rg\"]")
                })
                put("project", buildJsonObject {
                    put("type", "string")
                    put("description", "项目标识，默认当前会话绑定的目录")
                })
                put("reason", buildJsonObject {
                    put("type", "string")
                    put("description", "require 时说明为什么需要，会显示给用户")
                })
            })
            put("required", buildJsonArray {})
        }
    )

    // =================================================================
    //  执行
    // =================================================================

    /**
     * [project] 用来区分不同项目的依赖声明；[workDir] 是当前项目的 work/
     * 目录，`action=link` 解析相对路径时要用；[extraPath] 传容器的 execDir，
     * 让探测结果和实际执行环境的 PATH 完全一致（老的 check_environment
     * 就是因为两边 PATH 不同，报告「有」的命令跑起来 not found）。
     */
    suspend fun run(
        name: String,
        args: JsonObject,
        installer: ToolchainInstaller,
        project: String? = null,
        workDir: File? = null,
        extraPath: List<File> = emptyList()
    ): Pair<String, JsonObject> = when (name) {
        PROBE -> runProbe(args, installer, extraPath)
        CATALOG -> runCatalog(args, installer)
        INSTALL -> runInstall(args, installer)
        MANAGE -> runManage(args, installer, workDir)
        MANIFEST -> runManifest(args, installer, project)
        else -> "未知工具 $name" to errorJson(name, "unknown tool")
    }

    // ---- probe ----------------------------------------------------------

    private suspend fun runProbe(
        args: JsonObject,
        inst: ToolchainInstaller,
        extraPath: List<File>
    ): Pair<String, JsonObject> {
        return when (args.str("action") ?: "env") {
            "inspect" -> {
                val path = args.str("path")
                    ?: return "action=inspect 要给 path" to errorJson(PROBE, "missing path")
                val smoke = args.bool("smoke") ?: true
                val (elf, note) = inst.inspect(path, smoke, extraPath)
                val ui = buildJsonObject {
                    put("kind", PROBE)
                    put("action", "inspect")
                    put("path", elf.path)
                    put("elf", elfJson(elf))
                    if (note.isNotBlank()) put("smoke", note)
                }
                buildString {
                    appendLine("检查 ${elf.path}")
                    appendLine("  ${describeElf(elf)}")
                    appendLine("  大小 ${ToolchainInstaller.human(elf.sizeBytes)}，可执行位 ${if (elf.executableBit) "有" else "无"}")
                    if (elf.needed.isNotEmpty()) appendLine("  DT_NEEDED: ${elf.needed.joinToString(", ")}")
                    if (note.isNotBlank()) appendLine("  $note")
                    if (elf.problem != null) {
                        appendLine("  ✗ 跑不起来：${elf.problem}")
                    } else if (elf.isElf) {
                        appendLine("  ✓ 这个能跑（${elf.linkage}）")
                    }
                } to ui
            }
            else -> {
                // bin 目录空的话顺手把基础命令补上。「初始化开发环境」不该是
                // 用户设置页里的一个待办事项 —— 模型干活前总会先探测一次，
                // 在这里静默 bootstrap 是最自然的时机。用户关了自动安装的话
                // install() 自己会拒，这里不用额外判断。
                val boot = inst.bootstrapIfEmpty()
                val extra = args.strList("extra_commands")
                val r = inst.probeEnv(extra, extraPath)
                val ui = buildJsonObject {
                    put("kind", PROBE)
                    put("action", "env")
                    put("arch", r.arch)
                    put("abi", r.abi)
                    put("sdkInt", r.sdkInt)
                    put("rooted", r.rooted)
                    put("binDir", r.binDir)
                    put("optDir", r.optDir)
                    put("usedBytes", r.toolchainBytes)
                    put("freeBytes", r.freeDiskBytes)
                    put("budgetBytes", r.budgetBytes)
                    put("autoInstall", r.policy.autoInstall)
                    put("commands", buildJsonArray {
                        r.commands.forEach {
                            add(buildJsonObject {
                                put("name", it.name)
                                put("present", it.path != null)
                                it.path?.let { p -> put("path", p) }
                                put("source", it.source)
                            })
                        }
                    })
                    put("installed", buildJsonArray { r.installed.forEach { add(toolJson(it)) } })
                }
                val have = r.commands.filter { it.path != null }
                val miss = r.commands.filter { it.path == null }
                val text = buildString {
                    appendLine("设备：${r.abi} (${r.arch}) · Android API ${r.sdkInt} · root=${if (r.rooted) "有" else "无"}")
                    appendLine(
                        "工具链：占用 ${ToolchainInstaller.human(r.toolchainBytes)} · " +
                            "磁盘剩余 ${ToolchainInstaller.human(r.freeDiskBytes)} · " +
                            "预算 ${budgetLabel(r.budgetBytes)}" +
                            if (!r.policy.autoInstall) " · ⚠ 用户关闭了自动安装" else ""
                    )
                    appendLine("bin=${r.binDir}  opt=${r.optDir}")
                    appendLine()
                    if (boot != null) {
                        appendLine(
                            if (boot.ok) "（bin 目录原本是空的，已自动补上基础命令集：${boot.name}）"
                            else "（想自动补基础命令集但没成功：${boot.message}）"
                        )
                        appendLine()
                    }
                    if (r.installed.isEmpty()) {
                        appendLine("已装工具：空 —— 环境还没搭。缺什么就 toolchain_catalog 查了装上，别让用户去设置里点。")
                    } else {
                        appendLine("已装工具 (${r.installed.size})：")
                        r.installed.forEach { t ->
                            appendLine(
                                "  ${t.name.padEnd(12)} ${ToolchainInstaller.human(t.sizeBytes).padEnd(9)} " +
                                    "${hostOf(t.url).padEnd(22)} ${byLabel(t.installedBy)}" +
                                    if (t.version.isNotBlank()) "  ${t.version.take(48)}" else ""
                            )
                        }
                    }
                    appendLine()
                    appendLine("PATH 上有 (${have.size})：")
                    appendLine("  " + have.joinToString("  ") { "${it.name}(${it.source})" })
                    appendLine("缺 (${miss.size})：")
                    appendLine("  " + miss.joinToString(" ") { it.name })
                    if (miss.any { it.name in setOf("cc", "gcc", "clang", "zig") }) {
                        appendLine()
                        appendLine("提示：要编 C/C++ 就装 zig（catalog_id=zig），zig cc 就是 clang + musl。别找 gcc/tcc。")
                    }
                }
                text to ui
            }
        }
    }

    // ---- catalog --------------------------------------------------------

    private fun runCatalog(args: JsonObject, inst: ToolchainInstaller): Pair<String, JsonObject> {
        val action = args.str("action") ?: "search"
        val installedNames = inst.registry.manifest.tools.map { it.name }.toSet()
        fun installed(e: CatalogEntry) =
            e.id in installedNames || e.binName in installedNames

        if (action == "resolve") {
            val id = args.str("id") ?: args.str("q")
            ?: return "action=resolve 要给 id" to errorJson(CATALOG, "missing id")
            val e = ToolchainCatalog.resolve(id)
                ?: return deadEndText(id) to errorJson(CATALOG, "not found", "id" to id)
            val ui = buildJsonObject {
                put("kind", CATALOG); put("action", "resolve"); put("entry", entryJson(e))
                put("installed", installed(e))
            }
            return buildString {
                appendLine("${e.id} — ${e.title}")
                appendLine(e.summary)
                appendLine()
                appendLine("直接调 toolchain_install catalog_id=\"${e.id}\" 就行，下面这些参数会自动带上：")
                appendLine("  url = ${e.url}")
                appendLine("  format = ${e.format}, bin_name = ${e.binName}")
                if (e.members.isNotEmpty()) appendLine("  members = ${e.members}")
                if (e.stripComponents > 0) appendLine("  strip_components = ${e.stripComponents}")
                e.prefix?.let { appendLine("  prefix = $it（整树安装）") }
                if (e.links.isNotEmpty()) appendLine("  links = ${e.links}")
                e.wrapper?.let { appendLine("  wrapper = ${it.name} env=${it.env} exec=${it.exec}") }
                if (e.multicall) appendLine("  multicall = true")
                appendLine("  下载 ${ToolchainInstaller.human(e.downloadBytes)} → 装完约 ${ToolchainInstaller.human(e.installedBytes)}")
                appendLine("  链接方式：${e.linkage}；提供命令：${e.provides.joinToString(" ")}")
                appendLine("  已验证：${e.verified}")
                if (e.caveat.isNotBlank()) appendLine("  注意：${e.caveat}")
                if (installed(e)) appendLine("  状态：已经装了，重装会直接覆盖。")
            } to ui
        }

        val q = args.str("q").orEmpty()
        val cat = args.str("category")
        val list = if (action == "list") ToolchainCatalog.search("", cat)
        else ToolchainCatalog.search(q, cat)
        val dead = if (action == "search") ToolchainCatalog.deadEndFor(q) else null

        val ui = buildJsonObject {
            put("kind", CATALOG); put("action", action)
            if (q.isNotBlank()) put("q", q)
            dead?.let { put("dead_end", it) }
            put("entries", buildJsonArray {
                list.forEach {
                    add(buildJsonObject {
                        put("id", it.id); put("title", it.title); put("summary", it.summary)
                        put("category", it.category)
                        put("installed_bytes", it.installedBytes)
                        put("installed", installed(it))
                    })
                }
            })
        }
        val text = buildString {
            if (dead != null) {
                appendLine("关于 \"$q\"：$dead")
                appendLine()
            }
            if (list.isEmpty()) {
                appendLine("目录里没有匹配 \"$q\" 的条目。")
                appendLine("去 web_search 找直链，挑 asset 时认准 aarch64-unknown-linux-musl（静态）或 -android，")
                appendLine("别碰 -gnu / .deb / .rpm / Termux 的包。找到后用 toolchain_install url=... bin_name=... 装，")
                appendLine("装完看 smoke 结果。")
            } else {
                appendLine("已验证源目录${if (q.isNotBlank()) "（匹配 \"$q\"）" else ""}，共 ${list.size} 条：")
                list.forEach { e ->
                    appendLine(
                        "  ${if (installed(e)) "✓" else " "} ${e.id.padEnd(12)} " +
                            "${ToolchainInstaller.human(e.installedBytes).padEnd(9)} " +
                            "${e.category.padEnd(9)} ${e.summary}"
                    )
                }
                appendLine()
                appendLine("要装：toolchain_install catalog_id=\"<上面的 id>\"。要看细节：action=resolve id=\"<id>\"。")
            }
        }
        return text to ui
    }

    private fun deadEndText(id: String): String =
        ToolchainCatalog.deadEndFor(id)
            ?: "目录里没有 \"$id\"。先 toolchain_catalog action=search 换个词试试；" +
            "确实没有就 web_search 找静态 musl / -android 的直链，再用 toolchain_install url=... bin_name=... 装。"

    // ---- install --------------------------------------------------------

    private suspend fun runInstall(
        args: JsonObject,
        inst: ToolchainInstaller
    ): Pair<String, JsonObject> = when (args.str("action") ?: "install") {
        "poll" -> {
            val id = args.str("job_id")
            val v = id?.let { ToolchainJobs.poll(it, args.long("cursor") ?: 0L, MAX_LOG_CHARS) }
            when {
                id == null -> "action=poll 要给 job_id" to errorJson(INSTALL, "missing job_id")
                // job 表只在内存里、只留最近 12 条，app 进程一死就全没了；
                // 而账本在磁盘上。所以「查不到这个 job」**不等于**没装成 ——
                // 不点明这条，模型的下一步多半是原样再装一遍。
                v == null -> ("没有 job_id=$id 这个安装任务：任务表只在内存里、只留最近 12 条，" +
                    "app 重启就没了。这不代表没装成 —— 用 toolchain_manifest action=get 或 " +
                    "toolchain_probe action=env 查账本（那份在磁盘上），确认到底装上没有，别急着重装。") to
                    errorJson(INSTALL, "unknown job", "job_id" to id)
                else -> jobText(v) to jobJson(v, "poll")
            }
        }
        "cancel" -> {
            val id = args.str("job_id")
            val v = id?.let { ToolchainJobs.cancel(it) }
            when {
                id == null -> "action=cancel 要给 job_id" to errorJson(INSTALL, "missing job_id")
                v == null -> "没有 job_id=$id 这个安装任务。" to
                    errorJson(INSTALL, "unknown job", "job_id" to id)
                else -> jobText(v) to jobJson(v, "cancel")
            }
        }
        "list" -> {
            val all = ToolchainJobs.list()
            val ui = buildJsonObject {
                put("kind", INSTALL); put("action", "list")
                put("jobs", buildJsonArray { all.forEach { add(jobJson(it, "list")) } })
            }
            buildString {
                if (all.isEmpty()) appendLine("没有安装任务。")
                else {
                    appendLine("安装任务 ${all.size} 个：")
                    all.forEach {
                        appendLine("  ${it.id.padEnd(14)} ${it.title.padEnd(14)} ${ToolchainJobs.progressLine(it)}")
                    }
                }
            } to ui
        }
        else -> startInstall(args, inst)
    }

    private suspend fun startInstall(
        args: JsonObject,
        inst: ToolchainInstaller
    ): Pair<String, JsonObject> {
        val expect = args["expect"] as? JsonObject
        val wrapperObj = args["wrapper"] as? JsonObject
        val req = InstallRequest(
            catalogId = args.str("catalog_id"),
            url = args.str("url"),
            binName = args.str("bin_name"),
            format = args.str("format") ?: "auto",
            members = args.strList("members"),
            stripComponents = args["strip_components"]?.jsonPrimitive?.intOrNull ?: 0,
            prefix = args.str("prefix"),
            links = args.strList("links"),
            wrapper = wrapperObj?.let { w ->
                val exec = w.str("exec").orEmpty()
                if (exec.isBlank()) null else WrapperSpec(
                    name = w.str("name") ?: args.str("bin_name") ?: args.str("catalog_id").orEmpty(),
                    env = (w["env"] as? JsonObject)?.mapValues { (_, v) ->
                        v.jsonPrimitive.contentOrNull.orEmpty()
                    }.orEmpty(),
                    exec = exec
                )
            },
            multicall = args.bool("multicall") ?: false,
            sha256 = args.str("sha256"),
            expectArch = expect?.str("arch") ?: "aarch64",
            expectLinkage = expect?.str("linkage") ?: "any",
            smokeArg = expect?.str("smoke_arg"),
            smokeContains = expect?.str("smoke_contains"),
            note = args.str("note").orEmpty(),
            installedBy = "ai"
        )
        // 一律走 job：小件在这次调用里就装完了（start 会内联等它），
        // 大件立刻带着 job_id 回来。两条路的区别只有「等多久」，
        // 所以这里不用分叉，返回时看状态就行。
        // 用目录里的 binName 而不是模型给的 catalog_id：问 "git" 装的其实是
        // gix，通知栏和进度日志得说装完之后真正能敲的那个名字。
        val label = req.binName
            ?: req.catalogId?.let { ToolchainCatalog.resolve(it)?.binName ?: it }
            ?: "工具"
        val v = ToolchainJobs.start(
            inst,
            listOf(ToolchainStep(label, req)),
            title = label,
            forceBackground = args.bool("background") ?: false
        )
        if (!v.state.finished) return startedText(v) to jobJson(v, "start")
        val r = v.results.firstOrNull() ?: InstallResult(
            ok = false, name = label,
            message = if (v.state == ToolchainJobState.CANCELLED) "安装被取消了。" else v.message
        )
        return resultText(r) to buildJsonObject {
            installJson(r).forEach { (k, value) -> put(k, value) }
            put("job_id", v.id)
            put("state", v.state.name.lowercase())
        }
    }

    /** 大件启动后的第一段文案。重点只有两件事：拿什么去 poll、以及别守着它空转。 */
    private fun startedText(v: ToolchainJobView): String = buildString {
        appendLine("已开始安装 ${v.title}（后台跑，前台服务保活，锁屏也不会断）。")
        appendLine("job_id=${v.id}  ${ToolchainJobs.progressLine(v)}")
        if (v.log.isNotBlank()) appendLine(v.log.trimEnd())
        appendLine(
            "查进度：toolchain_install action=poll job_id=\"${v.id}\" cursor=${v.nextCursor}。" +
                "不装了：action=cancel。"
        )
        appendLine("**先去干别的**（读代码 / 写文件 / 跟用户说一句你在装什么），隔几轮再 poll，别原地空转。")
    }

    private fun jobText(v: ToolchainJobView): String = buildString {
        appendLine("安装任务 ${v.id}（${v.title}）— ${ToolchainJobs.progressLine(v)}")
        if (v.droppedChars > 0) appendLine("（中间有 ${v.droppedChars} 字符的进度没取，被缓冲挤掉了）")
        if (v.log.isNotBlank()) appendLine(v.log.trimEnd())
        when (v.state) {
            ToolchainJobState.QUEUED, ToolchainJobState.RUNNING -> {
                appendLine("还没完。下次 poll 把 cursor=${v.nextCursor} 传回来只拉新增的。")
                appendLine("这期间可以正常干别的活儿，不用守着。")
            }
            ToolchainJobState.CANCELLED -> {
                // 只说「没留下半成品」是不够的：多步 ensure 被取消时，取消之前
                // 装完的那几步是真的装上了、账本也记了。不说清楚模型会当成
                // 整批失败，回头把已经有的东西再装一遍。
                val done = v.results.filter { it.ok }
                if (done.isEmpty()) appendLine("已取消，没有留下半成品。")
                else {
                    appendLine("已取消：正在进行的那一步已经回滚，但下面这些在取消之前就装完了，账本里记着，可以直接用：")
                    done.forEach { r ->
                        appendLine(
                            "  ✓ ${r.name}" +
                                if (r.commands.isEmpty()) ""
                                else "（${r.commands.take(6).joinToString(" ")}）"
                        )
                    }
                }
            }
            else -> {
                v.results.forEach { append(resultText(it)) }
                if (v.results.isEmpty()) appendLine(v.message)
            }
        }
    }

    private fun jobJson(v: ToolchainJobView, action: String): JsonObject = buildJsonObject {
        put("kind", INSTALL)
        put("action", action)
        put("job_id", v.id)
        put("title", v.title)
        put("state", v.state.name.lowercase())
        put("phase", v.phase)
        put("step", v.stepIndex + 1)
        put("steps", v.stepCount)
        put("step_label", v.stepLabel)
        if (v.percent >= 0) put("percent", v.percent)
        if (v.totalBytes > 0) { put("bytes", v.bytes); put("total_bytes", v.totalBytes) }
        if (v.entries > 0) put("entries", v.entries)
        put("elapsed_ms", v.elapsedMs)
        if (v.queuedAhead > 0) put("queued_ahead", v.queuedAhead)
        if (v.message.isNotBlank()) put("message", v.message)
        if (v.log.isNotBlank()) put("log", v.log)
        put("next_cursor", v.nextCursor)
        if (v.droppedChars > 0) put("dropped", v.droppedChars)
        put("more_available", v.moreAvailable)
        put("finished", v.state.finished)
        if (v.results.isNotEmpty()) {
            put("results", buildJsonArray { v.results.forEach { add(installJson(it)) } })
        }
    }

    private fun resultText(r: InstallResult): String = buildString {
        if (r.ok) {
            appendLine("✓ ${r.message}")
            if (r.smokeOutput.isNotBlank()) appendLine("  验活输出：${r.smokeOutput}")
            r.elf?.let { appendLine("  ${describeElf(it)}") }
            appendLine("  跟用户说一句你装了什么、多大、干嘛用的，一句就够，别贴日志。")
        } else {
            appendLine("✗ 安装未完成：${r.message}")
            if (!r.needsConfirm) {
                appendLine("（没有留下半成品，可以直接换来源或换路线重试。）")
            }
        }
    }

    private fun installJson(r: InstallResult): JsonObject = buildJsonObject {
        put("kind", INSTALL)
        put("name", r.name)
        put("ok", r.ok)
        put("message", r.message)
        put("needsConfirm", r.needsConfirm)
        if (r.sourceUrl.isNotBlank()) put("url", r.sourceUrl)
        if (r.commands.isNotEmpty()) {
            put("commands", buildJsonArray { r.commands.forEach { add(it) } })
        }
        if (r.sizeBytes > 0) put("sizeBytes", r.sizeBytes)
        if (r.smokeOutput.isNotBlank()) put("smoke", r.smokeOutput)
        r.elf?.let { put("elf", elfJson(it)) }
    }

    // ---- manage ---------------------------------------------------------

    private suspend fun runManage(
        args: JsonObject,
        inst: ToolchainInstaller,
        workDir: File?
    ): Pair<String, JsonObject> {
        return when (val action = args.str("action").orEmpty()) {
            "link" -> {
                val name = args.str("name")
                    ?: return "action=link 要给 name" to errorJson(MANAGE, "missing name")
                val target = args.str("target")
                    ?: return "action=link 要给 target" to errorJson(MANAGE, "missing target")
                val env = (args["wrapper_env"] as? JsonObject)
                    ?.mapValues { (_, v) -> v.jsonPrimitive.contentOrNull.orEmpty() }
                    .orEmpty()
                val r = inst.link(name, target, env, workDir)
                r.message to manageJson(action, r.ok, r.message, 0L)
            }
            "unlink" -> {
                val name = args.str("name")
                    ?: return "action=unlink 要给 name" to errorJson(MANAGE, "missing name")
                val r = inst.unlink(name)
                r.message to manageJson(action, r.ok, r.message, 0L)
            }
            "remove" -> {
                val name = args.str("name")
                    ?: return "action=remove 要给 name" to errorJson(MANAGE, "missing name")
                val r = inst.remove(name, args.bool("purge") ?: false)
                r.message to manageJson(action, r.ok, r.message, r.freedBytes)
            }
            "gc" -> {
                val r = inst.gc()
                r.message to manageJson(action, r.ok, r.message, r.freedBytes)
            }
            "disk" -> {
                val (entries, free) = inst.diskReport()
                val ui = buildJsonObject {
                    put("kind", MANAGE); put("action", "disk")
                    put("freeBytes", free)
                    put("entries", buildJsonArray {
                        entries.forEach {
                            add(buildJsonObject {
                                put("name", it.name); put("bytes", it.bytes)
                                put("commands", it.commands)
                                it.prefix?.let { p -> put("prefix", p) }
                            })
                        }
                    })
                }
                buildString {
                    appendLine("磁盘占用（工具链共 ${ToolchainInstaller.human(entries.sumOf { it.bytes })}，设备剩余 ${ToolchainInstaller.human(free)}）：")
                    if (entries.isEmpty()) appendLine("  （还没装任何工具）")
                    entries.forEach {
                        appendLine(
                            "  ${it.name.padEnd(14)} ${ToolchainInstaller.human(it.bytes).padEnd(10)} " +
                                "${it.commands} 个命令${it.prefix?.let { p -> "  opt/$p" } ?: ""}"
                        )
                    }
                    if (entries.isNotEmpty()) {
                        appendLine("要腾地方：toolchain_manage action=remove name=<上面的名字> purge=true。")
                    }
                } to ui
            }
            else -> "action 只能是 link / unlink / remove / gc / disk（拿到的是 \"$action\"）" to
                errorJson(MANAGE, "bad action")
        }
    }

    private fun manageJson(action: String, ok: Boolean, msg: String, freed: Long) = buildJsonObject {
        put("kind", MANAGE); put("action", action); put("ok", ok); put("message", msg)
        if (freed > 0) put("freedBytes", freed)
    }

    // ---- manifest -------------------------------------------------------

    private suspend fun runManifest(
        args: JsonObject,
        inst: ToolchainInstaller,
        project: String?
    ): Pair<String, JsonObject> {
        val proj = args.str("project") ?: project ?: "default"
        val reg = inst.registry
        return when (args.str("action") ?: "get") {
            "require" -> {
                val tools = args.strList("tools")
                if (tools.isEmpty()) {
                    return "action=require 要给 tools" to errorJson(MANIFEST, "missing tools")
                }
                reg.declareNeeds(proj, tools, args.str("reason").orEmpty())
                val missing = tools.filter { reg.find(it) == null }
                val ui = buildJsonObject {
                    put("kind", MANIFEST); put("action", "require"); put("project", proj)
                    put("tools", buildJsonArray { tools.forEach { add(it) } })
                    put("missing", buildJsonArray { missing.forEach { add(it) } })
                }
                buildString {
                    appendLine("已登记：项目 \"$proj\" 需要 ${tools.joinToString(" ")}")
                    if (missing.isEmpty()) appendLine("这些现在都装好了。")
                    else appendLine("其中还缺：${missing.joinToString(" ")} —— 用 action=ensure 一次补齐。")
                } to ui
            }
            "ensure" -> {
                val wanted = args.strList("tools").ifEmpty {
                    reg.needsOf(proj)?.tools.orEmpty()
                }
                if (wanted.isEmpty()) {
                    return "action=ensure 要给 tools（或者先用 action=require 登记这个项目要什么）" to
                        errorJson(MANIFEST, "missing tools")
                }
                val skipped = ArrayList<String>()
                val unresolved = ArrayList<Pair<String, String>>()
                val steps = ArrayList<ToolchainStep>()
                for (w in wanted) {
                    val e = ToolchainCatalog.resolve(w)
                    // 「已经有了」有三种：账本里有这个名字、PATH 上能解析到、
                    // 或者目录条目提供的命令里已经装了一个（问 "git" 装的是
                    // gitoxide，账本里记的名字是 gix —— 不这么查会重复装）。
                    val already = reg.find(w) != null || inst.resolveCommand(w) != null ||
                        (e != null && (reg.find(e.binName) != null ||
                            e.provides.any { reg.find(it) != null }))
                    if (already) {
                        skipped += w
                        continue
                    }
                    if (e == null) {
                        unresolved += w to deadEndText(w)
                        continue
                    }
                    steps += ToolchainStep(
                        e.binName,
                        InstallRequest(catalogId = e.id, note = "项目 $proj 需要", installedBy = "ai")
                    )
                }

                // 一组工具打包成**一个** job：「帮我搭个 C 环境」可能是三个
                // 大件几百 MB，拆成三个 job 只会让模型多轮询两轮，而且中途
                // 失败时不好说清是哪一步断的。
                val v = if (steps.isEmpty()) null
                else ToolchainJobs.start(inst, steps, title = "$proj 环境")

                val ui = buildJsonObject {
                    put("kind", MANIFEST); put("action", "ensure"); put("project", proj)
                    put("skipped", buildJsonArray { skipped.forEach { add(it) } })
                    put("unresolved", buildJsonArray { unresolved.forEach { add(it.first) } })
                    v?.let {
                        put("job_id", it.id)
                        put("state", it.state.name.lowercase())
                        put("finished", it.state.finished)
                        put("next_cursor", it.nextCursor)
                        val rs = it.results
                        if (rs.isNotEmpty()) {
                            put("results", buildJsonArray { rs.forEach { x -> add(installJson(x)) } })
                        }
                    }
                }
                buildString {
                    appendLine("ensure（项目 $proj）：")
                    skipped.forEach { appendLine("  = $it 已经有了，跳过") }
                    unresolved.forEach { (n, why) -> appendLine("  ✗ $n $why") }
                    when {
                        v == null -> appendLine(
                            if (unresolved.isEmpty()) "要的都装好了，可以开工。"
                            else "上面查不到的先解决：换个名字搜，或者 web_search 找直链自己装。"
                        )
                        !v.state.finished -> {
                            appendLine("  ↻ 正在装 ${steps.joinToString(" ") { it.label }}（后台，锁屏不断）")
                            appendLine(
                                "job_id=${v.id} —— 用 toolchain_install action=poll job_id=\"${v.id}\" " +
                                    "cursor=${v.nextCursor} 查进度，别原地空转，先去干别的。"
                            )
                        }
                        else -> {
                            v.results.forEach { r ->
                                appendLine(if (r.ok) "  ✓ ${r.name} ${r.message}" else "  ✗ ${r.name} ${r.message}")
                            }
                            if (v.results.all { it.ok } && unresolved.isEmpty()) appendLine("环境齐了，可以开工。")
                            else appendLine("没搞定的先别绕过：看上面的原因，换来源或换路线。")
                        }
                    }
                } to ui
            }
            else -> {
                val m = reg.manifest
                val needs = reg.needsOf(proj)
                val missing = needs?.tools?.filter { reg.find(it) == null && inst.resolveCommand(it) == null }
                    .orEmpty()
                val ui = buildJsonObject {
                    put("kind", MANIFEST); put("action", "get"); put("project", proj)
                    put("totalBytes", m.totalBytes)
                    put("tools", buildJsonArray { m.tools.forEach { add(toolJson(it)) } })
                    needs?.let {
                        put("needs", buildJsonArray { it.tools.forEach { t -> add(t) } })
                        if (it.reason.isNotBlank()) put("reason", it.reason)
                    }
                    put("missing", buildJsonArray { missing.forEach { add(it) } })
                }
                buildString {
                    if (m.tools.isEmpty()) {
                        appendLine("环境清单是空的 —— 这台设备上还什么都没装。")
                        appendLine("要干活先 toolchain_probe action=env 看一眼，缺什么就 toolchain_catalog 查了装上。")
                    } else {
                        appendLine("已装 ${m.tools.size} 个工具，共 ${ToolchainInstaller.human(m.totalBytes)}：")
                        m.tools.sortedByDescending { it.sizeBytes }.forEach { t ->
                            appendLine(
                                "  ${t.name.padEnd(12)} ${ToolchainInstaller.human(t.sizeBytes).padEnd(9)} " +
                                    "${hostOf(t.url).padEnd(22)} ${byLabel(t.installedBy)}" +
                                    (if (t.version.isNotBlank()) "  ${t.version.take(40)}" else "") +
                                    (if (!t.smokeOk) "  ⚠ 验活没过" else "")
                            )
                            if (t.commands.size > 1) {
                                appendLine("               命令：${t.commands.take(12).joinToString(" ")}" +
                                    if (t.commands.size > 12) " …(${t.commands.size})" else "")
                            }
                        }
                    }
                    if (needs != null) {
                        appendLine()
                        appendLine("项目 \"$proj\" 声明需要：${needs.tools.joinToString(" ")}" +
                            if (needs.reason.isNotBlank()) "（${needs.reason}）" else "")
                        if (missing.isNotEmpty()) {
                            appendLine("还缺：${missing.joinToString(" ")} —— action=ensure 一次补齐。")
                        }
                    } else {
                        appendLine()
                        appendLine("这个项目还没声明过依赖。摸清楚要什么之后用 action=require 登记一下，下次换会话不用重来。")
                    }
                } to ui
            }
        }
    }

    // =================================================================
    //  小工具
    // =================================================================

    private fun elfJson(e: ElfInfo): JsonObject = buildJsonObject {
        put("isElf", e.isElf)
        put("bits", e.bits)
        put("machine", e.machine)
        put("type", e.type)
        put("linkage", e.linkage)
        e.interp?.let { put("interp", it) }
        if (e.needed.isNotEmpty()) put("needed", buildJsonArray { e.needed.forEach { add(it) } })
        put("sizeBytes", e.sizeBytes)
        put("executable", e.executableBit)
        e.shebang?.let { put("shebang", it) }
        e.problem?.let { put("problem", it) }
    }

    private fun toolJson(t: InstalledTool): JsonObject = t.toJson()

    private fun entryJson(e: CatalogEntry): JsonObject = buildJsonObject {
        put("id", e.id); put("title", e.title); put("summary", e.summary)
        put("category", e.category); put("url", e.url); put("format", e.format)
        put("bin_name", e.binName)
        if (e.members.isNotEmpty()) put("members", buildJsonArray { e.members.forEach { add(it) } })
        if (e.stripComponents > 0) put("strip_components", e.stripComponents)
        e.prefix?.let { put("prefix", it) }
        if (e.links.isNotEmpty()) put("links", buildJsonArray { e.links.forEach { add(it) } })
        e.wrapper?.let { w ->
            put("wrapper", buildJsonObject {
                put("name", w.name)
                put("exec", w.exec)
                put("env", buildJsonObject { w.env.forEach { (k, v) -> put(k, v) } })
            })
        }
        if (e.multicall) put("multicall", true)
        put("download_bytes", e.downloadBytes)
        put("installed_bytes", e.installedBytes)
        put("linkage", e.linkage)
        put("verified", e.verified)
        if (e.caveat.isNotBlank()) put("caveat", e.caveat)
        put("provides", buildJsonArray { e.provides.forEach { add(it) } })
    }

    private fun describeElf(e: ElfInfo): String = when {
        !e.isElf && e.shebang != null -> "脚本（${e.shebang}）"
        !e.isElf -> "不是 ELF"
        else -> buildString {
            append("ELF${e.bits} ${e.machine} ${e.type} ")
            append(
                when (e.linkage) {
                    "static" -> "静态链接"
                    "bionic" -> "Android 原生（bionic linker）"
                    else -> "动态链接 ${e.interp}"
                }
            )
        }
    }

    private fun budgetLabel(b: Long) =
        if (b == Long.MAX_VALUE) "不限" else ToolchainInstaller.human(b)

    private fun byLabel(by: String) = when (by) {
        "ai" -> "AI 装的"
        "user" -> "用户装的"
        else -> "自动"
    }

    private fun hostOf(url: String) = when {
        url.startsWith("local:") -> "本地产物"
        else -> runCatching { java.net.URL(url).host }.getOrDefault(url.take(22))
    }

    private fun errorJson(kind: String, message: String, vararg extra: Pair<String, String>) =
        buildJsonObject {
            put("kind", kind)
            put("error", message)
            for ((k, v) in extra) put(k, v)
        }

    private fun JsonObject.str(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }

    private fun JsonObject.bool(key: String): Boolean? =
        this[key]?.jsonPrimitive?.let { it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull() }

    private fun JsonObject.long(key: String): Long? =
        this[key]?.jsonPrimitive?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }

    private fun JsonObject.strList(key: String): List<String> =
        (this[key] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?.filter { it.isNotBlank() }
            .orEmpty()
}
