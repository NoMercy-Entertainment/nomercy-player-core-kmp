// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.natives

import java.io.File
import java.io.InputStream
import java.net.URI
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

private const val HEX_BYTE: String = "%02x"
private const val CONNECT_TIMEOUT_MS: Int = 15_000
private const val READ_TIMEOUT_MS: Int = 120_000

// Getting one payload onto this machine and answering where it landed.
//
// Four places are tried, in this order, and the order is the design:
//
//   1. A directory somebody pointed us at. An air-gapped install, a distro
//      package that already carries libVLC, a developer testing a patched
//      build — all of them need a way to say "use this one", and none of them
//      should have to go through a download to get it.
//   2. A copy this machine already unpacked.
//   3. An archive on the classpath, for a consumer who would rather resolve the
//      payload through Maven than over HTTP at runtime.
//   4. The published archive, fetched once.
//
// What is deliberately NOT in that list is "whatever the operating system
// happens to have". That is the caller's fallback, applied after this returns
// nothing — never before — because a library whose behaviour depends on what
// was installed on the machine is a library that behaves differently for every
// user, which is the bug this exists to close.
internal object NativePayloadStore {

    fun install(kind: NativeRuntimeKind): NativeInstall {
        val staged: File? = staged(kind)
        if (staged != null) return NativeInstall.at(staged)

        val platform: HostPlatform? = HostPlatform.current()
        val archive: NativeArchive? = platform?.let { host -> NativeArchives.of(kind, host) }
        return archive?.let(::obtain) ?: NativeInstall.absent(whyNoArchive(kind, platform))
    }

    private fun whyNoArchive(kind: NativeRuntimeKind, platform: HostPlatform?): String =
        if (platform == null) {
            "no native payload is built for ${System.getProperty("os.name")} " +
                "on ${System.getProperty("os.arch")}"
        } else {
            "no ${kind.slug} payload has been published for ${platform.id} yet"
        }

    // An explicitly staged directory, taken as-is. Not digest-checked, on
    // purpose: its whole point is to be a build this project did not produce.
    private fun staged(kind: NativeRuntimeKind): File? {
        val slug: String = kind.slug
        val named: String? = System.getProperty("nomercy.player.natives.$slug.dir")
            ?: System.getenv("NOMERCY_PLAYER_${slug.uppercase()}_DIR")
        return named?.let(::File)?.takeIf { candidate -> candidate.isDirectory }
    }

    private fun obtain(archive: NativeArchive): NativeInstall {
        val target = File(cacheRoot(), "${archive.kind.slug}/${archive.installName}")
        val stamp = File(target, ".installed")
        if (stamp.isFile && File(target, archive.marker).exists()) return finished(archive, target)

        return runCatching { unpackOnce(archive, target, stamp) }
            .fold(
                onSuccess = { finished(archive, target) },
                onFailure = { failure ->
                    target.deleteRecursively()
                    NativeInstall.absent("the ${archive.kind.slug} payload could not be installed: ${failure.message}")
                },
            )
    }

    // Under a lock, because two processes starting together — a testbed and its
    // own test run, an app and its updater — would otherwise unpack sixty
    // megabytes over each other and both read a half-written plugin.
    private fun unpackOnce(archive: NativeArchive, target: File, stamp: File) {
        target.parentFile.mkdirs()
        val lock = File(target.parentFile, "${archive.installName}.lock")
        FileChannel.open(lock.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            .use { channel -> underLock(channel, archive, target, stamp) }
    }

    // The second process through here finds the stamp already written and does
    // nothing, which is why the check is inside the lock rather than outside it.
    private fun underLock(channel: FileChannel, archive: NativeArchive, target: File, stamp: File) {
        channel.lock().use {
            if (!stamp.isFile) unpack(archive, target, stamp)
        }
    }

    // The stamp is written last, deliberately. A machine that loses power
    // halfway through unpacking sixty megabytes has a directory full of plugins
    // and no stamp, and the next run treats that as absent and starts again —
    // where a stamp written first would leave it loading half a runtime forever.
    private fun unpack(archive: NativeArchive, target: File, stamp: File) {
        target.deleteRecursively()
        target.mkdirs()
        TarGz.extract(verified(archive).inputStream(), target)
        check(File(target, archive.marker).exists()) {
            "the archive did not contain ${archive.marker}"
        }
        stamp.writeText(archive.sha256)
    }

    // Read whole rather than streamed, so nothing is ever written to disk before
    // its digest has been checked. Sixty megabytes in memory once, on first run,
    // is cheaper than the class of bug where a partially verified archive is
    // already unpacked by the time the check fails.
    private fun verified(archive: NativeArchive): ByteArray {
        val bytes: ByteArray = fromClasspath(archive) ?: downloaded(archive)
        val digest: String = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> HEX_BYTE.format(byte) }
        check(digest == archive.sha256) {
            "digest mismatch: expected ${archive.sha256} but got $digest"
        }
        return bytes
    }

    private fun fromClasspath(archive: NativeArchive): ByteArray? =
        NativePayloadStore::class.java.getResourceAsStream(archive.resourcePath)
            ?.use(InputStream::readBytes)

    private fun downloaded(archive: NativeArchive): ByteArray {
        check(System.getProperty("nomercy.player.natives.offline") != "true") {
            "offline, and no ${archive.kind.slug} payload is staged or on the classpath"
        }
        val connection = URI(archive.url).toURL().openConnection()
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        return connection.getInputStream().use(InputStream::readBytes)
    }

    private fun finished(archive: NativeArchive, target: File): NativeInstall {
        PayloadFinishing.of(archive.kind).finish(target)
        return NativeInstall.at(target)
    }

    // Per user, never per application. Two NoMercy applications on one machine
    // share the same libVLC rather than unpacking sixty megabytes each, and a
    // per-application directory under Program Files would need an installer and
    // administrator rights to write.
    private fun cacheRoot(): File {
        System.getProperty("nomercy.player.natives.cache")?.let { override -> return File(override) }
        val home: String = System.getProperty("user.home")
        val os: String = System.getProperty("os.name").orEmpty().lowercase()
        return when {
            os.startsWith("windows") -> File(System.getenv("LOCALAPPDATA") ?: "$home/AppData/Local", "NoMercy/natives")
            os.startsWith("mac") -> File(home, "Library/Caches/tv.nomercy.player/natives")
            else -> File(System.getenv("XDG_CACHE_HOME") ?: "$home/.cache", "nomercy/player-natives")
        }
    }
}
