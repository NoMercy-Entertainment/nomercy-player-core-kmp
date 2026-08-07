// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports.engines

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The rules that decide which engine plays, driven against stand-ins.
//
// Stand-ins rather than the real providers, because the answer for libVLC and
// libmpv depends on what is installed on the machine running the test — and the
// thing under test is the DECISION, which must be the same on a machine with
// both engines and a machine with neither.
class VideoEnginesTest {

    private class StubEngine(
        override val id: String,
        private val available: Boolean,
    ) : VideoEngineProvider {
        override fun isAvailable(): Boolean = available
        override fun whyUnavailable(): String? = if (available) null else "$id is not installed here"
        override fun create(): Nothing = error("no engine is constructed in this test")
    }

    private fun select(requested: String?, vararg engines: VideoEngineProvider): EngineSelection =
        VideoEngines.selectFrom(engines.toList(), requested)

    @Test
    fun anExplicitRequestThatCannotRunIsRefusedRatherThanSwappedOut() {
        // The failure this whole seam exists to prevent. Asking for mpv and
        // silently getting VLC is how a migration reports itself green while
        // never having run once — every fixture passes, on the old engine.
        val decision: EngineSelection = select(MPV, StubEngine(VLC, true), StubEngine(MPV, false))

        val refusal = decision as? EngineSelection.None
        assertTrue(refusal != null, "an unavailable request was quietly satisfied by another engine")
        assertTrue(refusal.reason.contains(MPV), "the refusal does not name what was asked for: ${refusal.reason}")
    }

    @Test
    fun anExplicitRequestWinsOverPreferenceOrder() {
        val decision: EngineSelection = select(MPV, StubEngine(VLC, true), StubEngine(MPV, true))

        assertEquals(MPV, (decision as EngineSelection.Chosen).provider.id)
    }

    @Test
    fun withNoRequestTheFirstAvailableEngineIsChosen() {
        // Preference order, not registration order by accident: an engine leads
        // only once it is proven, so the fallback has to be the one that plays.
        val decision: EngineSelection = select(null, StubEngine(VLC, false), StubEngine(MPV, true))

        assertEquals(MPV, (decision as EngineSelection.Chosen).provider.id)
    }

    @Test
    fun anUnknownIdIsSaidOutLoud() {
        val decision: EngineSelection = select(UNKNOWN, StubEngine(VLC, true))

        assertTrue((decision as EngineSelection.None).reason.contains(UNKNOWN))
    }

    @Test
    fun aRequestArrivesFromAConfigFileWithWhitespaceOnIt() {
        val decision: EngineSelection = select("  MPV \n", StubEngine(VLC, true), StubEngine(MPV, true))

        assertEquals(MPV, (decision as EngineSelection.Chosen).provider.id)
    }

    @Test
    fun noEngineAtAllReportsEveryReason() {
        // A desktop with nothing installed has to be able to say why, per
        // engine. "Playback failed" sends a developer looking in the player.
        val decision: EngineSelection = select(null, StubEngine(VLC, false), StubEngine(MPV, false))

        val reason: String = (decision as EngineSelection.None).reason
        assertTrue(reason.contains(VLC) && reason.contains(MPV), "not every engine was accounted for: $reason")
    }

    @Test
    fun bothEnginesAreRegisteredWhetherOrNotThisMachineCanRunThem() {
        // Side by side is the point of this stage: mpv has to be selectable on
        // a machine that has it, without VLC being removed first.
        assertEquals(listOf(VLC, MPV), VideoEngines.registered.map { provider -> provider.id })
        assertNull(VideoEngines.byId(UNKNOWN))
    }
}

private const val VLC = "vlc"
private const val MPV = "mpv"
private const val UNKNOWN = "gstreamer"
