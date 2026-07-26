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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// AVFoundation's vocabulary against the library's.
//
// On values rather than AVFoundation objects, the same as the other two mappers:
// an AVMediaSelectionOption cannot be constructed in a test, and what is easy to
// get wrong here is a locale string and a float that arrives as NaN.
class AVTrackMapperTest {

    @Test
    fun aLanguageTagIsNormalisedWithoutBeingFlattened() {
        // The region matters — Brazilian and European Portuguese are different
        // tracks — so it is kept. Only the case and the separator move, because
        // AVFoundation is inconsistent about both and a menu selection should
        // not depend on which.
        assertEquals("pt-br", AVTrackMapper.languageOf("pt_BR"))
        assertEquals("zh-hans-cn", AVTrackMapper.languageOf("zh-Hans-CN"))
        assertEquals("nl", AVTrackMapper.languageOf("NL"))
    }

    @Test
    fun aTrackWithoutALanguageIsStillNamed() {
        assertEquals("und", AVTrackMapper.languageOf(null))
        assertEquals("und", AVTrackMapper.languageOf("   "))
    }

    @Test
    fun theDevicesOwnDisplayNameIsTheLabel() {
        // AVFoundation localises it for the device, so a Dutch phone says
        // "Nederlands" rather than "nl". That is the right label and this must
        // not throw it away.
        assertEquals("Nederlands", AVTrackMapper.labelOf("Nederlands", "nl"))
        assertEquals("nl", AVTrackMapper.labelOf("", "nl"))
        assertEquals("und", AVTrackMapper.labelOf(null, null))
    }

    @Test
    fun forcedSubtitlesAreRecognisedByTheirCharacteristic() {
        // The alien dialogue in an otherwise English film. Unmarked, it appears
        // as a second identical English row the viewer has to guess between.
        assertTrue(AVTrackMapper.isForced(listOf("public.subtitles.forced-only")))
        assertFalse(AVTrackMapper.isForced(listOf("public.accessibility.transcribes-spoken-dialog")))
        assertFalse(AVTrackMapper.isForced(emptyList()))
    }

    @Test
    fun aTrackForTheHardOfHearingIsDistinguishable() {
        // It carries the same language as the plain one, so without this the two
        // are the same row twice.
        assertTrue(
            AVTrackMapper.describesMusicAndSound(
                listOf("public.accessibility.describes-music-and-sound"),
            ),
        )
        assertFalse(AVTrackMapper.describesMusicAndSound(listOf("public.subtitles.forced-only")))
    }

    @Test
    fun anUnknownBitrateIsZeroRatherThanNonsense() {
        // AVFoundation's estimated data rate is NaN before the asset is read and
        // negative on some live streams. Either sorts a ladder into nonsense.
        assertEquals(0, AVTrackMapper.bitrateOf(Float.NaN))
        assertEquals(0, AVTrackMapper.bitrateOf(-1f))
        assertEquals(0, AVTrackMapper.bitrateOf(0f))
        assertEquals(6_000_000, AVTrackMapper.bitrateOf(6_000_000f))
    }
}
