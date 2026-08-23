# =====================================================================
# biji native 层构建脚本
#
# 产物：libbijinative.so（仅 arm64-v8a，见 app/build.gradle.kts 的 abiFilters）
#
# 源文件收集策略：**GLOB + 必需文件断言**，故意不写死完整列表。
#   理由：本项目的 native 模块由多个并行的 agent 分别产出，各自只被允许写
#   自己的 .cpp/.h。如果这里维护一份显式清单，每加一个模块就要改这个文件，
#   必然产生互相冲突的编辑。GLOB 让「加文件 = 自动参与编译」，谁都不用碰它。
#
#   GLOB 的经典毛病是增量构建时不重新扫描（新增文件不参与编译，表现为
#   UnsatisfiedLinkError，极难查）。CONFIGURE_DEPENDS（CMake >= 3.12）让
#   构建系统在每次 build 时重新 glob，正好消掉这个坑。
#
#   同时用 BIJI_REQUIRED_SOURCES 断言四个核心模块必须存在：GLOB 漏掉文件时
#   在 configure 阶段就大声失败，而不是等到运行期 dlopen 找不到符号。
# =====================================================================
cmake_minimum_required(VERSION 3.22.1)
project(bijinative LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# ---------------------------------------------------------------------
# 源文件
# ---------------------------------------------------------------------
file(GLOB BIJI_NATIVE_SOURCES CONFIGURE_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/*.cpp")

if(NOT BIJI_NATIVE_SOURCES)
    message(FATAL_ERROR "bijinative: 在 ${CMAKE_CURRENT_SOURCE_DIR} 下没有找到任何 .cpp")
endif()

# 核心模块清单。少任何一个都不该产出一个「能加载但缺方法」的 .so ——
# 那会让 Kotlin 侧的 available == true 却在首次调用时 UnsatisfiedLinkError，
# 绕过整套降级逻辑。
set(BIJI_REQUIRED_SOURCES
    jni_common.cpp          # 共享 JNI 基础设施（UTF-16 取串 / 数组构造 / 异常闸门）
    markdown_parser.cpp     # NativeMarkdown.nParseBlocks
    syntax_highlight.cpp    # NativeHighlight.nHighlight
    text_tools.cpp          # NativeText.nLineDiff / nSyntaxIssues / nLatexToUnicode
    pty.cpp                 # NativePty.nCreateSubprocess / nSetWinSize / nWaitFor / ...
)
foreach(_req IN LISTS BIJI_REQUIRED_SOURCES)
    if(NOT EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/${_req}")
        message(FATAL_ERROR
            "bijinative: 缺少必需源文件 ${_req}。"
            " 若该模块尚未落地，请先放一个空实现占位，不要让库带着缺失的导出符号发布。")
    endif()
endforeach()

add_library(bijinative SHARED ${BIJI_NATIVE_SOURCES})

target_include_directories(bijinative PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}")

# ---------------------------------------------------------------------
# 编译选项
# ---------------------------------------------------------------------
target_compile_options(bijinative PRIVATE
    -O2                     # 体积/速度平衡点；-O3 对这些扫描型循环没有可测收益
    -fvisibility=hidden     # 默认隐藏符号 —— JNI 入口必须显式写 JNIEXPORT，
                            # 漏写 = 运行期 UnsatisfiedLinkError
    -fvisibility-inlines-hidden
    -ffunction-sections     # 配合下面的 --gc-sections 才能真正削体积
    -fdata-sections
    -fno-exceptions         # 约束：不用异常跨 JNI 边界。注意副作用 ——
                            # 分配失败变成 abort 而非抛异常，所以所有模块
                            # 必须「先查长度上限、再分配」，大块分配用
                            # new (std::nothrow)（见 jni_common.cpp）
    -fno-rtti               # 不能 dynamic_cast/typeid；lexer 用 switch 分发
    -Wall
    -Wextra
    -Wno-unused-parameter   # JNI 签名里的 jobject thiz 一律用不到
)

# ---------------------------------------------------------------------
# 链接选项
# ---------------------------------------------------------------------
target_link_options(bijinative PRIVATE
    -Wl,--gc-sections           # 丢弃没被引用的 section（配 -ffunction-sections）
    -Wl,--exclude-libs,ALL      # 不把静态库的符号再导出一遍
    -Wl,--no-undefined          # 未定义符号在链接期就报错，而不是 dlopen 时
    -Wl,-z,max-page-size=16384  # 兼容 16 KB page size 设备（Android 15+ 的 arm64
                                # 新要求；不加的话在这些设备上 .so 直接加载失败）
)

# Release 才 strip：debug 包留符号，native crash 的 backtrace 才有意义。
target_link_options(bijinative PRIVATE $<$<CONFIG:Release>:-Wl,--strip-all>)

# __android_log_print 用；模块里只在真出错时打日志，热路径不打。
find_library(log-lib log)
target_link_libraries(bijinative ${log-lib})

# ---------------------------------------------------------------------
# 构建期自检
# ---------------------------------------------------------------------
# 这个库的算法全部以 UTF-16 code unit 为单位（Kotlin String 下标口径）。
# jchar 不是 16 位的话所有 reinterpret_cast 都不成立 —— 各模块里也有
# static_assert，这里再挡一道，configure 阶段就能发现工具链异常。
if(NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(WARNING
        "bijinative: 当前 ABI = '${ANDROID_ABI}'，而 build.gradle.kts 的 abiFilters"
        " 只保留 arm64-v8a。非 arm64 的产物不会被打进 APK。")
endif()

message(STATUS "bijinative: ABI=${ANDROID_ABI} 源文件数=${CMAKE_CURRENT_SOURCE_DIR}")
foreach(_src IN LISTS BIJI_NATIVE_SOURCES)
    get_filename_component(_name "${_src}" NAME)
    message(STATUS "bijinative:   + ${_name}")
endforeach()
