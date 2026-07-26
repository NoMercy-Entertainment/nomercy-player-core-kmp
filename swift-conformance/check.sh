#!/usr/bin/env bash
# Type-check the Swift surface against the built XCFramework.
#
# macOS only, and it says so rather than failing obscurely elsewhere. Run from
# the module root after assembling the framework:
#
#   ./gradlew assembleNoMercyPlayerCoreXCFramework
#   ./swift-conformance/check.sh
set -euo pipefail

if ! command -v swiftc >/dev/null 2>&1; then
  echo "swiftc not found — the Swift surface check runs on macOS" >&2
  exit 0
fi

FRAMEWORKS="build/XCFrameworks/release/NoMercyPlayerCore.xcframework/ios-arm64-simulator"
if [ ! -d "$FRAMEWORKS" ]; then
  FRAMEWORKS="$(find build/XCFrameworks/release/NoMercyPlayerCore.xcframework -maxdepth 1 -type d -name 'ios-*simulator' | head -1)"
fi

if [ ! -d "$FRAMEWORKS" ]; then
  echo "no iOS simulator slice — run ./gradlew assembleNoMercyPlayerCoreXCFramework first" >&2
  exit 1
fi

SDK="$(xcrun --sdk iphonesimulator --show-sdk-path)"
TARGET="arm64-apple-ios15.0-simulator"

swiftc -typecheck \
  -target "$TARGET" \
  -sdk "$SDK" \
  -F "$FRAMEWORKS" \
  swift-conformance/SurfaceConformance.swift

echo "Swift surface conformance: the library type-checks from Swift"
