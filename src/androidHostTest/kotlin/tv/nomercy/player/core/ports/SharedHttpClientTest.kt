// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Every OkHttpClient built from scratch creates its own SSL context, which cost
 * 428 ms and 333 ms on the main thread of an SM-A137F during one playback
 * start. The library builds every client from one shared base instead.
 */
class SharedHttpClientTest {
    @Test
    fun derivedClientsShareTheSslSetupAndConnectionPool() {
        val first = SharedHttpClient.derive { readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS) }
        val second = SharedHttpClient.derive { }

        assertSame(first.sslSocketFactory, second.sslSocketFactory)
        assertSame(first.connectionPool, second.connectionPool)
        assertEquals(TimeUnit.SECONDS.toMillis(READ_TIMEOUT_S).toInt(), first.readTimeoutMillis)
    }

    @Test
    fun noLibraryCodeBuildsAClientFromScratch() {
        val offenders = File("src/androidMain/kotlin").walkTopDown()
            .filter { it.extension == "kt" && it.name != "SharedHttpClient.kt" }
            .filter { file -> file.readText().contains("OkHttpClient.Builder()") }
            .map { it.name }
            .toList()
        assertEquals(emptyList<String>(), offenders)
    }

    private companion object {
        const val READ_TIMEOUT_S = 5L
    }
}
