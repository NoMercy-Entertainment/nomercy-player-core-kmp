// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import java.net.URI

// Every URI in a master playlist, made absolute against where the playlist came
// from.
//
// A narrowed playlist is opened from somewhere other than the server that served
// it, and HLS has no base-URI tag: a relative variant URI resolves against the
// playlist's own location and nothing else. Move the text without doing this and
// every variant becomes a path next to the copy, which is a directory with
// nothing in it — the load fails with no picture and no reason.
//
// Both shapes the spec allows are handled. A variant's URI is the line beneath
// its tag; an audio group's, a key's and a map's is a URI attribute inside one.
internal object HlsAbsoluteUris {

    private const val URI_ATTRIBUTE: String = "URI=\""

    internal fun rewrite(manifest: String, masterUrl: String): String {
        val base: URI = runCatching { URI.create(masterUrl) }.getOrNull() ?: return manifest
        return manifest.lines().joinToString("\n") { line: String -> absolute(line, base) }
    }

    private fun absolute(line: String, base: URI): String = when {
        line.isBlank() -> line
        line.startsWith("#") -> withAbsoluteAttribute(line, base)
        else -> resolve(line.trim(), base) ?: line
    }

    // Only the first URI attribute, because no tag the spec defines carries two.
    private fun withAbsoluteAttribute(tagLine: String, base: URI): String {
        val opens: Int = tagLine.indexOf(URI_ATTRIBUTE)
        if (opens < 0) return tagLine

        val valueStart: Int = opens + URI_ATTRIBUTE.length
        val closes: Int = tagLine.indexOf('"', valueStart)
        if (closes < 0) return tagLine

        return resolve(tagLine.substring(valueStart, closes), base)
            ?.let { tagLine.substring(0, valueStart) + it + tagLine.substring(closes) }
            ?: tagLine
    }

    // Null when the reference is a shape java.net.URI will not take. Leaving such
    // a line untouched is the honest failure: it was already going to be resolved
    // by libVLC or by nobody, and replacing it with a guess would break a variant
    // that might have worked.
    private fun resolve(reference: String, base: URI): String? {
        if (reference.isEmpty()) return null
        return runCatching { base.resolve(reference).toString() }.getOrNull()
    }
}
