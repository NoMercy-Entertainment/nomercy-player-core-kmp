// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.events

import tv.nomercy.player.core.errors.ErrorScope
import tv.nomercy.player.core.errors.Severity

// The payload types the contract names rather than spelling out inline.
//
// These are hand-authored because a name in the contract means the shape is
// shared by several events and worth reading as one thing. Everything the
// contract states as an inline object literal is generated instead — see
// GeneratedEventPayloads.kt.

// What every error tier carries: fatal, error, warning, info, and the fourteen
// stage-specific *Error events.
//
// It is the error's information rather than the error itself, so a listener
// can render it without catching anything, and a Connect client can put it on
// the wire.
public data class PlayerErrorEvent(
    val code: String,
    val message: String,
    val severity: Severity,
    val scope: ErrorScope,
    val suggestion: String? = null,
    val context: Map<String, Any?> = emptyMap(),
)

// A subtitle or chapter cue crossing the playhead.
public data class CueEvent(
    val id: String? = null,
    val startTime: Double,
    val endTime: Double,
    val text: String? = null,
)

// The current subtitle cue, or null when none is showing. Distinct from
// CueEvent: this is state, that is a crossing.
public data class SubtitleCueChange(
    val cue: CueEvent?,
)

// How the consumer wants subtitles drawn. Every field is optional because a
// chrome sets the ones it cares about and inherits the rest.
public data class SubtitleStyle(
    val fontFamily: String? = null,
    val fontSize: Double? = null,
    val color: String? = null,
    val backgroundColor: String? = null,
    val edgeStyle: String? = null,
    val opacity: Double? = null,
)

// What playback actually cost, sampled on an interval. The numbers a support
// ticket needs and nobody can reconstruct afterwards.
public data class PlaybackMetrics(
    val droppedFrames: Double = 0.0,
    val totalFrames: Double = 0.0,
    val bufferingEvents: Double = 0.0,
    val bufferingSeconds: Double = 0.0,
    val startupSeconds: Double = 0.0,
    val bitrate: Double = 0.0,
    // How long this item has been playing, filled in when the snapshot is read
    // rather than stored, so it does not stand still during a stall.
    val sessionDurationMs: Long = 0L,
    // Counters this library does not know about: a plugin counting lyric
    // fetches, a backend counting decoder resets.
    val custom: Map<String, Double> = emptyMap(),
)

// Where a session is being handed to. Kept as a value rather than a platform
// handle so the same event crosses the Connect wire unchanged.
public data class CastTarget(
    val id: String,
    val name: String,
    val kind: String? = null,
)

