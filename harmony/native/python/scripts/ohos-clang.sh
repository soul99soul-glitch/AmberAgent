#!/bin/sh
set -eu
sdk="${OHOS_BASE_SDK_HOME:-$HOME/Library/Huawei/commandline/command-line-tools/sdk/default/openharmony}/native"
exec "$sdk/llvm/bin/clang" --target=aarch64-linux-ohos12.0.0 --sysroot="$sdk/sysroot" -D__OHOS_API__=12 -fPIC "$@"
