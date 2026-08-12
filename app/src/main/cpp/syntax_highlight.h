// 语法高亮词法扫描器 —— 手写单遍状态机，替代 SyntaxHighlight.kt 的正则方案。
//
// 契约（三条硬约束，改动前先读）：
//  1. 全程 UTF-16。输入是 jchar/char16_t 序列，输出的每个 offset 都是
//     **UTF-16 code unit 下标**，和 Kotlin String.get(i) / AnnotatedString
//     的下标口径完全一致。任何地方都不得转成 UTF-8 再算 byte offset ——
//     中文注释和 emoji 会让所有偏移错位。
//  2. 代理对安全靠一条不变量保证：所有语法判定（字母/数字/引号/注释起始/
//     分隔符）只对 < 0x80 的 ASCII 生效，>= 0x80 的 code unit 一律当作
//     "普通文本字符"。高代理 0xD800-0xDBFF 和低代理 0xDC00-0xDFFF 都 >= 0x80，
//     于是永远同属一类，token 边界不可能落在代理对中间。
//  3. 纯函数、无全局可变状态、不分配静态缓存 —— 可被多个协程并发调用。
//     关键词表是 constexpr 静态只读数组，查表走二分。
//
// 本头文件刻意不 include <jni.h>，方便在主机上编译做对拍测试。
//
// =====================================================================
// 与 SyntaxHighlight.kt 的**全部**已知渲染差异（对拍结论，改动前先读）
// =====================================================================
// 目标是"视觉等价"而不是逐 span 一致。在**格式正确**的真实代码上，逐
// code unit 的最终着色只在下面 1、2 两条上不同；其余各条只在畸形 / 流式
// 半截输入上出现。（对拍方法：把 colorizeImpl 的 CLAIM/FILL 两趟逐条移植
// 成 Java，把两边的结果都归约成"每个 code unit 的最终 token 类型"再比。）
//
//  1. 关键字 / 类型优先于函数名。原实现的关键字规则与 `(?=\s*\()` 函数名
//     规则都是 FILL 且互不 claim，`if (` / `when (` / `catch (` / `for (`
//     两条都命中，后加的函数名色覆盖关键字色（渲染成粉色）。单遍扫描里一个
//     token 只有一种类型，这里取关键字。C++ 的 `vector<` / `template <` /
//     `cout <<` 同理取 type/keyword 而非 func。这是真实代码上最主要的差异。
//  2. CSS 的 `%` 单位。原正则 `…(?:px|em|…|%|…)?\b` 里 `%` 后面永远接不上
//     `\b`（'%' 与其后的 ';' 都不是 \w），所以 `50%` 只染 "50"。这里染 "50%"。
//  3. 未闭合结构的方向反过来了。原正则 `/\*[\s\S]*?\*/` 未闭合时整条不匹配
//     （流式输出到一半的代码块完全不着色）；这里吃到文本尾。三引号 / 反引号 /
//     HTML 注释 / C++ 原始串同理。
//  4. 单双引号字符串在**行尾**强制终止（原正则允许跨行）。一个漏掉的引号最多
//     污染一行。跨行能力保留给 `"""` / `'''` / 反引号 / 块注释。
//  5. 修掉的两个原实现 bug：`<?xml version="1.0"?>` 原来因为字符串规则先
//     claim 掉 `"1.0"` 导致整条 `<?…?>` 规则被跳过；Kotlin/Java 的
//     `"""…"""` 原来被拆成三个空串。
//  6. 小幅超集：Kotlin/Java 文本块 `"""`、C++ 原始串的一般形式
//     `R"delim(…)delim"`、十六进制后缀 `0xFFu`（原来整体不着色）。
//  7. 不复刻病态回溯：`1.5x` 原正则会回溯出一个数字 "1"，这里整体不着色。
//     指数记数法 `1e9` 两边都不支持。
//  8. 预处理指令 `^[ \t]*#…` 的 claim 区间不含行首空白（原正则含，但那是
//     空白，没有可见差异）。
//
// 反过来说，下面这些**曾经**是差异、现在已经对齐，不要"顺手简化"回去：
//  * CSS 数字的左侧 `\b`：`h1` / `h2` / `nth-child(2)` 里的数字**不着色**
//    （见 lexCss 里的 isIdentPart 前置检查）。这是最常见的选择器写法。
//  * CSS 负号归属：`.col-6` / `10-5px` 染 "-6" / "-5px"，而 `margin:-5px`
//    只染 "5px"（`\b` 落在 '-' 之前，要求前一个字符是 \w）。
//  * CSS 关键字两端的 `\b`：`important1` / `_important` 不算关键字。
//  * C++ 预处理指令在裸 '\r' 之后也成立（Java MULTILINE 的 `^` 匹配任何
//    行终止符之后，不只是 '\n'）。
#ifndef BIJI_SYNTAX_HIGHLIGHT_H
#define BIJI_SYNTAX_HIGHLIGHT_H

#include <cstddef>
#include <cstdint>
#include <vector>

namespace biji {

// token 类型 —— 与 SyntaxHighlight.kt 的 Palette 字段一一对应。
// native 只吐语义 id，颜色/字重/斜体全部留在 Kotlin 侧，
// 亮暗主题切换不需要重跑 native。
enum TokenType : int32_t {
    kTokenComment = 0,
    kTokenString = 1,
    kTokenNumber = 2,
    kTokenKeyword = 3,
    kTokenFunc = 4,
    kTokenType = 5,
    kTokenPreprocessor = 6,
};

// 语言 id —— 别名（kt/kotlin/java …）到 id 的映射留在 Kotlin 侧，
// native 只认基本类型，不需要读任何 Java 字符串。
// 这些数值是跨 JNI 的稳定契约，只能追加不能重排。
enum LangId : int32_t {
    kLangNone = 0,
    kLangKotlin = 1,  // kotlin, kt, java
    kLangJs = 2,      // javascript, js, jsx, mjs, cjs, typescript, ts, tsx
    kLangPython = 3,  // python, py
    kLangJson = 4,    // json
    kLangBash = 5,    // bash, sh, shell, zsh
    kLangSql = 6,     // sql
    kLangHtml = 7,    // html, htm, xml, svg, vue
    kLangCss = 8,     // css, scss, sass, less
    kLangCpp = 9,     // c, cpp, cc, cxx, h, hpp, hh, objc …
};

// 超过这个长度直接放弃高亮（返回空结果，上层退回纯文本）。
// 单遍扫描是 O(n)，这里的上限是防止一次分配出几 MB 的 span 数组，
// 而不是防止扫描本身慢。
constexpr size_t kMaxHighlightUnits = 200000;

// 单遍扫描 s[0, len) ，把 (start, end, tokenType) 三元组依次追加进 out。
//  - start/end 都是 UTF-16 code unit 下标，end 为开区间上界。
//  - 输出天然按 start 升序、互不重叠 —— 单遍扫描保证同一个 code unit
//    只会被一个 token 覆盖，所以 Kotlin 侧不需要再做去重/覆盖。
//  - "CLAIM 优先" 语义由扫描顺序天然满足：字符串/注释一旦开始就一路吃到
//    结束，内部的关键字/数字/括号永远不会被单独识别。
//  - lang 不认识、len 为 0、或 len > kMaxHighlightUnits 时 out 保持不变。
// 纯函数：不读写任何全局状态，可并发调用。
void highlight(const char16_t* s, size_t len, int32_t lang, std::vector<int32_t>& out);

}  // namespace biji

#endif  // BIJI_SYNTAX_HIGHLIGHT_H
