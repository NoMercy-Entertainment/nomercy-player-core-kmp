// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.natives

// Which prebuilt native payload this machine needs.
//
// The JVM target is the only desktop target there is, and it runs on three
// operating systems and two instruction sets. A native library therefore has
// five shapes, and a machine needs exactly one of them — which is the whole
// reason payloads are per-platform rather than one archive carrying everything:
// a consumer on a laptop must not download macOS and Linux binaries to play a
// file on Windows.
public enum class HostPlatform(public val id: String) {
    WINDOWS_X64("windows-x64"),
    LINUX_X64("linux-x64"),
    LINUX_ARM64("linux-arm64"),
    MACOS_X64("macos-x64"),
    MACOS_ARM64("macos-arm64"),
    ;

    public companion object {
        // Null on a machine this project has no payload shape for — a 32-bit
        // JVM, a BSD, an s390x. Null rather than a guess, because guessing here
        // means downloading forty megabytes of the wrong architecture and
        // failing at the linker with a message about a file format.
        public fun current(): HostPlatform? = of(
            System.getProperty("os.name").orEmpty(),
            System.getProperty("os.arch").orEmpty(),
        )

        internal fun of(osName: String, osArch: String): HostPlatform? {
            val os: String = osName.lowercase()
            val arch: Arch = archOf(osArch.lowercase()) ?: return null
            return when {
                os.startsWith("windows") -> if (arch == Arch.X64) WINDOWS_X64 else null
                os.startsWith("mac") || os.startsWith("darwin") ->
                    if (arch == Arch.ARM64) MACOS_ARM64 else MACOS_X64

                os.startsWith("linux") -> if (arch == Arch.ARM64) LINUX_ARM64 else LINUX_X64
                else -> null
            }
        }

        // "amd64" from a HotSpot on Linux, "x86_64" from one on macOS, "aarch64"
        // from a JDK on Apple silicon and "arm64" from some others. All four
        // name two architectures.
        private fun archOf(osArch: String): Arch? = when (osArch) {
            "amd64", "x86_64", "x64" -> Arch.X64
            "aarch64", "arm64" -> Arch.ARM64
            else -> null
        }
    }

    private enum class Arch { X64, ARM64 }
}
