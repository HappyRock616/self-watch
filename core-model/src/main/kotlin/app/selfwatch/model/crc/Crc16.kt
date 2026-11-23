/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.model.crc

/**
 * The 16-bit CRC used by FIT files and the Garmin protocol (CRC-16/ARC: reflected polynomial 0xA001,
 * initial value 0, no final XOR). Processed a nibble at a time with a 16-entry table, as published in
 * the FIT protocol description.
 */
public object Crc16 {
    private val TABLE: IntArray = intArrayOf(
        0x0000, 0xCC01, 0xD801, 0x1400, 0xF001, 0x3C00, 0x2800, 0xE401,
        0xA001, 0x6C00, 0x7800, 0xB401, 0x5000, 0x9C01, 0x8801, 0x4400,
    )

    /** Feeds one byte [b] (only the low 8 bits are used) into the running CRC [crc]; returns the new CRC. */
    public fun update(crc: Int, b: Int): Int {
        var c = crc and 0xFFFF
        var tmp = TABLE[c and 0xF]
        c = (c shr 4) and 0x0FFF
        c = c xor tmp xor TABLE[b and 0xF]
        tmp = TABLE[c and 0xF]
        c = (c shr 4) and 0x0FFF
        return c xor tmp xor TABLE[(b shr 4) and 0xF]
    }

    /** CRC of `bytes[offset until offset + length]`, continuing from [initial] (0 for a fresh CRC). */
    public fun compute(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset, initial: Int = 0): Int {
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size) { "range out of bounds" }
        var crc = initial and 0xFFFF
        for (i in offset until offset + length) crc = update(crc, bytes[i].toInt() and 0xFF)
        return crc
    }
}
