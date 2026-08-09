// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.natives

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The two files that describe a built payload have to agree about its version.
//
// natives/payloads.json names the archive the loader fetches and the bundling
// task copies. tools/native-payloads.lock tells the build script what to build
// and what to call it. Nothing compared them, and the macOS entry had no
// version pinned at all — so the script fell back to the WINDOWS version and
// packed libmpv-2026.06.10-macos-arm64.tar.gz while the catalogue asked for
// 0.41.0. The build succeeded, the archive was written, and the caller found
// nothing at the name it wanted.
//
// A silence, not a failure: two hand-maintained files disagreeing about one
// payload, in a repository whose whole premise is that they cannot. The libass
// entry going missing for every platform at once was the same shape.
class PayloadVersionsAgreeTest {

    @Test
    fun everyBuiltPayloadIsNamedTheSameByBothFiles() {
        val lock: String = repositoryFile("tools/native-payloads.lock").readText()
        val manifest: String = repositoryFile("natives/payloads.json").readText()

        val entries: List<Pair<String, String>> = builtPayloads(manifest)
        assertTrue(entries.isNotEmpty(), "no LIB_MPV payloads were read — the manifest was not parsed")

        for ((platform, version) in entries) {
            val pinned: String? = pin(lock, "libmpv.$platform.version") ?: pin(lock, "libmpv.version")
            assertEquals(
                version,
                pinned,
                "payloads.json calls the $platform payload $version and the lock builds $pinned",
            )
        }
    }

    // The platform ids as the build script spells them, paired with the version
    // the catalogue expects. Read as text rather than through a JSON library,
    // which this module does not depend on for the JVM target.
    private fun builtPayloads(manifest: String): List<Pair<String, String>> =
        ENTRY.findAll(manifest)
            .filter { match -> match.groupValues[1] == "LIB_MPV" }
            .map { match -> match.groupValues[2].lowercase().replace('_', '-') to match.groupValues[3] }
            .toList()

    private fun pin(lock: String, key: String): String? =
        Regex("(?m)^${Regex.escape(key)}=(.*)$").find(lock)?.groupValues?.get(1)?.trim()?.ifEmpty { null }

    // Up from the module directory the test JVM starts in, so the test reads the
    // same bytes the build does rather than a copy that can drift.
    private fun repositoryFile(path: String): File {
        val direct = File(path)
        if (direct.isFile) return direct
        return File(File("").absoluteFile, path).also {
            assertTrue(it.isFile, "cannot find $path from ${it.absolutePath}")
        }
    }

    private companion object {
        val ENTRY: Regex = Regex(
            """"kind":\s*"(\w+)",\s*"platform":\s*"(\w+)",\s*"version":\s*"([^"]+)"""",
        )
    }
}
