cmake_minimum_required(VERSION 3.20)
project(ets2_phone_plugin LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(ets2_phone_plugin SHARED
    src/ets2_phone_plugin.cpp
    src/protocol.h
)

target_include_directories(ets2_phone_plugin PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/src
    ${CMAKE_CURRENT_SOURCE_DIR}/include/scssdk
)

target_compile_options(ets2_phone_plugin PRIVATE /O2 /W4 /DNDEBUG)
target_link_libraries(ets2_phone_plugin PRIVATE ws2_32 iphlpapi)