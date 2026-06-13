#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
PROJECT_ROOT="$(pwd)"
SHARED_DIR="$PROJECT_ROOT/shared"
IOSAPP_DIR="$PROJECT_ROOT/iosApp"

echo "=== AmberAgent iOS Setup ==="
echo ""

# Step 1: Build the shared Kotlin framework
echo "[1/3] Building :shared Kotlin framework for iOS..."
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64 || {
    echo "ERROR: Shared framework compilation failed."
    echo "Fix the errors above and re-run this script."
    exit 1
}

# Copy the built framework to a known location
FRAMEWORK_OUTPUT="$SHARED_DIR/build/bin/iosSimulatorArm64/debugFramework"
mkdir -p "$FRAMEWORK_OUTPUT"
echo "   Framework outputs will be at: $FRAMEWORK_OUTPUT"
echo ""

# Step 2: Generate Xcode project
echo "[2/3] Generating Xcode project..."
if command -v xcodegen &>/dev/null; then
    cd "$IOSAPP_DIR"
    xcodegen generate
    echo "   Generated iosApp.xcodeproj via XcodeGen."
else
    echo "   xcodegen not found. Install it with:"
    echo "     brew install xcodegen"
    echo ""
    echo "   Alternatively, create the Xcode project manually:"
    echo "   ---"
    echo "   1. Open Xcode → File → New → Project → iOS → App"
    echo "   2. Product Name: AmberAgent"
    echo "   3. Organization Identifier: app.amber"
    echo "   4. Interface: SwiftUI"
    echo "   5. Language: Swift"
    echo "   6. Save to: $IOSAPP_DIR (as 'iosApp' subfolder)"
    echo "   7. Replace generated files with those in iosApp/iosApp/"
    echo "   8. In Build Phases, add the Shared.framework from:"
    echo "      $FRAMEWORK_OUTPUT"
    echo "   9. In Build Phases, add AmberNative.xcframework from:"
    echo "      $PROJECT_ROOT/native/AmberNative.xcframework"
    echo "   ---"
fi
echo ""

# Step 3: Reminders
echo "[3/3] Next steps:"
echo "   - Open iosApp.xcodeproj in Xcode"
echo "   - In Xcode: add Embedded Binaries → Shared.framework + AmberNative.xcframework"
echo "   - Build & Run on simulator (arm64) or device"
echo ""
echo "=== Done ==="
