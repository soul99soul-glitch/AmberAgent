cmake_minimum_required(VERSION 3.16)
if(DEFINED ENV{AMBER_NATIVE_NINJA})
    set(CMAKE_MAKE_PROGRAM "$ENV{AMBER_NATIVE_NINJA}" CACHE FILEPATH "Host-compatible Ninja" FORCE)
endif()
project(amber_native LANGUAGES CXX)

if(DEFINED PACKAGE_FIND_FILE)
    include(${PACKAGE_FIND_FILE})
endif()
if(NOT OHOS_ARCH STREQUAL "arm64-v8a")
    message(FATAL_ERROR "amber_native currently supports arm64-v8a")
endif()

get_filename_component(AMBER_RUST_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../native/rust-napi" ABSOLUTE)
set(AMBER_RUST_ARCHIVE "${AMBER_RUST_ROOT}/target/aarch64-unknown-linux-ohos/release/libamber_native_core.a")

# Cargo checks its own source/dependency timestamps on every native build.
add_custom_target(amber_rust_build
    COMMAND "${CMAKE_COMMAND}" -E env "OHOS_BASE_SDK_HOME=${OHOS_SDK_NATIVE}/.."
        "${AMBER_RUST_ROOT}/scripts/build-rust-native.sh"
    BYPRODUCTS "${AMBER_RUST_ARCHIVE}"
    COMMENT "Building Amber Rust core for OHOS API 12"
    VERBATIM)
add_library(amber_rust STATIC IMPORTED)
set_target_properties(amber_rust PROPERTIES IMPORTED_LOCATION "${AMBER_RUST_ARCHIVE}")
add_dependencies(amber_rust amber_rust_build)

add_library(amber_native SHARED napi_init.cpp)
target_compile_features(amber_native PRIVATE cxx_std_17)
target_include_directories(amber_native PRIVATE "${AMBER_RUST_ROOT}/include")
# The SDK toolchain passes --gcc-toolchain, which clang 15 does not consume for OHOS.
target_compile_options(amber_native PRIVATE -Wall -Wextra -Werror -Wno-unused-command-line-argument)
target_link_options(amber_native PRIVATE -Wl,--no-undefined -Wl,--exclude-libs,ALL -Wl,--as-needed)
# Rust 1.96 OHOS --print=native-static-libs reports these two system libraries.
target_link_libraries(amber_native PRIVATE amber_rust libace_napi.z.so unwind c)
