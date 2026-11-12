// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import com.sun.jna.Pointer
import com.sun.jna.platform.win32.COM.Unknown
import com.sun.jna.ptr.PointerByReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class WasapiLoopbackCaptureTest {

    @Test
    fun failedInitializeReleasesEveryHandleAndBalancesComOnEachRetry() {
        val com = FakeCom(Failure.INITIALIZE)
        val capture = WasapiLoopbackCapture(com)

        repeat(2) { assertFalse(capture.start(48_000, 2) { _, _ -> }) }

        assertEquals(listOf(2L, 1L, 3L, 2L, 1L, 3L), com.released)
        assertEquals(2, com.uninitializations)
    }

    @Test
    fun failedStartReleasesCaptureServiceAndAudioClient() {
        val com = FakeCom(Failure.START)

        assertFalse(WasapiLoopbackCapture(com).start(48_000, 2) { _, _ -> })

        assertEquals(listOf(2L, 1L, 4L, 3L), com.released)
        assertEquals(1, com.uninitializations)
    }

    @Test
    fun missingRenderDeviceReleasesEnumeratorAndBalancesCom() {
        val com = FakeCom(Failure.DEVICE)

        assertFalse(WasapiLoopbackCapture(com).start(48_000, 2) { _, _ -> })

        assertEquals(listOf(1L), com.released)
        assertEquals(1, com.uninitializations)
    }

    @Test
    fun failedComInitializationDoesNotUninitialize() {
        val com = FakeCom(Failure.COM_INITIALIZATION)

        assertFalse(WasapiLoopbackCapture(com).start(48_000, 2) { _, _ -> })

        assertEquals(emptyList(), com.released)
        assertEquals(0, com.uninitializations)
    }

    private enum class Failure { COM_INITIALIZATION, DEVICE, INITIALIZE, START }

    private class FakeCom(private val failure: Failure) : WasapiCom {
        val released = mutableListOf<Long>()
        var uninitializations = 0

        override fun initialize(): Boolean = failure != Failure.COM_INITIALIZATION

        override fun uninitialize() { uninitializations++ }

        override fun createDeviceEnumerator(): Unknown = Unknown(Pointer.createConstant(1))

        override fun release(target: Unknown) {
            released += Pointer.nativeValue(target.pointer)
        }

        override fun invoke(target: Unknown, vtableIndex: Int, vararg args: Any?): Int =
            when (Pointer.nativeValue(target.pointer)) {
                1L -> openDevice(args.last())
                2L -> returnHandle(args.last(), 3)
                3L -> invokeAudioClient(vtableIndex, args.lastOrNull())
                else -> error("unexpected COM handle or call: ${target.pointer}, slot $vtableIndex")
            }

        private fun openDevice(output: Any?): Int {
            if (failure == Failure.DEVICE) return -1
            return returnHandle(output, 2)
        }

        private fun invokeAudioClient(vtableIndex: Int, output: Any?): Int = when (vtableIndex) {
            3 -> if (failure == Failure.INITIALIZE) -1 else 0
            14 -> returnHandle(output, 4)
            10 -> -1 // IAudioClient.Start fails after the capture service is acquired.
            else -> error("unexpected IAudioClient slot $vtableIndex")
        }

        private fun returnHandle(output: Any?, handle: Long): Int {
            (output as PointerByReference).value = Pointer.createConstant(handle)
            return 0
        }
    }
}
