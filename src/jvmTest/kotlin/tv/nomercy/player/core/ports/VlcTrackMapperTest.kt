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

// libVLC's vocabulary against the library's.
//
// This is the translation that decides whether a quality chosen on a phone can
// be restored on a desktop. libVLC says "h264" where a manifest says
// "avc1.640028" and Media3 reports "avc1"; if the two do not meet, the
// descriptor matcher finds nothing and the player silently falls back to
// automatic — the failure looks like a preference that will not stick.
class VlcTrackMapperTest {

    @Test
    fun libVlcsCodecNamesBecomeTheFamiliesEveryEngineCompares() {
        assertEquals("avc1", VlcTrackMapper.codecFamily("h264"))
        assertEquals("hvc1", VlcTrackMapper.codecFamily("hevc"))
        assertEquals("av01", VlcTrackMapper.codecFamily("av1"))
        assertEquals("mp4a", VlcTrackMapper.codecFamily("aac"))
        assertEquals("ac-3", VlcTrackMapper.codecFamily("a52"))
    }

    @Test
    fun theTranslationIsCaseInsensitive() {
        // libVLC's casing varies with the demuxer that reported the track.
        assertEquals("hvc1", VlcTrackMapper.codecFamily("HEVC"))
        assertEquals("avc1", VlcTrackMapper.codecFamily("H264"))
    }

    @Test
    fun aCodecNobodyTranslatedKeepsItsOwnName() {
        // Better identified by its own name than by a blank, which would
        // collide with every other unknown and make two different streams match.
        assertEquals("theora", VlcTrackMapper.codecFamily("Theora"))
        assertEquals("unknown", VlcTrackMapper.codecFamily(null))
        assertEquals("unknown", VlcTrackMapper.codecFamily(""))
    }

    @Test
    fun theDesktopReportsSdrBecauseLibVlcDoesNotSayOtherwise() {
        // libVLC exposes no transfer characteristics through its track info.
        // Guessing HDR from a codec would be wrong on the majority of HEVC,
        // which is the mistake the Media3 mapper avoids by reading the transfer
        // function — a field this engine does not offer.
        assertEquals(DynamicRange.SDR, VlcTrackMapper.dynamicRange())
    }

    @Test
    fun aTrackWithoutALanguageIsStillNamed() {
        // libVLC leaves language empty rather than null on plenty of tracks, and
        // a menu row titled "" is a row a viewer cannot choose.
        assertEquals("und", VlcTrackMapper.languageOf(""))
        assertEquals("und", VlcTrackMapper.languageOf(null))
        assertEquals("nl", VlcTrackMapper.languageOf("nl"))
    }

    @Test
    fun aLabelFallsBackThroughDescriptionThenLanguage() {
        assertEquals("Director commentary", VlcTrackMapper.labelOf("Director commentary", "en"))
        assertEquals("en", VlcTrackMapper.labelOf("", "en"))
        assertEquals("und", VlcTrackMapper.labelOf(null, null))
    }

    @Test
    fun anUnmeasuredBitrateIsZeroRatherThanNegative() {
        // libVLC reports 0 for a track it has not measured, which is most local
        // files, and negative for some. A made-up number would sort a ladder
        // wrongly and a negative one would sort it upside down.
        assertEquals(0, VlcTrackMapper.bitrateOf(0))
        assertEquals(0, VlcTrackMapper.bitrateOf(-1))
        assertEquals(6_000_000, VlcTrackMapper.bitrateOf(6_000_000))
    }
}
