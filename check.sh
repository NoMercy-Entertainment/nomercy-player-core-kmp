#!/usr/bin/env bash
# Build and test the Swift half of core, on iOS and on tvOS.
#
# Core ships no chrome, so what builds here is the debug product — the inspector
# view and its feed — plus the surface type-check that has always run.
#
# macOS only, and it says so rather than failing obscurely elsewhere. Run from
# the module root:
#
#   ./gradlew assembleNoMercyPlayerCoreXCFramework
#   ./check.sh
set -euo pipefail

if ! command -v xcodebuild >/dev/null 2>&1; then
  echo "xcodebuild not found — the Apple views build on macOS" >&2
  exit 0
fi

FRAMEWORK="build/XCFrameworks/release/NoMercyPlayerCore.xcframework"
if [ ! -d "$FRAMEWORK" ]; then
  echo "no xcframework — run ./gradlew assembleNoMercyPlayerCoreXCFramework first" >&2
  exit 1
fi

# Both platforms build, because tvOS is the one with no app to fall back on and
# a view that only ever compiled for iOS would be a surprise on the day someone
# needs it.
for destination in "generic/platform=iOS" "generic/platform=tvOS"; do
  echo "building for $destination"
  xcodebuild build \
    -scheme NoMercyPlayerCore-Package \
    -destination "$destination" \
    -quiet
done

# By id, not by name. Simulator names carry their generation in brackets —
# "Apple TV 4K (3rd generation)" — and matching the readable part of that gives
# a name no device has, which xcodebuild reports by listing every destination it
# does know about, visionOS included.
device_id() {
  xcrun simctl list devices available | grep -m1 "$1" | grep -oE '[0-9A-F]{8}(-[0-9A-F]{4}){3}-[0-9A-F]{12}'
}

# The -Package scheme, not the product one. SPM generates a scheme per product
# plus one for the package, and only the package scheme carries the test target.
run_tests() {
  local udid
  udid="$(device_id "$2")"
  if [ -z "$udid" ]; then
    echo "no $1 simulator installed — install one from Xcode > Settings > Components" >&2
    exit 1
  fi
  echo "testing on $2 ($udid)"
  xcodebuild test -scheme NoMercyPlayerCore-Package -destination "id=$udid" -quiet
}

run_tests iOS "iPhone"
run_tests tvOS "Apple TV"

# The older check, kept. It type-checks a file naming the engine's symbols, which
# is a different question from whether the debug view compiles: the view names a
# handful of them and the conformance file names the surface.
./swift-conformance/check.sh

echo "Apple: core builds on iOS and tvOS, devtools suites green on both"
