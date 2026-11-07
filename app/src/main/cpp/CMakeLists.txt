cmake_minimum_required(VERSION 3.22)
project("lfm3omni" LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_KLEIDIAI ON)
    set(GGML_OPENMP ON)
endif()

set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/llama.cpp)
add_subdirectory(${LLAMA_SRC} build-llama)

add_library(lfm3omni SHARED lfm_jni.cpp)
target_include_directories(lfm3omni PRIVATE
    ${LLAMA_SRC}/include ${LLAMA_SRC}/common ${LLAMA_SRC}/ggml/include ${LLAMA_SRC}/tools/mtmd ${LLAMA_SRC}/vendor)
target_link_libraries(lfm3omni llama llama-common mtmd android log)
