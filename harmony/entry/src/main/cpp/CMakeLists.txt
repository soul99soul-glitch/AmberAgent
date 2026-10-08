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

get_filename_component(AMBER_SSH_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../native/ssh" ABSOLUTE)
set(AMBER_SSH_INSTALL "${AMBER_SSH_ROOT}/build/ohos/install")
add_custom_target(amber_ssh_build
    COMMAND "${CMAKE_COMMAND}" -E env "OHOS_BASE_SDK_HOME=${OHOS_SDK_NATIVE}/.."
        "${AMBER_SSH_ROOT}/scripts/build-deps.sh" ohos
    BYPRODUCTS "${AMBER_SSH_INSTALL}/lib/libssh2.a" "${AMBER_SSH_INSTALL}/lib/libcrypto.a"
    COMMENT "Building SSH dependencies for OHOS API 12"
    VERBATIM)
add_library(amber_ssh2 STATIC IMPORTED)
set_target_properties(amber_ssh2 PROPERTIES IMPORTED_LOCATION "${AMBER_SSH_INSTALL}/lib/libssh2.a")
add_dependencies(amber_ssh2 amber_ssh_build)
add_library(amber_crypto STATIC IMPORTED)
set_target_properties(amber_crypto PROPERTIES IMPORTED_LOCATION "${AMBER_SSH_INSTALL}/lib/libcrypto.a")
add_dependencies(amber_crypto amber_ssh_build)

get_filename_component(AMBER_PYTHON_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../native/python" ABSOLUTE)
set(AMBER_PYTHON_INSTALL "${AMBER_PYTHON_ROOT}/build/ohos/install")
add_custom_target(amber_python_build
    COMMAND "${CMAKE_COMMAND}" -E env "OHOS_BASE_SDK_HOME=${OHOS_SDK_NATIVE}/.."
        "${AMBER_PYTHON_ROOT}/scripts/build-deps.sh" ohos
    BYPRODUCTS "${AMBER_PYTHON_INSTALL}/lib/libpython3.14.a"
    COMMENT "Building embedded CPython for OHOS API 12"
    VERBATIM)
add_library(amber_python STATIC IMPORTED)
set_target_properties(amber_python PROPERTIES IMPORTED_LOCATION "${AMBER_PYTHON_INSTALL}/lib/libpython3.14.a")
add_dependencies(amber_python amber_python_build)

get_filename_component(AMBER_MOSH_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../native/mosh" ABSOLUTE)
set(AMBER_MOSH_INSTALL "${AMBER_MOSH_ROOT}/build/ohos/install")
add_custom_target(amber_mosh_build
    COMMAND "${CMAKE_COMMAND}" -E env "OHOS_BASE_SDK_HOME=${OHOS_SDK_NATIVE}/.."
        "${AMBER_MOSH_ROOT}/scripts/build-deps.sh" ohos
    BYPRODUCTS "${AMBER_MOSH_INSTALL}/lib/libamber_mosh.a" "${AMBER_MOSH_INSTALL}/lib/libprotobuf-lite.a"
    COMMENT "Building Mosh dependencies for OHOS API 12"
    VERBATIM)
add_dependencies(amber_mosh_build amber_ssh_build)
add_library(amber_mosh STATIC IMPORTED)
set_target_properties(amber_mosh PROPERTIES IMPORTED_LOCATION "${AMBER_MOSH_INSTALL}/lib/libamber_mosh.a")
add_dependencies(amber_mosh amber_mosh_build)
add_library(amber_protobuf STATIC IMPORTED)
set_target_properties(amber_protobuf PROPERTIES IMPORTED_LOCATION "${AMBER_MOSH_INSTALL}/lib/libprotobuf-lite.a")
add_dependencies(amber_protobuf amber_mosh_build)

add_library(amber_native SHARED napi_init.cpp ssh_core.cpp ssh_napi.cpp
    python_core.cpp python_napi.cpp mosh_core.cpp mosh_napi.cpp plugin_js_core.cpp plugin_js_napi.cpp
    google_loopback_core.cpp google_loopback_napi.cpp stream_frame_rate.cpp)
target_compile_features(amber_native PRIVATE cxx_std_17)
target_include_directories(amber_native PRIVATE "${AMBER_RUST_ROOT}/include" "${AMBER_SSH_INSTALL}/include")
target_include_directories(amber_native SYSTEM PRIVATE "${AMBER_PYTHON_INSTALL}/include/python3.14"
    "${AMBER_MOSH_INSTALL}/include" "${AMBER_MOSH_INSTALL}/include/mosh/crypto"
    "${AMBER_MOSH_INSTALL}/include/mosh/network" "${AMBER_MOSH_INSTALL}/include/mosh/statesync"
    "${AMBER_MOSH_INSTALL}/include/mosh/terminal" "${AMBER_MOSH_INSTALL}/include/mosh/util")
set_source_files_properties(mosh_core.cpp mosh_napi.cpp PROPERTIES COMPILE_DEFINITIONS "AMBER_MOSH_EMBEDDED=1;NDEBUG")
# The SDK toolchain passes --gcc-toolchain, which clang 15 does not consume for OHOS.
target_compile_options(amber_native PRIVATE -Wall -Wextra -Werror -Wno-unused-command-line-argument)
target_link_options(amber_native PRIVATE -Wl,--no-undefined -Wl,--exclude-libs,ALL -Wl,--as-needed)
# Rust 1.96 OHOS --print=native-static-libs reports these two system libraries.
target_link_libraries(amber_native PRIVATE amber_rust amber_ssh2 amber_python amber_mosh
    amber_protobuf amber_crypto libace_napi.z.so libjsvm.so libnative_vsync.so libhilog_ndk.z.so unwind z dl m c)
