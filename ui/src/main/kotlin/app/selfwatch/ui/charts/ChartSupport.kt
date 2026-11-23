/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.ui.charts

import android.content.Context
import android.icu.text.DateFormat
import android.icu.util.TimeZone
import androidx.annotation.ColorInt
import app.selfwatch.model.health.SleepStage
import app.selfwatch.ui.R
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/**
 * Cached formatters for chart labels. Creating ICU and number formats is slow, so a chart builds one of
 * these per configuration and reuses it on every frame. Times follow the phone's 12/24-hour setting.
 */
internal class ChartFormat(context: Context, val zone: ZoneId) {
    private val locale: Locale = context.resources.configuration.locales[0]
    private val is24h: Boolean = android.text.format.DateFormat.is24HourFormat(context)
    private val timeFormat: DateFormat = skeleton(if (is24h) "Hmm" else "hmm")
    private val dateFormat: DateFormat = skeleton("dMMM")
    private val dayFormat: DateFormat = skeleton("EEEdMMM")
    private val numbers = arrayOfNulls<NumberFormat>(5)

    private fun skeleton(s: String): DateFormat =
        DateFormat.getInstanceForSkeleton(s, locale).also { it.timeZone = TimeZone.getTimeZone(zone.id) }

    /** Clock time, e.g. "23:00" or "11:00 PM". */
    fun time(tSec: Long): String = timeFormat.format(Date(tSec * 1000L))

    /** Short date, e.g. "22 Nov". */
    fun date(tSec: Long): String = dateFormat.format(Date(tSec * 1000L))

    /** Weekday and date, e.g. "Sat 22 Nov". */
    fun day(tSec: Long): String = dayFormat.format(Date(tSec * 1000L))

    /** A time-axis label: the clock time for steps under a day, else the date. */
    fun axis(tSec: Long, stepSec: Long): String = if (stepSec < 86_400L) time(tSec) else date(tSec)

    /** A number with exactly [decimals] (0..4) fraction digits and the locale's grouping. */
    fun number(value: Double, decimals: Int): String {
        val d = decimals.coerceIn(0, 4)
        val nf = numbers[d] ?: NumberFormat.getNumberInstance(locale).also {
            it.minimumFractionDigits = d
            it.maximumFractionDigits = d
            numbers[d] = it
        }
        return nf.format(value)
    }

    /** UTC offset of [zone] at [tSec], for aligning time ticks. */
    fun offsetSec(tSec: Long): Int = zone.rules.getOffset(Instant.ofEpochSecond(tSec)).totalSeconds
}

/** Fraction digits for a layer's display scale: 1 → 0, 10 → 1, 100 → 2. */
internal fun decimalsForScale(scale: Int): Int = when {
    scale >= 1000 -> 3
    scale >= 100 -> 2
    scale >= 10 -> 1
    else -> 0
}

/** User-facing name of a sleep stage. */
internal fun stageName(context: Context, stage: SleepStage): String = context.getString(
    when (stage) {
        SleepStage.AWAKE -> R.string.sw_stage_awake
        SleepStage.REM -> R.string.sw_stage_rem
        SleepStage.LIGHT -> R.string.sw_stage_light
        SleepStage.DEEP -> R.string.sw_stage_deep
        SleepStage.UNMEASURABLE -> R.string.sw_stage_unmeasurable
    },
)

/** The current color theme's color for a sleep stage (see [ChartPalette.stage]). */
@ColorInt
fun defaultStageColor(context: Context, stage: SleepStage): Int = ChartPalette.of(context).stage(stage)
