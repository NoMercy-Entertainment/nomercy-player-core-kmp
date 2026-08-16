// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import kotlin.test.Test
import kotlin.test.assertEquals

// The decisions inside the Media3 mapper, against plain values.
//
// Not against a Format. Building one needs an Android runtime, and the first
// version of this file reached for Robolectric to get it — which then could not
// load its native runtime on the CI runner and turned eight passing tests red
// for a reason that had nothing to do with the mapping.
//
// What is actually easy to get wrong here is a string and an integer: which
// field the dynamic range comes from, how much of an RFC 6381 codec descriptor
// to keep. Those take values directly and are provable anywhere. Walking a real
// Tracks object is the thin part, and it belongs on a device.
class ExoTrackMapperTest {

    @Test
    fun theCodecIsTheFamilyRatherThanTheWholeDescriptor() {
        // "hvc1.2.4.L153.B0" is what a manifest carries. Keeping the profile
        // makes one rung unmatchable across two manifests of the same film,
        // because the level digits change with the encode.
        assertEquals("hvc1", ExoTrackMapper.codecFamily("hvc1.2.4.L153.B0", MimeTypes.VIDEO_H265))
        assertEquals("avc1", ExoTrackMapper.codecFamily("avc1.640028", MimeTypes.VIDEO_H264))
        assertEquals("av01", ExoTrackMapper.codecFamily("av01.0.08M.08", MimeTypes.VIDEO_AV1))
    }

    @Test
    fun aMissingCodecFallsBackToTheMimeType() {
        // Progressive MP4 carries no codecs attribute at all, and a rung
        // labelled with an empty codec matches nothing.
        assertEquals("hvc1", ExoTrackMapper.codecFamily(null, MimeTypes.VIDEO_H265))
        assertEquals("avc1", ExoTrackMapper.codecFamily("", MimeTypes.VIDEO_H264))
        assertEquals("mp4a", ExoTrackMapper.codecFamily(null, MimeTypes.AUDIO_AAC))
        assertEquals("ec-3", ExoTrackMapper.codecFamily(null, MimeTypes.AUDIO_E_AC3))
    }

    @Test
    fun anUnknownMimeTypeKeepsItsSubtypeRatherThanVanishing() {
        // A codec nobody mapped is still better identified by its subtype than
        // by a blank, which would collide with every other unknown.
        assertEquals("x-vnd.on2.vp9", ExoTrackMapper.codecFamily(null, "video/x-vnd.on2.vp9"))
        assertEquals("unknown", ExoTrackMapper.codecFamily(null, null))
    }

    @Test
    fun theRangeComesFromTheTransferFunction() {
        // HDR10 and SDR are both HEVC. Reading the codec to decide the range
        // gets it wrong on exactly the streams where the answer matters.
        assertEquals(DynamicRange.HDR10, ExoTrackMapper.dynamicRange(C.COLOR_TRANSFER_ST2084))
        assertEquals(DynamicRange.HDR10, ExoTrackMapper.dynamicRange(C.COLOR_TRANSFER_HLG))
        assertEquals(DynamicRange.SDR, ExoTrackMapper.dynamicRange(C.COLOR_TRANSFER_SDR))
    }

    @Test
    fun anUnreportedRangeIsSdrRatherThanAGuess() {
        // Media3 leaves colorInfo null on plenty of streams. Treating unknown as
        // HDR would send a device down a decode path it cannot take.
        assertEquals(DynamicRange.SDR, ExoTrackMapper.dynamicRange(null))
    }

    @Test
    fun subtitleFormatsAreNamedByWhatTheyAre() {
        // The format decides which renderer draws it: an .ass through libass, a
        // .vtt through the built-in one. Getting it wrong shows nothing.
        assertEquals("vtt", ExoTrackMapper.subtitleFormatOf(MimeTypes.TEXT_VTT))
        assertEquals("srt", ExoTrackMapper.subtitleFormatOf(MimeTypes.APPLICATION_SUBRIP))
        assertEquals("ass", ExoTrackMapper.subtitleFormatOf(MimeTypes.TEXT_SSA))
        assertEquals("ttml", ExoTrackMapper.subtitleFormatOf(MimeTypes.APPLICATION_TTML))
        assertEquals("pgs", ExoTrackMapper.subtitleFormatOf(MimeTypes.APPLICATION_PGS))
    }

    @Test
    fun anUnknownSubtitleTypeDefaultsToTheOneEveryPlayerHandles() {
        assertEquals("vtt", ExoTrackMapper.subtitleFormatOf(null))
    }

    @Test
    fun aBlankLabelFallsBackToLanguageRatherThanShowingAnEmptyRow() {
        // Format.label is often "" (not null) on HLS variants — NAME="" is
        // a valid attribute — so a null-only fallback leaves the menu row
        // blank instead of catching it.
        assertEquals("eng", ExoTrackMapper.resolveLabel("", "eng"))
        assertEquals("eng", ExoTrackMapper.resolveLabel(null, "eng"))
        assertEquals("Commentary", ExoTrackMapper.resolveLabel("Commentary", "eng"))
    }

    @Test
    fun aBlankLabelAndLanguageFallsBackToUnknown() {
        assertEquals("und", ExoTrackMapper.resolveLabel("", ""))
        assertEquals("und", ExoTrackMapper.resolveLabel(null, null))
    }

    @Test
    fun aBareKindTokenIsDetectedAsThatKind() {
        // The server names an HLS SUBTITLES group after its bare variant —
        // NAME="full", NAME="sdh" — which arrives here as the whole label.
        assertEquals("Full", subtitleKindOf("full"))
        assertEquals("SDH", subtitleKindOf("sdh"))
        assertEquals("Signs", subtitleKindOf("sign"))
        assertEquals("Signs", subtitleKindOf("signs"))
        assertEquals("Forced", subtitleKindOf("forced"))
        assertEquals("Alt", subtitleKindOf("alt"))
    }

    @Test
    fun aLanguageNameInsideTheLabelDoesNotFalsePositiveAsAKind() {
        // "Maltese" contains "alt" as a substring, not a token.
        assertEquals(null, subtitleKindOf("Maltese"))
        assertEquals(null, subtitleKindOf("English"))
    }

    @Test
    fun aSubtitleLabelCombinesTheLanguageWithTheDetectedKind() {
        // The actual defect: six same-kind tracks in different languages all
        // showed the bare word "full" with nothing to tell them apart.
        // displayLanguage() reads the runtime's own locale, not a fixed one
        // (the whole point of it over the old hardcoded map), so the expected
        // language name is derived the same way rather than hardcoded English.
        assertEquals("${displayLanguage("en")} (Full)", resolveSubtitleLabel("full", "en"))
        assertEquals("${displayLanguage("nl")} (SDH)", resolveSubtitleLabel("sdh", "nl"))
    }

    @Test
    fun aSubtitleLabelWithNoDetectableKindFallsBackLikeAnyOtherTrack() {
        assertEquals("Commentary", resolveSubtitleLabel("Commentary", "en"))
        assertEquals("eng", resolveSubtitleLabel(null, "eng"))
    }
}
