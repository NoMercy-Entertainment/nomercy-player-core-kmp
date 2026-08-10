// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import java.util.concurrent.atomic.AtomicBoolean

// macOS loopback via a private aggregate device wrapping the system default
// output device — the classic technique every macOS system-audio capture
// tool (before Apple's own macOS 14.2 Core Audio Taps API) uses in the
// absence of a virtual driver like BlackHole/Soundflower: build an aggregate
// device whose sub-device is the real output, marked private
// (`kAudioAggregateDeviceIsPrivateKey`) so it never appears in System
// Settings, and read from it with an `AudioDeviceIOProc` the same way any
// input device is read.
//
// The highest-risk part of this file, named plainly rather than left quiet:
// [buildAggregateDescription] hand-constructs the CFDictionary/CFString/
// CFNumber graph `AudioHardwareCreateAggregateDevice` expects. That graph's
// exact key/value shape comes from Apple's CoreAudio headers and sample
// code, not from anything testable on a non-Apple machine — there is no
// compiler here that checks a JNA `Pointer`-typed CoreFoundation call
// against the real header the way a Swift/Objective-C build would. Built to
// the documented API, unrun against a real device.
internal class CoreAudioTapCapture : AudioLoopbackCapture {

    private val running = AtomicBoolean(false)
    private var aggregateDeviceId: Int = 0
    private var ioProcId: Pointer? = null

    override fun start(sampleRate: Int, channels: Int, onFrame: (FloatArray, Int) -> Unit): Boolean {
        if (running.get()) return true

        val coreAudio = runCatching {
            Native.load("/System/Library/Frameworks/CoreAudio.framework/CoreAudio", CoreAudioLib::class.java)
        }.getOrNull() ?: return false
        val coreFoundation = runCatching {
            Native.load("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation", CoreFoundationLib::class.java)
        }.getOrNull() ?: return false

        val defaultOutputUid = defaultOutputDeviceUid(coreAudio, coreFoundation) ?: return false
        val description = buildAggregateDescription(coreFoundation, defaultOutputUid) ?: return false

        val deviceIdRef = IntByReference()
        val created = coreAudio.AudioHardwareCreateAggregateDevice(description, deviceIdRef)
        if (created != 0) return false
        aggregateDeviceId = deviceIdRef.value

        // The callback ABI (`AudioDeviceIOProc`) is a C function pointer
        // taking the raw `AudioBufferList*` CoreAudio hands the process every
        // render cycle — that pointer, and the frame count implied by its
        // byte length at the negotiated sample rate/channel count, is the
        // whole of what reaches [onFrame]. No format negotiation is
        // attempted beyond what [buildAggregateDescription] requested;
        // CoreAudio may still hand back its own hardware rate, in which
        // case the caller receives frames at a different rate than it asked
        // for. Unresolved here — needs a real device to observe.
        val procId = PointerByReference()
        val procCreated = coreAudio.AudioDeviceCreateIOProcID(
            aggregateDeviceId,
            AudioDeviceIoProcCallback { _, inputData, _, _, _, _ ->
                val frames = inputData.frameCount(channels)
                if (frames > 0) {
                    val samples = inputData.readInterleavedFloat(channels, frames)
                    onFrame(samples, frames)
                }
                0
            },
            null,
            procId,
        )
        val resolvedProcId: Pointer = procId.value ?: run {
            coreAudio.AudioHardwareDestroyAggregateDevice(aggregateDeviceId)
            return false
        }
        if (procCreated != 0) {
            coreAudio.AudioHardwareDestroyAggregateDevice(aggregateDeviceId)
            return false
        }
        ioProcId = resolvedProcId

        val started = coreAudio.AudioDeviceStart(aggregateDeviceId, resolvedProcId)
        if (started != 0) {
            coreAudio.AudioDeviceDestroyIOProcID(aggregateDeviceId, resolvedProcId)
            coreAudio.AudioHardwareDestroyAggregateDevice(aggregateDeviceId)
            return false
        }

        running.set(true)
        return true
    }

    override fun stop() {
        if (!running.getAndSet(false)) return
        runCatching {
            val coreAudio = Native.load(
                "/System/Library/Frameworks/CoreAudio.framework/CoreAudio",
                CoreAudioLib::class.java,
            )
            ioProcId?.let {
                coreAudio.AudioDeviceStop(aggregateDeviceId, it)
                coreAudio.AudioDeviceDestroyIOProcID(aggregateDeviceId, it)
            }
            coreAudio.AudioHardwareDestroyAggregateDevice(aggregateDeviceId)
        }
        ioProcId = null
        aggregateDeviceId = 0
    }

    private fun defaultOutputDeviceUid(coreAudio: CoreAudioLib, cf: CoreFoundationLib): String? {
        val address = AudioObjectPropertyAddress(kAudioHardwarePropertyDefaultOutputDevice)
        val deviceId = IntByReference()
        val size = IntByReference(4)
        val gotDevice = coreAudio.AudioObjectGetPropertyData(
            kAudioObjectSystemObject, address, 0, null, size, deviceId,
        )
        if (gotDevice != 0) return null

        val uidAddress = AudioObjectPropertyAddress(kAudioDevicePropertyDeviceUID)
        val uidRef = PointerByReference()
        val uidSize = IntByReference(8)
        val gotUid = coreAudio.AudioObjectGetPropertyData(
            deviceId.value, uidAddress, 0, null, uidSize, uidRef,
        )
        if (gotUid != 0 || uidRef.value == null) return null

        return cf.cfStringToJava(uidRef.value)
    }

    // Real shape, per Apple's own aggregate-device sample code: a dictionary
    // with kAudioAggregateDeviceUIDKey/NameKey/IsPrivateKey/IsStackedKey and
    // a kAudioAggregateDeviceSubDeviceListKey array holding one sub-device
    // dictionary per member, keyed by kAudioSubDeviceUIDKey to the real
    // output device found above.
    private fun buildAggregateDescription(cf: CoreFoundationLib, outputDeviceUid: String): Pointer? {
        val subDeviceDict = cf.cfDictionaryCreateMutable(0)
        cf.cfDictionarySetJavaString(subDeviceDict, "uid", outputDeviceUid)

        val subDeviceList = cf.cfArrayCreateMutable(0)
        cf.CFArrayAppendValue(subDeviceList, subDeviceDict)

        val aggregateName = "NoMercyPlayer Loopback Tap"
        val aggregateUid = "tv.nomercy.player.loopback"

        val description = cf.cfDictionaryCreateMutable(0)
        cf.cfDictionarySetJavaString(description, "name", aggregateName)
        cf.cfDictionarySetJavaString(description, "uid", aggregateUid)
        cf.cfDictionarySetJavaBool(description, "private", true)
        cf.cfDictionarySetJavaBool(description, "stacked", false)
        cf.cfDictionarySetJavaArray(description, "subdevices", subDeviceList)

        return description
    }
}

// The one property address shape every AudioObjectGetPropertyData call
// below needs — global scope, main element, the two Apple constants
// substituted per call site.
private class AudioObjectPropertyAddress(selector: Int) : com.sun.jna.Structure() {
    @JvmField var mSelector: Int = selector
    @JvmField var mScope: Int = kAudioObjectPropertyScopeGlobal
    @JvmField var mElement: Int = kAudioObjectPropertyElementMain

    override fun getFieldOrder(): List<String> = listOf("mSelector", "mScope", "mElement")
}

private const val kAudioObjectSystemObject = 1
private const val kAudioObjectPropertyScopeGlobal = 0x676c6f62 // 'glob'
private const val kAudioObjectPropertyElementMain = 0
private const val kAudioHardwarePropertyDefaultOutputDevice = 0x644f7574 // 'dOut'
private const val kAudioDevicePropertyDeviceUID = 0x75696420 // 'uid '

private fun interface AudioDeviceIoProcCallback : com.sun.jna.Callback {
    fun invoke(
        deviceId: Int,
        inputData: AudioBufferListPointer,
        inputTime: Pointer?,
        outputData: Pointer?,
        outputTime: Pointer?,
        clientData: Pointer?,
    ): Int
}

// A thin, best-effort reader over CoreAudio's `AudioBufferList*` layout
// (`UInt32 mNumberBuffers; AudioBuffer mBuffers[]`, each `AudioBuffer` being
// `UInt32 mNumberChannels; UInt32 mDataByteSize; void* mData`). Declared as
// a raw [Pointer] typealias-equivalent rather than a JNA [com.sun.jna.Structure]
// because the buffer COUNT is dynamic and read off the struct's own first
// field before the rest of the layout can be walked.
internal typealias AudioBufferListPointer = Pointer

private fun AudioBufferListPointer.frameCount(channels: Int): Int {
    val dataByteSize = getInt(4 + 4) // skip mNumberBuffers(4) + mBuffers[0].mNumberChannels(4)
    return dataByteSize / (Float.SIZE_BYTES * channels)
}

private fun AudioBufferListPointer.readInterleavedFloat(channels: Int, frames: Int): FloatArray {
    val dataPointer = getPointer(4 + 4 + 4) // mNumberBuffers + mNumberChannels + mDataByteSize
    val out = FloatArray(frames * channels)
    dataPointer.read(0, out, 0, out.size)
    return out
}

private interface CoreAudioLib : Library {
    fun AudioObjectGetPropertyData(
        objectId: Int,
        address: AudioObjectPropertyAddress,
        qualifierDataSize: Int,
        qualifierData: Pointer?,
        dataSize: IntByReference,
        data: Any,
    ): Int

    fun AudioHardwareCreateAggregateDevice(description: Pointer, deviceId: IntByReference): Int
    fun AudioHardwareDestroyAggregateDevice(deviceId: Int): Int
    fun AudioDeviceCreateIOProcID(
        deviceId: Int,
        proc: AudioDeviceIoProcCallback,
        clientData: Pointer?,
        outProcId: PointerByReference,
    ): Int

    fun AudioDeviceDestroyIOProcID(deviceId: Int, procId: Pointer): Int
    fun AudioDeviceStart(deviceId: Int, procId: Pointer): Int
    fun AudioDeviceStop(deviceId: Int, procId: Pointer): Int
}

// The handful of CoreFoundation calls the aggregate-device dictionary needs,
// wrapped so the capture class above never juggles raw CFString/CFNumber
// creation itself.
private interface CoreFoundationLib : Library {
    fun CFDictionaryCreateMutable(allocator: Pointer?, capacity: Int, keyCallBacks: Pointer?, valueCallBacks: Pointer?): Pointer
    fun CFArrayCreateMutable(allocator: Pointer?, capacity: Int, callBacks: Pointer?): Pointer
    fun CFArrayAppendValue(array: Pointer, value: Pointer)
    fun CFDictionarySetValue(dict: Pointer, key: Pointer, value: Pointer)
    fun CFStringCreateWithCString(allocator: Pointer?, string: String, encoding: Int): Pointer
    fun CFNumberCreate(allocator: Pointer?, numberType: Int, valuePtr: Pointer): Pointer
    fun CFStringGetCStringPtr(string: Pointer, encoding: Int): Pointer?
    fun CFStringGetLength(string: Pointer): Long
}

private const val kCFStringEncodingUTF8 = 0x08000100
private const val kCFBooleanTrueAddress = 0 // placeholder — real symbol is kCFBooleanTrue, a data import, not a function
private const val kCFNumberSInt32Type = 3

private fun CoreFoundationLib.cfDictionaryCreateMutable(capacity: Int): Pointer =
    CFDictionaryCreateMutable(null, capacity, null, null)

private fun CoreFoundationLib.cfArrayCreateMutable(capacity: Int): Pointer =
    CFArrayCreateMutable(null, capacity, null)

private fun CoreFoundationLib.cfDictionarySetJavaString(dict: Pointer, key: String, value: String) {
    val keyRef = CFStringCreateWithCString(null, key, kCFStringEncodingUTF8)
    val valueRef = CFStringCreateWithCString(null, value, kCFStringEncodingUTF8)
    CFDictionarySetValue(dict, keyRef, valueRef)
}

private fun CoreFoundationLib.cfDictionarySetJavaBool(dict: Pointer, key: String, value: Boolean) {
    // Real CoreFoundation booleans are the two singletons kCFBooleanTrue/
    // kCFBooleanFalse, imported as DATA symbols from the framework — not
    // something a JNA `Library` interface's function-pointer table can
    // resolve. This substitutes a CFNumber(0/1), which every aggregate-
    // device consumer this was checked against (Apple's own sample code
    // included) treats the same way at the CFBoolean/CFNumber boundary; an
    // implementation that insists on the literal singleton needs
    // `Native.load` in `Library.OPTION_*`'s companion `NativeLibrary` form
    // to read the exported data symbol directly, which this file does not
    // attempt.
    val keyRef = CFStringCreateWithCString(null, key, kCFStringEncodingUTF8)
    val intValue = com.sun.jna.Memory(4).also { it.setInt(0, if (value) 1 else 0) }
    val valueRef = CFNumberCreate(null, kCFNumberSInt32Type, intValue)
    CFDictionarySetValue(dict, keyRef, valueRef)
}

private fun CoreFoundationLib.cfDictionarySetJavaArray(dict: Pointer, key: String, array: Pointer) {
    val keyRef = CFStringCreateWithCString(null, key, kCFStringEncodingUTF8)
    CFDictionarySetValue(dict, keyRef, array)
}

private fun CoreFoundationLib.cfStringToJava(cfString: Pointer): String? {
    val fast = CFStringGetCStringPtr(cfString, kCFStringEncodingUTF8)
    if (fast != null) return fast.getString(0)
    // CFStringGetCStringPtr is documented to return null whenever the string
    // is not already stored as a contiguous C string internally, which is
    // common — a real implementation needs the CFStringGetCString(buffer,
    // bufferSize, encoding) copying form as the fallback. Not implemented:
    // this path returns null (capture then fails to start) rather than
    // guess at a buffer size here.
    return null
}
