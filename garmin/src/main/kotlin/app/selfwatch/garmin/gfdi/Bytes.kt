/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.garmin.gfdi

/** An immutable byte string with content equality, so messages holding bytes can be data classes. */
public class Bytes(bytes: ByteArray) {
    private val data: ByteArray = bytes.copyOf()

    /** Number of bytes. */
    public val size: Int get() = data.size

    /** A copy of the bytes. */
    public fun toByteArray(): ByteArray = data.copyOf()

    /** The byte at [i] as 0..255. */
    public operator fun get(i: Int): Int = data[i].toInt() and 0xFF

    override fun equals(other: Any?): Boolean = other is Bytes && data.contentEquals(other.data)

    override fun hashCode(): Int = data.contentHashCode()

    override fun toString(): String = hex(data)

    /** Constants and helpers. */
    public companion object {
        /** No bytes. */
        public val EMPTY: Bytes = Bytes(ByteArray(0))

        /** Lower-case hex of [b], for debug logs and test messages. */
        public fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

        /** Parses hex (spaces allowed) into bytes; for tests and constants. */
        public fun fromHex(s: String): ByteArray {
            val clean = s.filter { !it.isWhitespace() }
            require(clean.length % 2 == 0) { "odd hex length" }
            return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }
    }
}

/** Thrown by [ByteReader] when input is shorter than its layout says. Caught at message boundaries. */
public class MalformedException(message: String) : Exception(message)

/** Bounds-checked little-endian reader over [buf] (`from until to`). Overruns throw [MalformedException]. */
public class ByteReader(private val buf: ByteArray, from: Int = 0, private val to: Int = buf.size) {
    /** Current read position (absolute index into the array). */
    public var pos: Int = from
        private set

    /** Bytes left to read. */
    public val remaining: Int get() = to - pos

    private fun need(n: Int) {
        if (n < 0 || remaining < n) throw MalformedException("need $n bytes, have $remaining")
    }

    /** Unsigned 8-bit. */
    public fun u8(): Int {
        need(1)
        return buf[pos++].toInt() and 0xFF
    }

    /** Unsigned 16-bit little-endian. */
    public fun u16(): Int {
        need(2)
        val v = (buf[pos].toInt() and 0xFF) or ((buf[pos + 1].toInt() and 0xFF) shl 8)
        pos += 2
        return v
    }

    /** Signed 32-bit little-endian. */
    public fun i32(): Int {
        need(4)
        var v = 0
        for (i in 3 downTo 0) v = (v shl 8) or (buf[pos + i].toInt() and 0xFF)
        pos += 4
        return v
    }

    /** Unsigned 32-bit little-endian as a Long. */
    public fun u32(): Long = i32().toLong() and 0xFFFF_FFFFL

    /** Signed 64-bit little-endian. */
    public fun i64(): Long {
        need(8)
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (buf[pos + i].toLong() and 0xFF)
        pos += 8
        return v
    }

    /** [n] raw bytes. */
    public fun bytes(n: Int): ByteArray {
        need(n)
        val out = buf.copyOfRange(pos, pos + n)
        pos += n
        return out
    }

    /** Everything that is left. */
    public fun rest(): ByteArray = bytes(remaining)

    /** UTF-8 string prefixed by a u8 length (the GFDI string layout). */
    public fun str8(): String = String(bytes(u8()), Charsets.UTF_8)

    /** UTF-8 string prefixed by a u16 length. */
    public fun str16(): String = String(bytes(u16()), Charsets.UTF_8)
}

/** Growable little-endian writer. */
public class ByteWriter(initial: Int = 64) {
    private var buf = ByteArray(initial)

    /** Bytes written so far. */
    public var size: Int = 0
        private set

    private fun ensure(n: Int) {
        if (size + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + n))
    }

    /** Writes the low 8 bits of [v]. */
    public fun u8(v: Int): ByteWriter {
        ensure(1)
        buf[size++] = v.toByte()
        return this
    }

    /** Writes the low 16 bits of [v], little-endian. */
    public fun u16(v: Int): ByteWriter {
        ensure(2)
        buf[size++] = v.toByte()
        buf[size++] = (v shr 8).toByte()
        return this
    }

    /** Writes [v] as 32 bits little-endian. */
    public fun i32(v: Int): ByteWriter {
        ensure(4)
        for (i in 0 until 4) buf[size++] = (v shr (8 * i)).toByte()
        return this
    }

    /** Writes [v] as 64 bits little-endian. */
    public fun i64(v: Long): ByteWriter {
        ensure(8)
        for (i in 0 until 8) buf[size++] = (v shr (8 * i)).toByte()
        return this
    }

    /** Writes raw bytes. */
    public fun bytes(b: ByteArray): ByteWriter {
        ensure(b.size)
        b.copyInto(buf, size)
        size += b.size
        return this
    }

    /** Writes a UTF-8 string with a u8 length prefix; longer strings are cut at 255 bytes on a character boundary. */
    public fun str8(s: String): ByteWriter {
        val b = utf8Cut(s, 255)
        u8(b.size)
        return bytes(b)
    }

    /** Writes a UTF-8 string with a u16 length prefix. */
    public fun str16(s: String): ByteWriter {
        val b = utf8Cut(s, 0xFFFF)
        u16(b.size)
        return bytes(b)
    }

    /** Overwrites a u16 at [at] (for length fields filled in last). */
    public fun putU16At(at: Int, v: Int) {
        buf[at] = v.toByte()
        buf[at + 1] = (v shr 8).toByte()
    }

    /** A copy of the written bytes. */
    public fun toByteArray(): ByteArray = buf.copyOf(size)

    private companion object {
        fun utf8Cut(s: String, max: Int): ByteArray {
            val all = s.toByteArray(Charsets.UTF_8)
            if (all.size <= max) return all
            var end = max
            // Back up over UTF-8 continuation bytes so a character is never split.
            while (end > 0 && (all[end].toInt() and 0xC0) == 0x80) end--
            return all.copyOf(end)
        }
    }
}
