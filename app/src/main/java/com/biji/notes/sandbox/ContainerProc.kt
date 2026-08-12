package com.biji.notes.sandbox

import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.delay
import java.io.File

/**
 * 进程树工具。
 *
 * 我们没有 PTY、也没有独立进程组（ProcessBuilder 不会 setsid，子进程的
 * pgid 直接继承 app 进程 —— 所以 `kill -- -<pgid>` 会把 biji 自己打死，
 * 绝对不能用）。要收掉一条超时命令或一个后台任务，只能顺着 /proc 的
 * ppid 链把子孙进程找全再逐个杀。只 destroyForcibly() 顶层 sh 会留下
 * 一堆孤儿进程继续吃 CPU 和电。
 *
 * 全部是无状态纯函数，异常一律吞掉（拿不到 /proc 就当没有子进程）。
 */
internal object ContainerProc {

    /** 把任意字符串包成单引号 shell 字面量。 */
    fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    fun isAlive(pid: Int): Boolean = pid > 0 && File("/proc/$pid").exists()

    /**
     * [rootPid] 的全部子孙 pid（不含自己），父在前、子在后。
     *
     * /proc/<pid>/stat 的第 2 字段 comm 允许含空格和括号（`(a b)` 甚至
     * `((x)`），所以必须从**最后一个** ')' 之后再按空格切；切完 index 0
     * 是 state，index 1 才是 ppid。
     */
    fun descendants(rootPid: Int): List<Int> {
        if (rootPid <= 0) return emptyList()
        val byParent = HashMap<Int, MutableList<Int>>()
        val dirs = File("/proc").listFiles() ?: return emptyList()
        for (d in dirs) {
            val pid = d.name.toIntOrNull() ?: continue
            val stat = runCatching { File(d, "stat").readText() }.getOrNull() ?: continue
            val tail = stat.substringAfterLast(')').trim()
            if (tail.isEmpty()) continue
            val ppid = tail.split(' ').getOrNull(1)?.toIntOrNull() ?: continue
            byParent.getOrPut(ppid) { mutableListOf() }.add(pid)
        }
        val out = ArrayList<Int>()
        val seen = HashSet<Int>()
        val queue = ArrayDeque<Int>()
        queue.addLast(rootPid)
        seen.add(rootPid)
        var guard = 0
        while (queue.isNotEmpty() && guard++ < MAX_WALK) {
            val cur = queue.removeFirst()
            val kids = byParent[cur] ?: continue
            for (child in kids) {
                if (seen.add(child)) {
                    out.add(child)
                    queue.addLast(child)
                }
            }
        }
        return out
    }

    private fun signal(pid: Int, sig: Int) {
        runCatching { Os.kill(pid, sig) }
    }

    /**
     * 杀掉 [rootPid] 的整棵子树。[includeSelf] = false 时保留 rootPid
     * 本身 —— 超时要干掉前台命令、但必须保住长驻 shell，靠的就是它。
     *
     * 先 TERM 给个善终机会（让编译器之类清掉临时文件），[graceMs] 后
     * 还活着的再 KILL。从叶子往根杀，避免父进程在子进程还在时重新 fork。
     */
    suspend fun killTree(rootPid: Int, includeSelf: Boolean, graceMs: Long = 150L) {
        val kids = descendants(rootPid)
        val targets = if (includeSelf) kids + rootPid else kids
        if (targets.isEmpty()) return
        for (p in targets.asReversed()) signal(p, OsConstants.SIGTERM)
        if (graceMs > 0) delay(graceMs)
        for (p in targets.asReversed()) if (isAlive(p)) signal(p, OsConstants.SIGKILL)
    }

    private const val MAX_WALK = 4096
}
