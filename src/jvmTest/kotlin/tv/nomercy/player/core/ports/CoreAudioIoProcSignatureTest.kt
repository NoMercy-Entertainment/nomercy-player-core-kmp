// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import com.sun.jna.Memory
import com.sun.jna.Pointer
import kotlin.test.Test
import kotlin.test.assertEquals

// AUD-3042: CoreAudio's AudioDeviceIOProc takes seven arguments (inDevice,
// inNow, inInputData, inInputTime, outOutputData, inOutputTime, inClientData).
// The callback is only reachable on macOS, so this pins the declared shape.
class CoreAudioIoProcSignatureTest {

    private val invoke = AudioDeviceIoProcCallback::class.java.methods.single { it.name == "invoke" }

    @Test
    fun callbackDeclaresCoreAudiosSevenParameters() {
        assertEquals(7, invoke.parameterCount)
    }

    @Test
    fun inNowComesBeforeInputData() {
        val types = invoke.parameterTypes.toList()
        assertEquals(Int::class.javaPrimitiveType, types[0])
        assertEquals(Pointer::class.java, types[1])
        assertEquals(Pointer::class.java, types[2])
    }

    @Test
    fun inputIsReadFromTheThirdArgumentAndNotFromInNow() {
        var delivered = 0
        val callback = ioProcCallback(channels = 2) { _, _ -> delivered++ }
        // JNA does not zero fresh memory; stale bytes read as a huge buffer count.
        val timestamp = Memory(BUFFER_LIST_BYTES).apply { clear(); setInt(8, 4_096) }
        val emptyList = Memory(BUFFER_LIST_BYTES).apply { clear() }

        assertEquals(0, callback.invoke(1, timestamp, emptyList, null, null, null, null))
        assertEquals(0, delivered)
    }

    @Test
    fun aNullInputListIsIgnoredWithoutThrowing() {
        val callback = ioProcCallback(channels = 2) { _, _ -> error("no frames expected") }

        assertEquals(0, callback.invoke(1, null, null, null, null, null, null))
    }

    private companion object {
        const val BUFFER_LIST_BYTES = 32L
    }
}
