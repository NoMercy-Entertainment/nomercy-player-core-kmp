// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.natives

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

// Which payloads this library can actually get hold of.
//
// The catalogue published libVLC for the two desktop platforms and libass for
// none of them, on any operating system. Every consequence of that was silent:
// AssRenderers fell through to a plain "ass" lookup plus a few Homebrew and
// Linux paths, found nothing on Windows, and returned null — so a styled .ass
// track drew no text at all while a .vtt beside it drew fine, and nothing
// anywhere said why. A viewer reported it as "this episode has no subtitles".
//
// The archives had been built and published the whole time. The table was the
// only thing missing, which is why this asserts the table rather than the
// renderer: a payload that exists and is not listed is indistinguishable from
// one that was never built.
class NativeArchivesTest {

    @Test
    fun libassIsPublishedForEveryDesktopPlatformTheLoaderRunsOn() {
        for (platform in listOf(HostPlatform.WINDOWS_X64, HostPlatform.LINUX_X64)) {
            assertNotNull(
                NativeArchives.of(NativeRuntimeKind.LIB_ASS, platform),
                "no libass payload for $platform — styled subtitles draw nothing there",
            )
        }
    }

    // The marker is what the installer looks for to decide an unpack worked,
    // and it is also the filename AssRenderers loads. A marker that does not
    // name a file inside the archive installs a directory the loader then
    // cannot use, which fails exactly as quietly as having no archive at all.
    @Test
    fun theLibassMarkerIsTheFileTheLoaderAsksFor() {
        assertEquals(
            "libass-9.dll",
            NativeArchives.of(NativeRuntimeKind.LIB_ASS, HostPlatform.WINDOWS_X64)?.marker,
        )
        assertEquals(
            "libass.so.9",
            NativeArchives.of(NativeRuntimeKind.LIB_ASS, HostPlatform.LINUX_X64)?.marker,
        )
    }

    // libass resolves to the repository that builds it, not to this one. That
    // repo exists so web, desktop, Android and Apple all render with one build;
    // mirroring its bytes here to keep a single URL shape would put the second
    // origin back.
    @Test
    fun eachKindResolvesToTheRepositoryThatPublishesIt() {
        // The version comes from the entry rather than being typed here. What
        // this test is for is the REPOSITORY and the tag shape -- a payload
        // published by nomercy-libass and resolved against this repo's releases
        // is the second origin that repo exists to remove. A literal version on
        // top of that only means the test has to be edited every time a payload
        // is rebuilt, and a test edited on every bump stops being read.
        val libass = assertNotNull(NativeArchives.of(NativeRuntimeKind.LIB_ASS, HostPlatform.WINDOWS_X64))
        assertEquals(
            "https://github.com/NoMercy-Entertainment/nomercy-libass/releases/download/" +
                "v${libass.version}/libass-${libass.version}-windows-x64.tar.gz",
            libass.url,
        )

        val libmpv = assertNotNull(NativeArchives.of(NativeRuntimeKind.LIB_MPV, HostPlatform.WINDOWS_X64))
        assertEquals(
            "https://github.com/NoMercy-Entertainment/nomercy-player-core-kmp/releases/download/" +
                "natives-libmpv-2026.06.10/libmpv-2026.06.10-windows-x64.tar.gz",
            libmpv.url,
        )
    }
}
