// =====================================================================
// markdown_parser.h — 块级 Markdown 解析器（UTF-16 直通，纯 offset 输出）
//
// 目标：一比一替代 ui/markdown/Markdown.kt 里的 private fun parseBlocks()。
//
// 【硬性契约】
//  1. 输入是 UTF-16 code unit 序列（jchar* / uint16_t*），长度单独传。
//     UTF-16 不是 NUL 结尾，且允许出现 U+0000 —— 任何地方都不得使用
//     strlen 语义。
//  2. 所有输出 offset 都是 **原始输入串** 的 UTF-16 下标，可以直接喂给
//     Kotlin String.substring / AnnotatedString。全程不做 UTF-8 转换。
//  3. 所有语法判定只对 ASCII(<0x80) 生效，唯一的例外是空白判定
//     （见 isKtWs），而那些字符全部是非代理区的 BMP 字符。因此代理对
//     (0xD800..0xDFFF) 永远被当作"普通文本字符"，token 边界不可能落在
//     代理对中间 —— 中文 / emoji / 组合字符自动正确。
//  4. 无全局可变状态、不起线程、不分配跨调用的资源 —— 纯函数，天然并发安全。
//  5. 不抛异常（-fno-exceptions），不用 RTTI（-fno-rtti）。输入超限直接
//     返回失败，让调用方降级。
//
// 【与 Kotlin 版的一个关键等价性】
//  Kotlin 先做 source.replace("\r\n", "\n") 再 split("\n")。这会改变
//  offset。本实现不做任何字符串重写：改为在切行时，如果一行是被 "\r\n"
//  终结的，就把行尾那个 '\r' 排除在 raw 范围外。这样每行的 raw 内容与
//  Kotlin 的 lines[i] 逐字符相同，而 offset 仍然指向原始串。
//  （末尾没有 '\n' 收尾的残段保留其 '\r'，与 replace 的行为一致。）
// =====================================================================
#ifndef BIJI_MARKDOWN_PARSER_H
#define BIJI_MARKDOWN_PARSER_H

#include <cstdint>
#include <vector>

namespace biji {
namespace md {

// 与 Kotlin 侧 NativeMarkdown.TYPE_* 一一对应，改动必须两边同步。
enum BlockType : int32_t {
    BT_BLANK     = 0,   // Block.Blank
    BT_DIVIDER   = 1,   // Block.Divider
    BT_HEADING   = 2,   // Block.Heading(level=arg0)          spans: [text]
    BT_PARAGRAPH = 3,   // Block.Paragraph                    spans: 每行一段，Kotlin 用 '\n' 拼
    BT_BULLET    = 4,   // Block.BulletItem(depth=arg0)       spans: [text]
    BT_TASK      = 5,   // Block.TaskItem(depth=arg0, checked=arg1) spans: [text]
    BT_NUMBERED  = 6,   // Block.NumberedItem(depth=arg0, number=arg1) spans: [text]
    BT_QUOTE     = 7,   // Block.Quote                        spans: 每行一段 -> List<String>
    BT_CODE      = 8,   // Block.CodeBlock                    spans: [lang, body 行...]
    BT_MERMAID   = 9,   // Block.Mermaid                      spans: body 行...
    BT_MATH      = 10,  // Block.MathBlock                    spans: body 行...
    BT_TABLE     = 11,  // Block.Table(rowCount=arg0)         spans: 所有单元格（行 0 = header）
                        //                                    extra: 每行的单元格数，共 rowCount 个
};

// 打包格式常量 —— Kotlin 侧 NativeBlocks 必须用同一套。
constexpr int32_t kMagic       = 0x424D4B31;  // 'BMK1'
constexpr int32_t kVersion     = 1;
constexpr int32_t kHeaderInts  = 8;
constexpr int32_t kBlockStride = 8;

// 输入上限（UTF-16 code unit）。-fno-exceptions 下 vector 分配失败是
// abort 而不是抛异常，所以上限检查必须发生在任何分配之前。
constexpr int32_t kMaxInput = 4 * 1000 * 1000;

struct ParseOut {
    std::vector<int32_t> blocks;  // 每 kBlockStride 个 int 一个 block
    std::vector<int32_t> spans;   // 每 2 个 int 一对 (startUtf16, endUtf16)
    std::vector<int32_t> extra;   // 变长整型附加区（目前只有表格的每行列数）
};

// 纯解析。s 可以为 nullptr（当 len == 0）。len < 0 或 > kMaxInput 时
// 返回 false 且不修改 out。
bool parse(const uint16_t* s, int32_t len, ParseOut& out);

// 打包成单个自描述 int 数组：
//   [0] kMagic
//   [1] kVersion
//   [2] blockCount
//   [3] blockOff      (= kHeaderInts)
//   [4] spanCount     (对数，不是 int 数)
//   [5] spanOff
//   [6] extraCount
//   [7] extraOff
// 之后依次是 blocks 区、spans 区、extra 区。
//
// block 记录（kBlockStride = 8 个 int）：
//   [0] type
//   [1] arg0        level | depth | table rowCount
//   [2] arg1        number | checked
//   [3] spanStart   spans 区里的**对**下标
//   [4] spanCount   对数
//   [5] extraStart  extra 区下标
//   [6] extraCount
//   [7] 保留 = 0
std::vector<int32_t> pack(const ParseOut& out);

}  // namespace md
}  // namespace biji

#endif  // BIJI_MARKDOWN_PARSER_H
