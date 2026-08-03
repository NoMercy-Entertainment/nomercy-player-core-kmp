// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.controllers

import kotlinx.coroutines.test.runTest
import tv.nomercy.player.core.errors.Severity
import tv.nomercy.player.core.events.BeforeMutationPayload
import tv.nomercy.player.core.events.CoreEvents
import tv.nomercy.player.core.events.MutationPreventedPayload
import tv.nomercy.player.core.events.PlayerErrorEvent
import tv.nomercy.player.core.player.MutationGuards
import tv.nomercy.player.core.player.PlayerConfig
import tv.nomercy.player.core.player.RepeatState
import tv.nomercy.player.core.plugin.Plugin
import tv.nomercy.player.core.plugin.PluginAdvisory
import tv.nomercy.player.core.plugin.PluginManifest
import tv.nomercy.player.testing.FakeMediaBackend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class AdvisingPlugin : Plugin<Unit>() {
    companion object Manifest : PluginManifest {
        override val id: String = "advisor"
        override val version: String = "1.0.0"
    }

    override val manifest: PluginManifest get() = Manifest

    override val advisories: List<PluginAdvisory> = listOf(
        PluginAdvisory(
            method = "repeatState",
            severity = Severity.WARNING,
            reason = "repeat-during-handoff",
            message = "changing repeat while a handoff is in flight will not reach the other device",
        ),
    )
}

// CoreEvents.BeforeMutation and CoreEvents.MutationPrevented were declared,
// carried payloads, were listed in the registry, and were emitted by nothing:
// mutationGuards was a config field with no behaviour and a plugin subscribing
// to beforeMutation heard nothing, ever.
class MutationGuardTest {

    private suspend fun player(guards: MutationGuards = MutationGuards.Default): ComposedPlayer =
        ComposedPlayer(backend = FakeMediaBackend()).apply {
            setup(PlayerConfig(mutationGuards = guards))
        }

    @Test
    fun aGuardedMutationAnnouncesItselfWithTheMethodTheArgumentsAndThePhase() = runTest {
        val subject = player()
        val seen: MutableList<BeforeMutationPayload> = mutableListOf()
        subject.on(CoreEvents.BeforeMutation) { seen += it.data }

        subject.repeatState(RepeatState.ALL)

        assertEquals(1, seen.size, "beforeMutation reached nobody")
        assertEquals("repeatState", seen.first().method)
        assertEquals(listOf(RepeatState.ALL), seen.first().args)
        assertEquals(subject.phase(), seen.first().phase)
    }

    @Test
    fun aListenerThatRefusesStopsTheMutationAndSaysSo() = runTest {
        // Both halves. Without the event a caller cannot know it was refused,
        // and without the state check a test passes on a guard that announces
        // and then mutates anyway.
        val subject = player()
        val prevented: MutableList<MutationPreventedPayload> = mutableListOf()
        subject.on(CoreEvents.MutationPrevented) { prevented += it }
        subject.on(CoreEvents.BeforeMutation) { it.preventDefault() }

        subject.repeatState(RepeatState.ALL)

        assertEquals(RepeatState.OFF, subject.repeatState(), "the mutation happened anyway")
        assertEquals(listOf("repeatState"), prevented.map { it.method })
    }

    @Test
    fun anUnguardedRunLetsTheMutationThrough() = runTest {
        // The control. Every assertion above would also pass on a player whose
        // repeat state never changes.
        val subject = player()

        subject.repeatState(RepeatState.ALL)

        assertEquals(RepeatState.ALL, subject.repeatState())
    }

    @Test
    fun aHotMutationStaysQuietUntilAConsumerAsksForIt() = runTest {
        // recordMetric fires per sample. Guarding it by default would put a
        // dispatch on the engine's own clock rate.
        val quiet = player()
        val heard: MutableList<String> = mutableListOf()
        quiet.on(CoreEvents.BeforeMutation) { heard += it.data.method }
        quiet.recordMetric(Metric.DROPPED_FRAMES, 1.0)
        assertEquals(emptyList(), heard)

        val asked = player(MutationGuards.Including(setOf("recordMetric")))
        asked.on(CoreEvents.BeforeMutation) { heard += it.data.method }
        asked.recordMetric(Metric.DROPPED_FRAMES, 1.0)
        assertEquals(listOf("recordMetric"), heard)
    }

    @Test
    fun namingAHotMutationAddsToTheOrdinaryOnesRatherThanReplacingThem() = runTest {
        val subject = player(MutationGuards.Including(setOf("recordMetric")))
        val heard: MutableList<String> = mutableListOf()
        subject.on(CoreEvents.BeforeMutation) { heard += it.data.method }

        subject.repeatState(RepeatState.ALL)

        assertTrue("repeatState" in heard, "the ordinary guards were switched off by naming a hot one")
    }

    @Test
    fun guardsOffMeansNoDispatchAtAll() = runTest {
        val subject = player(MutationGuards.None)
        val heard: MutableList<String> = mutableListOf()
        subject.on(CoreEvents.BeforeMutation) { heard += it.data.method }

        subject.repeatState(RepeatState.ALL)

        assertEquals(emptyList(), heard)
        assertEquals(RepeatState.ALL, subject.repeatState(), "turning guards off also stopped the mutation")
    }

    @Test
    fun aPluginsAdvisoryIsRaisedForItWithACodeNamingThePluginThatDeclaredIt() = runTest {
        val subject = player()
        val errors: MutableList<PlayerErrorEvent> = mutableListOf()
        subject.on(CoreEvents.Error) { errors += it }
        subject.addPlugin(AdvisingPlugin())

        subject.repeatState(RepeatState.ALL)

        assertEquals(1, errors.size, "the advisory reached nobody")
        assertEquals("plugin:advisor/repeat-during-handoff", errors.first().code)
        assertEquals(Severity.WARNING, errors.first().severity)
    }

    @Test
    fun aDisabledPluginStopsAdvising() = runTest {
        // A viewer who turned a plugin off should not keep getting its warnings.
        val subject = player()
        val plugin = AdvisingPlugin()
        subject.addPlugin(plugin)
        val errors: MutableList<PlayerErrorEvent> = mutableListOf()
        subject.on(CoreEvents.Error) { errors += it }

        plugin.disable("switched off")
        subject.repeatState(RepeatState.ALL)

        assertEquals(emptyList(), errors.map { it.code })
    }
}
