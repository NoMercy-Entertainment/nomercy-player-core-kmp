// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.input

// Every key the player has an opinion about, named once.
//
// A remote, a keyboard and a gamepad all send different numbers for the same
// press, and the two clients this replaces each translated them separately. That
// is why the same button pauses on one and does nothing on the other: the
// vocabulary was never shared, so neither list could be checked against the
// other.
//
// Letters, symbols and modifier combinations are deliberately absent. A desktop
// keyboard has too many of them to enumerate, and they are expressible as a
// combo string instead. What belongs here is the set a remote can send, because
// those are the ones that must mean the same thing on every device.
public enum class PlayerKey(public val combo: String) {

    Play("Play"),
    Pause("Pause"),
    PlayPause("PlayPause"),
    Stop("Stop"),

    // Direction, which on a television is the whole interface. Centre is
    // separate from Play because a remote's centre button selects what is
    // focused, and only means play when nothing is.
    Left("ArrowLeft"),
    Right("ArrowRight"),
    Up("ArrowUp"),
    Down("ArrowDown"),
    Center("Enter"),

    // What a media key sends, which is not the same as what a transport button
    // in the chrome does: these arrive whether or not the player has focus, and
    // several arrive from headphones rather than from the device.
    MediaPlay("MediaPlay"),
    MediaPause("MediaPause"),
    MediaPlayPause("MediaPlayPause"),
    MediaStop("MediaStop"),
    MediaRewind("MediaRewind"),
    MediaFastForward("MediaFastForward"),
    MediaNext("MediaTrackNext"),
    MediaPrevious("MediaTrackPrevious"),
    MediaRecord("MediaRecord"),

    Captions("Captions"),
    AudioTrack("AudioTrack"),

    // The four coloured buttons every television remote has and nothing else
    // does. Their names are the ones the web reference already binds, so a
    // binding table written for one works on the other.
    ColorRed("ColorF0Red"),
    ColorGreen("ColorF1Green"),
    ColorYellow("ColorF2Yellow"),
    ColorBlue("ColorF3Blue"),

    VolumeUp("AudioVolumeUp"),
    VolumeDown("AudioVolumeDown"),
    VolumeMute("AudioVolumeMute"),

    Info("Info"),
    Favorites("Favorites"),
    Back("Back"),
}
