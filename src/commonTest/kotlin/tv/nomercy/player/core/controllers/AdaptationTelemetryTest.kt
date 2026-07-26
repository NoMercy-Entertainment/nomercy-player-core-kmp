// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.controllers

import tv.nomercy.player.core.events.CoreEvents
import tv.nomercy.player.core.events.StreamFragmentLoadedPayload
import tv.nomercy.player.core.events.StreamLevelConsideredPayload
import tv.nomercy.player.core.events.StreamLevelSwitchedPayload
import tv.nomercy.player.core.events.StreamManifestLoadedPayload
import tv.nomercy.player.core.ports.CanonicalBackendEvent
import kotlin.test.Test
import kotlin.test.assertEquals

// What the adaptive pipeline says about itself.
//
// Telemetry, not the way to learn the current quality — quality() is that. Only
// the engines that own their manifest parsing emit these; AVFoundation and
// libVLC adapt behind a closed door and say nothing, which is why a consumer
// binding to them for state rather than for a support log would be wrong on two
// platforms out of three.
class AdaptationTelemetryTest {

    @Test
    fun aManifestLoadingReachesTheConsumerUnchanged() {
        val backend = FakeMediaBackend()
        val player = ComposedPlayer(backend = backend)
        val seen: MutableList<String> = mutableListOf()
        player.on(CoreEvents.StreamManifestLoaded) { seen += it.url }

        backend.fire(CanonicalBackendEvent.MANIFEST_LOADED, StreamManifestLoadedPayload("https://s.test/master.m3u8"))

        assertEquals(listOf("https://s.test/master.m3u8"), seen)
    }

    @Test
    fun aFragmentCarriesItsUrlAndDuration() {
        // Reconstructing these in the bridge would be a second, worse source of
        // a fact the engine already knows exactly.
        val backend = FakeMediaBackend()
        val player = ComposedPlayer(backend = backend)
        val seen: MutableList<StreamFragmentLoadedPayload> = mutableListOf()
        player.on(CoreEvents.StreamFragmentLoaded) { seen += it }

        backend.fire(
            CanonicalBackendEvent.FRAGMENT_LOADED,
            StreamFragmentLoadedPayload(url = "https://s.test/720p/3.ts", durationMs = 6_000.0),
        )

        assertEquals(listOf(StreamFragmentLoadedPayload("https://s.test/720p/3.ts", 6_000.0)), seen)
    }

    @Test
    fun aRungConsideredAndRejectedSaysWhy() {
        // The event that makes an adaptation decision auditable. Without the
        // reason, a support log records that the player chose 480p on a fast
        // connection and nothing about what it was weighing.
        val backend = FakeMediaBackend()
        val player = ComposedPlayer(backend = backend)
        val seen: MutableList<StreamLevelConsideredPayload> = mutableListOf()
        player.on(CoreEvents.StreamLevelConsidered) { seen += it }

        backend.fire(
            CanonicalBackendEvent.LEVEL_CONSIDERED,
            StreamLevelConsideredPayload(candidate = 3.0, decided = 1.0, reason = "insufficient-bandwidth"),
        )

        assertEquals("insufficient-bandwidth", seen.single().reason)
        assertEquals(1.0, seen.single().decided)
    }

    @Test
    fun aSwitchThatHappenedIsAnnouncedWithItsLabel() {
        val backend = FakeMediaBackend()
        val player = ComposedPlayer(backend = backend)
        val seen: MutableList<String> = mutableListOf()
        player.on(CoreEvents.StreamLevelSwitched) { seen += it.label }

        backend.fire(CanonicalBackendEvent.LEVEL_SWITCHED, StreamLevelSwitchedPayload(level = 2.0, label = "1080p"))

        assertEquals(listOf("1080p"), seen)
    }

    @Test
    fun aMalformedPayloadIsDroppedRatherThanGuessedAt() {
        // A fragment event with no fragment tells a consumer nothing, and
        // inventing an empty url would put a lie in a support log.
        val backend = FakeMediaBackend()
        val player = ComposedPlayer(backend = backend)
        var announcements = 0
        player.on(CoreEvents.StreamFragmentLoaded) { announcements += 1 }

        backend.fire(CanonicalBackendEvent.FRAGMENT_LOADED, "not a payload")
        backend.fire(CanonicalBackendEvent.FRAGMENT_LOADED, null)

        assertEquals(0, announcements)
    }

    @Test
    fun anEngineThatSaysNothingAnnouncesNothing() {
        // AVFoundation and libVLC. Silence here is correct rather than a gap:
        // they adapt without narrating it, and a synthesised event would claim
        // knowledge the library does not have.
        val player = ComposedPlayer(backend = FakeMediaBackend())
        var announcements = 0
        player.on(CoreEvents.StreamLevelSwitched) { announcements += 1 }

        assertEquals(0, announcements)
    }
}
