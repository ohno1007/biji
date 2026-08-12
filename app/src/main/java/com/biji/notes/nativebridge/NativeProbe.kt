package com.biji.notes.nativebridge

/** native 链路自检。available 为 false 时上层一律走 Kotlin 实现。 */
object NativeProbe {
    val available: Boolean = runCatching { System.loadLibrary("bijinative") }.isSuccess

    external fun utf16Length(s: String): Int

    fun selfTest(): Boolean = runCatching {
        available && utf16Length("中a😀") == 4
    }.getOrDefault(false)
}
