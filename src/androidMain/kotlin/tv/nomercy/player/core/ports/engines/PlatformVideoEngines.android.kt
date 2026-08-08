// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports.engines

import tv.nomercy.player.core.ports.ExoPlayerVideoBackend
import tv.nomercy.player.core.ports.PlatformEnvironment
import tv.nomercy.player.core.ports.VideoBackend

internal actual val platformVideoEngines: List<VideoEngineProvider> = listOf(ExoVideoEngineProvider)

/**
 * ExoPlayer, and the engine an Android device gets unless something asks for
 * another.
 *
 * It leads libmpv here for one reason and it is not maturity: it decodes in
 * hardware. Every ordinary file — and that is nearly all of them — costs a
 * fraction of the power through MediaCodec that it costs through ffmpeg, and a
 * player that drains a phone is a player nobody finishes a film on. libmpv is
 * behind it for the files this cannot open at all.
 */
public object ExoVideoEngineProvider : VideoEngineProvider {

    public override val id: String = "exoplayer"

    // Always. Media3 is a dependency of this library rather than something a
    // device may or may not have, so there is no probe to run — and a probe
    // that can only answer yes is a probe that hides the day it should have
    // answered no.
    public override fun isAvailable(): Boolean = true

    public override fun whyUnavailable(): String? = null

    public override fun create(): VideoBackend =
        ExoPlayerVideoBackend(PlatformEnvironment.requireContext().androidContext)
}
