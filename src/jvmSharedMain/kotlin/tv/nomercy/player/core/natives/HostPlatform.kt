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
    ANDROID_ARM64("android-arm64"),
    ANDROID_ARM("android-arm"),
    ;

    public companion object {
        // Null on a machine this project has no payload shape for — a 32-bit
        // JVM, a BSD, an s390x. Null rather than a guess, because guessing here
        // means downloading forty megabytes of the wrong architecture and
        // failing at the linker with a message about a file format.
        public fun current(): HostPlatform? = of(
            System.getProperty("os.name").orEmpty(),
            System.getProperty("os.arch").orEmpty(),
            System.getProperty("java.vm.name").orEmpty(),
        )

        internal fun of(osName: String, osArch: String, vmName: String = ""): HostPlatform? {
            val os: String = osName.lowercase()
            val arch: Arch = archOf(osArch.lowercase()) ?: return null

            // Android before Linux, because Android IS Linux as far as os.name
            // is concerned — a phone answers "Linux"/"aarch64" and would take
            // the desktop payload, whose libraries are linked against glibc and
            // load on nothing. The VM is what tells them apart: ART still
            // reports itself as Dalvik.
            if (isAndroid(vmName)) {
                return when (arch) {
                    Arch.ARM64 -> ANDROID_ARM64
                    Arch.ARM -> ANDROID_ARM
                    else -> null
                }
            }

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
            // 32-bit ARM, which is most of the Android fleet and not a rarity:
            // of three devices on this desk two are armeabi-v7a only — a Galaxy
            // A13 and a Nokia streaming box. A phone reporting this and getting
            // null was the whole payload silently unavailable.
            "arm", "armv7l", "armv7", "aarch32" -> Arch.ARM
            else -> null
        }

        // ART still answers "Dalvik" for java.vm.name, and has since KitKat.
        // Exposed because the library loader needs the same answer BEFORE a
        // platform can be resolved — asking HostPlatform there was circular and
        // produced a library name of "libmpv.so.2.so".
        internal fun isAndroid(vmName: String = System.getProperty("java.vm.name").orEmpty()): Boolean =
            vmName.contains("dalvik", ignoreCase = true) || vmName.contains("art", ignoreCase = true)
    }

    private enum class Arch { X64, ARM64, ARM }
}
