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
import kotlinx.coroutines.runBlocking

class MpvHeaderFieldsTest {

    @Test
    fun aHeaderValueWithACommaReachesMpvAsOneLengthQuotedEntry() {
        val fake = FakeLibMpv()
        val backend = MpvVideoBackend(fake.library)

        try {
            runBlocking {
                backend.load(
                    "http://192.0.2.1/a.m3u8",
                    LoadOptions(headers = linkedMapOf("Accept" to "video/mp4, video/webm", "X-A" to "1")),
                )
            }
        } finally {
            backend.release()
        }

        assertEquals(
            listOf("%29%Accept: video/mp4, video/webm,%6%X-A: 1"),
            fake.valuesOf("http-header-fields"),
        )
    }
}
