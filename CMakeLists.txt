cmake_minimum_required(VERSION 3.22.1)
project(SpatialXRNativeTests LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Enable testing
enable_testing()

include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}/app/src/main/cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/app/src/main/cpp/graphics
    ${CMAKE_CURRENT_SOURCE_DIR}/app/src/main/cpp/hardware
    ${CMAKE_CURRENT_SOURCE_DIR}/app/src/main/cpp/tracking
    ${CMAKE_CURRENT_SOURCE_DIR}/app/src/main/cpp/openxr
    ${CMAKE_CURRENT_SOURCE_DIR}/tests/native
)

# Common mock/host headers for tests running without Android NDK
add_definitions(-DSPATIAL_XR_HOST_TEST=1)

file(GLOB_RECURSE TEST_SOURCES
    ${CMAKE_CURRENT_SOURCE_DIR}/tests/native/*.cpp
)

if (TEST_SOURCES)
    add_executable(run_native_tests ${TEST_SOURCES})
    add_test(NAME AllNativeTests COMMAND run_native_tests)
endif()
