cmake_minimum_required(VERSION 3.18.1)
project(edlsec C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(edlsec SHARED
    edlsec.cpp
    detect.cpp
    apk_cert.c
    sha256.c
    edlcipher.c
    vm.c)

# 隐藏全部符号（仅 JNI_OnLoad 导出）、按段裁剪、关闭 C++ 异常/RTTI 缩小可逆面。
target_compile_options(edlsec PRIVATE
    -Os
    -fvisibility=hidden
    -fvisibility-inlines-hidden
    -ffunction-sections
    -fdata-sections
    -fno-unwind-tables
    -fno-asynchronous-unwind-tables
    $<$<COMPILE_LANGUAGE:CXX>:-fno-exceptions>
    $<$<COMPILE_LANGUAGE:CXX>:-fno-rtti>)

target_link_options(edlsec PRIVATE
    -Wl,--gc-sections
    -Wl,--exclude-libs,ALL
    -Wl,-z,noexecstack)

# Gradle 任务把含真实证书 hash / 加密 secret 的 edlsec_gen.h 生成到 EDLSEC_GEN_DIR。
# 该目录置于 include 最前：编译期优先取生成头，缺失则回退源码目录的占位头（可独立编译）。
# 不在 configure 期做 EXISTS 判断——生成任务在编译(buildCMake)前完成即可，解析发生在 -I 顺序。
if(DEFINED EDLSEC_GEN_DIR)
    target_include_directories(edlsec BEFORE PRIVATE "${EDLSEC_GEN_DIR}")
endif()
target_include_directories(edlsec PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}")

find_library(log-lib log)
target_link_libraries(edlsec ${log-lib})
