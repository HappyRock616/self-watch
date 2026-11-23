/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.garmin.link

import app.selfwatch.model.log.SwLog

/**
 * Consistent Overhead Byte Stuffing as Garmin uses it: every frame is wrapped in a leading AND a
 * trailing 0x00 delimiter (standard COBS has only the trailing one).
 *
 * Ref: Gadgetbridge 160beab41 service/devices/garmin/communicator/CobsCoDec.java (leading 0x00 quirk,
 * trailing 0x01 block after a final zero byte, 0xFF block = 254 bytes without an implied zero).
 */
public object Cobs {
    /** Encodes [data] into one delimited frame: `00 <cobs blocks> 00`. */
    public fun encode(data: ByteArray): ByteArray {
        val out = ArrayList<Byte>(data.size + data.size / 254 + 4)
        out.add(0)
        var i = 0
        var endedWithZero = false
        while (i < data.size) {
            var zero = i
            while (zero < data.size && data[zero].toInt() != 0) zero++
            endedWithZero = zero < data.size
            var start = i
            var run = zero - i
            while (run >= 0xFE) {
                out.add(0xFF.toByte())
                for (k in start until start + 0xFE) out.add(data[k])
                start += 0xFE
                run -= 0xFE
            }
            out.add((run + 1).toByte())
            for (k in start until start + run) out.add(data[k])
            i = zero + 1
        }
        if (endedWithZero || data.isEmpty()) out.add(1)
        out.add(0)
        return out.toByteArray()
    }

    /**
     * Decodes the bytes between two delimiters (no 0x00 inside). Returns null when the blocks are
     * inconsistent (a code byte claims more bytes than there are).
     */
    public fun decodeBody(body: ByteArray, from: Int = 0, to: Int = body.size): ByteArray? {
        val out = java.io.ByteArrayOutputStream(to - from)
        var i = from
        while (i < to) {
            val code = body[i].toInt() and 0xFF
            if (code == 0) return null
            i++
            val n = code - 1
            if (i + n > to) return null
            out.write(body, i, n)
            i += n
            if (code != 0xFF && i < to) out.write(0)
        }
        return out.toByteArray()
    }
}

/**
 * Streaming COBS frame splitter for one byte stream. Feed it notification payloads in order with
 * [feed]; it returns every complete frame they finish. A broken or oversized frame is dropped and
 * decoding resumes at the next delimiter, so a lost notification never wedges the link.
 *
 * @param maxFrame frames longer than this (encoded) are discarded (bounded memory).
 */
public class CobsDecoder(private val maxFrame: Int = 64 * 1024) {
    private val buf = java.io.ByteArrayOutputStream()
    private var overflow = false

    /** Feeds [bytes]; returns the decoded frames they complete (often none or one). */
    public fun feed(bytes: ByteArray): List<ByteArray> {
        var frames: MutableList<ByteArray>? = null
        for (b in bytes) {
            if (b.toInt() == 0) {
                if (buf.size() > 0 && !overflow) {
                    val body = buf.toByteArray()
                    val decoded = Cobs.decodeBody(body)
                    if (decoded == null) {
                        SwLog.w(TAG, "dropping malformed COBS frame (${body.size} bytes)")
                    } else {
                        (frames ?: ArrayList<ByteArray>().also { frames = it }).add(decoded)
                    }
                } else if (overflow) {
                    SwLog.w(TAG, "dropped oversized COBS frame")
                }
                buf.reset()
                overflow = false
            } else if (!overflow) {
                if (buf.size() >= maxFrame) {
                    overflow = true
                    buf.reset()
                } else {
                    buf.write(b.toInt())
                }
            }
        }
        return frames ?: emptyList()
    }

    /** Forgets any partial frame. */
    public fun reset() {
        buf.reset()
        overflow = false
    }

    private companion object {
        const val TAG = "garmin.cobs"
    }
}
