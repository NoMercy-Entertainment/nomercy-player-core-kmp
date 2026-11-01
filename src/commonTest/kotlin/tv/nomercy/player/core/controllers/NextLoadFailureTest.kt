// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.controllers

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import tv.nomercy.player.core.errors.CoreErrorCodes
import tv.nomercy.player.core.events.CoreEvents
import tv.nomercy.player.core.events.ItemSkipped
import tv.nomercy.player.core.player.PlayState
import tv.nomercy.player.core.player.RepeatState
import tv.nomercy.player.core.ports.CanonicalBackendEvent
import tv.nomercy.player.core.ports.EngineFailure
import tv.nomercy.player.testing.FakeMediaBackend
import tv.nomercy.player.testing.TestItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// next() behaves like the web trio's: a missing item is skipped, a 401 is
// refreshed and retried once, and a server error is fatal without a skip.
@OptIn(ExperimentalCoroutinesApi::class)
class NextLoadFailureTest {

    private companion object {
        const val PUMPS: Int = 20
    }

    // Answers each url with the next status in its script; 0 loads normally.
    private class ScriptedBackend : FakeMediaBackend() {
        val script: MutableMap<String, ArrayDeque<EngineFailure?>> = mutableMapOf()
        var silent: Boolean = false

        override suspend fun load(url: String, opts: tv.nomercy.player.core.ports.LoadOptions) {
            if (silent) {
                loadedUrls += url
                return
            }
            val failure: EngineFailure? = script[url]?.removeFirstOrNull()
            if (failure == null) {
                super.load(url, opts)
                return
            }
            loadedUrls += url
            fire(CanonicalBackendEvent.ERROR, failure)
        }
    }

    private class Rig(val scope: TestScope, val backend: ScriptedBackend) {
        val auth: CountingAuth = CountingAuth()
        val player: ComposedPlayer = ComposedPlayer(backend, scope = scope.backgroundScope).also { it.auth(auth) }
        val skipped: MutableList<ItemSkipped> = mutableListOf()
        val exhausted: MutableList<Unit> = mutableListOf()
        val fatals: MutableList<String> = mutableListOf()
        val errors: MutableList<String> = mutableListOf()

        init {
            player.on(CoreEvents.ItemSkipped) { skipped += it }
            player.on(CoreEvents.QueueExhausted) { exhausted += it }
            player.on(CoreEvents.Fatal) { fatals += it.code }
            player.on(CoreEvents.Error) { errors += it.code }
        }
    }

    private class CountingAuth : AuthController() {
        var refreshes: Int = 0
        override suspend fun refresh(): Boolean {
            refreshes += 1
            return true
        }
    }

    private suspend fun TestScope.rig(vararg ids: String): Rig {
        val rig = Rig(this, ScriptedBackend())
        rig.player.queue(ids.map { TestItem(it) })
        rig.player.setup()
        rig.player.load(TestItem(ids.first()))
        return rig
    }

    // A bounded pump: the player's own tickers would keep advance-until-idle going.
    private fun TestScope.advanceUntilIdle() {
        repeat(PUMPS) {
            runCurrent()
            advanceTimeBy(1)
        }
    }

    private fun url(id: String): String = "https://example.test/$id"

    private fun ScriptedBackend.fail(id: String, vararg failures: EngineFailure) {
        script[url(id)] = ArrayDeque(failures.toList())
    }

    private fun absent(): EngineFailure = EngineFailure(CoreErrorCodes.MEDIA_ABSENT, 404)

    private fun unauthorized(): EngineFailure = EngineFailure(CoreErrorCodes.UNAUTHENTICATED, 401)

    private fun serverError(): EngineFailure = EngineFailure(CoreErrorCodes.SERVER_ERROR, 500)

    @Test
    fun aMissingItemIsSkippedAndTheFollowingOneLoads() = runTest {
        val rig: Rig = rig("a", "b", "c")
        rig.backend.fail("b", absent())

        rig.player.next()
        advanceUntilIdle()

        assertEquals(listOf("b"), rig.skipped.map { it.item.id })
        assertEquals(listOf(404), rig.skipped.map { it.status })
        assertEquals("c", rig.player.item()?.id)
        assertTrue(rig.backend.loadedUrls.contains(url("c")))
    }

    @Test
    fun aGoneItemIsSkippedToo() = runTest {
        val rig: Rig = rig("a", "b", "c")
        rig.backend.fail("b", EngineFailure(CoreErrorCodes.MEDIA_ABSENT, 410))

        rig.player.next()
        advanceUntilIdle()

        assertEquals(listOf(410), rig.skipped.map { it.status })
        assertEquals("c", rig.player.item()?.id)
    }

    @Test
    fun theLastItemMissingStopsAtTheQueueEnd() = runTest {
        val rig: Rig = rig("a", "b")
        rig.backend.fail("b", absent())

        rig.player.next()
        advanceUntilIdle()

        assertEquals(listOf("b"), rig.skipped.map { it.item.id })
        assertEquals(1, rig.exhausted.size)
        assertEquals(1, rig.backend.loadedUrls.count { it == url("b") })
    }

    @Test
    fun repeatAllNeverLoopsOverAQueueOfMissingItems() = runTest {
        val rig: Rig = rig("a", "b", "c")
        rig.player.repeatState(RepeatState.ALL)
        rig.backend.fail("b", absent())
        rig.backend.fail("c", absent(), absent())
        rig.backend.fail("a", absent(), absent())

        rig.player.next()
        advanceUntilIdle()

        // b and c are missing and a is the item next() started from, which is not
        // retried inside the same call.
        assertEquals(listOf("b", "c"), rig.skipped.map { it.item.id })
        assertEquals(1, rig.exhausted.size)
        assertEquals(1, rig.backend.loadedUrls.count { it == url("b") })
        assertEquals(1, rig.backend.loadedUrls.count { it == url("c") })
    }

    @Test
    fun anUnauthorizedItemRefreshesOnceAndLoadsTheSameItemAgain() = runTest {
        val rig: Rig = rig("a", "b", "c")
        rig.backend.fail("b", unauthorized())

        rig.player.next()
        advanceUntilIdle()

        assertEquals(1, rig.auth.refreshes)
        assertEquals(2, rig.backend.loadedUrls.count { it == url("b") })
        assertEquals("b", rig.player.item()?.id)
        assertEquals(emptyList(), rig.skipped)
    }

    @Test
    fun aSecondUnauthorizedAnswerFailsWithoutAnotherRefresh() = runTest {
        val rig: Rig = rig("a", "b", "c")
        rig.backend.fail("b", unauthorized(), unauthorized())

        rig.player.next()
        advanceUntilIdle()

        assertEquals(1, rig.auth.refreshes)
        assertEquals(2, rig.backend.loadedUrls.count { it == url("b") })
        assertTrue(rig.errors.contains(CoreErrorCodes.UNAUTHENTICATED), "the failure is reported, not hidden")
        assertEquals(emptyList(), rig.skipped)
    }

    @Test
    fun aServerErrorIsFatalAndSkipsNothing() = runTest {
        val rig: Rig = rig("a", "b", "c")
        rig.backend.fail("b", serverError())

        rig.player.next()
        advanceUntilIdle()

        assertEquals(listOf(CoreErrorCodes.SERVER_ERROR), rig.fatals)
        assertEquals(PlayState.ERROR, rig.player.playState())
        assertEquals(emptyList(), rig.skipped)
        assertEquals(0, rig.backend.loadedUrls.count { it == url("c") })
    }

    @Test
    fun aLoadThatIsStillWorkingIsNotCutOffByAClock() = runTest {
        val rig: Rig = rig("a", "b")
        rig.backend.silent = true

        rig.player.next()
        advanceTimeBy(10 * 60_000L)

        assertEquals(emptyList(), rig.errors)
        assertEquals(emptyList(), rig.fatals)
        assertEquals(emptyList(), rig.skipped)
    }

    @Test
    fun previousDoesNotSkipAMissingItem() = runTest {
        val rig: Rig = rig("a", "b", "c")
        rig.player.next()
        rig.player.next()
        rig.backend.fail("b", absent())

        rig.player.previous()
        advanceUntilIdle()

        assertEquals(emptyList(), rig.skipped)
        assertEquals("b", rig.player.item()?.id)
    }
}
