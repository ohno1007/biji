cmake_minimum_required(VERSION 3.22.1)
project(bijinative LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 所有 .cpp 自动纳入 —— 后续新增模块不用改这里。
file(GLOB BIJI_NATIVE_SOURCES "${CMAKE_CURRENT_SOURCE_DIR}/*.cpp")

add_library(bijinative SHARED ${BIJI_NATIVE_SOURCES})

target_compile_options(bijinative PRIVATE
    -O2
    -fvisibility=hidden
    -ffunction-sections
    -fdata-sections
    -fno-exceptions
    -fno-rtti
    -Wall)

target_link_options(bijinative PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)

find_library(log-lib log)
target_link_libraries(bijinative ${log-lib})
