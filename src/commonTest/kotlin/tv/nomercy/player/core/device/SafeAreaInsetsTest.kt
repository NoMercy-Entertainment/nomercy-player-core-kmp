// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.device

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// How far in it is safe to draw.
//
// The arithmetic is the part that goes wrong. Two sources of inset describe the
// same edge, and treating them as two margins pushes the controls a long way
// into a picture nobody was hiding.
class SafeAreaInsetsTest {

    @Test
    fun twoClaimsAboutAnEdgeTakeTheLargerRatherThanBoth() {
        val overscan = SafeAreaInsets(left = 48f, top = 27f, right = 48f, bottom = 27f)
        val systemBars = SafeAreaInsets(left = 12f, top = 30f, right = 64f, bottom = 16f)

        val combined: SafeAreaInsets = overscan + systemBars

        // Every edge, because a merge that is right on three of them and adds on
        // the fourth passes a test that only checks the three.
        assertEquals(48f, combined.left)
        assertEquals(30f, combined.top)
        assertEquals(64f, combined.right)
        assertEquals(27f, combined.bottom)
    }

    @Test
    fun anEdgeNobodyClaimedStaysWhereItWas() {
        val overscan = SafeAreaInsets(left = 48f, right = 48f)

        val combined: SafeAreaInsets = overscan + SafeAreaInsets(top = 30f)

        assertEquals(48f, combined.left)
        assertEquals(48f, combined.right)
    }

    @Test
    fun combiningIsTheSameWhicheverWayRound() {
        // Two sources arriving in a different order on one platform than another
        // would otherwise inset differently, which reads as one device being
        // wrong rather than the merge being.
        val a = SafeAreaInsets(left = 48f, top = 10f)
        val b = SafeAreaInsets(left = 12f, top = 30f)

        assertEquals(a + b, b + a)
    }

    @Test
    fun theTelevisionDefaultIsFivePercentOfTheUsualPicture() {
        // The figure the broadcast world settled on and every panel is built to
        // be safe within. Written down because no Android version since API 21
        // will say what a given television actually crops.
        assertEquals(48f, DEFAULT_TV_OVERSCAN.left)
        assertEquals(27f, DEFAULT_TV_OVERSCAN.top)
        assertEquals(DEFAULT_TV_OVERSCAN.left, DEFAULT_TV_OVERSCAN.right)
        assertEquals(DEFAULT_TV_OVERSCAN.top, DEFAULT_TV_OVERSCAN.bottom)
    }

    @Test
    fun nothingInsetIsRecognisableAsSuch() {
        // A chrome skips its padding entirely rather than applying four zeroes,
        // and on a desktop that is every frame.
        assertTrue(SafeAreaInsets().isEmpty)
        assertFalse(DEFAULT_TV_OVERSCAN.isEmpty)
    }
}
