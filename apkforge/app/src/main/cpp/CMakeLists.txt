cmake_minimum_required(VERSION 3.22)
project(forge)
set(BUILD_SHARED_LIBS OFF)
add_subdirectory(llama.cpp)        # cloned by scripts/fetch-deps.sh
add_library(forge SHARED llm_jni.cpp)
target_link_libraries(forge llama log android)
