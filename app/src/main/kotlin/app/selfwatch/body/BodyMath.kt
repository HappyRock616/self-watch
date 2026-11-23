/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.body

import app.selfwatch.model.health.BodyKind
import app.selfwatch.model.health.BodyMeasure
import app.selfwatch.ui.format.Fmt
import app.selfwatch.ui.format.Units
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * The energy card's numbers. Out is what the body burned (active + resting); Remaining follows the
 * formula shown in the info sheet: **Goal − Food + Activity = Remaining**, where Activity is the
 * active calories only (resting calories are already part of the goal).
 *
 * @property goalKcal the calorie goal in force today, or null when none is set.
 * @property inKcal calories eaten today, or null when nothing is logged.
 * @property activeKcal active calories burned today, or null when no watch data.
 * @property restingKcal resting calories so far today, or null when unknown.
 */
data class Energy(val goalKcal: Int?, val inKcal: Int?, val activeKcal: Int?, val restingKcal: Int?) {
    /** Calories out (active + resting), or null when both are unknown. */
    val outKcal: Int? get() = if (activeKcal == null && restingKcal == null) null else (activeKcal ?: 0) + (restingKcal ?: 0)

    /** Goal − Food + Activity, or null without a goal. */
    val remainingKcal: Int? get() = goalKcal?.let { it - (inKcal ?: 0) + (activeKcal ?: 0) }

    /** In − Out (negative is a deficit), or null when either side is unknown. */
    val netKcal: Int? get() {
        val o = outKcal ?: return null
        val i = inKcal ?: return null
        return i - o
    }

    /** True when there is nothing at all to show. */
    val isEmpty: Boolean get() = inKcal == null && activeKcal == null && restingKcal == null
}

/** One day of the 7-day in-vs-out bars; -1 marks "no data". */
data class DayEnergy(val day: Int, val inKcal: Int, val outKcal: Int)

/** Weight trend arithmetic, shared by the Body card and the Weight screen. */
object WeightMath {
    /** Trend half-life used everywhere (the same as the weight chart's default). */
    const val HALF_LIFE_DAYS: Double = 10.0

    private const val DAY_SEC = 86_400.0

    /**
     * Exponential moving average of [kgX100] at times [tSec] (ascending) with a [halfLifeDays] half-life
     * that respects real gaps between weigh-ins. Returns one trend value per reading.
     */
    fun trend(tSec: LongArray, kgX100: IntArray, halfLifeDays: Double = HALF_LIFE_DAYS): DoubleArray {
        val n = minOf(tSec.size, kgX100.size)
        val out = DoubleArray(n)
        if (n == 0) return out
        out[0] = kgX100[0].toDouble()
        val k = ln(2.0) / (halfLifeDays * DAY_SEC)
        for (i in 1 until n) {
            val dt = (tSec[i] - tSec[i - 1]).coerceAtLeast(0).toDouble()
            val keep = exp(-k * dt)
            out[i] = out[i - 1] * keep + kgX100[i] * (1 - keep)
        }
        return out
    }

    /**
     * The change of the trend over the last [days] days before the newest weigh-in, in hundredths of a
     * kilogram (negative is a loss), or null with fewer than two weigh-ins in reach.
     */
    fun trendChange(entries: List<BodyMeasure>, days: Int): Int? {
        if (entries.size < 2) return null
        val sorted = entries.sortedBy { it.tSec }
        val t = LongArray(sorted.size) { sorted[it].tSec }
        val v = IntArray(sorted.size) { sorted[it].valueX100 }
        val trend = trend(t, v)
        val cutoff = t.last() - days * DAY_SEC.toLong()
        // Compare with the trend at the last weigh-in on or before the cutoff (else the oldest one).
        val baseIdx = t.indexOfLast { it <= cutoff }.coerceAtLeast(0)
        if (baseIdx == t.size - 1) return null
        return (trend.last() - trend[baseIdx]).roundToInt()
    }
}

/** Labels, display units and conversions for body measurement kinds. */
object BodyUnits {
    private const val KG_PER_LB = 0.45359237
    private const val CM_PER_INCH = 2.54

    /** The measurement kinds listed on the Measurements screen (weight and BMI have their own places). */
    val LISTED: List<BodyKind> = listOf(
        BodyKind.WAIST, BodyKind.HIPS, BodyKind.CHEST, BodyKind.ARM, BodyKind.THIGH, BodyKind.NECK,
        BodyKind.BODY_FAT, BodyKind.MUSCLE_MASS, BodyKind.BONE_MASS, BodyKind.BODY_WATER,
    )

    /** How a kind is measured. */
    enum class Dim { LENGTH, MASS, PERCENT, PLAIN }

    /** The dimension of [kind]. */
    fun dim(kind: BodyKind): Dim = when (kind) {
        BodyKind.WAIST, BodyKind.HIPS, BodyKind.CHEST, BodyKind.ARM, BodyKind.THIGH, BodyKind.NECK -> Dim.LENGTH
        BodyKind.WEIGHT, BodyKind.MUSCLE_MASS, BodyKind.BONE_MASS -> Dim.MASS
        BodyKind.BODY_FAT, BodyKind.BODY_WATER -> Dim.PERCENT
        BodyKind.BMI, BodyKind.CUSTOM -> Dim.PLAIN
    }

    /** A stored value (kind unit ×100) in display units (cm→in, kg→lb when imperial). */
    fun toDisplay(kind: BodyKind, valueX100: Int, units: Units): Double {
        val v = valueX100 / 100.0
        if (units == Units.METRIC) return v
        return when (dim(kind)) {
            Dim.LENGTH -> v / CM_PER_INCH
            Dim.MASS -> v / KG_PER_LB
            else -> v
        }
    }

    /** A display-unit value back to the stored value (kind unit ×100), rounded. */
    fun fromDisplay(kind: BodyKind, display: Double, units: Units): Int {
        val v = if (units == Units.METRIC) {
            display
        } else {
            when (dim(kind)) {
                Dim.LENGTH -> display * CM_PER_INCH
                Dim.MASS -> display * KG_PER_LB
                else -> display
            }
        }
        return (v * 100).roundToInt()
    }

    /** The unit symbol shown for [kind] ([customUnit] for custom kinds). */
    fun unit(kind: BodyKind, units: Units, customUnit: String = ""): String = when (dim(kind)) {
        Dim.LENGTH -> if (units == Units.METRIC) "cm" else "in"
        Dim.MASS -> if (units == Units.METRIC) "kg" else "lb"
        Dim.PERCENT -> "%"
        Dim.PLAIN -> if (kind == BodyKind.CUSTOM) customUnit else ""
    }

    /** A formatted value with its unit, for example "91 cm", "35.8 in", "24.0 %". */
    fun format(kind: BodyKind, valueX100: Int, units: Units, customUnit: String = "", locale: Locale = Locale.getDefault()): String =
        when (dim(kind)) {
            Dim.LENGTH -> Fmt.length(valueX100 / 100.0, units, locale)
            Dim.MASS -> Fmt.weight(valueX100, units, locale)
            Dim.PERCENT -> Fmt.number(valueX100 / 100.0, 1, locale) + " %"
            Dim.PLAIN -> {
                val n = Fmt.number(valueX100 / 100.0, 1, locale)
                val u = unit(kind, units, customUnit)
                if (u.isEmpty()) n else "$n $u"
            }
        }

    /** The number alone in display units with one decimal, for tiles ("91.5"). */
    fun number(kind: BodyKind, valueX100: Int, units: Units, locale: Locale = Locale.getDefault()): String =
        Fmt.number(toDisplay(kind, valueX100, units), 1, locale)
}
