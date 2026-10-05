cmake_minimum_required(VERSION 3.22)
project(turrini_realistic)

set(BULLET_ROOT ${CMAKE_SOURCE_DIR}/../../../../bullet3)
include_directories(${BULLET_ROOT}/src)

file(GLOB_RECURSE BULLET_SRC
    ${BULLET_ROOT}/src/*.cpp
)
list(FILTER BULLET_SRC EXCLUDE REGEX ".*/Bullet3/.*")
list(FILTER BULLET_SRC EXCLUDE REGEX ".*/examples/.*")
list(FILTER BULLET_SRC EXCLUDE REGEX ".*/OpenCL/.*")

add_library(bullet STATIC ${BULLET_SRC})
target_include_directories(bullet PUBLIC ${BULLET_ROOT}/src)
target_compile_definitions(bullet PRIVATE BT_NO_PROFILE=1)
target_compile_options(bullet PRIVATE -O2 -fexceptions -frtti)

add_library(rage SHARED main.cpp)
find_library(log-lib log)
find_library(gles-lib GLESv3)
find_library(android-lib android)

target_link_libraries(rage bullet ${log-lib} ${gles-lib} ${android-lib})
