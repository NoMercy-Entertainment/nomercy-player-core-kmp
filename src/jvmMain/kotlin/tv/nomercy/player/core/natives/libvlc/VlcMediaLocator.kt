// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.natives.libvlc

import java.io.File

// Which of libVLC's two openers a string is for, and what to hand it.
//
// libVLC opens a URL and a filesystem path through different calls, and picking
// the wrong one fails quietly: a plain Windows path opened as a location becomes
// a request for a host called "c".
internal object VlcMediaLocator {

    // Anything with a scheme is a location. Deliberately loose — libVLC knows
    // its own schemes and there are dozens, so recognising them here would be a
    // list to keep up to date for no gain.
    fun isLocation(mrl: String): Boolean = LOCATION.matches(mrl)

    // A path carrying characters outside ASCII, turned into a file URI.
    //
    // libVLC's path opener takes bytes in the system's own encoding, and a JVM
    // whose file.encoding is not UTF-8 hands it something it cannot open — which
    // is a film that will not play because of an accent in its title. A file URI
    // is percent-encoded ASCII and has no such question.
    //
    // Untouched otherwise, because the conversion is not free of consequences
    // either: it turns a relative path absolute against the working directory.
    fun encode(mrl: String): String {
        if (isLocation(mrl) || mrl.all { character -> character.code < ASCII_LIMIT }) return mrl
        return runCatching { fileUri(mrl) }.getOrDefault(mrl)
    }

    // File.toURI writes file:/C:/x with one slash, which is legal and which
    // libVLC will not open.
    private fun fileUri(path: String): String {
        val uri: String = File(path).toURI().toASCIIString()
        return if (uri.startsWith(AUTHORITY)) uri else uri.replaceFirst("file:/", "file:///")
    }

    private const val ASCII_LIMIT: Int = 128
    private const val AUTHORITY: String = "file://"
    private val LOCATION = Regex(".+://.*")
}
