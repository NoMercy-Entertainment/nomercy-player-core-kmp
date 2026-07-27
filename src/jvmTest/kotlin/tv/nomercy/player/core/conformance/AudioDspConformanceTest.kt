// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.conformance

import tv.nomercy.player.core.dsp.EqBands
import tv.nomercy.player.core.dsp.EqPresets
import tv.nomercy.player.core.plugins.audio.EqualizerPlugin
import tv.nomercy.player.core.plugins.audio.SpectrumPlugin
import tv.nomercy.player.core.plugins.audio.VisualizationFrame
import tv.nomercy.player.core.plugins.audio.VisualizationPlugin
import kotlin.reflect.KClass
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.memberFunctions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The audio surface, held against the web player's.
//
// Everything else in this subsystem measures behaviour: does the filter apply
// the gain, does the band light. This measures names, which nothing else can —
// a plugin whose method is called `setBand` instead of `band` works perfectly
// and is still wrong, because a consumer porting a visualiser from the web
// finds nothing where they look for it and concludes the feature is missing.
//
// Names are the part of a contract that cannot be inferred from a passing test,
// so they are asserted directly.
class AudioDspConformanceTest {

    private fun methodsOf(type: KClass<*>): Set<String> =
        type.memberFunctions
            .filter { it.name !in INHERITED }
            .map { it.name }
            .toSet()

    @Test
    fun theEqualiserExposesTheWebsSurface() {
        val present: Set<String> = methodsOf(EqualizerPlugin::class)

        // Each of these is a call a consumer's existing code already makes.
        val required: Set<String> = setOf("bands", "band", "preGain", "preset", "presets", "reset")
        val missing: Set<String> = required - present

        assertTrue(missing.isEmpty(), "the equaliser is missing $missing — a web consumer's code would not compile")
    }

    @Test
    fun theSpectrumExposesTheWebsSurface() {
        val present: Set<String> = methodsOf(SpectrumPlugin::class)
        val required: Set<String> = setOf("currentFrame", "bandEnergy", "syntheticMode", "pushFrame")
        val missing: Set<String> = required - present

        assertTrue(missing.isEmpty(), "the spectrum plugin is missing $missing")
    }

    @Test
    fun aVisualiserOnlyHasToWriteRender() {
        // The whole point of the base class. If a consumer had to override more
        // than one thing, every visualiser would re-solve subscribing and
        // tearing down, and each would leak differently.
        val abstracts: List<String> = VisualizationPlugin::class.memberFunctions
            .filter { it.isAbstract }
            .map { it.name }

        assertEquals(listOf("render"), abstracts, "a visualiser now has to implement $abstracts")
    }

    @Test
    fun theFrameCarriesTheFieldNamesTheWebFrameCarries() {
        // A visualiser reads these by name. One renamed is a visualiser that
        // compiles against the web and not against this, for no reason a reader
        // would guess.
        val present: Set<String> = VisualizationFrame::class.declaredMemberProperties.map { it.name }.toSet()

        val required: Set<String> = setOf(
            "frequency",
            "waveform",
            "time",
            "deltaMs",
            "energy",
            "bandEnergies",
            "sampleRate",
            "binHz",
            "peakHz",
            "peakBandEnergies",
            "beat",
            "bpm",
        )

        assertTrue((required - present).isEmpty(), "VisualizationFrame is missing ${required - present}")
    }

    @Test
    fun theBandLayoutIsTheWebsTable() {
        // Not a round decade series, and not ours to choose. A listener who set
        // up an equaliser on the web has to find the same sliders here.
        assertEquals(
            listOf(70, 180, 320, 600, 1_000, 3_000, 6_000, 12_000, 14_000, 16_000),
            EqBands.DEFAULT.map { it.frequency },
        )
    }

    @Test
    fun everyWebPresetIsPresentUnderItsOwnName() {
        // Nineteen names, spelled the way the web spells them, because a stored
        // setting is looked up by this string. A renamed preset reads back as
        // unknown and the viewer's choice silently becomes Custom.
        val present: List<String> = EqPresets.BUILTIN.map { it.name }

        // Read off presets.ts, not from memory. The first version of this list
        // split "Laptop speakers/headphones" into two entries and reported the
        // implementation as wrong when the implementation was right — which is
        // the failure mode of writing a conformance table by recollection.
        val required: List<String> = listOf(
            "Custom", "Classical", "Club", "Dance", "Flat", "Laptop speakers/headphones",
            "Large hall", "Party", "Pop", "Reggae", "Rock", "Soft", "Ska", "Full Bass",
            "Soft Rock", "Full Treble", "Full Bass & Treble", "Live", "Techno",
        )

        assertEquals(required, present, "the preset table has drifted from the web's")
    }

    @Test
    fun everyPresetCoversEveryBand() {
        // A preset short of a band leaves that slider wherever the last preset
        // put it, so switching carries a value across and the curve is neither
        // preset.
        val layout: List<Int> = EqBands.DEFAULT.map { it.frequency }

        for (preset in EqPresets.BUILTIN) {
            assertEquals(layout, preset.bands.map { it.frequency }, "${preset.name} does not cover the layout")
        }
    }
}

// Kotlin puts these on everything. Filtering them keeps the assertion about the
// surface this project designed rather than about the language.
private val INHERITED: Set<String> = setOf(
    "equals", "hashCode", "toString", "use", "dispose", "enable", "disable", "enabled",
)
