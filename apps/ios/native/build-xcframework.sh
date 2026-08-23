#!/bin/bash
# Build AmberNative.xcframework from the amber-ffi crate.
#
# Produces:
#   native/AmberNative.xcframework/       — XCFramework for iOS device + simulator
#   native/amber-ffi/AmberNative.h         — Generated C header
#
# Prerequisites:
#   - Rust toolchain with aarch64-apple-ios and aarch64-apple-ios-sim targets
#   - cbindgen (installed via `cargo install cbindgen` if missing)
#   - Xcode command-line tools (xcodebuild, lipo)
#
# Usage:
#   cd native && bash build-xcframework.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

FFI_CRATE="amber-ffi"
FRAMEWORK_NAME="AmberNative"
OUTPUT_DIR="$SCRIPT_DIR/${FRAMEWORK_NAME}.xcframework"
HEADER_NAME="${FRAMEWORK_NAME}.h"
DEVICE_TARGET="aarch64-apple-ios"
SIM_TARGET="aarch64-apple-ios-sim"

# Keep local workspace and Cargo registry paths out of committed archives.
export RUSTFLAGS="${RUSTFLAGS:+$RUSTFLAGS }--remap-path-prefix=${HOME}=/build/home --remap-path-prefix=${SCRIPT_DIR}=/build/source"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

info()  { echo -e "${GREEN}[INFO]${NC}  $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
error() { echo -e "${RED}[ERROR]${NC} $*" >&2; exit 1; }

# ── Step 0: Prerequisites ──────────────────────────────────────────────────

# Install iOS targets if missing
for target in "$DEVICE_TARGET" "$SIM_TARGET"; do
    if ! rustup target list --installed | grep -q "$target"; then
        info "Installing Rust target: $target"
        rustup target add "$target"
    fi
done

# Install cbindgen if missing
if ! command -v cbindgen &>/dev/null; then
    info "Installing cbindgen..."
    cargo install cbindgen
fi

# Check Xcode tools
if ! command -v xcodebuild &>/dev/null; then
    error "xcodebuild not found. Install Xcode command-line tools: xcode-select --install"
fi

# ── Step 1: Build for iOS device ───────────────────────────────────────────

# Pin deployment target so device & simulator slices match the app target.
# Without this, Rust may pick up a different SDK minimum, producing link warnings
# like "object file was built for newer iOS simulator version than target".
export IPHONEOS_DEPLOYMENT_TARGET=26.0

info "Building $FFI_CRATE for $DEVICE_TARGET..."
cargo build --package "$FFI_CRATE" --target "$DEVICE_TARGET" --release

DEVICE_LIB="target/$DEVICE_TARGET/release/lib${FFI_CRATE//-/_}.a"
if [ ! -f "$DEVICE_LIB" ]; then
    error "Device static library not found: $DEVICE_LIB"
fi
info "Device lib: $(ls -lh "$DEVICE_LIB" | awk '{print $5}')"

# ── Step 2: Build for iOS simulator ────────────────────────────────────────

info "Building $FFI_CRATE for $SIM_TARGET..."
cargo build --package "$FFI_CRATE" --target "$SIM_TARGET" --release

SIM_LIB="target/$SIM_TARGET/release/lib${FFI_CRATE//-/_}.a"
if [ ! -f "$SIM_LIB" ]; then
    error "Simulator static library not found: $SIM_LIB"
fi
info "Simulator lib: $(ls -lh "$SIM_LIB" | awk '{print $5}')"

# ── Step 3: Generate C header ─────────────────────────────────────────────

info "Generating C header with cbindgen..."
HEADER_PATH="$SCRIPT_DIR/$FFI_CRATE/$HEADER_NAME"
cbindgen --crate "$FFI_CRATE" --config "$SCRIPT_DIR/$FFI_CRATE/cbindgen.toml" \
    --output "$HEADER_PATH" \
    --lang c
if [ ! -f "$HEADER_PATH" ]; then
    error "Header generation failed: $HEADER_PATH not created"
fi
info "Header: $HEADER_PATH ($(wc -l < "$HEADER_PATH") lines)"

# ── Step 4: Create XCFramework (manual packaging) ────────────────────────

info "Creating $FRAMEWORK_NAME.xcframework..."

# Clean previous output
rm -rf "$OUTPUT_DIR"

# Directory structure for XCFramework (static library):
#   AmberNative.xcframework/
#     Info.plist
#     ios-arm64/
#       libamber_ffi.a
#       Headers/AmberNative.h
#       Headers/module.modulemap
#     ios-arm64-sim/
#       libamber_ffi.a
#       Headers/AmberNative.h
#       Headers/module.modulemap

DEVICE_DIR="$OUTPUT_DIR/ios-arm64/Headers"
SIM_DIR="$OUTPUT_DIR/ios-arm64-sim/Headers"
mkdir -p "$DEVICE_DIR" "$SIM_DIR"

cp "$DEVICE_LIB" "$OUTPUT_DIR/ios-arm64/"
cp "$SIM_LIB" "$OUTPUT_DIR/ios-arm64-sim/"
xcrun strip -S -D \
    "$OUTPUT_DIR/ios-arm64/$(basename "$DEVICE_LIB")" \
    "$OUTPUT_DIR/ios-arm64-sim/$(basename "$SIM_LIB")"
cp "$HEADER_PATH" "$DEVICE_DIR/"
cp "$HEADER_PATH" "$SIM_DIR/"

# module.modulemap for Swift interop
cat > "$DEVICE_DIR/module.modulemap" << 'MODMAP'
module AmberNative {
    header "AmberNative.h"
    export *
}
MODMAP
cat > "$SIM_DIR/module.modulemap" << 'MODMAP'
module AmberNative {
    header "AmberNative.h"
    export *
}
MODMAP

# Info.plist
cat > "$OUTPUT_DIR/Info.plist" << 'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>AvailableLibraries</key>
	<array>
		<dict>
			<key>LibraryIdentifier</key>
			<string>ios-arm64</string>
			<key>LibraryPath</key>
			<string>PLACEHOLDER_LIB_NAME</string>
			<key>SupportedArchitectures</key>
			<array><string>arm64</string></array>
			<key>SupportedPlatform</key>
			<string>ios</string>
			<key>HeadersPath</key>
			<string>Headers</string>
		</dict>
		<dict>
			<key>LibraryIdentifier</key>
			<string>ios-arm64-sim</string>
			<key>LibraryPath</key>
			<string>PLACEHOLDER_LIB_NAME</string>
			<key>SupportedArchitectures</key>
			<array><string>arm64</string></array>
			<key>SupportedPlatform</key>
			<string>ios</string>
			<key>SupportedPlatformVariant</key>
			<string>simulator</string>
			<key>HeadersPath</key>
			<string>Headers</string>
		</dict>
	</array>
	<key>CFBundlePackageType</key>
	<string>XFWK</string>
	<key>XCFrameworkFormatVersion</key>
	<string>1.0</string>
</dict>
</plist>
PLIST

LIB_NAME="lib${FFI_CRATE//-/_}.a"
sed -i '' "s|PLACEHOLDER_LIB_NAME|$LIB_NAME|g" "$OUTPUT_DIR/Info.plist"

# ── Step 5: Summary ───────────────────────────────────────────────────────

info ""
info "✅ $FRAMEWORK_NAME.xcframework built successfully!"
info ""
info "Contents:"
find "$OUTPUT_DIR" -type f | sort
info ""
info "Device slice:  $(ls -lh "$OUTPUT_DIR/ios-arm64/$LIB_NAME" | awk '{print $5}')"
info "Sim slice:     $(ls -lh "$OUTPUT_DIR/ios-arm64-sim/$LIB_NAME" | awk '{print $5}')"
info ""
info "To use in Xcode:"
info "  1. Drag $OUTPUT_DIR into your Xcode project"
info "  2. Add to Frameworks, Libraries, and Embedded Content"
info "  3. In Swift: import AmberNative"
