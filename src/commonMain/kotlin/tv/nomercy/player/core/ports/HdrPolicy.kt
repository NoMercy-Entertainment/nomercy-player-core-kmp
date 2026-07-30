// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

/**
 * What a consumer wants done when HDR content meets an SDR screen and the backend
 * cannot convert it.
 *
 * A choice rather than a rule, because the two answers are both defensible and the
 * library is not the one who has to explain either to a viewer: [Play] shows a
 * washed-out picture, [Refuse] shows none. Tone-mapping is preferred over both
 * wherever the backend can do it, so this only decides the case where it cannot.
 */
public enum class HdrOnSdrFallback {
    /** Show the HDR stream unconverted. Watchable, and the colours are wrong. */
    Play,

    /** Do not start, and say why. Never a wrong picture, sometimes no picture. */
    Refuse,
}

/**
 * What to do about an item's dynamic range on this screen.
 *
 * Separate from [HdrAbrConstraint] because capping the ladder is only one of the
 * answers, and it is not the first one. That object was written, tested three ways,
 * and never called from a playback path — so HDR played on an SDR screen with
 * nothing consulted and nothing converted, which is what a viewer reported.
 */
public sealed interface HdrDecision {

    /** Nothing to do: an HDR screen, or an item with no HDR in it. */
    public data object AsIs : HdrDecision

    /**
     * Ask the backend to convert HDR to SDR as it decodes.
     *
     * Preferred wherever the backend can do it, on every platform that can: it needs
     * no second rendition on the server and no bandwidth decision, and the picture is
     * right rather than merely watchable. It costs work per frame, which is the trade
     * being made deliberately.
     */
    public data object ToneMap : HdrDecision

    /**
     * Pin adaptation to the best SDR rung the ladder has.
     *
     * Ahead of tone-mapping when a rung exists, because a natively-SDR stream is
     * correct without spending anything per frame — and adaptation will climb into
     * the HDR rungs on its own the moment the connection allows, handing a viewer
     * with good bandwidth a worse picture as a reward.
     */
    public data class CapTo(val level: QualityLevel) : HdrDecision

    /** No SDR rung, no tone-mapping, and the consumer chose to show it anyway. */
    public data object PlayUnconverted : HdrDecision

    /** No SDR rung, no tone-mapping, and the consumer chose not to. */
    public data object Refuse : HdrDecision
}

/**
 * The decision, in the order the cheapest correct answer comes first.
 *
 * Pure and taking booleans rather than a Display or a backend, so every branch is
 * provable without a device — the same reason HdrAbrConstraint is. The device answers
 * two questions and this answers the rest.
 *
 * @param backendCanToneMap whether THIS backend on THIS platform can convert as it
 *   decodes. Not a platform constant: the same platform answers differently by
 *   codec, by decoder, and by whether hardware decoding is in use.
 */
public fun hdrDecision(
    levels: List<QualityLevel>,
    displayHdr: Boolean,
    backendCanToneMap: Boolean,
    fallback: HdrOnSdrFallback = HdrOnSdrFallback.Play,
): HdrDecision {
    val ceiling: QualityLevel? = HdrAbrConstraint.abrCeiling(levels, displayHdr)

    // One expression, cheapest correct answer first, so the ORDER is the statement
    // rather than something to reconstruct from a sequence of early returns.
    return when {
        displayHdr -> HdrDecision.AsIs
        ceiling != null -> HdrDecision.CapTo(ceiling)
        // Nothing to cap and nothing HDR: an ordinary SDR item on an SDR screen. An
        // empty ladder lands here too, which is right — a backend that has not
        // enumerated its rungs yet must not refuse the item.
        !HdrAbrConstraint.isUnplayable(levels, displayHdr) -> HdrDecision.AsIs
        backendCanToneMap -> HdrDecision.ToneMap
        fallback == HdrOnSdrFallback.Play -> HdrDecision.PlayUnconverted
        else -> HdrDecision.Refuse
    }
}
