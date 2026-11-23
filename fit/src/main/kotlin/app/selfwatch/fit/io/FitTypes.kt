/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.fit.io

/**
 * One decoded data message.
 *
 * @property global the global message number (for example 20 = record, 55 = monitoring).
 * @property fields field number to value. Fields the file marks invalid are present as [FitValue.Invalid].
 * @property dev developer fields, keyed by [devKey] (developer data index and field number).
 * @property tSec UTC epoch seconds: the message's own timestamp (field 253 or a compressed timestamp header),
 *   otherwise the last timestamp seen earlier in the file; null if no timestamp has been seen yet.
 * @property ownTime true when [tSec] came from this message itself (field 253 or a compressed header),
 *   false when it was carried over from an earlier message.
 */
data class FitRecord(
    val global: Int,
    val fields: Map<Int, FitValue>,
    val dev: Map<Int, FitValue>,
    val tSec: Long?,
    val ownTime: Boolean = false,
) {
    /** Helpers for developer field keys. */
    companion object {
        /** Key of developer field [fieldNum] of developer data index [devIndex] in [FitRecord.dev]. */
        fun devKey(devIndex: Int, fieldNum: Int): Int = ((devIndex and 0xFF) shl 8) or (fieldNum and 0xFF)
    }
}

/** A decoded field value. Numbers are raw (unscaled); decoders apply the profile's scale and offset. */
sealed interface FitValue {
    /**
     * An integer of any FIT integer type (enum, sintN, uintN, uintNz, byte).
     * A uint64 above [Long.MAX_VALUE] keeps its raw bits (so it reads as negative).
     */
    data class I(val v: Long) : FitValue

    /** A float32 or float64. */
    data class D(val v: Double) : FitValue

    /** A UTF-8 string (up to its first null byte). */
    data class S(val v: String) : FitValue

    /** An array field (field size is several times the base type size); elements may be [Invalid]. */
    data class A(val v: List<FitValue>) : FitValue

    /** The file stored the base type's invalid value (or an array of only invalid values, or an empty string). */
    data object Invalid : FitValue
}

/**
 * What [FitReader.read] found.
 *
 * @property fileType `file_id.type` of the first file (4 activity, 9 weight, 32 monitoring, 49 sleep, ...).
 * @property manufacturer `file_id.manufacturer` (1 = Garmin).
 * @property product `file_id.product`.
 * @property records number of data messages passed to the callback.
 * @property crcOk false when the header CRC or the file CRC did not match. The records were still read
 *   (see [FitReader] for the policy).
 */
data class FitSummary(
    val fileType: Int?,
    val manufacturer: Int?,
    val product: Int?,
    val records: Int,
    val crcOk: Boolean,
)

/**
 * A field in a definition message written by [FitWriter].
 *
 * @property num field number.
 * @property size size in bytes (a multiple of the base type size for arrays; any length for strings).
 * @property baseType base type code from [FitBaseType] (for example [FitBaseType.UINT16]).
 */
data class FieldDef(val num: Int, val size: Int, val baseType: Int)

/**
 * A developer field in a definition message written by [FitWriter].
 *
 * @property num developer field number.
 * @property size size in bytes.
 * @property devIndex developer data index.
 */
data class DevFieldDef(val num: Int, val size: Int, val devIndex: Int)

/** FIT time: seconds since 1989-12-31 00:00 UTC. */
object FitTime {
    /** Seconds between the Unix epoch and the FIT epoch. */
    const val EPOCH_OFFSET_SEC: Long = 631_065_600L

    /** UTC epoch seconds of FIT time [fit]. */
    fun toEpoch(fit: Long): Long = fit + EPOCH_OFFSET_SEC

    /** FIT time of UTC epoch seconds [tSec]. */
    fun fromEpoch(tSec: Long): Long = tSec - EPOCH_OFFSET_SEC

    /**
     * Rebuilds a full timestamp from a 16-bit `timestamp_16` value [t16] and the last full timestamp [ref]
     * (both in FIT time). Picks the nearest match, so a sample a little before [ref] works too.
     */
    // Ref: Gadgetbridge 160beab41 service/devices/garmin/fit/messages/FitMonitoring.java (signed ±32768 rollover)
    fun fromTimestamp16(ref: Long, t16: Int): Long {
        var diff = (t16 and 0xFFFF) - (ref and 0xFFFF).toInt()
        if (diff < -32768) diff += 65536 else if (diff > 32768) diff -= 65536
        return ref + diff
    }

    /**
     * The timestamp of a compressed-timestamp record header with 5-bit [offset], given the last full
     * timestamp [ref] (both in FIT time). Rolls over when the offset is smaller than the low 5 bits of [ref].
     */
    // Ref: Gadgetbridge 160beab41 service/devices/garmin/fit/FitFile.java (compressed timestamp rollover)
    fun fromCompressed(ref: Long, offset: Int): Long {
        val o = (offset and 0x1F).toLong()
        val base = ref and 0x1FL.inv()
        return if (o >= (ref and 0x1FL)) base + o else base + o + 0x20
    }
}
