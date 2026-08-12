// =====================================================================
// pty.cpp —— 见 pty.h 的设计约束。
//
// 阅读顺序建议：utf16ToUtf8 → ScopedUtf8/ScopedExecArgs（fork 前的物化）
// → runChild（fork 后唯一允许执行的东西，全是裸 syscall）
// → createSubprocess（把两半缝起来）→ 文件末尾的 JNI 入口。
// =====================================================================
#include "pty.h"

#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <signal.h>
#include <stdlib.h>
#include <sys/ioctl.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

#include <new>  // std::nothrow

#include "jni_common.h"

// IUTF8 来自 <linux/termios.h> 的 uapi 定义。各版本 NDK sysroot 都应该有，
// 但这是我没能逐版本核对的一处，缺了就自己补上 —— 它只是 c_iflag 的一个位，
// 值在 uapi 里是固定的 0040000（asm-generic/termbits.h）。
#ifndef IUTF8
#define IUTF8 0040000
#endif

// close_range 是 Linux 5.9 的 syscall，bionic 的 wrapper 标了
// __INTRODUCED_IN(34)，minSdk 26 编不过。但 syscall 号本身是全架构统一的
// 436，内核够新就能用 —— 所以绕过 libc wrapper 直接 syscall，ENOSYS 再降级。
// （Android 8/9 设备普遍是 4.x 内核，慢路一定会被走到，别只写快路。）
#ifndef __NR_close_range
#define __NR_close_range 436
#endif

namespace biji {
namespace pty {

namespace {

// =====================================================================
// UTF-16 → 真 UTF-8
// =====================================================================
//
// 为什么必须自己写：GetStringUTFChars 给的是 Modified UTF-8 —— U+0000 编成
// C0 80，增补平面字符编成 CESU-8 的 6 字节代理对。路径/环境变量里出现一个
// emoji，execve 拿到的就是畸形字节串，而且是静默的（内核只当普通字节，
// 最后表现为「文件不存在」）。
//
// @param cap 允许写入的字节数（不含结尾 NUL，调用方自己补）。
// @return 写入字节数；-1 表示内嵌 U+0000（非法）或空间不够。
//
// 孤儿代理（配不上对的高/低代理）替换成 U+FFFD：直接透传会产生非法 UTF-8，
// 下游从终端解码到日志全线跟着坏，而替换字符至少是可见、可诊断的。
ptrdiff_t utf16ToUtf8(const uint16_t* src, jsize len, char* dst, size_t cap) noexcept {
    if (len < 0 || (len > 0 && (src == nullptr || dst == nullptr))) return -1;

    size_t o = 0;
    for (jsize i = 0; i < len; ++i) {
        uint32_t cp = src[i];

        // execve 的参数是 NUL 结尾的 C 串，中间带 NUL 说明调用方逻辑错了。
        // 静默截断比报错危险得多（"rm -rf /x\0/y" 会变成 "rm -rf /x"）。
        if (cp == 0) return -1;

        if (cp >= 0xD800 && cp <= 0xDBFF) {
            if (i + 1 < len) {
                const uint32_t lo = src[i + 1];
                if (lo >= 0xDC00 && lo <= 0xDFFF) {
                    cp = 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00);
                    ++i;
                } else {
                    cp = 0xFFFD;
                }
            } else {
                cp = 0xFFFD;
            }
        } else if (cp >= 0xDC00 && cp <= 0xDFFF) {
            cp = 0xFFFD;  // 没有前导高代理的孤儿低代理
        }

        if (cp < 0x80) {
            if (o + 1 > cap) return -1;
            dst[o++] = static_cast<char>(cp);
        } else if (cp < 0x800) {
            if (o + 2 > cap) return -1;
            dst[o++] = static_cast<char>(0xC0 | (cp >> 6));
            dst[o++] = static_cast<char>(0x80 | (cp & 0x3F));
        } else if (cp < 0x10000) {
            if (o + 3 > cap) return -1;
            dst[o++] = static_cast<char>(0xE0 | (cp >> 12));
            dst[o++] = static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
            dst[o++] = static_cast<char>(0x80 | (cp & 0x3F));
        } else {
            if (o + 4 > cap) return -1;
            dst[o++] = static_cast<char>(0xF0 | (cp >> 18));
            dst[o++] = static_cast<char>(0x80 | ((cp >> 12) & 0x3F));
            dst[o++] = static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
            dst[o++] = static_cast<char>(0x80 | (cp & 0x3F));
        }
    }
    return static_cast<ptrdiff_t>(o);
}

// =====================================================================
// 以下这一组是 fork 之后、execve 之前唯一允许调用的东西：
// 全部 async-signal-safe（裸 syscall 或纯计算），无分配、无 JNI、无 log。
// =====================================================================

size_t cstrLen(const char* s) noexcept {
    size_t n = 0;
    while (s != nullptr && s[n] != '\0') ++n;
    return n;
}

/// 短写和 EINTR 都要处理；写失败就放弃（子进程已经没有别的报错渠道了）。
void writeAll(int fd, const char* s, size_t n) noexcept {
    size_t off = 0;
    while (off < n) {
        const ssize_t w = write(fd, s + off, n - off);
        if (w > 0) {
            off += static_cast<size_t>(w);
        } else if (w < 0 && errno == EINTR) {
            continue;
        } else {
            return;
        }
    }
}

void writeCStr(int fd, const char* s) noexcept { writeAll(fd, s, cstrLen(s)); }

/// 手写 itoa：snprintf 不是 AS-safe（内部可能取锁、按 locale 走）。
void writeInt(int fd, long v) noexcept {
    char buf[24];
    size_t n = sizeof(buf);
    const bool neg = v < 0;
    unsigned long u = neg ? static_cast<unsigned long>(-(v + 1)) + 1u : static_cast<unsigned long>(v);
    do {
        buf[--n] = static_cast<char>('0' + (u % 10));
        u /= 10;
    } while (u != 0 && n > 1);
    if (neg && n > 0) buf[--n] = '-';
    writeAll(fd, buf + n, sizeof(buf) - n);
}

/// 内核 getdents64 的记录布局。不用 <dirent.h> 的 struct dirent64 是为了
/// 不依赖 libc 的结构体定义与 uapi 恰好一致这件事（bionic 上确实一致，
/// 但这里只需要三个字段，自己写更明确）。
struct Dirent64 {
    uint64_t d_ino;
    int64_t d_off;
    unsigned short d_reclen;
    unsigned char d_type;
    char d_name[1];  // 实际是变长、NUL 结尾
};

/// 关掉从 JVM 继承来的所有 fd（> 2）。
///
/// 不关的后果：JVM 进程随手就是几百个打开的 fd（binder、ashmem、apk 的
/// mmap、socket），shell 和它的**所有孙子进程**会全盘继承 —— 既是泄漏也是
/// 安全问题，还会让某些 fd 的对端永远等不到 EOF。
///
/// Termux 用 opendir("/proc/self/fd") 遍历（malloc，非 AS-safe）。这里用
/// open + getdents64 + 栈缓冲，零分配。
void closeInheritedFds(int keepAbove) noexcept {
    // 快路：一个 syscall 关掉 [3, ∞)。老内核 ENOSYS，往下降级。
    if (syscall(__NR_close_range, static_cast<unsigned int>(keepAbove + 1), ~0u, 0u) == 0) {
        return;
    }

    const int dfd = open("/proc/self/fd", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (dfd < 0) {
        // 兜底：按 RLIMIT_NOFILE 硬扫。上限夹在 [1024, 65536]，
        // 65536 次 close 也就几毫秒，而且这条路基本不可能被走到
        // （/proc 不可读的 Android 设备等于什么都跑不起来）。
        rlim_t hi = 1024;
        struct rlimit rl;
        if (getrlimit(RLIMIT_NOFILE, &rl) == 0 && rl.rlim_cur != RLIM_INFINITY) {
            hi = rl.rlim_cur;
        }
        if (hi < 1024) hi = 1024;
        if (hi > 65536) hi = 65536;
        for (int fd = keepAbove + 1; fd < static_cast<int>(hi); ++fd) close(fd);
        return;
    }

    // 8 字节对齐：记录里第一个字段是 u64，内核也按 8 对齐排布每条记录。
    alignas(8) char buf[4096];
    for (;;) {
        const long n = syscall(__NR_getdents64, dfd, buf, sizeof(buf));
        if (n <= 0) break;

        long pos = 0;
        while (pos < n) {
            const Dirent64* d = reinterpret_cast<const Dirent64*>(buf + pos);
            if (d->d_reclen == 0) break;  // 防御：畸形记录会让循环死住
            pos += d->d_reclen;

            // 手工 atoi（strtol 会碰 locale）。非纯数字（"." / ".."）直接跳过。
            int fd = 0;
            bool digits = false;
            for (const char* p = d->d_name; *p != '\0'; ++p) {
                if (*p < '0' || *p > '9') {
                    digits = false;
                    break;
                }
                digits = true;
                fd = fd * 10 + (*p - '0');
                if (fd > 1 << 24) {  // 溢出保护
                    digits = false;
                    break;
                }
            }
            if (digits && fd > keepAbove && fd != dfd) close(fd);
        }
    }
    close(dfd);
}

/// fork 之后子进程要用到的全部输入。这个结构体活在**父进程 createSubprocess
/// 的栈帧**上，fork 时整份栈被复制，子进程访问的是自己的那份，合法。
struct ChildCtx {
    const char* execPath;
    const char* cwd;        // 可为 nullptr
    char* const* argv;
    char* const* envp;
    const char* slaveName;  // "/dev/pts/N"，fork 前就算好了
    int ptm;
};

/// fork 后子进程的全部逻辑。noinline 是为了让「fork 前/后」在生成的代码里
/// 泾渭分明，review 和排查时不用怀疑编译器把什么东西挪过了 fork。
[[noreturn]] __attribute__((noinline)) void runChild(const ChildCtx& c) noexcept {
    // JVM 屏蔽了一堆信号；不解开的话子进程（以及它 exec 出来的 shell）
    // 收不到 SIGINT —— 那样 pty 的行规程白做了。
    sigset_t all;
    sigfillset(&all);
    sigprocmask(SIG_UNBLOCK, &all, nullptr);

    // execve 会把 handler 重置成 SIG_DFL，但**不会**重置 SIG_IGN —— 被忽略的
    // 信号会一路继承给 shell 和它的所有孙子进程。最典型的受害者是 SIGPIPE：
    // JVM 把它设成 SIG_IGN，不复位的话 `yes | head` 这类管道永远不会正常收尾。
    // SIGKILL/SIGSTOP 上会 EINVAL，无害。
    for (int i = 1; i < NSIG; ++i) signal(i, SIG_DFL);

    // 必须关！否则 master 永远有一个引用，子进程死后父进程的 read()
    // 收不到 EIO，reader 线程就永远挂着。
    close(c.ptm);

    // 新会话：脱离原控制终端，本进程成为会话首进程 + 进程组长。
    // 组长这件事让父进程可以用 kill(-pid) 一把收掉整棵进程树。
    setsid();

    // **不带 O_NOCTTY** ⇒ 会话首进程 open 一个 tty 就自动获得控制终端。
    // Termux 就只靠这条 Linux 语义，它根本没调 TIOCSCTTY。
    const int pts = open(c.slaveName, O_RDWR);
    if (pts < 0) _exit(kExitPtsFailed);

    // 再显式兜一道（bionic login_tty 的路线）。TIOCSCTTY 在 sepolicy 的
    // unpriv_tty_ioctls 白名单内，且对已经是本会话 ctty 的 fd 是幂等的 ——
    // 零成本换一道保险。
    ioctl(pts, TIOCSCTTY, 0);

    dup2(pts, STDIN_FILENO);
    dup2(pts, STDOUT_FILENO);
    dup2(pts, STDERR_FILENO);
    if (pts > STDERR_FILENO) close(pts);

    closeInheritedFds(STDERR_FILENO);

    // 失败不致命：目录没了也让命令跑起来，让用户从错误信息里看出问题，
    // 比"什么都没发生"强。写成 if 而不是裸调用是因为 _FORTIFY_SOURCE 给
    // chdir 标了 warn_unused_result（NDK release 构建默认开 fortify）。
    if (c.cwd != nullptr && c.cwd[0] != '\0') {
        if (chdir(c.cwd) != 0) { /* 故意忽略 */ }
    }

    // 绝对路径 + execve，**不用 execvp/execvpe**：bionic 的 execvpe 做 PATH
    // 搜索时读的是 getenv("PATH")，也就是**父进程 environ** 的 PATH，
    // 拿不到我们 envp 里的容器 PATH（libc/bionic/exec.cpp:119）。
    // 路径解析已经在 fork 之前的父进程里做完了。
    execve(c.execPath, c.argv, c.envp);

    // 只有 exec 失败才会走到这里。此时 fd 2 就是 pts，直接 write ——
    // 用户在终端里看得见，而且 write 是 AS-safe 的（__android_log_print 不是）。
    const int e = errno;
    writeCStr(STDERR_FILENO, "biji-pty: exec 失败 (errno=");
    writeInt(STDERR_FILENO, e);
    writeCStr(STDERR_FILENO, "): ");
    writeCStr(STDERR_FILENO, c.execPath);
    writeCStr(STDERR_FILENO, "\n");

    // 必须 _exit 而不是 exit：exit 会跑 atexit 处理器（ART 注册过），
    // 在一个只复制了一条线程的进程里执行它们 = 死锁。
    // （Termux 的 termux.c:81 写的是 exit(-1)，那是它的 bug，别抄。）
    _exit(kExitExecFailed);
}

// =====================================================================
// 父进程侧的辅助
// =====================================================================

/// 在 envp 里找 "PATH=" 的值。找不到返回 nullptr（**不**回退到父进程的
/// getenv：那正是我们要避开的东西）。
const char* pathFromEnv(char* const envp[]) noexcept {
    if (envp == nullptr) return nullptr;
    for (int i = 0; envp[i] != nullptr; ++i) {
        const char* e = envp[i];
        if (e[0] == 'P' && e[1] == 'A' && e[2] == 'T' && e[3] == 'H' && e[4] == '=') {
            return e + 5;
        }
    }
    return nullptr;
}

/// 命令名不含 '/' 时，在**父进程**里按 envp 的 PATH 搜索。
/// 这里可以随便用 access/字符串操作 —— 还没 fork。
bool resolveExec(const char* cmd, char* const envp[], char* out, size_t cap) noexcept {
    const size_t cmdLen = cstrLen(cmd);
    if (cmdLen == 0 || cap == 0) return false;

    const char* path = pathFromEnv(envp);
    if (path == nullptr) return false;

    const char* seg = path;
    for (;;) {
        const char* end = seg;
        while (*end != '\0' && *end != ':') ++end;

        // 空段（"::" 或首尾冒号）按 POSIX 是当前目录。
        size_t dirLen = static_cast<size_t>(end - seg);
        const char* dir = seg;
        if (dirLen == 0) {
            dir = ".";
            dirLen = 1;
        }

        if (dirLen + 1 + cmdLen + 1 <= cap) {
            size_t o = 0;
            for (size_t i = 0; i < dirLen; ++i) out[o++] = dir[i];
            if (out[o - 1] != '/') out[o++] = '/';
            for (size_t i = 0; i < cmdLen; ++i) out[o++] = cmd[i];
            out[o] = '\0';
            // access 是 TOCTOU，但这里的替代品（在子进程里逐个 execve 试）
            // 要么破坏 AS-safe 要么无法把"找不到"和"跑失败"区分开。
            // 真跑丢了也只是 exec 失败 127，用户在终端里看得见。
            if (access(out, X_OK) == 0) return true;
        }

        if (*end == '\0') break;
        seg = end + 1;
    }
    out[0] = '\0';
    return false;
}

unsigned short clampU16(int32_t v) noexcept {
    if (v < 0) return 0;
    if (v > 0xFFFF) return 0xFFFF;
    return static_cast<unsigned short>(v);
}

/// 像素尺寸只是给 ws_xpixel/ws_ypixel 参考，溢出就夹到 65535，
/// 不值得为它失败（几乎没有程序真读这两个字段）。
unsigned short pixelExtent(int32_t cells, int32_t cellPx) noexcept {
    if (cells <= 0 || cellPx <= 0) return 0;
    const int64_t v = static_cast<int64_t>(cells) * static_cast<int64_t>(cellPx);
    return v > 0xFFFF ? static_cast<unsigned short>(0xFFFF) : static_cast<unsigned short>(v);
}

}  // namespace

// =====================================================================
// ScopedUtf8
// =====================================================================
ScopedUtf8::ScopedUtf8(JNIEnv* env, jstring s, jsize maxUnits) noexcept {
    stack_[0] = '\0';
    data_ = stack_;  // 失败时 c_str() 也是合法空串，不是野指针

    if (env == nullptr || s == nullptr || maxUnits < 0) return;

    // ScopedUtf16 是 kReject 策略：超限直接失败。对 exec 参数来说这是对的 ——
    // 截断一个路径会静默跑错东西。（它的 !ok() 也可能是 OOM，这里不区分，
    // 反正两种都只导致上层降级。）
    biji::jni::ScopedUtf16 u(env, s, maxUnits);
    if (!u.ok()) return;

    const size_t worst = static_cast<size_t>(u.size()) * 3u + 1u;
    size_t cap = kStackBytes;
    if (worst > kStackBytes) {
        // -fno-exceptions：nothrow + 判空，OOM 变降级而不是 abort。
        heap_ = new (std::nothrow) char[worst];
        if (heap_ == nullptr) {
            err_ = kErrAlloc;
            return;
        }
        data_ = heap_;
        cap = worst;
    }

    const ptrdiff_t n = utf16ToUtf8(u.u16raw(), u.size(), data_, cap - 1);
    if (n < 0) {
        data_ = stack_;
        stack_[0] = '\0';
        return;  // err_ 保持 kErrBadArgs（内嵌 NUL）
    }
    data_[n] = '\0';
    err_ = 0;
}

ScopedUtf8::~ScopedUtf8() {
    delete[] heap_;  // nullptr 时是合法 no-op
}

// =====================================================================
// ScopedExecArgs
// =====================================================================
char* const ScopedExecArgs::kEmpty[1] = {nullptr};

ScopedExecArgs::ScopedExecArgs(JNIEnv* env, jobjectArray arr, jsize maxItems,
                               jsize maxUnitsPerItem) noexcept {
    if (env == nullptr || arr == nullptr || maxItems <= 0 || maxUnitsPerItem < 0) return;

    const jsize n = env->GetArrayLength(arr);
    if (n < 0 || n > maxItems) return;

    // 第一趟只量长度、不取内容：取两次内容要么多一次全量拷贝，要么得同时
    // 持有 n 个局部引用（512 项上限，1024 条必爆）。按最坏情况 3 字节/unit
    // 估 blob 大小，代价是几十 KB 的虚高，换来只分配两次。
    size_t worst = 0;
    for (jsize i = 0; i < n; ++i) {
        jobject o = env->GetObjectArrayElement(arr, i);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return;
        }
        if (o == nullptr) return;  // null 元素是调用方的 bug，不猜
        const jsize len = env->GetStringLength(static_cast<jstring>(o));
        env->DeleteLocalRef(o);
        if (len < 0 || len > maxUnitsPerItem) return;
        worst += static_cast<size_t>(len) * 3u + 1u;
        if (worst > kMaxTotalBytes) return;  // 先查上限再分配（约束 4）
    }

    ptrs_ = new (std::nothrow) char*[static_cast<size_t>(n) + 1];
    if (ptrs_ == nullptr) {
        err_ = kErrAlloc;
        return;
    }
    if (worst == 0) worst = 1;  // n == 0：仍然要一块合法内存
    blob_ = new (std::nothrow) char[worst];
    if (blob_ == nullptr) {
        err_ = kErrAlloc;
        return;
    }

    size_t off = 0;
    for (jsize i = 0; i < n; ++i) {
        jobject o = env->GetObjectArrayElement(arr, i);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return;
        }
        if (o == nullptr) return;
        biji::jni::ScopedUtf16 u(env, static_cast<jstring>(o), maxUnitsPerItem);
        env->DeleteLocalRef(o);
        if (!u.ok()) return;

        if (off + 1 > worst) return;  // 不变量被破坏（理论到不了），宁可失败
        const ptrdiff_t w = utf16ToUtf8(u.u16raw(), u.size(), blob_ + off, worst - off - 1);
        if (w < 0) return;  // 内嵌 NUL / 空间不够

        ptrs_[i] = blob_ + off;
        off += static_cast<size_t>(w);
        blob_[off++] = '\0';
    }
    ptrs_[n] = nullptr;
    count_ = n;
    err_ = 0;
}

ScopedExecArgs::~ScopedExecArgs() {
    delete[] blob_;
    delete[] ptrs_;
}

// =====================================================================
// createSubprocess
// =====================================================================
int32_t createSubprocess(const char* execPath, const char* cwd, char* const argv[],
                         char* const envp[], int32_t rows, int32_t cols, int32_t cellW,
                         int32_t cellH, pid_t* outPid) noexcept {
    if (execPath == nullptr || execPath[0] == '\0' || argv == nullptr || envp == nullptr ||
        outPid == nullptr || rows <= 0 || cols <= 0) {
        return kErrBadArgs;
    }

    // ---- 路径解析：必须在 fork 之前完成，理由见 pty.h ----
    char resolved[PATH_MAX];
    const char* finalPath = execPath;
    bool hasSlash = false;
    for (const char* p = execPath; *p != '\0'; ++p) {
        if (*p == '/') {
            hasSlash = true;
            break;
        }
    }
    if (!hasSlash) {
        if (!resolveExec(execPath, envp, resolved, sizeof(resolved))) return kErrExecNotFound;
        finalPath = resolved;
    }

    // ---- master ----
    // O_CLOEXEC 不是可选项：本进程随时可能有别的线程在跑 ProcessBuilder
    // （AiContainer/InteractiveShell），那些 fork 出来的进程一旦继承了
    // master，我们的 read() 就永远等不到 EIO。open 时带上标志才能避开
    // "open 完到 fcntl 之间被别人 fork 走"的竞态。
    // O_NOCTTY：本进程正常不是会话首进程，理论上不会因此获得控制终端，
    // 但这是零成本的卫生习惯。
    const int ptm = posix_openpt(O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (ptm < 0) return kErrOpenPtmx;

    // bionic 里 grantpt 就是 `return 0;`（libc/bionic/pty.cpp），调它纯粹是
    // 可移植性习惯；返回值没有信息量，真出问题会在 unlockpt 上暴露。
    (void)grantpt(ptm);

    if (unlockpt(ptm) != 0) {
        close(ptm);
        return kErrUnlockpt;
    }

    // ptsname_r 而不是 ptsname：后者返回静态缓冲，多会话并发时互相踩。
    // 名字在 fork **之前**算好 —— 子进程里不能做任何字符串拼接。
    char slaveName[64];
    if (ptsname_r(ptm, slaveName, sizeof(slaveName)) != 0) {
        close(ptm);
        return kErrPtsname;
    }

    // ---- termios：只动两个 flag，其余一律别碰 ----
    // 绝对**不要**设成 raw（不要清 ISIG/ICANON/ECHO）。常见误解：raw 是
    // "真实终端的模拟器"该做的事。这里 Compose UI 就是终端，pty 必须保持
    // cooked + ISIG，行规程才会替我们把 0x03 变成给前台进程组的 SIGINT。
    // vim/less/fzf 想要 raw 会自己对 fd 0 调 tcsetattr，那是它的自由。
    struct termios tio;
    if (tcgetattr(ptm, &tio) == 0) {
        // 行规程按 UTF-8 处理退格，删中文/emoji 才对
        tio.c_iflag |= static_cast<tcflag_t>(IUTF8);
        // 关软件流控：^S 会冻住终端，而用户完全不知道怎么解开
        tio.c_iflag &= ~static_cast<tcflag_t>(IXON | IXOFF);
        // TCSANOW（TCSETS）而不是 TCSAFLUSH（TCSETSF）：TCSETSF 不在 API 26
        // 时代的 unpriv_tty_ioctls 白名单里，对 slave 用会被 SELinux 拒。
        // master 侧虽无限制，统一用 TCSANOW 省心。master/slave 共享同一份
        // termios，对 master 设等于对 pair 设。
        tcsetattr(ptm, TCSANOW, &tio);  // 失败不致命
    }

    // 尺寸必须在 fork 前设好，否则子进程一出生看到 0×0，
    // ncurses/vim 会按 80×24 兜底然后整屏画错。
    struct winsize ws;
    ws.ws_row = clampU16(rows);
    ws.ws_col = clampU16(cols);
    ws.ws_xpixel = pixelExtent(cols, cellW);
    ws.ws_ypixel = pixelExtent(rows, cellH);
    ioctl(ptm, TIOCSWINSZ, &ws);  // 失败不致命

    ChildCtx ctx;
    ctx.execPath = finalPath;
    ctx.cwd = cwd;
    ctx.argv = argv;
    ctx.envp = envp;
    ctx.slaveName = slaveName;
    ctx.ptm = ptm;

    // 到这里为止，子进程要用的东西全部物化完毕：两个 char* 数组、
    // 三个 char 缓冲、一个 int。fork 之后不再有任何分配。
    const pid_t pid = fork();
    if (pid < 0) {
        close(ptm);
        return kErrFork;  // EAGAIN = 进程数到顶，Kotlin 侧可以重试一次
    }
    if (pid == 0) {
        runChild(ctx);  // [[noreturn]]
    }

    *outPid = pid;
    return ptm;  // fd 的所有权交给 Kotlin（ParcelFileDescriptor.adoptFd）
}

// =====================================================================
// setWinSize / waitFor / closeFd / selfTest
// =====================================================================
int32_t setWinSize(int fd, int32_t rows, int32_t cols, int32_t cellW, int32_t cellH) noexcept {
    if (fd < 0 || rows <= 0 || cols <= 0) return -EINVAL;

    struct winsize ws;
    ws.ws_row = clampU16(rows);
    ws.ws_col = clampU16(cols);
    ws.ws_xpixel = pixelExtent(cols, cellW);
    ws.ws_ypixel = pixelExtent(rows, cellH);

    // 对 master 调。SIGWINCH 由内核在尺寸真的变化时自动发给 tty->pgrp，
    // 我们不需要（也不应该）手动 kill —— 手动发会漏掉前台进程组的变化。
    if (ioctl(fd, TIOCSWINSZ, &ws) != 0) {
        const int e = errno;
        return e > 0 ? -e : -EINVAL;
    }
    return 0;
}

int32_t waitFor(pid_t pid, bool blocking) noexcept {
    if (pid <= 0) return kWaitFailed;

    for (;;) {
        int status = 0;
        const pid_t r = waitpid(pid, &status, blocking ? 0 : WNOHANG);
        if (r == 0) return kWaitRunning;  // 只可能出现在 WNOHANG 下
        if (r < 0) {
            if (errno == EINTR) continue;
            // ECHILD：已经被回收过，或者根本不是我们的子进程。
            // 注意 libcore 的 ProcessBuilder 用的是**定向** waitpid(pid)
            // （UNIXProcess_md.c:330），不是 waitpid(-1)，所以两条路
            // 不会互相偷状态 —— 这是 PTY 与 ProcessBuilder 长期共存的硬前提。
            return kWaitFailed;
        }
        if (WIFEXITED(status)) return WEXITSTATUS(status) & 0xFF;
        if (WIFSIGNALED(status)) return -WTERMSIG(status);
        // 没传 WUNTRACED/WCONTINUED，理论上到不了这里；真到了就当没结束。
        if (blocking) continue;
        return kWaitRunning;
    }
}

int32_t closeFd(int fd) noexcept {
    if (fd < 0) return -EBADF;
    if (close(fd) != 0) {
        const int e = errno;
        // EINTR 绝不重试：Linux 上 close() 无论返回什么，fd 都已经被释放，
        // 重试等于关掉别的线程刚 open 的同号 fd —— 典型的"随机文件损坏"。
        return e > 0 ? -e : -EBADF;
    }
    return 0;
}

int32_t selfTest() noexcept {
    const int ptm = posix_openpt(O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (ptm < 0) return kErrOpenPtmx;

    (void)grantpt(ptm);

    if (unlockpt(ptm) != 0) {  // TIOCSPTLCK
        close(ptm);
        return kErrUnlockpt;
    }

    char name[64];
    if (ptsname_r(ptm, name, sizeof(name)) != 0) {  // TIOCGPTN
        close(ptm);
        return kErrPtsname;
    }

    // O_NOCTTY：自检跑在 app 的普通线程上，绝不能顺手把 slave 变成本进程的
    // 控制终端。也不调 TIOCSCTTY（非会话首进程调它必然 EPERM）。
    const int pts = open(name, O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (pts < 0) {
        close(ptm);
        return kErrOpenPts;
    }

    struct winsize ws;
    ws.ws_row = 24;
    ws.ws_col = 80;
    ws.ws_xpixel = 0;
    ws.ws_ypixel = 0;
    const bool winOk = ioctl(ptm, TIOCSWINSZ, &ws) == 0;

    close(pts);
    close(ptm);
    return winOk ? 0 : kErrWinSize;
}

}  // namespace pty
}  // namespace biji

// =====================================================================
// JNI 边界
//
// 约定（jni_common.h）：每个入口第一行 BIJI_JNI_ENTER；不抛异常给 JVM，
// 失败一律返回负错误码；第二个参数是 jobject 不是 jclass —— Kotlin
// `object` 里的 external fun 编译成**实例**方法。
// =====================================================================
extern "C" JNIEXPORT jint JNICALL Java_com_biji_notes_nativebridge_NativePty_nCreateSubprocess(
        JNIEnv* env, jobject /*thiz*/, jstring cmd, jstring cwd, jobjectArray argv,
        jobjectArray envp, jint rows, jint cols, jint cellWidthPx, jint cellHeightPx,
        jintArray outPid) {
    BIJI_JNI_ENTER(env, biji::pty::kErrBadArgs);
    BIJI_JNI_REQUIRE(cmd != nullptr && argv != nullptr && envp != nullptr && outPid != nullptr,
                     biji::pty::kErrBadArgs);
    BIJI_JNI_REQUIRE(rows > 0 && cols > 0, biji::pty::kErrBadArgs);
    BIJI_JNI_REQUIRE(env->GetArrayLength(outPid) >= 1, biji::pty::kErrBadArgs);

    biji::pty::ScopedUtf8 cmdU(env, cmd);
    if (!cmdU.ok()) return cmdU.err();

    // cwd 允许为 null（= 不 chdir）。
    biji::pty::ScopedUtf8 cwdU(env, cwd);
    const char* cwdPtr = (cwd != nullptr && cwdU.ok()) ? cwdU.c_str() : nullptr;
    if (cwd != nullptr && !cwdU.ok()) return cwdU.err();

    biji::pty::ScopedExecArgs argvA(env, argv);
    if (!argvA.ok()) return argvA.err();
    // execve 要求 argv[0] 存在。空数组多半是调用方漏了 argv[0]，
    // 与其 exec 出一个没有 argv[0] 的进程（很多程序会直接段错误），不如报错。
    BIJI_JNI_REQUIRE(argvA.count() >= 1, biji::pty::kErrBadArgs);

    biji::pty::ScopedExecArgs envpA(env, envp);
    if (!envpA.ok()) return envpA.err();

    pid_t pid = -1;
    const int32_t r = biji::pty::createSubprocess(cmdU.c_str(), cwdPtr, argvA.argv(),
                                                  envpA.argv(), rows, cols, cellWidthPx,
                                                  cellHeightPx, &pid);
    if (r < 0) return r;

    // 走到这里子进程已经起来了。如果 outPid 写不进去（理论上不可能，长度
    // 前面查过），把 fd 关掉让子进程收 SIGHUP —— 否则 pid 泄漏，谁也收不了它。
    const jint pidOut = static_cast<jint>(pid);
    env->SetIntArrayRegion(outPid, 0, 1, &pidOut);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        biji::pty::closeFd(r);
        return biji::pty::kErrBadArgs;
    }
    return r;
}

extern "C" JNIEXPORT jint JNICALL Java_com_biji_notes_nativebridge_NativePty_nSetWinSize(
        JNIEnv* env, jobject /*thiz*/, jint fd, jint rows, jint cols, jint cellWidthPx,
        jint cellHeightPx) {
    BIJI_JNI_ENTER(env, -EINVAL);
    return biji::pty::setWinSize(fd, rows, cols, cellWidthPx, cellHeightPx);
}

extern "C" JNIEXPORT jint JNICALL Java_com_biji_notes_nativebridge_NativePty_nWaitFor(
        JNIEnv* env, jobject /*thiz*/, jint pid) {
    BIJI_JNI_ENTER(env, biji::pty::kWaitFailed);
    // 阻塞版。Kotlin 侧必须在**裸 Thread**上调 —— 它会长期挂在 syscall 上，
    // 放进 Dispatchers.IO 会占死线程池且无法被协程取消打断。
    return biji::pty::waitFor(static_cast<pid_t>(pid), true);
}

extern "C" JNIEXPORT jint JNICALL Java_com_biji_notes_nativebridge_NativePty_nWaitNoHang(
        JNIEnv* env, jobject /*thiz*/, jint pid) {
    BIJI_JNI_ENTER(env, biji::pty::kWaitFailed);
    return biji::pty::waitFor(static_cast<pid_t>(pid), false);
}

extern "C" JNIEXPORT jint JNICALL Java_com_biji_notes_nativebridge_NativePty_nClose(
        JNIEnv* env, jobject /*thiz*/, jint fd) {
    BIJI_JNI_ENTER(env, -EBADF);
    return biji::pty::closeFd(fd);
}

extern "C" JNIEXPORT jint JNICALL Java_com_biji_notes_nativebridge_NativePty_nSelfTest(
        JNIEnv* env, jobject /*thiz*/) {
    BIJI_JNI_ENTER(env, biji::pty::kErrOpenPtmx);
    return biji::pty::selfTest();
}
