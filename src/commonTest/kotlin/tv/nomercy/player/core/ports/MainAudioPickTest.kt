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
import kotlin.test.assertNull

// Four English tracks in a shuffled file order: stereo AAC (the file's default),
// 5.1 EAC3, a commentary and an audio description.
class MainAudioPickTest {

    private val stereoAac = AudioPickCandidate("en", "English", true, false, 2, true)
    private val surroundEac3 = AudioPickCandidate("en", "English 5.1", false, false, 6, true)
    private val commentary = AudioPickCandidate("en", "Commentary", false, false, 2, true)
    private val description = AudioPickCandidate("en", "Audio Description", false, false, 2, true)

    private fun pick(list: List<AudioPickCandidate>, current: Int = 0, max: Int = 6): AudioPickCandidate? =
        MainAudioPick.pick(list, current, max)?.let(list::get)

    @Test
    fun aCommentaryOrDescriptionTrackIsNeverPickedWhileAMainTrackExists() {
        val shuffled = listOf(commentary, description, surroundEac3, stereoAac)
        val byRole = listOf(commentary.copy(label = "English", isCommentaryRole = true), stereoAac)

        assertEquals(stereoAac, pick(shuffled, current = 0))
        assertEquals(stereoAac, pick(byRole, current = 0))
        assertEquals(stereoAac, pick(listOf(description.copy(label = "English (AD)"), stereoAac)))
        assertEquals(stereoAac, pick(listOf(description.copy(label = "Descriptive audio"), stereoAac)))
    }

    @Test
    fun theTrackTheFileMarksDefaultWinsOverMoreChannels() {
        val list = listOf(surroundEac3, commentary, stereoAac, description)

        assertEquals(stereoAac, pick(list, current = 0))
    }

    @Test
    fun withoutADefaultTheTrackWithTheMostOutputChannelsWins() {
        val list = listOf(
            commentary,
            stereoAac.copy(isDefault = false),
            surroundEac3,
            description,
        )

        assertEquals(surroundEac3, pick(list, current = 0, max = 6))
    }

    @Test
    fun channelsAboveWhatTheDeviceCanOutputDoNotCount() {
        val stereoNative = stereoAac.copy(isDefault = false)
        val surroundConverted = surroundEac3.copy(decodesNatively = false)

        assertEquals(stereoNative, pick(listOf(surroundConverted, stereoNative), max = 2))
    }

    @Test
    fun aCodecTheDeviceDecodesNativelyBeatsOneItMustConvert() {
        val converted = surroundEac3.copy(decodesNatively = false)
        val native = surroundEac3.copy(label = "English 5.1 alt")

        assertEquals(native, pick(listOf(converted, native), max = 6))
    }

    @Test
    fun whenEverythingElseTiesTheFirstInFileOrderWins() {
        val first = stereoAac.copy(isDefault = false, label = "First")
        val second = stereoAac.copy(isDefault = false, label = "Second")

        assertEquals(first, pick(listOf(first, second)))
        assertEquals(second, pick(listOf(second, first)))
    }

    @Test
    fun theLanguageIsTheEnginesChoiceAndOtherLanguagesAreNeverPicked() {
        val dutch = AudioPickCandidate("nl", "Nederlands", true, false, 6, true)
        val list = listOf(dutch, commentary, stereoAac)

        assertEquals(stereoAac, pick(list, current = 1))
        assertEquals(dutch, pick(list, current = 0))
    }

    // The shape the server stores for one real show: four English tracks, no
    // labels, two codec groups, one default per group.
    private val eac3Side = AudioPickCandidate("en", "", false, false, 6, true)
    private val eac3Default = AudioPickCandidate("en", "", true, false, 6, true)
    private val aacOther = AudioPickCandidate("en", "", false, false, 2, true)
    private val aacDefault = AudioPickCandidate("en", "", true, false, 2, true)
    private val unlabeled = listOf(aacOther, eac3Side, aacDefault, eac3Default)

    @Test
    fun unlabeledTracksOfOneLanguageOnASurroundDevicePlayTheDefaultSurroundTrack() {
        assertEquals(eac3Default, pick(unlabeled, current = 0, max = 6))
        assertEquals(eac3Default, pick(unlabeled.reversed(), current = 0, max = 6))
    }

    @Test
    fun unlabeledTracksOnADeviceThatCannotDecodeSurroundPlayTheDefaultStereoTrack() {
        val noSurround = listOf(aacOther, eac3Side.copy(decodesNatively = false), aacDefault, eac3Default.copy(decodesNatively = false))

        assertEquals(aacDefault, pick(noSurround, current = 0, max = 2))
    }

    @Test
    fun aSingleUndeterminedLanguageTrackPlays() {
        val only = AudioPickCandidate("und", "", true, false, 6, true)

        assertEquals(only, pick(listOf(only), current = 0))
    }

    @Test
    fun aListWithOnlyCommentaryStillPlaysOne() {
        assertEquals(commentary, pick(listOf(commentary, description), current = 0))
        assertNull(MainAudioPick.pick(listOf(stereoAac.copy(playable = false)), 0, 6))
    }
}
