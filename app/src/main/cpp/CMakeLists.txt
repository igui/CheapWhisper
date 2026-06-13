cmake_minimum_required(VERSION 3.10)

project(smartnotetaker)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_BUILD_TYPE Release) # Force release build for performance
set(WHISPER_LIB_DIR ${CMAKE_SOURCE_DIR}/whisper.cpp)

# --- WHISPER BUILD ---
file(READ "${WHISPER_LIB_DIR}/CMakeLists.txt" MAIN_CMAKE_CONTENT)
string(REGEX MATCH "project\\(\"whisper\\.cpp\" VERSION ([0-9]+\\.[0-9]+\\.[0-9]+)\\)" VERSION_MATCH "${MAIN_CMAKE_CONTENT}")
if(CMAKE_MATCH_1)
    set(WHISPER_VERSION ${CMAKE_MATCH_1} PARENT_SCOPE)
else()
    set(WHISPER_VERSION "unknown" PARENT_SCOPE)
endif()

find_library(LOG_LIB log)
include(FetchContent)

function(build_whisper_library target_name)
    add_library(
        ${target_name}
        SHARED
        ${WHISPER_LIB_DIR}/src/whisper.cpp
        ${CMAKE_SOURCE_DIR}/jni.c
    )

    target_compile_definitions(${target_name} PUBLIC GGML_USE_CPU)
    target_compile_definitions(${target_name} PRIVATE WHISPER_VERSION="${WHISPER_VERSION}")

    set(GGML_COMPILE_OPTIONS "")
    if (${target_name} STREQUAL "whisper_v8fp16_va")
        target_compile_options(${target_name} PRIVATE -march=armv8.2-a+fp16)
        list(APPEND GGML_COMPILE_OPTIONS -march=armv8.2-a+fp16)
    elseif (${target_name} STREQUAL "whisper_vfpv4")
        target_compile_options(${target_name} PRIVATE -mfpu=neon-vfpv4)
        list(APPEND GGML_COMPILE_OPTIONS -mfpu=neon-vfpv4)
    endif ()

    target_compile_options(${target_name} PRIVATE -O3 -flto -fvisibility=hidden -fvisibility-inlines-hidden -ffunction-sections -fdata-sections)
    target_link_options(${target_name} PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL -flto)

    FetchContent_Declare(ggml_whisper SOURCE_DIR ${WHISPER_LIB_DIR}/ggml)
    FetchContent_MakeAvailable(ggml_whisper)
    
    # Ensure ggml gets the O3 and architecture flags
    target_compile_options(ggml PRIVATE ${GGML_COMPILE_OPTIONS} -O3 -flto)
    target_link_libraries(${target_name} ${LOG_LIB} android ggml)
    
    target_include_directories(${target_name} PRIVATE ${WHISPER_LIB_DIR} ${WHISPER_LIB_DIR}/src ${WHISPER_LIB_DIR}/include ${WHISPER_LIB_DIR}/ggml/include ${WHISPER_LIB_DIR}/ggml/src ${WHISPER_LIB_DIR}/ggml/src/ggml-cpu)
endfunction()

if (${ANDROID_ABI} STREQUAL "arm64-v8a")
    build_whisper_library("whisper_v8fp16_va")
elseif (${ANDROID_ABI} STREQUAL "armeabi-v7a")
    build_whisper_library("whisper_vfpv4")
endif ()
build_whisper_library("whisper")
