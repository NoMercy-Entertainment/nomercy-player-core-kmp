// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import kotlin.test.Test
import kotlin.test.assertEquals

// The `nm-rung` lines, against plain values (a Format needs an Android runtime).
class RungLogTest {

    private val lines: MutableList<String> = mutableListOf()
    private val log = RungLog { line -> lines += line }

    @Test
    fun anAdaptiveSwitchWithNoTrackChangeLogsTheNewRung() {
        log.tracksChanged(
            width = 1920,
            height = 804,
            codecs = "hvc1.2.4.L120.B0",
            bitrate = 4_000_000,
            overrides = 0,
            viewportWidth = 3840,
            viewportHeight = 2160,
        )

        // Auto moves up inside the same track group: no tracks event follows.
        log.inputFormatChanged(width = 3840, height = 1608, codecs = "hvc1.2.4.L153.B0", bitrate = 16_000_000)

        assertEquals(2, lines.size)
        assertEquals("video rung playing: 3840x1608 hvc1.2.4.L153.B0 bitrate=16000000", lines.last())
    }

    @Test
    fun theSelectionLineKeepsItsFields() {
        log.tracksChanged(
            width = 1920,
            height = 804,
            codecs = "hvc1.2.4.L120.B0",
            bitrate = 4_000_000,
            overrides = 1,
            viewportWidth = 3840,
            viewportHeight = 2160,
        )

        assertEquals(
            listOf("video rung: 1920x804 hvc1.2.4.L120.B0 bitrate=4000000 overrides=1 viewport=3840x2160"),
            lines,
        )
    }

    @Test
    fun aSelectionWithNoVideoTrackStillSaysSo() {
        log.tracksChanged(
            width = null,
            height = null,
            codecs = null,
            bitrate = null,
            overrides = 0,
            viewportWidth = 1920,
            viewportHeight = 1080,
        )

        assertEquals(listOf("video rung: nullxnull null bitrate=null overrides=0 viewport=1920x1080"), lines)
    }
}
