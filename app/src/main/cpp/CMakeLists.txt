cmake_minimum_required(VERSION 3.22.1)
project(spatial_xr LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/graphics
    ${CMAKE_CURRENT_SOURCE_DIR}/hardware
    ${CMAKE_CURRENT_SOURCE_DIR}/tracking
    ${CMAKE_CURRENT_SOURCE_DIR}/openxr
)

file(GLOB_RECURSE SPATIAL_SOURCES
    ${CMAKE_CURRENT_SOURCE_DIR}/*.cpp
)

# Filter out test sources if any in main cpp tree
list(FILTER SPATIAL_SOURCES EXCLUDE REGEX ".*_test\\.cpp$")

add_library(spatial_xr SHARED
    ${SPATIAL_SOURCES}
)

find_library(log-lib log)
find_library(android-lib android)
find_library(vulkan-lib vulkan)

target_link_libraries(spatial_xr
    ${log-lib}
    ${android-lib}
    ${vulkan-lib}
)
