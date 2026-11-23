/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.interop.io

import java.io.BufferedReader
import java.io.Closeable
import java.io.Flushable
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Reader
import java.io.StringReader
import java.io.Writer

/** Thrown by [CsvReader] when a field exceeds the size limit (a sign of a binary or broken file). */
class CsvFormatException(message: String) : IOException(message)

/**
 * Streaming RFC 4180 CSV reader.
 *
 * - Quoted fields may hold the delimiter, `""` for a quote, and line breaks.
 * - Rows end at CR, LF or CRLF. Completely empty lines are skipped.
 * - A leading UTF-8 byte order mark is dropped.
 * - The delimiter (comma, semicolon or tab) is detected from the first line unless given.
 * - Lenient: text after a closing quote is kept; an unterminated quote runs to the end of input.
 *
 * @param maxFieldChars a field longer than this fails with [CsvFormatException].
 */
class CsvReader(reader: Reader, delimiter: Char? = null, private val maxFieldChars: Int = 1 shl 20) : Closeable {
    private val input: BufferedReader = if (reader is BufferedReader) reader else BufferedReader(reader, 1 shl 16)

    /** The delimiter in use. */
    val delimiter: Char

    private var peeked: Int = NONE

    init {
        input.mark(SAMPLE_CHARS + 1)
        val first = input.read()
        if (first != BOM && first != -1) {
            input.reset()
        } else if (first == BOM) {
            input.mark(SAMPLE_CHARS + 1)
        }
        this.delimiter = delimiter ?: run {
            val buf = CharArray(SAMPLE_CHARS)
            var n = 0
            while (n < buf.size) {
                val r = input.read(buf, n, buf.size - n)
                if (r <= 0) break
                n += r
            }
            input.reset()
            detectDelimiter(String(buf, 0, n))
        }
    }

    private fun next(): Int {
        if (peeked != NONE) {
            val c = peeked
            peeked = NONE
            return c
        }
        return input.read()
    }

    /** The next row, or null at the end of input. */
    fun readRow(): List<String>? {
        while (true) {
            val row = readRawRow() ?: return null
            if (row.size == 1 && row[0].isEmpty()) continue
            return row
        }
    }

    private fun readRawRow(): List<String>? {
        var c = next()
        if (c == -1) return null
        val fields = ArrayList<String>()
        val sb = StringBuilder()
        var quoted = false
        var fieldStart = true
        while (true) {
            if (quoted) {
                when (c) {
                    -1 -> {
                        fields.add(sb.toString())
                        return fields
                    }
                    '"'.code -> {
                        val n = next()
                        if (n == '"'.code) {
                            append(sb, '"')
                        } else {
                            quoted = false
                            c = n
                            continue
                        }
                    }
                    else -> append(sb, c.toChar())
                }
            } else {
                when {
                    c == -1 -> {
                        fields.add(sb.toString())
                        return fields
                    }
                    c == delimiter.code -> {
                        fields.add(sb.toString())
                        sb.setLength(0)
                        fieldStart = true
                        c = next()
                        continue
                    }
                    c == '\n'.code -> {
                        fields.add(sb.toString())
                        return fields
                    }
                    c == '\r'.code -> {
                        val n = next()
                        if (n != '\n'.code && n != -1) peeked = n
                        fields.add(sb.toString())
                        return fields
                    }
                    c == '"'.code && fieldStart && sb.isEmpty() -> quoted = true
                    else -> append(sb, c.toChar())
                }
            }
            fieldStart = false
            c = next()
        }
    }

    private fun append(sb: StringBuilder, ch: Char) {
        if (sb.length >= maxFieldChars) throw CsvFormatException("field too long")
        sb.append(ch)
    }

    /** All remaining rows. */
    fun readAll(): List<List<String>> {
        val out = ArrayList<List<String>>()
        while (true) out.add(readRow() ?: return out)
    }

    override fun close() {
        input.close()
    }

    /** Factories and helpers. */
    companion object {
        private const val NONE = -2
        private const val BOM = 0xFEFF
        private const val SAMPLE_CHARS = 8192

        /** A reader over UTF-8 [input]. */
        fun open(input: InputStream, delimiter: Char? = null): CsvReader =
            CsvReader(InputStreamReader(input, Charsets.UTF_8), delimiter)

        /** Parses all of [text]. */
        fun parse(text: String, delimiter: Char? = null): List<List<String>> =
            CsvReader(StringReader(text), delimiter).use { it.readAll() }

        /** The most frequent of comma, semicolon and tab outside quotes on the first line (comma on a tie). */
        fun detectDelimiter(sample: String): Char {
            var commas = 0
            var semis = 0
            var tabs = 0
            var quoted = false
            for (ch in sample) {
                when {
                    ch == '"' -> quoted = !quoted
                    quoted -> {}
                    ch == '\n' || ch == '\r' -> break
                    ch == ',' -> commas++
                    ch == ';' -> semis++
                    ch == '\t' -> tabs++
                }
            }
            return when {
                semis > commas && semis >= tabs -> ';'
                tabs > commas && tabs > semis -> '\t'
                else -> ','
            }
        }
    }
}

/**
 * RFC 4180 CSV writer: CRLF line ends, fields quoted when they hold the delimiter, a quote, a line
 * break, or leading/trailing spaces. UTF-8, with a byte order mark by default so spreadsheet apps
 * read accents correctly.
 *
 * [flush] writes buffered text without closing the stream (use it when the stream is a zip entry).
 */
class CsvWriter(private val out: Writer, private val delimiter: Char = ',') : Closeable, Flushable {
    /** Writes UTF-8 to [stream], starting with a byte order mark when [bom] is true. */
    constructor(stream: OutputStream, bom: Boolean = true, delimiter: Char = ',') :
        this(OutputStreamWriter(stream, Charsets.UTF_8).buffered(1 shl 16), delimiter) {
        if (bom) out.write(0xFEFF)
    }

    /** Writes one row; null values become empty fields. */
    fun row(values: List<String?>) {
        values.forEachIndexed { i, v ->
            if (i > 0) out.write(delimiter.code)
            if (v != null) out.write(quote(v))
        }
        out.write("\r\n")
    }

    /** Writes one row. */
    fun row(vararg values: String?) {
        row(values.asList())
    }

    private fun quote(v: String): String {
        val needs = v.any { it == delimiter || it == '"' || it == '\n' || it == '\r' } ||
            (v.isNotEmpty() && (v.first() == ' ' || v.last() == ' '))
        return if (needs) "\"" + v.replace("\"", "\"\"") + "\"" else v
    }

    override fun flush() {
        out.flush()
    }

    override fun close() {
        out.close()
    }
}
