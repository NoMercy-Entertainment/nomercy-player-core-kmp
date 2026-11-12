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
internal class WasapiLoopbackCapture(
    private val comApi: WasapiCom = NativeWasapiCom,
) : AudioLoopbackCapture {

    private val running = AtomicBoolean(false)
    private var captureThread: Thread? = null
    private var audioClient: Unknown? = null
    private var captureClient: Unknown? = null
    private var comInitialized = false

    override fun start(sampleRate: Int, channels: Int, onFrame: (FloatArray, Int) -> Unit): Boolean {
        if (running.get()) return true

        val capture = openCaptureClient(sampleRate, channels) ?: return cleanupAndFail()

        running.set(true)
        captureThread = Thread({ pumpLoop(capture, channels, onFrame) }, "nomercy-wasapi-loopback").apply {
            isDaemon = true
            start()
        }
        return true
    }

    // Each step yields the next COM handle or leaves the chain; the caller
    // turns a null into the cleanup, so acquisition reads as a sequence rather
    // than as eight separate exits.
    private fun openCaptureClient(sampleRate: Int, channels: Int): Unknown? {
        comInitialized = comApi.initialize()
        if (!comInitialized) return null

        val enumerator = comApi.createDeviceEnumerator() ?: return null
        return try {
            openFromEnumerator(enumerator, sampleRate, channels)
        } finally {
            comApi.release(enumerator)
        }
    }

    private fun openFromEnumerator(enumerator: Unknown, sampleRate: Int, channels: Int): Unknown? {
        val device = getDefaultRenderDevice(enumerator) ?: return null
        return try {
            val client = activateAudioClient(device) ?: return null
            audioClient = client
            startCapture(client, sampleRate, channels)
        } finally {
            comApi.release(device)
        }
    }

    private fun startCapture(client: Unknown, sampleRate: Int, channels: Int): Unknown? {
        if (!COMUtils.SUCCEEDED(audioClientInitialize(client, waveFormat(sampleRate, channels)))) return null

        val capture = getCaptureClientService(client) ?: return null
        captureClient = capture

        return if (COMUtils.SUCCEEDED(audioClientStart(client))) capture else null
    }

    private fun waveFormat(sampleRate: Int, channels: Int): WaveFormatEx = WaveFormatEx().apply {
        wFormatTag = WORD(WAVE_FORMAT_IEEE_FLOAT.toLong())
        nChannels = WORD(channels.toLong())
        nSamplesPerSec = DWORD(sampleRate.toLong())
        wBitsPerSample = WORD(BITS_PER_FLOAT_SAMPLE.toLong())
        nBlockAlign = WORD((channels * BYTES_PER_FLOAT_SAMPLE).toLong())
        nAvgBytesPerSec = DWORD((sampleRate * channels * BYTES_PER_FLOAT_SAMPLE).toLong())
        cbSize = WORD(0L)
    }

    override fun stop() {
        if (!running.getAndSet(false)) return
        captureThread?.join(THREAD_JOIN_TIMEOUT_MS)
        captureThread = null
        audioClient?.let { invoke(it, VTBL_AUDIOCLIENT_STOP) }
        releaseClientsAndUninitialize()
    }

    private fun releaseClientsAndUninitialize() {
        captureClient?.let(comApi::release)
        audioClient?.let(comApi::release)
        captureClient = null
        audioClient = null
        if (comInitialized) comApi.uninitialize()
        comInitialized = false
    }

    private fun cleanupAndFail(): Boolean {
        releaseClientsAndUninitialize()
        return false
    }

    // Polling `GetNextPacketSize` rather than the event-driven form
    // (`SetEventHandle` + `WaitForSingleObject`) — one fewer Win32 handle to
    // manage, at the cost of a fixed poll interval rather than a wake the
    // instant a buffer is ready. A spectrum tap has no latency budget tight
    // enough for that trade to matter.
    private fun pumpLoop(capture: Unknown, channels: Int, onFrame: (FloatArray, Int) -> Unit) {
        while (running.get()) {
            if (!drainOnePacket(capture, channels, onFrame)) Thread.sleep(POLL_INTERVAL_MS)
        }
    }

    // True when a packet was taken from the endpoint, false when there was
    // nothing there or the call failed — the caller waits out a poll interval
    // on false, which is what the loop's two `continue`s used to say.
    private fun drainOnePacket(capture: Unknown, channels: Int, onFrame: (FloatArray, Int) -> Unit): Boolean {
        val packetFrames = IntByReference()
        val sizeResult = invoke(capture, VTBL_CAPTURECLIENT_GET_NEXT_PACKET_SIZE, packetFrames)
        if (!COMUtils.SUCCEEDED(sizeResult) || packetFrames.value == 0) return false

        val dataPointer = PointerByReference()
        val framesToRead = IntByReference()
        val flags = IntByReference()
        val getResult = invoke(
            capture, VTBL_CAPTURECLIENT_GET_BUFFER, dataPointer, framesToRead, flags,
            Pointer.NULL, Pointer.NULL,
        )
        if (!COMUtils.SUCCEEDED(getResult)) return false

        val frames = framesToRead.value
        // AUDCLNT_BUFFERFLAGS_SILENT (bit 1) — WASAPI hands back a valid
        // pointer with unspecified contents while the endpoint is silent, not
        // a null one, so this is the only way to tell the two apart.
        val silent = (flags.value and BUFFERFLAGS_SILENT) != 0
        if (frames > 0 && !silent) {
            val samples = FloatArray(frames * channels)
            dataPointer.value.read(0, samples, 0, samples.size)
            onFrame(samples, frames)
        }

        invoke(capture, VTBL_CAPTURECLIENT_RELEASE_BUFFER, frames)
        return true
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
        val hr = invoke(
            device,
            VTBL_DEVICE_ACTIVATE,
            iid,
            com.sun.jna.platform.win32.WTypes.CLSCTX_ALL,
            Pointer.NULL,
            result,
        )
        if (!COMUtils.SUCCEEDED(hr) || result.value == null) return null
        return Unknown(result.value)
    }

    private fun audioClientInitialize(client: Unknown, format: WaveFormatEx): Int {
        // Shared mode takes only the device mix format (often 48 kHz) unless the
        // stream auto-converts, so ask for that. Buffer and period 0 let WASAPI
        // pick its own shared-mode defaults.
        return invoke(
            client, VTBL_AUDIOCLIENT_INITIALIZE,
            0, LOOPBACK_STREAM_FLAGS, 0L, 0L, format, Pointer.NULL,
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
    private fun invoke(target: Unknown, vtableIndex: Int, vararg args: Any?): Int =
        comApi.invoke(target, vtableIndex, *args)

    private object NativeWasapiCom : WasapiCom {
        override fun initialize(): Boolean = COMUtils.SUCCEEDED(
            Ole32.INSTANCE.CoInitializeEx(Pointer.NULL, Ole32.COINIT_MULTITHREADED).toInt(),
        )

        override fun uninitialize() { Ole32.INSTANCE.CoUninitialize() }

        override fun createDeviceEnumerator(): Unknown? {
            val result = PointerByReference()
            val hr = Ole32.INSTANCE.CoCreateInstance(
                CLSID(CLSID_MMDEVICEENUMERATOR), null, com.sun.jna.platform.win32.WTypes.CLSCTX_ALL,
                IID(IID_IMMDEVICEENUMERATOR), result,
            )
            if (!COMUtils.SUCCEEDED(hr.toInt()) || result.value == null) return null
            return Unknown(result.value)
        }

        override fun release(target: Unknown) { target.Release() }

        override fun invoke(target: Unknown, vtableIndex: Int, vararg args: Any?): Int {
            val function = target.pointer.getPointer(0).getPointer((vtableIndex * Native.POINTER_SIZE).toLong())
            return com.sun.jna.Function.getFunction(function).invokeInt(arrayOf(target.pointer, *args))
        }
    }

    private companion object {
        const val WAVE_FORMAT_IEEE_FLOAT = 3

        // The capture format is IEEE float, so a sample is one 32-bit float —
        // both numbers below are that one fact, in the two units the
        // WAVEFORMATEX fields want it in.
        const val BITS_PER_FLOAT_SAMPLE = 32
        const val BYTES_PER_FLOAT_SAMPLE = 4

        // AUDCLNT_BUFFERFLAGS_SILENT.
        const val BUFFERFLAGS_SILENT = 0x2
        const val AUDCLNT_STREAMFLAGS_LOOPBACK = 0x00020000
        const val AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM = 0x80000000.toInt()
        const val AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY = 0x08000000
        const val LOOPBACK_STREAM_FLAGS = AUDCLNT_STREAMFLAGS_LOOPBACK or
            AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM or
            AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY
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

internal interface WasapiCom {
    fun initialize(): Boolean
    fun uninitialize()
    fun createDeviceEnumerator(): Unknown?
    fun release(target: Unknown)
    fun invoke(target: Unknown, vtableIndex: Int, vararg args: Any?): Int
}

// WAVEFORMATEX, byte-for-byte — the format WASAPI's Initialize takes a
// pointer to. Field order matches the Win32 struct exactly; JNA lays out a
// Structure's fields in the order [getFieldOrder] names, not declaration
// order, which is why that override exists below rather than relying on it
// matching source order by accident.
internal class WaveFormatEx : com.sun.jna.Structure() {
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
