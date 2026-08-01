// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.natives

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.zip.GZIPInputStream

private const val BLOCK: Int = 512
private const val NAME_LENGTH: Int = 100
private const val MODE_OFFSET: Int = 100
private const val SIZE_OFFSET: Int = 124
private const val MTIME_OFFSET: Int = 136
private const val TYPE_OFFSET: Int = 156
private const val LINK_OFFSET: Int = 157
private const val PREFIX_OFFSET: Int = 345
private const val PREFIX_LENGTH: Int = 155
private const val OCTAL_FIELD: Int = 12
private const val MODE_FIELD: Int = 8
private const val OCTAL_RADIX: Int = 8
private const val COPY_BUFFER: Int = 65_536
private const val OWNER_EXECUTE: Int = 64
private const val MILLIS_PER_SECOND: Long = 1000L

// tar pads its fixed-width text fields with this, and a reader that trims
// whitespace instead keeps them: a plugin name would carry ninety invisible
// bytes and the file would be written under a name nothing can open.
private val PAD: Char = Char.MIN_VALUE

private const val TYPE_FILE: Char = '0'
private val TYPE_FILE_OLD: Char = Char.MIN_VALUE
private const val TYPE_DIRECTORY: Char = '5'
private const val TYPE_SYMLINK: Char = '2'
private const val TYPE_LONG_NAME: Char = 'L'

// Unpacking a native payload, in the JVM, with the two properties that matter.
//
// tar rather than zip because a payload carries things zip cannot describe: the
// executable bit on a helper binary, and the symlinks a Linux or macOS shared
// library ships as. Written here rather than taken from a library because the
// alternative is putting an archive library on every consumer's classpath to
// read one archive once, on first run, and never again.
//
// Timestamps are restored, and that is not tidiness. libVLC records each
// plugin's modification time in its plugin cache and rechecks it on load: an
// extractor that stamps every file with "now" invalidates the cache it just
// unpacked, and every launch afterwards pays for a full scan of the plugin
// directory — the exact cost the cache exists to remove.
internal object TarGz {

    @Throws(IOException::class)
    fun extract(source: InputStream, into: File) {
        GZIPInputStream(source).use { stream -> entries(stream, into) }
    }

    private fun entries(stream: InputStream, into: File) {
        var pendingLongName: String? = null
        var header: ByteArray? = readBlock(stream)
        // Two blocks of zeroes are how a tar says it is finished, and a reader
        // that keeps going past them reads the padding as a header.
        while (header != null && header.any { byte -> byte != 0.toByte() }) {
            val entry: TarEntry = TarEntry.of(header, pendingLongName)
            pendingLongName = longNameIn(entry, stream)
            if (pendingLongName == null) write(entry, stream, into)
            header = readBlock(stream)
        }
    }

    // A GNU long-name entry is not a file: it is the name of the file in the
    // NEXT entry, carried in a body because the header field is a hundred bytes.
    private fun longNameIn(entry: TarEntry, stream: InputStream): String? =
        if (entry.type == TYPE_LONG_NAME) readString(stream, entry.size) else null

    private fun write(entry: TarEntry, stream: InputStream, into: File) {
        val target: File = resolveSafely(into, entry.name)
        when (entry.type) {
            TYPE_DIRECTORY -> target.mkdirs()
            TYPE_SYMLINK -> link(target, entry.link)
            TYPE_FILE, TYPE_FILE_OLD -> file(target, entry, stream)
            else -> skip(stream, entry.size + padding(entry.size))
        }
        if (entry.type != TYPE_SYMLINK) target.setLastModified(entry.mtime * MILLIS_PER_SECOND)
    }

    private fun file(target: File, entry: TarEntry, stream: InputStream) {
        target.parentFile?.mkdirs()
        target.outputStream().use { output -> copy(stream, output, entry.size) }
        skip(stream, padding(entry.size))
        if (entry.mode and OWNER_EXECUTE != 0) target.setExecutable(true, true)
    }

    // A payload built on Windows has no symlinks, and a Windows machine without
    // developer mode cannot create one — so the link becomes a copy of what it
    // pointed at. Same bytes under the same name, at the cost of the disk space
    // the link was saving.
    private fun link(target: File, to: String) {
        target.parentFile?.mkdirs()
        target.delete()
        runCatching { Files.createSymbolicLink(target.toPath(), File(to).toPath()) }
            .onFailure {
                File(target.parentFile, to)
                    .takeIf { source -> source.isFile }
                    ?.copyTo(target, overwrite = true)
            }
    }

    // An archive entry naming ../../something is how an unpacker becomes an
    // arbitrary write. These archives are ours and digest-checked; this is still
    // here, because "the input is trusted" is the sentence in front of every one
    // of these.
    private fun resolveSafely(into: File, name: String): File {
        val target = File(into, name)
        val root: String = into.canonicalPath
        val path: String = target.canonicalPath
        // Equal as well as beneath. A tar built with `-C dir .` opens with an
        // entry for `./` itself, and a check that only accepted strictly deeper
        // paths rejected the archive's own root as an escape attempt — which
        // read, in the failure, as a malicious payload rather than an off-by-one.
        if (path != root && !path.startsWith(root + File.separator)) {
            throw IOException("archive entry escapes its directory: $name")
        }
        return target
    }

    // A gzip stream hands out whatever it has decompressed, which is rarely a
    // whole block, so a single read is not a header.
    private fun readBlock(stream: InputStream): ByteArray? {
        val block = ByteArray(BLOCK)
        var read = 0
        var count: Int = stream.read(block, 0, BLOCK)
        while (count > 0 && read + count < BLOCK) {
            read += count
            count = stream.read(block, read, BLOCK - read)
        }
        return if (count < 0 && read == 0) null else block
    }

    private fun copy(stream: InputStream, output: OutputStream, size: Long) {
        val buffer = ByteArray(COPY_BUFFER)
        var remaining: Long = size
        while (remaining > 0) {
            val count: Int = stream.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (count < 0) throw IOException("archive ended inside an entry")
            output.write(buffer, 0, count)
            remaining -= count
        }
    }

    private fun readString(stream: InputStream, size: Long): String {
        val bytes = ByteArray(size.toInt())
        var read = 0
        while (read < bytes.size) {
            val count: Int = stream.read(bytes, read, bytes.size - read)
            if (count < 0) throw IOException("archive ended inside a long name")
            read += count
        }
        skip(stream, padding(size))
        return String(bytes, Charsets.UTF_8).substringBefore(PAD)
    }

    private fun skip(stream: InputStream, count: Long) {
        var remaining: Long = count
        while (remaining > 0) {
            val skipped: Long = stream.skip(remaining)
            remaining -= if (skipped > 0) skipped else oneByte(stream)
        }
    }

    // InflaterInputStream.skip can legally answer zero with data still to come,
    // so a loop that only trusts skip spins forever. Reading one byte makes
    // progress; a stream that has ended consumes the rest of the count so the
    // loop finishes rather than spinning on end-of-input.
    private fun oneByte(stream: InputStream): Long =
        if (stream.read() < 0) Long.MAX_VALUE else 1L

    private fun padding(size: Long): Long = (BLOCK - size % BLOCK) % BLOCK

    private class TarEntry(
        val name: String,
        val link: String,
        val size: Long,
        val mtime: Long,
        val mode: Int,
        val type: Char,
    ) {
        companion object {
            fun of(header: ByteArray, longName: String?): TarEntry = TarEntry(
                name = longName ?: joinName(header),
                link = text(header, LINK_OFFSET, NAME_LENGTH),
                size = octal(header, SIZE_OFFSET, OCTAL_FIELD),
                mtime = octal(header, MTIME_OFFSET, OCTAL_FIELD),
                mode = octal(header, MODE_OFFSET, MODE_FIELD).toInt(),
                type = header[TYPE_OFFSET].toInt().toChar(),
            )

            // ustar splits a long path across a prefix field and the name field,
            // and a reader that only looks at the name silently unpacks three
            // hundred plugins into one flat directory.
            private fun joinName(header: ByteArray): String {
                val name: String = text(header, 0, NAME_LENGTH)
                val prefix: String = text(header, PREFIX_OFFSET, PREFIX_LENGTH)
                return if (prefix.isEmpty()) name else "$prefix/$name"
            }

            private fun text(header: ByteArray, offset: Int, length: Int): String =
                String(header, offset, length, Charsets.UTF_8).substringBefore(PAD).trim()

            private fun octal(header: ByteArray, offset: Int, length: Int): Long {
                val raw: String = text(header, offset, length).takeWhile { digit -> digit in '0'..'7' }
                return raw.toLongOrNull(OCTAL_RADIX) ?: 0L
            }
        }
    }
}
