// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import tv.nomercy.player.core.media.PlaylistItem
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private data class Track(override val id: String) : PlaylistItem

private class GainSpyBackend(private val capable: Boolean = true) : TransitionBackend {
    var gain: Float = 0.0f

    override fun supportsCrossfade(): Boolean = capable
    override suspend fun loadSecondary(url: String) = Unit
    override suspend fun primeSecondary(seekMs: Long) = Unit
    override suspend fun crossfade(durationMs: Long, curve: CrossfadeCurve) = Unit
    override fun disposeSecondary() = Unit
    override fun secondaryGain(): Float = gain
    override fun secondaryGain(value: Float) { gain = value }
}

class StrategiesTest {

    private val items = (1..20).map { Track("t$it") }
    private val outgoing = Track("outgoing")
    private val incoming = Track("incoming")

    @Test
    fun theSameSeedProducesTheSameOrder() {
        val first = FisherYatesShuffle(Random(42)).order(items, currentIndex = 0)
        val second = FisherYatesShuffle(Random(42)).order(items, currentIndex = 0)

        assertEquals(first, second)
    }

    @Test
    fun differentSeedsProduceDifferentOrders() {
        // Without this, a shuffle that returned the input unchanged would pass
        // both the determinism and the permutation tests.
        assertNotEquals(
            FisherYatesShuffle(Random(1)).order(items, 0),
            FisherYatesShuffle(Random(2)).order(items, 0),
        )
        assertNotEquals(items, FisherYatesShuffle(Random(1)).order(items, 0))
    }

    @Test
    fun everyPermutationIsReachable() {
        // The classic Fisher-Yates off-by-one (drawing from below the cursor
        // instead of including it) still returns a valid permutation and still
        // passes every test above — it just makes some orders impossible.
        val three = listOf(Track("a"), Track("b"), Track("c"))
        val seen = mutableSetOf<List<Track>>()

        repeat(300) { seed -> seen.add(FisherYatesShuffle(Random(seed)).order(three, 0)) }

        assertEquals(6, seen.size)
    }

    @Test
    fun everyItemSurvivesTheShuffleExactlyOnce() {
        val shuffled = FisherYatesShuffle(Random(7)).order(items, currentIndex = 5)

        assertEquals(items.size, shuffled.size)
        assertEquals(items.toSet(), shuffled.toSet())
    }

    @Test
    fun anEmptyOrSingleItemQueueShufflesToItself() {
        val shuffle = FisherYatesShuffle(Random(1))

        assertEquals(emptyList(), shuffle.order(emptyList<Track>(), -1))
        assertEquals(listOf(Track("only")), shuffle.order(listOf(Track("only")), 0))
    }

    @Test
    fun retryResolutionGoesExactThenCategoryThenRangeThenWildcard() {
        val policy: RetryPolicy = mapOf(
            "a:b/c" to RetryConfig(attempts = 3),
            "a:b/" to RetryConfig(attempts = 2),
            "5xx" to RetryConfig(attempts = 9),
            "*" to RetryConfig(attempts = 1),
        )

        assertEquals(3, policy.resolve("a:b/c").attempts)
        assertEquals(2, policy.resolve("a:b/x").attempts)
        assertEquals(9, policy.resolve("503").attempts)
        assertEquals(1, policy.resolve("weird:z/none").attempts)
    }

    @Test
    fun aCategoryEntryOutranksTheRangeThatWouldAlsoMatch() {
        // Real codes never hit both branches — an HTTP status has no category
        // and a player code does not start with a digit — so this pins the
        // documented order with a key that can reach both.
        val policy: RetryPolicy = mapOf(
            "500/" to RetryConfig(attempts = 7),
            "5xx" to RetryConfig(attempts = 9),
        )

        assertEquals(7, policy.resolve("500/timeout").attempts)
        assertEquals(9, policy.resolve("500").attempts)
    }

    @Test
    fun anUnmatchedCodeWithNoWildcardDoesNotRetry() {
        // A policy that has not been asked about a failure has not approved
        // retrying it.
        assertEquals(0, emptyMap<String, RetryConfig>().resolve("anything").attempts)
    }

    @Test
    fun theDefaultPolicyRetriesTransportAndRefusesTheRest() {
        assertEquals(5, DEFAULT_RETRY_POLICY.resolve("core:network/timeout").attempts)
        assertEquals(3, DEFAULT_RETRY_POLICY.resolve("500").attempts)

        // Retrying these just makes the same request again and delays telling
        // the viewer.
        assertEquals(0, DEFAULT_RETRY_POLICY.resolve("core:auth/forbidden").attempts)
        assertEquals(0, DEFAULT_RETRY_POLICY.resolve("core:media/codec-unsupported").attempts)
        assertEquals(0, DEFAULT_RETRY_POLICY.resolve("404").attempts)
    }

    @Test
    fun anExpiredTokenIsTheOneCaseThatRefreshesFirst() {
        val expired = DEFAULT_RETRY_POLICY.resolve("core:auth/unauthenticated")

        assertTrue(expired.refreshFirst)
        assertEquals(1, expired.attempts)
        assertFalse(DEFAULT_RETRY_POLICY.resolve("core:network/timeout").refreshFirst)
    }

    @Test
    fun exponentialBackoffDoublesFromTheBaseAndStopsAtTheCap() {
        val config = RetryConfig(attempts = 5, backoff = Backoff.EXPONENTIAL, baseMs = 500, maxMs = 3_000)

        assertEquals(500, config.delayMs(1))
        assertEquals(1_000, config.delayMs(2))
        assertEquals(2_000, config.delayMs(3))
        assertEquals(3_000, config.delayMs(4))
    }

    @Test
    fun aVeryLateAttemptStaysAtTheCapRatherThanOverflowing() {
        val config = RetryConfig(attempts = 99, backoff = Backoff.EXPONENTIAL, baseMs = 1_000, maxMs = 30_000)

        // Kotlin masks a Long shift to its low six bits, so attempt 65 shifts
        // by zero and an unclamped backoff quietly returns to the base delay
        // instead of the cap — a retry storm that looks like a backoff.
        assertEquals(30_000, config.delayMs(65))
        assertEquals(30_000, config.delayMs(80))
    }

    @Test
    fun linearBackoffScalesWithTheAttemptAndNoBackoffIsImmediate() {
        val linear = RetryConfig(attempts = 5, backoff = Backoff.LINEAR, baseMs = 200, maxMs = 5_000)

        assertEquals(200, linear.delayMs(1))
        assertEquals(600, linear.delayMs(3))
        assertEquals(0, linear.delayMs(0))
        assertEquals(0, RetryConfig(attempts = 3).delayMs(2))
    }

    @Test
    fun preloadFiresOnceInsideTheLeadWindow() {
        val strategy = DefaultPreloadStrategy(leadSeconds = 10.0)

        assertTrue(strategy.shouldPreload(PreloadContext(91.0, 100.0, incoming)))
        assertTrue(strategy.shouldPreload(PreloadContext(90.0, 100.0, incoming)))
        assertFalse(strategy.shouldPreload(PreloadContext(89.9, 100.0, incoming)))
    }

    @Test
    fun preloadHoldsWithNothingNextOrNoKnownEnd() {
        val strategy = DefaultPreloadStrategy(leadSeconds = 10.0)

        assertFalse(strategy.shouldPreload(PreloadContext(95.0, 100.0, null)))
        // A live stream has no end to measure from.
        assertFalse(strategy.shouldPreload(PreloadContext(95.0, 0.0, incoming)))
    }

    @Test
    fun crossfadeStartsInsideItsLeadWindow() {
        val strategy = CrossfadeTransitionStrategy(leadSeconds = 3.0)

        assertTrue(strategy.shouldTransition(PreloadContext(97.0, 100.0, incoming)))
        assertFalse(strategy.shouldTransition(PreloadContext(96.9, 100.0, incoming)))
        assertFalse(strategy.shouldTransition(PreloadContext(99.0, 100.0, null)))
    }

    @Test
    fun crossfadeRidesTheEqualPowerCurveAcrossTheWindow() {
        val strategy = CrossfadeTransitionStrategy(curve = CrossfadeCurve.EQUAL_POWER)
        val backend = GainSpyBackend()

        listOf(0.0, 0.25, 0.5, 0.75, 1.0).forEach { fraction ->
            strategy.tick(TransitionContext(99.0, 100.0, outgoing, incoming, fraction), backend)
            val expected: Float = CrossfadeCurve.EQUAL_POWER.gain(fraction).toFloat()
            assertTrue(abs(backend.gain - expected) < 1e-6f, "gain ${backend.gain} at fraction $fraction")
        }
    }

    @Test
    fun theCrossfadeDefaultIsEqualPowerNotLinear() {
        val backend = GainSpyBackend()

        // Halfway through, the two curves differ by more than a rounding error:
        // linear is 0.5 and equal-power is 0.707. Taking the default is the
        // difference between a dip and a level fade.
        CrossfadeTransitionStrategy().tick(
            TransitionContext(99.0, 100.0, outgoing, incoming, 0.5),
            backend,
        )

        assertTrue(abs(backend.gain - CrossfadeCurve.EQUAL_POWER.gain(0.5).toFloat()) < 1e-6f)
        assertEquals(CrossfadeCurve.EQUAL_POWER, CrossfadeTransitionStrategy().curve)
    }

    @Test
    fun whenAndHowLongAreSeparateSettings() {
        val strategy = CrossfadeTransitionStrategy(leadSeconds = 8.0, tailSeconds = 2.0)

        // A long lead with a short fade is a legitimate setup, so the fade
        // length is not derived from the lead.
        assertEquals(2_000, strategy.crossfadeMs)
        assertTrue(strategy.shouldTransition(PreloadContext(92.0, 100.0, incoming)))
    }

    @Test
    fun aBackendThatCannotOverlapIsLeftAloneRatherThanFailing() {
        val strategy = CrossfadeTransitionStrategy()
        val incapable = GainSpyBackend(capable = false)

        strategy.tick(TransitionContext(99.0, 100.0, outgoing, incoming, 0.5), incapable)
        strategy.tick(TransitionContext(99.0, 100.0, outgoing, incoming, 0.5), null)

        // The result is a hard cut, which is the honest degradation.
        assertEquals(0.0f, incapable.gain)
    }

    @Test
    fun theGaplessStrategyNeverOverlapsAndNeverTouchesGain() {
        val strategy = GaplessTransitionStrategy()
        val backend = GainSpyBackend()

        assertFalse(strategy.shouldTransition(PreloadContext(100.0, 100.0, incoming)))
        strategy.tick(TransitionContext(100.0, 100.0, outgoing, incoming, 1.0), backend)

        // Two video streams overlapping is a dissolve nobody asked for.
        assertEquals(0.0f, backend.gain)
    }
}
