/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.model.health

import kotlin.math.roundToInt

/** How the heart-rate zones are worked out (as on Garmin watches). */
public enum class HrZoneMethod(public val code: String) {
    /** Percent of maximum heart rate (Garmin's default). */
    MAX_HR("max"),

    /** Percent of heart-rate reserve: resting HR + percent × (max HR − resting HR). */
    HRR("hrr"),
    ;

    /** Lookup. */
    public companion object {
        /** The method stored as [code], else [MAX_HR]. */
        public fun of(code: String?): HrZoneMethod = entries.firstOrNull { it.code == code } ?: MAX_HR
    }
}

/** Where the maximum heart rate came from. */
public enum class MaxHrOrigin {
    /** Typed in by the user. */
    MANUAL,

    /** 220 − age. */
    AGE,

    /** No birth year: 180 (220 − 40). */
    DEFAULT,
}

/** Where the resting heart rate came from. */
public enum class RestingHrOrigin {
    /** Typed in by the user. */
    MANUAL,

    /** The watch's average resting heart rate of the last 7 days. */
    MEASURED,

    /** No reading: 60. */
    DEFAULT,
}

/**
 * The heart-rate zones in use.
 *
 * @property bounds the lowest bpm of zones 1..5 (5 values, strictly increasing). Under `bounds[0]` is
 *   zone 0 (no zone).
 * @property manual true when the user set the bounds by hand ("auto" otherwise: they follow the method,
 *   maximum and resting heart rate).
 */
public data class HrZoneSet(
    val method: HrZoneMethod,
    val maxHr: Int,
    val maxOrigin: MaxHrOrigin,
    val restingHr: Int,
    val restingOrigin: RestingHrOrigin,
    val bounds: List<Int>,
    val manual: Boolean,
) {
    /** The zone (0..5) of [bpm]. */
    public fun zoneOf(bpm: Int): Int = HrZoneMath.zoneOf(bpm, bounds)
}

/**
 * Heart-rate zone arithmetic, as Garmin does it.
 *
 * - Five zones; each starts at a percent of maximum heart rate (default method) or of heart-rate reserve:
 *   50, 60, 70, 80 and 90%. Ref: Garmin owner's manuals, "About Heart Rate Zones" (Forerunner 245/945/55,
 *   vívoactive 5), and the %HRR option in "Setting Your Heart Rate Zones".
 * - Auto: the maximum is 220 − age, as on the vívoactive 5 (automatic max-HR detection is only on
 *   Forerunner and fēnix watches). When a workout goes higher the app suggests that value; it never
 *   changes the zones by itself. The resting heart rate is the watch's 7-day average.
 * - Manual override: the user may set each zone's lowest bpm; "back to auto" clears it.
 * - A heart rate on a border counts in the higher zone.
 *
 * **Active (intensity) minutes** count a minute whose heart rate is in zone 2 or higher: zones 2–3 are
 * moderate (counts 1), zones 4–5 vigorous (counts 2) — Garmin's heart-rate-zone option for intensity
 * minutes. Below zone 2 nothing counts, so a raised heart rate at rest (stress, anxiety) is not exercise.
 */
public object HrZoneMath {
    /** Lower edge of zones 1..5 in percent. */
    public val PERCENTS: List<Int> = listOf(50, 60, 70, 80, 90)

    /** Zones 0..5. */
    public const val ZONES: Int = 6

    /** The lowest zone that counts as moderate activity. */
    public const val MODERATE_ZONE: Int = 2

    /** The lowest zone that counts as vigorous activity. */
    public const val VIGOROUS_ZONE: Int = 4

    /** Plausible maximum heart rates. */
    public val MAX_RANGE: IntRange = 100..230

    /** Plausible resting heart rates. */
    public val RESTING_RANGE: IntRange = 30..110

    /** Without a birth year: 220 − 40. */
    public const val DEFAULT_MAX_HR: Int = 180

    /** Without a resting reading. */
    public const val DEFAULT_RESTING_HR: Int = 60

    /** The zone bounds of [method] for [maxHr] and [restingHr]. */
    public fun bounds(method: HrZoneMethod, maxHr: Int, restingHr: Int): List<Int> {
        val raw = PERCENTS.map { p ->
            when (method) {
                HrZoneMethod.MAX_HR -> (maxHr * p / 100.0).roundToInt()
                HrZoneMethod.HRR -> restingHr + ((maxHr - restingHr).coerceAtLeast(0) * p / 100.0).roundToInt()
            }
        }
        return increasing(raw)
    }

    /** [b] made strictly increasing (each at least one above the one before). */
    private fun increasing(b: List<Int>): List<Int> {
        val out = ArrayList<Int>(b.size)
        for (v in b) out += if (out.isEmpty()) v else maxOf(v, out.last() + 1)
        return out
    }

    /** True when [b] is five strictly increasing plausible bpm values. */
    public fun valid(b: List<Int>?): Boolean =
        b != null && b.size == 5 && b.all { it in 30..250 } && (1 until 5).all { b[it] > b[it - 1] }

    /** The zone (0..5) of [bpm] for [bounds]. */
    public fun zoneOf(bpm: Int, bounds: List<Int>): Int {
        var z = 0
        for (i in bounds.indices) if (bpm >= bounds[i]) z = i + 1
        return z
    }

    /** The stored form of [bounds] ("z1,z2,z3,z4,z5"). */
    public fun format(bounds: List<Int>): String = bounds.joinToString(",")

    /** Stored bounds, or null when absent or invalid. */
    public fun parse(text: String?): List<Int>? =
        text?.split(',')?.map { it.trim().toIntOrNull() ?: return null }?.takeIf { valid(it) }

    /** The maximum heart rate: [manual] when plausible, else 220 − age from [birthYear] in [year], else 180. */
    public fun maxHr(manual: Int?, birthYear: Int?, year: Int): Pair<Int, MaxHrOrigin> {
        if (manual != null && manual in MAX_RANGE) return manual to MaxHrOrigin.MANUAL
        val age = birthYear?.let { year - it }?.takeIf { it in 10..100 }
        return if (age != null) (220 - age) to MaxHrOrigin.AGE else DEFAULT_MAX_HR to MaxHrOrigin.DEFAULT
    }

    /** A higher maximum to suggest: the highest workout heart rate [highestSeen] when it beats [maxHr]. */
    public fun suggestedMax(highestSeen: Int?, maxHr: Int): Int? =
        highestSeen?.takeIf { it in MAX_RANGE && it > maxHr }

    /** The resting heart rate: [manual] when plausible, else the [measured] average, else 60. */
    public fun restingHr(manual: Int?, measured: Int?): Pair<Int, RestingHrOrigin> = when {
        manual != null && manual in RESTING_RANGE -> manual to RestingHrOrigin.MANUAL
        measured != null && measured in RESTING_RANGE -> measured to RestingHrOrigin.MEASURED
        else -> DEFAULT_RESTING_HR to RestingHrOrigin.DEFAULT
    }

    /**
     * [ActivityMinute.intensity] for a minute in [zone]: 3 (vigorous) from zone 4, 2 (moderate) from
     * zone 2, otherwise 0 or 1 ([moving]).
     */
    public fun intensityOf(zone: Int, moving: Boolean): Int = when {
        zone >= VIGOROUS_ZONE -> 3
        zone >= MODERATE_ZONE -> 2
        moving -> 1
        else -> 0
    }
}
