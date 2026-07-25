// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.stream

import tv.nomercy.player.core.media.DynamicRange
import tv.nomercy.player.core.media.QualityDescriptor

private const val STREAM_INF = "#EXT-X-STREAM-INF:"
private const val I_FRAME_STREAM_INF = "#EXT-X-I-FRAME-STREAM-INF:"

// Rewrites an HLS master playlist so the engine only sees renditions the device
// can actually play.
//
// Pure text in, pure text out, and it lives in common: all three engines drive
// the same function, so they cannot disagree about what a rewritten manifest
// looks like. The per-engine plumbing that feeds it — an OkHttp interceptor, a
// resource-loader delegate, libVLC options — is the only part that differs.
//
// Written against RFC 8216's tag grammar rather than one server's output, so it
// handles playlists from anything that follows the spec.
//
// It is not a parser of record. Anything it does not recognise is passed through
// untouched, because a rewriter that silently drops what it has not been taught
// about breaks playback in a way nobody can see in the diff.
public object MasterPlaylistRewriter {

    public fun rewrite(manifest: String, keep: Collection<QualityDescriptor>): String {
        val lines: List<String> = manifest.lines()
        val wanted: Set<QualityDescriptor> = keep.toSet()
        val playableHeights: Set<Int> = wanted.map { it.height }.toSet()
        val out: MutableList<String> = mutableListOf()

        var index = 0
        while (index < lines.size) {
            val line: String = lines[index]
            index = when {
                line.startsWith(STREAM_INF) -> copyVariant(lines, index, out, wanted)
                line.startsWith(I_FRAME_STREAM_INF) -> index + copyTrickPlay(line, out, playableHeights)
                else -> { out.add(line); index + 1 }
            }
        }

        return out.joinToString("\n")
    }

    // A variant is two lines — the tag and the URI beneath it — so dropping one
    // has to drop both, and the caller cannot advance by a fixed step.
    private fun copyVariant(
        lines: List<String>,
        index: Int,
        out: MutableList<String>,
        wanted: Set<QualityDescriptor>,
    ): Int {
        val uriIndex: Int = nextUriIndex(lines, index)
        // A variant nobody can identify is kept. Guessing at it would drop a
        // rendition on the strength of an attribute the server chose not to send.
        val descriptor: QualityDescriptor? = descriptorOf(lines[index])
        if (descriptor == null || descriptor in wanted) {
            out.add(lines[index])
            if (uriIndex < lines.size) out.add(lines[uriIndex])
        }
        return uriIndex + 1
    }

    // An I-frame variant is the trick-play track a scrubber previews with, not a
    // rendition anyone watches. Its bitrate is a thumbnail bitrate and is never
    // in the ladder, so matching it against the ladder would drop every one of
    // them and take scrubbing previews with it. Height is what ties it to the
    // renditions that survived, and its URI is an attribute rather than the next
    // line, so it drops alone.
    private fun copyTrickPlay(line: String, out: MutableList<String>, playableHeights: Set<Int>): Int {
        val height: Int? = heightOf(attributesOf(line))
        if (height == null || height in playableHeights) out.add(line)
        return 1
    }

    public fun variants(manifest: String): List<QualityDescriptor> =
        manifest.lines()
            .filter { it.startsWith(STREAM_INF) }
            .mapNotNull { descriptorOf(it) }

    // A variant's identity, from the attributes the spec defines for it.
    //
    // Null when the tag carries no RESOLUTION: without a height there is nothing
    // to match a descriptor against.
    public fun descriptorOf(streamInfLine: String): QualityDescriptor? {
        val attributes: Map<String, String> = attributesOf(streamInfLine)
        val height: Int = heightOf(attributes) ?: return null

        return QualityDescriptor(
            height = height,
            // AVERAGE-BANDWIDTH is the honest number for choosing a rendition;
            // BANDWIDTH is the peak, and the spec requires only the latter.
            bitrate = (attributes["AVERAGE-BANDWIDTH"] ?: attributes["BANDWIDTH"])?.toIntOrNull() ?: 0,
            dynamicRange = dynamicRangeOf(attributes["VIDEO-RANGE"]),
            codec = attributes["CODECS"]?.split(",")?.firstOrNull()?.trim(),
        )
    }

    private fun heightOf(attributes: Map<String, String>): Int? =
        attributes["RESOLUTION"]?.substringAfter('x')?.toIntOrNull()

    // The spec's VIDEO-RANGE values. PQ covers HDR10 and Dolby Vision and the
    // playlist does not distinguish them — a client that needs to reads the
    // codec string, which is why the descriptor carries both.
    private fun dynamicRangeOf(videoRange: String?): DynamicRange = when (videoRange?.uppercase()) {
        "PQ" -> DynamicRange.Hdr10
        "HLG" -> DynamicRange.HlG
        else -> DynamicRange.Sdr
    }

    // Attribute lists are comma-separated, values may be quoted, and a quoted
    // value may itself contain commas — CODECS="avc1.640028,mp4a.40.2" is the
    // common case. Splitting on commas without tracking quotes is the bug this
    // avoids.
    private fun attributesOf(tagLine: String): Map<String, String> {
        val attributes: MutableMap<String, String> = mutableMapOf()
        val current: StringBuilder = StringBuilder()
        var inQuotes = false

        for (char in tagLine.substringAfter(':')) {
            if (char == '"') inQuotes = !inQuotes
            if (char == ',' && !inQuotes) {
                putAttribute(attributes, current.toString())
                current.clear()
            } else {
                current.append(char)
            }
        }
        putAttribute(attributes, current.toString())
        return attributes
    }

    private fun putAttribute(into: MutableMap<String, String>, text: String) {
        val trimmed: String = text.trim()
        if (trimmed.isEmpty()) return
        val name: String = trimmed.substringBefore('=').trim()
        if (name.isEmpty()) return
        into[name] = trimmed.substringAfter('=', "").trim().trim('"')
    }

    // The URI belonging to a STREAM-INF tag: the next line that is neither blank
    // nor a tag. Comments and blank lines between the two are legal.
    private fun nextUriIndex(lines: List<String>, tagIndex: Int): Int {
        var index: Int = tagIndex + 1
        while (index < lines.size && !isUri(lines[index])) index += 1
        return index
    }

    private fun isUri(line: String): Boolean = line.isNotBlank() && !line.startsWith("#")
}
