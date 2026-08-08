// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.stream

/**
 * What an `avc1.` codec string says about the profile it needs.
 *
 * RFC 6381 packs three bytes of hex after the fourcc: profile_idc,
 * constraint flags, level_idc. `avc1.640028` is profile 0x64 — High — and
 * `avc1.6E0028` is 0x6E, which is 110, which is High 10. Those two differ by a
 * single character and by whether any Android device on earth can decode the
 * file, so reading it is worth doing properly rather than by substring.
 */
public object AvcCodecString {

    /** Profile 110, the one a 10-bit AVC file needs. */
    public const val HIGH_10: Int = 110

    /**
     * The profile_idc a codec string asks for, or null when it is not an AVC
     * codec string or does not carry one.
     *
     * Null rather than a default, because "not AVC" and "AVC, profile unknown"
     * are both answers a caller must not read as "ordinary High profile".
     */
    public fun profileIdc(codec: String?): Int? = codec
        ?.trim()
        ?.lowercase()
        ?.takeIf { candidate -> AVC_PREFIXES.any(candidate::startsWith) }
        ?.substringAfter('.')
        ?.takeIf { hex -> hex.length >= PROFILE_HEX_DIGITS }
        ?.take(PROFILE_HEX_DIGITS)
        ?.toIntOrNull(radix = HEX)

    /**
     * Whether this codec string names 10-bit AVC.
     *
     * The one question with a different answer on a phone than in a browser:
     * Chrome carries its own decoder, and Android's MediaCodec stops at High.
     */
    public fun isHigh10(codec: String?): Boolean = profileIdc(codec) == HIGH_10

    // avc3 as well as avc1: the two differ in where the parameter sets live,
    // not in what decodes them, and a fragmented stream uses the second.
    private val AVC_PREFIXES: List<String> = listOf("avc1.", "avc3.")

    private const val PROFILE_HEX_DIGITS = 2
    private const val HEX = 16
}
