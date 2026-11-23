/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.fit.io

import app.selfwatch.model.crc.Crc16
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * Writes a FIT file. Call [header] first, then [define] and [data] (or [compressed]) as needed, then
 * [finish], which writes the header with the final data size, the records and the file CRC to the
 * output. Records are buffered in memory until [finish] because the header holds their size.
 *
 * Values for [data]: `null` writes the base type's invalid value; a [Number] writes a number (floats for
 * float types); a [String] writes UTF-8 padded with zeros; a [List] fills an array field element by
 * element; a [ByteArray] is copied as is. Misuse (wrong value count, undefined local type) throws
 * [IllegalArgumentException], because it is a programming error.
 *
 * @param out where [finish] writes the file; not closed.
 * @param protocolVersion header protocol version byte (0x20 = 2.0).
 * @param profileVersion header profile version (2132 = 21.32).
 */
class FitWriter(
    private val out: OutputStream,
    private val protocolVersion: Int = 0x20,
    private val profileVersion: Int = 2132,
) {
    private class Def(val fields: List<FieldDef>, val dev: List<DevFieldDef>, val be: Boolean, val devTypes: IntArray)

    private val body = ByteArrayOutputStream()
    private val defs = arrayOfNulls<Def>(16)
    private val devTypes = HashMap<Int, Int>()
    private var started = false
    private var finished = false

    /** Starts the file. Must be called once, before anything else. */
    fun header() {
        require(!started) { "header() called twice" }
        started = true
    }

    /**
     * Writes a definition message for local message type [local] (0..15) and global message [global].
     * [devFields] adds developer fields; [bigEndian] writes the message and its data in big-endian order.
     */
    fun define(
        local: Int,
        global: Int,
        fields: List<FieldDef>,
        devFields: List<DevFieldDef> = emptyList(),
        bigEndian: Boolean = false,
    ) {
        require(started && !finished) { "call header() first" }
        require(local in 0..15) { "local type $local" }
        require(fields.size <= 255 && devFields.size <= 255) { "too many fields" }
        body.write((if (devFields.isEmpty()) 0x40 else 0x60) or local)
        body.write(0)
        body.write(if (bigEndian) 1 else 0)
        if (bigEndian) {
            body.write((global shr 8) and 0xFF); body.write(global and 0xFF)
        } else {
            body.write(global and 0xFF); body.write((global shr 8) and 0xFF)
        }
        body.write(fields.size)
        for (f in fields) {
            require(f.size in 0..255) { "field size ${f.size}" }
            body.write(f.num); body.write(f.size); body.write(f.baseType)
        }
        if (devFields.isNotEmpty()) {
            body.write(devFields.size)
            for (f in devFields) {
                body.write(f.num); body.write(f.size); body.write(f.devIndex)
            }
        }
        val dt = IntArray(devFields.size) { devTypes[FitRecord.devKey(devFields[it].devIndex, devFields[it].num)] ?: FitBaseType.BYTE }
        defs[local] = Def(fields, devFields, bigEndian, dt)
    }

    /**
     * Tells the writer the base type of a developer field (so its values encode correctly). Use together
     * with a `field_description` (206) message written through [data].
     */
    fun describeDevField(devIndex: Int, fieldNum: Int, baseType: Int) {
        devTypes[FitRecord.devKey(devIndex, fieldNum)] = baseType
    }

    /** Writes a data message with a normal header for local type [local]. */
    fun data(local: Int, values: List<Any?>, devValues: List<Any?> = emptyList()) {
        writeData(local and 0x0F, local, values, devValues)
    }

    /**
     * Writes a data message with a compressed timestamp header: [local] must be 0..3 and [offsetSec] is
     * the low 5 bits of the message's FIT time.
     */
    fun compressed(local: Int, offsetSec: Int, values: List<Any?>, devValues: List<Any?> = emptyList()) {
        require(local in 0..3) { "compressed headers allow local types 0..3" }
        writeData(0x80 or (local shl 5) or (offsetSec and 0x1F), local, values, devValues)
    }

    private fun writeData(headerByte: Int, local: Int, values: List<Any?>, devValues: List<Any?>) {
        require(started && !finished) { "call header() first" }
        val def = requireNotNull(defs[local]) { "local type $local not defined" }
        require(values.size == def.fields.size) { "expected ${def.fields.size} values, got ${values.size}" }
        require(devValues.isEmpty() || devValues.size == def.dev.size) { "developer value count" }
        body.write(headerByte)
        for (i in def.fields.indices) encode(def.fields[i].size, def.fields[i].baseType, values[i], def.be)
        for (i in def.dev.indices) encode(def.dev[i].size, def.devTypes[i], devValues.getOrNull(i), def.be)
    }

    private fun encode(size: Int, type: Int, value: Any?, be: Boolean) {
        val n = type and 0x1F
        if (n == 7) {
            val bytes = (value as? String)?.toByteArray(StandardCharsets.UTF_8) ?: ByteArray(0)
            val len = minOf(bytes.size, size - 1).coerceAtLeast(0)
            body.write(bytes, 0, len)
            repeat(size - len) { body.write(0) }
            return
        }
        if (value is ByteArray) {
            for (i in 0 until size) body.write(if (i < value.size) value[i].toInt() else 0xFF)
            return
        }
        val elem = FitBaseType.size(type)
        if (size < elem && (size == 1 || size == 2 || size == 4)) {
            // Undersized field (as some encoders write): an unsigned integer of the declared size.
            writeOne(when (size) { 1 -> 2; 2 -> 4; else -> 6 }, size, if (value is List<*>) value.firstOrNull() else value, be)
            return
        }
        val count = size / elem
        for (i in 0 until count) {
            val v = if (value is List<*>) value.getOrNull(i) else if (i == 0) value else null
            writeOne(n, elem, v, be)
        }
        repeat(size - count * elem) { body.write(0xFF) }
    }

    private fun writeOne(n: Int, elem: Int, v: Any?, be: Boolean) {
        val bits: Long = when {
            v == null -> invalidBits(n)
            n == 8 -> java.lang.Float.floatToRawIntBits((v as Number).toFloat()).toLong() and 0xFFFFFFFFL
            n == 9 -> java.lang.Double.doubleToRawLongBits((v as Number).toDouble())
            else -> (v as Number).toLong()
        }
        if (be) {
            for (i in elem - 1 downTo 0) body.write(((bits ushr (8 * i)) and 0xFF).toInt())
        } else {
            for (i in 0 until elem) body.write(((bits ushr (8 * i)) and 0xFF).toInt())
        }
    }

    private fun invalidBits(n: Int): Long = when (n) {
        1 -> 0x7F
        3 -> 0x7FFF
        5 -> 0x7FFFFFFF
        14 -> Long.MAX_VALUE
        10, 11, 12, 16 -> 0
        else -> -1L // all bits set (masked to the element size when written)
    }

    /** Writes the 14-byte header (with header CRC), the records and the file CRC to the output. */
    fun finish() {
        require(started && !finished) { "call header() first" }
        finished = true
        val data = body.toByteArray()
        val h = ByteArray(14)
        h[0] = 14
        h[1] = protocolVersion.toByte()
        h[2] = (profileVersion and 0xFF).toByte()
        h[3] = ((profileVersion shr 8) and 0xFF).toByte()
        for (i in 0 until 4) h[4 + i] = ((data.size ushr (8 * i)) and 0xFF).toByte()
        h[8] = '.'.code.toByte(); h[9] = 'F'.code.toByte(); h[10] = 'I'.code.toByte(); h[11] = 'T'.code.toByte()
        val hc = Crc16.compute(h, 0, 12)
        h[12] = (hc and 0xFF).toByte()
        h[13] = ((hc shr 8) and 0xFF).toByte()
        var crc = Crc16.compute(h)
        crc = Crc16.compute(data, initial = crc)
        out.write(h)
        out.write(data)
        out.write(crc and 0xFF)
        out.write((crc shr 8) and 0xFF)
        out.flush()
    }
}
