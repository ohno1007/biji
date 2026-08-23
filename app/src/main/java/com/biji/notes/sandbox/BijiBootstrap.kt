package com.biji.notes.sandbox

import android.content.Context
import java.io.File

/**
 * 唯一那个「在 PATH 上、而且真的能 exec」的目录的持有者。
 *
 * 名字里的 bootstrap 是历史：它以前还负责下载 toybox 并把 applet 软链铺开。
 * 那套东西已经删干净了，理由有两条，写在这里免得哪天有人想「顺手加回来」：
 *
 * 1. **装工具只能有一条路**。[ToolchainInstaller] 已经把这件事做完了：
 *    ELF 头校验（静态 aarch64 / bionic）、smoke test、失败整包回滚、装完记账
 *    （[ToolchainRegistry]）。`bootstrapIfEmpty()` 装的就是目录里那条 toybox，
 *    和用户/模型手动装的走同一条流水线。这里再留一个自己下载、自己 chmod、
 *    不校验也不记账的 install()，等于给同一个 bin 目录开了后门 —— 账本上没有的
 *    文件出现在 PATH 上，`toolchain_remove` 只能回一句「那不归这里管」。
 *
 * 2. **原来的 uninstall() 是颗定时炸弹**。它 `deleteRecursively` 整个
 *    `filesDir/bootstrap`，而 [binDir] 就在里面 —— 一执行，账本里记着的每个
 *    二进制都没了，账本却毫不知情：`toolchain_probe` 继续报告 jq 装着，
 *    shell 里 `jq` command not found。真要「和账本对齐」，它得遍历账本、
 *    逐条删 bin 里的命令、再 `registry.clearAll()` —— 那正是
 *    [ToolchainInstaller.wipe] 已经写好的东西（设置页的「全部清空并重建」就是它），
 *    照抄一份只会多一个会漂移的副本。
 *
 * 所以这个类现在只剩「目录在哪」。[binDir] 被容器、终端 PATH、ToolchainInstaller
 * 到处引用，路径本身不能动。
 */
class BijiBootstrap(private val context: Context) {

    /** `filesDir/bootstrap`。内部存储 —— 共享存储那卷是 noexec 的，放这儿才 exec 得动。 */
    val rootDir: File get() = File(context.filesDir, "bootstrap").also { it.mkdirs() }

    /** 全 app 唯一在 shell PATH 上的可写 bin 目录。 */
    val binDir: File get() = File(rootDir, "bin").also { it.mkdirs() }

    /**
     * 目录里有没有能跑的东西。设置页拿它和工具账本一起判断「环境算不算就绪」——
     * 账本是空的但目录里有东西（老版本装的、或者模型自己编出来塞进去的）也算数。
     */
    val installed: Boolean
        get() = (binDir.listFiles()?.any { it.canExecute() } == true)
}
