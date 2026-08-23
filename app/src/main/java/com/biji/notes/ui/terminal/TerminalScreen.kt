package com.biji.notes.ui.terminal

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.biji.notes.sandbox.BijiBootstrap
import com.biji.notes.sandbox.LocalSandbox
import com.biji.notes.sandbox.TerminalSessionInfo
import com.biji.notes.sandbox.TerminalState
import androidx.compose.ui.graphics.luminance
import com.biji.notes.terminal.TerminalEmulator
import com.biji.notes.ui.glass.bouncyClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// =====================================================================
//  终端页。
//
//  ## 组件树
//    TerminalScreen
//     └ ModalNavigationDrawer            会话抽屉（新建 / 切换 / 改名 / 关闭）
//        └ Column
//           ├ TerminalTopBar             抽屉 / 标题+cwd / 键盘 / 中断 / 菜单
//           ├ Box(weight 1f)
//           │   ├ TerminalCanvas         ← 单个 Canvas 画整张网格
//           │   │   + Modifier.terminalGestures  ← 唯一的手势状态机
//           │   ├ TerminalImeAnchor      ← 1dp 隐形 View，只管 InputConnection
//           │   ├ DropdownMenu           ← 长按 / ⋮ 菜单
//           │   └ SessionStatusBar       ← 进程结束时的提示 + 重开
//           └ Column(ime ∪ navBars inset)
//               └ ExtraKeysRow           ← 粘滞 CTRL / ALT + Termux 默认两行
//
//  ## 相对旧实现拆掉的东西
//   * 底部那个「攒一整行再提交」的 TextField —— 那是 Termux 工具栏**第二页**的
//     东西，不是主交互。有了逐键直达 pty，readline / vim / `[y/N]` 才可能工作。
//   * UI 自己维护的命令历史（↑↓ 被 UI 吞掉）—— 历史归 shell 的 readline 管，
//     UI 插手只会和 bash 的历史打架。
//   * 按 `startsWith("$ ")` 猜颜色的逻辑 —— 有了真 SGR 之后那是纯错误来源。
//   * 每行一个 Text 的 LazyColumn、共享的 horizontalScroll。
//
//  ## inset 只在一处
//  旧实现外层 `imePadding()` + 输入行 `navigationBarsPadding()` 是双份，键盘弹起
//  时底部会多出一条导航栏高度的空隙。这里只有底部这一处
//  `ime ∪ navigationBars`（Compose 的 union 是每边取最大值，正是要的语义）。
// =====================================================================

// 终端外壳（顶栏 / 抽屉 / 菜单 / 输入框）跟着 app 主题走，不再写死黑底 ——
// 白天模式下整个 app 都是浅色、只有终端一块死黑，看着像另一个 app 贴进来的。
//
// 网格本身是另一回事：它要画的是 shell 发来的 ANSI 颜色，那套盘由
// TerminalTheme.of(dark) 决定（见 TerminalColors.BASE_16_LIGHT 里为什么
// 浅色盘不能靠把深色盘调亮糊弄过去）。
//
// 写成带 @Composable getter 的顶层属性，是为了让下面三十来处调用点一个字都不用改。
private val TerminalDark: Boolean
    @Composable get() = MaterialTheme.colorScheme.background.luminance() < 0.5f

private val TerminalBg: Color
    @Composable get() = Color(TerminalTheme.of(TerminalDark).background)

private val TerminalFg: Color
    @Composable get() = MaterialTheme.colorScheme.onSurface

private val TerminalDim: Color
    @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant

private val TerminalAccent: Color
    @Composable get() = MaterialTheme.colorScheme.primary

private val TerminalWarn: Color
    @Composable get() = MaterialTheme.colorScheme.error

private val PanelBg: Color
    @Composable get() = MaterialTheme.colorScheme.surfaceContainer

/**
 * 收到输出信号后先等这么久再问 cwd。
 *
 * `cd` 之后紧跟着的是提示符重绘，等输出停下来再问才问得准；顺带把一条命令期间
 * 的成百上千个重绘信号折成一次 readlink。
 */
private const val CWD_SETTLE_MS = 250L

/**
 * 两次 readlink 之间的最小间隔。
 *
 * 给 `yes` / `tail -f` 这类永远停不下来的输出兜底：不加的话每 [CWD_SETTLE_MS]
 * 就要问一次，比原来的定时轮询还费。
 */
private const val CWD_MIN_INTERVAL_MS = 1500L

/**
 * 没有 OSC 7 时的 cwd 兜底。
 *
 * 唤醒源是终端的重绘信号：屏幕不动就说明 shell 没在干活，cwd 不可能变，这条协程
 * 就一直挂着，一次 syscall 都不做。
 *
 * 限流用「把这一轮往后推」而不是「跳过这一轮」：跳过的话，一段长输出末尾那个信号
 * 正好被丢掉时，`cd` 完的新路径就再也不会显示出来了 —— 而那恰恰是最需要更新的
 * 时刻。推迟只是晚一点到，不会丢。
 */
private suspend fun probeCwdOnOutput(binding: TerminalBinding, onCwd: (String) -> Unit) {
    var earliest = 0L
    binding.redraw.collect {
        // 半路冒出 OSC 7（用户自己 source 了一份带 OSC 7 的 rc）：让位，别再 readlink。
        if (binding.sink.cwd.value != null) return@collect
        // 后台闸门：帧时钟被 Recomposer 在 ON_STOP 时暂停，这里会一直挂着，
        // 后台跑构建刷出来的输出不会再拽着我们做 syscall；回到前台的第一帧
        // 自动补一次。和 TerminalCanvas 用的是同一个机制。
        withFrameNanos { }
        delay(CWD_SETTLE_MS)
        val wait = earliest - SystemClock.uptimeMillis()
        if (wait > 0) delay(wait)
        earliest = SystemClock.uptimeMillis() + CWD_MIN_INTERVAL_MS
        val path = withContext(Dispatchers.IO) { binding.cwd() }
        if (!path.isNullOrEmpty()) onCwd(binding.shortenPath(path))
    }
}

@Composable
fun TerminalScreen(
    sandbox: LocalSandbox,
    bootstrap: BijiBootstrap,
    folder: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    val handles = rememberTerminalBinding(sandbox, bootstrap, folder)
    val binding = handles.binding

    // 字号：捏合实时改、抬手落盘。默认 13sp 换算成 px 之后按 2px 量化
    // （Termux 也是偶数 px），保证 cellW / cellH 稳定。
    val defaultFont = remember(density) {
        TerminalMetrics.quantize(with(density) { 13.sp.toPx() })
    }
    var fontPx by remember { mutableFloatStateOf(TerminalRuntime.loadFontPx(context, defaultFont)) }
    var fontAtPinchStart by remember { mutableFloatStateOf(fontPx) }
    val metrics = rememberTerminalMetrics(fontPx)

    // 网格配色跟随系统深浅色。remember 的 key 必须带上 dark，否则切主题时
    // 屏幕还画着上一套盘。
    val dark = TerminalDark
    val theme = remember(dark) { TerminalTheme.of(dark) }
    // key 是会话 id：选区存的是**绝对行号**，而绝对行号只在一个会话内部有意义。
    // 不带 key 的话，在会话 A 里选中一段再从抽屉切到会话 B，选区会原样活下来 ——
    // B 的屏幕上凭空高亮一块，「复制」复制的是 B 在那几行上的内容。
    // 用 id 而不是 binding 本身：binding 对象会随会话列表长度变化重建，
    // 拿它当 key 会让「关掉另一个会话」也顺手清掉当前的选区。
    val selection = remember(binding?.id) { TerminalSelection() }
    val viewInfo = remember { TerminalViewInfo() }
    val extraKeys = remember { ExtraKeysState() }
    val ime = rememberTerminalImeController()

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var menuAt by remember { mutableStateOf<Offset?>(null) }
    var renameTarget by remember { mutableStateOf<TerminalSessionInfo?>(null) }
    // 「＋」建不出会话时给抽屉里挂一条原因。用局部 state 而不是直接读
    // handles.lastError：那个值会一直留到下次建成功为止，直接渲染的话
    // 用户关掉一个终端、抽屉重开，那条早就不成立的红字还挂在上面。
    var newSessionError by remember { mutableStateOf<String?>(null) }

    // ---- 输入接线。每次重组重新赋值，不触发任何重组，也不重建 View ----
    SideEffect {
        ime.stickyMods = { extraKeys.peekMods() }
        ime.onText = { s ->
            val b = binding
            if (b != null) {
                // 软键盘上屏时也要认粘滞修饰键：手机上「点 CTRL 再点 c」是
                // Ctrl-C 最主要的输入方式，只在硬件键盘上支持等于没支持。
                val mods = extraKeys.consumeMods()
                if (mods != 0 && s.isNotEmpty()) {
                    val cp = s.codePointAt(0)
                    b.key(TerminalEmulator.KEY_CHAR, mods, cp)
                    val rest = s.substring(Character.charCount(cp))
                    if (rest.isNotEmpty()) b.text(rest)
                } else {
                    b.text(s)
                }
            }
        }
        ime.onKey = { key, mods, cp ->
            // stickyMods() 已经把粘滞状态并进 mods 了，这里再 consume 一次
            // 只是为了把「用完即失效」那部分清掉，返回值不再使用。
            extraKeys.consumeMods()
            binding?.key(key, mods, cp)
        }
    }

    // ---- 终端自己要说的话：标题 / cwd / 剪贴板 / 响铃 ----
    //
    // 这几个 flow 都先 remember 成非空再 collect。写成
    // `sink?.clipboard?.collectAsState()` 那种安全调用链的话，composable 调用
    // 会落在一个条件分支里，会话一换（sink 从 null 变成非 null）Compose 的
    // 组结构就对不上了。
    val sink = binding?.sink
    val clipFlow = remember(sink) { sink?.clipboard ?: MutableStateFlow<String?>(null) }
    val bellFlow = remember(sink) { sink?.bell ?: MutableStateFlow(0) }
    val titleFlow = remember(sink) { sink?.title ?: MutableStateFlow<String?>(null) }
    val stateFlow = remember(binding) {
        binding?.state ?: MutableStateFlow<TerminalState>(TerminalState.Idle)
    }

    val osc52 by clipFlow.collectAsState()
    val bell by bellFlow.collectAsState()
    val windowTitle by titleFlow.collectAsState()
    val state by stateFlow.collectAsState()

    LaunchedEffect(osc52) {
        val text = osc52 ?: return@LaunchedEffect
        clipboard.setText(AnnotatedString(text))
        sink?.consumeClipboard()
    }
    LaunchedEffect(bell) {
        // 手机上响铃没有意义，短震一下就够了。BEL 在 `printf '\a'` 和 tab 补全
        // 失败时都会响，做成声音会很烦人。
        if (bell > 0) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    // cwd 显示。**不能在重组里直接调 binding.cwd()** —— 那底下是
    // `readlink /proc/<pid>/cwd`，重组一次就是一次 syscall，而重组是很频繁的。
    //
    // 两条路，都是事件驱动的：
    //  * shell 的 PS1 带 OSC 7 → 直接收报告，`cd` 完立刻更新，一次 syscall 不做；
    //  * 没有 OSC 7 → 退到 readlink，但唤醒源是「终端有输出」而不是定时器。
    //
    // 原来这里是雷打不动的 1.5 秒一轮 while 循环，用户按 Home 走了也照转到进程被
    // 杀为止。而 cwd 只可能因为「终端里跑了东西」而变 —— 屏幕静止的时候那一轮轮
    // 线程跳转 + readlink + String 分配是纯浪费。现在静止 = 一次都不问。
    var cwdLabel by remember { mutableStateOf(folder.ifBlank { "/" }) }
    LaunchedEffect(binding) {
        val b = binding ?: return@LaunchedEffect
        val probe = launch { probeCwdOnOutput(b) { cwdLabel = it } }
        b.sink.cwd.collect { reported ->
            if (reported.isNullOrEmpty()) return@collect
            // OSC 7 到货了。它又快又准，兜底那条从此没有存在的理由，停掉。
            // cancel 幂等，每次上报都调一遍没有代价。
            probe.cancel()
            cwdLabel = b.shortenPath(reported)
        }
    }

    // 返回键：先退选区，再收抽屉，两者都没有才让外面的 BackHandler 关页面。
    BackHandler(enabled = selection.active || drawerState.isOpen) {
        when {
            selection.active -> selection.clear()
            else -> scope.launch { drawerState.close() }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = PanelBg) {
                SessionDrawer(
                    sessions = handles.sessions,
                    activeId = handles.activeId,
                    onSelect = {
                        handles.activate(it)
                        scope.launch { drawerState.close() }
                    },
                    onRename = { renameTarget = it },
                    onClose = {
                        handles.close(it.id)
                        // 关掉一个正好是上限的出路，那条提示当场就不成立了。
                        newSessionError = null
                    },
                    notice = newSessionError,
                    onNew = { failsafe ->
                        val ok = handles.newSession(
                            cols = binding?.columns ?: 80,
                            rows = binding?.screenRows ?: 24,
                            systemShell = failsafe
                        )
                        if (ok) {
                            newSessionError = null
                            scope.launch { drawerState.close() }
                        } else {
                            // 建不出来就把抽屉留在原地。上限的唯一出路是「关掉一个」，
                            // 而那份列表就在眼前；关掉抽屉等于把用户送回一块什么都
                            // 没发生的屏幕，他只会再按一次。
                            newSessionError = handles.lastError ?: "建不了新终端"
                        }
                    }
                )
            }
        }
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(TerminalBg)
        ) {
            TerminalTopBar(
                title = windowTitle
                    ?: handles.sessions.firstOrNull { it.id == handles.activeId }?.name
                    ?: "终端",
                subtitle = cwdLabel,
                compatMode = (state as? TerminalState.Running)?.pty == false,
                onDrawer = { scope.launch { drawerState.open() } },
                onKeyboard = { ime.toggle() },
                onInterrupt = {
                    binding?.interrupt()
                    ime.ensureFocus()
                },
                onMenu = { menuAt = Offset.Zero },
                onBack = onBack
            )

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (binding == null) {
                    // 两种「没有会话」要分开说。
                    //
                    // 建会话是会失败的（全局 8 个 / 单笔记 4 个上限，腾不出位置
                    // 就直说），失败时 ensure() 返回空串、不抛异常，而上面那个
                    // 补会话的 LaunchedEffect 的 key 也不会再变 —— 也就是说这块
                    // 空屏是**终局**，不是「再等等就好」。写死「会话启动中…」的话
                    // 用户会盯着一个永远不会变的加载态，既不知道原因，也不知道
                    // 出路（关掉一个别的终端）就在抽屉里。
                    val reason = handles.lastError
                    Text(
                        reason ?: "会话启动中…",
                        color = if (reason != null) TerminalWarn else TerminalDim,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(16.dp)
                    )
                } else {
                    // key 里**不能有 metrics**：捏合改字号会产生新的 metrics，
                    // 控制器一重建 pointerInput 就重启，捏合手势当场断掉。
                    val gestures = remember(binding, selection, viewInfo) {
                        TerminalGestureController(
                            binding = binding,
                            metrics = metrics,
                            selection = selection,
                            info = viewInfo,
                            scope = scope,
                            density = density
                        )
                    }
                    // 回调和 metrics 每次重组重新塞进去（都是普通字段写，
                    // 不触发重组，也不会重建控制器）。
                    SideEffect {
                        gestures.metrics = metrics
                        gestures.haptics = haptics
                        gestures.onZoomStart = { fontAtPinchStart = fontPx }
                        gestures.onZoom = { factor ->
                            // 量化到 2px 一档：跨过一档才真的换字号，
                            // 否则每帧都要重建 Paint + 重算行列 + 下 TIOCSWINSZ。
                            fontPx = TerminalMetrics.quantize(fontAtPinchStart * factor)
                        }
                        gestures.onZoomEnd = { TerminalRuntime.saveFontPx(context, fontPx) }
                        gestures.onLongPress = { menuAt = it }
                        gestures.onTap = { ime.show() }
                    }

                    TerminalCanvas(
                        binding = binding,
                        metrics = metrics,
                        theme = theme,
                        selection = selection,
                        viewInfo = viewInfo,
                        focused = ime.focused,
                        onGridSize = { cols, rows ->
                            binding.resize(
                                cols, rows,
                                metrics.cellW.toInt(), metrics.cellH.toInt()
                            )
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .terminalGestures(gestures)
                    )
                }

                // 输入锚点。必须在布局里（0 尺寸的 View 在部分 ROM 上拿不到焦点），
                // 但 1dp 且在左上角，谁也看不见。
                TerminalImeAnchor(
                    controller = ime,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .size(1.dp)
                )

                menuAt?.let { at ->
                    TerminalContextMenu(
                        at = at,
                        density = density,
                        hasSelection = selection.active,
                        onDismiss = { menuAt = null },
                        onCopy = {
                            val b = binding
                            if (b != null) {
                                val text = selection.text(b)
                                if (text.isNotEmpty()) clipboard.setText(AnnotatedString(text))
                            }
                            selection.clear()
                            menuAt = null
                        },
                        onPaste = {
                            clipboard.getText()?.text?.let { binding?.paste(it) }
                            menuAt = null
                            ime.ensureFocus()
                        },
                        onSelectAll = {
                            val b = binding
                            if (b != null) selection.selectAll(b.totalLines, b.columns)
                            menuAt = null
                        },
                        onInterrupt = {
                            binding?.interrupt()
                            menuAt = null
                            ime.ensureFocus()
                        },
                        onReset = {
                            // 程序被 kill 在半条转义序列里之后的自救：屏幕花了、
                            // 光标不见了、颜色卡住了，reset 一步还原。
                            binding?.reset()
                            selection.clear()
                            menuAt = null
                        },
                        onCloseSession = {
                            handles.activeId?.let { handles.close(it) }
                            menuAt = null
                        }
                    )
                }

                SessionStatusBar(
                    state = state,
                    onRestart = { binding?.restart() },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }

            Column(
                Modifier
                    .fillMaxWidth()
                    .background(PanelBg)
                    // 唯一的一处底部 inset。union = 每边取最大值：键盘弹起时用
                    // 键盘高度，收起时用导航栏高度，两者不会叠加。
                    .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
            ) {
                ExtraKeysRow(
                    binding = binding,
                    state = extraKeys,
                    onKeySent = { ime.ensureFocus() }
                )
            }
        }
    }

    renameTarget?.let { target ->
        RenameDialog(
            initial = target.name,
            onDismiss = { renameTarget = null },
            onConfirm = {
                handles.rename(target.id, it)
                renameTarget = null
            }
        )
    }
}

// ---------------------------------------------------------------------
// 顶栏
// ---------------------------------------------------------------------

@Composable
private fun TerminalTopBar(
    title: String,
    subtitle: String,
    compatMode: Boolean,
    onDrawer: () -> Unit,
    onKeyboard: () -> Unit,
    onInterrupt: () -> Unit,
    onMenu: () -> Unit,
    onBack: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconBtn(Icons.AutoMirrored.Rounded.ArrowBack, "返回", onBack)
        IconBtn(Icons.Rounded.Menu, "会话列表", onDrawer)
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = TerminalFg,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (compatMode) {
                    // 兼容模式（没拿到 pty）必须一眼看得见，否则用户会一直
                    // 疑惑「为什么没有颜色 / 为什么 vi 起不来」。
                    Text(
                        "兼容模式",
                        fontSize = 9.sp,
                        color = TerminalWarn,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .padding(start = 6.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.16f))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            }
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = TerminalDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconBtn(Icons.Rounded.Keyboard, "键盘", onKeyboard)
        IconBtn(Icons.Rounded.Stop, "中断", onInterrupt)
        IconBtn(Icons.Rounded.MoreVert, "更多", onMenu)
    }
}

@Composable
private fun IconBtn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .size(38.dp)
            .clip(CircleShape)
            .bouncyClickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(19.dp), tint = TerminalFg)
    }
}

// ---------------------------------------------------------------------
// 长按 / ⋮ 菜单
// ---------------------------------------------------------------------

@Composable
private fun TerminalContextMenu(
    at: Offset,
    density: androidx.compose.ui.unit.Density,
    hasSelection: Boolean,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onSelectAll: () -> Unit,
    onInterrupt: () -> Unit,
    onReset: () -> Unit,
    onCloseSession: () -> Unit
) {
    // DropdownMenu 的 offset 是相对锚点（这里是终端区左上角）的，
    // 把长按点的像素坐标换成 dp 就能让菜单从手指那儿弹出来。
    val offset = with(density) { DpOffset(at.x.toDp(), at.y.toDp()) }
    DropdownMenu(
        expanded = true,
        onDismissRequest = onDismiss,
        offset = offset,
        modifier = Modifier.background(PanelBg)
    ) {
        MenuItem("复制", enabled = hasSelection, onClick = onCopy)
        MenuItem("粘贴", onClick = onPaste)
        MenuItem("全选", onClick = onSelectAll)
        MenuItem("中断（^C）", onClick = onInterrupt)
        MenuItem("重置终端", onClick = onReset)
        MenuItem("关闭会话", onClick = onCloseSession)
    }
}

@Composable
private fun MenuItem(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Text(
                label,
                color = if (enabled) TerminalFg else TerminalDim,
                fontSize = 14.sp
            )
        },
        enabled = enabled,
        onClick = onClick
    )
}

// ---------------------------------------------------------------------
// 会话抽屉
// ---------------------------------------------------------------------

@Composable
private fun SessionDrawer(
    sessions: List<TerminalSessionInfo>,
    activeId: String?,
    onSelect: (String) -> Unit,
    onRename: (TerminalSessionInfo) -> Unit,
    onClose: (TerminalSessionInfo) -> Unit,
    onNew: (failsafe: Boolean) -> Unit,
    /** 建会话失败的原因；null 就什么都不画。 */
    notice: String? = null
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "会话",
                color = TerminalFg,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            IconBtn(Icons.Rounded.Add, "新建会话") { onNew(false) }
        }

        // 建不出会话时的原因。摆在「＋」正下方、列表正上方 —— 它说的是
        // 「先关掉一个」，而要关的那份列表就在紧接着的下面。
        if (notice != null) {
            Text(
                notice,
                color = TerminalWarn,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(TerminalWarn.copy(alpha = 0.12f))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }

        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 8.dp)
        ) {
            items(sessions, key = { it.id }) { info ->
                SessionRow(
                    info = info,
                    active = info.id == activeId,
                    onSelect = { onSelect(info.id) },
                    onRename = { onRename(info) },
                    onClose = { onClose(info) }
                )
            }
        }

        // failsafe：装出来的 bash 把终端搞崩时唯一的自救入口。Termux 也有，
        // 藏在长按「+」里；这里直接摆出来，手机上长按太难被发现。
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(bottom = 8.dp)
                .clip(RoundedCornerShape(10.dp))
                .bouncyClickable { onNew(true) }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "failsafe 会话",
                color = TerminalDim,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
    }
}

@Composable
private fun SessionRow(
    info: TerminalSessionInfo,
    active: Boolean,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onClose: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                else Color.Transparent
            )
            .bouncyClickable(onClick = onSelect)
            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                info.name,
                color = if (active) TerminalAccent else TerminalFg,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                buildString {
                    append(info.statusText)
                    if (info.pid > 0) append(" · pid ${info.pid}")
                },
                color = TerminalDim,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1
            )
        }
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .bouncyClickable(onClick = onRename),
            contentAlignment = Alignment.Center
        ) {
            Text("改名", color = TerminalDim, fontSize = 10.sp)
        }
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .bouncyClickable(onClick = onClose),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = "关闭会话",
                modifier = Modifier.size(16.dp),
                tint = TerminalDim
            )
        }
    }
}

@Composable
private fun RenameDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember(initial) { mutableStateOf(initial) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PanelBg,
        title = { Text("会话改名", color = TerminalFg) },
        text = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = TerminalFg),
                    cursorBrush = SolidColor(TerminalAccent),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { onConfirm(text) }) {
                Text("确定", color = TerminalAccent)
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("取消", color = TerminalDim)
            }
        }
    )
}

// ---------------------------------------------------------------------
// 进程结束提示
// ---------------------------------------------------------------------

@Composable
private fun SessionStatusBar(
    state: TerminalState,
    onRestart: () -> Unit,
    modifier: Modifier = Modifier
) {
    val message = when (state) {
        is TerminalState.Exited -> state.message
        is TerminalState.Failed -> state.message
        else -> null
    } ?: return

    Row(
        modifier
            .fillMaxWidth()
            .background(Color(0xE6201510))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            message,
            color = TerminalWarn,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Box(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0x33FFB27A))
                .bouncyClickable(onClick = onRestart)
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text("重开", color = TerminalWarn, fontSize = 12.sp)
        }
    }
}
