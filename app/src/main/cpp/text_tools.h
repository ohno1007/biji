// =====================================================================
// text_tools.h — 文本工具三件套（UTF-16 直通，纯 offset / 纯字符串输出）
//
// 一比一替代三个 Kotlin 热点：
//   1. lineDiff        ← ui/editor/Diff.kt:48        （O(n·m) DP → Myers O(ND)）
//   2. syntaxIssues    ← ui/editor/SyntaxTransform.kt:76
//   3. latexToUnicode  ← ui/markdown/Math.kt:10
//
// 【硬性契约】
//  1. 输入是 UTF-16 code unit 序列（jchar / uint16_t），长度**单独传**。
//     UTF-16 不是 NUL 结尾、允许出现 U+0000 —— 任何地方都不得用 strlen 语义。
//     所有输出 offset 都是**原始输入串**的 UTF-16 下标，可以直接喂给
//     Kotlin String.substring / AnnotatedString。全程不做 UTF-8 转换。
//  2. 代理对安全：所有语法判定只对 ASCII(<0x80) 生效（唯一例外是
//     isKtWs 的空白判定，那些字符全部落在 BMP 非代理区 < 0xD800）。
//     高代理 0xD800..0xDBFF / 低代理 0xDC00..0xDFFF 一律被当作"普通字符"，
//     因此永远同属一类，token/行边界不可能落在代理对中间。
//  3. 纯函数、无全局可变状态、不起线程、不做跨调用缓存 —— 天然并发安全，
//     可被多个协程同时调用。
//  4. 不用异常（-fno-exceptions）、不用 RTTI（-fno-rtti）。
//     **所有长度上限必须在分配之前检查** —— 分配失败会直接 abort，catch 不到。
//  5. 出错一律返回失败/空结果，绝不把异常抛给 JVM，让 Kotlin 侧降级。
//
// 本头文件刻意不 include <jni.h>，方便在主机上编译做对拍测试
// （见 .cpp 末尾的 BIJI_TT_HOST_TEST）。
// =====================================================================
#ifndef BIJI_TEXT_TOOLS_H
#define BIJI_TEXT_TOOLS_H

#include <cstdint>
#include <vector>

namespace biji {
namespace tt {

// =====================================================================
// 1. 行级 diff
// =====================================================================
//
// 替代 Diff.kt 的 lineDiff()。原实现是 O(n·m) 时间 **且 O(n·m) 内存** 的
// LCS DP：`Array(n+1){IntArray(m+1)}`，4000×4000 行就是 64 MB 一次性分配，
// EditorScreen 那条路径（readFile maxBytes = 256 KB，约 6400 行）能直接
// 把手机打 OOM。这里换成 Myers O(ND) 的线性空间分治版：
//   - 内存 O(n+m)，10 万行级输入峰值只有几 MB；
//   - 每层先剥公共前后缀（相似文件的二次项直接消失）；
//   - 行先归一成稠密 int id（64 位 FNV-1a 哈希 + 内容校验，杜绝碰撞误判），
//     内层循环只比 int，不比字符串。
//
// 【返回布局】扁平 int 数组 = 4 个 int 的表头 + 每行一个三元组：
//   out[0] = kDiffFormatVersion
//   out[1] = flags（见 DiffFlag）
//   out[2] = 参与 diff 的 before 行数（截断后）
//   out[3] = 参与 diff 的 after  行数（截断后）
//   out[4 + 3i + 0] = op        （见 DiffOp）
//   out[4 + 3i + 1] = startUtf16（含）
//   out[4 + 3i + 2] = endUtf16  （不含）
//
// 【offset 指向哪个串】这是唯一容易记错的地方：
//   op == kDiffRemoved → 下标指向 **before**
//   op == kDiffCommon / kDiffAdded → 下标指向 **after**
// COMMON 取 after 侧是有意的：调用方渲染时通常只手里握着新文本，
// 而且两侧内容在 COMMON 行上逐 code unit 相同，取哪边都一样。
//
// 行的切分口径与 Kotlin 的 `String.split("\n")` 完全一致：
// 只按 '\n' 切，'\r' 留在行尾（CRLF 文件的每行末尾会带一个 '\r'），
// 末尾的 '\n' 之后还会产生一个空行；空串产生一行空行。
//
// 【与 Diff.kt 的等价性口径 —— 写对拍测试前必读】
// 最短编辑脚本**不唯一**。DP 版同分时的取舍是 `dp[i+1][j] >= dp[i][j+1]`
// （先删后加），Myers 分治的取舍来自中点切分，两者会给出不同但同样最短的
// 结果。已对拍验证的等价性是这三条，不是"逐行相同"：
//   1. kDiffRemoved + kDiffCommon 能逐 code unit 重建 before；
//   2. kDiffAdded  + kDiffCommon 能逐 code unit 重建 after；
//   3. kDiffCommon 的条数（= LCS 长度）与 DP 版完全一致 —— 于是
//      "+N 行 / -N 行" 的表头数字在两条路径上相同。
// 随机语料里约 1/3 的用例 hunk 对齐不同（同一处改动里 '-'/'+' 的先后、
// 或者哪几行算公共）。这与 git 和 diff(1) 之间的差异是同一类，不是 bug。
enum DiffOp : int32_t {
    kDiffCommon = 0,
    kDiffAdded = 1,
    kDiffRemoved = 2,
};

enum DiffFlag : int32_t {
    kDiffFlagNone = 0,
    // 输入行数/长度超限，只 diff 了前面一段（before 侧）
    kDiffFlagTruncatedBefore = 1 << 0,
    // 同上（after 侧）
    kDiffFlagTruncatedAfter = 1 << 1,
    // 计算预算耗尽，部分区段退化成"整段删+整段加"（结果仍然是合法 diff，
    // 只是不再是最短编辑脚本）。UI 可据此提示"差异过大，已简化显示"。
    kDiffFlagApproximate = 1 << 2,
    // 输出条目数触顶，后面的行没有输出
    kDiffFlagOutputCapped = 1 << 3,
};

// 表头长度（int 个数）。
constexpr int32_t kDiffHeaderInts = 4;
// 布局版本号。改布局必须 +1，并同步 NativeText.kt 的 DIFF_FORMAT_VERSION。
constexpr int32_t kDiffFormatVersion = 1;

// 单侧行数上限：超过就在行边界截断并置 truncated 标志。
// 20 万行 × 两侧的峰值内存约 15 MB（V 数组 + 行表 + 哈希桶 + 输出），
// 远好于原 DP 版在 4000 行就要的 64 MB。
constexpr int32_t kDiffMaxLines = 200000;
// 单侧 code unit 上限（约 8 MB 文本）。超过同样是截断而不是失败 ——
// 返回 null 会让调用方退回 Kotlin 的 O(n·m) 版，那才是真的会 OOM。
constexpr int32_t kDiffMaxUnits = 4000000;
// 输出条目上限（= 两侧行数上限之和）。
constexpr int32_t kDiffMaxEntries = 2 * kDiffMaxLines;
// 单个子问题允许的最大编辑距离 d。超过就用"最远推进点"启发式切分，
// 代价 O(d²)，这里 4096 对应约 1600 万次内层更新。
constexpr int32_t kDiffMaxD = 4096;
// 全局工作预算（内层 V 更新次数）。耗尽后剩余区段全部退化成整删整加，
// 保证最坏情况有硬性时间上界（arm64 上约几百毫秒），永远不会跑飞。
constexpr int64_t kDiffWorkBudget = 40000000;
// 单次中段搜索最少能花的预算。配合"每次最多花掉剩余预算的一半"，
// 保证近似模式下能做几十次切分，而不是被第一次调用一口气吃光。
constexpr int64_t kDiffMinCallWork = 400000;

// 把 a[0,alen) 与 b[0,blen) 做行级 diff，结果按上面的布局追加进 out。
// 返回 false 表示参数非法（out 内容未定义，调用方应放弃）；
// 只要返回 true，out 一定包含完整表头，且三元组按文件顺序排列。
// 纯函数，可并发调用。
bool lineDiff(const uint16_t* a, int32_t alen, const uint16_t* b, int32_t blen,
              std::vector<int32_t>& out);

// =====================================================================
// 2. 括号/引号配对检查
// =====================================================================
//
// 一比一移植 SyntaxTransform.kt:76 findSyntaxIssues()：
//   - 未配对的 (){}[] —— 每个各产生一个覆盖自身的 (i, i+1)；
//   - 行内未闭合的 ' / " —— 从引号到行尾；
//   - 跳过 // 行注释与 /* */ 块注释，字符串内不做括号统计。
//
// 与 Kotlin 版的等价性细节（改动前先读，都是"看起来像 bug 其实要保留"的）：
//   - 字符串里的 `\` 转义会连吃两个 code unit，**包括换行**：
//     `"abc\` + 换行 会继续吃到下一行，这与 Kotlin 版逐字符一致。
//   - 扫描结束后栈里剩下的未闭合左括号是**从栈顶往栈底**弹出的，
//     所以这部分输出的 offset 是递减的 —— 整个数组并非全局有序。
//     调用方只是给这些范围加波浪线，顺序无所谓，但对拍时要按这个顺序比。
//   - 输出是 [start, end) 开区间，而 Kotlin 返回的是闭区间 IntRange，
//     换算关系 end == range.last + 1（NativeText.kt 里做转换）。
//
// 输出：扁平 (start, endExclusive) 二元组，追加进 out。
constexpr int32_t kIssuesMaxUnits = 1000000;
// 最多报这么多处。真实代码不可能触顶（编辑器侧文件上限 256 KB）；
// 这个上限挡的是"整个文件都是左括号"这种对抗性输入的内存放大。
constexpr int32_t kIssuesMaxCount = 100000;

void syntaxIssues(const uint16_t* s, int32_t len, std::vector<int32_t>& out);

// =====================================================================
// 3. LaTeX → Unicode
// =====================================================================
//
// 对齐 Math.kt:13 latexToUnicodeImpl() 的语义，但把 55 趟全文扫描
// （37 次 GreekMap replace + 16 次操作符 replace + 5 趟 Regex）压成
// 表驱动的 9 趟单遍扫描，不用任何正则。
//
// 处理顺序**必须**与 Kotlin 一致（顺序会影响结果，见下方注释）：
//   ① trim + 剥掉外层 $$ / $
//   ② 具名命令（38 个希腊字母 + 16 个操作符）—— 最长匹配单遍替换
//   ③ \frac{X}{Y} → "X / Y"
//   ④ \sqrt{X}    → "√X"
//   ⑤ ^{X} → 上标 ；⑥ ^C → 上标 ；⑦ _{X} → 下标 ；⑧ _C → 下标
//   ⑨ 剩余 \命令 去掉反斜杠 ；⑩ \{ → { ，\} → } ，\\ → 换行
//
// 为什么 ② 可以把"希腊字母"和"操作符"两组合并成一趟：
//   Kotlin 是 54 次顺序 replace，等价于"先匹配到的先赢"；单遍最长匹配
//   只在两个模式于**同一位置**都能匹配时才可能不同。已穷举验证：54 个
//   模式里存在前缀包含关系的只有 le ⊂ leftarrow 这一对（见下）。
//   另外 ③④ 在 Kotlin 里夹在两组 replace 中间，但命令替换的产物全是
//   单个非 ASCII 字符、不含花括号也不含反斜杠，不可能影响 \frac/\sqrt
//   的匹配，反之亦然，所以调换先后不改变结果。
//
// 【与 Kotlin 版的两处已知差异 —— 都是有意为之的修正，别当 bug 改回去】
//   A. `\leftarrow`：Kotlin 先 replace("\\le") 再 replace("\\leftarrow")，
//      所以 `\leftarrow` 会被打成 `≤ftarrow`。本实现用最长匹配，输出 `←`。
//      （`\Leftarrow` 大小写不同，Kotlin 侧本来就是对的。）
//   B. `^{😀}` 这类上下标里的**代理对**：Kotlin 的 superscript() 逐
//      Char（= code unit）映射，会输出 `^`+高代理+`^`+低代理，把代理对
//      从中间劈开成两个孤代理。本实现把成对的代理一起搬运，输出
//      `^`+完整代理对。破坏代理对正是本项目最不能接受的失败模式。
//   除这两处外，对任意输入（含中文、emoji、U+0000、CRLF）输出应逐 code
//   unit 相同 —— 对拍时把这两类样本单独列出来即可。
constexpr int32_t kLatexMaxUnits = 100000;

// 结果写进 out（覆盖，不追加）。返回 false 表示输入超限/非法，
// 调用方应退回 Kotlin 实现。
bool latexToUnicode(const uint16_t* s, int32_t len, std::vector<uint16_t>& out);

}  // namespace tt
}  // namespace biji

#endif  // BIJI_TEXT_TOOLS_H
