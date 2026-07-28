// swift-tools-version:5.9
import Foundation
import PackageDescription

// The headless engine, for a Swift app that draws its own player.
//
// Core ships no chrome, and still does not: an application that wants controls
// takes the video or music package, whose drop-in product is the SwiftUI
// player. What it now also ships is a second, separate product holding the
// debug surfaces — the inspector overlay and nothing else.
//
// A separate product rather than a second tier of the first: an application
// taking the engine gets the engine, exactly as before, and has to ask for the
// debug view by name. A debug tool that arrives with the engine is a debug tool
// that ships to production because nobody chose it.
//
// It lives here rather than in the two player packages because the inspector it
// renders is core's. Putting it in the video package would make it a video
// feature the music package has to copy, and two copies of one debug tool is
// how one of them stops being maintained.
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
        .library(name: "NoMercyPlayerDevTools", targets: ["NoMercyPlayerDevTools"]),
    ],
    targets: [
        engine,
        .target(name: "NoMercyPlayerDevTools", dependencies: ["NoMercyPlayerCore"]),
        .testTarget(
            name: "NoMercyPlayerDevToolsTests",
            dependencies: ["NoMercyPlayerDevTools"]
        ),
    ]
)
