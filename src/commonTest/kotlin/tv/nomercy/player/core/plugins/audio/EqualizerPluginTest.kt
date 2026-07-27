// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugins.audio

import tv.nomercy.player.core.dsp.EqBands
import tv.nomercy.player.core.dsp.EqPresets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// What the sliders do to the sound path.
//
// The filter arithmetic is proven against known signals elsewhere. What this
// covers is the half a listener notices without being able to name: whether a
// preset arrives in one piece, whether a drag rebuilds the chain, and whether
// the UI is being told the truth about what it is showing.
class EqualizerPluginTest {

    @Test
    fun aPresetIsSentAsOneChange() {
        val graph = FakeDspGraph()
        val equalizer = EqualizerPlugin(graph)

        equalizer.preset("Rock")

        assertEquals(1, graph.setEqBandsCalls)
        assertEquals(EqPresets.ROCK.bands, graph.installedBands)
        assertTrue(graph.bandUpdates.isEmpty(), "a preset leaked out as per-band updates")
        assertEquals(EqPresets.ROCK.bands, equalizer.bands())
    }

    @Test
    fun aSliderDragTouchesOneBandAndLeavesTheChainStanding() {
        val graph = FakeDspGraph()
        val equalizer = EqualizerPlugin(graph)
        equalizer.preset("Rock")

        equalizer.band(frequencyHz = 1_000, gainDb = 3.0)

        assertEquals(listOf(1_000 to 3.0), graph.bandUpdates)
        assertEquals(1, graph.setEqBandsCalls, "a drag rebuilt the whole chain")
        assertEquals(3.0, equalizer.bands().first { it.frequency == 1_000 }.gainDb)
    }

    @Test
    fun editingAPresetMakesTheCurveTheViewersOwn() {
        // A UI still showing "Rock" after the viewer has moved a slider is
        // claiming something untrue about what they are hearing, and the next
        // person to open the menu cannot tell whether it is the real Rock.
        val equalizer = EqualizerPlugin(FakeDspGraph())
        equalizer.preset("Rock")
        assertEquals("Rock", equalizer.preset())

        equalizer.band(frequencyHz = 1_000, gainDb = 3.0)

        assertEquals(EqPresets.CUSTOM.name, equalizer.preset())
    }

    @Test
    fun anUnknownPresetChangesNothing() {
        // Stored settings outlive the list they were chosen from. A preset that
        // has since been renamed must leave the sound alone rather than reset
        // it to flat, which would silently undo whatever the viewer had.
        val graph = FakeDspGraph()
        val equalizer = EqualizerPlugin(graph)
        equalizer.preset("Rock")

        equalizer.preset("Not A Preset")

        assertEquals(EqPresets.ROCK.bands, equalizer.bands())
        assertEquals(1, graph.setEqBandsCalls)
    }

    @Test
    fun aBandThatIsNotInTheLayoutIsIgnored() {
        // Frequencies come from stored settings too, and a layout can change
        // between versions. Writing past the end of the list would be a crash
        // in a settings screen rather than a slider that does nothing.
        val graph = FakeDspGraph()
        val equalizer = EqualizerPlugin(graph)

        equalizer.band(frequencyHz = 999, gainDb = 6.0)

        assertTrue(graph.bandUpdates.isEmpty())
        assertEquals(EqBands.DEFAULT, equalizer.bands())
    }

    @Test
    fun resetReturnsTheCurveAndTheHeadroomTogether() {
        val graph = FakeDspGraph()
        val equalizer = EqualizerPlugin(graph)
        equalizer.preset("Full Bass")
        equalizer.preGain(2.0)

        equalizer.reset()

        assertEquals(EqBands.DEFAULT, equalizer.bands())
        assertEquals(1.0, equalizer.preGain(), "the headroom was left where the old curve needed it")
        assertEquals(1.0, graph.preGain)
    }

    @Test
    fun switchingTheEqualiserOffKeepsTheCurve() {
        // Off and flat are different. A viewer who bypasses the equaliser to
        // compare expects their curve to still be there when they switch back.
        val graph = FakeDspGraph()
        val equalizer = EqualizerPlugin(graph)
        equalizer.preset("Rock")

        equalizer.enabled(false)

        assertFalse(equalizer.equalizerEnabled())
        assertFalse(graph.enabled)
        assertEquals(EqPresets.ROCK.bands, equalizer.bands(), "bypassing threw the curve away")
    }

    @Test
    fun withNoGraphTheControlsReportThemselvesUnavailable() {
        // The video engines have no DSP graph. A UI should draw the controls
        // disabled rather than let a viewer move sliders that change nothing —
        // silently accepting the calls is how a control comes to look broken
        // instead of absent.
        val equalizer = EqualizerPlugin(null)

        assertFalse(equalizer.available())

        // And it still does not throw, because a host that registers the plugin
        // everywhere should not have to special-case the engines without one.
        equalizer.preset("Rock")
        equalizer.band(frequencyHz = 1_000, gainDb = 3.0)
        equalizer.reset()
    }

    @Test
    fun theHeadroomSliderIsAMultiplierAndItsMiddleIsUnity() {
        val graph = FakeDspGraph()
        val equalizer = EqualizerPlugin(graph)

        equalizer.preGain(0.0)
        assertEquals(1.0, graph.preGain)

        equalizer.preGain(3.0)
        assertEquals(4.0, graph.preGain)
    }
}
