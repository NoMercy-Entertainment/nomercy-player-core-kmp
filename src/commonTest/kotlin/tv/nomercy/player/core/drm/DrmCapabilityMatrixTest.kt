// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.drm

import kotlinx.coroutines.test.runTest
import tv.nomercy.player.core.controllers.ComposedPlayer
import tv.nomercy.player.core.controllers.FakeMediaBackend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The rule that has to hold on every engine, whatever it can decrypt.
//
// Written against the shape of a capability rather than against the three real
// ones, because each of those lives in a source set the others cannot see. What
// is shared is the promise: a scheme an engine cannot run degrades, and AES-128
// runs everywhere.
class DrmCapabilityMatrixTest {

    private class Engine(val name: String, private val runs: Set<DrmScheme>) : DrmCapability {
        override fun supports(scheme: DrmScheme): Boolean = scheme in runs
    }

    // The audit's matrix, spelled once. The real implementations mirror it in
    // their own source sets and are each covered where they compile.
    private val engines: List<Engine> = listOf(
        Engine("ExoPlayer", setOf(DrmScheme.AES_128, DrmScheme.WIDEVINE, DrmScheme.CLEAR)),
        Engine("AVPlayer", setOf(DrmScheme.AES_128, DrmScheme.FAIRPLAY, DrmScheme.CLEAR)),
        Engine("VLCJ", setOf(DrmScheme.AES_128, DrmScheme.CLEAR)),
    )

    private suspend fun announcementsFor(scheme: DrmScheme, engine: DrmCapability): List<DrmScheme> {
        val player = ComposedPlayer(backend = FakeMediaBackend())
        val refused: MutableList<DrmScheme> = mutableListOf()
        player.on(DrmEvents.UnsupportedOnPlayer) { refused += it.scheme }
        player.setup()
        player.addPlugin(DrmPlugin(DrmConfig(scheme), engine))
        return refused
    }

    @Test
    fun everyEngineCanPlayTheSchemeThatNeedsNoKeyBox() = runTest {
        // The acceptance this subsystem is actually judged on. AES-128 is the
        // protection a self-hosted install can really ship, so an engine that
        // could not honour it would leave that install with nothing.
        for (engine in engines) {
            assertEquals(
                emptyList(),
                announcementsFor(DrmScheme.AES_128, engine),
                "${engine.name} refused AES-128",
            )
        }
    }

    @Test
    fun everyEngineCanPlayContentThatIsNotProtectedAtAll() = runTest {
        for (engine in engines) {
            assertEquals(emptyList(), announcementsFor(DrmScheme.CLEAR, engine), "${engine.name} refused clear content")
        }
    }

    @Test
    fun aSchemeAnEngineCannotRunIsRefusedRatherThanAttempted() = runTest {
        // Desktop has no key box of any kind, which is a fact about the product
        // rather than a gap: a studio key box on a desktop is a vendor SDK and a
        // signed application.
        val desktop: Engine = engines.first { it.name == "VLCJ" }

        assertEquals(listOf(DrmScheme.WIDEVINE), announcementsFor(DrmScheme.WIDEVINE, desktop))
        assertEquals(listOf(DrmScheme.FAIRPLAY), announcementsFor(DrmScheme.FAIRPLAY, desktop))
    }

    @Test
    fun eachStudioSchemeRunsOnItsOwnPlatformAndNotTheOther() = runTest {
        // A stream packaged for one is one the other should be told about early,
        // rather than at the first key with a spinner already on screen.
        val android: Engine = engines.first { it.name == "ExoPlayer" }
        val apple: Engine = engines.first { it.name == "AVPlayer" }

        assertEquals(emptyList(), announcementsFor(DrmScheme.WIDEVINE, android))
        assertEquals(listOf(DrmScheme.WIDEVINE), announcementsFor(DrmScheme.WIDEVINE, apple))
        assertEquals(emptyList(), announcementsFor(DrmScheme.FAIRPLAY, apple))
        assertEquals(listOf(DrmScheme.FAIRPLAY), announcementsFor(DrmScheme.FAIRPLAY, android))
    }

    @Test
    fun noEngineClaimsAStudioSchemeWithNoLicenceEndpointBehindIt() = runTest {
        // Capability is about the device. Whether a licence can be fetched is
        // about the server, and today it cannot be on any of them.
        for (engine in engines) {
            for (scheme in listOf(DrmScheme.WIDEVINE, DrmScheme.FAIRPLAY, DrmScheme.PLAYREADY)) {
                val blocked = licenseUrlOrBlocked(DrmConfig(scheme))
                assertTrue(blocked != null, "${engine.name} would have attempted $scheme with no endpoint")
            }
        }
    }
}
