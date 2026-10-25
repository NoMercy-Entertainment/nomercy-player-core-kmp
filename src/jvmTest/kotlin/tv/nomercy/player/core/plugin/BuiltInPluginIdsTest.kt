// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.plugin

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// A plugin id is a plain name. A vendor prefix (`vendor:name`) never appears on
// an id this repository declares, in a shipped plugin or in a test fixture.
//
// Reads every `val id: String = "..."` in the sources, so a plugin added later
// is covered without anyone remembering to list it here.
class BuiltInPluginIdsTest {

    private val literalId = Regex("""\bval id: String = "([^"]*)"""")

    private fun declaredIds(sourceRoot: String): List<Pair<String, String>> {
        val root = File(sourceRoot)
        assertTrue(root.isDirectory, "no sources at ${root.absolutePath}")

        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "BuiltInPluginIdsTest.kt" }
            .flatMap { file -> literalId.findAll(file.readText()).map { match -> file.name to match.groupValues[1] } }
            .toList()
    }

    @Test
    fun theScanFindsTheShippedPlugins() {
        val ids = declaredIds("src/commonMain/kotlin").map { it.second }
        assertTrue("waveform" in ids, "WaveformVisualization was not found: $ids")
        assertTrue("spectrum" in ids, "SpectrumPlugin was not found: $ids")
        assertTrue(ids.size >= 15, "expected at least 15 declared ids, found ${ids.size}: $ids")
    }

    @Test
    fun noDeclaredPluginIdCarriesAVendorPrefix() {
        val prefixed = declaredIds("src")
            .filter { (_, id) -> ':' in id }
            .map { (fileName, id) -> "$fileName: $id" }
        assertEquals(emptyList(), prefixed)
    }
}
