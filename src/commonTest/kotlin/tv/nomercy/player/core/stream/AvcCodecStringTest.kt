// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.stream

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AvcCodecStringTest {

    @Test
    fun high10IsReadAsProfile110() {
        assertEquals(AvcCodecString.HIGH_10, AvcCodecString.profileIdc("avc1.6E0028"))
        assertTrue(AvcCodecString.isHigh10("avc1.6E0028"))
    }

    // The two strings differ by one character and by whether any Android device
    // can open the file. No-Rin's master playlist declared the first while the
    // stream was the second.
    @Test
    fun highIsNotHigh10() {
        assertEquals(0x64, AvcCodecString.profileIdc("avc1.640028"))
        assertFalse(AvcCodecString.isHigh10("avc1.640028"))
    }

    @Test
    fun caseAndAvc3AreTheSameCodecString() {
        assertTrue(AvcCodecString.isHigh10("AVC1.6e0028"))
        assertTrue(AvcCodecString.isHigh10("avc3.6E0028"))
    }

    // Null rather than a default: "not AVC" must not be read as ordinary High
    // profile by a caller deciding which engine can open the file.
    @Test
    fun anythingThatIsNotAnAvcCodecStringHasNoProfile() {
        assertNull(AvcCodecString.profileIdc("hev1.2.4.L120.90"))
        assertNull(AvcCodecString.profileIdc("mp4a.40.2"))
        assertNull(AvcCodecString.profileIdc("avc1"))
        assertNull(AvcCodecString.profileIdc(null))
        assertFalse(AvcCodecString.isHigh10(null))
    }
}
