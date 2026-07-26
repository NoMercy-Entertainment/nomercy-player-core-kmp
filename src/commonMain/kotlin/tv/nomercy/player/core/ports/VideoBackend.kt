// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

// What a video engine can do that an audio one cannot.
//
// Every selector takes the thing itself, never its position in a list. The
// ladder is filtered by device capability before a viewer sees it, so an index
// means something different to the engine than to the list on screen — which is
// how "switch to 1080p" ends up playing 480p.
public interface VideoBackend : MediaBackend {
    public fun audioTracks(): List<AudioTrack>
    public fun audioTrack(): AudioTrack?
    public fun audioTrack(track: AudioTrack)

    public fun subtitleTracks(): List<SubtitleTrack>
    public fun subtitleTrack(): SubtitleTrack?

    // Null turns captions off, which is a real selection rather than an error.
    public fun subtitleTrack(track: SubtitleTrack?)

    public fun qualityLevels(): List<QualityLevel>
    public fun quality(): QualityLevel?

    // Null is automatic, which is a selection rather than a missing one: it
    // hands the rung choice back to the engine's own adaptation. A descriptor
    // pins one variant, and QualityMatcher is the only thing that turns it into
    // whatever number this engine calls it.
    public fun quality(level: QualityLevel?)
}

// An audio engine is a media engine that can also play two things at once,
// which is what makes crossfade possible.
public interface AudioBackend : MediaBackend, TransitionBackend
