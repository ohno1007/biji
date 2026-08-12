package com.biji.notes.nativebridge

/**
 * native 模块的准入开关。
 *
 * 每个模块第一次被用到时跑一遍自检（中文 / emoji 代理对 / 未闭合围栏
 * 这些最容易在 UTF-16 offset 上翻车的输入），不过就永久走 Kotlin 版。
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
}
