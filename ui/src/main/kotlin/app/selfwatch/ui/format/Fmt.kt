/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.ui.format

import android.content.Context
import android.icu.text.DateFormat
import android.icu.util.TimeZone
import java.text.NumberFormat
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Locale-aware formatting for every number, unit, time and day the app shows.
 *
 * Numbers use [NumberFormat]; times and day labels use ICU skeletons so each locale gets its own order
 * ("Sat 22 Nov" in en-GB, "Sat, Nov 22" in en-US) and the phone's 12/24-hour choice. Values arrive in
 * storage units (metric) and are converted for [Units.IMPERIAL].
 */
object Fmt {
    private const val KG_PER_LB: Double = 0.45359237
    private const val CM_PER_INCH: Double = 2.54
    private const val M_PER_MILE: Double = 1609.344
    private const val M_PER_FOOT: Double = 0.3048

    /** Millilitres in one US fluid ounce. */
    const val ML_PER_FL_OZ: Double = 29.5735295625

    /** Kilograms in one pound. */
    const val KG_PER_POUND: Double = KG_PER_LB

    /** Metres in one mile. */
    const val METRES_PER_MILE: Double = M_PER_MILE

    /** Metres in one foot. */
    const val METRES_PER_FOOT: Double = M_PER_FOOT

    /** Formats [value] with exactly [decimals] fraction digits and the locale's grouping, e.g. "1,240". */
    fun number(value: Double, decimals: Int = 0, locale: Locale = Locale.getDefault()): String =
        numberFormat(locale, decimals, decimals).format(value)

    /** Formats a whole number with grouping, e.g. "10,000". */
    fun number(value: Long, locale: Locale = Locale.getDefault()): String =
        numberFormat(locale, 0, 0).format(value)

    /** Energy in kilocalories: "1,240 kcal". [signed] adds a plus sign to positive values. */
    fun energy(
        kcal: Int,
        locale: Locale = Locale.getDefault(),
        labels: FmtLabels = FmtLabels.ENGLISH,
        signed: Boolean = false,
    ): String {
        val n = number(kcal.toLong(), locale)
        val text = if (signed && kcal > 0) "+$n" else n
        return withUnit(text, labels.kcal)
    }

    /** Body weight from hundredths of a kilogram, one decimal: "82.4 kg" or "181.7 lb". */
    fun weight(
        kgX100: Int,
        units: Units,
        locale: Locale = Locale.getDefault(),
        labels: FmtLabels = FmtLabels.ENGLISH,
    ): String {
        val kg = kgX100 / 100.0
        return when (units) {
            Units.METRIC -> withUnit(number(kg, 1, locale), labels.kg)
            Units.IMPERIAL -> withUnit(number(kg / KG_PER_LB, 1, locale), labels.lb)
        }
    }

    /** Body length (waist, height) from centimetres: "91 cm", "91.5 cm" or "35.8 in". */
    fun length(
        cm: Double,
        units: Units,
        locale: Locale = Locale.getDefault(),
        labels: FmtLabels = FmtLabels.ENGLISH,
    ): String = when (units) {
        Units.METRIC -> withUnit(numberFormat(locale, 0, 1).format(cm), labels.cm)
        Units.IMPERIAL -> withUnit(number(cm / CM_PER_INCH, 1, locale), labels.inch)
    }

    /**
     * Distance from metres. Metric: "850 m" under 1 km, then "3.2 km" (whole km from 100 km).
     * Imperial: "500 ft" under 0.1 mi, then "2.0 mi".
     */
    fun distance(
        m: Double,
        units: Units,
        locale: Locale = Locale.getDefault(),
        labels: FmtLabels = FmtLabels.ENGLISH,
    ): String = when (units) {
        Units.METRIC -> when {
            m < 1000.0 -> withUnit(number(m, 0, locale), labels.m)
            m < 100_000.0 -> withUnit(number(m / 1000.0, 1, locale), labels.km)
            else -> withUnit(number(m / 1000.0, 0, locale), labels.km)
        }
        Units.IMPERIAL -> {
            val miles = m / M_PER_MILE
            when {
                miles < 0.1 -> withUnit(number(m / M_PER_FOOT, 0, locale), labels.ft)
                miles < 100.0 -> withUnit(number(miles, 1, locale), labels.mi)
                else -> withUnit(number(miles, 0, locale), labels.mi)
            }
        }
    }

    /**
     * Elevation or climb from metres, whole numbers: "420 m" or "1,378 ft" (never switches to km or mi).
     */
    fun elevation(
        m: Double,
        units: Units,
        locale: Locale = Locale.getDefault(),
        labels: FmtLabels = FmtLabels.ENGLISH,
    ): String = when (units) {
        Units.METRIC -> withUnit(number(m, 0, locale), labels.m)
        Units.IMPERIAL -> withUnit(number(m / M_PER_FOOT, 0, locale), labels.ft)
    }

    /** A drink volume from millilitres: "350 ml" or "2.8 L" (from 1 litre), or "12 fl oz". */
    fun volume(
        ml: Double,
        units: Units,
        locale: Locale = Locale.getDefault(),
        labels: FmtLabels = FmtLabels.ENGLISH,
    ): String = withUnit(volumeNumber(ml, units, locale), volumeUnit(ml, units, labels))

    /** The number part of [volume]: "350", "2.8" or "12". */
    fun volumeNumber(ml: Double, units: Units, locale: Locale = Locale.getDefault()): String = when (units) {
        Units.METRIC -> if (ml < 1000.0) number(ml, 0, locale) else number(ml / 1000.0, 1, locale)
        Units.IMPERIAL -> number(ml / ML_PER_FL_OZ, 0, locale)
    }

    /** The unit part of [volume] for [ml]: "ml", "L" or "fl oz". */
    fun volumeUnit(ml: Double, units: Units, labels: FmtLabels = FmtLabels.ENGLISH): String = when (units) {
        Units.METRIC -> if (ml < 1000.0) labels.ml else labels.l
        Units.IMPERIAL -> labels.flOz
    }

    /** A temperature from °C: "21 °C" or "70 °F" with [decimals] fraction digits. */
    fun temperature(
        celsius: Double,
        units: Units,
        decimals: Int = 0,
        locale: Locale = Locale.getDefault(),
        labels: FmtLabels = FmtLabels.ENGLISH,
    ): String = when (units) {
        Units.METRIC -> withUnit(number(celsius, decimals, locale), labels.celsius)
        Units.IMPERIAL -> withUnit(number(celsius * F_PER_C + F_OFFSET, decimals, locale), labels.fahrenheit)
    }

    /** A temperature change in [units]: a change of 1 °C is 1.8 °F (no offset). */
    fun temperatureDelta(celsiusDelta: Double, units: Units): Double =
        if (units == Units.METRIC) celsiusDelta else celsiusDelta * F_PER_C

    /** The temperature unit symbol in [units]: "°C" or "°F". */
    fun temperatureUnit(units: Units, labels: FmtLabels = FmtLabels.ENGLISH): String =
        if (units == Units.METRIC) labels.celsius else labels.fahrenheit

    private const val F_PER_C: Double = 1.8
    private const val F_OFFSET: Double = 32.0

    /** Duration: "42 min" under an hour, else "7h 12m" (minutes always two digits). Negative counts as 0. */
    fun duration(
        sec: Long,
        locale: Locale = Locale.getDefault(),
        labels: FmtLabels = FmtLabels.ENGLISH,
    ): String {
        val totalMin = (if (sec < 0) 0 else sec) / 60
        val h = totalMin / 60
        val min = totalMin % 60
        return if (h == 0L) {
            String.format(locale, labels.durationMin, number(min, locale))
        } else {
            val mm = NumberFormat.getIntegerInstance(locale).apply { minimumIntegerDigits = 2 }.format(min)
            String.format(locale, labels.durationHm, number(h, locale), mm)
        }
    }

    /**
     * Minutes and seconds, "5:07" (pace per km or mile, split times). [sec] is rounded to the whole second
     * before splitting, so 299.6 s reads "5:00", never "4:60". Negative counts as 0.
     */
    fun minSec(sec: Double, locale: Locale = Locale.getDefault()): String {
        val s = if (sec.isNaN() || sec < 0) 0L else Math.round(sec)
        return String.format(locale, "%d:%02d", s / 60, s % 60)
    }

    /**
     * Clock time of an epoch second in [zone]. [is24h] true or false forces the hour cycle (pass the
     * phone's setting); null uses the locale's own preference.
     */
    fun time(
        tSec: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
        is24h: Boolean? = null,
    ): String {
        val skeleton = when (is24h) {
            true -> "Hmm"
            false -> "hmm"
            null -> "jmm"
        }
        val df = DateFormat.getInstanceForSkeleton(skeleton, locale)
        df.timeZone = TimeZone.getTimeZone(zone.id)
        return df.format(Date(tSec * 1000L))
    }

    /** [time] using the phone's 12/24-hour setting. */
    fun time(context: Context, tSec: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        time(tSec, zone, currentLocale(context), android.text.format.DateFormat.is24HourFormat(context))

    /**
     * Day label for a `Day` (YYYYMMDD): "Today", "Yesterday", else the short weekday, day and month in
     * the locale's order ("Sat 22 Nov" in en-GB). The year is added when it differs from [today]'s.
     */
    fun day(
        day: Int,
        today: LocalDate = LocalDate.now(),
        locale: Locale = Locale.getDefault(),
        labels: FmtLabels = FmtLabels.ENGLISH,
    ): String {
        val date = dayToDate(day)
        return when (ChronoUnit.DAYS.between(date, today)) {
            0L -> labels.today
            1L -> labels.yesterday
            else -> {
                val skeleton = if (date.year == today.year) "EEEdMMM" else "EEEdMMMy"
                val df = DateFormat.getInstanceForSkeleton(skeleton, locale)
                df.timeZone = TimeZone.GMT_ZONE
                val epochMs = date.atStartOfDay().toEpochSecond(ZoneOffset.UTC) * 1000L
                df.format(Date(epochMs))
            }
        }
    }

    /** [day] with translated labels and the context's locale. */
    fun day(context: Context, day: Int, today: LocalDate = LocalDate.now()): String =
        day(day, today, currentLocale(context), FmtLabels.from(context))

    /** Percent from a fraction (0.24 is "24%"), with [decimals] fraction digits. */
    fun percent(fraction: Double, decimals: Int = 0, locale: Locale = Locale.getDefault()): String {
        val pf = NumberFormat.getPercentInstance(locale)
        pf.minimumFractionDigits = decimals
        pf.maximumFractionDigits = decimals
        return pf.format(fraction)
    }

    /** Converts a `Day` (YYYYMMDD) to a date. */
    fun dayToDate(day: Int): LocalDate = LocalDate.of(day / 10000, (day / 100) % 100, day % 100)

    /** Converts a date to a `Day` (YYYYMMDD). */
    fun dateToDay(date: LocalDate): Int = date.year * 10000 + date.monthValue * 100 + date.dayOfMonth

    /** Formats a hundredths value ("1250" is "12.5") with up to [maxDecimals] digits, for entry fields. */
    fun x100(valueX100: Long, maxDecimals: Int = 2, locale: Locale = Locale.getDefault()): String =
        numberFormat(locale, 0, maxDecimals).apply { isGroupingUsed = false }.format(valueX100 / 100.0)

    /**
     * Parses user text in the locale's format into hundredths, rounding half up. Returns null for text
     * that isn't a number. Accepts both "." and the locale's decimal separator.
     */
    fun parseX100(text: String, locale: Locale = Locale.getDefault()): Long? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val nf = NumberFormat.getNumberInstance(locale)
        nf.isGroupingUsed = false
        val pos = java.text.ParsePosition(0)
        val parsed = nf.parse(trimmed, pos)
        val value = if (parsed != null && pos.index == trimmed.length) {
            parsed.toDouble()
        } else {
            trimmed.replace(',', '.').toDoubleOrNull() ?: return null
        }
        val scaled = value * 100.0
        return if (scaled >= 0) scaled.roundToLong() else -(abs(scaled).roundToLong())
    }

    /** Number and unit joined by a no-break space so they never wrap apart. */
    private fun withUnit(number: String, unit: String): String = "$number\u00A0$unit"

    private fun numberFormat(locale: Locale, minFrac: Int, maxFrac: Int): NumberFormat =
        NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = minFrac
            maximumFractionDigits = maxFrac
        }

    private fun currentLocale(context: Context): Locale = context.resources.configuration.locales[0]
}
