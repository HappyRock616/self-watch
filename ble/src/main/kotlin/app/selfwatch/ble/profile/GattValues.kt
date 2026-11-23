/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.ble.profile

import java.time.DateTimeException
import java.time.LocalDateTime
import kotlin.math.pow

/**
 * Reads little-endian GATT field types from a characteristic value. Every read returns null when the
 * value is too short, so a truncated packet is rejected instead of crashing.
 */
internal class GattReader(private val bytes: ByteArray) {
    private var pos = 0

    /** Bytes not read yet. */
    val remaining: Int get() = bytes.size - pos

    /** `uint8`. */
    fun u8(): Int? {
        if (remaining < 1) return null
        return bytes[pos++].toInt() and 0xFF
    }

    /** `uint16`, little-endian. */
    fun u16(): Int? {
        if (remaining < 2) return null
        val v = (bytes[pos].toInt() and 0xFF) or ((bytes[pos + 1].toInt() and 0xFF) shl 8)
        pos += 2
        return v
    }

    /**
     * IEEE 11073-20601 `SFLOAT` (16 bits: 4-bit signed exponent, 12-bit signed mantissa). The special
     * values (NaN, NRes, ±infinity, reserved) come back as [Double.NaN]; null means too short.
     */
    fun sfloat(): Double? = u16()?.let { GattValues.sfloat(it) }

    /**
     * A Date Time field (7 bytes: year `uint16`, month, day, hours, minutes, seconds). Returns the
     * time, or [GattValues.UNKNOWN_TIME] when the device left a part unknown (zero) or out of range;
     * null means too short.
     */
    fun dateTime(): LocalDateTime? {
        val year = u16() ?: return null
        val month = u8() ?: return null
        val day = u8() ?: return null
        val hour = u8() ?: return null
        val minute = u8() ?: return null
        val second = u8() ?: return null
        if (year == 0 || month == 0 || day == 0) return GattValues.UNKNOWN_TIME
        return try {
            LocalDateTime.of(year, month, day, hour, minute, second)
        } catch (e: DateTimeException) {
            GattValues.UNKNOWN_TIME
        }
    }
}

/** Shared field conversions. */
internal object GattValues {
    /** Stands for a Date Time the device did not know (it is never a real reading time). */
    val UNKNOWN_TIME: LocalDateTime = LocalDateTime.of(1, 1, 1, 0, 0)

    /** Decodes a raw 16-bit `SFLOAT`; special values give [Double.NaN]. */
    fun sfloat(raw: Int): Double {
        var mantissa = raw and 0x0FFF
        var exponent = (raw shr 12) and 0x0F
        if (exponent == 0 && mantissa in SPECIAL_FIRST..SPECIAL_LAST) return Double.NaN
        if (mantissa >= 0x0800) mantissa -= 0x1000
        if (exponent >= 0x08) exponent -= 0x10
        return mantissa * 10.0.pow(exponent)
    }

    /** Kilograms ×100 from a mass field: SI resolution 0.005 kg, imperial 0.01 lb. */
    fun massKgX100(raw: Int, imperial: Boolean): Int =
        if (imperial) Math.round(raw * LB_TO_KG).toInt() else Math.round(raw * 0.5).toInt()

    /** Millimetres from a height field: SI resolution 0.001 m, imperial 0.1 inch. */
    fun heightMm(raw: Int, imperial: Boolean): Int = if (imperial) Math.round(raw * 2.54).toInt() else raw

    /** mmHg from a pressure in kPa. */
    fun kpaToMmHg(kpa: Double): Double = kpa * MMHG_PER_KPA

    /** 1/1024 s to milliseconds. */
    fun rrMs(raw: Int): Int = Math.round(raw * 1000.0 / 1024.0).toInt()

    // 0x07FE +INF, 0x07FF NaN, 0x0800 NRes, 0x0801 reserved, 0x0802 -INF (exponent 0).
    private const val SPECIAL_FIRST = 0x07FE
    private const val SPECIAL_LAST = 0x0802

    /** 0.01 lb in kg, ×100 (so a raw lb value gives kg ×100). */
    private const val LB_TO_KG = 0.45359237

    private const val MMHG_PER_KPA = 7.50061683
}
