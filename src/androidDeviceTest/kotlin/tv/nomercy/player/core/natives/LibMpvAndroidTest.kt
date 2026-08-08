// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.natives

import androidx.test.platform.app.InstrumentationRegistry
import tv.nomercy.player.core.ports.PlatformContext
import tv.nomercy.player.core.ports.PlatformEnvironment
import tv.nomercy.player.core.ports.engines.MpvVideoEngineProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * libmpv, on a phone, doing the three things nothing else has proven.
 *
 * The payload builds, the catalogue lists it and both targets compile — none of
 * which says a single byte reached a device or that the loader opened it. This
 * is the only check in the module that can answer that, and it is the one whose
 * failure looks like "Hi10P still does not play".
 */
class LibMpvAndroidTest {

    private fun install() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PlatformEnvironment.install(PlatformContext(context.applicationContext))
    }

    // The device is ANDROID_ARM64 and not LINUX_ARM64, which is the whole
    // reason the platform is read from the VM rather than from os.name: a phone
    // answers "Linux"/"aarch64" and would otherwise be handed a payload of
    // glibc-linked desktop libraries.
    @Test
    fun theDeviceAsksForTheAndroidPayloadAndNotTheDesktopOne() {
        val expected: HostPlatform = if (android.os.Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) {
            HostPlatform.ANDROID_ARM64
        } else {
            HostPlatform.ANDROID_ARM
        }

        // Not a constant, because half this fleet is 32-bit: a Galaxy A13 and a
        // Nokia streaming box are armeabi-v7a only, and the first run of this
        // test asserted arm64 on a phone that has never had it. The answer that
        // must never come back is null — that was the state where the payload
        // was silently unavailable and libmpv was asked for by a name with two
        // extensions on it.
        assertEquals(
            expected,
            HostPlatform.current(),
            "os.name=${System.getProperty("os.name")} os.arch=${System.getProperty("os.arch")} " +
                "vm=${System.getProperty("java.vm.name")} abis=${android.os.Build.SUPPORTED_ABIS.joinToString()}",
        )
    }

    // The archive has to be IN the artifact. A catalogue entry for a payload
    // that was never packaged is the shape the libass entry had for months
    // while styled subtitles silently drew nothing.
    @Test
    fun theArchiveIsInsideTheInstalledArtifact() {
        install()

        val platform: HostPlatform = requireNotNull(HostPlatform.current())
        val stream = javaClass.classLoader
            ?.getResourceAsStream("tv/nomercy/player/natives/libmpv-0.41.0-${platform.id}.tar.gz")

        assertNotNull(stream, "the android libmpv payload is not packaged in the artifact")
        stream.use { open -> assertTrue(open.read() >= 0, "the packaged payload is empty") }
    }

    /**
     * Unpacked, linked and initialised — the three that fail separately.
     *
     * The provider creates and destroys a real handle rather than only loading
     * the library, because loading proves a file is on the path and
     * initialising proves it is a libmpv this build can drive. On Android there
     * is a fourth way to fail that no other platform has: libmpv's DT_NEEDED
     * names seven ffmpeg objects, and the linker will not search a directory
     * unpacked at run time, so each has to be opened by absolute path first.
     */
    @Test
    fun libmpvLoadsAndInitialisesOnThisDevice() {
        install()

        assertTrue(
            MpvVideoEngineProvider.isAvailable(),
            "libmpv did not start on this device: ${MpvVideoEngineProvider.whyUnavailable()}",
        )
    }
}
