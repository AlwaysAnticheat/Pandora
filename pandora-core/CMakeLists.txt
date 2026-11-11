cmake_minimum_required(VERSION 3.22)
project(PandoraCore LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

if(NOT ANDROID OR NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR
        "libpandora-core targets Android arm64-v8a only; configure with the NDK "
        "toolchain file and -DANDROID_ABI=arm64-v8a. See README.md.")
endif()

add_library(pandora-core STATIC
    src/State.cpp
    src/ProcessMemory.cpp
    src/Integrity.cpp
    src/Hooks.cpp
    src/Redirect.cpp)

set_target_properties(pandora-core PROPERTIES
    OUTPUT_NAME pandora-core
    POSITION_INDEPENDENT_CODE ON)

target_include_directories(pandora-core
    PUBLIC  $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include>
            $<INSTALL_INTERFACE:include>
    PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/src)

target_compile_options(pandora-core PRIVATE -Wall -Wextra -Wpedantic)
