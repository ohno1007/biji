// 语法高亮词法扫描器 —— 手写单遍状态机。契约见 syntax_highlight.h。
//
// 为什么是单遍状态机而不是把正则搬过来：
//  - std::regex 比 java.util.regex 还慢，且是递归回溯实现，长输入会爆栈；
//  - 原 Kotlin 实现要跑 8~10 趟全文正则 + 一个 BooleanArray(claim) 做占位仲裁，
//    单遍扫描把 "CLAIM 优先" 变成结构性质：字符串/注释一旦开始就一路吃到结束，
//    内部的关键字/数字永远没有机会被单独识别，claim 数组直接消失。
//
// 编译环境（app/src/main/cpp/CMakeLists.txt）：-fno-exceptions -fno-rtti
// -fvisibility=hidden。所以：
//  - 长度上限必须在分配之前检查（分配失败会 abort，catch 不到）；
//  - 不用 dynamic_cast / typeid，语言分发走 switch；
//  - JNI 导出必须显式写 JNIEXPORT，否则符号被 hidden 掉 → UnsatisfiedLinkError。

#include "syntax_highlight.h"

#include <jni.h>

namespace biji {
namespace {

// ---------------------------------------------------------------------------
// ASCII 分类
//
// 约束：所有分类函数对 >= 0x80 的 code unit 一律返回 false，即把它们当作
// "普通文本字符"。高低代理（0xD800-0xDFFF）都落在这一类里，永远同属一类，
// 所以 token 边界不可能切开一个代理对。中文/emoji/组合字符自动正确。
// ---------------------------------------------------------------------------

inline bool isDigit(char16_t c) { return c >= u'0' && c <= u'9'; }
inline bool isAlpha(char16_t c) {
    return (c >= u'a' && c <= u'z') || (c >= u'A' && c <= u'Z');
}
inline bool isAlnum(char16_t c) { return isAlpha(c) || isDigit(c); }
inline bool isIdentStart(char16_t c) { return isAlpha(c) || c == u'_'; }
inline bool isIdentPart(char16_t c) { return isAlnum(c) || c == u'_'; }
inline bool isHexDigit(char16_t c) {
    return isDigit(c) || (c >= u'a' && c <= u'f') || (c >= u'A' && c <= u'F');
}
// 与 java.util.regex 的 \s 对齐：[ \t\n\x0B\f\r]
inline bool isSpace(char16_t c) {
    return c == u' ' || c == u'\t' || c == u'\n' || c == 0x0B || c == u'\f' || c == u'\r';
}

// ---------------------------------------------------------------------------
// 关键词表 —— 照搬 SyntaxHighlight.kt 里的集合，按字节序预排序，二分查找。
// constexpr 静态只读，进程内唯一一份，不构造、不加锁、天然线程安全。
// 排序正确性由下面的 static_assert 在编译期保证（改表时漏排序会编译失败）。
// 注意字节序：数字 < 大写字母 < '_' < 小写字母，所以 "False"/"None"/"True"
// 排在小写词之前，"co_await" 排在 "concept" 之前。
// ---------------------------------------------------------------------------

// KotlinKeywords（kotlin / kt / java），46 条
constexpr const char* kKotlin[] = {
    "abstract", "as", "break", "by", "catch", "class", "companion", "continue", "data", "do",
    "else", "enum", "false", "final", "finally", "for", "fun", "if", "import", "in", "init",
    "interface", "internal", "is", "lateinit", "null", "object", "open", "out", "override",
    "package", "private", "protected", "public", "return", "sealed", "super", "suspend", "this",
    "throw", "true", "try", "val", "var", "when", "while",
};

// JsKeywords（javascript / ts / jsx / tsx …），34 条
constexpr const char* kJs[] = {
    "async", "await", "catch", "class", "const", "default", "do", "else", "export", "extends",
    "false", "finally", "for", "from", "function", "if", "import", "in", "instanceof", "let",
    "new", "null", "of", "return", "super", "this", "throw", "true", "try", "typeof",
    "undefined", "var", "void", "while",
};

// PythonKeywords，32 条
constexpr const char* kPython[] = {
    "False", "None", "True", "and", "as", "async", "await", "break", "class", "continue", "def",
    "elif", "else", "except", "finally", "for", "from", "global", "if", "import", "in", "is",
    "lambda", "nonlocal", "not", "or", "pass", "return", "try", "while", "with", "yield",
};

// CppKeywords，71 条
constexpr const char* kCpp[] = {
    "auto", "break", "case", "catch", "char", "class", "co_await", "co_return", "co_yield",
    "concept", "const", "const_cast", "constexpr", "continue", "decltype", "default", "delete",
    "do", "double", "dynamic_cast", "else", "enum", "explicit", "extern", "false", "final",
    "float", "for", "friend", "goto", "if", "inline", "int", "long", "mutable", "namespace",
    "new", "noexcept", "nullptr", "operator", "override", "private", "protected", "public",
    "register", "reinterpret_cast", "requires", "return", "short", "signed", "sizeof", "static",
    "static_cast", "struct", "switch", "template", "this", "thread_local", "throw", "true",
    "try", "typedef", "typeid", "typename", "union", "unsigned", "using", "virtual", "void",
    "volatile", "while",
};

// CppTypes —— 单独一个 token 类型，让 std::vector / size_t 之类和关键字区分开，34 条
constexpr const char* kCppType[] = {
    "array", "bool", "cerr", "cin", "cout", "deque", "endl", "int16_t", "int32_t", "int64_t",
    "int8_t", "istream", "list", "map", "ostream", "pair", "ptrdiff_t", "queue", "set",
    "shared_ptr", "size_t", "stack", "std", "string", "tuple", "uint16_t", "uint32_t",
    "uint64_t", "uint8_t", "unique_ptr", "unordered_map", "unordered_set", "vector", "weak_ptr",
};

// SqlKeywords（大小写不敏感匹配，表本身全小写），35 条
constexpr const char* kSql[] = {
    "all", "alter", "and", "as", "by", "create", "delete", "distinct", "drop", "from", "group",
    "having", "in", "inner", "insert", "into", "is", "join", "left", "limit", "not", "null",
    "offset", "on", "or", "order", "outer", "right", "select", "set", "table", "union",
    "update", "values", "where",
};

// bashRules 里内联的那条关键字正则，15 条
constexpr const char* kBash[] = {
    "case", "do", "done", "else", "esac", "export", "fi", "for", "function", "if", "in",
    "local", "return", "then", "while",
};

// jsonRules: \b(?:true|false|null)\b
constexpr const char* kJsonLit[] = {"false", "null", "true"};

// cssRules: \b(?:important|inherit|initial|unset|auto|none|true|false)\b
constexpr const char* kCssKw[] = {
    "auto", "false", "important", "inherit", "initial", "none", "true", "unset",
};

// cssRules 的单位表。按长度降序排列 —— 必须最长优先匹配，否则 "3rem" 会先
// 撞上 "em" 之外的短单位。不做二分（只有 11 条，线性即可）。
constexpr const char* kCssUnits[] = {"rem", "deg", "px", "em", "vw", "vh",
                                     "ms",  "fr",  "pt", "s",  "%"};

// ---- 编译期排序自检 -------------------------------------------------------

constexpr int cstrCmp(const char* a, const char* b) {
    size_t i = 0;
    while (a[i] != '\0' && a[i] == b[i]) ++i;
    return static_cast<int>(static_cast<unsigned char>(a[i])) -
           static_cast<int>(static_cast<unsigned char>(b[i]));
}

template <size_t N>
constexpr bool isSortedTable(const char* const (&t)[N]) {
    for (size_t i = 1; i < N; ++i) {
        if (cstrCmp(t[i - 1], t[i]) >= 0) return false;  // 未排序或有重复
    }
    return true;
}

static_assert(isSortedTable(kKotlin), "kKotlin must stay sorted for binary search");
static_assert(isSortedTable(kJs), "kJs must stay sorted for binary search");
static_assert(isSortedTable(kPython), "kPython must stay sorted for binary search");
static_assert(isSortedTable(kCpp), "kCpp must stay sorted for binary search");
static_assert(isSortedTable(kCppType), "kCppType must stay sorted for binary search");
static_assert(isSortedTable(kSql), "kSql must stay sorted for binary search");
static_assert(isSortedTable(kBash), "kBash must stay sorted for binary search");
static_assert(isSortedTable(kJsonLit), "kJsonLit must stay sorted for binary search");
static_assert(isSortedTable(kCssKw), "kCssKw must stay sorted for binary search");

// ---- 查表 -----------------------------------------------------------------

// 把 UTF-16 切片 s[0,len) 和 ASCII 关键词逐 code unit 比。
// 关键词全是 ASCII，切片里只要出现 >= 0x80 的 code unit 就必然不相等，
// 所以这里不需要任何编码转换。
// fold=true 时把切片里的 A-Z 折成小写（SQL 用），表本身必须全小写。
int cmpSlice(const char16_t* s, size_t len, const char* kw, bool fold) {
    size_t i = 0;
    for (; kw[i] != '\0'; ++i) {
        if (i >= len) return -1;  // 切片是关键词的真前缀 → 更小
        unsigned int a = static_cast<unsigned int>(s[i]);
        if (fold && a >= u'A' && a <= u'Z') a += 32;
        unsigned int b = static_cast<unsigned char>(kw[i]);
        if (a != b) return a < b ? -1 : 1;
    }
    return len == i ? 0 : 1;  // 关键词是切片的真前缀 → 切片更大
}

bool containsImpl(const char* const* table, size_t n, const char16_t* s, size_t len, bool fold) {
    size_t lo = 0;
    size_t hi = n;
    while (lo < hi) {
        size_t mid = lo + (hi - lo) / 2;
        int c = cmpSlice(s, len, table[mid], fold);
        if (c == 0) return true;
        if (c < 0) {
            hi = mid;
        } else {
            lo = mid + 1;
        }
    }
    return false;
}

template <size_t N>
inline bool contains(const char* const (&t)[N], const char16_t* s, size_t len, bool fold = false) {
    return containsImpl(t, N, s, len, fold);
}

// ---------------------------------------------------------------------------
// 输出
// ---------------------------------------------------------------------------

inline void emit(std::vector<int32_t>& out, size_t start, size_t end, int32_t type) {
    if (end <= start) return;
    out.push_back(static_cast<int32_t>(start));
    out.push_back(static_cast<int32_t>(end));
    out.push_back(type);
}

// ---------------------------------------------------------------------------
// 通用扫描原语。全部返回 "消费到的下标"（开区间上界）。
// ---------------------------------------------------------------------------

// 行注释：吃到 '\n' 之前（不含换行本身），对齐正则 `//[^\n]*`。
size_t scanToLineEnd(const char16_t* s, size_t len, size_t i) {
    while (i < len && s[i] != u'\n') ++i;
    return i;
}

// 找到 s[from..) 里第一个 a+b 二元序列，返回它之后的下标；找不到返回 len。
size_t skipPast2(const char16_t* s, size_t len, size_t from, char16_t a, char16_t b) {
    for (size_t j = from; j + 1 < len; ++j) {
        if (s[j] == a && s[j + 1] == b) return j + 2;
    }
    return len;
}

size_t skipPast3(const char16_t* s, size_t len, size_t from, char16_t a, char16_t b, char16_t c) {
    for (size_t j = from; j + 2 < len; ++j) {
        if (s[j] == a && s[j + 1] == b && s[j + 2] == c) return j + 3;
    }
    return len;
}

// 块注释 /* … */。i 指向 '/'。未闭合时吃到文本尾 —— 流式输出里代码块常常
// 只到一半，让它保持注释色比整段回退成普通色更符合直觉（这一点和原正则
// `\/\*[\s\S]*?\*\/` 不同：原实现未闭合时整条规则不匹配）。
size_t scanBlockComment(const char16_t* s, size_t len, size_t i) {
    return skipPast2(s, len, i + 2, u'*', u'/');
}

// 单/双引号字符串。i 指向开引号。
//  escapes=true  → 反斜杠转义下一个字符（对齐 `(?:\\.|[^"\\])*`）。
//  行尾强制终止 —— 未闭合的引号最多污染一行，不会把后面整个文件染绿。
//  （原正则允许跨行，但未闭合时整条不匹配；这里换成"到行尾为止"，
//   damage 更可控，也是编辑器的通行做法。）
size_t scanQuoted(const char16_t* s, size_t len, size_t i, char16_t quote, bool escapes) {
    size_t j = i + 1;
    while (j < len) {
        char16_t c = s[j];
        if (c == u'\n') return j;  // 未闭合
        if (escapes && c == u'\\') {
            if (j + 1 >= len) return len;
            if (s[j + 1] == u'\n') return j + 1;  // 反斜杠后直接换行，不当转义
            j += 2;
            continue;
        }
        ++j;
        if (c == quote) return j;
    }
    return len;
}

// 三引号字符串（Python 的 """ / '''，Kotlin/Java 的 """）。可跨行，
// 未闭合时吃到文本尾。i 指向第一个引号。
size_t scanTriple(const char16_t* s, size_t len, size_t i, char16_t quote) {
    return skipPast3(s, len, i + 3, quote, quote, quote);
}

// 反引号：JS 模板串 / Kotlin 转义标识符。可跨行。
size_t scanBacktick(const char16_t* s, size_t len, size_t i) {
    for (size_t j = i + 1; j < len; ++j) {
        if (s[j] == u'`') return j + 1;
    }
    return len;
}

// 标识符 run：[A-Za-z0-9_]* 的最长匹配。
inline size_t identEnd(const char16_t* s, size_t len, size_t i) {
    while (i < len && isIdentPart(s[i])) ++i;
    return i;
}

// `(?=\s*X)` 形式的前瞻：跳过空白后看下一个字符是不是 a 或 b。
// b 传 0 表示只看 a。整体仍是 O(n)：跳过的空白随后会被主循环再扫一遍，常数 2。
bool peekAfterSpaces(const char16_t* s, size_t len, size_t j, char16_t a, char16_t b) {
    while (j < len && isSpace(s[j])) ++j;
    if (j >= len) return false;
    return s[j] == a || (b != 0 && s[j] == b);
}

// ---- 数字 -----------------------------------------------------------------

enum NumFlavor {
    kNumPlain,  // \b\d+(\.\d+)?\b            —— python / sql
    kNumInt,    // \b\d+\b                    —— bash
    kNumCLike,  // \b\d+(\.\d+)?[FfLl]?\b     —— kotlin / js
    kNumCpp,    // 0[xX]hex+ | \d+(\.\d+)?[fFlLuU]*
    kNumJson,   // -?\b\d+(\.\d+)?\b
    kNumCss,    // \b-?\d+(\.\d+)?(px|em|rem|%|…)?\b
};

// i 指向数字（或 json/css 的前导 '-'）。合法则产出 number token。
// 不合法（形如 123abc / 0x1F 在非 C++ 语言里）时一个 token 都不产出，并把整个
// word run 一起吃掉 —— 对齐正则两端 \b 的语义：`\b\d+\b` 在 `123abc` 上无匹配。
size_t scanNumber(const char16_t* s, size_t len, size_t i, std::vector<int32_t>& out,
                  NumFlavor flavor) {
    size_t j = i;
    if ((flavor == kNumJson || flavor == kNumCss) && j < len && s[j] == u'-') ++j;

    bool hex = false;
    if (flavor == kNumCpp && j + 2 < len && s[j] == u'0' && (s[j + 1] == u'x' || s[j + 1] == u'X') &&
        isHexDigit(s[j + 2])) {
        hex = true;
        j += 2;
        while (j < len && isHexDigit(s[j])) ++j;
        // 0xFFu / 0x1FULL —— 十六进制也吃后缀（原正则不吃，`0xFFu` 整体不着色）。
        while (j < len && (s[j] == u'l' || s[j] == u'L' || s[j] == u'u' || s[j] == u'U')) ++j;
    } else {
        while (j < len && isDigit(s[j])) ++j;
        // 小数部分必须是 `.` + 至少一位数字，否则 `0..10` 这种 range 会被吃掉。
        if (flavor != kNumInt && j + 1 < len && s[j] == u'.' && isDigit(s[j + 1])) {
            j += 2;
            while (j < len && isDigit(s[j])) ++j;
        }
    }

    if (!hex) {
        switch (flavor) {
            case kNumCLike:
                if (j < len && (s[j] == u'F' || s[j] == u'f' || s[j] == u'L' || s[j] == u'l')) ++j;
                break;
            case kNumCpp:
                while (j < len && (s[j] == u'f' || s[j] == u'F' || s[j] == u'l' || s[j] == u'L' ||
                                   s[j] == u'u' || s[j] == u'U')) {
                    ++j;
                }
                break;
            case kNumCss: {
                for (const char* unit : kCssUnits) {
                    size_t k = j;
                    size_t u = 0;
                    while (unit[u] != '\0' && k < len &&
                           s[k] == static_cast<char16_t>(static_cast<unsigned char>(unit[u]))) {
                        ++u;
                        ++k;
                    }
                    if (unit[u] == '\0' && !(k < len && isIdentPart(s[k]))) {
                        j = k;
                        break;
                    }
                }
                break;
            }
            default:
                break;
        }
    }

    if (j < len && isIdentPart(s[j])) {
        // 结尾没有 \b（`123abc` / 非 C++ 语言里的 `0x1F`）—— 整个 run 一起吃掉，
        // 一个 token 都不产出。注意这里只看标识符字符，不看 '.'：Kotlin 的
        // `0..10` 必须仍然识别出 0 和 10 两个数字。
        while (j < len && isIdentPart(s[j])) ++j;
        return j;
    }
    emit(out, i, j, kTokenNumber);
    return j;
}

// ---------------------------------------------------------------------------
// kotlin / kt / java + javascript / ts / jsx / tsx
// ---------------------------------------------------------------------------

void lexCLike(const char16_t* s, size_t len, std::vector<int32_t>& out, bool kotlin) {
    size_t i = 0;
    while (i < len) {
        const char16_t c = s[i];
        if (c == u'/' && i + 1 < len) {
            if (s[i + 1] == u'/') {
                size_t e = scanToLineEnd(s, len, i);
                emit(out, i, e, kTokenComment);
                i = e;
                continue;
            }
            if (s[i + 1] == u'*') {
                size_t e = scanBlockComment(s, len, i);
                emit(out, i, e, kTokenComment);
                i = e;
                continue;
            }
            ++i;
            continue;
        }
        if (c == u'"') {
            // Kotlin / Java 文本块 """…"""（原正则会把它拆成三个空串，这里修正）。
            size_t e = (kotlin && i + 2 < len && s[i + 1] == u'"' && s[i + 2] == u'"')
                           ? scanTriple(s, len, i, u'"')
                           : scanQuoted(s, len, i, u'"', true);
            emit(out, i, e, kTokenString);
            i = e;
            continue;
        }
        if (c == u'\'') {
            size_t e = scanQuoted(s, len, i, u'\'', true);
            emit(out, i, e, kTokenString);
            i = e;
            continue;
        }
        if (c == u'`') {
            size_t e = scanBacktick(s, len, i);
            emit(out, i, e, kTokenString);
            i = e;
            continue;
        }
        if (isDigit(c)) {
            i = scanNumber(s, len, i, out, kNumCLike);
            continue;
        }
        if (isIdentStart(c)) {
            size_t e = identEnd(s, len, i);
            // 关键字优先于函数名：`if (`、`when (`、`catch (` 这类既是关键字又
            // 满足 `(?=\s*\()` 的 case，原实现两条 FILL 规则都会命中、后加的
            // 函数名色覆盖掉关键字色。单遍扫描里一个 token 只有一种类型，
            // 这里取关键字 —— 视觉上更接近编辑器的通行配色。
            const bool isKw = kotlin ? contains(kKotlin, s + i, e - i) : contains(kJs, s + i, e - i);
            if (isKw) {
                emit(out, i, e, kTokenKeyword);
            } else if (peekAfterSpaces(s, len, e, u'(', 0)) {
                emit(out, i, e, kTokenFunc);
            }
            i = e;
            continue;
        }
        ++i;
    }
}

// ---------------------------------------------------------------------------
// python / py
// ---------------------------------------------------------------------------

void lexPython(const char16_t* s, size_t len, std::vector<int32_t>& out) {
    size_t i = 0;
    while (i < len) {
        const char16_t c = s[i];
        if (c == u'#') {
            size_t e = scanToLineEnd(s, len, i);
            emit(out, i, e, kTokenComment);
            i = e;
            continue;
        }
        if (c == u'"' || c == u'\'') {
            size_t e = (i + 2 < len && s[i + 1] == c && s[i + 2] == c) ? scanTriple(s, len, i, c)
                                                                      : scanQuoted(s, len, i, c, true);
            emit(out, i, e, kTokenString);
            i = e;
            continue;
        }
        if (isDigit(c)) {
            i = scanNumber(s, len, i, out, kNumPlain);
            continue;
        }
        if (isIdentStart(c)) {
            size_t e = identEnd(s, len, i);
            if (contains(kPython, s + i, e - i)) {
                emit(out, i, e, kTokenKeyword);
            } else if (peekAfterSpaces(s, len, e, u'(', 0)) {
                emit(out, i, e, kTokenFunc);
            }
            i = e;
            continue;
        }
        ++i;
    }
}

// ---------------------------------------------------------------------------
// json —— 键（后面跟 ':' 的字符串）用 keyword 色，其余字符串用 string 色。
// ---------------------------------------------------------------------------

void lexJson(const char16_t* s, size_t len, std::vector<int32_t>& out) {
    size_t i = 0;
    while (i < len) {
        const char16_t c = s[i];
        if (c == u'"') {
            size_t e = scanQuoted(s, len, i, u'"', true);
            bool key = peekAfterSpaces(s, len, e, u':', 0);
            emit(out, i, e, key ? kTokenKeyword : kTokenString);
            i = e;
            continue;
        }
        if (isDigit(c) || (c == u'-' && i + 1 < len && isDigit(s[i + 1]))) {
            i = scanNumber(s, len, i, out, kNumJson);
            continue;
        }
        if (isIdentStart(c)) {
            size_t e = identEnd(s, len, i);
            if (contains(kJsonLit, s + i, e - i)) emit(out, i, e, kTokenKeyword);
            i = e;
            continue;
        }
        ++i;
    }
}

// ---------------------------------------------------------------------------
// bash / sh / shell / zsh
// ---------------------------------------------------------------------------

void lexBash(const char16_t* s, size_t len, std::vector<int32_t>& out) {
    size_t i = 0;
    // `${` 找不到闭合 '}' 时，同一行后面的每个 `${` 也一定找不到。记住这一行的
    // 终点，后续直接跳过扫描 —— 否则 "${${${${…" 这种输入会退化成 O(n²)。
    size_t noBraceUntil = 0;
    while (i < len) {
        const char16_t c = s[i];
        if (c == u'#') {
            size_t e = scanToLineEnd(s, len, i);
            emit(out, i, e, kTokenComment);
            i = e;
            continue;
        }
        if (c == u'"') {
            size_t e = scanQuoted(s, len, i, u'"', true);
            emit(out, i, e, kTokenString);
            i = e;
            continue;
        }
        if (c == u'\'') {
            // POSIX 单引号里没有转义，一路吃到下一个单引号（或行尾）。
            size_t e = scanQuoted(s, len, i, u'\'', false);
            emit(out, i, e, kTokenString);
            i = e;
            continue;
        }
        if (c == u'$' && i + 1 < len) {
            if (s[i + 1] == u'{') {
                if (i < noBraceUntil) {  // 本行已知没有闭合 '}'，不重复扫
                    ++i;
                    continue;
                }
                size_t j = i + 2;
                while (j < len && s[j] != u'}' && s[j] != u'\n') ++j;
                if (j < len && s[j] == u'}' && j > i + 2) {
                    emit(out, i, j + 1, kTokenKeyword);
                    i = j + 1;
                    continue;
                }
                noBraceUntil = j;
                ++i;
                continue;
            }
            if (isIdentStart(s[i + 1])) {
                size_t e = identEnd(s, len, i + 1);
                emit(out, i, e, kTokenKeyword);
                i = e;
                continue;
            }
            ++i;
            continue;
        }
        if (isDigit(c)) {
            i = scanNumber(s, len, i, out, kNumInt);
            continue;
        }
        if (isIdentStart(c)) {
            size_t e = identEnd(s, len, i);
            if (contains(kBash, s + i, e - i)) emit(out, i, e, kTokenKeyword);
            i = e;
            continue;
        }
        ++i;
    }
}

// ---------------------------------------------------------------------------
// sql —— 关键字大小写不敏感；字符串用 '' 表示转义单引号。
// ---------------------------------------------------------------------------

void lexSql(const char16_t* s, size_t len, std::vector<int32_t>& out) {
    size_t i = 0;
    while (i < len) {
        const char16_t c = s[i];
        if (c == u'-' && i + 1 < len && s[i + 1] == u'-') {
            size_t e = scanToLineEnd(s, len, i);
            emit(out, i, e, kTokenComment);
            i = e;
            continue;
        }
        if (c == u'/' && i + 1 < len && s[i + 1] == u'*') {
            size_t e = scanBlockComment(s, len, i);
            emit(out, i, e, kTokenComment);
            i = e;
            continue;
        }
        if (c == u'\'') {
            size_t j = i + 1;
            size_t e = len;
            while (j < len) {
                if (s[j] == u'\n') {
                    e = j;
                    break;
                }
                if (s[j] == u'\'') {
                    if (j + 1 < len && s[j + 1] == u'\'') {
                        j += 2;
                        continue;
                    }
                    e = j + 1;
                    break;
                }
                ++j;
            }
            if (j >= len) e = len;
            emit(out, i, e, kTokenString);
            i = e;
            continue;
        }
        if (isDigit(c)) {
            i = scanNumber(s, len, i, out, kNumPlain);
            continue;
        }
        if (isIdentStart(c)) {
            size_t e = identEnd(s, len, i);
            if (contains(kSql, s + i, e - i, /*fold=*/true)) emit(out, i, e, kTokenKeyword);
            i = e;
            continue;
        }
        ++i;
    }
}

// ---------------------------------------------------------------------------
// html / xml / svg / vue
//
// 和原实现一样是"上下文无关"的：不区分标签内/标签外，属性名规则在正文里
// 也会命中。内嵌 <script>/<style> 不做二次分派（不值得为聊天气泡里的片段
// 做 DOM 感知）。
// ---------------------------------------------------------------------------

bool matchAsciiCI(const char16_t* s, size_t len, size_t i, const char* word) {
    size_t k = 0;
    for (; word[k] != '\0'; ++k) {
        if (i + k >= len) return false;
        char16_t a = s[i + k];
        if (a >= u'A' && a <= u'Z') a = static_cast<char16_t>(a + 32);
        if (a != static_cast<char16_t>(static_cast<unsigned char>(word[k]))) return false;
    }
    return true;
}

void lexHtml(const char16_t* s, size_t len, std::vector<int32_t>& out) {
    size_t i = 0;
    while (i < len) {
        const char16_t c = s[i];
        if (c == u'<') {
            if (i + 3 < len && s[i + 1] == u'!' && s[i + 2] == u'-' && s[i + 3] == u'-') {
                size_t e = skipPast3(s, len, i + 4, u'-', u'-', u'>');
                emit(out, i, e, kTokenComment);
                i = e;
                continue;
            }
            if (i + 1 < len && s[i + 1] == u'!') {
                if (matchAsciiCI(s, len, i + 2, "doctype")) {
                    size_t j = i + 2;
                    while (j < len && s[j] != u'>') ++j;
                    size_t e = (j < len) ? j + 1 : len;
                    emit(out, i, e, kTokenPreprocessor);
                    i = e;
                    continue;
                }
                ++i;
                continue;
            }
            if (i + 1 < len && s[i + 1] == u'?') {
                size_t e = skipPast2(s, len, i + 2, u'?', u'>');
                emit(out, i, e, kTokenPreprocessor);
                i = e;
                continue;
            }
            // `<tag` / `</tag` —— 尖括号连同标签名一起算 keyword。
            size_t k = i + 1;
            if (k < len && s[k] == u'/') ++k;
            if (k < len && isAlpha(s[k])) {
                ++k;
                while (k < len && (isAlnum(s[k]) || s[k] == u'-')) ++k;
                emit(out, i, k, kTokenKeyword);
                i = k;
                continue;
            }
            ++i;
            continue;
        }
        if (c == u'/' && i + 1 < len && s[i + 1] == u'>') {
            emit(out, i, i + 2, kTokenKeyword);
            i += 2;
            continue;
        }
        if (c == u'>') {
            emit(out, i, i + 1, kTokenKeyword);
            ++i;
            continue;
        }
        if (c == u'"' || c == u'\'') {
            size_t e = scanQuoted(s, len, i, c, true);
            emit(out, i, e, kTokenString);
            i = e;
            continue;
        }
        if (c == u'&') {
            size_t k = i + 1;
            while (k < len && (isAlnum(s[k]) || s[k] == u'#')) ++k;
            if (k > i + 1 && k < len && s[k] == u';') {
                emit(out, i, k + 1, kTokenNumber);
                i = k + 1;
                continue;
            }
            i = (k > i + 1) ? k : i + 1;
            continue;
        }
        if (isIdentStart(c) || c == u':') {
            // 属性名：`[A-Za-z_:][A-Za-z0-9_.:-]*` 且必须紧跟 '='。
            size_t e = i + 1;
            while (e < len && (isIdentPart(s[e]) || s[e] == u'.' || s[e] == u':' || s[e] == u'-')) {
                ++e;
            }
            if (e < len && s[e] == u'=') emit(out, i, e, kTokenFunc);
            i = e;
            continue;
        }
        ++i;
    }
}

// ---------------------------------------------------------------------------
// css / scss / sass / less
// ---------------------------------------------------------------------------

void lexCss(const char16_t* s, size_t len, std::vector<int32_t>& out) {
    size_t i = 0;
    while (i < len) {
        const char16_t c = s[i];
        if (c == u'/' && i + 1 < len) {
            if (s[i + 1] == u'*') {
                size_t e = scanBlockComment(s, len, i);
                emit(out, i, e, kTokenComment);
                i = e;
                continue;
            }
            if (s[i + 1] == u'/') {  // SCSS / LESS
                size_t e = scanToLineEnd(s, len, i);
                emit(out, i, e, kTokenComment);
                i = e;
                continue;
            }
            ++i;
            continue;
        }
        if (c == u'"' || c == u'\'') {
            size_t e = scanQuoted(s, len, i, c, true);
            emit(out, i, e, kTokenString);
            i = e;
            continue;
        }
        if (c == u'@') {  // @media / @import / @keyframes …
            size_t k = i + 1;
            while (k < len && (isAlpha(s[k]) || s[k] == u'-')) ++k;
            if (k > i + 1) {
                emit(out, i, k, kTokenPreprocessor);
                i = k;
                continue;
            }
            ++i;
            continue;
        }
        if (c == u'#') {  // #rgb / #rrggbb / #rrggbbaa
            size_t k = i + 1;
            while (k < len && isHexDigit(s[k])) ++k;
            size_t n = k - i - 1;
            if (n >= 3 && n <= 8 && !(k < len && isIdentPart(s[k]))) {
                emit(out, i, k, kTokenNumber);
                i = k;
                continue;
            }
            ++i;
            continue;
        }
        // 数字的**左侧 \b**。CSS 是唯一需要显式检查它的语言：其它语言的标识符
        // run 含数字（identEnd 吃 [A-Za-z0-9_]），"abc123" 整体被当标识符消化掉，
        // 数字分支根本够不着；而这里的标识符 run 是 `[A-Za-z-]+`，遇到数字就停，
        // 于是 `h1` / `h2` / `nth-child` 里的数字会漏到数字分支。
        // 原正则 `\b-?\d+…` 在 `h1` 上因为 'h' 与 '1' 同为 \w 而**不匹配**，
        // 所以这里必须同样拒绝 —— 否则 `h1 { }` 这种最常见的选择器会把 '1'
        // 染成数字色，而降级到 Kotlin 版时又不染，两条路径肉眼可辨。
        // 同理 `-` 起头时 `\b` 落在 '-' 之前，要求前一个字符**是** \w：
        // `10-5px` / `.col-6` 命中（染 "-5px" / "-6"），而 `margin:-5px`
        // 不命中，只染 "5px"。
        if (isDigit(c)) {
            if (i > 0 && isIdentPart(s[i - 1])) {  // \b 失败 → 整段不着色
                ++i;
                continue;
            }
            i = scanNumber(s, len, i, out, kNumCss);
            continue;
        }
        if (c == u'-' && i + 1 < len && isDigit(s[i + 1]) && i > 0 && isIdentPart(s[i - 1])) {
            i = scanNumber(s, len, i, out, kNumCss);
            continue;
        }
        if (isAlpha(c) || c == u'-') {
            // 属性名 / 值标识符：`[A-Za-z-]+`（覆盖 -webkit-transform 这类）。
            size_t e = i;
            while (e < len && (isAlpha(s[e]) || s[e] == u'-')) ++e;
            // run 以 '-' 结尾且紧跟数字时把这个 '-' 让给数字分支：原实现里
            // 关键字/属性名规则和数字规则是**互相独立**的两条 FILL 正则，
            // `.col-6` 的 "-6" 归数字、`important-5` 的 "important" 归关键字。
            // 单遍扫描必须显式还原这个切分，否则 run 会把 '-' 吞掉。
            if (e - i >= 2 && s[e - 1] == u'-' && e < len && isDigit(s[e]) && isAlpha(s[e - 2])) {
                --e;
            }
            // 关键字规则是 `\b(?:important|…)\b`，两端都要 \b：run 停在 '_' / 数字
            // 上时（"important1"、"_important"）原正则不匹配，这里也必须拒绝。
            // 属性名规则 `[A-Za-z-]+(?=\s*:)` 没有 \b，所以 func 分支不做这个检查。
            const bool wordBoundary =
                (i == 0 || !isIdentPart(s[i - 1])) && !(e < len && isIdentPart(s[e]));
            if (wordBoundary && contains(kCssKw, s + i, e - i)) {
                emit(out, i, e, kTokenKeyword);
            } else if (peekAfterSpaces(s, len, e, u':', 0)) {
                emit(out, i, e, kTokenFunc);
            }
            i = e;
            continue;
        }
        ++i;
    }
}

// ---------------------------------------------------------------------------
// c / cpp / cc / cxx / h / hpp / objc
// ---------------------------------------------------------------------------

// C++ 字符字面量。严格对齐原正则 `'(?:\\.|[^'\\])'`：要么是反斜杠转义序列，
// 要么正好一个字符。宽松匹配会把 C++14 的数字分隔符 `1'000'000` 误判成字符串。
// 返回 0 表示不是字符字面量（调用方自行前进一格）。
size_t scanCharLiteral(const char16_t* s, size_t len, size_t i) {
    if (i + 2 >= len) return 0;
    if (s[i + 1] == u'\\') {
        // '\n' '\0' '\x41' 'é' …：最多往后看 10 个 code unit，不跨行。
        size_t limit = i + 12 < len ? i + 12 : len;
        for (size_t j = i + 3; j < limit; ++j) {
            if (s[j] == u'\n') return 0;
            if (s[j] == u'\'') return j + 1;
        }
        return 0;
    }
    if (s[i + 1] == u'\n' || s[i + 1] == u'\'') return 0;
    return s[i + 2] == u'\'' ? i + 3 : 0;
}

// C++11 原始字符串 R"delim( … )delim"。i 指向 'R'。返回 0 表示不是原始串。
size_t scanRawString(const char16_t* s, size_t len, size_t i) {
    size_t k = i + 2;  // 跳过 R"
    char16_t delim[17];
    size_t dn = 0;
    while (k < len && dn < 16 && s[k] != u'(' && s[k] != u'"' && s[k] != u'\\' && !isSpace(s[k])) {
        delim[dn++] = s[k];
        ++k;
    }
    if (k >= len || s[k] != u'(') return 0;
    size_t j = k + 1;
    while (j < len) {
        if (s[j] == u')') {
            size_t m = 0;
            while (m < dn && j + 1 + m < len && s[j + 1 + m] == delim[m]) ++m;
            if (m == dn && j + 1 + dn < len && s[j + 1 + dn] == u'"') return j + dn + 2;
        }
        ++j;
    }
    return len;  // 未闭合 → 吃到文本尾
}

void lexCpp(const char16_t* s, size_t len, std::vector<int32_t>& out) {
    size_t i = 0;
    bool bol = true;  // 当前行到目前为止只有空白 → '#' 可以起预处理指令
    while (i < len) {
        const char16_t c = s[i];
        // 预处理规则是 `^[ \t]*#[^\n]*` + MULTILINE，而 Java 的 MULTILINE `^`
        // 在**任何**行终止符之后都匹配，裸 '\r' 也算（"\r\n" 当作一个终止符，
        // 中间不匹配，但把两个都当行首得到的结果相同）。原来把 '\r' 归进
        // "行内空白" 会让 "int x;\r#define A" 里的 #define 不着预处理色，
        // 而 Kotlin 版会着 —— 混合换行的粘贴片段里就能看出来。
        if (c == u'\n' || c == u'\r') {
            bol = true;
            ++i;
            continue;
        }
        if (c == u' ' || c == u'\t') {
            ++i;
            continue;
        }
        if (bol && c == u'#') {  // ^[ \t]*#[^\n]*
            size_t e = scanToLineEnd(s, len, i);
            emit(out, i, e, kTokenPreprocessor);
            i = e;
            bol = false;
            continue;
        }
        bol = false;
        if (c == u'/' && i + 1 < len) {
            if (s[i + 1] == u'/') {
                size_t e = scanToLineEnd(s, len, i);
                emit(out, i, e, kTokenComment);
                i = e;
                continue;
            }
            if (s[i + 1] == u'*') {
                size_t e = scanBlockComment(s, len, i);
                emit(out, i, e, kTokenComment);
                i = e;
                continue;
            }
            ++i;
            continue;
        }
        if (c == u'R' && i + 1 < len && s[i + 1] == u'"') {
            size_t e = scanRawString(s, len, i);
            if (e != 0) {
                emit(out, i, e, kTokenString);
                i = e;
                continue;
            }
        }
        if (c == u'"') {
            size_t e = scanQuoted(s, len, i, u'"', true);
            emit(out, i, e, kTokenString);
            i = e;
            continue;
        }
        if (c == u'\'') {
            size_t e = scanCharLiteral(s, len, i);
            if (e != 0) {
                emit(out, i, e, kTokenString);
                i = e;
                continue;
            }
            ++i;
            continue;
        }
        if (isDigit(c)) {
            i = scanNumber(s, len, i, out, kNumCpp);
            continue;
        }
        if (isIdentStart(c)) {
            size_t e = identEnd(s, len, i);
            if (contains(kCpp, s + i, e - i)) {
                emit(out, i, e, kTokenKeyword);
            } else if (contains(kCppType, s + i, e - i)) {
                emit(out, i, e, kTokenType);
            } else if (peekAfterSpaces(s, len, e, u'(', u'<')) {
                emit(out, i, e, kTokenFunc);
            }
            i = e;
            continue;
        }
        ++i;
    }
}

}  // namespace

void highlight(const char16_t* s, size_t len, int32_t lang, std::vector<int32_t>& out) {
    if (s == nullptr || len == 0 || len > kMaxHighlightUnits) return;
    // 经验值：平均每 18 个 code unit 出一个 token（3 个 int）。预留一次，
    // 密集代码顶多再翻一两次倍，不会退化成逐个 push_back 扩容。
    out.reserve(out.size() + len / 6 + 24);
    switch (lang) {
        case kLangKotlin:
            lexCLike(s, len, out, /*kotlin=*/true);
            break;
        case kLangJs:
            lexCLike(s, len, out, /*kotlin=*/false);
            break;
        case kLangPython:
            lexPython(s, len, out);
            break;
        case kLangJson:
            lexJson(s, len, out);
            break;
        case kLangBash:
            lexBash(s, len, out);
            break;
        case kLangSql:
            lexSql(s, len, out);
            break;
        case kLangHtml:
            lexHtml(s, len, out);
            break;
        case kLangCss:
            lexCss(s, len, out);
            break;
        case kLangCpp:
            lexCpp(s, len, out);
            break;
        default:
            break;  // 未知语言 → 空结果，Kotlin 侧退回纯文本
    }
}

}  // namespace biji

// ---------------------------------------------------------------------------
// JNI 边界
//
//  - 标准命名导出，不用 RegisterNatives。
//  - 只收 jstring + jint，只返回 jintArray —— 一个 Java 类型都不需要 FindClass。
//  - 用 GetStringLength + GetStringRegion 取 UTF-16：Android 8 起 ART 用 8 位/字符
//    存 ASCII 串且 GC 会移动对象，GetStringCritical 早就不再是零拷贝，反而会挂起
//    GC；region 版本一次调用、不 pin、不阻塞，且拿到的同样是 UTF-16 code unit。
//    绝不用 GetStringUTFChars / NewStringUTF —— 那是 Modified UTF-8，offset 口径
//    会和 Kotlin String 下标错开。
//  - UTF-16 串没有 NUL 结尾且允许内嵌 U+0000，长度必须单独带着走。
//  - 任何失败都返回 null / 空数组，绝不把异常抛回 JVM。
// ---------------------------------------------------------------------------

static_assert(sizeof(jint) == sizeof(int32_t), "jint must be 32-bit");
static_assert(sizeof(jchar) == sizeof(char16_t), "jchar must be UTF-16 code unit");

extern "C" JNIEXPORT jintArray JNICALL Java_com_biji_notes_nativebridge_NativeHighlight_nHighlight(
        JNIEnv* env, jobject /* thiz: Kotlin object 的成员方法，收 jobject */, jstring code,
        jint langId) {
    if (env == nullptr || code == nullptr) return nullptr;

    const jsize len = env->GetStringLength(code);
    // 超长直接放弃：返回空数组（而不是 null），上层据此退回纯文本而不是再跑一遍
    // Kotlin 正则版 —— 那一版在这个规模上正是要避开的东西。
    if (len <= 0 || static_cast<size_t>(len) > biji::kMaxHighlightUnits) {
        jintArray empty = env->NewIntArray(0);
        if (empty == nullptr) env->ExceptionClear();
        return empty;
    }

    // -fno-exceptions：分配失败会 abort 而不是抛异常，所以上限检查必须在分配之前。
    // 短输入（编辑器里的绝大多数代码块起手、聊天里的小片段）走栈，零堆分配。
    jchar stackBuf[512];
    std::vector<jchar> heapBuf;
    jchar* buf = stackBuf;
    if (static_cast<size_t>(len) > sizeof(stackBuf) / sizeof(stackBuf[0])) {
        heapBuf.resize(static_cast<size_t>(len));
        buf = heapBuf.data();
    }
    env->GetStringRegion(code, 0, len, buf);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return nullptr;
    }

    std::vector<int32_t> spans;
    biji::highlight(reinterpret_cast<const char16_t*>(buf), static_cast<size_t>(len), langId, spans);

    jintArray arr = env->NewIntArray(static_cast<jsize>(spans.size()));
    if (arr == nullptr) {
        env->ExceptionClear();  // OOM 也不外泄给 JVM
        return nullptr;
    }
    if (!spans.empty()) {
        env->SetIntArrayRegion(arr, 0, static_cast<jsize>(spans.size()),
                               reinterpret_cast<const jint*>(spans.data()));
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return nullptr;
        }
    }
    return arr;
}
