// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.media

// Whether a rendition carries more range than SDR, and which system it uses to
// say so. Kept as a closed set because a chrome badges them and a probe asks
// about them by name.
public enum class DynamicRange(public val token: String) {
    Sdr("sdr"),
    Hdr10("hdr10"),
    Hdr10Plus("hdr10+"),
    HlG("hlg"),
    DolbyVision("dolby-vision"),
    ;

    public companion object {
        public fun fromToken(token: String): DynamicRange =
            entries.firstOrNull { it.token == token }
                ?: throw IllegalArgumentException("unknown DynamicRange token: $token")
    }
}

// One rung of the quality ladder, identified by what it is rather than where it
// sits in a list.
//
// No index. ExoPlayer, AVPlayer and VLCJ each hand their ladder over in their
// own order and reorder it between loads, so an index taken before a reload
// points at something else afterwards — which is how a viewer who picked 1080p
// ends up watching 480p after an ad break. An engine that needs an index keeps
// that mapping to itself.
//
// Dynamic range is part of the identity, not a decoration: an HDR and an SDR
// variant at the same height and bitrate are different renditions, and picking
// the wrong one is visible on screen.
public data class QualityDescriptor(
    val height: Int,
    val bitrate: Int,
    val dynamicRange: DynamicRange = DynamicRange.Sdr,
    val codec: String? = null,
    /**
     * The rung's width, when the source declares one.
     *
     * A manifest writes `RESOLUTION=1280x536` and this carried only the part
     * after the x, so every rung reached the quality menu with a null width and
     * the pane could name it nothing but "536p" - beside a browser naming the
     * same stream 1280x536.
     */
    val width: Int? = null,
) : Comparable<QualityDescriptor> {

    // Height first, then bitrate, then range. A ladder sorted this way reads the
    // way a quality menu is drawn, and sorting is what lets three engines'
    // different orderings become one.
    override fun compareTo(other: QualityDescriptor): Int {
        val byHeight: Int = height.compareTo(other.height)
        if (byHeight != 0) return byHeight
        val byBitrate: Int = bitrate.compareTo(other.bitrate)
        if (byBitrate != 0) return byBitrate
        return dynamicRange.ordinal.compareTo(other.dynamicRange.ordinal)
    }

    // What a quality menu shows. Not a format anything parses — the descriptor
    // is the identity, this is for humans.
    public fun label(): String = buildString {
        append(height)
        append('p')
        if (dynamicRange != DynamicRange.Sdr) {
            append(' ')
            append(dynamicRange.token.uppercase())
        }
    }
}
