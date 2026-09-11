// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The browser audio engine, checked against what a real `<audio>` element
 * actually reports.
 *
 * Nothing here loads media. A karma browser has no NoMercy server to fetch from
 * and no user gesture to permit sound, so a test that played something would be
 * asserting on the browser's autoplay policy rather than on this engine. What is
 * left is still the part that used to be wrong in the app's own hand-rolled
 * version: what an empty element reports, and whether the second element exists
 * before anything asked for it.
 */
class Html5AudioBackendTest {

    @Test
    fun anEngineThatHasLoadedNothingIsIdleRatherThanLoading() {
        // LOADING would put a spinner over a player nobody has asked to play
        // anything yet, which is how a cast receiver looks broken on arrival.
        assertEquals(BackendState.IDLE, Html5MediaBackend().state())
        assertEquals(BackendState.IDLE, Html5AudioBackend().state())
    }

    @Test
    fun anEmptyElementReportsNoDurationRatherThanNotANumber() {
        // A media element with no source reports NaN. A progress bar dividing by
        // that draws nothing at all, so the contract's answer is zero.
        assertEquals(0.0, Html5MediaBackend().duration())
        assertEquals(0.0, Html5AudioBackend().duration())
    }

    @Test
    fun volumeIsRememberedAndClampedToWhatAnElementAccepts() {
        val backend = Html5MediaBackend()

        backend.volume(0.5f)
        assertEquals(0.5f, backend.volume())

        // An element throws on a value outside 0..1, so the backend clamps
        // rather than passing a crossfade's overshoot straight through.
        backend.volume(1.5f)
        assertEquals(1f, backend.volume())
        backend.volume(-1f)
        assertEquals(0f, backend.volume())
    }

    @Test
    fun theSecondElementIsNotBuiltUntilACrossfadeAsksForOne() {
        // Two elements per player, built eagerly, is two connections to the
        // media server for every receiver that never crossfades anything.
        val built: MutableList<Html5MediaBackend> = mutableListOf()
        val backend = Html5AudioBackend { Html5MediaBackend().also { built += it } }

        assertEquals(1, built.size)
        assertEquals(0f, backend.secondaryGain())

        // And a gain written before there is a second element is a no-op, not a
        // crash: the transition strategy sets it before deciding to fade.
        backend.secondaryGain(0.3f)
        assertEquals(0f, backend.secondaryGain())
    }

    @Test
    fun disposingASecondElementThatWasNeverBuiltDoesNothing() {
        // The strategy calls this on every hard cut, including the first one.
        val backend = Html5AudioBackend()
        backend.disposeSecondary()
        assertEquals(0f, backend.secondaryGain())
    }

    @Test
    fun theEngineSaysItCanCrossfadeSoTheStrategyDoesNotHardCutEveryTrack() {
        assertTrue(Html5AudioBackend().supportsCrossfade())
    }

    @Test
    fun aReleasedEngineGoesBackToIdle() {
        val backend = Html5AudioBackend()
        backend.release()
        assertEquals(BackendState.IDLE, backend.state())
    }
}
