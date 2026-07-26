// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

// Whether to hand decoding straight to the display pipeline.
//
// Tunneling lets a television decode and present without the frames passing
// through the app, which is what makes 4K HDR play on hardware that could not
// otherwise keep up. It is also the single most device-specific thing this
// player does, and every condition below is a device that broke.
//
// A pure function because the alternative is untestable: the real call is one
// setter on a track selector inside a player being constructed, and asserting on
// it means building an ExoPlayer. The decision is the part worth testing, so it
// is the part that got extracted.
public object TunnelingRule {

    public fun shouldTunnel(
        isTv: Boolean,
        sourceIsHls: Boolean,
        refusedByAudioSink: Boolean,
    ): Boolean = when {
        // Phones have no tunnel surface contract with their displays. Enabling
        // it there is not a slower path, it is a black picture.
        !isTv -> false

        // HLS switches representation mid-stream, and a tunneled decoder cannot
        // be reconfigured without tearing down the surface. The symptom is a
        // freeze at the first quality change rather than a failure to start.
        sourceIsHls -> false

        // Tunneling needs an AudioTrack with hardware A/V sync, and some audio
        // HALs refuse to open one. Once a device has said no, it will say no
        // every time, so this stays off for the life of the player rather than
        // failing again at the first frame of every item.
        refusedByAudioSink -> false

        else -> true
    }

    // Whether a playback failure is the audio sink refusing a tunneled track.
    //
    // Checked before any decoder-error handling, because the message contains
    // "codec" and a substring match sends it down the rebuild path — which
    // rebuilds with the same tunneling parameters and fails again identically.
    public fun isTunnelingRefusal(errorCode: Int, tunnelingWasEnabled: Boolean): Boolean =
        tunnelingWasEnabled && errorCode == AUDIO_TRACK_INIT_FAILED

    // Media3's ERROR_CODE_AUDIO_TRACK_INIT_FAILED. Named here rather than
    // imported so the rule stays in commonMain and testable off Android.
    public const val AUDIO_TRACK_INIT_FAILED: Int = 5001
}
