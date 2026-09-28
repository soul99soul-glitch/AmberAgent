#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cpp="$root/../../entry/src/main/cpp"
sdk="${OHOS_BASE_SDK_HOME:-$HOME/Library/Huawei/commandline/command-line-tools/sdk/default/openharmony}/native"
"$root/scripts/build-deps.sh" ohos
"$sdk/llvm/bin/clang++" --target=aarch64-linux-ohos12.0.0 --sysroot="$sdk/sysroot" -D__OHOS_API__=12 \
  -std=c++17 -O2 -Wall -Wextra -Werror -fPIC -shared -Wl,--no-undefined \
  -I"$cpp" -I"$root/build/ohos/install/include/python3.14" \
  "$cpp/python_core.cpp" "$cpp/python_napi.cpp" "$root/build/ohos/install/lib/libpython3.14.a" \
  -lace_napi.z -lunwind -ldl -lm -o "$root/build/ohos/libamber_python_smoke.so"
"$sdk/llvm/bin/llvm-readelf" -h -d "$root/build/ohos/libamber_python_smoke.so"
