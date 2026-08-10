// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import tv.nomercy.player.core.dsp.EqBand
import java.util.Locale

// Turning the shared EQ band list into the mpv `af` property string.
//
// mpv's public client API has no equivalent of libVLC's `amem` raw-PCM
// callback — nothing here can hand samples to the shared
// [tv.nomercy.player.core.ports.PcmEqualiser] the way the desktop plan
// originally assumed. FFmpeg's own `equalizer` filter is the substitute, not
// a compromise one: `width_type=q` drives it with the identical RBJ-cookbook
// peaking transfer function [tv.nomercy.player.core.dsp.BiquadPeaking]
// implements, `EqBand.bandwidth` already IS that Q (see `BiquadPeaking`'s own
// doc — "[bandwidth] is the filter's Q"), so this is a coordinate change, not
// a second EQ.
//
// Pure and jvmMain-only rather than commonMain: it emits a string in
// FFmpeg's own filter-graph syntax, which is desktop's problem alone — Android
// and Apple shape the identical bands through their own APIs entirely.
internal object MpvAudioFilterChain {

    // `af=""` (mpv's own "no filters" value) rather than a `volume=1` no-op
    // chain — an empty desktop EQ should cost mpv nothing to skip, the same
    // reason [tv.nomercy.player.core.ports.PcmEqualiser.eqEnabled] bypasses
    // its own biquads instead of running them flat.
    internal const val EMPTY: String = ""

    internal fun build(bands: List<EqBand>, preGain: Double, enabled: Boolean): String {
        val stages: MutableList<String> = mutableListOf()

        if (!isUnityGain(preGain)) {
            stages += "volume=${format(preGain)}"
        }

        if (enabled) {
            for (band in bands) {
                if (band.gainDb == 0.0) continue
                stages += "equalizer=f=${band.frequency}:width_type=q:w=${format(band.bandwidth)}:g=${format(band.gainDb)}"
            }
        }

        if (stages.isEmpty()) return EMPTY
        return "lavfi=[${stages.joinToString(",")}]"
    }

    // Below this, floating-point noise on a preGain round-tripped through a
    // slider (0.999999999) would otherwise chain a `volume=` stage that
    // audibly does nothing and only costs a resample.
    private fun isUnityGain(linear: Double): Boolean = kotlin.math.abs(linear - 1.0) < UNITY_EPSILON

    // Plain decimal with a `.` separator, never scientific notation and never
    // the current JVM locale's own decimal mark. `"%.6f".format(value)` reads
    // the DEFAULT locale — on a machine set to a comma-decimal locale that
    // silently emits `g=0,5` instead of `g=0.5`, which FFmpeg's filter-graph
    // parser reads as two arguments, `g=0` and a stray `5`. Caught by running
    // this test suite on exactly such a machine, not by guessing.
    private fun format(value: Double): String =
        String.format(Locale.ROOT, "%.6f", value).trimEnd('0').trimEnd('.').ifEmpty { "0" }

    private const val UNITY_EPSILON: Double = 1e-6
}
