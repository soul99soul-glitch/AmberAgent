#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cpp="$root/../../entry/src/main/cpp"
"$root/scripts/build-deps.sh" ohos
sdk="${OHOS_BASE_SDK_HOME:-$HOME/Library/Huawei/commandline/command-line-tools/sdk/default/openharmony}/native"
prefix="$root/build/ohos/install"
crypto="$root/../ssh/build/ohos/install"
"$sdk/llvm/bin/clang++" --target=aarch64-linux-ohos12.0.0 --sysroot="$sdk/sysroot" -D__OHOS_API__=12 \
  -std=c++17 -Wall -Wextra -Werror -Wno-deprecated-declarations -fPIC -shared -DAMBER_MOSH_EMBEDDED=1 -DNDEBUG \
  -I"$cpp" -I"$sdk/sysroot/usr/include" -isystem "$prefix/include" -isystem "$crypto/include" \
  -isystem "$prefix/include/mosh/crypto" -isystem "$prefix/include/mosh/network" \
  -isystem "$prefix/include/mosh/statesync" -isystem "$prefix/include/mosh/terminal" -isystem "$prefix/include/mosh/util" \
  "$cpp/mosh_core.cpp" "$cpp/mosh_napi.cpp" "$prefix/lib/libamber_mosh.a" \
  "$prefix/lib/libprotobuf-lite.a" "$crypto/lib/libcrypto.a" \
  -Wl,--no-undefined -Wl,--exclude-libs,ALL -Wl,--as-needed -lace_napi.z -lz \
  -o "$root/build/ohos/libamber_mosh_probe.so"
"$sdk/llvm/bin/llvm-readelf" -h -d "$root/build/ohos/libamber_mosh_probe.so"
