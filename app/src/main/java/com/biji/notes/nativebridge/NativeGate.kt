package com.biji.notes.nativebridge

import com.biji.notes.terminal.TerminalEmulator

/**
 * 各子系统的准入开关。
 *
 * 每个模块第一次被用到时跑一遍自检（中文 / emoji 代理对 / 未闭合围栏
 * 这些最容易在 UTF-16 offset 上翻车的输入）。前三个纯计算模块不过就永久走
 * Kotlin 版；[pty] 和 [vt] 没有等价的纯 Kotlin 实现，它们的"降级"是各自
 * 调用点上一条明确的退路，写在下面各自的注释里。
 *
 * 分模块而不是一刀切：highlight 挂了不该把 markdown 也拖下水。
 *
 * 自检必须在**调用点**做，不能塞进 bridge 自己的方法里 —— 那样
 * `selfTest()` 会调到被门控的方法，`by lazy` 直接自锁。
 */
object NativeGate {
    val highlight: Boolean by lazy { NativeProbe.selfTest() && NativeHighlight.selfTest() }
    val markdown: Boolean by lazy { NativeProbe.selfTest() && NativeMarkdown.selfTest() }
    val text: Boolean by lazy { NativeProbe.selfTest() && NativeText.selfTest() }

    /**
     * PTY。**和上面三个纯计算模块不一样：这一项会 fork 并阻塞最多约 2 秒**
     * （自检真起一个 `echo` 子进程，只有这样才能证明 fork/setsid/控制终端/
     * dup2/execve 整条链在这台设备上是通的）。
     *
     * 所以这个 `by lazy` 的**首次触发必须在非主线程上**。会话层
     * （`sandbox/TerminalSession.kt` 的 `private object Pty`）已经保证了这一点：
     * 它只在 `Dispatchers.IO` 的 boot 流程里读它。
     */
    val pty: Boolean by lazy { NativeProbe.selfTest() && NativePty.selfTest() }

    /**
     * VT 模拟器（`terminal/TerminalEmulator`）。**这一项不是 native**，
     * 但要门控的理由和上面几个完全一样：它坏掉的时候不抛异常、不打日志，
     * 只是把宽字符的列数算错、或者状态不跨 feed 保持，症状是"屏幕看着像
     * 对的，其实是错的"。让用户对着一屏错位内容排查问题，比直接告诉他
     * "这里不可信"要糟糕得多。
     *
     * 和 [pty] 不同，这个自检是**纯计算**：自己 new 几个小模拟器喂脚本化
     * 字节流再比对，不 fork、不碰文件、微秒级，所以**任何线程读都安全**。
     *
     * `by lazy` 的本体在 [TerminalEmulator.usable]，这里只是转发 ——
     * `terminal` 包反过来依赖 `nativebridge` 会成包级环。放一个转发在这，
     * 是为了让"所有子系统的准入开关在同一个文件里查得到"这件事继续成立。
     *
     * 拿到 false 时的降级路径（这是这个开关存在的全部意义，别只判不处理）：
     *  - `ui/terminal`：终端仍然能开，但要把"渲染自检未通过、显示可能错位"
     *    明说给用户（会话层降级到管道时就是这么做的），不能装作一切正常；
     *  - `net/Tools.kt` 的 `terminal_snapshot`：**直接停用**，回一句为什么。
     *    把一屏可能错位的文本喂进模型上下文，等于给它一份伪造的事实。
     */
    val vt: Boolean get() = TerminalEmulator.usable
}
