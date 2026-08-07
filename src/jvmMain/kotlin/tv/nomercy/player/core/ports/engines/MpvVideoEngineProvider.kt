// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports.engines

import tv.nomercy.player.core.natives.NativeRuntimeKind
import tv.nomercy.player.core.natives.NativeRuntimes
import tv.nomercy.player.core.natives.libmpv.LibMpv
import tv.nomercy.player.core.natives.libmpv.MpvHandle
import tv.nomercy.player.core.ports.MpvVideoBackend
import tv.nomercy.player.core.ports.VideoBackend

/**
 * libmpv, the engine replacing libVLC on the desktop.
 *
 * Registered whether or not a payload exists for this host. An engine that
 * disappears from the registry when it cannot run is an engine nobody can ask a
 * question about, and "why can I not select mpv" is the question this whole
 * migration is going to be asked.
 *
 * The probe creates and destroys a real handle rather than only loading the
 * library. Loading proves the file is on the path; initialising proves it is a
 * libmpv that this build can drive, which is the failure a mismatched or
 * stripped payload actually produces.
 */
public object MpvVideoEngineProvider : VideoEngineProvider {

    public override val id: String = "mpv"

    public override fun isAvailable(): Boolean = whyUnavailable() == null

    public override fun whyUnavailable(): String? = probe

    private val probe: String? by lazy { probeOnce() }

    // LinkageError as well as RuntimeException, for the same reason the libVLC
    // probe takes both: a missing native library surfaces as
    // UnsatisfiedLinkError, NoClassDefFoundError or ExceptionInInitializerError
    // depending on where the load failed, and naming the shapes one at a time
    // means missing the one that reaches a user.
    @Suppress("TooGenericExceptionCaught")
    private fun probeOnce(): String? = try {
        val mpv: LibMpv = LibMpv.load()
        val handle: MpvHandle = mpv.mpv_create() ?: error("mpv_create returned null")
        val started: Int = mpv.mpv_initialize(handle)
        mpv.mpv_terminate_destroy(handle)

        if (started < 0) "libmpv would not initialise: ${mpv.mpv_error_string(started)}. ${payloadState()}" else null
    } catch (missing: LinkageError) {
        "${LibMpv.SONAME} could not be loaded: ${missing.message}. ${payloadState()}"
    } catch (refused: RuntimeException) {
        "libmpv could not be started: ${refused.message}. ${payloadState()}"
    }

    // What happened to the copy that was supposed to arrive with the library.
    // Without it the message sends a developer looking for an installer, and the
    // answer is nearly always that no payload is published for their platform
    // yet.
    private fun payloadState(): String =
        NativeRuntimes.whyUnavailable(NativeRuntimeKind.LIB_MPV)
            ?.let { why -> "The bundled runtime was not used: $why" }
            ?: "The bundled runtime is present, so this is a load failure rather than a missing library."

    public override fun create(): VideoBackend = MpvVideoBackend()
}
