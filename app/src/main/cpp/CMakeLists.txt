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
    ${CMAKE_CURRENT_SOURCE_DIR}/include/viture
)

file(GLOB_RECURSE SPATIAL_SOURCES
    ${CMAKE_CURRENT_SOURCE_DIR}/*.cpp
)

# Filter out test sources if any in main cpp tree
list(FILTER SPATIAL_SOURCES EXCLUDE REGEX ".*_test\\.cpp$")

set(JNILIBS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")
add_library(glasses SHARED IMPORTED)
set_target_properties(glasses PROPERTIES
    IMPORTED_LOCATION "${JNILIBS_DIR}/libglasses.so"
)

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
    glasses
)

target_link_options(spatial_xr PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)
