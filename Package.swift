// swift-tools-version:5.9
import Foundation
import PackageDescription

// The headless engine, for a Swift app that draws its own player.
//
// Core ships one tier and only one: there is no chrome in it. An application
// that wants controls takes the video or music package, whose drop-in product is
// the SwiftUI player; an application that has already drawn its own takes this
// and gets the engine with nothing on top.
//
// Two shapes of the same target, chosen by an environment variable. A released
// consumer resolves a checksummed zip from the tag; somebody working on this
// repo has no tag yet and needs the framework they just built, and a manifest
// that only had the released form would leave them editing a package they cannot
// resolve. The default is the local one because that is who runs this manifest
// most: a release is a job, and the job sets the variable.
let releasing = ProcessInfo.processInfo.environment["NOMERCY_SPM_RELEASE"] == "1"

let engine: Target = releasing
    ? .binaryTarget(
        name: "NoMercyPlayerCore",
        url: "https://github.com/NoMercy-Entertainment/nomercy-player-core-kmp/releases/download/v2.0.0-rc.1/NoMercyPlayerCore.xcframework.zip",
        // Filled by the release job from tools/package-xcframework.sh. A
        // binaryTarget without a real checksum resolves whatever the URL serves,
        // which is a build that changes under a consumer who changed nothing.
        checksum: "REPLACED_BY_RELEASE_JOB"
    )
    : .binaryTarget(
        name: "NoMercyPlayerCore",
        path: "build/XCFrameworks/release/NoMercyPlayerCore.xcframework"
    )

let package = Package(
    name: "NoMercyPlayerCore",
    platforms: [.iOS(.v15), .tvOS(.v15)],
    products: [
        .library(name: "NoMercyPlayerCore", targets: ["NoMercyPlayerCore"]),
    ],
    targets: [engine]
)
