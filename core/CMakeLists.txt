cmake_minimum_required(VERSION 3.18)
project(eqcore LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if(NOT CMAKE_BUILD_TYPE AND NOT CMAKE_CONFIGURATION_TYPES)
  set(CMAKE_BUILD_TYPE Release)
endif()

# Top-level build (host tests/bench) vs. consumed from the Android project.
if(CMAKE_SOURCE_DIR STREQUAL CMAKE_CURRENT_SOURCE_DIR)
  set(EQCORE_IS_TOP_LEVEL ON)
else()
  set(EQCORE_IS_TOP_LEVEL OFF)
endif()
option(EQCORE_BUILD_TESTS "Build eqcore unit tests and benchmark" ${EQCORE_IS_TOP_LEVEL})
set(EQCORE_SANITIZE "" CACHE STRING "Sanitizers, e.g. address,undefined or thread")

add_library(eqcore STATIC
  src/graphic_eq.cpp
  src/biquad.cpp
  src/parametric_eq.cpp
  src/oversampler.cpp
  src/resampler.cpp
  src/bass.cpp
  src/tuning.cpp
  src/calibration.cpp
  src/comparison.cpp
  src/stereo.cpp
  src/spatial.cpp
  src/bass_unmask.cpp
  src/policy.cpp
  src/dither.cpp
  src/autoeq.cpp
  src/true_peak.cpp
  src/dynamic_eq.cpp
  src/grounding.cpp
  src/engine.cpp
  src/analyzer.cpp
  src/svaramanas.cpp)
target_include_directories(eqcore PUBLIC include)
if(CMAKE_CXX_COMPILER_ID MATCHES "GNU|Clang")
  # Deliberately no -ffast-math: it would change NaN/denormal behaviour and
  # reassociate the filter math we test against analytic responses.
  target_compile_options(eqcore PRIVATE -Wall -Wextra -Wpedantic)
endif()
find_package(Threads REQUIRED)
target_link_libraries(eqcore PUBLIC Threads::Threads)

if(EQCORE_SANITIZE)
  target_compile_options(eqcore PUBLIC -fsanitize=${EQCORE_SANITIZE} -fno-omit-frame-pointer -g)
  target_link_options(eqcore PUBLIC -fsanitize=${EQCORE_SANITIZE})
endif()

if(EQCORE_BUILD_TESTS)
  enable_testing()
  add_executable(eqcore_tests tests/test_main.cpp)
  target_link_libraries(eqcore_tests PRIVATE eqcore)
  target_compile_definitions(eqcore_tests PRIVATE EQCORE_TEST_DATA_DIR="${CMAKE_CURRENT_SOURCE_DIR}/tests/data" EQCORE_REPO_DIR="${CMAKE_CURRENT_SOURCE_DIR}/..")
  add_test(NAME eqcore_tests COMMAND eqcore_tests)

  add_executable(eqcore_policy_doc tools/policy_doc.cpp)
  target_link_libraries(eqcore_policy_doc PRIVATE eqcore)

  add_executable(eqcore_bench bench/bench.cpp)
  target_link_libraries(eqcore_bench PRIVATE eqcore)
endif()
