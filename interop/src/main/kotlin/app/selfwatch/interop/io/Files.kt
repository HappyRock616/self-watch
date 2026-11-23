/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.interop.io

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.GZIPInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Thrown when an input is larger than a reader allows (zip bombs, wrong files). */
class TooLargeException(message: String) : IOException(message)

/** Text decoding and bounded reading. */
object Texts {
    /** Default limit for a single file read into memory. */
    const val MAX_FILE_BYTES: Int = 64 shl 20

    /** Reads all of [input], failing with [TooLargeException] beyond [max] bytes. */
    fun readBounded(input: InputStream, max: Int = MAX_FILE_BYTES): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1 shl 16)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > max) throw TooLargeException("file larger than $max bytes")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /**
     * Decodes [bytes] as UTF-8 (dropping a byte order mark); if that fails, as Windows-1252, the usual
     * encoding of old text files such as Meal-Master archives.
     */
    fun decode(bytes: ByteArray): String {
        var start = 0
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) start = 3
        val utf8 = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            utf8.decode(ByteBuffer.wrap(bytes, start, bytes.size - start)).toString()
        } catch (_: CharacterCodingException) {
            String(bytes, start, bytes.size - start, legacy())
        }
    }

    private fun legacy(): Charset = try {
        Charset.forName("windows-1252")
    } catch (_: IllegalArgumentException) {
        Charsets.ISO_8859_1
    }

    /** True when [text] looks like binary data: a NUL, or more than 1 % control characters other than tab and line breaks. */
    fun looksBinary(text: String): Boolean {
        if (text.isEmpty()) return false
        var controls = 0
        for (c in text) {
            if (c.code == 0) return true
            if (c < ' ' && c != '\t' && c != '\n' && c != '\r') controls++
        }
        return controls * 100 > text.length
    }

    /** Lower-case file extension of [name] without the dot, or "". */
    fun extension(name: String): String = name.substringAfterLast('/').substringAfterLast('.', "").lowercase()

    /** The part of [name] after the last slash. */
    fun baseName(name: String): String = name.substringAfterLast('/').substringAfterLast('\\')

    /** [head] decoded leniently as UTF-8 for sniffing. */
    fun headText(head: ByteArray): String = String(head, Charsets.UTF_8).trimStart(0xFEFF.toChar())
}

/** One file from a zip, in memory. */
class ZipItem(val name: String, val bytes: ByteArray) {
    /** A source over this entry. */
    fun source(): app.selfwatch.interop.api.InputSource = app.selfwatch.interop.api.InputSource.ofBytes(name, bytes)
}

/** Zip and gzip helpers with limits, so a hostile archive can't exhaust memory. */
object Zips {
    /** Default limit per entry. */
    const val MAX_ENTRY_BYTES: Int = 64 shl 20

    /** Default limit for all entries together. */
    const val MAX_TOTAL_BYTES: Long = 512L shl 20

    /** Default limit on the number of entries. */
    const val MAX_ENTRIES: Int = 20_000

    /** True when [head] starts with a zip local file header. */
    fun isZip(head: ByteArray): Boolean =
        head.size >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() && head[2] == 3.toByte() && head[3] == 4.toByte()

    /** True when [head] starts with the gzip magic. */
    fun isGzip(head: ByteArray): Boolean = head.size >= 2 && head[0] == 0x1F.toByte() && head[1] == 0x8B.toByte()

    /**
     * Names of the entries whose local headers lie within [head] (the first bytes of a zip), for
     * sniffing without reading the whole archive.
     */
    fun namesInHead(head: ByteArray): List<String> {
        val names = ArrayList<String>()
        var i = 0
        while (i + 30 <= head.size) {
            if (head[i] == 'P'.code.toByte() && head[i + 1] == 'K'.code.toByte() && head[i + 2] == 3.toByte() && head[i + 3] == 4.toByte()) {
                val nameLen = u16(head, i + 26)
                val extraLen = u16(head, i + 28)
                if (i + 30 + nameLen <= head.size) {
                    names.add(String(head, i + 30, nameLen, Charsets.UTF_8))
                    val compressed = u32(head, i + 18)
                    val flags = u16(head, i + 6)
                    // Skip the data when its size is known; else scan on byte by byte.
                    i += if (flags and 0x08 == 0 && compressed in 0..head.size.toLong()) {
                        (30 + nameLen + extraLen + compressed).toInt()
                    } else {
                        30 + nameLen
                    }
                    continue
                }
                break
            }
            i++
        }
        return names
    }

    private fun u16(b: ByteArray, at: Int): Int = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, at: Int): Long =
        (u16(b, at).toLong()) or (u16(b, at + 2).toLong() shl 16)

    /**
     * Reads every file entry of the zip [input] into memory (directories skipped). Fails with
     * [TooLargeException] beyond the limits and with an [IOException] on a broken archive.
     */
    fun readAll(
        input: InputStream,
        maxEntryBytes: Int = MAX_ENTRY_BYTES,
        maxTotalBytes: Long = MAX_TOTAL_BYTES,
        maxEntries: Int = MAX_ENTRIES,
    ): List<ZipItem> {
        val items = ArrayList<ZipItem>()
        var total = 0L
        ZipInputStream(input).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                if (e.isDirectory) continue
                if (items.size >= maxEntries) throw TooLargeException("too many entries")
                val bytes = Texts.readBounded(zin, maxEntryBytes)
                total += bytes.size
                if (total > maxTotalBytes) throw TooLargeException("archive too large")
                items.add(ZipItem(e.name, bytes))
            }
        }
        return items
    }

    /** Reads [bytes] as a zip. */
    fun readAll(bytes: ByteArray): List<ZipItem> = readAll(ByteArrayInputStream(bytes))

    /** Decompresses gzip [bytes], at most [max] bytes out. */
    fun gunzip(bytes: ByteArray, max: Int = MAX_ENTRY_BYTES): ByteArray =
        GZIPInputStream(ByteArrayInputStream(bytes)).use { Texts.readBounded(it, max) }

    /**
     * Writes a zip to [out]; [block] adds entries with [ZipBuilder.entry]. [out] is finished but not
     * closed.
     */
    inline fun write(out: OutputStream, block: ZipBuilder.() -> Unit) {
        val zout = ZipOutputStream(out)
        ZipBuilder(zout).block()
        zout.finish()
        zout.flush()
    }
}

/** Adds entries to a zip being written by [Zips.write]. */
class ZipBuilder @PublishedApi internal constructor(@PublishedApi internal val zout: ZipOutputStream) {
    private val names = HashSet<String>()

    /**
     * Adds the entry [name] (made unique by appending ` (2)`, ` (3)`… before the extension if it was
     * already used) and lets [write] fill it. Returns the name used. Closing the stream passed to
     * [write] only flushes it.
     */
    inline fun entry(name: String, write: (OutputStream) -> Unit): String {
        val unique = unique(name)
        zout.putNextEntry(ZipEntry(unique))
        write(nonClosing())
        zout.closeEntry()
        return unique
    }

    /** A view of the zip stream whose close only flushes. */
    @PublishedApi
    internal fun nonClosing(): OutputStream = NonClosing(zout)

    /** [name], or a numbered variant if it was already used. */
    @PublishedApi
    internal fun unique(name: String): String {
        if (names.add(name)) return name
        val dot = name.lastIndexOf('.').takeIf { it > name.lastIndexOf('/') } ?: name.length
        var n = 2
        while (true) {
            val candidate = name.substring(0, dot) + " ($n)" + name.substring(dot)
            if (names.add(candidate)) return candidate
            n++
        }
    }

    private class NonClosing(private val out: OutputStream) : OutputStream() {
        override fun write(b: Int) = out.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
        override fun flush() = out.flush()
        override fun close() = out.flush()
    }
}
