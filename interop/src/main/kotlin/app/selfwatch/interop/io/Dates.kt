/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.interop.io

import app.selfwatch.interop.api.DateFormatGuess
import app.selfwatch.model.time.Day
import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

/** A local day with an optional local time of day. */
data class LocalStamp(val day: Day, val secOfDay: Int?) {
    /** UTC epoch seconds in [zone]; noon when there is no time (safe from DST edges). */
    fun epochSec(zone: ZoneId): Long =
        LocalDateTime.of(day.toLocalDate(), java.time.LocalTime.ofSecondOfDay((secOfDay ?: NOON).toLong()))
            .atZone(zone).toEpochSecond()

    private companion object {
        const val NOON = 12 * 3600
    }
}

/**
 * The date layouts the importers understand. Each accepts one- or two-digit days and months, two- or
 * four-digit years, and an optional time after a space or `T` (`H:mm`, `H:mm:ss`, fractions, AM/PM;
 * a trailing zone is ignored).
 */
enum class DateFormat(val id: String, private val order: Order, private val separators: String) {
    /** `2026-05-01`. */
    ISO("yyyy-MM-dd", Order.YMD, "-/"),

    /** `05/01/2026` (US). */
    US("MM/dd/yyyy", Order.MDY, "/-"),

    /** `01/05/2026`. */
    EU("dd/MM/yyyy", Order.DMY, "/-"),

    /** `01.05.2026`. */
    DOTTED("dd.MM.yyyy", Order.DMY, "."),
    ;

    private enum class Order { YMD, MDY, DMY }

    /** Parses [text], or null if it isn't a valid date in this layout. */
    fun parse(text: String): LocalStamp? {
        val m = PATTERN.matchEntire(text.trim().trim('"')) ?: return null
        val g = m.groupValues
        if (g[2][0] !in separators) return null
        val a = g[1]
        val b = g[3]
        val c = g[4]
        val (y, mo, d) = when (order) {
            Order.YMD -> if (a.length != 4) return null else Triple(a.toInt(), b.toInt(), c.toInt())
            Order.MDY -> if (a.length > 2) return null else Triple(year(c) ?: return null, a.toInt(), b.toInt())
            Order.DMY -> if (a.length > 2) return null else Triple(year(c) ?: return null, b.toInt(), a.toInt())
        }
        val date = try {
            LocalDate.of(y, mo, d)
        } catch (_: DateTimeException) {
            return null
        }
        var sec: Int? = null
        if (g[5].isNotEmpty()) {
            var h = g[5].toInt()
            val min = g[6].toInt()
            val s = g[7].ifEmpty { "0" }.toInt()
            val ampm = g[8].lowercase()
            if (ampm.isNotEmpty()) {
                if (h !in 1..12) return null
                if (ampm == "am" && h == 12) h = 0 else if (ampm == "pm" && h != 12) h += 12
            }
            if (h > 23 || min > 59 || s > 59) return null
            sec = h * 3600 + min * 60 + s
        }
        return LocalStamp(Day.of(date), sec)
    }

    /** Formats [day] in this layout with two-digit days and months. */
    fun format(day: Day): String {
        val y = day.year.toString().padStart(4, '0')
        val m = day.month.toString().padStart(2, '0')
        val d = day.dayOfMonth.toString().padStart(2, '0')
        return when (this) {
            ISO -> "$y-$m-$d"
            US -> "$m/$d/$y"
            EU -> "$d/$m/$y"
            DOTTED -> "$d.$m.$y"
        }
    }

    /** Lookup. */
    companion object {
        private val PATTERN = Regex(
            """(\d{1,4})([-/.])(\d{1,2})\2(\d{2}|\d{4})""" +
                """(?:[ T]+(\d{1,2}):(\d{2})(?::(\d{2})(?:[.,]\d+)?)?\s*([AaPp][Mm])?\s*(?:Z|[+-]\d{2}:?\d{2})?)?""",
        )

        private fun year(s: String): Int? = when (s.length) {
            4 -> s.toInt()
            2 -> s.toInt().let { if (it < 70) 2000 + it else 1900 + it }
            else -> null
        }

        /** The format with [id], or null. */
        fun byId(id: String): DateFormat? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Guesses which [DateFormat] a column uses from up to [MAX_SAMPLES] values. Formats that parse every
 * value are candidates; when several fit (for example `03/04/2026` in both US and European order) the
 * guess is ambiguous and the preview asks the user, preselecting the importer's preferred format.
 */
object DateFormatDetector {
    /** How many values are looked at. */
    const val MAX_SAMPLES: Int = 200

    /** Detects the format of [values]; [prefer] wins among equal candidates. */
    fun detect(values: List<String>, prefer: DateFormat? = null): DateFormatGuess {
        val samples = values.asSequence().map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_SAMPLES).toList()
        if (samples.isEmpty()) return DateFormatGuess(prefer?.id, emptyList(), emptyList())
        val scores = DateFormat.entries.associateWith { f -> samples.count { f.parse(it) != null } }
        val full = DateFormat.entries.filter { scores[it] == samples.size }
        if (full.isEmpty()) {
            val best = scores.filter { it.value > 0 }.maxByOrNull { it.value }?.key
            return DateFormatGuess(best?.id, listOfNotNull(best?.id), emptyList())
        }
        val best = if (prefer != null && prefer in full) prefer else full.first()
        val ambiguous = if (full.size > 1) {
            samples.filter { s -> full.map { it.parse(s)?.day }.toSet().size > 1 }.take(5)
        } else {
            emptyList()
        }
        // Several formats that read every sample the same way are not really ambiguous.
        val candidates = if (full.size > 1 && ambiguous.isEmpty()) listOf(best) else full
        return DateFormatGuess(best.id, candidates.map { it.id }, ambiguous)
    }
}

/** ISO 8601 date-time helpers for XML formats (GPX, TCX). */
object IsoTime {
    /**
     * Parses `2026-05-01T07:00:00Z`, with optional fractions and offset, to UTC epoch seconds.
     * Values without an offset are read as UTC. Returns null when malformed.
     */
    fun parse(text: String): Long? {
        val t = text.trim()
        return try {
            OffsetDateTime.parse(t).toEpochSecond()
        } catch (_: DateTimeParseException) {
            try {
                LocalDateTime.parse(t).toEpochSecond(ZoneOffset.UTC)
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }

    /** Formats [tSec] as `2026-05-01T07:00:00Z`. */
    fun format(tSec: Long): String = java.time.Instant.ofEpochSecond(tSec).toString()
}
