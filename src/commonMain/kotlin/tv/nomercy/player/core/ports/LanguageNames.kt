// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

// The name of a language, in the language the viewer reads.
//
// The app this replaces carried a hand-written map of about a hundred entries,
// "nld" to "Dutch" and so on. Two things were wrong with it. It was English
// only, so a Dutch viewer picking a dub read English words in a Dutch
// interface; and it was a list someone has to keep, which means the next
// language a title ships in is missing until somebody notices.
//
// Every platform here already knows this and keeps it current with the OS. Asked
// in a Dutch locale, "nld" comes back as "Nederlands".
//
// Returns the tag itself when the platform does not recognise it, rather than
// something like "Unknown". An unrecognised tag is still the most specific true
// thing available, and a menu row reading "qaa" at least matches what the
// manifest said — three rows reading "Unknown" cannot be told apart at all.
public expect fun displayLanguage(tag: String): String

// Whether two tracks need more than their language to tell them apart.
//
// A title with one English and one Japanese track needs no codec in the menu:
// the languages already distinguish them, and "English (EAC3)" is noise. A
// title with two English tracks — a stereo mix and a surround one — needs
// something, or the menu offers the same word twice.
//
// This is the rule the app had and it is worth keeping. It is pure, so it lives
// here rather than beside any one engine's mapper.
public fun labelsNeedQualifier(languages: List<String>): Boolean =
    languages.size != languages.toSet().size

// A subtitle rendition's label, when the source only gave a bare variant word.
//
// The media server (and HLS SUBTITLES groups it generates) names a track's
// VARIANT — "full", "sdh", "sign", "forced", "alt" — not a human label, on both
// the sidecar wire response AND the embedded-track path a native engine reads
// off the manifest. Left alone, six same-kind tracks in six different
// languages all render the identical bare word, with nothing to tell them
// apart (confirmed live, real TV, 2026-08-15). Shared here rather than
// duplicated per engine, because both paths hand this exact ambiguity to
// exactly the same fix.
public fun resolveSubtitleLabel(label: String?, language: String?): String {
    val kind: String? = subtitleKindOf(label)

    // Resolved once, used by both tiers below: a track with no kind word is
    // exactly as entitled to a real language name as one with "full" in its
    // label. Excludes the tag itself — "und" IS a real ISO-639-2 code, but
    // showing it verbatim in a menu answers nothing a viewer can act on, so
    // it is treated the same as no language at all rather than round-tripped
    // through displayLanguage (which mostly just echoes an unrecognised tag
    // back unchanged, per its own doc — including this one).
    val langName: String? = language
        ?.takeIf { it.isNotBlank() && !it.equals(UNKNOWN_SUBTITLE_LANGUAGE, ignoreCase = true) }
        ?.let(::displayLanguage)

    if (kind != null) {
        return if (langName != null) "$langName ($kind)" else kind
    }
    return label?.takeIf { it.isNotBlank() }
        ?: langName
        ?: UNKNOWN_SUBTITLE_LANGUAGE
}

// Whole tokens, not substrings — "Maltese" contains "alt", and a title named
// "Sign of Four" would otherwise claim the signs variant.
public fun subtitleKindOf(label: String?): String? {
    val tokens: Set<String> = label.orEmpty().lowercase()
        .split('.', ' ', '_', '-', '/', '(', ')', '[', ']')
        .filterTo(mutableSetOf()) { it.isNotBlank() }
    return when {
        "sdh" in tokens -> "SDH"
        "sign" in tokens || "signs" in tokens -> "Signs"
        "full" in tokens -> "Full"
        "forced" in tokens -> "Forced"
        "alt" in tokens -> "Alt"
        else -> null
    }
}

private const val UNKNOWN_SUBTITLE_LANGUAGE = "und"
