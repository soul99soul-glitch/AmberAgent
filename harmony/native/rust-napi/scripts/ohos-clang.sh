#!/bin/sh
set -eu
native_sdk="${OHOS_BASE_SDK_HOME:?Set OHOS_BASE_SDK_HOME to the OpenHarmony SDK directory}/native"
exec "$native_sdk/llvm/bin/clang" -target aarch64-linux-ohos12 \
  --sysroot="$native_sdk/sysroot" -D__MUSL__ "$@"
