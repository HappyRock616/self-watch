/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.ble.log

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer

// Pure Kotlin on purpose (no Android imports): this file can move to core-model so that garmin's
// replay harness reads the same recordings.

/** One recorded Bluetooth event. [bytes] is the payload as sent or received. */
public class BleLogRecord(
    /** Wall-clock time in epoch milliseconds. */
    public val timeMs: Long,
    /** [BleLogFormat.DIR_OUT] or [BleLogFormat.DIR_IN]. Other values are kept as read. */
    public val direction: Int,
    /** Pipe index: opening order within the link session (0 = GFDI pipe, 1 = watch-app pipe). */
    public val pipe: Int,
    /** Payload bytes. */
    public val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean = other is BleLogRecord && other.timeMs == timeMs &&
        other.direction == direction && other.pipe == pipe && other.bytes.contentEquals(bytes)

    override fun hashCode(): Int = ((timeMs.hashCode() * 31 + direction) * 31 + pipe) * 31 + bytes.contentHashCode()

    override fun toString(): String = "BleLogRecord(t=$timeMs dir=$direction pipe=$pipe len=${bytes.size})"
}

/** The result of reading a recording: the whole records, plus how many bytes at the end were cut off. */
public class BleLogRead(
    /** Records in file order. */
    public val records: List<BleLogRecord>,
    /** Bytes after the last whole record (a crash mid-write leaves a partial record). 0 if none. */
    public val trailingBytes: Int,
)

/**
 * The capture file format (WP-05), shared with garmin's replay harness: a plain sequence of
 * records with no file header. Multi-byte fields are **big-endian** (as `java.io.DataOutputStream`
 * writes them).
 *
 * | Field | Size |
 * |---|---|
 * | time, epoch ms, big-endian | 8 bytes |
 * | direction: [DIR_OUT] (0) phone write to watch, [DIR_IN] (1) notification from watch | 1 byte |
 * | pipe index: opening order within the link session, 0 = GFDI pipe, 1 = watch-app pipe | 1 byte |
 * | payload length, unsigned, big-endian | 2 bytes |
 * | payload | length bytes |
 *
 * Only directions 0 and 1 are written. Readers keep other values as read so the format can grow.
 */
public object BleLogFormat {
    /** Phone to watch: a characteristic write. */
    public const val DIR_OUT: Int = 0

    /** Watch to phone: a notification. */
    public const val DIR_IN: Int = 1

    /** Bytes before the payload. */
    public const val HEADER_SIZE: Int = 12

    /** Longest payload a record can hold. */
    public const val MAX_PAYLOAD: Int = 0xFFFF

    /** Encodes one record. Payloads longer than [MAX_PAYLOAD] are rejected. */
    public fun encode(record: BleLogRecord): ByteArray {
        require(record.bytes.size <= MAX_PAYLOAD) { "payload of ${record.bytes.size} bytes" }
        return ByteBuffer.allocate(HEADER_SIZE + record.bytes.size)
            .putLong(record.timeMs)
            .put(record.direction.toByte())
            .put(record.pipe.toByte())
            .putShort(record.bytes.size.toShort())
            .put(record.bytes)
            .array()
    }

    /** Decodes a whole recording held in memory, tolerating a cut-off last record. */
    public fun decode(data: ByteArray): BleLogRead {
        val records = ArrayList<BleLogRecord>()
        val buf = ByteBuffer.wrap(data)
        while (buf.remaining() >= HEADER_SIZE) {
            val start = buf.position()
            val time = buf.getLong()
            val dir = buf.get().toInt() and 0xFF
            val pipe = buf.get().toInt() and 0xFF
            val len = buf.getShort().toInt() and 0xFFFF
            if (buf.remaining() < len) {
                buf.position(start)
                break
            }
            val bytes = ByteArray(len)
            buf.get(bytes)
            records += BleLogRecord(time, dir, pipe, bytes)
        }
        return BleLogRead(records, buf.remaining())
    }

    /** Reads records from [input] one at a time; stops quietly at a cut-off last record. */
    public fun read(input: InputStream): Sequence<BleLogRecord> = sequence {
        val data = DataInputStream(input)
        while (true) {
            val header = ByteArray(HEADER_SIZE)
            if (!readFully(data, header)) break
            val hb = ByteBuffer.wrap(header)
            val time = hb.getLong()
            val dir = hb.get().toInt() and 0xFF
            val pipe = hb.get().toInt() and 0xFF
            val len = hb.getShort().toInt() and 0xFFFF
            val bytes = ByteArray(len)
            if (!readFully(data, bytes)) break
            yield(BleLogRecord(time, dir, pipe, bytes))
        }
    }

    private fun readFully(input: DataInputStream, into: ByteArray): Boolean = try {
        input.readFully(into)
        true
    } catch (_: EOFException) {
        false
    }
}
