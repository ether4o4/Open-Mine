cmake_minimum_required(VERSION 3.22.1)
project(openmine_ai C CXX)

# One self-contained library: the embedded llama.cpp server (LFM3 and other GGUF chat models)
# and, on ARM, the Cactus runtime for Needle 3. Nothing is downloaded at build time; llama.cpp is
# the pinned git submodule at app/src/main/cpp/llama.cpp (tag b12318, 7021f7e).
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_UI OFF CACHE BOOL "" FORCE)
set(LLAMA_USE_PREBUILT_UI OFF CACHE BOOL "" FORCE)
set(LLAMA_CONNECT OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_SUBPROCESS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_IS_DEV OFF CACHE BOOL "" FORCE)
set(LLAMA_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
# llamafile's sgemm kernels need fp16 NEON intrinsics that 32-bit ARM lacks.
if(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
else()
    set(GGML_LLAMAFILE ON CACHE BOOL "" FORCE)
endif()
if(ANDROID_ABI STREQUAL "arm64-v8a")
    # Armv8.2 dot-product and fp16 (all Snapdragon 8-series, including the S24 Ultra).
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
endif()

add_subdirectory(llama.cpp EXCLUDE_FROM_ALL)

add_library(openmine_ai SHARED openmine_ai.cpp)
target_include_directories(openmine_ai PRIVATE
    llama.cpp/tools/server llama.cpp/common llama.cpp/include cactus/include)
target_link_libraries(openmine_ai PRIVATE llama-server-impl llama-common llama android log)

if(ANDROID_ABI STREQUAL "arm64-v8a" OR ANDROID_ABI STREQUAL "armeabi-v7a")
    add_library(cactus STATIC IMPORTED)
    set_target_properties(cactus PROPERTIES IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/cactus/${ANDROID_ABI}/libcactus.a)
    target_link_libraries(openmine_ai PRIVATE cactus)
    target_compile_definitions(openmine_ai PRIVATE OPENMINE_HAS_CACTUS=1)
endif()
target_link_options(openmine_ai PRIVATE "-Wl,--exclude-libs,ALL" "-Wl,-z,max-page-size=16384")
