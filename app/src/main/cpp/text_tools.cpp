// text_tools.cpp — 见 text_tools.h 的契约说明。
//
// 三个互不相干的纯函数放在同一个 .cpp 里，是为了共享同一组 UTF-16 基础
// 判定（isKtWs / isAsciiAlpha …）并只暴露一个 JNI 类。三者之间没有任何
// 共享的可变状态。
//
// 编译期已开 -fno-exceptions / -fno-rtti / -fvisibility=hidden：
//  - 分配失败 = abort，不是抛异常 → 所有长度上限必须在分配之前检查；
//  - 不能 dynamic_cast / typeid → 分派一律用 switch / 表；
//  - JNI 导出必须显式写 JNIEXPORT，否则符号被 hidden 掉 → UnsatisfiedLinkError。

#include "text_tools.h"

#include <cstring>

namespace biji {
namespace tt {
namespace {

// =====================================================================
// 共用的 UTF-16 基础判定
// 全部只对 ASCII / BMP 非代理区生效 —— 代理对因此永远被当作普通字符。
// =====================================================================

// Kotlin: Char.isWhitespace() = Character.isWhitespace() || Character.isSpaceChar()
//   并集 = 0x09..0x0D, 0x1C..0x1F, 0x20, 0xA0, 0x1680, 0x2000..0x200A,
//          0x2028, 0x2029, 0x202F, 0x205F, 0x3000
// 这些全部 < 0xD800，不会误切代理对。
inline bool isKtWs(uint16_t c) {
    if (c == 0x20) return true;
    if (c >= 0x09 && c <= 0x0D) return true;
    if (c >= 0x1C && c <= 0x1F) return true;
    if (c < 0x80) return false;
    if (c >= 0x2000 && c <= 0x200A) return true;
    switch (c) {
        case 0x00A0: case 0x1680: case 0x2028:
        case 0x2029: case 0x202F: case 0x205F: case 0x3000:
            return true;
        default:
            return false;
    }
}

// 正则 [A-Za-z]（未开 UNICODE 标志，所以只有 ASCII）
inline bool isAsciiAlpha(uint16_t c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
}

// =====================================================================
// 1. 行级 diff —— Myers O(ND) 线性空间分治
// =====================================================================

// 行表：只记 offset，从不复制字符串。
struct Lines {
    std::vector<int32_t> start;  // 含
    std::vector<int32_t> end;    // 不含，且不含行尾的 '\n'
    bool truncated = false;
};

// 与 Kotlin `String.split("\n")` 逐行等价：只按 '\n' 切；'\r' 留在行尾；
// 末尾的 '\n' 之后再产生一个空行；空串产生一个空行。
void splitLines(const uint16_t* s, int32_t len, Lines& L) {
    int32_t cap = len;
    if (cap > kDiffMaxUnits) {
        cap = kDiffMaxUnits;
        L.truncated = true;
        // 截断点不得落在代理对中间：否则最后一行的 end offset 会指到代理对
        // 内部，调用方 substring 出来的串以孤立高代理结尾。JNI 层已经先截过
        // 一道（readUtf16），所以这条分支实际只对直接调本函数的宿主测试生效，
        // 但契约（见 text_tools.h 第 2 条）在两处都必须成立。
        if (cap > 0 && s[cap - 1] >= 0xD800 && s[cap - 1] <= 0xDBFF) --cap;
    }
    // 经验值：源码平均 30 个 code unit 一行。只是省几次 realloc。
    const int32_t guess = cap / 30 + 8;
    L.start.reserve((size_t)(guess < kDiffMaxLines ? guess : kDiffMaxLines));
    L.end.reserve((size_t)(guess < kDiffMaxLines ? guess : kDiffMaxLines));

    int32_t b = 0;
    for (int32_t i = 0; i < cap; ++i) {
        if (s[i] == '\n') {
            L.start.push_back(b);
            L.end.push_back(i);
            b = i + 1;
            if ((int32_t)L.start.size() >= kDiffMaxLines) {
                L.truncated = true;
                return;  // 行数触顶：后面整段丢弃，flags 里会标出来
            }
        }
    }
    L.start.push_back(b);
    L.end.push_back(cap);
}

// FNV-1a over UTF-16 code unit。只用来做哈希桶定位，命中后一律再比内容，
// 所以碰撞不会导致误判"两行相同"。
inline uint64_t hashLine(const uint16_t* s, int32_t b, int32_t e) {
    uint64_t h = 1469598103934665603ULL;
    for (int32_t i = b; i < e; ++i) {
        h ^= (uint64_t)s[i];
        h *= 1099511628211ULL;
    }
    return h;
}

// 行内容 → 稠密 int id。之后 Myers 的内层循环只比 int，
// 不再有 String.equals —— 这是把 O(n·m) 常数压下去的关键之一。
struct Interner {
    struct Key {
        const uint16_t* p;
        int32_t len;
        uint64_t h;
    };
    std::vector<int32_t> slots;  // -1 = 空，否则是 keys 下标
    std::vector<Key> keys;
    uint32_t mask = 0;

    void init(size_t expected) {
        size_t cap = 16;
        while (cap < expected * 2) cap <<= 1;  // 负载因子 <= 50%
        slots.assign(cap, -1);
        mask = (uint32_t)(cap - 1);
        keys.reserve(expected);
    }

    int32_t intern(const uint16_t* p, int32_t len, uint64_t h) {
        uint32_t i = (uint32_t)h & mask;
        for (;;) {
            const int32_t k = slots[i];
            if (k < 0) {
                slots[i] = (int32_t)keys.size();
                keys.push_back(Key{p, len, h});
                return (int32_t)keys.size() - 1;
            }
            const Key& kk = keys[(size_t)k];
            if (kk.h == h && kk.len == len &&
                (len == 0 || std::memcmp(kk.p, p, (size_t)len * sizeof(uint16_t)) == 0)) {
                return k;
            }
            i = (i + 1) & mask;  // 线性探测：缓存友好
        }
    }
};

void buildIds(const uint16_t* s, const Lines& L, Interner& in, std::vector<int32_t>& ids) {
    const size_t n = L.start.size();
    ids.resize(n);
    for (size_t i = 0; i < n; ++i) {
        const int32_t b = L.start[i];
        const int32_t e = L.end[i];
        ids[i] = in.intern(s + b, e - b, hashLine(s, b, e));
    }
}

struct DiffState {
    const int32_t* A = nullptr;  // before 的行 id
    const int32_t* B = nullptr;  // after  的行 id
    const Lines* la = nullptr;
    const Lines* lb = nullptr;
    std::vector<int32_t>* out = nullptr;
    std::vector<int32_t> v1;  // Myers 前向 V（分治各层共用：切分点算完就不再用）
    std::vector<int32_t> v2;  // Myers 反向 V
    int64_t budget = kDiffWorkBudget;
    int32_t flags = kDiffFlagNone;
    int32_t outLimit = 0;
    bool capped = false;
};

inline void emitEntry(DiffState& st, int32_t op, int32_t s, int32_t e) {
    if ((int32_t)st.out->size() + 3 > st.outLimit) {
        st.capped = true;
        st.flags |= kDiffFlagOutputCapped;
        return;
    }
    st.out->push_back(op);
    st.out->push_back(s);
    st.out->push_back(e);
}

inline void emitRange(DiffState& st, int32_t op, const Lines& L, int32_t from, int32_t to) {
    for (int32_t i = from; i < to; ++i) {
        emitEntry(st, op, L.start[(size_t)i], L.end[(size_t)i]);
        if (st.capped) return;
    }
}

// 整数开方（牛顿迭代）。只用来把"还能花多少预算"换算成"还能搜多深"。
inline int64_t isqrt64(int64_t v) {
    if (v <= 0) return 0;
    int64_t x = v;
    int64_t y = (x + 1) / 2;
    while (y < x) {
        x = y;
        y = (x + v / x) / 2;
    }
    return x;
}

// 切分点合法性：必须让两个子问题都严格小于父问题，否则会无限递归。
inline bool acceptSplit(DiffState& st, int32_t a0, int32_t b0, int32_t N, int32_t M,
                        int32_t x, int32_t y, int32_t& ox, int32_t& oy) {
    if ((x > 0 || y > 0) && (x < N || y < M)) {
        ox = a0 + x;
        oy = b0 + y;
        return true;
    }
    // 理论上到不了这里（进 bisect 前公共前后缀已剥净）。真到了就退化处理，
    // 绝不能把"和父问题相同"的切分点交出去。
    st.flags |= kDiffFlagApproximate;
    return false;
}

// Myers 论文的线性空间中段搜索（front/reverse 同时推进，在中点相遇）。
// 找到中段就把问题一分为二，内存 O(n+m) 而不是 O(n·m)。
// 返回 true 并给出**绝对**下标切分点 (outX, outY)；返回 false 表示
// 该子问题退化成"整段删 + 整段加"。
bool bisect(DiffState& st, int32_t a0, int32_t a1, int32_t b0, int32_t b1,
            int32_t& outX, int32_t& outY) {
    const int32_t N = a1 - a0;
    const int32_t M = b1 - b0;
    const int32_t maxD = (N + M + 1) / 2;
    int32_t limitD = maxD > kDiffMaxD ? kDiffMaxD : maxD;
    // 单次调用的花费上限 = 剩余预算的一半（但不低于 kDiffMinCallWork）。
    // 搜到深度 d 的代价约 2d²，所以上限换算回 d 要开方。
    // 没有这一道，一次 d=4096 的搜索就能把 40M 的全局预算吃掉 33M，
    // 剩下的区段全部退化成"整删整加"—— 实测 10 万行/1 万处改动时
    // 只能认出 10 行公共内容。有了它，近似模式会做几十次便宜的切分而不是
    // 一次昂贵的；而 D 不大的常见输入（代价 D²/2）根本碰不到这个上限。
    const int64_t allowance =
            st.budget / 2 > kDiffMinCallWork ? st.budget / 2 : kDiffMinCallWork;
    const int64_t affordD = isqrt64(allowance / 2);
    if ((int64_t)limitD > affordD) limitD = (int32_t)affordD;
    if (limitD < 1) limitD = 1;
    const int32_t vOff = limitD;
    const int32_t vLen = 2 * limitD + 2;

    st.v1.assign((size_t)vLen, -1);
    st.v2.assign((size_t)vLen, -1);
    st.v1[(size_t)vOff + 1] = 0;
    st.v2[(size_t)vOff + 1] = 0;

    const int32_t delta = N - M;
    // delta 为奇数时前向搜索先越过中点，偶数时反向搜索先越过。
    const bool front = (delta % 2 != 0);
    int32_t k1start = 0, k1end = 0, k2start = 0, k2end = 0;
    // 预算耗尽时的近似切分点：走得最远的那个前向点（GNU diff 的
    // too_expensive 思路）。比"整删整加"好得多，还能保证继续推进。
    int32_t bestX = 0, bestY = 0, bestScore = -1;
    bool budgetOut = false;

    for (int32_t d = 0; d < limitD; ++d) {
        if (st.budget <= 0) {
            budgetOut = true;
            break;
        }
        st.budget -= (int64_t)(4 * d + 4);

        // ---- 前向 ----
        for (int32_t k1 = -d + k1start; k1 <= d - k1end; k1 += 2) {
            const int32_t k1o = vOff + k1;
            int32_t x1;
            if (k1 == -d || (k1 != d && st.v1[(size_t)k1o - 1] < st.v1[(size_t)k1o + 1])) {
                x1 = st.v1[(size_t)k1o + 1];
            } else {
                x1 = st.v1[(size_t)k1o - 1] + 1;
            }
            int32_t y1 = x1 - k1;
            while (x1 < N && y1 < M && st.A[a0 + x1] == st.B[b0 + y1]) {
                ++x1;
                ++y1;
            }
            st.v1[(size_t)k1o] = x1;
            if (x1 <= N && y1 <= M && x1 + y1 > bestScore) {
                bestScore = x1 + y1;
                bestX = x1;
                bestY = y1;
            }
            if (x1 > N) {
                k1end += 2;  // 越过右边界，收窄 k 范围
            } else if (y1 > M) {
                k1start += 2;
            } else if (front) {
                const int32_t k2o = vOff + delta - k1;
                if (k2o >= 0 && k2o < vLen && st.v2[(size_t)k2o] != -1) {
                    const int32_t x2 = N - st.v2[(size_t)k2o];
                    if (x1 >= x2) {  // 两条路径相遇
                        return acceptSplit(st, a0, b0, N, M, x1, y1, outX, outY);
                    }
                }
            }
        }

        // ---- 反向 ----
        for (int32_t k2 = -d + k2start; k2 <= d - k2end; k2 += 2) {
            const int32_t k2o = vOff + k2;
            int32_t x2;
            if (k2 == -d || (k2 != d && st.v2[(size_t)k2o - 1] < st.v2[(size_t)k2o + 1])) {
                x2 = st.v2[(size_t)k2o + 1];
            } else {
                x2 = st.v2[(size_t)k2o - 1] + 1;
            }
            int32_t y2 = x2 - k2;
            while (x2 < N && y2 < M &&
                   st.A[a0 + N - x2 - 1] == st.B[b0 + M - y2 - 1]) {
                ++x2;
                ++y2;
            }
            st.v2[(size_t)k2o] = x2;
            if (x2 > N) {
                k2end += 2;
            } else if (y2 > M) {
                k2start += 2;
            } else if (!front) {
                const int32_t k1o = vOff + delta - k2;
                if (k1o >= 0 && k1o < vLen && st.v1[(size_t)k1o] != -1) {
                    const int32_t x1 = st.v1[(size_t)k1o];
                    const int32_t y1 = vOff + x1 - k1o;
                    if (x1 >= N - x2) {  // 两条路径相遇
                        return acceptSplit(st, a0, b0, N, M, x1, y1, outX, outY);
                    }
                }
            }
        }
    }

    if (!budgetOut && limitD == maxD) {
        // 跑满了 d 也没相遇 = 两段毫无公共内容，整删整加就是最短编辑脚本。
        // 这是精确结论，不置 approximate。
        return false;
    }
    st.flags |= kDiffFlagApproximate;
    if (bestScore > 0) {
        return acceptSplit(st, a0, b0, N, M, bestX, bestY, outX, outY);
    }
    return false;
}

enum TaskKind : int32_t { kTaskDiff = 0, kTaskEmitCommon = 1 };

struct Task {
    int32_t kind;
    int32_t a0, a1, b0, b1;
};

// 显式任务栈而不是递归：近似切分下分治深度可能上千层，
// Android 工作线程默认栈只有 1 MB，递归有溢出风险。
void runDiff(DiffState& st, int32_t na, int32_t nb) {
    std::vector<Task> stack;
    stack.reserve(64);
    stack.push_back(Task{kTaskDiff, 0, na, 0, nb});

    while (!stack.empty() && !st.capped) {
        const Task t = stack.back();
        stack.pop_back();

        if (t.kind == kTaskEmitCommon) {
            emitRange(st, kDiffCommon, *st.lb, t.b0, t.b1);
            continue;
        }

        int32_t a0 = t.a0, a1 = t.a1, b0 = t.b0, b1 = t.b1;

        // 公共前缀：就在当前输出位置上，直接吐出去。
        // 流式/小改动场景下这一步就把绝大部分行消化掉了。
        while (a0 < a1 && b0 < b1 && st.A[a0] == st.B[b0]) {
            emitEntry(st, kDiffCommon, st.lb->start[(size_t)b0], st.lb->end[(size_t)b0]);
            ++a0;
            ++b0;
        }
        if (st.capped) return;

        // 公共后缀：要等中间段输出完才轮到它，记下范围稍后补。
        const int32_t sufB = b1;
        while (a1 > a0 && b1 > b0 && st.A[a1 - 1] == st.B[b1 - 1]) {
            --a1;
            --b1;
        }

        if (a0 == a1 || b0 == b1) {  // 一侧空 = 纯删 / 纯加
            emitRange(st, kDiffRemoved, *st.la, a0, a1);
            emitRange(st, kDiffAdded, *st.lb, b0, b1);
            emitRange(st, kDiffCommon, *st.lb, b1, sufB);
            continue;
        }

        int32_t x = 0, y = 0;
        if (!bisect(st, a0, a1, b0, b1, x, y)) {
            emitRange(st, kDiffRemoved, *st.la, a0, a1);
            emitRange(st, kDiffAdded, *st.lb, b0, b1);
            emitRange(st, kDiffCommon, *st.lb, b1, sufB);
            continue;
        }

        // 出栈顺序 = 输出顺序：左半 → 右半 → 公共后缀，所以逆序压栈。
        if (b1 < sufB) stack.push_back(Task{kTaskEmitCommon, 0, 0, b1, sufB});
        stack.push_back(Task{kTaskDiff, x, a1, y, b1});
        stack.push_back(Task{kTaskDiff, a0, x, b0, y});
    }
}

// =====================================================================
// 3. LaTeX → Unicode 的表
// =====================================================================

struct Cmd {
    const char* name;  // 不含前导反斜杠，纯 ASCII
    uint16_t rep;      // 替换成的单个 BMP 字符
};

// 38 个希腊字母 + 16 个操作符，值逐条抄自 Math.kt 的 GreekMap 与
// 那串 .replace 链（用脚本从 Math.kt 提取，不是手敲）。
// 全表 54 条里存在前缀包含关系的只有 le ⊂ leftarrow —— 因此单遍最长匹配
// 与 Kotlin 的 54 次顺序 replace 在其它所有输入上等价。详见 text_tools.h。
constexpr Cmd kCommands[] = {
    // ---- 希腊字母（Math.kt GreekMap，顺序保持一致以便对照）----
    {"alpha", 0x03B1},   {"beta", 0x03B2},      {"gamma", 0x03B3},
    {"delta", 0x03B4},   {"epsilon", 0x03B5},   {"varepsilon", 0x03B5},
    {"zeta", 0x03B6},    {"eta", 0x03B7},       {"theta", 0x03B8},
    {"vartheta", 0x03D1},{"iota", 0x03B9},      {"kappa", 0x03BA},
    {"lambda", 0x03BB},  {"mu", 0x03BC},        {"nu", 0x03BD},
    {"xi", 0x03BE},      {"pi", 0x03C0},        {"varpi", 0x03D6},
    {"rho", 0x03C1},     {"sigma", 0x03C3},     {"tau", 0x03C4},
    {"upsilon", 0x03C5}, {"phi", 0x03C6},       {"varphi", 0x03C6},
    {"chi", 0x03C7},     {"psi", 0x03C8},       {"omega", 0x03C9},
    {"Gamma", 0x0393},   {"Delta", 0x0394},     {"Theta", 0x0398},
    {"Lambda", 0x039B},  {"Xi", 0x039E},        {"Pi", 0x03A0},
    {"Sigma", 0x03A3},   {"Upsilon", 0x03A5},   {"Phi", 0x03A6},
    {"Psi", 0x03A8},     {"Omega", 0x03A9},
    // ---- 操作符 ----
    {"sum", 0x2211},     {"int", 0x222B},       {"infty", 0x221E},
    {"partial", 0x2202}, {"cdot", 0x00B7},      {"times", 0x00D7},
    {"div", 0x00F7},     {"le", 0x2264},        {"ge", 0x2265},
    {"ne", 0x2260},      {"approx", 0x2248},    {"equiv", 0x2261},
    {"rightarrow", 0x2192}, {"leftarrow", 0x2190},
    {"Rightarrow", 0x21D2}, {"Leftarrow", 0x21D0},
};
constexpr int32_t kCommandCount = (int32_t)(sizeof(kCommands) / sizeof(kCommands[0]));

struct ScriptEntry {
    char from;
    uint16_t to;
};

// Math.kt 的 Superscripts（注意：刻意没有 'q'，Unicode 里没有上标 q）
constexpr ScriptEntry kSuper[] = {
    {'0', 0x2070}, {'1', 0x00B9}, {'2', 0x00B2}, {'3', 0x00B3}, {'4', 0x2074},
    {'5', 0x2075}, {'6', 0x2076}, {'7', 0x2077}, {'8', 0x2078}, {'9', 0x2079},
    {'+', 0x207A}, {'-', 0x207B}, {'=', 0x207C}, {'(', 0x207D}, {')', 0x207E},
    {'a', 0x1D43}, {'b', 0x1D47}, {'c', 0x1D9C}, {'d', 0x1D48}, {'e', 0x1D49},
    {'f', 0x1DA0}, {'g', 0x1D4D}, {'h', 0x02B0}, {'i', 0x2071}, {'j', 0x02B2},
    {'k', 0x1D4F}, {'l', 0x02E1}, {'m', 0x1D50}, {'n', 0x207F}, {'o', 0x1D52},
    {'p', 0x1D56}, {'r', 0x02B3}, {'s', 0x02E2}, {'t', 0x1D57}, {'u', 0x1D58},
    {'v', 0x1D5B}, {'w', 0x02B7}, {'x', 0x02E3}, {'y', 0x02B8}, {'z', 0x1DBB},
};

// Math.kt 的 Subscripts（缺 b c d f g q w y z —— Unicode 里没有）
constexpr ScriptEntry kSub[] = {
    {'0', 0x2080}, {'1', 0x2081}, {'2', 0x2082}, {'3', 0x2083}, {'4', 0x2084},
    {'5', 0x2085}, {'6', 0x2086}, {'7', 0x2087}, {'8', 0x2088}, {'9', 0x2089},
    {'+', 0x208A}, {'-', 0x208B}, {'=', 0x208C}, {'(', 0x208D}, {')', 0x208E},
    {'a', 0x2090}, {'e', 0x2091}, {'h', 0x2095}, {'i', 0x1D62}, {'j', 0x2C7C},
    {'k', 0x2096}, {'l', 0x2097}, {'m', 0x2098}, {'n', 0x2099}, {'o', 0x2092},
    {'p', 0x209A}, {'r', 0x1D63}, {'s', 0x209B}, {'t', 0x209C}, {'u', 0x1D64},
    {'v', 0x1D65}, {'x', 0x2093},
};

// 表都不到 40 条且只在 ^/_ 后面查一次，线性扫比二分还快（缓存行更友好）。
inline uint16_t lookupScript(const ScriptEntry* tab, int32_t n, uint16_t c) {
    if (c >= 0x80) return 0;  // 非 ASCII 一律未映射
    for (int32_t i = 0; i < n; ++i) {
        if ((uint16_t)(uint8_t)tab[i].from == c) return tab[i].to;
    }
    return 0;
}

// 正则 [0-9A-Za-z+\-=()]：^ / _ 后面不带花括号时能接的单个字符
inline bool isScriptArg(uint16_t c) {
    if ((c >= '0' && c <= '9') || isAsciiAlpha(c)) return true;
    return c == '+' || c == '-' || c == '=' || c == '(' || c == ')';
}

// 在 s[i..n) 处匹配 ASCII 模式 name。返回匹配长度，0 = 不匹配。
inline int32_t matchAscii(const uint16_t* s, int32_t i, int32_t n, const char* name) {
    int32_t k = 0;
    while (name[k] != '\0') {
        if (i + k >= n || s[i + k] != (uint16_t)(uint8_t)name[k]) return 0;
        ++k;
    }
    return k;
}

inline void appendRange(std::vector<uint16_t>& dst, const uint16_t* s, int32_t b, int32_t e) {
    for (int32_t i = b; i < e; ++i) dst.push_back(s[i]);
}

// ① 具名命令：单遍最长匹配。等价于 Kotlin 的 54 次全文 replace。
void passCommands(const std::vector<uint16_t>& src, std::vector<uint16_t>& dst) {
    const int32_t n = (int32_t)src.size();
    const uint16_t* s = src.data();
    dst.clear();
    int32_t i = 0;
    while (i < n) {
        if (s[i] == '\\' && i + 1 < n) {
            int32_t bestLen = 0;
            uint16_t bestRep = 0;
            for (int32_t c = 0; c < kCommandCount; ++c) {
                // 首字符先挡一道，绝大多数条目一次比较就出局
                if (s[i + 1] != (uint16_t)(uint8_t)kCommands[c].name[0]) continue;
                const int32_t m = matchAscii(s, i + 1, n, kCommands[c].name);
                if (m > bestLen) {
                    bestLen = m;
                    bestRep = kCommands[c].rep;
                }
            }
            if (bestLen > 0) {
                dst.push_back(bestRep);
                i += 1 + bestLen;
                continue;
            }
        }
        dst.push_back(s[i]);
        ++i;
    }
}

// 读一段 [^{}]+ ：从 from 开始吃到第一个花括号。
// 返回终止位置；*ok 表示"至少 1 个字符且终止于 '}'"（= 正则 [^{}]+\} 能匹配）。
inline int32_t scanBraceArg(const uint16_t* s, int32_t from, int32_t n, bool* ok) {
    int32_t j = from;
    while (j < n && s[j] != '{' && s[j] != '}') ++j;
    *ok = (j > from && j < n && s[j] == '}');
    return j;
}

// ② \frac{X}{Y} → "X / Y"（正则 \\frac\{([^{}]+)\}\{([^{}]+)\}）
void passFrac(const std::vector<uint16_t>& src, std::vector<uint16_t>& dst) {
    const int32_t n = (int32_t)src.size();
    const uint16_t* s = src.data();
    dst.clear();
    int32_t i = 0;
    while (i < n) {
        if (s[i] == '\\' && matchAscii(s, i, n, "\\frac{") == 6) {
            bool ok1 = false;
            const int32_t j = scanBraceArg(s, i + 6, n, &ok1);
            if (ok1 && j + 1 < n && s[j + 1] == '{') {
                bool ok2 = false;
                const int32_t k = scanBraceArg(s, j + 2, n, &ok2);
                if (ok2) {
                    appendRange(dst, s, i + 6, j);
                    dst.push_back(' ');
                    dst.push_back('/');
                    dst.push_back(' ');
                    appendRange(dst, s, j + 2, k);
                    i = k + 1;
                    continue;
                }
            }
        }
        dst.push_back(s[i]);
        ++i;
    }
}

// ③ \sqrt{X} → "√X"
void passSqrt(const std::vector<uint16_t>& src, std::vector<uint16_t>& dst) {
    const int32_t n = (int32_t)src.size();
    const uint16_t* s = src.data();
    dst.clear();
    int32_t i = 0;
    while (i < n) {
        if (s[i] == '\\' && matchAscii(s, i, n, "\\sqrt{") == 6) {
            bool ok = false;
            const int32_t j = scanBraceArg(s, i + 6, n, &ok);
            if (ok) {
                dst.push_back(0x221A);  // √
                appendRange(dst, s, i + 6, j);
                i = j + 1;
                continue;
            }
        }
        dst.push_back(s[i]);
        ++i;
    }
}

// 把 [b,e) 逐 code unit 映射成上标/下标；映射不到就原样吐 marker + 字符
//（这正是 Kotlin superscript()/subscript() 的 "^$it" / "_$it" 分支）。
void mapScript(std::vector<uint16_t>& dst, const uint16_t* s, int32_t b, int32_t e,
               uint16_t marker, const ScriptEntry* tab, int32_t tabN) {
    int32_t k = b;
    while (k < e) {
        const uint16_t c = s[k];
        const uint16_t rep = lookupScript(tab, tabN, c);
        if (rep != 0) {
            dst.push_back(rep);
            ++k;
            continue;
        }
        dst.push_back(marker);
        dst.push_back(c);
        ++k;
        // 与 Kotlin 的有意差异：高代理后面紧跟低代理时把整对一起搬过来，
        // 不在中间插 marker。Kotlin 版逐 Char 映射会把代理对劈成两个孤代理
        // （见 text_tools.h 的"已知差异 B"）。
        if (c >= 0xD800 && c <= 0xDBFF && k < e && s[k] >= 0xDC00 && s[k] <= 0xDFFF) {
            dst.push_back(s[k]);
            ++k;
        }
    }
}

// ④⑤⑥⑦ 上下标。braced=true 处理 `^{X}`，false 处理 `^C`。
// 四趟必须按 Kotlin 的顺序分开跑（^{} → ^C → _{} → _C）：合并会改变结果，
// 例如 `_{^a}` 先跑完所有 ^ 再跑 _ 得到 `_ᵃ`，一趟处理则得到 `_^ₐ`。
void passScript(const std::vector<uint16_t>& src, std::vector<uint16_t>& dst,
                uint16_t marker, bool braced, const ScriptEntry* tab, int32_t tabN) {
    const int32_t n = (int32_t)src.size();
    const uint16_t* s = src.data();
    dst.clear();
    int32_t i = 0;
    while (i < n) {
        if (s[i] == marker && i + 1 < n) {
            if (braced && s[i + 1] == '{') {
                bool ok = false;
                const int32_t j = scanBraceArg(s, i + 2, n, &ok);
                if (ok) {
                    mapScript(dst, s, i + 2, j, marker, tab, tabN);
                    i = j + 1;
                    continue;
                }
            } else if (!braced && isScriptArg(s[i + 1])) {
                mapScript(dst, s, i + 1, i + 2, marker, tab, tabN);
                i += 2;
                continue;
            }
        }
        dst.push_back(s[i]);
        ++i;
    }
}

// ⑧ 没认出来的 \命令 去掉反斜杠（正则 \\([A-Za-z]+) → $1）
void passStripSlash(const std::vector<uint16_t>& src, std::vector<uint16_t>& dst) {
    const int32_t n = (int32_t)src.size();
    const uint16_t* s = src.data();
    dst.clear();
    int32_t i = 0;
    while (i < n) {
        if (s[i] == '\\' && i + 1 < n && isAsciiAlpha(s[i + 1])) {
            int32_t j = i + 1;
            while (j < n && isAsciiAlpha(s[j])) ++j;  // 贪婪，同正则 +
            appendRange(dst, s, i + 1, j);
            i = j;
            continue;
        }
        dst.push_back(s[i]);
        ++i;
    }
}

// ⑨ 两字符字面量替换，等价于 Kotlin String.replace(old, new)：
// 从左到右、不重叠、替换结果不再参与后续匹配。
// 三次替换必须**依次**跑：合并成一趟会改结果（`\\}` 在 Kotlin 下是
// 先命中 `\}` 得到 `\}`，一趟处理则会先命中 `\\` 得到 换行+`}`）。
void passLit2(const std::vector<uint16_t>& src, std::vector<uint16_t>& dst,
              uint16_t c0, uint16_t c1, uint16_t rep) {
    const int32_t n = (int32_t)src.size();
    const uint16_t* s = src.data();
    dst.clear();
    int32_t i = 0;
    while (i < n) {
        if (s[i] == c0 && i + 1 < n && s[i + 1] == c1) {
            dst.push_back(rep);
            i += 2;
            continue;
        }
        dst.push_back(s[i]);
        ++i;
    }
}

}  // namespace

// =====================================================================
// 对外接口
// =====================================================================

bool lineDiff(const uint16_t* a, int32_t alen, const uint16_t* b, int32_t blen,
              std::vector<int32_t>& out) {
    out.clear();
    if (alen < 0 || blen < 0) return false;
    if ((a == nullptr && alen > 0) || (b == nullptr && blen > 0)) return false;

    Lines la, lb;
    splitLines(a, alen, la);
    splitLines(b, blen, lb);

    const int32_t na = (int32_t)la.start.size();
    const int32_t nb = (int32_t)lb.start.size();

    Interner in;
    in.init((size_t)na + (size_t)nb);
    std::vector<int32_t> ida, idb;
    buildIds(a, la, in, ida);
    buildIds(b, lb, in, idb);

    DiffState st;
    st.A = ida.data();
    st.B = idb.data();
    st.la = &la;
    st.lb = &lb;
    st.out = &out;
    st.outLimit = kDiffHeaderInts + 3 * kDiffMaxEntries;
    if (la.truncated) st.flags |= kDiffFlagTruncatedBefore;
    if (lb.truncated) st.flags |= kDiffFlagTruncatedAfter;

    out.assign((size_t)kDiffHeaderInts, 0);  // 表头占位，最后回填
    runDiff(st, na, nb);

    out[0] = kDiffFormatVersion;
    out[1] = st.flags;
    out[2] = na;
    out[3] = nb;
    return true;
}

void syntaxIssues(const uint16_t* s, int32_t len, std::vector<int32_t>& out) {
    out.clear();
    if (s == nullptr || len <= 0 || len > kIssuesMaxUnits) return;

    struct Open {
        uint16_t ch;
        int32_t pos;
    };
    std::vector<Open> stack;
    const int32_t outLimit = 2 * kIssuesMaxCount;

    int32_t i = 0;
    while (i < len) {
        if ((int32_t)out.size() >= outLimit) return;
        const uint16_t ch = s[i];

        if (ch == '\n') {
            ++i;
            continue;
        }
        if (ch == '/') {
            if (i + 1 < len && s[i + 1] == '/') {
                while (i < len && s[i] != '\n') ++i;  // 行注释吃到行尾
            } else if (i + 1 < len && s[i + 1] == '*') {
                i += 2;
                while (i + 1 < len && !(s[i] == '*' && s[i + 1] == '/')) ++i;
                i += 2;
                if (i > len) i = len;  // 未闭合的块注释：吃到结尾
            } else {
                ++i;
            }
            continue;
        }
        if (ch == '"' || ch == '\'') {
            const uint16_t quote = ch;
            const int32_t start = i;
            ++i;
            bool closed = false;
            while (i < len && s[i] != '\n') {
                // 转义连吃两个 code unit —— 包括换行，与 Kotlin 版一致
                if (s[i] == '\\' && i + 1 < len) {
                    i += 2;
                    continue;
                }
                if (s[i] == quote) {
                    closed = true;
                    ++i;
                    break;
                }
                ++i;
            }
            if (!closed) {
                int32_t last = i - 1;
                if (last < start) last = start;
                out.push_back(start);
                out.push_back(last + 1);  // 开区间：等价于 Kotlin 的 start..last
            }
            continue;
        }
        if (ch == '(' || ch == '[' || ch == '{') {
            stack.push_back(Open{ch, i});
            ++i;
            continue;
        }
        if (ch == ')' || ch == ']' || ch == '}') {
            const uint16_t want = (ch == ')') ? '(' : (ch == ']' ? '[' : '{');
            if (stack.empty() || stack.back().ch != want) {
                out.push_back(i);
                out.push_back(i + 1);
            } else {
                stack.pop_back();
            }
            ++i;
            continue;
        }
        ++i;
    }

    // 剩下的未闭合左括号：从栈顶往栈底弹（与 Kotlin 的 removeLast 一致，
    // 所以这段 offset 是递减的）。
    while (!stack.empty()) {
        if ((int32_t)out.size() >= outLimit) return;
        out.push_back(stack.back().pos);
        out.push_back(stack.back().pos + 1);
        stack.pop_back();
    }
}

bool latexToUnicode(const uint16_t* s, int32_t len, std::vector<uint16_t>& out) {
    out.clear();
    if (len < 0 || len > kLatexMaxUnits) return false;
    if (s == nullptr && len > 0) return false;

    // ---- ① trim + 剥外层 $$ / $ （顺序与 Kotlin 的 removePrefix/removeSuffix 链一致）----
    int32_t b = 0;
    int32_t e = len;
    while (b < e && isKtWs(s[b])) ++b;
    while (e > b && isKtWs(s[e - 1])) --e;
    if (e - b >= 2 && s[b] == '$' && s[b + 1] == '$') b += 2;
    if (e - b >= 2 && s[e - 1] == '$' && s[e - 2] == '$') e -= 2;
    if (e - b >= 1 && s[b] == '$') b += 1;
    if (e - b >= 1 && s[e - 1] == '$') e -= 1;

    std::vector<uint16_t> cur;
    std::vector<uint16_t> nxt;
    cur.reserve((size_t)(e - b));
    appendRange(cur, s, b, e);

    // ---- ②..⑨ 每趟都是单遍扫描，顺序不可调（见各 pass 的注释）----
    passCommands(cur, nxt);
    cur.swap(nxt);
    passFrac(cur, nxt);
    cur.swap(nxt);
    passSqrt(cur, nxt);
    cur.swap(nxt);

    constexpr int32_t kSuperN = (int32_t)(sizeof(kSuper) / sizeof(kSuper[0]));
    constexpr int32_t kSubN = (int32_t)(sizeof(kSub) / sizeof(kSub[0]));
    passScript(cur, nxt, '^', true, kSuper, kSuperN);
    cur.swap(nxt);
    passScript(cur, nxt, '^', false, kSuper, kSuperN);
    cur.swap(nxt);
    passScript(cur, nxt, '_', true, kSub, kSubN);
    cur.swap(nxt);
    passScript(cur, nxt, '_', false, kSub, kSubN);
    cur.swap(nxt);

    passStripSlash(cur, nxt);
    cur.swap(nxt);
    passLit2(cur, nxt, '\\', '{', '{');
    cur.swap(nxt);
    passLit2(cur, nxt, '\\', '}', '}');
    cur.swap(nxt);
    passLit2(cur, nxt, '\\', '\\', '\n');
    cur.swap(nxt);

    out.swap(cur);
    return true;
}

}  // namespace tt
}  // namespace biji

// =====================================================================
// JNI 边界
//
// 只收 jstring，只返回 jintArray / jstring —— 一次调用返回全部结果。
// 不 FindClass、不缓存 jmethodID、不 NewStringUTF、不抛异常。
//
// 为什么用 GetStringRegion 而不是 GetStringCritical：Android 8（= 本项目
// minSdk 26）起 ART 用 8 bit/char 存 ASCII 串并启用了移动式 GC，
// GetStringCritical 对 ASCII 主导的源码几乎必然发生拷贝，却额外挂起 GC、
// 且禁止临界区内做任何其他 JNI 调用。多协程并发进临界区正是最坏组合。
// GetStringRegion 一次调用完成、不 pin，语义同样是纯 UTF-16 code unit。
//
// 返回字符串用 NewString(jchar*, len)（UTF-16），**不是** NewStringUTF
// —— 后者要求 Modified UTF-8，中文/emoji 会错，而且和我们的 offset 口径不符。
// =====================================================================
#ifndef BIJI_TT_HOST_TEST

#include <jni.h>

namespace {

// jchar 与 uint16_t 必须同宽，否则 reinterpret_cast 不成立。
static_assert(sizeof(jchar) == sizeof(uint16_t), "jchar must be 16-bit");

// 读一个 jstring 的前 maxUnits 个 code unit 到 buf。
// 返回 false = 需要放弃（异常已清）。*truncated 标记源串比 maxUnits 长。
//
// 【截断点必须避开代理对】如果第 maxUnits-1 个 code unit 是高代理，它的低代理
// 就落在截断点之外。此时最后一行的 end offset 会正好指到代理对中间，
// Kotlin 侧 substring(start, end) 拿到的串就以一个孤立高代理结尾 —— 这正是
// 本项目 1 号硬性约束要防的静默 UTF-16 错误。少取一个 unit 即可，
// 反正这条路径本来就已经在截断了。
bool readUtf16(JNIEnv* env, jstring src, int32_t maxUnits, std::vector<jchar>& buf,
               bool* truncated) {
    const jsize len = env->GetStringLength(src);
    if (len < 0) return false;
    jsize take = len;
    if (take > maxUnits) {
        take = maxUnits;
        *truncated = true;
        // 代理对的回退在 GetStringRegion 之后做：判据是"保留的最后一个 unit
        // 是不是高代理"，而那个 unit 要先取出来才能看。
    }
    // -fno-exceptions：分配失败会 abort，所以上限必须先于分配。
    buf.assign((size_t)take, 0);
    if (take > 0) {
        env->GetStringRegion(src, 0, take, buf.data());
        if (env->ExceptionCheck()) {  // 越界 / OOM —— 不外泄异常
            env->ExceptionClear();
            return false;
        }
        if (*truncated) {
            const jchar last = buf[(size_t)take - 1];
            if (last >= 0xD800 && last <= 0xDBFF) {
                --take;
                buf.resize((size_t)take);
            }
        }
    }
    return true;
}

jintArray toIntArray(JNIEnv* env, const std::vector<int32_t>& v) {
    jintArray arr = env->NewIntArray((jsize)v.size());
    if (arr == nullptr) {
        env->ExceptionClear();  // OOM 也只降级，不抛
        return nullptr;
    }
    if (!v.empty()) {
        env->SetIntArrayRegion(arr, 0, (jsize)v.size(),
                               reinterpret_cast<const jint*>(v.data()));
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return nullptr;
        }
    }
    return arr;
}

}  // namespace

// 第二个参数是 jobject 而不是 jclass：Kotlin `object` 里的 `external fun`
// 编译成**实例**方法，JNI 传进来的是 NativeText.INSTANCE。这里用不到它。

extern "C" JNIEXPORT jintArray JNICALL
Java_com_biji_notes_nativebridge_NativeText_nLineDiff(
        JNIEnv* env, jobject /*thiz*/, jstring before, jstring after) {
    if (before == nullptr || after == nullptr) return nullptr;

    bool truncA = false;
    bool truncB = false;
    std::vector<jchar> bufA;
    std::vector<jchar> bufB;
    if (!readUtf16(env, before, biji::tt::kDiffMaxUnits, bufA, &truncA)) return nullptr;
    if (!readUtf16(env, after, biji::tt::kDiffMaxUnits, bufB, &truncB)) return nullptr;

    std::vector<int32_t> out;
    const uint16_t* pa = bufA.empty() ? nullptr : reinterpret_cast<const uint16_t*>(bufA.data());
    const uint16_t* pb = bufB.empty() ? nullptr : reinterpret_cast<const uint16_t*>(bufB.data());
    if (!biji::tt::lineDiff(pa, (int32_t)bufA.size(), pb, (int32_t)bufB.size(), out)) {
        return nullptr;
    }
    // 字符数触顶是在 JNI 这层截的（避免拷贝整个超大串），所以标志在这里补。
    if (out.size() >= 2) {
        if (truncA) out[1] |= biji::tt::kDiffFlagTruncatedBefore;
        if (truncB) out[1] |= biji::tt::kDiffFlagTruncatedAfter;
    }
    return toIntArray(env, out);
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_biji_notes_nativebridge_NativeText_nSyntaxIssues(
        JNIEnv* env, jobject /*thiz*/, jstring text) {
    if (text == nullptr) return nullptr;
    // 超长直接降级给 Kotlin：那边本来就是 O(n) 单遍状态机，不会 OOM。
    if (env->GetStringLength(text) > biji::tt::kIssuesMaxUnits) return nullptr;

    bool trunc = false;
    std::vector<jchar> buf;
    if (!readUtf16(env, text, biji::tt::kIssuesMaxUnits, buf, &trunc)) return nullptr;

    std::vector<int32_t> out;
    const uint16_t* p = buf.empty() ? nullptr : reinterpret_cast<const uint16_t*>(buf.data());
    biji::tt::syntaxIssues(p, (int32_t)buf.size(), out);
    return toIntArray(env, out);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_biji_notes_nativebridge_NativeText_nLatexToUnicode(
        JNIEnv* env, jobject /*thiz*/, jstring raw) {
    if (raw == nullptr) return nullptr;
    if (env->GetStringLength(raw) > biji::tt::kLatexMaxUnits) return nullptr;

    bool trunc = false;
    std::vector<jchar> buf;
    if (!readUtf16(env, raw, biji::tt::kLatexMaxUnits, buf, &trunc)) return nullptr;

    std::vector<uint16_t> out;
    const uint16_t* p = buf.empty() ? nullptr : reinterpret_cast<const uint16_t*>(buf.data());
    if (!biji::tt::latexToUnicode(p, (int32_t)buf.size(), out)) return nullptr;

    // 空结果是可达的（比如输入就是 "$$"）。空 vector 的 data() 可能是
    // nullptr，别把 nullptr 递给 NewString —— CheckJNI 会直接判违规。
    static const jchar kEmpty[1] = {0};
    const jchar* chars = out.empty() ? kEmpty : reinterpret_cast<const jchar*>(out.data());
    jstring js = env->NewString(chars, (jsize)out.size());
    if (js == nullptr) {
        env->ExceptionClear();
        return nullptr;
    }
    return js;
}

#endif  // BIJI_TT_HOST_TEST
