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
