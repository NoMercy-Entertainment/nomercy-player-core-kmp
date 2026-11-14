// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

// One audio track as the pick sees it: values only, so the rule is provable
// without an engine.
internal data class AudioPickCandidate(
    val language: String,
    val label: String,
    val isDefault: Boolean,
    val isCommentaryRole: Boolean,
    val channels: Int,
    val decodesNatively: Boolean,
    val playable: Boolean = true,
)

// Which track of one language plays when nobody chose one.
internal object MainAudioPick {

    // The index into `candidates` to play, or null when none is playable.
    // The language is the one of `current` (what the engine selected from the
    // viewer's preference), so the language decision stays where it was.
    fun pick(candidates: List<AudioPickCandidate>, current: Int, maxOutputChannels: Int): Int? {
        val language: String = candidates.getOrNull(current)?.language?.let(::primaryLanguage) ?: return null
        // sortedWith is stable, so the last key is the file order.
        return candidates.indices
            .filter { candidates[it].playable && primaryLanguage(candidates[it].language) == language }
            .sortedWith(
                compareBy<Int> { isCommentary(candidates[it]) }
                    .thenByDescending { candidates[it].isDefault }
                    .thenByDescending { minOf(candidates[it].channels, maxOutputChannels) }
                    .thenByDescending { candidates[it].decodesNatively },
            )
            .firstOrNull()
    }

    internal fun primaryLanguage(tag: String): String = tag.substringBefore('-').lowercase()

    private fun isCommentary(track: AudioPickCandidate): Boolean =
        track.isCommentaryRole ||
            COMMENTARY_WORDS.containsMatchIn(track.label) ||
            AD_ABBREVIATION.containsMatchIn(track.label)

    private val COMMENTARY_WORDS: Regex =
        Regex("""commentary|descriptive|audio[\s-]*description""", RegexOption.IGNORE_CASE)

    // Upper case only, so a word like "ad" in a title is not a description track.
    private val AD_ABBREVIATION: Regex = Regex("""\bAD\b""")
}
