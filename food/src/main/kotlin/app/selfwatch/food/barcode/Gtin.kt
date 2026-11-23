/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food.barcode

/**
 * GS1 barcode numbers, normalised the way FOODDB_SPEC §8 stores them: digits only, 8, 12, 13 or 14
 * long, left-padded to 14 digits, with a valid check digit, as a number (`barcode.gtin`).
 */
object Gtin {
    /** Smallest stored GTIN (an EAN-8 with leading digit 1). */
    const val MIN: Long = 10_000_000L

    /** Largest stored GTIN (14 nines). */
    const val MAX: Long = 99_999_999_999_999L

    /**
     * The GTIN-14 number for [raw], or null when it is not a valid EAN-8, UPC-A, EAN-13 or GTIN-14.
     * Every character that is not an ASCII digit is dropped first ("0 12345-67890 5" works).
     * An 8-digit [raw] is read as EAN-8; expand UPC-E with [expandUpcE] before calling this.
     */
    fun normalize(raw: String): Long? {
        val digits = digitsOf(raw)
        if (digits.length != 8 && digits.length != 12 && digits.length != 13 && digits.length != 14) return null
        if (!hasValidCheckDigit(digits)) return null
        val v = digits.toLong()
        return if (v in MIN..MAX) v else null
    }

    /** True when the last digit of [digits] (ASCII digits only, any length ≥ 2) is its GS1 check digit. */
    fun hasValidCheckDigit(digits: String): Boolean {
        if (digits.length < 2 || digits.any { it !in '0'..'9' }) return false
        return checkDigit(digits.substring(0, digits.length - 1)) == digits.last() - '0'
    }

    /**
     * The GS1 check digit for [body] (the code without its check digit): from the right, digits are
     * weighted 3, 1, 3, 1, …; the check digit brings the weighted sum up to a multiple of 10.
     */
    fun checkDigit(body: String): Int {
        var sum = 0
        var weight = 3
        for (i in body.length - 1 downTo 0) {
            val d = body[i] - '0'
            require(d in 0..9) { "not a digit" }
            sum += d * weight
            weight = 4 - weight
        }
        return (10 - sum % 10) % 10
    }

    /**
     * The 12-digit UPC-A for a UPC-E code, or null when [upcE] is not a valid UPC-E. Accepts 8 digits
     * (number system 0 or 1, six digits, check digit), 7 digits (no check digit; it is computed) or
     * 6 digits (number system 0 assumed). When a check digit is given it must match the expansion.
     */
    fun expandUpcE(upcE: String): String? {
        val d = digitsOf(upcE)
        val (ns, six, given) = when (d.length) {
            6 -> Triple('0', d, null)
            7 -> Triple(d[0], d.substring(1), null)
            8 -> Triple(d[0], d.substring(1, 7), d[7])
            else -> return null
        }
        if (ns != '0' && ns != '1') return null
        val m = six
        val body = when (m[5]) {
            '0', '1', '2' -> "$ns${m[0]}${m[1]}${m[5]}0000${m[2]}${m[3]}${m[4]}"
            '3' -> "$ns${m[0]}${m[1]}${m[2]}00000${m[3]}${m[4]}"
            '4' -> "$ns${m[0]}${m[1]}${m[2]}${m[3]}00000${m[4]}"
            else -> "$ns${m[0]}${m[1]}${m[2]}${m[3]}${m[4]}0000${m[5]}"
        }
        val check = checkDigit(body)
        if (given != null && given - '0' != check) return null
        return body + check
    }

    /** [gtin] as text of [length] digits (zero-padded), for example 13 for an EAN-13 lookup. */
    fun format(gtin: Long, length: Int = 14): String = gtin.toString().padStart(length, '0')

    /**
     * The shortest usual printed form of [gtin]: 8 digits for an EAN-8, otherwise 13 digits (EAN-13,
     * which also covers UPC-A with a leading 0), or 14 for a real GTIN-14.
     */
    fun shortForm(gtin: Long): String = when {
        gtin < 100_000_000L -> format(gtin, 8)
        gtin < 10_000_000_000_000L -> format(gtin, 13)
        else -> format(gtin, 14)
    }

    private fun digitsOf(s: String): String = buildString(s.length) { for (c in s) if (c in '0'..'9') append(c) }
}
