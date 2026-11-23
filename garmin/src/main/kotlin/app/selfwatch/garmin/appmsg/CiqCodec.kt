/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.garmin.appmsg

import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import app.selfwatch.model.watch.CiqValue
import java.io.ByteArrayOutputStream

/**
 * Monkey C serialized values, as carried by Connect IQ app messages. Big-endian.
 *
 * Layout: an optional string section (`AB CD AB CD`, u32 length, then strings as u16 length including a
 * terminating 0, UTF-8 bytes, 0x00), then the data section (`DA 7A DA 7A`, u32 length, values). Values are
 * a type byte and a body: null 0x00; sint32 0x01; float 0x02; string 0x03 (u32 offset into the string
 * section); array 0x05 (u32 count); bool 0x09 (u8); dictionary 0x0B (u32 count); sint64 0x0E; double 0x0F;
 * char 0x13 (u32); byte array 0x14 (u32 count, bytes). Containers are laid out breadth-first: a container
 * writes only its header, and its children follow after every value queued before them.
 *
 * Ref: Gadgetbridge 160beab41 service/devices/garmin/http/GarminJson.java (magics, type codes, breadth-first order,
 * float when exactly representable)
 */
public object CiqCodec {
    private val STRING_MAGIC = byteArrayOf(0xAB.toByte(), 0xCD.toByte(), 0xAB.toByte(), 0xCD.toByte())
    private val DATA_MAGIC = byteArrayOf(0xDA.toByte(), 0x7A, 0xDA.toByte(), 0x7A)
    private const val T_NULL = 0x00
    private const val T_SINT32 = 0x01
    private const val T_FLOAT = 0x02
    private const val T_STRING = 0x03
    private const val T_ARRAY = 0x05
    private const val T_BOOL = 0x09
    private const val T_MAP = 0x0B
    private const val T_SINT64 = 0x0E
    private const val T_DOUBLE = 0x0F
    private const val T_CHAR = 0x13
    private const val T_BYTE_ARRAY = 0x14

    /** Most values accepted when decoding (bounds memory on hostile input). */
    public const val MAX_VALUES: Int = 100_000

    /** Encodes [value]. */
    public fun encode(value: CiqValue): ByteArray {
        val offsets = LinkedHashMap<String, Int>()
        val strings = ByteArrayOutputStream()
        val data = ByteArrayOutputStream()
        val queue = ArrayDeque<CiqValue>()
        queue.addLast(value)
        while (queue.isNotEmpty()) {
            when (val v = queue.removeFirst()) {
                CiqValue.Null -> data.write(T_NULL)
                is CiqValue.Bool -> {
                    data.write(T_BOOL)
                    data.write(if (v.v) 1 else 0)
                }
                is CiqValue.Num -> if (v.v in Int.MIN_VALUE..Int.MAX_VALUE) {
                    data.write(T_SINT32)
                    u32(data, v.v.toInt())
                } else {
                    data.write(T_SINT64)
                    u64(data, v.v)
                }
                is CiqValue.Dbl -> if (!v.v.isNaN() && v.v.toFloat().toDouble() == v.v) {
                    data.write(T_FLOAT)
                    u32(data, v.v.toFloat().toRawBits())
                } else {
                    data.write(T_DOUBLE)
                    u64(data, v.v.toRawBits())
                }
                is CiqValue.Str -> {
                    data.write(T_STRING)
                    u32(data, offsets.getOrPut(v.v) { addString(strings, v.v) })
                }
                is CiqValue.Arr -> {
                    data.write(T_ARRAY)
                    u32(data, v.v.size)
                    queue.addAll(v.v)
                }
                is CiqValue.Dict -> {
                    data.write(T_MAP)
                    u32(data, v.v.size)
                    for ((k, x) in v.v) {
                        queue.addLast(k)
                        queue.addLast(x)
                    }
                }
            }
        }
        val out = ByteArrayOutputStream()
        if (strings.size() > 0) {
            out.write(STRING_MAGIC)
            u32(out, strings.size())
            strings.writeTo(out)
        }
        out.write(DATA_MAGIC)
        u32(out, data.size())
        data.writeTo(out)
        return out.toByteArray()
    }

    private fun addString(strings: ByteArrayOutputStream, s: String): Int {
        val at = strings.size()
        val b = s.toByteArray(Charsets.UTF_8)
        require(b.size + 1 <= 0xFFFF) { "string too long for Monkey C serialization" }
        strings.write((b.size + 1) shr 8)
        strings.write((b.size + 1) and 0xFF)
        strings.write(b)
        strings.write(0)
        return at
    }

    private fun u32(o: ByteArrayOutputStream, v: Int) {
        o.write(v ushr 24)
        o.write((v ushr 16) and 0xFF)
        o.write((v ushr 8) and 0xFF)
        o.write(v and 0xFF)
    }

    private fun u64(o: ByteArrayOutputStream, v: Long) {
        u32(o, (v ushr 32).toInt())
        u32(o, v.toInt())
    }

    /** Decodes serialized bytes; malformed input gives [SwError.BadInput], never an exception. */
    public fun decode(bytes: ByteArray): Outcome<CiqValue> = try {
        Outcome.Ok(Decoder(bytes).run())
    } catch (e: BadCiq) {
        Outcome.Fail(SwError.BadInput("Monkey C value: ${e.message}"))
    }

    private class BadCiq(msg: String) : Exception(msg)

    private class Decoder(private val b: ByteArray) {
        private var pos = 0
        private var end = b.size
        private val strings = HashMap<Int, String>()
        private var count = 0

        private fun need(n: Int) {
            if (n < 0 || pos + n > end) throw BadCiq("truncated at $pos")
        }

        private fun u8(): Int {
            need(1)
            return b[pos++].toInt() and 0xFF
        }

        private fun i32(): Int {
            need(4)
            val v = ((b[pos].toInt() and 0xFF) shl 24) or ((b[pos + 1].toInt() and 0xFF) shl 16) or
                ((b[pos + 2].toInt() and 0xFF) shl 8) or (b[pos + 3].toInt() and 0xFF)
            pos += 4
            return v
        }

        private fun i64(): Long = (i32().toLong() shl 32) or (i32().toLong() and 0xFFFF_FFFFL)

        private fun magic(m: ByteArray): Boolean {
            if (pos + 4 > end) return false
            for (i in 0 until 4) if (b[pos + i] != m[i]) return false
            pos += 4
            return true
        }

        // Placeholders for containers whose children are read later (breadth-first).
        private class ArrSlot(val n: Int, val items: ArrayList<CiqValue> = ArrayList())
        private class MapSlot(val n: Int, val items: LinkedHashMap<CiqValue, CiqValue> = LinkedHashMap())

        fun run(): CiqValue {
            if (magic(STRING_MAGIC)) {
                val len = i32()
                need(len)
                val start = pos
                val stop = pos + len
                while (pos < stop) {
                    val at = pos - start
                    if (pos + 2 > stop) throw BadCiq("bad string at $at")
                    val l = ((b[pos].toInt() and 0xFF) shl 8) or (b[pos + 1].toInt() and 0xFF)
                    pos += 2
                    if (l < 1 || pos + l > stop) throw BadCiq("bad string at $at")
                    strings[at] = String(b, pos, l - 1, Charsets.UTF_8)
                    pos += l
                }
            }
            if (!magic(DATA_MAGIC)) throw BadCiq("no data section")
            val len = i32()
            need(len)
            end = pos + len
            val root = value()
            val queue = ArrayDeque<Any>()
            queue.addLast(root)
            while (queue.isNotEmpty()) {
                when (val slot = queue.removeFirst()) {
                    is ArrSlot -> repeat(slot.n) {
                        val v = value()
                        if (v is ArrSlot || v is MapSlot) queue.addLast(v)
                        slot.items.add(resolve(v))
                    }
                    is MapSlot -> repeat(slot.n) {
                        val k = value()
                        if (k is ArrSlot || k is MapSlot) throw BadCiq("container as dictionary key")
                        val v = value()
                        if (v is ArrSlot || v is MapSlot) queue.addLast(v)
                        slot.items[resolve(k)] = resolve(v)
                    }
                }
            }
            return resolve(root)
        }

        private fun resolve(v: Any): CiqValue = when (v) {
            is ArrSlot -> CiqValue.Arr(v.items)
            is MapSlot -> CiqValue.Dict(v.items)
            else -> v as CiqValue
        }

        private fun value(): Any {
            if (++count > MAX_VALUES) throw BadCiq("too many values")
            return when (val t = u8()) {
                T_NULL -> CiqValue.Null
                T_BOOL -> CiqValue.Bool(u8().toByte() > 0) // signed: negative counts as false, as on the watch
                T_SINT32 -> CiqValue.Num(i32().toLong())
                T_SINT64 -> CiqValue.Num(i64())
                T_FLOAT -> CiqValue.Dbl(Float.fromBits(i32()).toDouble())
                T_DOUBLE -> CiqValue.Dbl(Double.fromBits(i64()))
                T_STRING -> {
                    val off = i32()
                    CiqValue.Str(strings[off] ?: throw BadCiq("unknown string offset $off"))
                }
                T_CHAR -> CiqValue.Str((i32() and 0xFFFF).toChar().toString())
                T_ARRAY -> {
                    val n = i32()
                    if (n < 0 || n > end - pos) throw BadCiq("array count $n")
                    ArrSlot(n)
                }
                T_MAP -> {
                    val n = i32()
                    if (n < 0 || n > (end - pos) / 2) throw BadCiq("dictionary count $n")
                    MapSlot(n)
                }
                T_BYTE_ARRAY -> {
                    val n = i32()
                    need(n)
                    val items = ArrayList<CiqValue>(n)
                    repeat(n) { items.add(CiqValue.Num(b[pos++].toLong())) }
                    CiqValue.Arr(items)
                }
                else -> throw BadCiq("unknown type 0x%02x".format(t))
            }
        }
    }
}
