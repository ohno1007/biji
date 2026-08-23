package com.biji.notes.sandbox

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

// =====================================================================
//  已验证下载源目录
// =====================================================================

/** wrapper 脚本规格。[env] 和 [exec] 里的 `$PREFIX` / `$CACHE` / `$BIJI_BIN`
 *  等占位符在**写脚本的那一刻**就被展开成绝对路径 —— 不留到运行期，
 *  因为脚本可能被终端、后台任务、smoke test 三种不同环境调起来，
 *  那三边的 env 不保证一致，展开越早越不会出「在 A 能跑 B 不能跑」。 */
@Immutable
data class WrapperSpec(
    val name: String,
    val env: Map<String, String> = emptyMap(),
    val exec: String,
    /**
     * 固定前置参数，排在用户参数（`"$@"`）前面，生成的是
     * `exec <exec> <args…> "$@"`。
     *
     * 有它才建得出 `cc` → `zig cc` 这类别名。为什么非要有：Makefile 里的
     * `$(CC)`、autotools 的 ./configure、CMake 的探测逻辑，找的都是
     * **名字**（cc / gcc / c++），没人会去问「zig 装了没」。装了 zig 却
     * 没有 cc，等于这台设备上所有现成的 C 项目都构建不了。
     *
     * 每一项会被单独 shell 引号包住，所以带空格也安全；同样支持
     * `$PREFIX` / `$CACHE` 占位符。
     */
    val args: List<String> = emptyList()
)

/**
 * 目录里的一条。每一条都是**实测过 ELF 头**的（静态 aarch64 或
 * PT_INTERP=/system/bin/linker64 的 bionic 原生构建），不是搜出来照抄的。
 *
 * [downloadBytes] / [installedBytes] 是量级参考，用来在装之前算磁盘够不够，
 * 不追求精确 —— 上游换个版本就变了，别拿它做校验。
 */
@Immutable
data class CatalogEntry(
    val id: String,
    val title: String,
    val summary: String,
    val category: String,
    /** 检索词：中英文、别名、用途都往里塞，search 是纯字符串包含匹配。 */
    val keywords: List<String>,
    /** 装完能敲的命令名。ensure / search 按这个反查。 */
    val provides: List<String>,
    val url: String,
    val format: String,
    val binName: String,
    val members: List<String> = emptyList(),
    val stripComponents: Int = 0,
    val prefix: String? = null,
    val links: List<String> = emptyList(),
    val wrapper: WrapperSpec? = null,
    /** 主入口之外还要落几个别名 wrapper。zig 靠它把 `cc` / `gcc` / `c++`
     *  这些名字真的放到 PATH 上（见 [WrapperSpec.args]）；软链做不到，
     *  软链没法带上 `cc` 这个子命令。 */
    val wrappers: List<WrapperSpec> = emptyList(),
    val multicall: Boolean = false,
    val downloadBytes: Long,
    val installedBytes: Long,
    /** static = 无 PT_INTERP；bionic = PT_INTERP 指向 /system/bin/linker64。 */
    val linkage: String,
    val verified: String,
    val caveat: String = "",
    /**
     * 装完不做 smoke test。给两类东西用：交互式 TUI（vi / tmux / less
     * 没有能正常返回的 --version，跑起来会等 6 秒超时再被强杀，试四个参数
     * 就是 24 秒）和纯库文件（musl loader 根本没有可执行入口）。
     * 其余一律要跑 —— 没跑过的东西不能算装好。
     */
    val skipSmoke: Boolean = false
)

/**
 * 内置目录。硬编码在这里而不是从网上拉，是因为「哪个 asset 能在 Android
 * 上跑」这个知识必须是确定的：模型自己搜到的 URL 十有八九是 -gnu 变体，
 * 装上去要到真正 exec 的时候才报一句极具误导性的 "No such file or
 * directory"（那说的是缺 loader，不是缺文件）。
 *
 * 目录是编译期常量，加源要发版 —— 这是已知代价。缓解手段是
 * `toolchain_install` 的 url 分支：模型搜到好源可以直接装，装完
 * [ToolchainRegistry] 会把 URL 记进清单，下个会话查清单就能复现。
 */
object ToolchainCatalog {

    private const val SB =
        "https://raw.githubusercontent.com/ryanwoodsmall/static-binaries/master/aarch64/"

    private const val GH = "https://github.com/"

    /** zig 的七个入口（zig 本体 + 六个编译器别名）共用同一份缓存环境：
     *  不显式指到可写目录的话它会去写 HOME 下的默认路径，然后在第一次
     *  编译时以一句和缓存毫无关系的错误挂掉。 */
    private val ZIG_ENV = mapOf(
        "ZIG_GLOBAL_CACHE_DIR" to "\$CACHE/global",
        "ZIG_LOCAL_CACHE_DIR" to "\$CACHE/local"
    )

    private fun sb(
        id: String,
        title: String,
        summary: String,
        category: String,
        keywords: List<String>,
        bin: String = id,
        file: String = id,
        provides: List<String> = listOf(bin),
        multicall: Boolean = false,
        size: Long,
        caveat: String = "",
        skipSmoke: Boolean = false
    ) = CatalogEntry(
        id = id, title = title, summary = summary, category = category,
        keywords = keywords, provides = provides,
        url = SB + file, format = "raw", binName = bin,
        multicall = multicall,
        downloadBytes = size, installedBytes = size,
        linkage = "static",
        verified = "readelf 实测：ELF64 · AArch64 · 无 PT_INTERP（静态）",
        caveat = caveat,
        skipSmoke = skipSmoke
    )

    val entries: List<CatalogEntry> = listOf(

        // ---- shell / coreutils -----------------------------------------
        CatalogEntry(
            id = "toybox",
            title = "toybox",
            summary = "200+ 基础命令合一（ls/cat/grep/find/sed/tar/wget…），环境的地基。",
            category = "coreutils",
            keywords = listOf("toybox", "coreutils", "基础命令", "ls", "cat", "grep", "shell"),
            provides = listOf("toybox", "ls", "cat", "grep", "find", "sed", "tar", "wget"),
            url = "https://landley.net/toybox/bin/toybox-aarch64",
            format = "raw", binName = "toybox", multicall = true,
            downloadBytes = 1_150_000, installedBytes = 1_150_000,
            linkage = "static",
            verified = "官方发布的静态 aarch64 构建，biji 的 bootstrap 一直用它",
            caveat = "applet 展开出来的是软链，占不了几个 inode。它自带的 grep/sed 是精简版，要 GNU 行为装 busybox 或 coreutils。"
        ),
        sb(
            id = "busybox", title = "busybox",
            summary = "350+ 命令合一，比 toybox 全：awk / vi / patch / diff / unzip 都有。",
            category = "coreutils",
            keywords = listOf("busybox", "awk", "vi", "patch", "diff", "unzip", "基础命令"),
            provides = listOf("busybox", "awk", "vi", "patch", "diff", "unzip", "ps"),
            multicall = true, size = 2_100_000
        ),
        sb(
            id = "coreutils", title = "GNU coreutils",
            summary = "完整 GNU 版 ls / cp / sort / date，比 busybox / toybox 的精简版参数全。",
            category = "coreutils",
            keywords = listOf("coreutils", "gnu", "ls", "cp", "sort", "date"),
            provides = listOf("coreutils"),
            multicall = true, size = 6_000_000
        ),
        sb(
            id = "bash", title = "bash 5",
            summary = "真 bash：数组、[[ ]]、进程替换、here-string。",
            category = "shell",
            keywords = listOf("bash", "shell", "脚本"),
            size = 3_000_000
        ),

        // ---- 归档 / 网络 ------------------------------------------------
        sb(
            id = "xz", title = "xz",
            summary = "xz / lzma 压缩解压（biji 自己能解 .tar.xz，这个是给脚本用的）。",
            category = "archive",
            keywords = listOf("xz", "lzma", "解压", "压缩"),
            size = 1_000_000
        ),
        sb(
            id = "curl", title = "curl",
            summary = "带 TLS 的静态 curl，下载任意文件。",
            category = "net",
            keywords = listOf("curl", "下载", "http", "网络"),
            size = 4_000_000
        ),
        sb(
            id = "socat", title = "socat",
            summary = "网络管道 / 端口转发 / 简易服务，nc 的超集。",
            category = "net",
            keywords = listOf("socat", "nc", "netcat", "端口", "网络"),
            size = 1_000_000
        ),
        sb(
            id = "rsync", title = "rsync",
            summary = "增量同步目录。",
            category = "net",
            keywords = listOf("rsync", "同步", "备份"),
            size = 1_000_000
        ),

        // ---- 搜索 / 数据 ------------------------------------------------
        CatalogEntry(
            id = "ripgrep",
            title = "ripgrep (rg)",
            summary = "最快的代码全文搜索，默认尊重 .gitignore。",
            category = "search",
            keywords = listOf("ripgrep", "rg", "grep", "搜索", "查找", "全文"),
            provides = listOf("rg"),
            url = GH + "BurntSushi/ripgrep/releases/download/15.2.0/" +
                "ripgrep-15.2.0-aarch64-unknown-linux-musl.tar.gz",
            format = "tar.gz", binName = "rg", members = listOf("*/rg"),
            downloadBytes = 1_900_000, installedBytes = 5_200_000,
            linkage = "static",
            verified = "file 实测：statically linked, stripped",
            caveat = "必须挑 -musl 那个 asset，同名的 -gnu 是动态 glibc，装上跑不了。"
        ),
        CatalogEntry(
            id = "fd",
            title = "fd",
            summary = "按文件名找文件，find 的现代替代。",
            category = "search",
            keywords = listOf("fd", "find", "查找文件", "搜索"),
            provides = listOf("fd"),
            url = GH + "sharkdp/fd/releases/download/v10.4.2/" +
                "fd-v10.4.2-aarch64-unknown-linux-musl.tar.gz",
            format = "tar.gz", binName = "fd", members = listOf("*/fd"),
            downloadBytes = 1_500_000, installedBytes = 3_400_000,
            linkage = "static",
            verified = "file 实测：statically linked",
            caveat = "同样只能用 -musl 变体。"
        ),
        CatalogEntry(
            id = "jq",
            title = "jq 1.8",
            summary = "命令行 JSON 处理器。",
            category = "search",
            keywords = listOf("jq", "json", "解析"),
            provides = listOf("jq"),
            url = GH + "jqlang/jq/releases/download/jq-1.8.2/jq-linux-arm64",
            format = "raw", binName = "jq",
            downloadBytes = 1_700_000, installedBytes = 1_700_000,
            linkage = "static",
            verified = "file 实测：statically linked（官方 arm64 裸二进制）"
        ),
        sb(
            id = "mlr", title = "miller (mlr)",
            summary = "CSV / TSV / JSON 流式处理，像 awk 但懂结构化数据。",
            category = "search",
            keywords = listOf("miller", "mlr", "csv", "tsv", "数据"),
            size = 8_000_000
        ),
        sb(
            id = "ag", title = "the_silver_searcher (ag)",
            summary = "代码搜索。没装 ripgrep 时的轻量替代。",
            category = "search",
            keywords = listOf("ag", "silver searcher", "搜索"),
            size = 1_000_000
        ),

        // ---- 版本控制 ---------------------------------------------------
        CatalogEntry(
            id = "gitoxide",
            title = "gitoxide (gix)",
            summary = "git 的 Rust 实现，clone / fetch / push / log 齐全。这是本机唯一能跑的 git。",
            category = "vcs",
            keywords = listOf("git", "gitoxide", "gix", "ein", "版本控制", "clone", "仓库"),
            provides = listOf("gix", "ein"),
            url = GH + "GitoxideLabs/gitoxide/releases/download/v0.56.0/" +
                "gitoxide-max-pure-v0.56.0-aarch64-unknown-linux-musl.tar.gz",
            format = "tar.gz", binName = "gix", members = listOf("*/gix", "*/ein"),
            downloadBytes = 10_200_000, installedBytes = 26_000_000,
            linkage = "static",
            verified = "file 实测：gix 和 ein 都是 statically linked",
            caveat = "命令名是 gix / ein，不是 git。子命令语法和 git 不完全一样（`gix clone URL DIR`）。" +
                "要让 `git` 这个名字也能敲，用 toolchain_manage action=link name=git target=gix。" +
                "真 git 只有动态 musl 构建，得先装 musl loader 再包 wrapper，别轻易走那条路。"
        ),

        // ---- 编译器 / 运行时 ---------------------------------------------
        CatalogEntry(
            id = "zig",
            title = "Zig 0.16（C/C++ 编译器）",
            summary = "zig cc / zig c++ 就是 clang + 自带 musl 头文件和源码，能直接产出静态 aarch64 可执行文件。本机唯一可用的 C 编译器。",
            category = "compiler",
            keywords = listOf(
                "zig", "cc", "c++", "gcc", "clang", "tcc", "编译器", "compiler",
                "c 语言", "cpp", "编译"
            ),
            provides = listOf("zig", "cc", "c++", "gcc", "g++", "clang", "clang++"),
            url = "https://ziglang.org/download/0.16.0/zig-aarch64-linux-0.16.0.tar.xz",
            format = "tar.xz", binName = "zig",
            members = listOf("**"), stripComponents = 1, prefix = "zig",
            wrapper = WrapperSpec(
                name = "zig",
                env = ZIG_ENV,
                exec = "\$PREFIX/zig"
            ),
            // `cc` / `gcc` / `clang` 都落成 `zig cc`，`c++` / `g++` / `clang++`
            // 落成 `zig c++`。名字给全是因为探测逻辑各找各的：GNU make 的
            // 默认 $(CC) 是 cc、默认 $(CXX) 是 g++，autotools 先试 gcc 再试 cc，
            // 一堆手写 Makefile 直接写死 clang。少一个就少一类项目能构建。
            wrappers = listOf("cc", "gcc", "clang").map {
                WrapperSpec(it, ZIG_ENV, "\$PREFIX/zig", listOf("cc"))
            } + listOf("c++", "g++", "clang++").map {
                WrapperSpec(it, ZIG_ENV, "\$PREFIX/zig", listOf("c++"))
            },
            downloadBytes = 48_800_000, installedBytes = 230_000_000,
            linkage = "static",
            verified = "file 实测：statically linked, stripped；zig 二进制单文件 152 MiB",
            caveat = "整棵树 2 万多个文件，必须 prefix 安装，绝不能倒进 bin 目录。" +
                "缓存目录必须显式指到可写位置（wrapper 已经设好），否则它会去写 HOME 下的默认路径然后失败。" +
                "装完至少留 300 MB 空间。" +
                "装完 cc / gcc / clang / c++ / g++ / clang++ 都会指向 zig，`cc hello.c -o hello` 直接可用；" +
                "ar / ranlib 没做别名（会盖掉 busybox 的同名 applet），要用就敲 `zig ar`。"
        ),
        CatalogEntry(
            id = "go",
            title = "Go 1.26",
            summary = "官方 linux-arm64 工具链，本身就是静态的。",
            category = "compiler",
            keywords = listOf("go", "golang", "编译器", "gofmt"),
            provides = listOf("go", "gofmt"),
            url = "https://go.dev/dl/go1.26.5.linux-arm64.tar.gz",
            format = "tar.gz", binName = "go",
            members = listOf("**"), stripComponents = 1, prefix = "go",
            links = listOf("gofmt=>opt/go/bin/gofmt"),
            wrapper = WrapperSpec(
                name = "go",
                env = mapOf(
                    "GOROOT" to "\$PREFIX",
                    "GOPATH" to "\$CACHE/gopath",
                    "GOCACHE" to "\$CACHE/build",
                    "GOTMPDIR" to "\$CACHE/tmp",
                    "GOTOOLCHAIN" to "local"
                ),
                exec = "\$PREFIX/bin/go"
            ),
            downloadBytes = 60_800_000, installedBytes = 280_000_000,
            linkage = "static",
            verified = "file 实测：go/bin/go 是 statically linked（Go BuildID 齐全）",
            caveat = "GOTOOLCHAIN=local 是故意的：否则 go 遇到 go.mod 里更高的版本号会试图联网下另一个工具链，" +
                "在这台设备上必然失败。整棵 GOROOT 接近 280 MB。"
        ),
        CatalogEntry(
            id = "bun",
            title = "Bun 1.3（JS / TS 运行时）",
            summary = "Node 兼容运行时 + 包管理器 + TS 转译 + 打包 + test runner 一体。本机唯一可用的 JS 运行时。",
            category = "runtime",
            keywords = listOf(
                "bun", "node", "nodejs", "npm", "javascript", "typescript", "ts",
                "js", "deno", "运行时", "前端"
            ),
            provides = listOf("bun"),
            url = GH + "oven-sh/bun/releases/download/bun-v1.3.14/bun-linux-aarch64-android.zip",
            format = "zip", binName = "bun", members = listOf("*/bun"),
            downloadBytes = 33_000_000, installedBytes = 85_600_000,
            linkage = "bionic",
            verified = "readelf 实测：interpreter /system/bin/linker64，NEEDED libc.so/libm.so/libdl.so —— 这是 Android 原生构建",
            caveat = "一定要 -android 那个 asset。-linux-aarch64（不带 android）是 glibc/musl 构建，跑不了。" +
                "它是唯一一个**不是静态链接**却能跑的包：靠的是 bionic linker，这条路只有官方出 Android 构建时才成立。"
        ),
        CatalogEntry(
            id = "uv",
            title = "uv（Python 包管理器）",
            summary = "Rust 写的 pip / venv 替代。注意：它管得了包，但本机还没有能跑的 Python 解释器。",
            category = "runtime",
            keywords = listOf("uv", "uvx", "pip", "python", "venv", "包管理"),
            provides = listOf("uv", "uvx"),
            url = GH + "astral-sh/uv/releases/download/0.12.3/uv-aarch64-unknown-linux-musl.tar.gz",
            format = "tar.gz", binName = "uv", members = listOf("*/uv", "*/uvx"),
            downloadBytes = 19_300_000, installedBytes = 48_000_000,
            linkage = "static",
            verified = "file 实测：statically linked",
            caveat = "装了 uv 不等于有 Python。python-build-standalone 的 musl 构建是动态的、不自带 libc，" +
                "在这台设备上跑不起来 —— 能不用 Python 就别用。"
        ),

        // ---- 构建 / 杂项 ------------------------------------------------
        sb(
            id = "make", title = "GNU make",
            summary = "标准 Makefile 构建。没有 cmake / ninja 时的默认选择。",
            category = "build",
            keywords = listOf("make", "makefile", "构建", "build", "cmake", "ninja"),
            size = 1_000_000,
            caveat = "cmake 和 ninja 都没有可用的 aarch64 静态构建，别找了：写 Makefile，或者 build.zig，" +
                "或者直接 zig cc。真要 ninja 就用 zig cc 现场编 samurai（几千行 C）。"
        ),
        sb(
            id = "ccache", title = "ccache",
            summary = "编译缓存，反复编同一份 C 代码时省时间。",
            category = "build",
            keywords = listOf("ccache", "缓存", "编译加速"),
            size = 1_000_000
        ),
        sb(
            id = "less", title = "less",
            summary = "分页查看长输出。",
            category = "coreutils",
            keywords = listOf("less", "分页", "查看"),
            size = 500_000, skipSmoke = true,
            caveat = "ncurses 程序，要读 terminfo 才知道怎么画屏。terminfo 数据库不在这个包里，" +
                "缺了它 less 会报 \"WARNING: terminal is not fully functional\" 然后退化成 cat。" +
                "跳过 smoke 也是因为这个：它没有能正常返回的 --version 路径，跑起来只会等超时。"
        ),
        sb(
            id = "tmux", title = "tmux",
            summary = "终端复用。给用户在终端页用的，AI 跑长任务请用 container_task。",
            category = "shell",
            keywords = listOf("tmux", "终端", "复用", "后台"),
            size = 1_000_000, skipSmoke = true,
            caveat = "ncurses 程序，强依赖 terminfo：找不到 \$TERM 对应的条目时 tmux 直接" +
                "\"open terminal failed: missing or unsuitable terminal\" 拒绝启动，" +
                "连界面都不会出。terminfo 数据库不在这个包里。" +
                "另外它是交互式 TUI，没有能正常返回的 --version，所以跳过 smoke。"
        ),
        sb(
            id = "neatvi", title = "vi 编辑器",
            summary = "轻量 vi。给用户在终端页用；AI 改文件请用 write_file。",
            category = "shell",
            keywords = listOf("vi", "vim", "编辑器", "neatvi"),
            bin = "vi", file = "neatvi", provides = listOf("vi"),
            size = 300_000, skipSmoke = true
        )
    )

    /** 目录里**没有**、且已经确认找不到可用构建的东西。模型问到这些名字
     *  时直接把替代方案甩给它，省掉一轮 web_search + 一次装了跑不动。 */
    val deadEnds: List<Pair<String, String>> = listOf(
        "tcc" to "没有任何 aarch64 静态构建（GitHub 搜索 0 结果）。要 C 编译器就装 zig，用 zig cc。",
        "gcc" to "没有可用的 aarch64 静态构建。装 zig —— 它会同时落下 gcc / cc / g++ 这几个名字，" +
            "背后是 `zig cc`，Makefile 和 ./configure 照常能用。",
        "clang" to "官方 release 是 glibc 动态。装 zig —— 它本来就是 clang，而且会把 clang / clang++ " +
            "这两个名字也建出来。",
        "cmake" to "没有静态构建。改用 Makefile（装 make）、build.zig，或直接 zig cc。",
        "ninja" to "官方 ninja-linux-aarch64.zip 实测是 glibc 动态（interpreter /lib/ld-linux-aarch64.so.1），装上必炸。" +
            "要么用 make，要么 zig cc 现场编 samurai（michaelforney/samurai，几千行 C）。",
        "node" to "Node.js 官方 arm64 只有 glibc 动态构建，unofficial-builds 的 musl 只到 x64。用 bun 代替，它兼容 Node API。",
        "nodejs" to "同 node：用 bun。",
        "deno" to "只发 deno-aarch64-unknown-linux-gnu.zip，glibc 动态。用 bun 代替。",
        "python" to "python-build-standalone 的 musl 变体是动态链接且自己不带 libc（实测 python/lib/ 里没有 libc.so），" +
            "直接跑必炸；gnu 变体是 glibc。这台设备上没有能跑的 Python，绕开它。",
        "python3" to "同 python。",
        "git" to "真 git 只有动态 musl 构建（Alpine apk），依赖闭包很长。用 gitoxide 的 gix，静态、10 MB、功能齐全。",
        "termux" to "Termux 的 .deb 把 /data/data/com.termux/files/usr 硬编码在二进制里，拿过来必炸。这个坑已经踩过了，别再试。",
        "apt" to "这台设备没有 apt / pkg / dnf / brew，也没有 /usr。装工具只有 toolchain_install 这一条路。",
        "deb" to ".deb / .rpm 解开也是动态链接 + FHS 路径，一律不行。"
    )

    fun byId(id: String): CatalogEntry? =
        entries.firstOrNull { it.id.equals(id, ignoreCase = true) }

    /** 按 id / 命令名 / 关键词解析一个「工具名」。ensure 和 install 的
     *  catalog_id 都走这里，所以模型传 "git" 也能命中 gitoxide。 */
    fun resolve(name: String): CatalogEntry? {
        val n = name.trim().lowercase()
        if (n.isEmpty()) return null
        return byId(n)
            ?: entries.firstOrNull { e -> e.provides.any { it.lowercase() == n } }
            ?: entries.firstOrNull { e -> e.binName.lowercase() == n }
            ?: entries.firstOrNull { e -> e.keywords.any { it.lowercase() == n } }
    }

    fun search(q: String, category: String?): List<CatalogEntry> {
        val needle = q.trim().lowercase()
        val cat = category?.takeIf { it.isNotBlank() && it != "all" }?.lowercase()
        val pool = if (cat == null) entries else entries.filter { it.category == cat }
        if (needle.isEmpty()) return pool
        // 先精确命中（id / 命令名），再模糊 —— 模型传 "rg" 时不该被
        // summary 里也提到 rg 的那几条淹没。
        val exact = pool.filter { e ->
            e.id.lowercase() == needle || e.provides.any { it.lowercase() == needle }
        }
        val fuzzy = pool.filter { e ->
            e !in exact && (
                e.id.contains(needle) ||
                    e.title.lowercase().contains(needle) ||
                    e.summary.lowercase().contains(needle) ||
                    e.category.contains(needle) ||
                    e.keywords.any { it.lowercase().contains(needle) || needle.contains(it.lowercase()) } ||
                    e.provides.any { it.lowercase().contains(needle) }
                )
        }
        return exact + fuzzy
    }

    fun deadEndFor(q: String): String? {
        val n = q.trim().lowercase()
        if (n.isEmpty()) return null
        return deadEnds.firstOrNull { (k, _) -> n == k || n.contains(k) }?.second
    }
}

// =====================================================================
//  环境清单
// =====================================================================

/** 装好的一个工具。[commands] 是这次安装在 bin 目录里落下的入口名（含
 *  软链和 wrapper）—— 卸载时按它精确回收，不靠猜名字。 */
@Immutable
data class InstalledTool(
    val name: String,
    val catalogId: String?,
    val version: String,
    val url: String,
    val sizeBytes: Long,
    val installedAt: Long,
    /** "ai" / "user"。用户在设置里要看到是谁装的。 */
    val installedBy: String,
    val commands: List<String>,
    val prefix: String?,
    val linkage: String,
    val smokeOk: Boolean,
    val smokeOutput: String,
    val note: String
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("name", name)
        catalogId?.let { put("catalog_id", it) }
        put("version", version)
        put("url", url)
        put("size_bytes", sizeBytes)
        put("installed_at", installedAt)
        put("installed_by", installedBy)
        put("commands", buildJsonArray { commands.forEach { add(it) } })
        prefix?.let { put("prefix", it) }
        put("linkage", linkage)
        put("smoke_ok", smokeOk)
        if (smokeOutput.isNotBlank()) put("smoke", smokeOutput)
        if (note.isNotBlank()) put("note", note)
    }

    companion object {
        fun fromJson(o: JsonObject): InstalledTool? {
            val name = o["name"]?.jsonPrimitive?.contentOrNull ?: return null
            return InstalledTool(
                name = name,
                catalogId = o["catalog_id"]?.jsonPrimitive?.contentOrNull,
                version = o["version"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                url = o["url"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                sizeBytes = o["size_bytes"]?.jsonPrimitive?.longOrNull ?: 0L,
                installedAt = o["installed_at"]?.jsonPrimitive?.longOrNull ?: 0L,
                installedBy = o["installed_by"]?.jsonPrimitive?.contentOrNull ?: "ai",
                commands = (o["commands"] as? JsonArray)
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
                prefix = o["prefix"]?.jsonPrimitive?.contentOrNull,
                linkage = o["linkage"]?.jsonPrimitive?.contentOrNull ?: "static",
                smokeOk = o["smoke_ok"]?.jsonPrimitive?.booleanOrNull ?: true,
                smokeOutput = o["smoke"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                note = o["note"]?.jsonPrimitive?.contentOrNull.orEmpty()
            )
        }
    }
}

/** 「这个项目需要哪些工具」的声明。只登记不安装 —— 换会话后模型读一次
 *  清单就知道该补什么，不用重新推断一遍「这个 Rust 项目要什么」。 */
@Immutable
data class ProjectNeeds(
    val project: String,
    val tools: List<String>,
    val reason: String,
    val declaredAt: Long
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("project", project)
        put("tools", buildJsonArray { tools.forEach { add(it) } })
        if (reason.isNotBlank()) put("reason", reason)
        put("declared_at", declaredAt)
    }

    companion object {
        fun fromJson(o: JsonObject): ProjectNeeds? {
            val p = o["project"]?.jsonPrimitive?.contentOrNull ?: return null
            return ProjectNeeds(
                project = p,
                tools = (o["tools"] as? JsonArray)
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
                reason = o["reason"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                declaredAt = o["declared_at"]?.jsonPrimitive?.longOrNull ?: 0L
            )
        }
    }
}

/**
 * 装配策略。用户在设置里改，[ToolchainInstaller] 每次安装前查。
 *
 * 原来还有一个「每次安装前询问我」的开关。它和 [autoInstall] 讲的是同一件事的
 * 两半，两个开关的四种组合里有两种没有意义，删掉了 —— 要么让 AI 自己装，
 * 要么不让，中间态只是多一次点击。
 */
@Immutable
data class ToolchainPolicy(
    val autoInstall: Boolean = true,
    val budgetBytes: Long = 2L * 1024 * 1024 * 1024
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("auto_install", autoInstall)
        put("budget_bytes", budgetBytes)
    }

    companion object {
        val UNLIMITED = Long.MAX_VALUE

        fun fromJson(o: JsonObject?): ToolchainPolicy {
            if (o == null) return ToolchainPolicy()
            return ToolchainPolicy(
                autoInstall = o["auto_install"]?.jsonPrimitive?.booleanOrNull ?: true,
                budgetBytes = o["budget_bytes"]?.jsonPrimitive?.longOrNull
                    ?: (2L * 1024 * 1024 * 1024)
            )
        }
    }
}

@Immutable
data class ToolchainManifest(
    val tools: List<InstalledTool> = emptyList(),
    val needs: List<ProjectNeeds> = emptyList(),
    val policy: ToolchainPolicy = ToolchainPolicy()
) {
    val totalBytes: Long get() = tools.sumOf { it.sizeBytes }
    val commandCount: Int get() = tools.sumOf { it.commands.size }
}

/**
 * 环境账本。一个 JSON 文件 + 一份内存快照 + 一个 StateFlow 给 UI。
 *
 * 为什么要有它：`ls binDir` 只能拿到一串名字，来源 URL、版本、体积、
 * 谁装的、smoke 过没过全丢了。没有这些，卸载只能靠猜文件名，用户看不到
 * 「312 MB 都被谁占了」，换会话的模型也只能从零重新摸索。
 *
 * 所有写操作都同步落盘（文件几 KB，谈不上开销），失败静默 —— 账本写不
 * 进去不该让安装本身失败，下次安装会重写一遍。
 */
class ToolchainRegistry(private val file: File) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val pretty = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val lock = Any()

    private val _state = MutableStateFlow(ToolchainManifest())
    val state: StateFlow<ToolchainManifest> = _state.asStateFlow()

    init {
        _state.value = readFile()
    }

    val manifest: ToolchainManifest get() = _state.value

    fun find(name: String): InstalledTool? {
        val n = name.trim().lowercase()
        return manifest.tools.firstOrNull { it.name.lowercase() == n }
            ?: manifest.tools.firstOrNull { t -> t.commands.any { it.lowercase() == n } }
    }

    fun record(tool: InstalledTool) = mutate { m ->
        m.copy(tools = m.tools.filterNot { it.name == tool.name } + tool)
    }

    fun forget(name: String): InstalledTool? {
        val existing = find(name) ?: return null
        mutate { m -> m.copy(tools = m.tools.filterNot { it.name == existing.name }) }
        return existing
    }

    fun declareNeeds(project: String, tools: List<String>, reason: String) = mutate { m ->
        val clean = tools.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        m.copy(
            needs = m.needs.filterNot { it.project == project } +
                ProjectNeeds(project, clean, reason, System.currentTimeMillis())
        )
    }

    fun needsOf(project: String): ProjectNeeds? =
        manifest.needs.firstOrNull { it.project == project }

    fun setPolicy(p: ToolchainPolicy) = mutate { m -> m.copy(policy = p) }

    fun clearAll() = mutate { ToolchainManifest(policy = it.policy) }

    // ---- 落盘 ----------------------------------------------------------

    private inline fun mutate(f: (ToolchainManifest) -> ToolchainManifest) {
        synchronized(lock) {
            val next = f(_state.value)
            _state.value = next
            writeFile(next)
        }
    }

    private fun readFile(): ToolchainManifest = runCatching {
        if (!file.isFile) return@runCatching ToolchainManifest()
        val root = json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject
        ToolchainManifest(
            tools = (root["tools"] as? JsonArray).orEmpty()
                .mapNotNull { InstalledTool.fromJson(it.jsonObject) },
            needs = (root["needs"] as? JsonArray).orEmpty()
                .mapNotNull { ProjectNeeds.fromJson(it.jsonObject) },
            policy = ToolchainPolicy.fromJson(root["policy"] as? JsonObject)
        )
    }.getOrDefault(ToolchainManifest())

    private fun writeFile(m: ToolchainManifest) {
        runCatching {
            file.parentFile?.mkdirs()
            val root = buildJsonObject {
                put("version", 1)
                put("tools", buildJsonArray { m.tools.forEach { add(it.toJson()) } })
                put("needs", buildJsonArray { m.needs.forEach { add(it.toJson()) } })
                put("policy", m.policy.toJson())
            }
            // 先写临时文件再 rename：进程在写一半时被杀掉不会留下半个 JSON，
            // 那会让下次启动整份账本解析失败、已装的东西全变成「没登记过」。
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(JsonObject.serializer(), root), Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText(Charsets.UTF_8), Charsets.UTF_8)
                tmp.delete()
            }
        }
    }
}
