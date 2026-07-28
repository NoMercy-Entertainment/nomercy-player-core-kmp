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

        // Play, not play. Kotlin/Native preserves a property's Kotlin name, so
        // the registry reads in Kotlin's convention from Swift. Worth knowing
        // before the iOS docs are written, and worth leaving alone: making it
        // Swifty needs @ObjCName, which is a Native-only annotation and this
        // registry is common code shared with Android and desktop.
        precondition(registry.Play.name == "play")
        precondition(registry.BeforePlay.name == "beforePlay")
        precondition(registry.StreamError.name == "stream:error")
    }

    // The value types a SwiftUI view binds to. A struct-like Kotlin data class
    // has to arrive with readable properties, not as an opaque handle.
    static func state() {
        // Every field, because Kotlin default arguments do not cross to Swift:
        // PlayerState() is unavailable here even though it is the common way to
        // build one from Kotlin. Worth knowing before the iOS docs promise
        // otherwise, and worth a factory on the Apple side eventually.
        let snapshot = PlayerState(
            phase: PlayerPhase.idle,
            playState: PlayState.idle,
            setupState: SetupState.notSetup,
            time: 0,
            duration: 0,
            buffered: 0,
            volume: 100,
            muted: false,
            volumeState: VolumeState.unmuted,
            playbackRate: 1,
            item: nil,
            index: -1,
            queueLength: 0,
            repeatState: RepeatState.off,
            shuffleState: ShuffleState.off,
            bufferState: BufferState.idle,
            networkState: NetworkState.online,
            castState: CastState.unavailable
        )

        // A SwiftUI view binds to these, so they have to arrive as readable
        // properties rather than as an opaque handle.
        precondition(snapshot.volume == 100)
        precondition(snapshot.index == -1)
        precondition(snapshot.item == nil)
    }

    // The errors an iOS host reports. The code is the string a support ticket
    // quotes, so it has to be reachable and readable.
    static func errors() {
        let parsed = ErrorCode.Companion.shared.parse(code: "core:auth/forbidden")

        precondition(parsed.namespace == "core")
        precondition(parsed.reason == "forbidden")
        precondition(PluginErrorCodes.shared.DUPLICATE_ID == "core:plugin/duplicate-id")
    }

    // The question an app actually asks: can I build one and drive it.
    //
    // This is where interop either works or does not. A suspend function has to
    // arrive as Swift async or every transport call becomes a callback; a
    // StateFlow has to arrive as something a SwiftUI view can observe; and the
    // constructor has to be reachable without handing it a CoroutineScope.
    static func player() async throws {
        // Every argument, because Kotlin defaults do not cross to Objective-C.
        //
        // This list is the cost, written out. It also grows on its own: the
        // constructor gained announcer, castSender, platform, clock, fetcher,
        // video and playerId over the campaign, and each one arrived here as a
        // compile error nobody saw, because nothing ran this file. That is what
        // ./check.sh is for now, and why it is in CI.
        let player = ComposedPlayer(
            backend: nil,
            logger: SilentLogger.shared,
            storage: InMemoryStorage(),
            translator: nil,
            announcer: nil,
            castSender: nil,
            platform: UnconfiguredPlatform.shared,
            clock: ClockKt.defaultClock(),
            fetcher: nil,
            video: nil,
            scope: nil,
            playerId: "swift-surface"
        )

        // Every field of both, because Kotlin defaults do not cross. This is
        // what `player.play()` costs an iOS engineer today, written out so the
        // cost is visible rather than described. The fix is an Apple-side facade
        // and it belongs with the SwiftUI chrome, not bolted onto core.
        try await player.setup(config: PlayerConfig(
            baseUrl: nil,
            baseImageUrl: nil,
            language: nil,
            defaultVolume: 100,
            metricsIntervalMs: 10_000,
            progressIntervalMs: 5_000,
            inactivityMs: 4_000,
            beforeEventTimeoutMs: 10_000,
            pluginInitTimeoutMs: 30_000,
            itemEndingSoonThreshold: 10,
            preloadLeadSeconds: 10,
            crossfadeLeadSeconds: 3,
            crossfadeTailSeconds: 3,
            crossfadeEnabled: false,
            pauseWhenHidden: false,
            controls: false,
            wakeLock: WakeLockPolicy.auto,
            onOffline: OfflinePolicy.continueBuffered
        ))
        try await player.play(opts: ActionOptions(source: nil, silent: false, autoplay: false))

        // state_(), not state(). Kotlin/Native renamed it around the stateFlow
        // property, and an iOS engineer reading the header will wonder why.
        let snapshot = player.state_()
        precondition(snapshot.playState == PlayState.playing)

        // What a SwiftUI view observes.
        let flow = player.stateFlow
        precondition(flow.value.volume == 100)
    }

    // The quality ladder a quality menu draws. Descriptor-keyed, so a menu can
    // name a rung without holding an index that goes stale.
    static func quality() {
        let hd = QualityDescriptor(height: 1080, bitrate: 6_000_000, dynamicRange: DynamicRange.sdr, codec: "avc1")

        precondition(hd.label() == "1080p")
        precondition(hd.height == 1080)
    }
}
