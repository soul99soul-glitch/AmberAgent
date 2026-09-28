#!/bin/sh
set -eu

native_crate=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
if [ -z "${OHOS_BASE_SDK_HOME:-}" ]; then
  OHOS_BASE_SDK_HOME="$HOME/Library/Huawei/commandline/command-line-tools/sdk/default/openharmony"
  export OHOS_BASE_SDK_HOME
fi
native_sdk="$OHOS_BASE_SDK_HOME/native"
if [ ! -x "$native_sdk/llvm/bin/clang" ]; then
  echo "OpenHarmony native SDK not found at $native_sdk" >&2
  exit 2
fi

export CARGO_TARGET_AARCH64_UNKNOWN_LINUX_OHOS_LINKER="$native_crate/scripts/ohos-clang.sh"
export CARGO_TARGET_AARCH64_UNKNOWN_LINUX_OHOS_RUSTFLAGS="-C relocation-model=pic"
export CC_aarch64_unknown_linux_ohos="$native_crate/scripts/ohos-clang.sh"
export AR_aarch64_unknown_linux_ohos="$native_sdk/llvm/bin/llvm-ar"
export RANLIB_aarch64_unknown_linux_ohos="$native_sdk/llvm/bin/llvm-ranlib"

exec cargo build --locked --manifest-path "$native_crate/Cargo.toml" \
  --target-dir "$native_crate/target" --target aarch64-unknown-linux-ohos --release
