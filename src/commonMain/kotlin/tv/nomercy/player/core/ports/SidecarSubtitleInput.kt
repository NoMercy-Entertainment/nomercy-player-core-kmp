// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

/**
 * A subtitle track supplied alongside the media rather than inside it.
 *
 * [type] is a free-form flavour — `sdh`, `forced`, `full` — and NOT a container
 * hint: every track added this way is fetched and parsed as WebVTT, the same as
 * the web. A caller passing `ass` here gets a WebVTT parse failure rather than
 * an ASS renderer, which is why the field says flavour and means it.
 */
public data class SidecarSubtitleInput(
    /** URL of the subtitle resource. */
    val url: String,
    /** BCP-47 language tag, for example `en` or `nl-NL`. */
    val language: String,
    /** Human-readable label. Falls back to [language] when absent. */
    val label: String? = null,
    /** Flavour, matching `SubtitleTrack.type`. */
    val type: String? = null,
    /** Select this track as soon as it is added. */
    val default: Boolean = false,
)
