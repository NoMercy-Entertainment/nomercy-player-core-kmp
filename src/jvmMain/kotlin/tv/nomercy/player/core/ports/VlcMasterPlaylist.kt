// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import java.io.File
import tv.nomercy.player.core.media.QualityDescriptor
import tv.nomercy.player.core.stream.MasterPlaylistRewriter

// An HLS master playlist the desktop has read, and can hand back narrowed.
//
// This overturns a decision recorded in VlcAdaptiveOptions: that the desktop
// narrows a ladder by handing libVLC options rather than by rewriting a manifest,
// because options mean libVLC stays the parser. The reasoning was sound and the
// premise turned out to be false, which two measurements against the installed
// VLC 3.0.23 settled:
//
//   --adaptive-maxheight=900 works. It never opened the 3840 rungs.
//   --adaptive-bw=800 does not. It switched onto an 821 kbps rendition anyway,
//   because adaptive-bw is the assumed bandwidth of the FIXED-RATE logic and is
//   ignored by the default one — and it is documented in KiB/s, which the option
//   builder was feeding kilobits.
//
// Height is therefore the only filter libVLC 3 honours, and height cannot tell an
// HDR rendition from an SDR one. NoMercy's own ladder puts both at the same
// resolution — Sintel offers 1920x818 in SDR and in PQ — so no combination of
// options can keep the SDR one, and the HDR-on-an-SDR-screen decision this class
// exists to serve would stay unenforceable.
//
// Rewriting is also what Android already does through its OkHttp interceptor, on
// the same MasterPlaylistRewriter in common. So this is the desktop joining the
// shared answer rather than inventing a second one, which is the opposite of the
// divergence the original comment was protecting against.
internal class VlcMasterPlaylist private constructor(
    private val url: String,
    private val text: String,
) {

    // Every rendition the manifest declares, with the dynamic range it declares
    // for each. This is the ladder libVLC will not describe: its video track info
    // reports the one variant it happens to be decoding, so before this a desktop
    // ladder was one rung long and always read as SDR.
    internal val ladder: List<QualityDescriptor> = MasterPlaylistRewriter.variants(text)

    // How many renditions the manifest CLAIMS, describable or not. The gap
    // between this and the ladder is the set of rungs that have to be stripped
    // before libVLC sees them.
    private val declaredVariants: Int = text.lines().count { it.startsWith("#EXT-X-STREAM-INF") }

    // A playlist offering only [keep], somewhere libVLC can open it.
    //
    // Null when there is nothing to narrow, when narrowing would leave nothing to
    // play, or when the file could not be written — and null means the caller
    // plays the original URL, which is what it did before any of this existed.
    internal fun narrowedTo(keep: Collection<QualityDescriptor>): String? {
        if (keep.isEmpty()) return null

        // Rewrite whenever the manifest declares rungs the ladder does not, not
        // only when the CALLER asked to drop some.
        //
        // A rung whose RESOLUTION is not <int>x<int> is undescribable, so it is
        // absent from the ladder — and this used to conclude there was nothing
        // to narrow and hand libVLC the ORIGINAL url, malformed rung and all.
        // libVLC then opened the first entry it saw. Tears of Steel declares
        // RESOLUTION=video_3840x1714 pointing at media that 404s, so the film
        // played its audio and drew its subtitles over a black rectangle.
        //
        // hls.js drops a rendition it cannot use and VLC itself falls to the
        // next one; a player that hands the whole broken manifest downstream is
        // the one doing something unusual.
        if (keep.size == ladder.size && ladder.size == declaredVariants) return null

        val narrowed: String = MasterPlaylistRewriter.rewrite(text, keep)
        if (MasterPlaylistRewriter.variants(narrowed).isEmpty()) return null

        return spill(HlsAbsoluteUris.rewrite(narrowed, url))
    }

    // A temp file rather than a data URI or an in-memory callback, because libVLC
    // 3 resolves a playlist's references against the playlist's own location and
    // neither of the other two has one. The URIs are absolute by the time they get
    // here, so the location only has to be somewhere readable.
    //
    // deleteOnExit as well as delete-on-replace: a process killed mid-playback
    // would otherwise leave one behind per item played.
    private fun spill(manifest: String): String? = runCatching {
        val file: File = File.createTempFile(SPILL_PREFIX, SPILL_SUFFIX)
        file.deleteOnExit()
        file.writeText(manifest)
        file.absolutePath
    }.getOrNull()

    internal companion object {

        // Null when this is not a master playlist, when it could not be read, or
        // when it turned out to declare no renditions. Each of those means the
        // item plays exactly as it would have.
        internal fun of(
            url: String,
            headers: Map<String, String>,
            fetch: HlsMasterFetch,
        ): VlcMasterPlaylist? {
            if (!looksLikePlaylist(url)) return null

            val text: String = fetch.get(url, headers)?.takeIf(::isPlaylist) ?: return null
            return VlcMasterPlaylist(url, text).takeIf { it.ladder.isNotEmpty() }
        }

        // The tag the spec requires first. A 404 page and a redirect to a login form
        // both arrive as a 200 with a body, and handing either to the rewriter would
        // produce an empty ladder for a reason nobody could see.
        private fun isPlaylist(text: String): Boolean =
            text.lineSequence().firstOrNull()?.startsWith(PLAYLIST_TAG) == true

        // The extension, before any query string. Cheap, and it is what keeps a
        // local file and a progressive MP4 from paying for a network read on every
        // load — a desktop client's most common item is not adaptive at all.
        private fun looksLikePlaylist(url: String): Boolean =
            url.substringBefore('?').substringBefore('#').endsWith(SPILL_SUFFIX, ignoreCase = true)
    }
}

private const val PLAYLIST_TAG: String = "#EXTM3U"

// Named so the gate can find what a load wrote, which is how the narrowing is
// asserted: on the artifact libVLC was handed rather than on a value the backend
// chose to report.
private const val SPILL_PREFIX: String = "nomercy-ladder-"
private const val SPILL_SUFFIX: String = ".m3u8"
