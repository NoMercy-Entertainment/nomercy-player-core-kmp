// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.COM.COMUtils
import com.sun.jna.platform.win32.COM.Unknown
import com.sun.jna.platform.win32.Guid.CLSID
import com.sun.jna.platform.win32.Guid.IID
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.WinDef.DWORD
import com.sun.jna.platform.win32.WinDef.WORD
import com.sun.jna.platform.win32.WinNT.HRESULT
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import java.util.concurrent.atomic.AtomicBoolean

// Windows loopback via WASAPI's own dedicated mode for exactly this — a
// render-endpoint client opened with `AUDCLNT_STREAMFLAGS_LOOPBACK`, which
// Windows itself has provided since Vista rather than something owed to a
// virtual driver or a mixer trick. `IMMDeviceEnumerator` finds the default
// render device, `IAudioClient` opens it in loopback, `IAudioCaptureClient`
// pulls buffers.
//
// A hand-built COM binding over JNA's [Unknown] vtable-dispatch base, not a
// pre-packaged WASAPI wrapper — jna-platform ships COM's own plumbing
// (`Ole32`, `Guid`, `Unknown`) but no WASAPI-specific interfaces. The vtable
// indices and struct layouts below are Microsoft's own published COM ABI
// (stable since the interfaces shipped, IUnknown's three methods first on
// every one of them) rather than anything reverse-engineered — the risk
// this file carries is a transcription error in that layout, not an
// invented one.
internal class WasapiLoopbackCapture : AudioLoopbackCapture {

    private val running = AtomicBoolean(false)
    private var captureThread: Thread? = null
    private var audioClient: Unknown? = null
    private var captureClient: Unknown? = null
    private var comInitialized = false

    override fun start(sampleRate: Int, channels: Int, onFrame: (FloatArray, Int) -> Unit): Boolean {
        if (running.get()) return true

        val initHr = Ole32.INSTANCE.CoInitializeEx(Pointer.NULL, Ole32.COINIT_MULTITHREADED)
        comInitialized = COMUtils.SUCCEEDED(initHr.toInt())
        if (!comInitialized) return false

        val enumerator = createDeviceEnumerator() ?: return cleanupAndFail()
        val device = getDefaultRenderDevice(enumerator) ?: return cleanupAndFail()
        val client = activateAudioClient(device) ?: return cleanupAndFail()

        val format = WaveFormatEx().apply {
            wFormatTag = WORD(WAVE_FORMAT_IEEE_FLOAT.toLong())
            nChannels = WORD(channels.toLong())
            nSamplesPerSec = DWORD(sampleRate.toLong())
            wBitsPerSample = WORD(32L)
            nBlockAlign = WORD((channels * 4).toLong())
            nAvgBytesPerSec = DWORD((sampleRate * channels * 4).toLong())
            cbSize = WORD(0L)
        }

        val initResult = audioClientInitialize(client, format)
        if (!COMUtils.SUCCEEDED(initResult)) return cleanupAndFail()
        audioClient = client

        val capture = getCaptureClientService(client) ?: return cleanupAndFail()
        captureClient = capture

        val startResult = audioClientStart(client)
        if (!COMUtils.SUCCEEDED(startResult)) return cleanupAndFail()

        running.set(true)
        captureThread = Thread({ pumpLoop(capture, channels, onFrame) }, "nomercy-wasapi-loopback").apply {
            isDaemon = true
            start()
        }
        return true
    }

    override fun stop() {
        if (!running.getAndSet(false)) return
        captureThread?.join(THREAD_JOIN_TIMEOUT_MS)
        captureThread = null
        audioClient?.let { invoke(it, VTBL_AUDIOCLIENT_STOP) }
        audioClient?.Release()
        captureClient?.Release()
        audioClient = null
        captureClient = null
        if (comInitialized) Ole32.INSTANCE.CoUninitialize()
        comInitialized = false
    }

    private fun cleanupAndFail(): Boolean {
        stop()
        return false
    }

    // Polling `GetNextPacketSize` rather than the event-driven form
    // (`SetEventHandle` + `WaitForSingleObject`) — one fewer Win32 handle to
    // manage, at the cost of a fixed poll interval rather than a wake the
    // instant a buffer is ready. A spectrum tap has no latency budget tight
    // enough for that trade to matter.
    private fun pumpLoop(capture: Unknown, channels: Int, onFrame: (FloatArray, Int) -> Unit) {
        while (running.get()) {
            val packetFrames = IntByReference()
            val sizeResult = invoke(capture, VTBL_CAPTURECLIENT_GET_NEXT_PACKET_SIZE, packetFrames)
            if (!COMUtils.SUCCEEDED(sizeResult) || packetFrames.value == 0) {
                Thread.sleep(POLL_INTERVAL_MS)
                continue
            }

            val dataPointer = PointerByReference()
            val framesToRead = IntByReference()
            val flags = IntByReference()
            val getResult = invoke(
                capture, VTBL_CAPTURECLIENT_GET_BUFFER, dataPointer, framesToRead, flags,
                Pointer.NULL, Pointer.NULL,
            )
            if (!COMUtils.SUCCEEDED(getResult)) {
                Thread.sleep(POLL_INTERVAL_MS)
                continue
            }

            val frames = framesToRead.value
            // AUDCLNT_BUFFERFLAGS_SILENT (bit 1) — WASAPI hands back a valid
            // pointer with unspecified contents while the endpoint is
            // silent, not a null one, so this is the only way to tell the
            // two apart.
            val silent = (flags.value and 0x2) != 0
            if (frames > 0 && !silent) {
                val samples = FloatArray(frames * channels)
                dataPointer.value.read(0, samples, 0, samples.size)
                onFrame(samples, frames)
            }

            invoke(capture, VTBL_CAPTURECLIENT_RELEASE_BUFFER, frames)
        }
    }

    private fun createDeviceEnumerator(): Unknown? {
        val clsid = CLSID(CLSID_MMDEVICEENUMERATOR)
        val iid = IID(IID_IMMDEVICEENUMERATOR)
        val result = PointerByReference()
        val hr = Ole32.INSTANCE.CoCreateInstance(
            clsid, null, com.sun.jna.platform.win32.WTypes.CLSCTX_ALL, iid, result,
        )
        if (!COMUtils.SUCCEEDED(hr.toInt()) || result.value == null) return null
        return Unknown(result.value)
    }

    private fun getDefaultRenderDevice(enumerator: Unknown): Unknown? {
        val deviceRef = PointerByReference()
        // eRender = 0, eConsole = 0
        val hr = invoke(enumerator, VTBL_ENUMERATOR_GET_DEFAULT_ENDPOINT, 0, 0, deviceRef)
        if (!COMUtils.SUCCEEDED(hr) || deviceRef.value == null) return null
        return Unknown(deviceRef.value)
    }

    private fun activateAudioClient(device: Unknown): Unknown? {
        val iid = IID(IID_IAUDIOCLIENT)
        val result = PointerByReference()
        // CLSCTX_ALL, no activation params.
        val hr = invoke(device, VTBL_DEVICE_ACTIVATE, iid, com.sun.jna.platform.win32.WTypes.CLSCTX_ALL, Pointer.NULL, result)
        if (!COMUtils.SUCCEEDED(hr) || result.value == null) return null
        return Unknown(result.value)
    }

    private fun audioClientInitialize(client: Unknown, format: WaveFormatEx): Int {
        // AUDCLNT_SHAREMODE_SHARED = 0, AUDCLNT_STREAMFLAGS_LOOPBACK =
        // 0x00020000. Buffer/periodicity both 0 lets WASAPI pick its own
        // default shared-mode engine period rather than this asking for one
        // it has no measured basis for.
        return invoke(
            client, VTBL_AUDIOCLIENT_INITIALIZE,
            0, AUDCLNT_STREAMFLAGS_LOOPBACK, 0L, 0L, format, Pointer.NULL,
        )
    }

    private fun getCaptureClientService(client: Unknown): Unknown? {
        val iid = IID(IID_IAUDIOCAPTURECLIENT)
        val result = PointerByReference()
        val hr = invoke(client, VTBL_AUDIOCLIENT_GET_SERVICE, iid, result)
        if (!COMUtils.SUCCEEDED(hr) || result.value == null) return null
        return Unknown(result.value)
    }

    private fun audioClientStart(client: Unknown): Int = invoke(client, VTBL_AUDIOCLIENT_START)

    // JNA's [Unknown] exposes `invokeNativeObject`, dispatching through the
    // COM vtable at the given zero-based slot (IUnknown's QueryInterface/
    // AddRef/Release occupy 0-2, so every index below is that method's
    // documented position minus those three).
    private fun invoke(target: Unknown, vtableIndex: Int, vararg args: Any?): Int {
        val result = target.pointer.getPointer(0).getPointer((vtableIndex * Native.POINTER_SIZE).toLong())
        @Suppress("UNCHECKED_CAST")
        return com.sun.jna.Function.getFunction(result).invokeInt(arrayOf(target.pointer, *args))
    }

    private companion object {
        const val WAVE_FORMAT_IEEE_FLOAT = 3
        const val AUDCLNT_STREAMFLAGS_LOOPBACK = 0x00020000
        const val THREAD_JOIN_TIMEOUT_MS = 1_000L
        const val POLL_INTERVAL_MS = 10L

        const val CLSID_MMDEVICEENUMERATOR = "{BCDE0395-E52F-467C-8E3D-C4579291692E}"
        const val IID_IMMDEVICEENUMERATOR = "{A95664D2-9614-4F35-A746-DE8DB63617E6}"
        const val IID_IAUDIOCLIENT = "{1CB9AD4C-DBFA-4C32-B178-C2F568A703B2}"
        const val IID_IAUDIOCAPTURECLIENT = "{C8ADBD64-E71E-48A0-A4DE-185C395CD317}"

        // Vtable slot AFTER IUnknown's QueryInterface(0)/AddRef(1)/Release(2).
        const val VTBL_ENUMERATOR_GET_DEFAULT_ENDPOINT = 4
        const val VTBL_DEVICE_ACTIVATE = 3
        const val VTBL_AUDIOCLIENT_INITIALIZE = 3
        const val VTBL_AUDIOCLIENT_START = 10
        const val VTBL_AUDIOCLIENT_STOP = 11
        const val VTBL_AUDIOCLIENT_GET_SERVICE = 14
        const val VTBL_CAPTURECLIENT_GET_BUFFER = 3
        const val VTBL_CAPTURECLIENT_RELEASE_BUFFER = 4
        const val VTBL_CAPTURECLIENT_GET_NEXT_PACKET_SIZE = 5
    }
}

// WAVEFORMATEX, byte-for-byte — the format WASAPI's Initialize takes a
// pointer to. Field order matches the Win32 struct exactly; JNA lays out a
// Structure's fields in the order [getFieldOrder] names, not declaration
// order, which is why that override exists below rather than relying on it
// matching source order by accident.
private class WaveFormatEx : com.sun.jna.Structure() {
    @JvmField var wFormatTag: WORD = WORD(0)
    @JvmField var nChannels: WORD = WORD(0)
    @JvmField var nSamplesPerSec: DWORD = DWORD(0)
    @JvmField var nAvgBytesPerSec: DWORD = DWORD(0)
    @JvmField var nBlockAlign: WORD = WORD(0)
    @JvmField var wBitsPerSample: WORD = WORD(0)
    @JvmField var cbSize: WORD = WORD(0)

    override fun getFieldOrder(): List<String> = listOf(
        "wFormatTag", "nChannels", "nSamplesPerSec", "nAvgBytesPerSec",
        "nBlockAlign", "wBitsPerSample", "cbSize",
    )
}
