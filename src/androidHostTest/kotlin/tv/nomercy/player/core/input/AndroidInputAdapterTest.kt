// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.input

import android.view.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// The Android table, checked against the key codes themselves.
//
// This is the one part of key handling with no way to be caught at runtime. A
// wrong entry is not an error: it is a button on a remote that does the wrong
// thing, or nothing, on one client while working on another. That is exactly
// what happened to the two clients this replaces.
class AndroidInputAdapterTest {

    private val adapter: InputAdapter = defaultInputAdapter()

    @Test
    fun theDirectionalPadIsTheWholeTelevisionInterface() {
        assertEquals(PlayerKey.Left, adapter.toPlayerKey(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(PlayerKey.Right, adapter.toPlayerKey(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(PlayerKey.Up, adapter.toPlayerKey(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(PlayerKey.Down, adapter.toPlayerKey(KeyEvent.KEYCODE_DPAD_DOWN))
    }

    @Test
    fun bothWaysOfPressingTheMiddleMeanTheSameThing() {
        // A remote sends the centre of its pad and a keyboard attached to the
        // same box sends enter. Handling one and not the other is a device that
        // works until somebody plugs in a keyboard.
        assertEquals(PlayerKey.Center, adapter.toPlayerKey(KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(PlayerKey.Center, adapter.toPlayerKey(KeyEvent.KEYCODE_ENTER))
    }

    @Test
    fun theDigitAlternatesAreKeptBecauseSomeRemotesHaveNothingElse() {
        // Plenty of television remotes have no captions button and send a digit.
        // They are in the shipped handler, and dropping them takes the shortcut
        // away from the hardware that has fewest of them.
        assertEquals(PlayerKey.Captions, adapter.toPlayerKey(KeyEvent.KEYCODE_CAPTIONS))
        assertEquals(PlayerKey.Captions, adapter.toPlayerKey(KeyEvent.KEYCODE_5))
        assertEquals(PlayerKey.AudioTrack, adapter.toPlayerKey(KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK))
        assertEquals(PlayerKey.AudioTrack, adapter.toPlayerKey(KeyEvent.KEYCODE_2))
    }

    @Test
    fun theColourButtonsArriveAsThemselves() {
        // Not as the seek amounts the old handler bound them to. What red does
        // is a binding decision, and burying it in the translation is why the
        // same button did different things on two clients.
        assertEquals(PlayerKey.ColorRed, adapter.toPlayerKey(KeyEvent.KEYCODE_PROG_RED))
        assertEquals(PlayerKey.ColorGreen, adapter.toPlayerKey(KeyEvent.KEYCODE_PROG_GREEN))
        assertEquals(PlayerKey.ColorYellow, adapter.toPlayerKey(KeyEvent.KEYCODE_PROG_YELLOW))
        assertEquals(PlayerKey.ColorBlue, adapter.toPlayerKey(KeyEvent.KEYCODE_PROG_BLUE))
    }

    @Test
    fun everyMediaKeyTheShippedHandlerAnsweredIsStillAnswered() {
        // The list is transcribed rather than reimagined, because a key the old
        // client handled and this one does not is a regression a viewer notices
        // and nobody else does.
        assertEquals(PlayerKey.MediaPlay, adapter.toPlayerKey(KeyEvent.KEYCODE_MEDIA_PLAY))
        assertEquals(PlayerKey.MediaPause, adapter.toPlayerKey(KeyEvent.KEYCODE_MEDIA_PAUSE))
        assertEquals(PlayerKey.MediaPlayPause, adapter.toPlayerKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertEquals(PlayerKey.MediaStop, adapter.toPlayerKey(KeyEvent.KEYCODE_MEDIA_STOP))
        assertEquals(PlayerKey.MediaRewind, adapter.toPlayerKey(KeyEvent.KEYCODE_MEDIA_REWIND))
        assertEquals(PlayerKey.MediaFastForward, adapter.toPlayerKey(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD))
        assertEquals(PlayerKey.MediaNext, adapter.toPlayerKey(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertEquals(PlayerKey.MediaPrevious, adapter.toPlayerKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS))
    }

    @Test
    fun aKeyThePlayerHasNoNameForStaysWithThePlatform() {
        // Most of them. Claiming a key the player does not use is how a
        // television stops responding to its own home button.
        assertNull(adapter.toPlayerKey(KeyEvent.KEYCODE_HOME))
        assertNull(adapter.toPlayerKey(KeyEvent.KEYCODE_SETTINGS))
        assertNull(adapter.toPlayerKey(KeyEvent.KEYCODE_A))
    }

    @Test
    fun theTableIsOneWayWithNoTwoCodesMeaningDifferentThings() {
        // A code mapped twice would resolve to whichever branch came first, and
        // which one that is depends on the order somebody happened to type.
        val codes = listOf(
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_PROG_RED,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_CAPTIONS,
        )

        val resolved = codes.map { adapter.toPlayerKey(it) }

        assertEquals(codes.size, resolved.toSet().size)
    }
}
