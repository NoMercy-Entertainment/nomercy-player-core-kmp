// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.media

// Turns "%S2 %E5" into "Season 2 Episode 5", in the viewer's language.
//
// A server that sent "Season 2" would be sending English to a Dutch viewer. It
// sends the shape instead and the player fills it in, which is the only way one
// stored title works for everybody.
//
// Which letters mean what is per library: the video player knows about seasons
// and episodes, the music player about discs and tracks, and core knows about
// neither. An empty registry leaves every token verbatim, so core's default is
// a guaranteed no-op rather than a wrong guess.
public object TitleTokens {

    // One pattern for every token, and the digits are part of it: %S2 is season
    // two, not the letter S followed by a stray 2. Matching the letter alone
    // would leave the number behind as loose text.
    private val TOKEN = Regex("""%([A-Za-z])(\d+)""")

    // Tokens whose letter is not registered are left exactly as they were.
    //
    // That is what makes this safe to run twice: resolved text has no tokens
    // left to match, and an unknown letter that came from a newer server is
    // shown as-is rather than swallowed. A viewer seeing "%X3" has something to
    // report; a viewer seeing a gap has nothing.
    public fun interpolate(
        text: String,
        registry: Map<String, String>,
        translate: (key: String, vars: Map<String, String>) -> String,
    ): String {
        if (text.isEmpty() || registry.isEmpty()) return text

        return TOKEN.replace(text) { match ->
            val letter: String = match.groupValues[1]
            val number: String = match.groupValues[2]
            val key: String? = registry[letter]
            if (key == null) match.value else translate(key, mapOf("number" to number))
        }
    }
}
