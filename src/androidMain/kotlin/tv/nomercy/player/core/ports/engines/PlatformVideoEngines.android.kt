// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports.engines

import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10
import android.media.MediaCodecList
import tv.nomercy.player.core.ports.ExoPlayerVideoBackend
import tv.nomercy.player.core.ports.PlatformEnvironment
import tv.nomercy.player.core.ports.VideoBackend
import tv.nomercy.player.core.stream.AvcCodecString

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

    /**
     * Asked of the device, not assumed.
     *
     * A phone's decoders are a fixed list and every phone's list is different,
     * so the question "can this play here" has a real answer and MediaCodecList
     * holds it. Hardcoding "Android cannot do High 10" would be true of every
     * device measured so far and would still be the wrong shape — the day one
     * ships that can, this would route it to a software decoder forever.
     *
     * Only AVC is checked, because AVC is where the gap is: HEVC Main 10 and
     * VP9 Profile 2 are widely supported and both carry 10-bit fine.
     */
    public override fun canDecode(codec: String?): Boolean {
        val profile: Int = AvcCodecString.profileIdc(codec) ?: return true
        if (profile != AvcCodecString.HIGH_10) return true

        return decoderProfiles().any { level -> level.profile == AVCProfileHigh10 }
    }

    private fun decoderProfiles(): List<CodecProfileLevel> =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filterNot(MediaCodecInfo::isEncoder)
            .filter { info -> info.supportedTypes.any { type -> type.equals(AVC, ignoreCase = true) } }
            .flatMap { info -> info.getCapabilitiesForType(AVC).profileLevels.asList() }

    private const val AVC: String = "video/avc"

    public override fun create(): VideoBackend =
        ExoPlayerVideoBackend(PlatformEnvironment.requireContext().androidContext)
}
