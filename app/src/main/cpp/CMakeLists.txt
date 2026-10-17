cmake_minimum_required(VERSION 3.22.1)

project(pingng_desync C ASM)

set(CORE_DIR "PingNG")

file(GLOB CORE_SOURCES ${CORE_DIR}/*.c)
list(REMOVE_ITEM CORE_SOURCES ${CMAKE_CURRENT_SOURCE_DIR}/${CORE_DIR}/win_service.c)

add_library(PingNG SHARED ${CORE_SOURCES} native-lib.c utils.c)
target_include_directories(PingNG PRIVATE ${CORE_DIR})
target_compile_options(PingNG PRIVATE -std=c99 -O2 -D_XOPEN_SOURCE=500)
target_compile_definitions(PingNG PRIVATE ANDROID_APP)
target_link_libraries(PingNG PRIVATE android log)

# HEV TUN is built as a real JNI shared library. The previous package used
# standalone HEV executables renamed to .so, which cannot be loaded by JNI.
set(HEV_DIR "hev-socks5-tunnel")
file(GLOB_RECURSE HEV_SOURCES CONFIGURE_DEPENDS
    "${HEV_DIR}/src/*.c"
    "${HEV_DIR}/third-part/yaml/src/*.c"
    "${HEV_DIR}/third-part/lwip/src/*.c"
)
file(GLOB_RECURSE HEV_TASK_SOURCES CONFIGURE_DEPENDS
    "${HEV_DIR}/third-part/hev-task-system/src/*.c"
)
# Compile only the dispatcher assembly. It selects the correct architecture
# implementation through the preprocessor; globbing *.S on Windows is
# case-insensitive and would incorrectly compile every other architecture's
# lowercase .s file as well.
list(APPEND HEV_TASK_SOURCES
    "${HEV_DIR}/third-part/hev-task-system/src/kern/task/hev-task-execute.S"
)
# HEV already ships its own list/rbtree implementations. The task-system
# copy exposes the same symbols, so compiling both causes duplicate-symbol
# linker failures with modern lld.
list(FILTER HEV_TASK_SOURCES EXCLUDE REGEX "/lib/list/hev-list\\.c$")
list(FILTER HEV_TASK_SOURCES EXCLUDE REGEX "/lib/rbtree/hev-rbtree\\.c$")
list(APPEND HEV_SOURCES ${HEV_TASK_SOURCES})

add_library(hev-socks5-tunnel SHARED ${HEV_SOURCES})
target_include_directories(hev-socks5-tunnel PRIVATE
    "${HEV_DIR}/src"
    "${HEV_DIR}/src/misc"
    "${HEV_DIR}/src/core/include"
    "${HEV_DIR}/third-part/yaml/include"
    "${HEV_DIR}/third-part/lwip/src/include"
    "${HEV_DIR}/third-part/lwip/src/ports/include"
    "${HEV_DIR}/third-part/hev-task-system/src"
    "${HEV_DIR}/third-part/hev-task-system/include"
)
target_compile_definitions(hev-socks5-tunnel PRIVATE
    ANDROID
    ENABLE_LIBRARY
    FD_SET_DEFINED
    SOCKLEN_T_DEFINED
    PKGNAME=com/v2ray/ang/service
    CLSNAME=TProxyService
    YAML_VERSION_MAJOR=0
    YAML_VERSION_MINOR=2
    YAML_VERSION_PATCH=5
    YAML_VERSION_STRING=\"0.2.5\"
    ENABLE_STACK_OVERFLOW_DETECTION
    ENABLE_MEMALLOC_SLICE
    ENABLE_IO_SPLICE_SYSCALL
    CONFIG_STACK_BACKEND=STACK_MMAP
    CONFIG_STACK_OVERFLOW_DETECTION=1
    CONFIG_MEMALLOC_SLICE_ALIGN=16
    CONFIG_MEMALLOC_SLICE_MAX_SIZE=4096
    CONFIG_MEMALLOC_SLICE_MAX_COUNT=1000
    CONFIG_SCHED_CLOCK=CLOCK_NONE
)
target_compile_options(hev-socks5-tunnel PRIVATE -std=gnu99 -O2)
target_link_options(hev-socks5-tunnel PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)
target_link_libraries(hev-socks5-tunnel PRIVATE android log)
