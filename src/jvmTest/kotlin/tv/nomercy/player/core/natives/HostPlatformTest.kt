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
import kotlin.test.assertNull

// The strings JVMs actually report, not the ones the documentation implies.
//
// Getting one of these wrong does not fail loudly: it downloads eighty
// megabytes of the wrong architecture and fails at the linker with a message
// about a file format, on somebody else's machine.
class HostPlatformTest {

    @Test
    fun `names every architecture spelling a JVM reports`() {
        // amd64 from HotSpot on Linux and Windows, x86_64 from one on macOS,
        // aarch64 from most JDKs on ARM and arm64 from some.
        assertEquals(HostPlatform.WINDOWS_X64, HostPlatform.of(WINDOWS, AMD64))
        assertEquals(HostPlatform.LINUX_X64, HostPlatform.of(LINUX, AMD64))
        assertEquals(HostPlatform.LINUX_X64, HostPlatform.of(LINUX, X86_64))
        assertEquals(HostPlatform.LINUX_ARM64, HostPlatform.of(LINUX, AARCH64))
        assertEquals(HostPlatform.MACOS_ARM64, HostPlatform.of(MACOS, AARCH64))
        assertEquals(HostPlatform.MACOS_ARM64, HostPlatform.of(MACOS, "arm64"))
        assertEquals(HostPlatform.MACOS_X64, HostPlatform.of(MACOS, X86_64))
    }

    @Test
    fun `answers nothing rather than guessing on a machine with no payload`() {
        // A 32-bit JVM is the dangerous one: it is not an error anywhere else in
        // the stack, and a 64-bit payload would load right up to the linker.
        assertNull(HostPlatform.of(WINDOWS, "x86"))
        assertNull(HostPlatform.of(WINDOWS, AARCH64))
        assertNull(HostPlatform.of("FreeBSD", AMD64))
        assertNull(HostPlatform.of(LINUX, "s390x"))
    }

    private companion object {
        const val WINDOWS = "Windows 11"
        const val LINUX = "Linux"
        const val MACOS = "Mac OS X"
        const val AMD64 = "amd64"
        const val X86_64 = "x86_64"
        const val AARCH64 = "aarch64"
    }
}
