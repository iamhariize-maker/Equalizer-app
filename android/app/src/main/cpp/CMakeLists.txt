cmake_minimum_required(VERSION 3.18)
project(eqjni LANGUAGES CXX)

# Shared DSP core lives at the repo root: core/
add_subdirectory(${CMAKE_CURRENT_LIST_DIR}/../../../../../core ${CMAKE_BINARY_DIR}/eqcore)

add_library(eqjni SHARED jni_bridge.cpp)
target_link_libraries(eqjni PRIVATE eqcore log)
# Release-grade optimisation even in debug APKs: DSP at -O0 can't keep up in 8x mode.
target_compile_options(eqjni PRIVATE -O2)
target_compile_options(eqcore PRIVATE -O2)
