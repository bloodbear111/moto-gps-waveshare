cmake_minimum_required(VERSION 3.22)

project(moto_mobile_jni LANGUAGES CXX)

# The Android port deliberately reuses the upstream `shared/` C++ core for
# coordinate conversion, the navigation state machine and the BLE v1 codec.
# Re-implementing those in Kotlin would create a second source of truth for the
# wire format and route matching.
if(NOT DEFINED MOTO_REPO_ROOT)
    set(MOTO_REPO_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../../../..")
endif()
get_filename_component(MOTO_REPO_ROOT "${MOTO_REPO_ROOT}" ABSOLUTE)

if(NOT EXISTS "${MOTO_REPO_ROOT}/shared/ble_protocol/src/ble_protocol.cpp")
    message(FATAL_ERROR
        "MOTO_REPO_ROOT does not point at the MOTO GPS repository root "
        "(looked for shared/ble_protocol/src/ble_protocol.cpp under "
        "${MOTO_REPO_ROOT}).")
endif()

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_subdirectory("${MOTO_REPO_ROOT}/shared/coordinates" coordinates)
add_subdirectory("${MOTO_REPO_ROOT}/shared/nav_core" nav_core)
add_subdirectory("${MOTO_REPO_ROOT}/shared/nav_app" nav_app)
add_subdirectory("${MOTO_REPO_ROOT}/shared/ble_protocol" ble_protocol)

# Embed the reviewed v1 golden vectors so the on-device protocol self-check and
# the instrumented tests compare against the repository fixture itself rather
# than a duplicated copy that can drift.
set(MOTO_GOLDEN_FIXTURE_PATH
    "${MOTO_REPO_ROOT}/shared/protocol/fixtures/ble-navigation-v1.golden.txt")
if(NOT EXISTS "${MOTO_GOLDEN_FIXTURE_PATH}")
    message(FATAL_ERROR "missing golden fixture: ${MOTO_GOLDEN_FIXTURE_PATH}")
endif()
file(READ "${MOTO_GOLDEN_FIXTURE_PATH}" MOTO_GOLDEN_FIXTURE_TEXT)
configure_file(
    "${CMAKE_CURRENT_LIST_DIR}/moto_golden_fixture.h.in"
    "${CMAKE_CURRENT_BINARY_DIR}/generated/moto_golden_fixture.h"
    @ONLY)

add_library(moto_mobile SHARED
    moto_jni.cpp
    moto_golden_selftest.cpp
)

target_include_directories(moto_mobile PRIVATE
    "${CMAKE_CURRENT_BINARY_DIR}/generated"
    "${CMAKE_CURRENT_LIST_DIR}"
    # accel_gmeter.hpp is pure C++17 with no LVGL/ESP-IDF dependency, so the
    # same header that drives the round display's G meter is compiled here and
    # checked from the on-device self-test. The firmware cannot be built in this
    # environment, so this is how its ball/maths behaviour is verified on real
    # hardware instead of only asserted in a host test nobody has run.
    "${MOTO_REPO_ROOT}/platforms/esp32/main"
)

target_link_libraries(moto_mobile
    PRIVATE
        moto_ble_protocol
        moto_nav_app
        moto_nav_core
        moto_coordinates
        log
)

target_compile_features(moto_mobile PRIVATE cxx_std_17)

# The shared core is built with -fno-exceptions/-fno-rtti. Keep the JNI
# translation unit consistent so no exception unwinding crosses the boundary.
target_compile_options(moto_mobile PRIVATE
    -Wall
    -Wextra
    -fno-exceptions
    -fno-rtti
)
