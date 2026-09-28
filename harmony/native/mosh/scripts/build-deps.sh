#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
mode=${1:-ohos}
case "$mode" in host|ohos) ;; *) echo 'Usage: build-deps.sh [host|ohos]' >&2; exit 2;; esac
mkdir -p "$root/archives" "$root/build/sources" "$root/build/$mode"
fetch() { if [ ! -f "$root/archives/$1" ]; then curl -fL --retry 2 "$2" -o "$root/archives/$1.part"; mv "$root/archives/$1.part" "$root/archives/$1"; fi; }
fetch mosh-1.4.0.tar.gz https://github.com/mobile-shell/mosh/releases/download/mosh-1.4.0/mosh-1.4.0.tar.gz
fetch protobuf-all-21.12.tar.gz https://github.com/protocolbuffers/protobuf/releases/download/v21.12/protobuf-all-21.12.tar.gz
(cd "$root/archives" && shasum -a 256 -c "$root/SHA256SUMS")
for release in mosh-1.4.0 protobuf-21.12; do
  if [ ! -d "$root/build/sources/$release" ]; then
    if [ "$release" = mosh-1.4.0 ]; then archive=mosh-1.4.0.tar.gz; else archive=protobuf-all-21.12.tar.gz; fi
    tar -xzf "$root/archives/$archive" -C "$root/build/sources"
  fi
done
(cd "$root" && shasum -a 256 -c PATCH_SHA256SUMS)
for patch in "$root"/patches/*.patch; do
  name=$(basename "$patch" .patch)
  case "$name" in terminaldisplayinit.cc) source=src/terminal/terminaldisplayinit.cc;; fatal_assert.h) source=src/util/fatal_assert.h;; crypto.cc) source=src/crypto/crypto.cc;; network.h) source=src/network/network.h;; networktransport.h) source=src/network/networktransport.h;; esac
  if ! grep -qE 'AMBER_MOSH_EMBEDDED|last_heard_timestamp' "$root/build/sources/mosh-1.4.0/$source"; then
    (cd "$root/build/sources/mosh-1.4.0" && patch -p1 < "$patch")
  fi
done
(cd "$root" && shasum -a 256 -c PATCHED_SOURCE_SHA256SUMS)
sdk="${OHOS_BASE_SDK_HOME:-$HOME/Library/Huawei/commandline/command-line-tools/sdk/default/openharmony}/native"
cmake=${AMBER_NATIVE_CMAKE:-$sdk/build-tools/cmake/bin/cmake}
ninja=${AMBER_NATIVE_NINJA:-/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/native/build-tools/cmake/bin/ninja}
jobs=${AMBER_MOSH_BUILD_JOBS:-4}
prefix="$root/build/$mode/install"
# The matching host generator is built first; no target executable is invoked.
if [ ! -x "$root/build/host/install/bin/protoc" ]; then
  "$cmake" -S "$root/build/sources/protobuf-21.12" -B "$root/build/host/protobuf" -G Ninja \
    -DCMAKE_MAKE_PROGRAM="$ninja" -DCMAKE_BUILD_TYPE=Release -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
    -Dprotobuf_BUILD_TESTS=OFF -Dprotobuf_WITH_ZLIB=OFF -Dprotobuf_BUILD_SHARED_LIBS=OFF \
    -DCMAKE_INSTALL_PREFIX="$root/build/host/install"
  "$cmake" --build "$root/build/host/protobuf" --parallel "$jobs"
  "$cmake" --install "$root/build/host/protobuf"
fi
set -- -G Ninja -DCMAKE_MAKE_PROGRAM="$ninja" -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_POSITION_INDEPENDENT_CODE=ON -DCMAKE_INSTALL_PREFIX="$prefix"
if [ "$mode" = ohos ]; then
  set -- "$@" -DCMAKE_TOOLCHAIN_FILE="$sdk/build/cmake/ohos.toolchain.cmake" \
    -DOHOS_ARCH=arm64-v8a -DOHOS_COMPATIBLE_SDK_VERSION=12 \
    -DCMAKE_CXX_FLAGS=-Wno-unused-command-line-argument
  "$cmake" -S "$root/build/sources/protobuf-21.12" -B "$root/build/ohos/protobuf" "$@" \
    -Dprotobuf_BUILD_TESTS=OFF -Dprotobuf_WITH_ZLIB=OFF -Dprotobuf_BUILD_SHARED_LIBS=OFF \
    -Dprotobuf_BUILD_PROTOC_BINARIES=OFF
  "$cmake" --build "$root/build/ohos/protobuf" --target libprotobuf-lite --parallel "$jobs"
  mkdir -p "$prefix/lib" "$prefix/include"
  cp "$root/build/ohos/protobuf/libprotobuf-lite.a" "$prefix/lib/"
  cp -R "$root/build/sources/protobuf-21.12/src/google" "$prefix/include/"
fi
crypto="$root/../ssh/build/$mode/install"
if [ ! -f "$crypto/lib/libcrypto.a" ]; then "$root/../ssh/scripts/build-deps.sh" "$mode"; fi
"$cmake" -S "$root" -B "$root/build/$mode/mosh" "$@" \
  -DAMBER_PROTOC="$root/build/host/install/bin/protoc" -DAMBER_DEP_INSTALL="$prefix" \
  -DAMBER_CRYPTO_INSTALL="$crypto"
"$cmake" --build "$root/build/$mode/mosh" --parallel "$jobs"
"$cmake" --install "$root/build/$mode/mosh"
