// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

// Sintel's real master playlist, byte for byte off the shipped fixture.
//
// The real one rather than a hand-written stand-in, because two of the three
// things that made this defect hard to see are properties of THIS manifest: an
// HDR and an SDR rendition at the same resolution, which no libVLC option can
// tell apart, and a directory-relative variant URI, which breaks the moment the
// playlist is opened from anywhere else.
internal const val SINTEL_MASTER_URL: String =
    "https://raw.githubusercontent.com/NoMercy-Entertainment/nomercy-media/master/" +
        "Films/Sintel.(2010)/Sintel.(2010).NoMercy.m3u8"

internal val SINTEL_MASTER: String = """
    #EXTM3U
    #EXT-X-VERSION:6

    #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio_aac",LANGUAGE="eng",AUTOSELECT=YES,DEFAULT=YES,URI="audio_eng_aac/audio_eng_aac.m3u8",NAME="English aac"

    #EXT-X-STREAM-INF:BANDWIDTH=743922,RESOLUTION=1920x818,CODECS="avc1.4D401E,mp4a.40.2",AUDIO="audio_aac",VIDEO-RANGE=SDR,NAME="1920x818 SDR"
    video_1920x818_SDR/video_1920x818_SDR.m3u8

    #EXT-X-STREAM-INF:BANDWIDTH=821147,RESOLUTION=1920x818,CODECS="avc1.4D401E,mp4a.40.2",AUDIO="audio_aac",VIDEO-RANGE=PQ,NAME="1920x818 HDR"
    video_1920x818/video_1920x818.m3u8

    #EXT-X-STREAM-INF:BANDWIDTH=2077179,RESOLUTION=3840x1635,CODECS="avc1.4D401E,mp4a.40.2",AUDIO="audio_aac",VIDEO-RANGE=SDR,NAME="3840x1635 SDR"
    video_3840x1635_SDR/video_3840x1635_SDR.m3u8

    #EXT-X-STREAM-INF:BANDWIDTH=2402870,RESOLUTION=3840x1635,CODECS="avc1.4D401E,mp4a.40.2",AUDIO="audio_aac",VIDEO-RANGE=PQ,NAME="3840x1635 HDR"
    video_3840x1635/video_3840x1635.m3u8
""".trimIndent()

// A fetch that answers from memory, so the narrowing is provable without a
// network — and so a machine with no connection still runs the gate.
internal class FakeHlsMasterFetch(
    private val body: String?,
) : HlsMasterFetch {

    internal var asked: MutableList<String> = mutableListOf()

    internal var sawHeaders: Map<String, String> = emptyMap()

    override fun get(url: String, headers: Map<String, String>): String? {
        asked.add(url)
        sawHeaders = headers
        return body
    }
}
