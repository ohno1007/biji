// =====================================================================
// pty.h —— 真 PTY 子进程（无 root，minSdk 26）
//
// 解决的问题：现在的 InteractiveShell 用 ProcessBuilder + 管道，管道没有
// 行规程（N_TTY），0x03 只是一个普通字节 —— Ctrl-C 发不出 SIGINT，^Z/^D/
// 作业控制、以及一切「检测到 isatty 才开彩色/交互」的程序全都是残废的。
// 换成 pty 之后这些是内核白送的：行规程把 VINTR 变成给前台进程组的 SIGINT。
//
// 【为什么无 root 也能干】AOSP sepolicy 里是**显式授权**的，不是钻空子：
//   /dev/ptmx 的标签是 ptmx_device（private/file_contexts），
//   public/domain.te 有 `allow domain ptmx_device:chr_file rw_file_perms`
//   （含 ioctl），且没有任何 allowxperm 收窄 master 上的 ioctl；
//   slave 一侧由 public/te_macros 的 create_pty(untrusted_app_all) 覆盖，
//   untrusted_app_27（本仓库 targetSdk=28 落在这个域）也在里面。
//   TIOCSCTTY / TIOCSWINSZ / TCGETS / TCSETS / TIOCSPGRP 都在
//   unpriv_tty_ioctls 白名单内 —— 唯独 **TCSETSF（tcsetattr 的 TCSAFLUSH）
//   在 API 26 时代的白名单里没有**，所以本模块一律只用 TCSANOW。
//   TIOCGPTN/TIOCSPTLCK 不在 slave 白名单里，所以 unlockpt/ptsname_r
//   只能对 master 调（我们本来也只对 master 调）。
//
// 【本文件的硬性约束】
//  1. fork() 与 execve() 之间**只允许 async-signal-safe 调用**。JVM 进程里
//     fork 只复制调用线程，ART 的 GC/JIT/finalizer/binder 线程全都不存在，
//     但它们持有的互斥锁被原样复制成「永远锁着」。所以子进程里禁止：
//     任何 JNIEnv* 调用、__android_log_print、exit()（会跑 atexit）、
//     malloc 相关的东西（scudo 注册了 pthread_atfork 兜底，但没必要冒险）。
//     → 所有分配、JNI 取值、ptsname_r、termios、winsize 全部在 fork **之前**
//     做完；子进程路径退化成纯 syscall 序列，天然 AS-safe。
//  2. 不抛异常给 JVM（-fno-exceptions/-fno-rtti），失败一律返回负错误码，
//     由 Kotlin 判断后降级回 ProcessBuilder。Termux 的 termux.c 是
//     throw_runtime_exception，这一点**不抄**。
//  3. 无全局可变状态。fd / pid 全部返回给 Kotlin，由 Kotlin 的会话对象持有；
//     本模块是纯函数，可被多个会话并发调用。
//  4. 分配失败在 -fno-exceptions 下是 abort 而不是抛异常 ⇒ 上限检查必须
//     先于分配，大块分配一律 new (std::nothrow)（同 jni_common.h 第 5 条）。
//
// 【文件名注意】这个文件叫 pty.h，而 CMake 用 `-I` 把 cpp/ 加进了搜索路径，
// 所以它会**遮蔽 NDK 的 <pty.h>**（openpty/forkpty 所在）。本模块用不到
// openpty/forkpty（我们要在 fork 前后精确控制每一步），也没有任何系统头
// 会去 include <pty.h>，因此无害；但同目录的其它模块若想用 forkpty，
// 记得这里有一层遮蔽。
// =====================================================================
#ifndef BIJI_PTY_H_
#define BIJI_PTY_H_

#include <jni.h>
#include <sys/types.h>

#include <cstddef>
#include <cstdint>

namespace biji {
namespace pty {

// ---------------------------------------------------------------------
// 错误码 —— 必须与 NativePty.kt 的 ERR_* 逐个对应。
// 全部为负；正返回值是 fd / 退出码，不会撞。
// ---------------------------------------------------------------------
enum Err : int32_t {
    kErrBadArgs = -1,       ///< 入参 null / 数组超限 / rows,cols <= 0 / 串里有内嵌 NUL
    kErrAlloc = -2,         ///< new (std::nothrow) 失败
    kErrOpenPtmx = -3,      ///< open("/dev/ptmx") 失败。EACCES ⇒ OEM 收紧了 sepolicy
    kErrUnlockpt = -4,      ///< ioctl(TIOCSPTLCK) 失败
    kErrPtsname = -5,       ///< ioctl(TIOCGPTN) 失败
    kErrOpenPts = -6,       ///< open("/dev/pts/N") 失败（仅自检路径会到这里）
    kErrFork = -7,          ///< fork 失败。EAGAIN = 进程数到顶，可重试一次
    kErrExecNotFound = -8,  ///< 命令名不含 '/' 且在 envp 的 PATH 里找不到可执行文件
    kErrWinSize = -9,       ///< ioctl(TIOCSWINSZ) 失败（自检路径；正常路径忽略）
};

/// 数组/错误码布局版本。改布局时 +1，并同步 NativePty.PTY_FORMAT_VERSION。
constexpr int32_t kPtyFormatVersion = 1;

// waitpid 的两个哨兵。退出码是 0..255、信号是 -1..-64，都离 INT32_MIN 很远。
constexpr int32_t kWaitFailed = INT32_MIN;       ///< waitpid 本身失败（ECHILD 等）
constexpr int32_t kWaitRunning = INT32_MIN + 1;  ///< 仅 WNOHANG：子进程还活着

// 子进程在 exec 之前自己退出时用的两个码（shell 惯例）。
constexpr int32_t kExitPtsFailed = 126;   ///< open(slave) 失败 —— 终端都没有，报不出话
constexpr int32_t kExitExecFailed = 127;  ///< execve 失败 —— 原因已写到 pts 上，用户看得见

// ---------------------------------------------------------------------
// argv/envp 的物化上限
//
// kMaxUnitsPerItem 取 32K 个 UTF-16 code unit 而不是「64 KiB」：内核的
// MAX_ARG_STRLEN 是 32 个页 = 128 KiB，单条超了 execve 直接 E2BIG。
// 一个 UTF-16 unit 最多产生 3 字节 UTF-8（代理对是 2 unit → 4 字节，
// 更省），32K × 3 = 96 KiB < 128 KiB，任何输入都不可能撞上 MAX_ARG_STRLEN。
// kMaxTotalBytes 按最坏估算（3 字节/unit）计，1 MiB 远低于 ARG_MAX
// （通常 = RLIMIT_STACK/4 ≈ 2 MiB），真实 argv/envp 差几个数量级。
// ---------------------------------------------------------------------
constexpr jsize kMaxItems = 1024;
constexpr jsize kMaxUnitsPerItem = 32 * 1024;
constexpr size_t kMaxTotalBytes = 1u << 20;

// ---------------------------------------------------------------------
// ScopedUtf8 —— 单个 jstring → NUL 结尾的**真** UTF-8
//
// 为什么不用 GetStringUTFChars：那是 Modified UTF-8，U+0000 编成 C0 80、
// 增补平面（emoji）编成 CESU-8 的 6 字节代理对。路径或环境变量里出现
// emoji 就会喂给 execve 一个畸形字节串，而且是静默的。整个项目的第 1 条
// 约束（jni_common.h）就是禁用它。
//
// 内嵌 U+0000 一律判非法：execve 的参数是 NUL 结尾的 C 串，中间带 NUL
// 说明调用方逻辑本身就错了，静默截断比报错危险得多。
// ---------------------------------------------------------------------
class ScopedUtf8 {
public:
    ScopedUtf8(JNIEnv* env, jstring s, jsize maxUnits = kMaxUnitsPerItem) noexcept;
    ~ScopedUtf8();

    ScopedUtf8(const ScopedUtf8&) = delete;
    ScopedUtf8& operator=(const ScopedUtf8&) = delete;

    bool ok() const noexcept { return err_ == 0; }
    /// 失败原因（kErrBadArgs / kErrAlloc）；ok() 为真时是 0。
    int32_t err() const noexcept { return err_; }
    /// 永远非 null（失败时指向空串），省掉调用方一层判空。
    const char* c_str() const noexcept { return data_; }

private:
    static constexpr size_t kStackBytes = 512;  // PATH_MAX 之内的路径基本都落在这

    char stack_[kStackBytes];
    char* heap_ = nullptr;
    char* data_ = nullptr;
    int32_t err_ = kErrBadArgs;
};

// ---------------------------------------------------------------------
// ScopedExecArgs —— jobjectArray<String> → fork 后可直接用的 char* const argv[]
//
// 三个关键点：
//   1. **所有分配发生在 fork 之前** ⇒ 子进程只解引用已经物化好的指针，
//      不 malloc、不碰 JNI，这是整个方案 AS-safe 的前提；
//   2. 用 GetStringRegion 取 UTF-16 再自己转真 UTF-8（理由同 ScopedUtf8）；
//   3. 所有字符串塞进**一块连续 blob**，指针数组指进去 —— 全程只有两次
//      分配，失败路径简单，且都用 new (std::nothrow)，OOM 变成降级而非 abort。
//
// 另外每取一个元素就 DeleteLocalRef：JNI 局部引用表默认只有 512 项，
// 而这里允许 1024 条，不删必爆 "local reference table overflow"（abort，
// 绕过整套降级逻辑）。
// ---------------------------------------------------------------------
class ScopedExecArgs {
public:
    ScopedExecArgs(JNIEnv* env, jobjectArray arr, jsize maxItems = kMaxItems,
                   jsize maxUnitsPerItem = kMaxUnitsPerItem) noexcept;
    ~ScopedExecArgs();

    ScopedExecArgs(const ScopedExecArgs&) = delete;
    ScopedExecArgs& operator=(const ScopedExecArgs&) = delete;

    bool ok() const noexcept { return err_ == 0; }
    int32_t err() const noexcept { return err_; }

    /// NULL 结尾，可直接喂 execve。失败时返回一个只含 NULL 的合法数组 ——
    /// 调用方漏判 ok() 时最多 exec 出一个没有 argv 的进程，而不是解引用野指针。
    char* const* argv() const noexcept {
        return (err_ == 0 && ptrs_ != nullptr) ? ptrs_ : kEmpty;
    }
    jsize count() const noexcept { return count_; }

private:
    static char* const kEmpty[1];

    char* blob_ = nullptr;
    char** ptrs_ = nullptr;
    jsize count_ = 0;
    int32_t err_ = kErrBadArgs;
};

// ---------------------------------------------------------------------
// 核心操作（纯 POSIX，不碰 JNI —— 便于单独 review 和主机上编译验证）
// ---------------------------------------------------------------------

/// 开一个 pty pair，fork，在子进程里把 slave 接成控制终端并 execve。
///
/// @param execPath 命令路径。含 '/' 就直接用；不含 '/' 时**在父进程里**按
///                 envp 的 PATH 搜索（注意不能用 execvpe：bionic 的 execvpe
///                 读的是**父进程 environ 的 PATH**，拿不到容器 PATH，见
///                 libc/bionic/exec.cpp:119。而在子进程里做搜索又要 getenv/
///                 拼字符串，破坏 AS-safe，所以搜索必须在 fork 之前完成）。
/// @param cwd      子进程 chdir 目标，可为 nullptr；失败不致命（继续 exec）。
/// @param argv     含 argv[0]，NULL 结尾。
/// @param envp     **完整**环境，NULL 结尾。不继承 JVM 的 environ，
///                 也不 clearenv/putenv（那两个都 malloc 且改全局 environ）。
/// @param rows/cols       > 0，fork **之前**就设进 master，子进程一出生尺寸就对
///                        （否则 ncurses/vim 看到 0×0 会按 80×24 兜底然后画错）。
/// @param cellW/cellH     字符宽高（px），只用于 ws_xpixel/ws_ypixel，可传 0。
/// @param outPid   成功时写入子进程 pid（同时也是它的会话/进程组 id）。
/// @return >= 0：pty master 的 fd（**调用方负责关闭**）；< 0：Err。
int32_t createSubprocess(const char* execPath, const char* cwd, char* const argv[],
                         char* const envp[], int32_t rows, int32_t cols, int32_t cellW,
                         int32_t cellH, pid_t* outPid) noexcept;

/// ioctl(TIOCSWINSZ)。内核会在尺寸**真的变化**时自动给前台进程组发 SIGWINCH，
/// 不需要我们手动 kill。@return 0 或 -errno。失败不致命，忽略即可。
int32_t setWinSize(int fd, int32_t rows, int32_t cols, int32_t cellW, int32_t cellH) noexcept;

/// waitpid。@param blocking false 时用 WNOHANG。
/// @return 0..255 正常退出码；-1..-64 被信号杀（= -signo）；
///         kWaitRunning（仅 WNOHANG）还活着；kWaitFailed 失败。
/// EINTR 内部重试。**每个 pid 只能成功 wait 一次** —— 回收后 pid 立即可被
/// 复用，重复 wait 可能等到一个毫不相干的进程。
int32_t waitFor(pid_t pid, bool blocking) noexcept;

/// @return 0 或 -errno。EINTR **不重试**：Linux 上 close() 无论如何都会释放
/// 这个 fd，重试等于关掉别的线程刚 open 的同号 fd。
int32_t closeFd(int fd) noexcept;

/// 门控自检：走一遍完整链路但**不 fork** ——
/// open(/dev/ptmx) → grantpt → unlockpt → ptsname_r → open(slave) →
/// ioctl(TIOCSWINSZ) → 全部 close。
///
/// 必须真跑 unlockpt + ptsname_r，不能只 open 一下就返回成功：这两个是
/// TIOCSPTLCK/TIOCGPTN，恰恰是 OEM 魔改 sepolicy 时最可能被 allowxperm
/// 收窄掉的两个 ioctl，也是本方案里我唯一无法从策略源码 100% 推死的一环。
/// @return 0 表示这台设备允许 PTY；否则为 Err。
int32_t selfTest() noexcept;

}  // namespace pty
}  // namespace biji

#endif  // BIJI_PTY_H_
