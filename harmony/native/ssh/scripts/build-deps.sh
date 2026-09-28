#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
mode=${1:-ohos}
case "$mode" in ohos|host) ;; *) echo 'Usage: build-deps.sh [ohos|host]' >&2; exit 2 ;; esac
mkdir -p "$root/archives" "$root/build/sources" "$root/build/$mode"
fetch() {
  if [ ! -f "$root/archives/$1" ]; then
    curl -fL --retry 2 "$2" -o "$root/archives/$1.part"
    mv "$root/archives/$1.part" "$root/archives/$1"
  fi
}
fetch libssh2-1.11.1.tar.gz https://libssh2.org/download/libssh2-1.11.1.tar.gz
fetch openssl-3.5.8.tar.gz https://github.com/openssl/openssl/releases/download/openssl-3.5.8/openssl-3.5.8.tar.gz
(cd "$root/archives" && shasum -a 256 -c "$root/SHA256SUMS")
for release in libssh2-1.11.1 openssl-3.5.8; do
  if [ ! -d "$root/build/sources/$release" ]; then
    tar -xzf "$root/archives/$release.tar.gz" -C "$root/build/sources"
  fi
done
(cd "$root" && shasum -a 256 -c PATCH_SHA256SUMS)
userauth="$root/build/sources/libssh2-1.11.1/src/userauth.c"
if ! grep -q 'strlen(session->server_sign_algorithms) + 1' "$userauth"; then
  (cd "$root/build/sources/libssh2-1.11.1" && patch -p1 < "$root/patches/libssh2-1.11.1-server-sig-algs-size.patch")
fi
if ! grep -q 'libssh2_channel_exit_status_received' "$root/build/sources/libssh2-1.11.1/src/channel.c"; then
  (cd "$root/build/sources/libssh2-1.11.1" && patch -p1 < "$root/patches/libssh2-1.11.1-exit-status-presence.patch")
fi
(cd "$root" && shasum -a 256 -c PATCHED_SOURCE_SHA256SUMS)
prefix="$root/build/$mode/install"
openssl_build="$root/build/$mode/openssl"
mkdir -p "$openssl_build"
jobs=${AMBER_SSH_BUILD_JOBS:-4}
if [ "$mode" = ohos ]; then
  sdk="${OHOS_BASE_SDK_HOME:-$HOME/Library/Huawei/commandline/command-line-tools/sdk/default/openharmony}/native"
  export CC="$root/scripts/ohos-clang.sh"
  export AR="$sdk/llvm/bin/llvm-ar"
  export RANLIB="$sdk/llvm/bin/llvm-ranlib"
  openssl_target=linux-aarch64
  cmake="${AMBER_NATIVE_CMAKE:-$sdk/build-tools/cmake/bin/cmake}"
  ninja="${AMBER_NATIVE_NINJA:-/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/native/build-tools/cmake/bin/ninja}"
else
  openssl_target=darwin64-arm64-cc
  cmake=${AMBER_NATIVE_CMAKE:-$HOME/Library/Huawei/commandline/command-line-tools/sdk/default/openharmony/native/build-tools/cmake/bin/cmake}
  ninja=${AMBER_NATIVE_NINJA:-/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/native/build-tools/cmake/bin/ninja}
fi
if [ ! -f "$openssl_build/Makefile" ]; then
  (cd "$openssl_build" && perl "$root/build/sources/openssl-3.5.8/Configure" "$openssl_target" \
    no-shared no-dso no-module no-apps no-tests no-autoload-config no-pinshared \
    --prefix="$prefix" --libdir=lib -fPIC)
fi
(cd "$openssl_build" && make -j "$jobs" build_libs && make install_dev)
set -- -G Ninja -DCMAKE_MAKE_PROGRAM="$ninja" -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_POSITION_INDEPENDENT_CODE=ON -DCMAKE_INSTALL_PREFIX="$prefix" \
  -DCRYPTO_BACKEND=OpenSSL -DOPENSSL_USE_STATIC_LIBS=ON -DOPENSSL_ROOT_DIR="$prefix" \
  -DOPENSSL_INCLUDE_DIR="$prefix/include" -DOPENSSL_CRYPTO_LIBRARY="$prefix/lib/libcrypto.a" \
  -DBUILD_STATIC_LIBS=ON -DBUILD_SHARED_LIBS=OFF -DBUILD_EXAMPLES=OFF -DBUILD_TESTING=OFF \
  -DENABLE_ZLIB_COMPRESSION=OFF -DCMAKE_DISABLE_FIND_PACKAGE_ZLIB=ON -DENABLE_DEBUG_LOGGING=OFF
if [ "$mode" = ohos ]; then
  set -- "$@" -DCMAKE_TOOLCHAIN_FILE="$sdk/build/cmake/ohos.toolchain.cmake" \
    -DOHOS_ARCH=arm64-v8a -DOHOS_COMPATIBLE_SDK_VERSION=12 \
    -DCMAKE_C_FLAGS=-Wno-unused-command-line-argument
fi
"$cmake" -S "$root/build/sources/libssh2-1.11.1" -B "$root/build/$mode/libssh2" "$@"
"$cmake" --build "$root/build/$mode/libssh2" --parallel "$jobs"
"$cmake" --install "$root/build/$mode/libssh2"
