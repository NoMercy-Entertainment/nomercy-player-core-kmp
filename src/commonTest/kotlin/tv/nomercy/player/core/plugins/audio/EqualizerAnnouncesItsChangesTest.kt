// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugins.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import tv.nomercy.player.core.dsp.EqPresets
import tv.nomercy.player.core.plugin.PluginRegistry
import tv.nomercy.player.core.plugin.fakes.FakePluginHost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// The equaliser tells anyone listening what it just did.
//
// EqualizerEvents has been declared since the port began -- BandChanged,
// PresetChanged, Ready, Changed, each with a payload and a doc comment -- with
// ZERO references anywhere: no key, no emit, no listener. A payload type
// nothing ever sends is a contract nobody can hold, and the consequence is not
// abstract: a panel could only ever show the changes it made itself, so a
// preset applied from a remote, a restored session or a second view left every
// slider displaying the old curve.
class EqualizerAnnouncesItsChangesTest {

    @Test
    fun draggingABandReportsThatBand() = runTest {
        val host = FakePluginHost()
        val equalizer = EqualizerPlugin(FakeDspGraph())
        registryFor(host, CoroutineScope(StandardTestDispatcher(testScheduler))).register(equalizer)

        val frequency: Int = equalizer.bands().first().frequency
        equalizer.band(frequency, GAIN)

        val sent: Pair<String, Any?>? =
            host.emitted.lastOrNull { it.first.endsWith("band:changed") }
        assertNotNull(sent, "a slider drag told nobody: ${host.emitted.map { it.first }}")
        val payload = sent.second as EqualizerEvents.BandChanged
        assertEquals(GAIN, payload.band.gainDb, "the event carried a different gain than the drag")
        assertEquals(frequency, payload.band.frequency, "the event named a different band")
    }

    @Test
    fun applyingAPresetReportsItByName() = runTest {
        val host = FakePluginHost()
        val equalizer = EqualizerPlugin(FakeDspGraph())
        registryFor(host, CoroutineScope(StandardTestDispatcher(testScheduler))).register(equalizer)

        equalizer.preset(EqPresets.ROCK.name)

        val sent: Pair<String, Any?>? =
            host.emitted.lastOrNull { it.first.endsWith("preset:changed") }
        assertNotNull(sent, "a preset was applied and nothing was announced")
        assertEquals(EqPresets.ROCK.name, (sent.second as EqualizerEvents.PresetChanged).name)
    }

    @Test
    fun anUnregisteredPluginStillWorksAndSaysNothing() {
        // The half that broke four green tests when the events went in: a
        // plugin's setters are callable before anyone registers it, and an
        // announcement with no host to hear it is not an error.
        val equalizer = EqualizerPlugin(FakeDspGraph())

        equalizer.preset(EqPresets.ROCK.name)

        assertEquals(EqPresets.ROCK.bands, equalizer.bands(), "the curve was not applied")
        assertTrue(equalizer.bands().isNotEmpty())
    }

    private fun registryFor(host: FakePluginHost, scope: CoroutineScope): PluginRegistry =
        PluginRegistry(host, coreVersion = "2.0.0", scope = scope)

    private companion object {
        const val GAIN: Double = 6.0
    }
}
