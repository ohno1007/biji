# 未推送的本地改动清单

git 推送通道在容器重置后断了（本地 git 代理端口没了，直连 GitHub 无凭据），
只能走 GitHub API 逐文件推。以下文件超过单次推送的体积上限，改动还留在本地
commit 里。每条都写清楚了改哪里、为什么，照着重做即可。

## ChatViewModel.kt — 流式落库节流（CPU 最大头）

`runChatTurn` 里原本每个 token 都 `chat.updateAssistantStream(...)`，一次写库
→ Flow emit → 整个聊天列表重组 → 末条消息全量 Markdown 重解析，一秒几十次。

改：文件顶层加 `private const val STREAM_FLUSH_MS = 90L`；在 collect 之前定义

    var lastFlushAt = 0L
    var pendingFlush = false
    suspend fun flushStream(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastFlushAt < STREAM_FLUSH_MS) { pendingFlush = true; return }
        lastFlushAt = now; pendingFlush = false
        chat.updateAssistantStream(placeholderId, contentBuf.toString(),
            reasoningBuf.takeIf { it.isNotEmpty() }?.toString())
    }

`ChatEvent.Delta` / `ChatEvent.Reasoning` 分支里只 append 缓冲然后
`flushStream(force = false)`；collect 结束后、`if (lastUsageTotal > 0)` 之前
无条件 `flushStream(force = true)` 收尾，保证不丢字。

## Markdown.kt — 解析/高亮缓存 + 代码块折叠 bug

1. 新增 `rememberInline(text, baseColor, accent)`：`remember(text, baseColor, accent) { inline(...) }`，
   把 7 处 `annotated = inline(...)` 全换成它。inline 里有正则和 latexToUnicode，
   原来每次重组都跑。
2. CodeBlock 里 `SyntaxHighlight.colorize(code, lang, isDark)` 包一层
   `remember(code, lang, isDark) { ... }`。
3. MathBlockBox 里 `latexToUnicode(source).trim()` 包 `remember(source) { ... }`。
4. **折叠 bug**：CodeBlock 的 expanded / copied / previewing 三个状态原来拿整段
   code 当 remember 的 key，流式时 code 一直在变长，状态被反复重建 —— 超过 12 行
   的代码块每秒被强制折叠十来次。改成

       val blockKey = remember(lang, code.take(64)) { lang + " " + code.take(64) }
       var copied by remember(blockKey) { mutableStateOf(false) }
       val isLong = remember(code) { code.count { it == '\n' } >= 12 }   // 这个要跟完整内容
       var expanded by remember(blockKey) { mutableStateOf(!isLong) }
       var previewing by remember(blockKey) { mutableStateOf(false) }

## ChatScreen.kt — 分组缓存 + Compose 稳定性

1. `groupChatItems(messages)` 原来写在 LazyColumn 的 content lambda 里，每帧重算
   整张列表。提到 LazyColumn 外面：`val renderedItems = remember(messages) {
   runCatching { groupChatItems(messages) }.getOrElse { emptyList() } }`，
   items 改成 `items(renderedItems, key = { it.key })`。
2. `sealed interface ChatItem` 前加 `@androidx.compose.runtime.Immutable` —— 里面的
   `List<Message>` 是接口类型，编译器只能保守判定 unstable，导致列表每项每次都重组。
3. `val stableOpenUrl = remember(vm) { { url: String -> vm.openWebUrl(url) } }`，
   把两处 `vm::openWebUrl` 换掉（方法引用每次重组都是新实例，会把重组传染下去）。
4. `stripToolCallMarkup`：四条正则从函数体提到顶层 val（原来每次调用重新编译，
   流式下每 90ms 对全文跑一遍），函数开头加快速路径
   `if (!raw.contains("<|")) return raw.trim()`；调用点 `remember(m.content) { ... }`。

## EditorScreen.kt — 补全与高亮缓存

- `suggestCompletions(fieldValue, lang)` 包 `remember(fieldValue.text, fieldValue.selection, lang)`。
- VIEW 模式的 `SyntaxHighlight.colorize(fieldValue.text, lang, isDark)` 包
  `remember(fieldValue.text, lang, isDark)`。

## Tools.kt — install_package 支持任意静态二进制直链

`install_package` 加 `url` + `bin_name` 两个可选参数（与 package_id 二选一），
handler 里走 `pkg.installCustom(binName, url)`；描述文案要点明"必须是 static /
musl 构建的裸二进制，.deb/.rpm 和动态链接的在 Android 上跑不起来"，并提示可以
先用 web_search 找直链。`list_packages` 的返回里加设备架构和 `pkg.installedBinaries()`。

## SettingsScreen.kt — 工具包卡片 + 自定义安装入口

`PackagesRow(pkg)`：遍历 `pkg.catalogue`，每行标题 + 体积 + 描述 + 安装/卸载按钮
+ 进度条 + 失败提示；底部 `CustomToolInstaller`：命令名 + 直链两个输入框，调
`pkg.installCustom`。挂在设置的「工具包」分组下（工程模式开启时显示）。
