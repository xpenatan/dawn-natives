cmake_minimum_required(VERSION 3.22)
project(dawn_natives_smoke LANGUAGES C CXX)

if(NOT DEFINED DAWN_NATIVES_PACKAGE_DIR)
    message(FATAL_ERROR "DAWN_NATIVES_PACKAGE_DIR is required")
endif()

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include("${DAWN_NATIVES_PACKAGE_DIR}/cmake/dawn-natives-targets.cmake")

option(DAWN_NATIVES_SMOKE_STATIC_LIBRARY "Build the smoke target as a static library." OFF)

if(DAWN_NATIVES_SMOKE_STATIC_LIBRARY)
    add_library(dawn_natives_smoke STATIC smoke.cpp)
else()
    add_executable(dawn_natives_smoke smoke.cpp)
endif()

target_link_libraries(dawn_natives_smoke PRIVATE dawn_natives::webgpu_dawn)
