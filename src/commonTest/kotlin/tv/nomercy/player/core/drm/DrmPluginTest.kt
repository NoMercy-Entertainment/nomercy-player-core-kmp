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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// An engine asked to play something it cannot decrypt.
//
// Listened to on the namespaced key a consumer actually gets, not the bare one
// the plugin publishes under. Subscribing to the wrong one is silence rather
// than an error, which is why the pair is exported instead of spelled here.
//
// The rule under all of it is that this degrades rather than fails. A device
// that cannot run one scheme can very often play another version of the same
// film, and a chrome handed an exception has nothing left to offer.
class DrmPluginTest {

    private class Engine(private val runs: Set<DrmScheme>) : DrmCapability {
        override fun supports(scheme: DrmScheme): Boolean = scheme in runs
    }

    private suspend fun playerWith(plugin: DrmPlugin): Pair<ComposedPlayer, MutableList<String>> {
        val player = ComposedPlayer(backend = FakeMediaBackend())
        val announced: MutableList<String> = mutableListOf()
        player.on(DrmEvents.UnsupportedOnPlayer) { announced += it.code }
        player.setup()
        player.addPlugin(plugin)
        return player to announced
    }

    @Test
    fun anEngineThatCannotRunTheSchemeSaysSoInsteadOfThrowing() = runTest {
        val plugin = DrmPlugin(DrmConfig(DrmScheme.WIDEVINE), Engine(setOf(DrmScheme.AES_128)))

        val (_, announced) = playerWith(plugin)

        assertEquals(listOf(DrmErrorCodes.UNSUPPORTED_SCHEME), announced)
    }

    @Test
    fun itNamesTheSchemeThatWillNotPlay() = runTest {
        // A chrome offering another version needs to know which one to avoid.
        // "Something failed" leaves it choosing at random.
        val plugin = DrmPlugin(DrmConfig(DrmScheme.FAIRPLAY), Engine(setOf(DrmScheme.AES_128)))
        val player = ComposedPlayer(backend = FakeMediaBackend())
        val seen: MutableList<DrmScheme> = mutableListOf()
        player.on(DrmEvents.UnsupportedOnPlayer) { seen += it.scheme }
        player.setup()

        player.addPlugin(plugin)

        assertEquals(listOf(DrmScheme.FAIRPLAY), seen)
    }

    @Test
    fun anEngineThatCanRunItStaysQuiet() = runTest {
        // The other half. A plugin that announced unsupported whatever happened
        // would pass the case above and be useless.
        val plugin = DrmPlugin(DrmConfig(DrmScheme.AES_128), Engine(setOf(DrmScheme.AES_128)))

        val (_, announced) = playerWith(plugin)

        assertEquals(emptyList(), announced)
    }

    @Test
    fun whetherItWillPlayCanBeAskedBeforeAnythingHasBeenTried() = runTest {
        // Asked at install rather than at the first key, so a chrome can choose
        // another version before a viewer has watched a spinner for it.
        val plugin = DrmPlugin(DrmConfig(DrmScheme.WIDEVINE), Engine(setOf(DrmScheme.AES_128)))
        playerWith(plugin)

        assertFalse(plugin.isPlayable)
    }

    @Test
    fun aPlayerWithNoDrmAwareEngineRefusesRatherThanClaimingItCanDecrypt() = runTest {
        // The default. Claiming support it would then fail to deliver moves the
        // failure to the worst possible moment, which is halfway through.
        val plugin = DrmPlugin(DrmConfig(DrmScheme.WIDEVINE))

        val (_, announced) = playerWith(plugin)

        assertEquals(listOf(DrmErrorCodes.UNSUPPORTED_SCHEME), announced)
    }

    @Test
    fun unprotectedContentPlaysOnAnEngineThatDoesNoDrmAtAll() = runTest {
        // Most content. A default that refused everything would make the plugin
        // impossible to install on a player that mostly plays clear files.
        val plugin = DrmPlugin(DrmConfig(DrmScheme.CLEAR))

        val (_, announced) = playerWith(plugin)

        assertEquals(emptyList(), announced)
        assertTrue(plugin.isPlayable)
    }

    @Test
    fun theManifestMatchesTheWebPlugin() {
        assertEquals("drm", DrmPlugin.Manifest.id)
        assertEquals("2.0.0", DrmPlugin.Manifest.version)
    }
}
