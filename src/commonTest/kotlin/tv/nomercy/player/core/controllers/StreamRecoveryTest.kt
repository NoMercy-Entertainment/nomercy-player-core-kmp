// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.controllers

import kotlin.test.Test
import kotlin.test.assertEquals

// The schedule the web has and this port did not.
//
// Every one of these describes a film that used to end. There was no test here
// because there was no policy here: a failure was announced and that was the
// whole behaviour, so nothing could be asserted about it beyond that it happened.
class StreamRecoveryTest {

    @Test
    fun aDroppedConnectionIsRetriedThreeTimesAndThenGivenUpOn() {
        val recovery = StreamRecovery()

        repeat(3) { attempt ->
            assertEquals(
                RecoveryStep.RELOAD,
                recovery.stepFor(FailureKind.NETWORK, nowMs = 0L),
                "attempt ${attempt + 1} of 3 should still be worth making",
            )
        }

        assertEquals(
            RecoveryStep.ESCALATE,
            recovery.stepFor(FailureKind.NETWORK, nowMs = 0L),
            "a fourth network failure is the answer, not another attempt",
        )
    }

    @Test
    fun theGapBetweenNetworkRetriesWidens() {
        val recovery = StreamRecovery()
        val waits: List<Long> = List(3) {
            recovery.stepFor(FailureKind.NETWORK, nowMs = 0L)
            recovery.retryDelayMs(FailureKind.NETWORK)
        }

        // One second, then two, then four. Retrying a congested link immediately
        // three times is three failures in the time the first one needed.
        assertEquals(listOf(1_000L, 2_000L, 4_000L), waits)
    }

    @Test
    fun framesFlowingAgainRefundsTheRetriesThatGotThere() {
        val recovery = StreamRecovery()

        repeat(3) { recovery.stepFor(FailureKind.NETWORK, nowMs = 0L) }
        recovery.progressed()

        // Otherwise a two-hour film that survived three bad patches dies at the
        // fourth, having recovered from every one before it.
        assertEquals(RecoveryStep.RELOAD, recovery.stepFor(FailureKind.NETWORK, nowMs = 0L))
    }

    @Test
    fun aDecodeFailureIsWorthOneAttemptAndTheSecondInsideTheWindowIsFinal() {
        val recovery = StreamRecovery()

        assertEquals(RecoveryStep.RELOAD, recovery.stepFor(FailureKind.MEDIA, nowMs = 0L))
        assertEquals(
            RecoveryStep.ESCALATE,
            recovery.stepFor(FailureKind.MEDIA, nowMs = 4_999L),
            "a reload that failed again in five seconds did not take",
        )
    }

    @Test
    fun aDecodeFailureLongAfterTheLastOneGetsItsOwnAttempt() {
        val recovery = StreamRecovery()

        recovery.stepFor(FailureKind.MEDIA, nowMs = 0L)

        assertEquals(
            RecoveryStep.RELOAD,
            recovery.stepFor(FailureKind.MEDIA, nowMs = 5_001L),
            "an hour of clean playback makes the next failure a new one",
        )
    }

    @Test
    fun everyEnginesVocabularyLandsInTheRightBucket() {
        // Media3's names, and the shapes the other two engines report. An engine
        // whose words this does not know is OTHER, which still gets one attempt.
        assertEquals(FailureKind.NETWORK, failureKindOf("ERROR_CODE_IO_NETWORK_CONNECTION_FAILED"))
        assertEquals(FailureKind.NETWORK, failureKindOf("ERROR_CODE_IO_BAD_HTTP_STATUS"))
        assertEquals(FailureKind.MEDIA, failureKindOf("ERROR_CODE_DECODING_FAILED"))
        assertEquals(FailureKind.MEDIA, failureKindOf("ERROR_CODE_DECODER_INIT_FAILED"))
        assertEquals(FailureKind.MEDIA, failureKindOf("ERROR_CODE_PARSING_MANIFEST_MALFORMED"))
        assertEquals(FailureKind.OTHER, failureKindOf("something libVLC said"))
        assertEquals(FailureKind.OTHER, failureKindOf(null))
    }

    @Test
    fun anItemChangeForgetsWhatTheLastOneSuffered() {
        val recovery = StreamRecovery()

        repeat(3) { recovery.stepFor(FailureKind.NETWORK, nowMs = 0L) }
        recovery.reset()

        assertEquals(RecoveryStep.RELOAD, recovery.stepFor(FailureKind.NETWORK, nowMs = 0L))
    }
}
