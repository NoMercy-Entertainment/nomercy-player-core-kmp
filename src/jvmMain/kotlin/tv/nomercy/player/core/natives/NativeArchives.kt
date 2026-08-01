// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.natives

// One payload: what it is, where it comes from and what its bytes must hash to.
//
// The digest is the whole contract. A native archive fetched over the network
// and executed is the shape of supply-chain compromise, so nothing is unpacked
// before it has been weighed, and a mismatch is a hard failure rather than a
// fallback — a payload that is not the one we published is not a payload to try
// anyway.
internal class NativeArchive(
    val kind: NativeRuntimeKind,
    val platform: HostPlatform,
    val version: String,
    val sha256: String,
    val marker: String,
) {
    val fileName: String get() = "${kind.slug}-$version-${platform.id}.tar.gz"

    // Keyed on the digest as well as the version, so a republished payload
    // installs beside the old one instead of half over it.
    val installName: String get() = "$version-${sha256.take(DIGEST_PREFIX)}"

    // The published archive, or a mirror of it.
    //
    // The mirror hook is not a user setting and never becomes one — a self-hosted
    // user configures NoMercy through its dashboard, not through the environment.
    // It is here because CI has to fetch a payload it has just built and has not
    // published yet, and because an enterprise that will not let a machine reach
    // github.com can point this at their own copy. Either way the digest check is
    // unchanged, so a mirror serving the wrong bytes fails exactly as loudly.
    val url: String
        get() {
            val base: String = System.getProperty("nomercy.player.natives.baseUrl") ?: RELEASES
            return "$base/natives-${kind.slug}-$version/$fileName"
        }

    // Inside the archive, so a resource-shipped payload needs no separate
    // download at all.
    val resourcePath: String get() = "/tv/nomercy/player/natives/$fileName"

    private companion object {
        const val DIGEST_PREFIX: Int = 12
        const val RELEASES: String =
            "https://github.com/NoMercy-Entertainment/nomercy-player-core-kmp/releases/download"
    }
}

// The payloads that exist, by kind and platform.
//
// A platform absent from this table is a platform with no published payload
// yet, and the honest consequence is that the library falls back to whatever
// the machine has — which is what every JVM consumer had before any of this.
// Absent rather than pinned-to-nothing, because a table entry with an empty
// digest is an entry that would happily install anything.
internal object NativeArchives {

    fun of(kind: NativeRuntimeKind, platform: HostPlatform): NativeArchive? =
        published.firstOrNull { archive -> archive.kind == kind && archive.platform == platform }

    private val published: List<NativeArchive> = listOf(
        NativeArchive(
            kind = NativeRuntimeKind.LIB_VLC,
            platform = HostPlatform.WINDOWS_X64,
            version = "3.0.23",
            sha256 = "c159cf42bf11a1cebb2c820805443773e33414526d9aae13783497a7344a2e0f",
            marker = "libvlc.dll",
        ),
        NativeArchive(
            kind = NativeRuntimeKind.LIB_VLC,
            platform = HostPlatform.LINUX_X64,
            version = "3.0.23",
            sha256 = "3501b673bc6f87a563951e119f3746109080adc2277a63cb33bf2b7134140fef",
            marker = "libvlc.so.5",
        ),
    )
}
