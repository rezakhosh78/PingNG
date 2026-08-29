cmake_minimum_required(VERSION 3.22.1)

project(pingng_desync C)

set(CORE_DIR "PingNG")

file(GLOB CORE_SOURCES ${CORE_DIR}/*.c)
list(REMOVE_ITEM CORE_SOURCES ${CMAKE_CURRENT_SOURCE_DIR}/${CORE_DIR}/win_service.c)

add_library(PingNG SHARED ${CORE_SOURCES} native-lib.c utils.c)
target_include_directories(PingNG PRIVATE ${CORE_DIR})
target_compile_options(PingNG PRIVATE -std=c99 -O2 -D_XOPEN_SOURCE=500)
target_compile_definitions(PingNG PRIVATE ANDROID_APP)
target_link_libraries(PingNG PRIVATE android log)
