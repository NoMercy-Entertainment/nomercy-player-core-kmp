// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

// What the library looks like from Swift.
//
// Every other gate in this repo is Kotlin checking Kotlin. This one is the only
// thing that answers the question an iOS engineer will ask first: can I call it,
// and does it read like an API rather than like a transpiler's output.
//
// It is type-checked rather than run. Running it would re-measure behaviour the
// Kotlin gates already measure on the same binary; what cannot be measured from
// Kotlin is whether the surface survives the trip through Objective-C interop —
// a name mangled beyond recognition, an enum that arrives as an opaque box, a
// suspend function with no async form. Those are compile-time facts, and a
// compile is the honest check for them.

import Foundation
import NoMercyPlayerCore

enum SurfaceConformance {

    // The registry an iOS chrome subscribes through.
    static func events() {
        let registry = CoreEvents.shared
        let every: [EventKey] = registry.all

        precondition(every.count == 152, "the base event map should have 152 keys, found \(every.count)")
        precondition(registry.play.name == "play")
        precondition(registry.beforePlay.name == "beforePlay")
        precondition(registry.streamError.name == "stream:error")
    }

    // The value types a SwiftUI view binds to. A struct-like Kotlin data class
    // has to arrive with readable properties, not as an opaque handle.
    static func state() {
        let snapshot = PlayerState(
            phase: .idle,
            playState: .idle,
            setupState: .notSetup,
            time: 0,
            duration: 0,
            buffered: 0,
            volume: 100,
            muted: false,
            volumeState: .unmuted,
            playbackRate: 1,
            item: nil,
            index: -1,
            queueLength: 0,
            repeatState: .off,
            shuffleState: .off,
            bufferState: .idle,
            networkState: .online,
            castState: .unavailable
        )

        precondition(snapshot.volume == 100)
        precondition(snapshot.phase == .idle)
    }

    // The errors an iOS host reports. The code is the string a support ticket
    // quotes, so it has to be reachable and readable.
    static func errors() {
        let parsed = ErrorCode.companion.parse(code: "core:auth/forbidden")

        precondition(parsed.namespace == "core")
        precondition(parsed.reason == "forbidden")
        precondition(PluginErrorCodes.shared.DUPLICATE_ID == "core:plugin/duplicate-id")
    }

    // The quality ladder a quality menu draws. Descriptor-keyed, so a menu can
    // name a rung without holding an index that goes stale.
    static func quality() {
        let hd = QualityDescriptor(height: 1080, bitrate: 6_000_000, dynamicRange: .sdr, codec: "avc1")

        precondition(hd.label() == "1080p")
        precondition(hd.height == 1080)
    }
}
