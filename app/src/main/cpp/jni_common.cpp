// =====================================================================
// jni_common.cpp —— 见 jni_common.h 的设计约束说明。
//
// 本文件不导出任何 JNI 入口（没有 Java_* 符号），只提供给同一个 .so 内的
// 其它模块链接。因此不需要 JNIEXPORT —— -fvisibility=hidden 把它们藏在
// 库内部正是我们要的效果。
// =====================================================================
#include "jni_common.h"

#include <cstdint>
#include <new>  // std::nothrow

namespace biji {
namespace jni {

namespace {

// NewString 不接受 (nullptr, 0)：CheckJNI 打开时会直接判违规并 abort。
// 空串统一指到这里。
const jchar kEmptyChars[1] = {0};

// 高代理：U+D800..U+DBFF。截断时如果最后一个 unit 是高代理，说明它的低代理
// 被切掉了，必须退一格，否则会产出一个孤立代理 —— 后续 NewString 出来的
// Java String 里就是个坏字符，而且 offset 全都还「看起来正确」，属于最难查的
// 那类静默错误。
inline bool isHighSurrogate(jchar c) noexcept {
    return c >= 0xD800 && c <= 0xDBFF;
}

// size_t → jsize 的安全上限检查（jsize 是 int32）。
inline bool fitsJsize(size_t n) noexcept {
    return n <= static_cast<size_t>(INT32_MAX);
}

}  // namespace

// ---------------------------------------------------------------------
// ScopedUtf16
// ---------------------------------------------------------------------
ScopedUtf16::ScopedUtf16(JNIEnv* env, jstring s, jsize maxUnits, Overflow policy) noexcept {
    // data_ 先指向栈缓冲：这样即使构造失败，data() 也不是野指针。配合
    // len_ == 0，误用时最多读到 0 个字符，不会越界。
    data_ = stack_;

    if (env == nullptr || s == nullptr || maxUnits < 0) return;

    const jsize total = env->GetStringLength(s);  // UTF-16 code unit 数
    if (total < 0) return;

    jsize take = total;
    if (take > maxUnits) {
        // kReject：直接判失败，让上层返回 null 给 Kotlin 兜底。对 O(n·m) 的
        // 算法（diff）这是唯一安全的选择 —— 截断会给出静默错误的结果。
        if (policy == Overflow::kReject) return;
        take = maxUnits;
        truncated_ = true;
    }

    // -fno-exceptions：分配失败会 abort 而不是抛异常，所以上限检查必须在
    // 分配之前，并且用 nothrow new 把 OOM 变成一次普通的降级。
    if (take > kStackUnits) {
        heap_ = new (std::nothrow) jchar[static_cast<size_t>(take)];
        if (heap_ == nullptr) return;  // OOM 也只降级，不 abort、不抛
        data_ = heap_;
    }

    if (take > 0) {
        env->GetStringRegion(s, 0, take, data_);
        if (env->ExceptionCheck()) {  // 越界 / OOM —— 不外泄异常
            env->ExceptionClear();
            return;
        }
        // 截断点绝不落在代理对中间（见上面 isHighSurrogate 的说明）。
        if (truncated_ && isHighSurrogate(data_[take - 1])) {
            --take;
        }
    }

    len_ = take;
    ok_ = true;
}

ScopedUtf16::~ScopedUtf16() {
    delete[] heap_;  // heap_ 为 nullptr 时 delete[] 是合法 no-op
}

// ---------------------------------------------------------------------
// ScopedCriticalUtf16
// ---------------------------------------------------------------------
ScopedCriticalUtf16::ScopedCriticalUtf16(JNIEnv* env, jstring s) noexcept
    : env_(env), str_(s) {
    if (env == nullptr || s == nullptr) return;

    // GetStringLength 必须在进临界区**之前**调用 —— 临界区内禁止任何其它
    // JNI 调用。同理，长度不能来自 Kotlin 侧传进来的数字：UTF-16 没有 NUL
    // 结尾，只有 GetStringLength 说了算。
    const jsize n = env->GetStringLength(s);
    if (n < 0) return;

    chars_ = static_cast<const jchar*>(env->GetStringCritical(s, nullptr));
    if (chars_ == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return;
    }
    len_ = n;
}

ScopedCriticalUtf16::~ScopedCriticalUtf16() {
    // RAII 的全部意义：无论调用方从哪条路径提前 return，Release 一定发生。
    // 漏掉它 = String 被永久 pin 住 + GC 无法移动对象。
    if (chars_ != nullptr && env_ != nullptr && str_ != nullptr) {
        env_->ReleaseStringCritical(str_, chars_);
        chars_ = nullptr;
    }
}

// ---------------------------------------------------------------------
// 返回值构造
// ---------------------------------------------------------------------
jintArray newIntArray(JNIEnv* env, const jint* data, size_t n) noexcept {
    if (env == nullptr || !fitsJsize(n)) return nullptr;

    jintArray arr = env->NewIntArray(static_cast<jsize>(n));
    if (arr == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();  // OOM 只降级
        return nullptr;
    }
    if (n > 0 && data != nullptr) {
        env->SetIntArrayRegion(arr, 0, static_cast<jsize>(n), data);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            env->DeleteLocalRef(arr);
            return nullptr;
        }
    }
    return arr;
}

jstring newUtf16String(JNIEnv* env, const jchar* data, jsize len) noexcept {
    if (env == nullptr || len < 0) return nullptr;
    // 指针为空却声称有长度是调用方的 bug；这里失败返回而不是猜一个空串，
    // 否则会变成「渲染出空内容但没人报错」的静默错误。
    if (data == nullptr && len > 0) return nullptr;

    // NewString 而不是 NewStringUTF：后者要 Modified UTF-8，中文/emoji 会错，
    // 而且和本项目的 UTF-16 offset 口径对不上。
    const jchar* src = (len == 0) ? kEmptyChars : data;
    jstring js = env->NewString(src, len);
    if (js == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return nullptr;
    }
    return js;
}

jobjectArray newStringArray(JNIEnv* env, const std::u16string* items, size_t n) noexcept {
    if (env == nullptr || !fitsJsize(n)) return nullptr;
    if (n > 0 && items == nullptr) return nullptr;

    // 唯一需要认识 Java 类型的地方。用局部引用、当场删掉，不缓存成全局引用
    // —— 缓存就需要 JNI_OnLoad 和一个可变全局，正是约束 3 要避免的东西。
    // FindClass 在 ART 里是一次哈希查找，相对于一次全文扫描可以忽略。
    jclass stringCls = env->FindClass("java/lang/String");
    if (stringCls == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return nullptr;
    }

    jobjectArray arr = env->NewObjectArray(static_cast<jsize>(n), stringCls, nullptr);
    env->DeleteLocalRef(stringCls);
    if (arr == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return nullptr;
    }

    for (size_t i = 0; i < n; ++i) {
        jstring js = newUtf16String(env, items[i]);
        if (js == nullptr) {
            env->DeleteLocalRef(arr);
            return nullptr;
        }
        env->SetObjectArrayElement(arr, static_cast<jsize>(i), js);
        // 必须逐个删：JNI 局部引用表默认只有 512 项，元素多了不删就是
        // "local reference table overflow" 直接 abort。这是 jobjectArray
        // 最常见的翻车点，也是我们尽量只返 jintArray 的原因之一。
        env->DeleteLocalRef(js);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            env->DeleteLocalRef(arr);
            return nullptr;
        }
    }
    return arr;
}

}  // namespace jni
}  // namespace biji
