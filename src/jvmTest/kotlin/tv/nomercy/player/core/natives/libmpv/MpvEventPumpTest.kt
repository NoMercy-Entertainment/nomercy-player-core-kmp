// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.natives.libmpv

import com.sun.jna.Memory
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MpvEventPumpTest {

    private fun endFileEvent(reason: Int, error: Int): MpvEvent.ByReference {
        val payload = MpvEndFile.ByReference(Memory(64))
        payload.reason = reason
        payload.error = error
        payload.write()
        return MpvEvent.ByReference().apply {
            eventId = MpvEventId.END_FILE
            data = payload.pointer
        }
    }

    private fun fakeMpv(steps: List<() -> MpvEvent.ByReference?>): LibMpv {
        var next = 0
        return Proxy.newProxyInstance(
            LibMpv::class.java.classLoader,
            arrayOf(LibMpv::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "mpv_wait_event" -> steps.getOrNull(next++)?.invoke()
                    ?: MpvEvent.ByReference().apply { eventId = MpvEventId.SHUTDOWN }
                else -> null
            }
        } as LibMpv
    }

    @Test
    fun aFailingReadDoesNotStopLaterEndFileEvents() {
        val seen = CountDownLatch(1)
        var reported = -1
        val mpv = fakeMpv(
            listOf(
                { throw IllegalStateException("listener failed") },
                { endFileEvent(MpvEndFileReason.ERROR, -13) },
            ),
        )
        val pump = MpvEventPump(mpv, MpvHandle()) { reason, _ ->
            reported = reason
            seen.countDown()
        }

        pump.start()
        val announced = seen.await(5, TimeUnit.SECONDS)
        pump.stop()

        assertTrue(announced, "the END_FILE after the failing read was never announced")
        assertEquals(MpvEndFileReason.ERROR, reported)
    }
}
