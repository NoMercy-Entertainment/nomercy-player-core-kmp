// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.natives

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val BLOCK = 512
private const val FIXED_MTIME = 1_700_000_000L
private const val OCTAL_DIGITS = 11
private const val MODE_DIGITS = 7
private const val OCTAL_RADIX = 8
private const val MILLIS = 1000

// The unpacker, against archives shaped like the ones it will actually be given.
//
// Every case here is one that has already gone wrong or would have gone
// unnoticed. The `./` entry made the extractor reject its own archive as a path
// traversal, and the failure read as a malicious payload rather than an
// off-by-one. A dropped timestamp invalidates the plugin cache the payload was
// built with, silently, and the only symptom is a slower launch nobody
// attributes to an unpacker.
class TarGzTest {

    @Test
    fun `accepts the archive's own root entry`() {
        val into: File = temporary()
        // GNU tar invoked as `tar -C dir -cf - .` opens with exactly this.
        TarGz.extract(archiveOf(directory("./"), file("./libvlc.dll", "binary")), into)

        assertTrue(File(into, "libvlc.dll").isFile)
    }

    @Test
    fun `restores the modification time the payload was built with`() {
        val into: File = temporary()
        TarGz.extract(archiveOf(file("plugins/libcodec.dll", "x")), into)

        // Seconds, not milliseconds: libVLC compares against st_mtime, and a
        // value rounded into the next second is a cache miss for that plugin.
        assertEquals(FIXED_MTIME, File(into, "plugins/libcodec.dll").lastModified() / MILLIS)
    }

    @Test
    fun `keeps a nested path nested`() {
        val into: File = temporary()
        TarGz.extract(archiveOf(file("plugins/access/libhttp_plugin.dll", "x")), into)

        assertTrue(File(into, "plugins/access/libhttp_plugin.dll").isFile)
    }

    @Test
    fun `refuses an entry that climbs out of its directory`() {
        val into: File = temporary()

        assertFailsWith<IOException> {
            TarGz.extract(archiveOf(file("../escaped.dll", "x")), into)
        }
        assertFalse(File(into.parentFile, "escaped.dll").exists())
    }

    private fun temporary(): File = File.createTempFile("targz", "").let { stub ->
        stub.delete()
        stub.mkdirs()
        stub.deleteOnExit()
        stub
    }

    // A minimal ustar writer. Every field is left NUL-terminated by the zeroed
    // block rather than by a terminator written into it, which keeps this file
    // free of literal control characters — one of those turned the source into
    // something git treated as binary.
    private fun header(name: String, size: Long, type: Char): ByteArray {
        val block = ByteArray(BLOCK)
        fun put(offset: Int, text: String) =
            text.toByteArray().copyInto(block, offset, 0, text.length)

        put(0, name)
        put(100, "0".repeat(MODE_DIGITS - 3) + "644")
        put(124, size.toString(OCTAL_RADIX).padStart(OCTAL_DIGITS, '0'))
        put(136, FIXED_MTIME.toString(OCTAL_RADIX).padStart(OCTAL_DIGITS, '0'))
        block[156] = type.code.toByte()
        put(257, "ustar")
        return block
    }

    private fun file(name: String, content: String): List<ByteArray> {
        val bytes: ByteArray = content.toByteArray()
        val padded = ByteArray((bytes.size + BLOCK - 1) / BLOCK * BLOCK)
        bytes.copyInto(padded)
        return listOf(header(name, bytes.size.toLong(), '0'), padded)
    }

    private fun directory(name: String): List<ByteArray> = listOf(header(name, 0, '5'))

    private fun archiveOf(vararg entries: List<ByteArray>): InputStream {
        val raw = ByteArrayOutputStream()
        // Two blocks of zeroes, because that is how a tar ends and the reader
        // looks for exactly that.
        val blocks: List<ByteArray> = entries.toList().flatten() + ByteArray(BLOCK * 2)
        GZIPOutputStream(raw).use { gzip -> blocks.forEach { block -> gzip.write(block) } }
        return raw.toByteArray().inputStream()
    }
}
