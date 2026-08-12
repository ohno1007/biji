// =====================================================================
// jni_common.h —— biji native 层的共享 JNI 基础设施
//
// 设计约束（全项目通用，改这个文件前先读一遍）：
//
//  1. UTF-16 直通。Kotlin String / Compose AnnotatedString 的下标都是 UTF-16
//     code unit，所以 native 侧从取字符串到返回 offset 全程只认 jchar。
//     **禁止** GetStringUTFChars / NewStringUTF —— 那是 Modified UTF-8，
//     中文注释和 emoji 会让所有 offset 整体错位，而且是静默错位。
//  2. 自包含。只用 C++17 标准库，无第三方依赖。
//  3. 无状态。这里没有任何可变全局变量；所有函数是纯函数，所有 RAII 类的
//     状态都在栈上。可以被任意多个协程线程并发调用。
//  4. 不抛异常给 JVM。编译期已开 -fno-exceptions/-fno-rtti，C++ 层面抛不出来；
//     真正要防的是 **JNI pending exception 泄漏到 Kotlin**——那会让下一次
//     JNI 调用直接崩。ExceptionGuard 在每条返回路径上兜底清理。
//  5. -fno-exceptions 下 `new`/std::vector 分配失败是 abort 而不是抛异常。
//     所以：长度上限检查必须先于分配，且本文件一律用 `new (std::nothrow)`，
//     拿到 nullptr 就降级返回，绝不 abort。
// =====================================================================
#ifndef BIJI_JNI_COMMON_H_
#define BIJI_JNI_COMMON_H_

#include <jni.h>

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace biji {
namespace jni {

// 这两条不成立的话，本文件所有 reinterpret_cast 都是未定义行为。
static_assert(sizeof(jchar) == sizeof(char16_t), "jchar must be a UTF-16 code unit");
static_assert(sizeof(jint) == sizeof(int32_t), "jint must be 32-bit");

/// 默认输入上限（UTF-16 code unit）。超过就降级给 Kotlin，别指望 catch OOM。
/// 各模块可以按自己的复杂度传更小的值（diff 是 O(n·m)，上限要远低于这个）。
constexpr jsize kDefaultMaxUnits = 2 * 1000 * 1000;

/// 输入超过上限时的策略。
enum class Overflow {
    kReject,    ///< 直接失败 → ok() == false → 上层返回 null → Kotlin 兜底。默认。
    kTruncate,  ///< 只取前 maxUnits 个 code unit，并置 truncated() = true。
};

// ---------------------------------------------------------------------
// ExceptionGuard —— 「不抛异常给 JVM」的兜底闸门
//
// 析构时如果发现有 pending exception 就清掉。因为是析构函数，**每一条**
// return 路径（包括早退）都会走到，不会像手写 ExceptionClear() 那样漏。
// 注意它不处理 C++ 异常（-fno-exceptions，根本没有），只处理 JNI 的
// pending exception 状态。
// ---------------------------------------------------------------------
class ExceptionGuard {
public:
    explicit ExceptionGuard(JNIEnv* env) noexcept : env_(env) {}

    ~ExceptionGuard() {
        if (env_ != nullptr && env_->ExceptionCheck()) {
            // 不 ExceptionDescribe()：那会往 logcat 刷栈，而这里的「异常」
            // 绝大多数是我们主动容忍的 OOM/越界，属于正常降级路径。
            env_->ExceptionClear();
        }
    }

    ExceptionGuard(const ExceptionGuard&) = delete;
    ExceptionGuard& operator=(const ExceptionGuard&) = delete;

private:
    JNIEnv* env_;
};

// ---------------------------------------------------------------------
// ScopedUtf16 —— 取 UTF-16 字符串的**默认**方式（GetStringLength + GetStringRegion）
//
// 为什么不是 GetStringCritical：本项目 minSdk = 26 = Android 8.0，而 Android 8
// 恰好做了两件事——String 内部对纯 ASCII 改用 8 bit/char 存储，以及换成移动式 GC。
// 结果是 ART 基本无法再交出一个不拷贝的 jchar*，**GetStringCritical 照样拷贝**，
// 却额外付出「临界区内禁止任何其它 JNI 调用、禁止阻塞、挂起 GC」的代价。
// 代码块/Markdown 正是 ASCII 主导的输入，最坏情况全中；再叠加本项目
// 「多个协程并发调用」的前提，多个线程同时进临界区只会放大 GC 停顿。
// GetStringRegion 一次调用完成、不 pin、不阻 GC，产出的同样是 UTF-16 code unit，
// offset 口径与 GetStringCritical 完全一致。（NDK JNI tips 官方建议）
//
// 短输入走栈缓冲，零堆分配；长输入才 new (std::nothrow)。
//
// 用法：
//     ScopedUtf16 in(env, src, kMyMax);
//     if (!in.ok()) return nullptr;
//     doWork(in.u16(), in.size());
//
// **UTF-16 串没有 NUL 结尾，且允许内嵌 U+0000**，长度必须始终跟着指针一起传，
// 任何 strlen/wcslen 语义在这里都是错的。
// ---------------------------------------------------------------------
class ScopedUtf16 {
public:
    /// @param maxUnits 允许的最大 code unit 数；@param policy 超限时的行为。
    ScopedUtf16(JNIEnv* env, jstring s, jsize maxUnits = kDefaultMaxUnits,
                Overflow policy = Overflow::kReject) noexcept;

    ~ScopedUtf16();

    ScopedUtf16(const ScopedUtf16&) = delete;
    ScopedUtf16& operator=(const ScopedUtf16&) = delete;

    /// false = 取字符串失败（null 入参 / 超限且策略为 kReject / 分配失败 /
    /// JNI 越界）。调用方应当直接返回 null 让 Kotlin 侧降级。
    bool ok() const noexcept { return ok_; }

    /// 是否因为超过 maxUnits 而被截断（仅 kTruncate 策略下可能为 true）。
    /// 截断点保证不落在代理对中间，见 .cpp 里的实现说明。
    bool truncated() const noexcept { return truncated_; }

    const jchar* data() const noexcept { return data_; }

    /// 给按 char16_t 写的算法用；与 data() 是同一块内存。
    const char16_t* u16() const noexcept { return reinterpret_cast<const char16_t*>(data_); }

    /// 给按 uint16_t 写的算法用（现有几个模块是这个口径）。
    const uint16_t* u16raw() const noexcept { return reinterpret_cast<const uint16_t*>(data_); }

    /// UTF-16 code unit 数量 —— **不是**字节数，也不是 code point 数。
    jsize size() const noexcept { return len_; }

    bool empty() const noexcept { return len_ == 0; }

private:
    // 256 unit = 512 字节栈缓冲。覆盖编辑器里绝大多数短代码块和行内公式。
    static constexpr jsize kStackUnits = 256;

    jchar stack_[kStackUnits];
    jchar* heap_ = nullptr;   // 仅当 len_ > kStackUnits 时非空
    jchar* data_ = nullptr;   // 指向 stack_ 或 heap_；空串时指向 stack_
    jsize len_ = 0;
    bool ok_ = false;
    bool truncated_ = false;
};

// ---------------------------------------------------------------------
// ScopedCriticalUtf16 —— GetStringCritical 的 RAII 包装
//
// **默认不要用这个，用 ScopedUtf16。** 保留它是为了：真在真机 profile 出
// GetStringRegion 的拷贝成为瓶颈时，有一个已经写好、Release 绝不会漏的版本
// 可以直接换上去做 A/B。
//
// 临界区内的硬性限制（违反 = 死锁或未定义行为）：
//   * 禁止调用**任何**其它 JNI 函数（包括 NewIntArray / ExceptionCheck）；
//   * 禁止任何可能阻塞的操作（锁、IO、sleep）；
//   * 期间 GC 被挂起，作用域要尽可能短。
// 所以正确写法是：进临界区 → memcpy 出来或就地算完 → 立刻出临界区 →
// 再去构造返回值。绝不要在临界区里 NewIntArray。
// ---------------------------------------------------------------------
class ScopedCriticalUtf16 {
public:
    ScopedCriticalUtf16(JNIEnv* env, jstring s) noexcept;
    ~ScopedCriticalUtf16();

    ScopedCriticalUtf16(const ScopedCriticalUtf16&) = delete;
    ScopedCriticalUtf16& operator=(const ScopedCriticalUtf16&) = delete;

    bool ok() const noexcept { return chars_ != nullptr; }
    const jchar* data() const noexcept { return chars_; }
    const char16_t* u16() const noexcept { return reinterpret_cast<const char16_t*>(chars_); }
    jsize size() const noexcept { return len_; }

private:
    JNIEnv* env_;
    jstring str_;
    const jchar* chars_ = nullptr;
    jsize len_ = 0;
};

// ---------------------------------------------------------------------
// 返回值构造 helper
//
// 全部遵循「失败就清异常 + 返回 nullptr」，绝不把 pending exception 留给 JVM。
// 契约：native 只返回 offset 数组，字符串留在 Kotlin 侧 substring —— 这样
// 既避开 NewStringUTF，又让屏幕外的内容永远不产生 String 对象。
// ---------------------------------------------------------------------

/// 一次调用批量返回（Telegram 的 jintArray 出参手法）。n == 0 返回长度为 0 的
/// 合法数组（**不是** nullptr）——上层据此区分「跑过了但没结果」和「降级」。
jintArray newIntArray(JNIEnv* env, const jint* data, size_t n) noexcept;

inline jintArray newIntArray(JNIEnv* env, const std::vector<int32_t>& v) noexcept {
    return newIntArray(env, reinterpret_cast<const jint*>(v.data()), v.size());
}

/// 用 NewString（UTF-16）而**不是** NewStringUTF 构造 Java String。
/// data 允许为 nullptr 当且仅当 len == 0（内部换成静态空缓冲，避免 CheckJNI 报违规）。
jstring newUtf16String(JNIEnv* env, const jchar* data, jsize len) noexcept;

inline jstring newUtf16String(JNIEnv* env, const std::u16string& s) noexcept {
    return newUtf16String(env, reinterpret_cast<const jchar*>(s.data()),
                          static_cast<jsize>(s.size()));
}

/// 构造 String[]。
///
/// **能不用就不用**：它需要一次 FindClass("java/lang/String")，是本文件里唯一
/// 需要认识 Java 类型的地方；而且每个元素都要 NewString + SetObjectArrayElement。
/// 只有确实要返回一组文本时才用（例如代码块的 lang 列表），offset 能表达的
/// 一律用 newIntArray。
///
/// 内部会逐个 DeleteLocalRef：JNI 局部引用表默认只有 512 项，元素一多不删必爆
/// （"local reference table overflow"），这是 jobjectArray 最常见的翻车点。
jobjectArray newStringArray(JNIEnv* env, const std::u16string* items, size_t n) noexcept;

inline jobjectArray newStringArray(JNIEnv* env, const std::vector<std::u16string>& v) noexcept {
    return newStringArray(env, v.data(), v.size());
}

}  // namespace jni
}  // namespace biji

// ---------------------------------------------------------------------
// 边界包装宏
//
// 每个 extern "C" JNIEXPORT 函数的**第一行**应当是 BIJI_JNI_ENTER，它做两件事：
//   1. env 为 null 直接返回 fallback（理论上不会发生，但 0 成本）；
//   2. 装上 ExceptionGuard —— 之后无论从哪条路径 return，pending exception
//      都会被清掉。这就是约束「不抛异常给 JVM」的机械保证，不依赖人记得写。
//
// 用法：
//     extern "C" JNIEXPORT jintArray JNICALL
//     Java_com_biji_notes_nativebridge_NativeText_nFoo(JNIEnv* env, jobject, jstring s) {
//         BIJI_JNI_ENTER(env, nullptr);
//         BIJI_JNI_REQUIRE(s != nullptr, nullptr);
//         ...
//     }
//
// 注意第二个参数：Kotlin `object` 里的 `external fun` 编译成**实例**方法，
// JNI 传进来的是 INSTANCE，类型是 jobject 而不是 jclass。写成 jclass 也能跑
// （ABI 上都是指针），但按实际情况写省下一个人的困惑。
// ---------------------------------------------------------------------

/// 装上异常闸门并校验 env。必须跟分号。
#define BIJI_JNI_ENTER(env, fallback)            \
    if ((env) == nullptr) return (fallback);     \
    ::biji::jni::ExceptionGuard _biji_jni_guard((env))

/// 前置条件不满足就降级返回，绝不抛。
#define BIJI_JNI_REQUIRE(cond, fallback) \
    do {                                 \
        if (!(cond)) return (fallback);  \
    } while (0)

#endif  // BIJI_JNI_COMMON_H_
