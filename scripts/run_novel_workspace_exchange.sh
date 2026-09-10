#!/usr/bin/env bash
set -euo pipefail

# W15 proves the real tree contract in both directions:
# current iOS exporter -> Android production importer/exporter -> current iOS importer.
# iOS's production Novel importer consumes a folder/.amberNovelProject; the
# Android production ZIP is unpacked by the JVM runner before iOS reads it.
# The iOS checkout is copied to /tmp because this repository is allowed to read it,
# but is intentionally never modified by this runner.

ANDROID_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IOS_ROOT="${AMBER_IOS_ROOT:-/Users/mi/Downloads/AI/AmberAgent/ios}"
RUN_ROOT="/tmp/amber-w15-cross-platform"
IOS_COPY="$RUN_ROOT/ios-source"
IOS_PROJECT="$IOS_COPY/iosApp"
DERIVED_DATA="/tmp/amber-w15-derived"
SIMULATOR_ID="${AMBER_W15_SIMULATOR_ID:-959825AC-6070-43FC-B2BB-3571D4A8CAAF}"

[[ -d "$IOS_ROOT/iosApp" ]] || { echo "Missing iOS checkout: $IOS_ROOT" >&2; exit 1; }
command -v xcodegen >/dev/null || { echo "xcodegen is required" >&2; exit 1; }
command -v xcodebuild >/dev/null || { echo "xcodebuild is required" >&2; exit 1; }

rm -rf "$RUN_ROOT"
mkdir -p "$IOS_COPY"

# Exclude generated build state only. Python.xcframework is a source artifact
# and remains part of the copied checkout so the real iOS target can build.
rsync -a --exclude '.git' --exclude 'build' --exclude '.gradle' --exclude '.kotlin' \
    "$IOS_ROOT/" "$IOS_COPY/"
cp "$ANDROID_ROOT/scripts/novel-workspace/W15CrossPlatformRunnerTests.swift" \
    "$IOS_PROJECT/iosAppTests/W15CrossPlatformRunnerTests.swift"

(
    cd "$IOS_PROJECT"
    xcodegen generate
)

IOS_XCODE="$IOS_PROJECT/AmberAgent.xcodeproj"
IOS_DESTINATION="platform=iOS Simulator,id=$SIMULATOR_ID"

echo "[W15] iOS export: current NovelWorkspaceBackup"
xcodebuild \
    -project "$IOS_XCODE" \
    -scheme iosApp \
    -destination "$IOS_DESTINATION" \
    -derivedDataPath "$DERIVED_DATA" \
    -parallel-testing-enabled NO \
    -only-testing:iosAppTests/W15CrossPlatformRunnerTests/testRealIOSExport \
    test > "$RUN_ROOT/ios-export-build.log"

echo "[W15] Android import -> modify -> export: production JVM path"
AMBER_W15_ROOT="$RUN_ROOT" "$ANDROID_ROOT/gradlew" \
    :feature:novel-workspace:testDebugUnitTest \
    --tests app.amber.feature.novelworkspace.NovelWorkspaceExchangeRunnerTest \
    --offline --console=plain > "$RUN_ROOT/android-exchange-test.log"

echo "[W15] iOS import: current NovelWorkspaceImporter"
xcodebuild test-without-building \
    -project "$IOS_XCODE" \
    -scheme iosApp \
    -destination "$IOS_DESTINATION" \
    -derivedDataPath "$DERIVED_DATA" \
    -parallel-testing-enabled NO \
    -only-testing:iosAppTests/W15CrossPlatformRunnerTests/testRealIOSImport \
    > "$RUN_ROOT/ios-import-build.log"

tree_sha256() {
    local directory="$1"
    (
        cd "$directory"
        find . -type f -print | LC_ALL=C sort | while IFS= read -r path; do
            shasum -a 256 "$path"
        done | shasum -a 256 | cut -d ' ' -f 1
    )
}

(
    cd "$RUN_ROOT"
    {
        echo "W15 cross-platform exchange passed"
        echo "ios_export_files=$(find ios-export -type f | wc -l | tr -d ' ')"
        echo "android_export_files=$(find android-export -type f | wc -l | tr -d ' ')"
        echo "ios_reimport_files=$(find ios-reimport -type f | wc -l | tr -d ' ')"
        echo "ios_export_sha256=$(tree_sha256 ios-export)"
        echo "android_export_sha256=$(tree_sha256 android-export)"
        echo "ios_reimport_sha256=$(tree_sha256 ios-reimport)"
    } | tee summary.txt
)

echo "W15 evidence: $RUN_ROOT"
