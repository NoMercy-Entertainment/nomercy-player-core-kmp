// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

// How to start a piece of media. Everything defaults, so loading something and
// leaving it paused at the beginning is the zero-argument case.
public data class LoadOptions(
    val startPositionMs: Long = 0L,
    val autoplay: Boolean = false,
    val headers: Map<String, String> = emptyMap(),
    // Resolved the same way startPositionMs is (PlayerContext.preferredAudioLanguageFor,
    // applied in loadQuietly before the backend ever sees the item) — the viewer's saved
    // language, known before there is any track list to restore against. A backend that
    // can act on it selects the right track AT PREPARE, not after the list announces and
    // a post-hoc switch re-buffers audio the viewer already heard start in the wrong one.
    val preferredAudioLanguage: String? = null,
)
