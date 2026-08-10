// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import tv.nomercy.player.core.natives.HostPlatform

// Tapping the OS's own mixed audio output, because mpv's client API will not
// hand this process one.
//
// The tap point moved outside the engine entirely: instead of asking mpv for
// samples it has no way to give, this asks the operating system for whatever
// it is currently sending to the speakers. That works regardless of which
// engine produced it, and it is the same technique every desktop visualiser
// that is not itself the audio engine uses — a system loopback/monitor
// capture, not a hook into playback.
//
// Interleaved 32-bit float, matching what [PcmEqualiser.process] already
// takes — the format libVLC's `amem` gave it, kept as the contract so this
// slots into the same equaliser/spectrum pipeline unchanged.
//
// Unverified against real hardware on all three platforms as of this commit
// (2026-08-10) — a decision made explicitly, not a gap left quiet: the
// choice to write this blind rather than wait for hardware was Stoney's own
// call. Each implementation is built to its platform's real, documented API
// sequence; none has been run against a live device yet.
public interface AudioLoopbackCapture {

    // Starts capturing. Returns false when this platform, or this machine on
    // it, has no route to try — a headless CI box with no default output
    // device, a Linux host with no PulseAudio/PipeWire running. A caller
    // treats false exactly like [tv.nomercy.player.video.cast.WakeOutcome.UNSUPPORTED]:
    // the feature quietly does not turn on rather than crashing the player
    // that asked for it.
    public fun start(sampleRate: Int, channels: Int, onFrame: (samples: FloatArray, frames: Int) -> Unit): Boolean

    public fun stop()
}

// No loopback route exists — the honest default for a platform this project
// has no capture path for, or a [start] call that failed before capture
// began.
public object UnsupportedAudioLoopbackCapture : AudioLoopbackCapture {
    override fun start(sampleRate: Int, channels: Int, onFrame: (FloatArray, Int) -> Unit): Boolean = false

    override fun stop() {
        // Nothing was ever running.
    }
}

// The capture this machine can actually attempt, chosen the same way the
// native payload picker chooses a platform — by [HostPlatform.current], not
// by a build-time target, because the jvm target is the one target that runs
// on three operating systems.
public fun defaultAudioLoopbackCapture(): AudioLoopbackCapture = when (HostPlatform.current()) {
    HostPlatform.WINDOWS_X64 -> WasapiLoopbackCapture()
    HostPlatform.MACOS_X64, HostPlatform.MACOS_ARM64 -> CoreAudioTapCapture()
    HostPlatform.LINUX_X64, HostPlatform.LINUX_ARM64 -> PulseAudioLoopbackCapture()
    else -> UnsupportedAudioLoopbackCapture
}
