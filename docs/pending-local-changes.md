# 分支状态说明

> 这个分支目前**不能直接编译**。请先读完本文。

## 怎么回事

开发容器在中途被重置，本地 git 丢了推送凭据（`could not read Username
for 'https://github.com'`），`git push` 从此不可用。匿名 `git fetch` 仍然
可以，所以只是写不进来，读没问题。

剩下唯一的写入通道是 GitHub 的 Contents API，它只能一个文件一个文件地
发**文本内容**。待推送的改动约 700 KB 源码，走这个通道成本太高，只推了
其中体积小、自成一体的几个文件。

## 已经推上来的

- `sandbox/ContainerProc.kt` —— 进程树遍历与 killTree
- `sandbox/ContainerGuard.kt` —— 命令闸门
- `sandbox/AiContainerManager.kt` —— 一会话一容器的注册表
- `nativebridge/NativeGate.kt` —— native 模块自检门控
- `BijiApp.kt` —— 容器接线

## 还没推上来的

**新文件**

- `sandbox/AiContainer.kt`、`ContainerSession.kt`、`ContainerTasks.kt`
- `nativebridge/NativeHighlight.kt`、`NativeMarkdown.kt`、`NativeText.kt`
- `cpp/jni_common.{h,cpp}`、`markdown_parser.{h,cpp}`、
  `syntax_highlight.{h,cpp}`、`text_tools.{h,cpp}`

**改动**

- `cpp/CMakeLists.txt`、`cpp/_probe.cpp`
- `MainActivity.kt`、`net/Tools.kt`
- `ui/chat/ChatScreen.kt`、`ChatViewModel.kt`、`ToolMessageCard.kt`
- `ui/editor/Diff.kt`、`EditorScreen.kt`、`SyntaxTransform.kt`
- `ui/markdown/Markdown.kt`、`Math.kt`、`SyntaxHighlight.kt`
- `ui/settings/SettingsScreen.kt`

因为缺了 `NativeHighlight` / `NativeMarkdown` / `NativeText`，已推上来的
`NativeGate.kt` 引用不到符号；`BijiApp.kt` 也引用不到 `AiContainer`。
补齐上面这批文件之后即可编译。

## 怎么拿到完整源码

完整的 6 个提交已经以 `git format-patch` 补丁包的形式直接发给用户
（`biji-code-patches.tar.gz`，不含 `dist/`）。在干净的工作区里：

```sh
tar xzf biji-code-patches.tar.gz
git am patches/*.patch
```

构建产物 APK 也已单独发出（约 17.9 MB，arm64-v8a）。

## 这批改动做了什么

1. **本地 AI 容器**：一个会话一个持久 shell + 后台任务 + 可重置工作区，
   挂在 Application 作用域上。给模型开了 `container_exec` /
   `container_task` / `container_info` / `container_manage` /
   `container_env` 五个工具。无 root，走 targetSdk 28 + 静态 aarch64
   二进制那条路。
2. **native 热点下沉**：语法高亮、Markdown 块解析、LaTeX 转换、行 diff
   （Myers）、语法错误扫描共 5 个调用点走 C++，每个模块首次使用前自检，
   不过就永久降级回 Kotlin 实现。
3. **CPU**：`groupChatItems` 不再对每条工具结果全量 parse JSON —— 流式
   输出期间这一处原本能吃掉将近半个核。
