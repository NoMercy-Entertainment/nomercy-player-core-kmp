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
// Tracks, a ladder and a dynamic-range decision is what a video engine has to
// answer. Splitting the interface to satisfy a threshold would put half of one
// engine's contract behind a second type every implementor has to find, and the
// dynamic-range members are only meaningful next to the ladder they constrain.
@Suppress("ComplexInterface")
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

    /**
     * Whether THIS engine on THIS platform can convert HDR to SDR as it decodes.
     *
     * Not a platform constant: the same device answers differently by codec, by
     * decoder and by API level, which is why it is asked of the engine rather than
     * looked up from the form factor.
     *
     * False by default so an engine that has not answered cannot claim a
     * conversion it will not perform — [hdrDecision] then falls through to the
     * consumer's [HdrOnSdrFallback] instead of promising a picture nothing fixes.
     */
    public val canToneMapHdrToSdr: Boolean get() = false

    /**
     * What this engine should do when HDR meets an SDR screen and nothing can
     * convert it.
     *
     * A no-op by default, so an engine with no dynamic-range decision to make is
     * not forced to pretend it has one. The value comes from the consumer through
     * PlayerConfig.hdrOnSdr, because both answers are defensible and the library
     * is not the one who has to explain either to a viewer.
     */
    public fun hdrOnSdrFallback(fallback: HdrOnSdrFallback) {}
}

// An audio engine is a media engine that can also play two things at once,
// which is what makes crossfade possible.
public interface AudioBackend : MediaBackend, TransitionBackend {

    // The signal path an equaliser and a spectrum plugin drive, when this
    // engine has one.
    //
    // Null by default, and that is an honest answer rather than a gap. The
    // video engines have no DSP graph today — a plugin asking one for an
    // equaliser should be told there is none, not handed a graph that accepts
    // every call and changes nothing. A control that appears to work is worse
    // than one that is absent.
    public fun audioGraph(): AudioDspGraph? = null
}
