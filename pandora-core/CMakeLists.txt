cmake_minimum_required(VERSION 3.22)
project(PandoraCore LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# The confirmed syscall numbers and register layouts require Android arm64-v8a.
# Configure with the NDK toolchain, e.g.:
#   cmake -B build \
#     -DCMAKE_TOOLCHAIN_FILE=<ndk>/build/cmake/android.toolchain.cmake \
#     -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-24
if(NOT ANDROID OR NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR
        "libpandora-core targets Android arm64-v8a only; configure with the NDK "
        "toolchain file and -DANDROID_ABI=arm64-v8a. See README.md.")
endif()

# Static library: reconstructed redirector core + public API wrapper.
# No JNI translation unit, no VM, no encrypted payload, no embedded .so.
add_library(pandora-core STATIC
    src/State.cpp
    src/ProcessMemory.cpp
    src/Integrity.cpp
    src/Hooks.cpp
    src/Redirect.cpp)

set_target_properties(pandora-core PROPERTIES
    OUTPUT_NAME pandora-core
    POSITION_INDEPENDENT_CODE ON)

# Public headers under include/ (pandora/redirect.h); internal headers alongside
# the sources in src/.
target_include_directories(pandora-core
    PUBLIC  $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include>
            $<INSTALL_INTERFACE:include>
    PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/src)

target_compile_options(pandora-core PRIVATE -Wall -Wextra -Wpedantic)

# Produces libpandora-core.a. Link it into a PIE/shared consumer; the core uses
# only pthread and libc, both part of the Android platform link set.
