// =====================================================================
// markdown_parser.cpp — 单遍、无正则的块级 Markdown 解析器 + JNI 导出。
//
// 行为基准：ui/markdown/Markdown.kt 的 private fun parseBlocks(source)。
// 下面每个判定函数上方都注明它替代的是哪条 Regex，以及该 Regex 的
// 精确语义（含 Java 正则的贪婪 / 回溯行为），改动时请对照。
//
// 两套"空白"定义必须严格区分，混用会导致 offset 与 Kotlin 版错位：
//   * isKtWs  —— Kotlin 的 Char.isWhitespace()，即
//                Character.isWhitespace(c) || Character.isSpaceChar(c)。
//                用于 trim / trimStart / trimEnd / isBlank。
//   * isReWs  —— Java 正则的 \s，即 [ \t\n\x0B\f\r]。
//                用于所有 Regex 里出现的 \s。
// 例：">>> $$ " 这类行，两者判定不同，Kotlin 版也确实不同。
// =====================================================================
#include "markdown_parser.h"

namespace biji {
namespace md {
namespace {

// ---------------------------------------------------------------------
// 字符类
// ---------------------------------------------------------------------

// Kotlin: Char.isWhitespace() = Character.isWhitespace() || Character.isSpaceChar()
//   isWhitespace : 0x09..0x0D, 0x1C..0x1F, Zs(除 00A0/2007/202F), 2028, 2029
//   isSpaceChar  : Zs(全部), 2028, 2029
// 并集 = 0x09..0x0D, 0x1C..0x1F, 0x20, 0xA0, 0x1680, 0x2000..0x200A,
//        0x2028, 0x2029, 0x202F, 0x205F, 0x3000
// 注意：这些全部落在 BMP 非代理区（< 0xD800），所以代理对不会被误切。
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

// Java 正则的 \s（未开 UNICODE_CHARACTER_CLASS，Markdown.kt 里全部没开）
inline bool isReWs(uint16_t c) { return c == 0x20 || (c >= 0x09 && c <= 0x0D); }

// Java 正则的 \d（未开 UNICODE 标志）= [0-9]
inline bool isDigit(uint16_t c) { return c >= '0' && c <= '9'; }

// Java 正则的 \w（未开 UNICODE 标志）= [a-zA-Z_0-9]
inline bool isWordCh(uint16_t c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
           (c >= '0' && c <= '9') || c == '_';
}

// FenceRegex 的 [\w+-]
inline bool isLangCh(uint16_t c) { return isWordCh(c) || c == '+' || c == '-'; }

// Java 正则的“行终止符”（未开 UNIX_LINES）：LF(0x0A)、CR(0x0D)、
// NEL(U+0085)、LS(U+2028)、PS(U+2029)。
//
// 这一条极其容易被忽略，但它是 Kotlin 版真实行为的一部分：
//  * '.' **不**匹配行终止符（注意 VT(0x0B)/FF(0x0C) 不是行终止符，
//    它俩只是 \s）；
//  * Regex.matches()/matchEntire() 走 ENDANCHOR，要求整段输入被吃光，
//    所以 `$` 即便在“末尾行终止符之前”匹配成功，整体仍然失配。
// 结论：形如 `(.*)$` 的模式，只要 `.*` 要覆盖的区间里还残留任何行终止符，
// 整条正则就一定失配。
//
// 什么时候会遇到？source 只把 "\r\n" 归一成 "\n" 再按 "\n" 切行，所以
// **行内的裸 CR**（老 Mac 换行、粘贴过来的混合换行）会原样留在行里；
// NEL/LS/PS 同理，而且 NEL 连 trimEnd 都不吃（它既不是
// Character.isWhitespace 也不是 isSpaceChar）。这类行在 Kotlin 版里会
// 整行降级成 Paragraph，native 必须复现，否则一个裸 CR 就会让 native 和
// Kotlin 对同一篇文档给出不同的块结构。
inline bool isJavaLineTerm(uint16_t c) {
    return c == 0x0A || c == 0x0D || c == 0x0085 || c == 0x2028 || c == 0x2029;
}

inline bool hasLineTerm(const uint16_t* s, int32_t b, int32_t e) {
    for (int32_t p = b; p < e; ++p) {
        if (isJavaLineTerm(s[p])) return true;
    }
    return false;
}

inline uint16_t lowerAscii(uint16_t c) {
    return (c >= 'A' && c <= 'Z') ? static_cast<uint16_t>(c + 32) : c;
}

// ---------------------------------------------------------------------
// 区间工具（[b, e) 半开区间，全部是 UTF-16 下标）
// ---------------------------------------------------------------------

// Kotlin String.trim()
inline void ktTrim(const uint16_t* s, int32_t& b, int32_t& e) {
    while (b < e && isKtWs(s[b])) b++;
    while (e > b && isKtWs(s[e - 1])) e--;
}

// lang 只可能由 [\w+-] 组成（纯 ASCII），所以 ASCII 折叠即等价于
// Kotlin 的 equals(other, ignoreCase = true)。
inline bool eqAsciiIgnoreCase(const uint16_t* s, int32_t b, int32_t e, const char* lit) {
    for (int32_t p = b; p < e; ++p) {
        if (*lit == '\0') return false;
        if (lowerAscii(s[p]) != static_cast<uint16_t>(lowerAscii(static_cast<uint16_t>(*lit))))
            return false;
        ++lit;
    }
    return *lit == '\0';
}

inline bool startsWithCh2(const uint16_t* s, int32_t b, int32_t e, uint16_t a, uint16_t c) {
    return e - b >= 2 && s[b] == a && s[b + 1] == c;
}

// ---------------------------------------------------------------------
// 行模型
// ---------------------------------------------------------------------
//   ls  行首（原始串下标）
//   le  raw 行尾 —— 已按 "\r\n" -> "\n" 的等价规则剔除结尾的 '\r'
//       => [ls, le) 逐字符等于 Kotlin 的 lines[i]
//   te  [ls, le) 做 trimEnd 后的尾   => [ls, te) 等于 Kotlin 的 `line`
//   ts  [ls, te) 做 trimStart 后的头 => [ts, te) 等于 Kotlin 的 `trimmed`
//   rs  [ls, le) 做 trimStart 后的头 => [rs, le) 等于 Kotlin 的 lines[i].trimStart()
//       （闭合围栏检测和引用块用的是这个，它**没有** trimEnd）
//   ind indentDepth(raw) = 前导 ' ' 的个数 / 2（未 coerce）
//   term [ls, le) 里是否含 Java 正则意义上的行终止符（见 isJavaLineTerm）。
//        绝大多数行为 false，用它做快速路径，避免为每行多扫一遍。
struct Line {
    int32_t ls, le, te, ts, rs, ind;
    bool term;
};

// ---------------------------------------------------------------------
// 各条 Regex 的手写等价物
// ---------------------------------------------------------------------

// FenceRegex = ^```([\w+-]*)\s*$
// [\w+-]* 与 \s* 的字符集不相交，所以贪婪匹配无回溯，单遍即可。
// 注意：闭合围栏用的是同一条 Regex，所以 "```kotlin" 也能闭合一个围栏。
bool isFence(const uint16_t* s, int32_t b, int32_t e, int32_t* langB, int32_t* langE) {
    if (e - b < 3) return false;
    if (s[b] != '`' || s[b + 1] != '`' || s[b + 2] != '`') return false;
    int32_t p = b + 3;
    const int32_t a = p;
    while (p < e && isLangCh(s[p])) p++;
    const int32_t z = p;
    while (p < e && isReWs(s[p])) p++;
    if (p != e) return false;          // 例如 "````" -> 第 4 个反引号不属于任一字符集
    if (langB) *langB = a;
    if (langE) *langE = z;
    return true;
}

// MathFenceRegex = ^\$\$\s*$
bool isMathFence(const uint16_t* s, int32_t b, int32_t e) {
    if (e - b < 2 || s[b] != '$' || s[b + 1] != '$') return false;
    int32_t p = b + 2;
    while (p < e && isReWs(s[p])) p++;
    return p == e;
}

// LatexBlockOpen = ^\\\[\s*$   /   LatexBlockClose = ^\\]\s*$
bool isLatexDelim(const uint16_t* s, int32_t b, int32_t e, uint16_t bracket) {
    if (e - b < 2 || s[b] != '\\' || s[b + 1] != bracket) return false;
    int32_t p = b + 2;
    while (p < e && isReWs(s[p])) p++;
    return p == e;
}

// HrRegex = ^(-{3,}|_{3,}|\*{3,})\s*$
// 三个分支的首字符互不相同，所以"取首字符 -> 数同字符游程"就是等价判定。
bool isHr(const uint16_t* s, int32_t b, int32_t e) {
    if (b >= e) return false;
    const uint16_t c = s[b];
    if (c != '-' && c != '_' && c != '*') return false;
    int32_t p = b, run = 0;
    while (p < e && s[p] == c) { p++; run++; }
    if (run < 3) return false;
    while (p < e && isReWs(s[p])) p++;
    return p == e;
}

// HeadingRegex = ^(#{1,6})\s+(.*)$   —— 注意：匹配的是 `line`，不是 `trimmed`，
// 所以标题必须顶格（前面不能有空格）。
// 7 个以上 '#' 不成立：#{1,6} 最多吃 6 个，回溯到 5/4/3/2/1 时下一个字符
// 仍是 '#' 而非 \s，全部失败。所以直接要求"完整游程 <= 6"。
// \s+ 贪婪吃光全部空白后，(.*)$ 取剩余部分；调用方再做 .trim()。
// `mayTerm` = 本行可能含行终止符（Line::term）。为 false 时跳过扫描。
bool isHeading(const uint16_t* s, int32_t b, int32_t e, bool mayTerm,
               int32_t* level, int32_t* textB, int32_t* textE) {
    int32_t p = b, h = 0;
    while (p < e && s[p] == '#') { p++; h++; }
    if (h < 1 || h > 6) return false;
    if (p >= e || !isReWs(s[p])) return false;
    while (p < e && isReWs(s[p])) p++;      // \s+ 贪婪；\s 含 \r，所以前导 \r 会被吃掉
    if (mayTerm && hasLineTerm(s, p, e)) return false;   // (.*)$ 跨不过行终止符
    *level = h;
    *textB = p;
    *textE = e;
    return true;
}

// OrderedRegex = ^(\d+)\.\s+(.*)$   —— 匹配 `trimmed`
bool isOrdered(const uint16_t* s, int32_t b, int32_t e, bool mayTerm,
               int32_t* textB, int32_t* textE) {
    int32_t p = b, d = 0;
    while (p < e && isDigit(s[p])) { p++; d++; }
    if (d < 1) return false;
    if (p >= e || s[p] != '.') return false;
    p++;
    if (p >= e || !isReWs(s[p])) return false;
    while (p < e && isReWs(s[p])) p++;
    if (mayTerm && hasLineTerm(s, p, e)) return false;
    if (textB) *textB = p;
    if (textE) *textE = e;
    return true;
}

// TaskRegex = ^[-*+]\s+\[([ xX])]\s+(.*)$   —— 匹配 `trimmed`
// 注意 ']' 之后是 \s+（至少一个空白），所以 "- [x]" 单独一行不是 TaskItem，
// 会掉进 BulletRegex 变成文本为 "[x]" 的 BulletItem。
bool isTask(const uint16_t* s, int32_t b, int32_t e, bool mayTerm,
            int32_t* checked, int32_t* textB, int32_t* textE) {
    if (b >= e) return false;
    const uint16_t m = s[b];
    if (m != '-' && m != '*' && m != '+') return false;
    int32_t p = b + 1;
    if (p >= e || !isReWs(s[p])) return false;
    while (p < e && isReWs(s[p])) p++;
    if (p >= e || s[p] != '[') return false;
    p++;
    if (p >= e) return false;
    const uint16_t mark = s[p];
    if (mark != ' ' && mark != 'x' && mark != 'X') return false;
    p++;
    if (p >= e || s[p] != ']') return false;
    p++;
    if (p >= e || !isReWs(s[p])) return false;
    while (p < e && isReWs(s[p])) p++;
    if (mayTerm && hasLineTerm(s, p, e)) return false;
    *checked = (mark == 'x' || mark == 'X') ? 1 : 0;
    *textB = p;
    *textE = e;
    return true;
}

// BulletRegex = ^[-*+]\s+(.*)$   —— 匹配 `trimmed`
bool isBullet(const uint16_t* s, int32_t b, int32_t e, bool mayTerm,
              int32_t* textB, int32_t* textE) {
    if (b >= e) return false;
    const uint16_t m = s[b];
    if (m != '-' && m != '*' && m != '+') return false;
    int32_t p = b + 1;
    if (p >= e || !isReWs(s[p])) return false;
    while (p < e && isReWs(s[p])) p++;
    if (mayTerm && hasLineTerm(s, p, e)) return false;
    *textB = p;
    *textE = e;
    return true;
}

// TablePipeRow = ^\|.*\|\s*$
// 所有调用点传进来的都是 trimEnd 过的 `line`，但这里仍然显式处理尾部 \s*，
// 免得将来有人换了调用点就静默错。至少要两个 '|'（"|" 单字符不匹配）。
//
// `\|\s*$` 里 \s* 之后必须吃光输入，而 '|' 本身不是 \s，所以收尾的那个 '|'
// 位置唯一确定：去掉尾部 \s 之后的最后一个字符。再要求 `.*` 覆盖的
// [b+1, q) 内没有行终止符。
bool isTablePipeRow(const uint16_t* s, int32_t b, int32_t e, bool mayTerm) {
    if (e - b < 2 || s[b] != '|') return false;
    int32_t r = e;
    while (r > b && isReWs(s[r - 1])) r--;
    const int32_t q = r - 1;
    if (q <= b || s[q] != '|') return false;
    return !(mayTerm && hasLineTerm(s, b + 1, q));
}

// TableSeparator = ^\|\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)+\|\s*$
// 组是 + 量词 => **至少两列**，"| --- |" 不算分隔行。
// 每个 cell 内 \s* / :? / -{3,} 的字符集两两不相交，贪婪无回溯。
// 唯一需要模拟回溯的地方：读到 '|' 时要判断它是"下一列的分隔符"还是
// "结尾那个 \|"。做法是向前看剩下的是不是纯 \s。
bool isTableSeparator(const uint16_t* s, int32_t b, int32_t e) {
    int32_t p = b;
    if (p >= e || s[p] != '|') return false;
    p++;
    int32_t cells = 0;
    for (;;) {
        while (p < e && isReWs(s[p])) p++;
        if (p < e && s[p] == ':') p++;
        int32_t dash = 0;
        while (p < e && s[p] == '-') { p++; dash++; }
        if (dash < 3) return false;
        if (p < e && s[p] == ':') p++;
        while (p < e && isReWs(s[p])) p++;
        cells++;
        if (p >= e || s[p] != '|') return false;
        p++;
        int32_t q = p;
        while (q < e && isReWs(s[q])) q++;
        if (q == e) return cells >= 2;   // 这个 '|' 是收尾的那个
    }
}

// ---------------------------------------------------------------------
// 输出装配
// ---------------------------------------------------------------------
struct Builder {
    ParseOut& o;
    int32_t lastType = -1;

    explicit Builder(ParseOut& out) : o(out) {}

    int32_t spanMark() const { return static_cast<int32_t>(o.spans.size() / 2); }
    int32_t extraMark() const { return static_cast<int32_t>(o.extra.size()); }

    void span(int32_t a, int32_t b) {
        o.spans.push_back(a);
        o.spans.push_back(b);
    }

    void block(int32_t type, int32_t arg0, int32_t arg1,
               int32_t spanStart, int32_t spanCount,
               int32_t extraStart, int32_t extraCount) {
        o.blocks.push_back(type);
        o.blocks.push_back(arg0);
        o.blocks.push_back(arg1);
        o.blocks.push_back(spanStart);
        o.blocks.push_back(spanCount);
        o.blocks.push_back(extraStart);
        o.blocks.push_back(extraCount);
        o.blocks.push_back(0);
        lastType = type;
    }
};

// splitTableRow(line) = line.trim().trim('|').split('|').map { it.trim() }
// 注意 trim('|') 会把**两端所有**竖线都吃掉：
//   "|a||"  -> trim('|') -> "a"   （只有 1 个单元格，不是 ["a", ""]）
//   "||"    -> trim('|') -> ""    -> split -> [""]（1 个空单元格）
// 返回单元格个数。
int32_t splitTableRow(const uint16_t* s, int32_t b, int32_t e, Builder& bld) {
    ktTrim(s, b, e);
    while (b < e && s[b] == '|') b++;
    while (e > b && s[e - 1] == '|') e--;
    int32_t cells = 0;
    int32_t cs = b;
    for (int32_t p = b; p <= e; ++p) {
        if (p == e || s[p] == '|') {
            int32_t a = cs, z = p;
            ktTrim(s, a, z);
            bld.span(a, z);
            cells++;
            cs = p + 1;
        }
    }
    return cells;
}

}  // namespace

// =====================================================================
// 主循环 —— 与 Markdown.kt:112..221 的 while 一一对应
// =====================================================================
bool parse(const uint16_t* s, int32_t len, ParseOut& out) {
    if (len < 0 || len > kMaxInput) return false;
    if (len > 0 && s == nullptr) return false;

    // ---- 切行 ----------------------------------------------------
    // 等价于 source.replace("\r\n", "\n").split("\n")，但不重写字符串。
    std::vector<Line> lines;
    lines.reserve(static_cast<size_t>(len) / 32 + 8);
    {
        int32_t i = 0;
        for (;;) {
            const int32_t ls = i;
            int32_t firstTerm = -1;
            while (i < len && s[i] != 0x0A) {
                if (firstTerm < 0 && isJavaLineTerm(s[i])) firstTerm = i;
                i++;
            }
            int32_t le = i;
            const bool terminated = (i < len);
            // 只有被 '\n' 收尾的行才可能是 "\r\n" 的前半；文件末尾没有换行
            // 的残段里的 '\r' 会被 replace 原样留下，这里也留下。
            if (terminated && le > ls && s[le - 1] == 0x0D) le--;

            Line L;
            L.ls = ls;
            L.le = le;
            int32_t te = le;
            while (te > ls && isKtWs(s[te - 1])) te--;
            L.te = te;
            int32_t ts = ls;
            while (ts < te && isKtWs(s[ts])) ts++;
            L.ts = ts;
            int32_t rs = ls;
            while (rs < le && isKtWs(s[rs])) rs++;
            L.rs = rs;
            int32_t k = ls, spaces = 0;
            while (k < le && s[k] == 0x20) { k++; spaces++; }
            L.ind = spaces / 2;              // indentDepth：只数半角空格，不数 tab
            // "\r\n" 的那个 '\r' 已经被排除在 [ls, le) 之外，不算行内终止符。
            L.term = (firstTerm >= 0 && firstTerm < le);
            lines.push_back(L);

            if (!terminated) break;
            i++;                             // 跳过 '\n'
        }
    }

    const int32_t n = static_cast<int32_t>(lines.size());
    out.blocks.clear();
    out.spans.clear();
    out.extra.clear();
    // 预留量必须**封顶**。理论上界是"每行一个 block"，但按 n * kBlockStride
    // 直接预留，在 len = kMaxInput 且行极短（极端是整篇都是 '\n'）时会一次性
    // 申请 32M 个 int = 128 MB —— 而那种输入的真实产出只有 1 个 block，
    // 128 MB 全是浪费。关键在于 -fno-exceptions 下 operator new 失败是
    // abort() 而不是可捕获的错误：一次失败的巨型预留会把本该优雅降级
    // （返回 null → Kotlin 版接手）的路径变成硬崩溃。
    // 封顶之后超出部分靠 vector 的几何扩容承担，摊还 O(1)，相对于整篇扫描
    // 可以忽略；而真实文档（平均行长 40+）根本碰不到这个上限。
    constexpr size_t kMaxReserveInts = 1u << 20;   // 4 MB/vector
    const size_t blockGuess = static_cast<size_t>(n) * kBlockStride;
    const size_t spanGuess = static_cast<size_t>(n) * 2;
    out.blocks.reserve(blockGuess < kMaxReserveInts ? blockGuess : kMaxReserveInts);
    out.spans.reserve(spanGuess < kMaxReserveInts ? spanGuess : kMaxReserveInts);

    Builder bld(out);

    // orderedCounters: MutableMap<Int, Int>，key 只可能是 0..3（depth 已 coerce）
    // -1 表示该 depth 尚未出现过（等价于 map 里没有这个 key）
    int32_t counters[4] = {-1, -1, -1, -1};
    auto clearCounters = [&counters]() {
        counters[0] = counters[1] = counters[2] = counters[3] = -1;
    };

    // 代码块 / 数学块的正文：Kotlin 是 buf.appendLine(lines[i]) 逐行追加
    // （每行后面补一个 '\n'），最后 .trimEnd('\n')。trimEnd('\n') 只吃
    // '\n'，所以效果是"丢掉末尾所有**内容为空**的行"，而只含空格的行会保留。
    // 这里把保留下来的行按 raw 区间发出去，Kotlin 侧用 '\n' 拼回来。
    auto emitBody = [&](const std::vector<int32_t>& bodyIdx) -> int32_t {
        int32_t last = -1;
        for (int32_t k = 0; k < static_cast<int32_t>(bodyIdx.size()); ++k) {
            const Line& B = lines[bodyIdx[static_cast<size_t>(k)]];
            if (B.le > B.ls) last = k;
        }
        for (int32_t k = 0; k <= last; ++k) {
            const Line& B = lines[bodyIdx[static_cast<size_t>(k)]];
            bld.span(B.ls, B.le);
        }
        return last + 1;
    };

    std::vector<int32_t> bodyIdx;

    int32_t li = 0;
    while (li < n) {
        const Line L = lines[li];
        const int32_t depth = L.ind > 3 ? 3 : L.ind;   // coerceAtMost(3)

        // --- if (line.isBlank()) -------------------------------------
        // line 是 trimEnd 过的，所以 isBlank() 等价于 "长度为 0"。
        if (L.te == L.ls) {
            if (bld.lastType != BT_BLANK) bld.block(BT_BLANK, 0, 0, bld.spanMark(), 0, 0, 0);
            clearCounters();
            li++;
            continue;
        }

        // --- $$ ... $$ ----------------------------------------------
        // 开围栏看 `trimmed`（两端都 trim 过），闭围栏看 lines[i].trimStart()
        // （只 trim 了左边）—— 与 Kotlin 一致，别"顺手统一"。
        if (isMathFence(s, L.ts, L.te)) {
            li++;
            bodyIdx.clear();
            while (li < n && !isMathFence(s, lines[li].rs, lines[li].le)) {
                bodyIdx.push_back(li);
                li++;
            }
            if (li < n) li++;                       // 吃掉闭围栏
            const int32_t sp = bld.spanMark();
            const int32_t cnt = emitBody(bodyIdx);
            bld.block(BT_MATH, 0, 0, sp, cnt, 0, 0);
            clearCounters();
            continue;
        }

        // --- \[ ... \] ----------------------------------------------
        if (isLatexDelim(s, L.ts, L.te, '[')) {
            li++;
            bodyIdx.clear();
            while (li < n && !isLatexDelim(s, lines[li].rs, lines[li].le, ']')) {
                bodyIdx.push_back(li);
                li++;
            }
            if (li < n) li++;
            const int32_t sp = bld.spanMark();
            const int32_t cnt = emitBody(bodyIdx);
            bld.block(BT_MATH, 0, 0, sp, cnt, 0, 0);
            clearCounters();
            continue;
        }

        // --- ``` ... ``` --------------------------------------------
        int32_t langB = 0, langE = 0;
        if (isFence(s, L.ts, L.te, &langB, &langE)) {
            li++;
            bodyIdx.clear();
            while (li < n && !isFence(s, lines[li].rs, lines[li].le, nullptr, nullptr)) {
                bodyIdx.push_back(li);
                li++;
            }
            if (li < n) li++;

            // body.contains("=>") && body.contains("->")
            // 只需要看被保留的那些行：拼接用的 '\n' 不可能跨行造出这两个
            // 二字符序列，末尾被丢掉的空行也不含任何字符。
            int32_t lastNonEmpty = -1;
            for (int32_t k = 0; k < static_cast<int32_t>(bodyIdx.size()); ++k) {
                const Line& B = lines[bodyIdx[static_cast<size_t>(k)]];
                if (B.le > B.ls) lastNonEmpty = k;
            }
            bool hasFat = false, hasThin = false;
            for (int32_t k = 0; k <= lastNonEmpty; ++k) {
                const Line& B = lines[bodyIdx[static_cast<size_t>(k)]];
                for (int32_t p = B.ls; p + 1 < B.le; ++p) {
                    if (s[p + 1] == '>') {
                        if (s[p] == '=') hasFat = true;
                        else if (s[p] == '-') hasThin = true;
                    }
                }
            }

            const bool mermaid =
                eqAsciiIgnoreCase(s, langB, langE, "mermaid") ||
                eqAsciiIgnoreCase(s, langB, langE, "flow") ||
                eqAsciiIgnoreCase(s, langB, langE, "flowchart") ||
                (langB == langE && hasFat && hasThin);   // lang.isBlank() <=> 空（[\w+-]* 不含空白）

            const int32_t sp = bld.spanMark();
            if (mermaid) {
                const int32_t cnt = emitBody(bodyIdx);
                bld.block(BT_MERMAID, 0, 0, sp, cnt, 0, 0);
            } else {
                bld.span(langB, langE);                  // spans[0] 恒为 lang
                const int32_t cnt = emitBody(bodyIdx);
                bld.block(BT_CODE, 0, 0, sp, cnt + 1, 0, 0);
            }
            clearCounters();
            continue;
        }

        // --- 分割线 --------------------------------------------------
        if (isHr(s, L.ts, L.te)) {
            bld.block(BT_DIVIDER, 0, 0, bld.spanMark(), 0, 0, 0);
            li++;
            clearCounters();
            continue;
        }

        // --- 表格 ----------------------------------------------------
        // 表头看的是 `line`（未 trimStart），所以表格必须顶格。
        if (isTablePipeRow(s, L.ls, L.te, L.term) && li + 1 < n &&
            isTableSeparator(s, lines[li + 1].ls, lines[li + 1].te)) {
            const int32_t sp = bld.spanMark();
            const int32_t ex = bld.extraMark();
            int32_t rowCount = 0;

            out.extra.push_back(splitTableRow(s, L.ls, L.te, bld));   // 行 0 = header
            rowCount++;
            li += 2;                                                  // 跳过表头 + 分隔行
            while (li < n && isTablePipeRow(s, lines[li].ls, lines[li].te, lines[li].term)) {
                out.extra.push_back(splitTableRow(s, lines[li].ls, lines[li].te, bld));
                rowCount++;
                li++;
            }
            bld.block(BT_TABLE, rowCount, 0, sp, bld.spanMark() - sp, ex, rowCount);
            clearCounters();
            continue;
        }

        // --- 标题 ----------------------------------------------------
        {
            int32_t level = 0, tb = 0, te2 = 0;
            if (isHeading(s, L.ls, L.te, L.term, &level, &tb, &te2)) {
                ktTrim(s, tb, te2);                                   // .trim()
                const int32_t sp = bld.spanMark();
                bld.span(tb, te2);
                bld.block(BT_HEADING, level, 0, sp, 1, 0, 0);
                li++;
                clearCounters();
                continue;
            }
        }

        // --- 引用 ----------------------------------------------------
        // 每行 = lines[i].trimStart().removePrefix(">").trimStart()
        // 注意**没有** trimEnd，行尾空白会被保留（Kotlin 版就是这样）。
        if (L.ts < L.te && s[L.ts] == '>') {
            const int32_t sp = bld.spanMark();
            int32_t cnt = 0;
            while (li < n && lines[li].rs < lines[li].le && s[lines[li].rs] == '>') {
                const Line& Q = lines[li];
                int32_t a = Q.rs + 1;                                 // removePrefix(">")
                while (a < Q.le && isKtWs(s[a])) a++;                 // trimStart()
                bld.span(a, Q.le);
                cnt++;
                li++;
            }
            bld.block(BT_QUOTE, 0, 0, sp, cnt, 0, 0);
            clearCounters();
            continue;
        }

        // --- 任务项（不清 orderedCounters！） -------------------------
        {
            int32_t checked = 0, tb = 0, te2 = 0;
            if (isTask(s, L.ts, L.te, L.term, &checked, &tb, &te2)) {
                ktTrim(s, tb, te2);
                const int32_t sp = bld.spanMark();
                bld.span(tb, te2);
                bld.block(BT_TASK, depth, checked, sp, 1, 0, 0);
                li++;
                continue;
            }
        }

        // --- 有序项（不清 orderedCounters，按 depth 累加） -------------
        {
            int32_t tb = 0, te2 = 0;
            if (isOrdered(s, L.ts, L.te, L.term, &tb, &te2)) {
                // getOrPut(depth) { 0 } + 1 —— 用的是计数器，不是字面数字
                const int32_t cur = counters[depth] < 0 ? 0 : counters[depth];
                const int32_t num = cur + 1;
                counters[depth] = num;
                ktTrim(s, tb, te2);
                const int32_t sp = bld.spanMark();
                bld.span(tb, te2);
                bld.block(BT_NUMBERED, depth, num, sp, 1, 0, 0);
                li++;
                continue;
            }
        }

        // --- 无序项（**会**清 orderedCounters） -----------------------
        {
            int32_t tb = 0, te2 = 0;
            if (isBullet(s, L.ts, L.te, L.term, &tb, &te2)) {
                ktTrim(s, tb, te2);
                const int32_t sp = bld.spanMark();
                bld.span(tb, te2);
                bld.block(BT_BULLET, depth, 0, sp, 1, 0, 0);
                li++;
                clearCounters();
                continue;
            }
        }

        // --- 段落 ----------------------------------------------------
        // 首行是 `line`（trimEnd，保留前导空格），续行是 nt（同样 trimEnd）。
        // Kotlin 用 '\n' 把它们拼起来，所以这里逐行发 span。
        {
            const int32_t sp = bld.spanMark();
            bld.span(L.ls, L.te);
            int32_t cnt = 1;
            li++;
            while (li < n) {
                const Line& N = lines[li];
                if (N.te == N.ls) break;                              // nt.isBlank()
                const int32_t b = N.ts, e = N.te;                     // nts = nt.trimStart()
                if (b < e && s[b] == '#') break;
                if (startsWithCh2(s, b, e, '-', ' ')) break;
                if (startsWithCh2(s, b, e, '*', ' ')) break;
                if (startsWithCh2(s, b, e, '+', ' ')) break;
                if (startsWithCh2(s, b, e, '>', ' ')) break;
                if (e - b >= 3 && s[b] == '`' && s[b + 1] == '`' && s[b + 2] == '`') break;
                if (startsWithCh2(s, b, e, '$', '$')) break;
                if (isHr(s, b, e)) break;
                if (isOrdered(s, b, e, N.term, nullptr, nullptr)) break;
                if (isTablePipeRow(s, N.ls, N.te, N.term)) break;             // 注意这条用 nt 不是 nts
                bld.span(N.ls, N.te);
                cnt++;
                li++;
            }
            bld.block(BT_PARAGRAPH, 0, 0, sp, cnt, 0, 0);
            clearCounters();
        }
    }
    return true;
}

std::vector<int32_t> pack(const ParseOut& out) {
    const int32_t blockCount = static_cast<int32_t>(out.blocks.size()) / kBlockStride;
    const int32_t spanCount = static_cast<int32_t>(out.spans.size()) / 2;
    const int32_t extraCount = static_cast<int32_t>(out.extra.size());
    const int32_t blockOff = kHeaderInts;
    const int32_t spanOff = blockOff + blockCount * kBlockStride;
    const int32_t extraOff = spanOff + spanCount * 2;

    std::vector<int32_t> r;
    r.reserve(static_cast<size_t>(extraOff + extraCount));
    r.push_back(kMagic);
    r.push_back(kVersion);
    r.push_back(blockCount);
    r.push_back(blockOff);
    r.push_back(spanCount);
    r.push_back(spanOff);
    r.push_back(extraCount);
    r.push_back(extraOff);
    r.insert(r.end(), out.blocks.begin(), out.blocks.end());
    r.insert(r.end(), out.spans.begin(), out.spans.end());
    r.insert(r.end(), out.extra.begin(), out.extra.end());
    return r;
}

}  // namespace md
}  // namespace biji

// =====================================================================
// JNI 边界
//
// 只收 jstring，只返回 jintArray —— 一次调用返回整篇文档的全部结果。
// 不 FindClass、不缓存 jmethodID、不 NewStringUTF、不抛异常。
//
// 为什么用 GetStringRegion 而不是 GetStringCritical：Android 8（= 本项目
// minSdk 26）起 ART 用 8 bit/char 存 ASCII 串并启用了移动式 GC，
// GetStringCritical 对 ASCII 主导的 Markdown 几乎必然发生拷贝，却额外
// 挂起 GC、并禁止临界区内做任何其他 JNI 调用。多协程并发进临界区正好是
// 最坏组合。GetStringRegion 一次调用完成、不 pin、语义同样是纯 UTF-16
// code unit，offset 口径完全一致。（NDK JNI tips 官方建议）
// =====================================================================
#ifndef BIJI_MD_HOST_TEST

#include <jni.h>

// 第二个参数是 jobject 而不是 jclass：Kotlin `object` 里的 `external fun`
// 编译成**实例**方法（javap 确认：private final native int[] nParseBlocks(String)），
// JNI 传进来的是 NativeMarkdown.INSTANCE。两者 ABI 上都是指针、这里也用不到它，
// 但按实际情况写类型能省掉下一个人的困惑。
extern "C" JNIEXPORT jintArray JNICALL
Java_com_biji_notes_nativebridge_NativeMarkdown_nParseBlocks(
        JNIEnv* env, jobject /*thiz*/, jstring src) {
    if (src == nullptr) return nullptr;

    const jsize len = env->GetStringLength(src);        // UTF-16 code unit 数
    if (len < 0 || len > biji::md::kMaxInput) return nullptr;

    // -fno-exceptions：分配失败会 abort，所以上面的长度上限必须先于分配。
    std::vector<jchar> buf(static_cast<size_t>(len));
    if (len > 0) {
        env->GetStringRegion(src, 0, len, buf.data());
        if (env->ExceptionCheck()) {                    // 越界/OOM -> 不外泄异常
            env->ExceptionClear();
            return nullptr;
        }
    }

    biji::md::ParseOut po;
    const uint16_t* p = len > 0 ? reinterpret_cast<const uint16_t*>(buf.data()) : nullptr;
    if (!biji::md::parse(p, static_cast<int32_t>(len), po)) return nullptr;

    const std::vector<int32_t> packed = biji::md::pack(po);
    jintArray arr = env->NewIntArray(static_cast<jsize>(packed.size()));
    if (arr == nullptr) {
        env->ExceptionClear();                          // OOM 同样只降级，不抛
        return nullptr;
    }
    env->SetIntArrayRegion(arr, 0, static_cast<jsize>(packed.size()),
                           reinterpret_cast<const jint*>(packed.data()));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return nullptr;
    }
    return arr;
}

#endif  // BIJI_MD_HOST_TEST
