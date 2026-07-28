// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.conformance

import kotlinx.coroutines.test.runTest
import tv.nomercy.player.core.events.CoreEvents
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import tv.nomercy.player.conformance.Scenario
import tv.nomercy.player.conformance.ScenarioAction
import tv.nomercy.player.conformance.ScenarioResult
import tv.nomercy.player.conformance.firstUnmatched
import tv.nomercy.player.conformance.loadScenarios
import tv.nomercy.player.conformance.scenarioItems

// The native player against the same scenarios the web harness runs.
//
// A scenario that passes there and fails here is a divergence between the two
// implementations, which is the only thing this suite is for. The scenario file
// is vendored from the harness repo so both sides measure with one ruler.
class ConformanceTest {

    private val file = loadScenarios()

    private val beforePlay = "beforePlay"

    // What the core controllers are responsible for today. A scenario naming a
    // backend event or a lifecycle stage core does not own yet is not a
    // conformance failure — it is a scenario for something not built. Listing
    // them by id rather than by a "skip if it fails" rule keeps that honest:
    // this list can only be shortened, and shortening it is the work.
    private val notYetOwnedByCore = setOf(
        "backend/stalled",
        "backend/waiting",
        "backend/level-switched",
        "lifecycle/video-reports-duration-and-canplay",
    )

    @Test
    fun theVendoredScenariosParseAndMatchTheContractTheyWereWrittenFor() {
        assertEquals("2.0.1", file.contractVersion)
        assertTrue(file.scenarios.isNotEmpty())

        val names = CoreEvents.all.map { it.name }.toSet()
        val unknown = file.scenarios.flatMap { it.expect }.filterNot { it in names }.distinct().sorted()

        // canplay, stalled and waiting are video-map events, so they belong to
        // VideoEvents in the video library rather than here. Naming them keeps
        // the check honest: anything else appearing would be a scenario
        // asserting on something the native side could never emit.
        assertEquals(listOf("canplay", "stalled", "waiting"), unknown)
    }

    @Test
    fun captureSeesTheBeforeEventsTheFirehoseCannot() {
        // The one rule the whole harness rests on. If this ever fails, either
        // the before-dispatch started going through emit() and capture can be
        // simplified, or the registry lost its before-events.
        val befores = beforeEventNames()

        // Nineteen in the base map. The twentieth the web harness counts is
        // beforeQuality, which is a video-map event.
        assertEquals(19, befores.size)
        assertTrue(befores.contains(beforePlay))
        assertTrue(befores.contains("beforeSeek"))
    }

    @Test
    fun everyScenarioCoreOwnsPassesAgainstTheNativePlayer() = runTest {
        val owned = file.scenarios.filterNot { it.id in notYetOwnedByCore }
        val failures = owned
            .map { runScenario(it) }
            .filterNot { it.ok }
            .map { "${it.id}: ${it.reason}\n    observed: ${it.observed}" }

        assertEquals(emptyList(), failures)
    }

    @Test
    fun theNotYetOwnedListIsAboutBackendAndLifecycleAndNothingElse() {
        // Guards the exemption itself. If a transport or queue scenario ever
        // ends up on that list, something regressed and the list is hiding it.
        val prefixes = notYetOwnedByCore.map { it.substringBefore('/') }.toSet()

        assertEquals(setOf("backend", "lifecycle"), prefixes)
    }

    @Test
    fun aScenarioCallingAMethodThePlayerLacksFailsLoudly() = runTest {
        val result = runScenario(
            Scenario(
                id = "invented/method",
                name = "calls something that is not there",
                medium = "video",
                actions = listOf(ScenarioAction(method = "teleport")),
                expect = listOf("play"),
            ),
        )

        assertTrue(!result.ok)
        assertTrue(result.reason.orEmpty().contains("teleport"))
    }

    @Test
    fun theMatcherRejectsAMissingEventAndTheRightEventsInTheWrongOrder() {
        val first = "first"
        val second = "second"
        val third = "third"

        assertEquals(-1, firstUnmatched(listOf(first, third), listOf(first, second, third)))
        assertEquals(1, firstUnmatched(listOf(first, third), listOf(first, second)))
        assertEquals(1, firstUnmatched(listOf("play", beforePlay), listOf(beforePlay, "play")))
        // Each expectation consumes one observation, so a repeat needs two.
        assertEquals(1, firstUnmatched(listOf(first, first), listOf(first)))
    }
}
