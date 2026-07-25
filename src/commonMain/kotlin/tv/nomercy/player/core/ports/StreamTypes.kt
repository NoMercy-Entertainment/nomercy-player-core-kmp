// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

public enum class StreamKind(public val wire: String) {
    NATIVE("native"),
    HLS("hls"),
    DASH("dash"),
}

public enum class StreamSourceState(public val wire: String) {
    IDLE("idle"),
    LOADING("loading"),
    READY("ready"),
    PLAYING("playing"),
    ERROR("error"),
}

// One rendition as an adaptive manifest describes it.
//
// [index] is the position in the underlying library's own level list, kept only
// so an implementation can talk back to that library. It is not identity and
// nothing outside the implementation should switch on it — QualityLevel is what
// the player and the viewer choose between.
public data class StreamLevel(
    val bitrate: Int,
    val label: String,
    val width: Int? = null,
    val height: Int? = null,
    val index: Int? = null,
)
