#!/bin/bash
set -euo pipefail

cd "$(dirname "$0")"

# Verify iOS targets are installed
for target in aarch64-apple-ios aarch64-apple-ios-sim; do
    if ! rustup target list --installed | grep -q "$target"; then
        echo "Installing $target..."
        rustup target add "$target"
    fi
done

echo "Building for iOS device (aarch64-apple-ios)..."
cargo build --target aarch64-apple-ios --release --workspace --exclude jni-common

echo "Building for iOS simulator (aarch64-apple-ios-sim)..."
cargo build --target aarch64-apple-ios-sim --release --workspace --exclude jni-common

echo "Done!"
echo ""
echo "Output libraries:"
echo "  Device:     target/aarch64-apple-ios/release/lib*.a"
echo "  Simulator:  target/aarch64-apple-ios-sim/release/lib*.a"
