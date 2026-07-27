// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.input

import android.view.KeyEvent

// What an Android remote and an Android keyboard actually send.
//
// Transcribed from the handler the shipped client already uses, including the
// number keys: a lot of television remotes have no captions button and send a
// digit instead, and dropping those would take subtitles away from the remotes
// that need the shortcut most.
//
// The colour buttons are here as themselves rather than as the actions the old
// handler bound them to. What red does is a binding decision, and burying it in
// the translation is why the same button did different things on two clients.
internal object AndroidInputAdapter : InputAdapter {

    @Suppress("CyclomaticComplexMethod")
    override fun toPlayerKey(nativeKeyCode: Int): PlayerKey? = when (nativeKeyCode) {
        KeyEvent.KEYCODE_DPAD_LEFT -> PlayerKey.Left
        KeyEvent.KEYCODE_DPAD_RIGHT -> PlayerKey.Right
        KeyEvent.KEYCODE_DPAD_UP -> PlayerKey.Up
        KeyEvent.KEYCODE_DPAD_DOWN -> PlayerKey.Down
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> PlayerKey.Center

        KeyEvent.KEYCODE_MEDIA_PLAY -> PlayerKey.MediaPlay
        KeyEvent.KEYCODE_MEDIA_PAUSE -> PlayerKey.MediaPause
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> PlayerKey.MediaPlayPause
        KeyEvent.KEYCODE_MEDIA_STOP -> PlayerKey.MediaStop
        KeyEvent.KEYCODE_MEDIA_REWIND -> PlayerKey.MediaRewind
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> PlayerKey.MediaFastForward
        KeyEvent.KEYCODE_MEDIA_NEXT -> PlayerKey.MediaNext
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> PlayerKey.MediaPrevious
        KeyEvent.KEYCODE_MEDIA_RECORD -> PlayerKey.MediaRecord

        // The digit alternates come from remotes with no dedicated button. They
        // are in the shipped handler and leaving them out would be a shortcut
        // quietly lost on the hardware that has fewest of them.
        KeyEvent.KEYCODE_CAPTIONS, KeyEvent.KEYCODE_5 -> PlayerKey.Captions
        KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK, KeyEvent.KEYCODE_2 -> PlayerKey.AudioTrack

        KeyEvent.KEYCODE_PROG_RED -> PlayerKey.ColorRed
        KeyEvent.KEYCODE_PROG_GREEN -> PlayerKey.ColorGreen
        KeyEvent.KEYCODE_PROG_YELLOW -> PlayerKey.ColorYellow
        KeyEvent.KEYCODE_PROG_BLUE -> PlayerKey.ColorBlue

        KeyEvent.KEYCODE_VOLUME_UP -> PlayerKey.VolumeUp
        KeyEvent.KEYCODE_VOLUME_DOWN -> PlayerKey.VolumeDown
        KeyEvent.KEYCODE_VOLUME_MUTE -> PlayerKey.VolumeMute

        KeyEvent.KEYCODE_INFO -> PlayerKey.Info
        KeyEvent.KEYCODE_BACK -> PlayerKey.Back

        else -> null
    }
}

public actual fun defaultInputAdapter(): InputAdapter = AndroidInputAdapter
