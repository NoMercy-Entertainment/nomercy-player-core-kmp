// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/**
 * The half of [SourceOutage] that needs a real `Uri` — building the
 * [DataSpec] a Media3 response exception carries calls `Uri.parse`, which a
 * host JVM stubs to throw. Reading the status back out of the cause chain is
 * what decides whether cloudflared's 530 earns the full reconnect ladder, so it
 * is checked against the real exception rather than a hand-made stand-in.
 */
@RunWith(AndroidJUnit4::class)
class SourceOutageDeviceTest {

    @Test
    fun theStatusIsReadOutOfTheCauseChain() {
        val response = HttpDataSource.InvalidResponseCodeException(
            530,
            "origin down",
            null,
            emptyMap(),
            DataSpec.Builder().setUri("https://example.test/segment.ts").build(),
            ByteArray(0),
        )

        assertEquals(530, SourceOutage.httpStatusOf(response))
        assertEquals(530, SourceOutage.httpStatusOf(IllegalStateException("wrapped", response)))
    }
}
