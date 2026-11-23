/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.fit.io

import app.selfwatch.model.crc.Crc16
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import java.io.IOException
import java.io.InputStream

/**
 * Streaming FIT reader. Reads [input] once, front to back, with a fixed-size buffer; never loads the
 * whole file. Memory use is bounded no matter what the input holds (at most 16 definitions of 255
 * fields and one message of at most 130 KB at a time).
 *
 * **Error policy**
 * - Structural damage (not a FIT file, a record past the declared data size, a data message for an
 *   undefined local type, a bad architecture byte, input ending early, a file larger than [maxBytes])
 *   returns [Outcome.Fail] with [SwError.BadInput]. Messages before the damage were already passed to
 *   the callback, so callers that store data should collect first and only store on [Outcome.Ok].
 * - A wrong header CRC or file CRC on an otherwise complete file is **not** a failure: every message is
 *   read and [FitSummary.crcOk] is false. Some third-party tools write wrong CRCs, and a bit flip in one
 *   value should not lose a whole day. Callers that need strictness check `crcOk`.
 * - Unknown messages, fields and base types are passed through or skipped, never errors.
 * - Several FIT files chained back to back are read in turn; bytes after the last file that do not form
 *   a FIT header are ignored.
 * - Exceptions thrown by the callback itself propagate unchanged.
 *
 * @param input the FIT bytes; not closed by the reader.
 * @param maxBytes the most bytes the reader will accept; bigger files fail before their records are read.
 */
class FitReader(private val input: InputStream, private val maxBytes: Long = 64L shl 20) {

    private class FormatError(msg: String) : Exception(msg)
    private class CallbackError(val original: Throwable) : Exception(original)

    private class LocalDef(
        val global: Int,
        val bigEndian: Boolean,
        val nums: IntArray,
        val sizes: IntArray,
        val types: IntArray,
        val devNums: IntArray,
        val devSizes: IntArray,
        val devIdx: IntArray,
        val total: Int,
    )

    private val src = Source(input)
    private val defs = arrayOfNulls<LocalDef>(16)
    private val devTypes = HashMap<Int, Int>()
    private var buf = ByteArray(1024)
    private var lastFitTime: Long? = null

    private var fileType: Int? = null
    private var manufacturer: Int? = null
    private var product: Int? = null
    private var records = 0
    private var crcOk = true
    private var seenFileId = false

    /**
     * Reads the input and calls [onRecord] for every data message, in file order.
     * Call once per reader.
     */
    fun read(onRecord: (FitRecord) -> Unit): Outcome<FitSummary> {
        return try {
            readFile(onRecord)
            while (src.peek() >= 12) {
                if (!readNextChained(onRecord)) break
            }
            Outcome.Ok(FitSummary(fileType, manufacturer, product, records, crcOk))
        } catch (e: CallbackError) {
            throw e.original
        } catch (e: FormatError) {
            SwLog.w(TAG, "bad FIT input: ${e.message}")
            Outcome.Fail(SwError.BadInput(e.message ?: "bad FIT file"))
        } catch (e: IOException) {
            SwLog.w(TAG, "FIT read failed", e)
            Outcome.Fail(SwError.Io(e.message ?: "read failed"))
        } catch (e: RuntimeException) {
            // A parsing bug must not crash the app on a corrupt file; report it as bad input.
            SwLog.e(TAG, "FIT parser error", e)
            Outcome.Fail(SwError.BadInput("parser error: ${e.javaClass.simpleName}"))
        }
    }

    /** Reads a further chained file if the next bytes form a FIT header; false to stop. */
    private fun readNextChained(onRecord: (FitRecord) -> Unit): Boolean {
        if (!src.peekIsFitHeader()) return false
        defs.fill(null)
        readFile(onRecord)
        return true
    }

    private fun readFile(onRecord: (FitRecord) -> Unit) {
        src.crc = 0
        val headerSize = src.u8()
        if (headerSize < 12) throw FormatError("header size $headerSize")
        val h = ByteArray(headerSize - 1)
        src.readFully(h, 0, h.size)
        if (h[7] != '.'.code.toByte() || h[8] != 'F'.code.toByte() || h[9] != 'I'.code.toByte() ||
            h[10] != 'T'.code.toByte()
        ) {
            throw FormatError("missing .FIT signature")
        }
        val dataSize = FitBaseType.rd(h, 3, 4, false)
        if (headerSize >= 14) {
            val stored = FitBaseType.rd(h, 11, 2, false).toInt()
            if (stored != 0) {
                var c = Crc16.update(0, headerSize)
                for (i in 0 until 11) c = Crc16.update(c, h[i].toInt())
                if (c != stored) {
                    SwLog.w(TAG, "FIT header CRC mismatch")
                    crcOk = false
                }
            }
        }
        if (src.consumed + dataSize + 2 > maxBytes) throw FormatError("file larger than maxBytes ($maxBytes)")
        val end = src.consumed + dataSize
        src.limit = end
        while (src.consumed < end) readRecord(onRecord)
        src.limit = Long.MAX_VALUE
        val computed = src.crc
        val stored = src.u8() or (src.u8() shl 8)
        if (stored != computed) {
            SwLog.w(TAG, "FIT file CRC mismatch")
            crcOk = false
        }
    }

    private fun readRecord(onRecord: (FitRecord) -> Unit) {
        val header = src.u8()
        if (header and 0x80 != 0) {
            val local = (header shr 5) and 0x03
            val ref = lastFitTime
            val t = if (ref != null) FitTime.fromCompressed(ref, header and 0x1F) else null
            if (t != null) lastFitTime = t
            readData(local, t, onRecord)
            return
        }
        val local = header and 0x0F
        if (header and 0x40 != 0) {
            readDefinition(local, header and 0x20 != 0)
        } else {
            readData(local, null, onRecord)
        }
    }

    private fun readDefinition(local: Int, hasDev: Boolean) {
        src.u8() // reserved
        val arch = src.u8()
        if (arch > 1) throw FormatError("architecture $arch")
        val be = arch == 1
        val g0 = src.u8()
        val g1 = src.u8()
        val global = if (be) (g0 shl 8) or g1 else (g1 shl 8) or g0
        val n = src.u8()
        val nums = IntArray(n)
        val sizes = IntArray(n)
        val types = IntArray(n)
        var total = 0
        for (i in 0 until n) {
            nums[i] = src.u8()
            sizes[i] = src.u8()
            types[i] = src.u8()
            total += sizes[i]
        }
        val nd = if (hasDev) src.u8() else 0
        val devNums = IntArray(nd)
        val devSizes = IntArray(nd)
        val devIdx = IntArray(nd)
        for (i in 0 until nd) {
            devNums[i] = src.u8()
            devSizes[i] = src.u8()
            devIdx[i] = src.u8()
            total += devSizes[i]
        }
        defs[local] = LocalDef(global, be, nums, sizes, types, devNums, devSizes, devIdx, total)
    }

    private fun readData(local: Int, compressedTime: Long?, onRecord: (FitRecord) -> Unit) {
        val def = defs[local] ?: throw FormatError("data for undefined local type $local")
        if (buf.size < def.total) buf = ByteArray(def.total)
        src.readFully(buf, 0, def.total)

        val n = def.nums.size
        val keys = IntArray(n)
        val values = arrayOfNulls<FitValue>(n)
        var off = 0
        var ownTime = compressedTime != null
        for (i in 0 until n) {
            val v = FitBaseType.decode(buf, off, def.sizes[i], def.types[i], def.bigEndian)
            keys[i] = def.nums[i]
            values[i] = v
            if (def.nums[i] == FIELD_TIMESTAMP && v is FitValue.I) {
                lastFitTime = v.v
                ownTime = true
            }
            off += def.sizes[i]
        }
        val nd = def.devNums.size
        val dev: Map<Int, FitValue> = if (nd == 0) {
            emptyMap()
        } else {
            val dk = IntArray(nd)
            val dv = arrayOfNulls<FitValue>(nd)
            for (i in 0 until nd) {
                val key = FitRecord.devKey(def.devIdx[i], def.devNums[i])
                val type = devTypes[key] ?: FitBaseType.BYTE
                dk[i] = key
                dv[i] = FitBaseType.decode(buf, off, def.devSizes[i], type, def.bigEndian)
                off += def.devSizes[i]
            }
            FieldMap(dk, dv)
        }
        val fields = FieldMap(keys, values)
        val fit = lastFitTime
        val rec = FitRecord(def.global, fields, dev, fit?.let { FitTime.toEpoch(it) }, ownTime)
        noteSpecial(rec)
        records++
        try {
            onRecord(rec)
        } catch (t: Throwable) {
            throw CallbackError(t)
        }
    }

    private fun noteSpecial(rec: FitRecord) {
        when (rec.global) {
            MSG_FILE_ID -> if (!seenFileId) {
                seenFileId = true
                fileType = (rec.fields[0] as? FitValue.I)?.v?.toInt()
                manufacturer = (rec.fields[1] as? FitValue.I)?.v?.toInt()
                product = (rec.fields[2] as? FitValue.I)?.v?.toInt()
            }
            MSG_FIELD_DESCRIPTION -> {
                // Ref: Gadgetbridge 160beab41 FitCodeGenerator/.../fit_profile.json (field_description 206: 0 developer_data_index, 1 field_definition_number, 2 fit_base_type_id)
                val idx = (rec.fields[0] as? FitValue.I)?.v?.toInt()
                val num = (rec.fields[1] as? FitValue.I)?.v?.toInt()
                val type = (rec.fields[2] as? FitValue.I)?.v?.toInt()
                if (idx != null && num != null && type != null && devTypes.size < 4096) {
                    devTypes[FitRecord.devKey(idx, num)] = type
                }
            }
        }
    }

    /** Buffered byte source that tracks the running CRC, the bytes consumed and a read limit. */
    private class Source(private val input: InputStream) {
        private val b = ByteArray(65536)
        private var pos = 0
        private var len = 0
        var consumed = 0L
        var limit = Long.MAX_VALUE
        var crc = 0

        private fun fill(): Boolean {
            if (pos < len) return true
            pos = 0
            len = 0
            while (len == 0) {
                val r = input.read(b, 0, b.size)
                if (r < 0) return false
                len = r
            }
            return true
        }

        fun u8(): Int {
            if (consumed >= limit) throw FormatError("record runs past the declared data size")
            if (!fill()) throw FormatError("input ended early")
            val v = b[pos++].toInt() and 0xFF
            consumed++
            crc = Crc16.update(crc, v)
            return v
        }

        fun readFully(dst: ByteArray, off: Int, n: Int) {
            if (consumed + n > limit) throw FormatError("record runs past the declared data size")
            var done = 0
            while (done < n) {
                if (!fill()) throw FormatError("input ended early")
                val k = minOf(n - done, len - pos)
                System.arraycopy(b, pos, dst, off + done, k)
                crc = Crc16.compute(b, pos, k, crc)
                pos += k
                done += k
            }
            consumed += n
        }

        /** Bytes available without blocking past the buffer, filling it once (0 at end of input). */
        fun peek(): Int {
            if (pos >= len) {
                if (!fill()) return 0
            }
            if (len - pos < 12) compact()
            return len - pos
        }

        private fun compact() {
            System.arraycopy(b, pos, b, 0, len - pos)
            len -= pos
            pos = 0
            while (len < 12) {
                val r = input.read(b, len, b.size - len)
                if (r < 0) break
                len += r
            }
        }

        fun peekIsFitHeader(): Boolean {
            if (peek() < 12) return false
            val hs = b[pos].toInt() and 0xFF
            return hs >= 12 && b[pos + 8] == '.'.code.toByte() && b[pos + 9] == 'F'.code.toByte() &&
                b[pos + 10] == 'I'.code.toByte() && b[pos + 11] == 'T'.code.toByte()
        }
    }

    private companion object {
        const val TAG = "FitReader"
        const val FIELD_TIMESTAMP = 253
        const val MSG_FILE_ID = 0
        const val MSG_FIELD_DESCRIPTION = 206
    }
}

/** Small read-only map over parallel key and value arrays; lookups scan (messages have few fields). */
internal class FieldMap(private val ks: IntArray, private val vals: Array<FitValue?>) : AbstractMap<Int, FitValue>() {
    override val size: Int get() = ks.size

    override fun get(key: Int): FitValue? {
        for (i in ks.indices) if (ks[i] == key) return vals[i]
        return null
    }

    override fun containsKey(key: Int): Boolean {
        for (k in ks) if (k == key) return true
        return false
    }

    override val entries: Set<Map.Entry<Int, FitValue>>
        get() {
            val out = LinkedHashMap<Int, FitValue>(ks.size * 2)
            for (i in ks.indices) {
                val v = vals[i] ?: continue
                if (!out.containsKey(ks[i])) out[ks[i]] = v
            }
            return out.entries
        }
}
