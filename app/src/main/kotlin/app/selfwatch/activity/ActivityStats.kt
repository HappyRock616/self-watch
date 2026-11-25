/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.activity

import app.selfwatch.model.health.ActivityMinute
import app.selfwatch.model.health.Workout
import app.selfwatch.model.time.Day
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToInt

/**
 * Week boundaries of the "this week" Activity cards. Weeks start on Monday (ISO 8601, the same as the
 * week totals in the database), whatever the phone's locale, so a week's numbers never change with it.
 */
object ActivityWeek {
    /** Days in a week. */
    const val DAYS: Int = 7

    /** The Monday on or before [day]. */
    fun start(day: Day): Day = Day.of(day.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)))

    /** The seven days of the week holding [day], Monday first. */
    fun days(day: Day): List<Day> {
        val first = start(day)
        return (0 until DAYS).map { first.plusDays(it) }
    }
}

/** Moderate and vigorous minutes of one day. Intensity minutes count a vigorous minute twice. */
data class DayIntensity(val moderateMin: Int, val vigorousMin: Int) {
    /** Moderate minutes plus twice the vigorous minutes. */
    val intensityMin: Int get() = moderateMin + 2 * vigorousMin
}

/**
 * Intensity minutes and active days (catalogue C10 and C15).
 *
 * The watch marks each minute 0 (sedentary) to 3 (vigorous); 2 is moderate. A minute recorded by two
 * watches counts once, at the higher intensity. The weekly target is the user's active-minutes goal
 * (minutes per week, default 300; [ActivityGoals]); WHO 2020's minimum of 150 is shown as a note.
 */
object IntensityMath {
    /** [ActivityMinute.intensity] of a moderate minute. */
    const val MODERATE: Int = 2

    /** [ActivityMinute.intensity] of a vigorous minute. */
    const val VIGOROUS: Int = 3

    /** Active days a week shown as the aim ("most days"). */
    const val ACTIVE_DAYS_TARGET: Int = 5

    /**
     * A day is "active" (C15) with at least a seventh of the weekly active-minutes goal [weekGoal] in
     * intensity minutes (vigorous counting double): 43 with the default 300.
     */
    fun activeDayMin(weekGoal: Int): Int = ActivityGoals.dailyShare(weekGoal).coerceAtLeast(1)

    /** Moderate and vigorous minutes per local day in [zone]; one reading per minute (the highest). */
    fun byDay(minutes: List<ActivityMinute>, zone: ZoneId): Map<Day, DayIntensity> {
        val best = HashMap<Long, ActivityMinute>()
        for (m in minutes) {
            val key = Math.floorDiv(m.tSec, MINUTE_SEC)
            val seen = best[key]
            if (seen == null || m.intensity > seen.intensity) best[key] = m
        }
        val mod = HashMap<Day, Int>()
        val vig = HashMap<Day, Int>()
        for (m in best.values) {
            val day = Day.of(m.tSec, zone)
            when {
                m.intensity >= VIGOROUS -> vig[day] = (vig[day] ?: 0) + 1
                m.intensity == MODERATE -> mod[day] = (mod[day] ?: 0) + 1
                else -> {
                    mod.putIfAbsent(day, 0)
                }
            }
        }
        return (mod.keys + vig.keys).associateWith { DayIntensity(mod[it] ?: 0, vig[it] ?: 0) }
    }

    /**
     * Intensity minutes for each of [days]: from [byDay] where the watch recorded minutes, else the day
     * summary's active minutes ([summaryActiveMin], counted once, e.g. from an import), else null.
     */
    fun perDay(days: List<Day>, byDay: Map<Day, DayIntensity>, summaryActiveMin: Map<Day, Int>): List<Int?> =
        days.map { d -> byDay[d]?.intensityMin ?: summaryActiveMin[d] }

    /** Days among [perDay] with at least [dayMin] intensity minutes. */
    fun activeDays(perDay: List<Int?>, dayMin: Int): Int = perDay.count { (it ?: 0) >= dayMin }

    private const val MINUTE_SEC = 60L
}

/**
 * Strength (and balance) sessions (C13): days this week with a muscle-strengthening workout, against 2
 * (WHO 2020), or 3 from 65, when yoga, pilates and flexibility sessions count too (WHO's
 * "multicomponent" balance and strength activity; the watch has no separate balance sport).
 */
object StrengthRules {
    /** FIT `sport` training. */
    const val SPORT_TRAINING: Int = 10

    /** FIT `sub_sport` values (public FIT profile). */
    const val SUB_FLEXIBILITY: Int = 19
    const val SUB_STRENGTH: Int = 20
    const val SUB_YOGA: Int = 43
    const val SUB_PILATES: Int = 44

    /** Age from which balance sessions count and the target is [OLDER_TARGET]. */
    const val OLDER_AGE: Int = 65

    /** Days a week. */
    const val TARGET: Int = 2

    /** Days a week from [OLDER_AGE]. */
    const val OLDER_TARGET: Int = 3

    /** True when [w] counts; [withBalance] adds yoga, pilates and flexibility. */
    fun counts(w: Workout, withBalance: Boolean): Boolean {
        if (w.sport != SPORT_TRAINING) return false
        val sub = w.subSport
        return sub == SUB_STRENGTH || (withBalance && (sub == SUB_YOGA || sub == SUB_PILATES || sub == SUB_FLEXIBILITY))
    }

    /** Distinct local days in [zone] with a counting workout among [workouts]. */
    fun days(workouts: List<Workout>, withBalance: Boolean, zone: ZoneId): Int =
        workouts.filter { counts(it, withBalance) }.map { Day.of(it.startSec, zone) }.distinct().size
}

/** Where the maximum heart rate for the zones came from. */
enum class MaxHrSource {
    /** The setting [HrZones.MAX_HR_KEY]. */
    STORED,

    /** 220 − age, from the profile's birth year. */
    AGE,

    /** No birth year: [HrZones.DEFAULT_MAX_HR] (220 − 40). */
    DEFAULT,
}

/** The maximum heart rate used for the zones and where it came from. */
data class MaxHr(val bpm: Int, val source: MaxHrSource)

/**
 * Heart-rate zones as a share of maximum heart rate: under 50% is zone 0 (rest), then 50–60% zone 1,
 * 60–70% zone 2, 70–80% zone 3, 80–90% zone 4 and 90% and up zone 5. Maximum heart rate is the setting
 * [MAX_HR_KEY] when present, else 220 − age, else 180.
 *
 * **Training load** (C19) is the zone-weighted minutes: minutes in zone 1 × 1 + … + zone 5 × 5 (zone 0
 * counts 0), the heart-rate-zone method of the catalogue. **Easy** (C21) is time under 80% of maximum
 * (zones 0–3); **hard** is 80% and up (zones 4–5), roughly the first threshold of the three-zone model the
 * 75–80% easy reference comes from.
 */
object HrZones {
    /** Setting: a known maximum heart rate in bpm (absent: 220 − age). */
    const val MAX_HR_KEY: String = "activity.max_hr"

    /** Maximum heart rate without a birth year (220 − 40). */
    const val DEFAULT_MAX_HR: Int = 180

    /** Zones 0..5. */
    const val ZONES: Int = 6

    /** Easy is under this share of maximum heart rate, in percent. */
    const val EASY_BELOW_PCT: Int = 80

    /** Reference share of easy time, lower end, in percent. */
    const val EASY_REF_LOW_PCT: Int = 75

    /** Reference share of easy time, upper end, in percent. */
    const val EASY_REF_HIGH_PCT: Int = 80

    /** A heart-rate reading stands for at most this long (gaps longer than this are not counted). */
    const val SAMPLE_CAP_SEC: Long = 300

    /** Readings must cover at least this share of a workout, in percent, before they are used. */
    const val MIN_COVERAGE_PCT: Int = 50

    private const val PLAUSIBLE_MIN = 100
    private const val PLAUSIBLE_MAX = 230
    private const val AGE_FORMULA = 220
    private const val MIN_AGE = 10
    private const val MAX_AGE = 100

    /** The maximum heart rate from a [stored] value, else the age in [year] from [birthYear], else the default. */
    fun maxHr(stored: Int?, birthYear: Int?, year: Int): MaxHr = when {
        stored != null && stored in PLAUSIBLE_MIN..PLAUSIBLE_MAX -> MaxHr(stored, MaxHrSource.STORED)
        birthYear != null && year - birthYear in MIN_AGE..MAX_AGE -> MaxHr(AGE_FORMULA - (year - birthYear), MaxHrSource.AGE)
        else -> MaxHr(DEFAULT_MAX_HR, MaxHrSource.DEFAULT)
    }

    /** The zone (0..5) of [bpm] for [maxHr]. */
    fun zoneOf(bpm: Int, maxHr: Int): Int {
        if (maxHr <= 0) return 0
        val pct = bpm * 100 / maxHr
        return when {
            pct < 50 -> 0
            pct < 60 -> 1
            pct < 70 -> 2
            pct < 80 -> 3
            pct < 90 -> 4
            else -> 5
        }
    }

    /**
     * Seconds in each zone (index 0..5) of a workout from [startSec] to [endSec] lasting [durationSec]
     * (moving time). Heart-rate [readings] (epoch second to bpm, any order) inside the workout each stand
     * until the next reading, at most [SAMPLE_CAP_SEC]; when they cover at least [MIN_COVERAGE_PCT] of the
     * workout, the zone times are scaled to [durationSec]. Otherwise the whole duration goes to the zone
     * of [avgHr]. Null when neither is available.
     */
    fun zoneSeconds(readings: List<Pair<Long, Int>>, startSec: Long, endSec: Long, durationSec: Int, avgHr: Int?, maxHr: Int): IntArray? {
        val end = if (endSec > startSec) endSec else startSec + durationSec
        val window = end - startSec
        val inside = readings.filter { it.first in startSec until end && it.second > 0 }.sortedBy { it.first }
        val acc = LongArray(ZONES)
        var covered = 0L
        for (i in inside.indices) {
            val t = inside[i].first
            val next = if (i + 1 < inside.size) inside[i + 1].first else end
            val sec = minOf(next, end, t + SAMPLE_CAP_SEC) - t
            if (sec <= 0) continue
            acc[zoneOf(inside[i].second, maxHr)] += sec
            covered += sec
        }
        if (window > 0 && covered * 100 >= window * MIN_COVERAGE_PCT) {
            val target = if (durationSec > 0) durationSec.toLong() else window
            return IntArray(ZONES) { (acc[it].toDouble() * target / covered).roundToInt() }
        }
        val avg = avgHr?.takeIf { it > 0 } ?: return null
        return IntArray(ZONES).also { it[zoneOf(avg, maxHr)] = maxOf(durationSec, 0) }
    }

    /** Training load of [zoneSec]: minutes in each zone times the zone number, rounded. */
    fun load(zoneSec: IntArray): Int {
        var weighted = 0L
        for (z in 1 until minOf(ZONES, zoneSec.size)) weighted += zoneSec[z].toLong() * z
        return (weighted / 60.0).roundToInt()
    }

    /** Seconds under [EASY_BELOW_PCT] (zones 0–3). */
    fun easySec(zoneSec: IntArray): Int = (0..3).sumOf { zoneSec.getOrElse(it) { 0 } }

    /** Seconds at [EASY_BELOW_PCT] and up (zones 4–5). */
    fun hardSec(zoneSec: IntArray): Int = (4..5).sumOf { zoneSec.getOrElse(it) { 0 } }
}

/** One workout with its time in each heart-rate zone (null without heart rate), for load and easy/hard. */
data class SessionZones(val workout: Workout, val zoneSec: IntArray?, val fromAverage: Boolean) {
    /** Training load (0 without heart rate). */
    val load: Int get() = zoneSec?.let { HrZones.load(it) } ?: 0

    override fun equals(other: Any?): Boolean =
        other is SessionZones && other.workout == workout && other.zoneSec.contentEquals(zoneSec) && other.fromAverage == fromAverage

    override fun hashCode(): Int = workout.hashCode() * 31 + zoneSec.contentHashCode()
}

/**
 * A session much longer than the recent longest (C20).
 *
 * @property value the session's distance in metres when [byDistance], else its duration in seconds.
 * @property previousLongest the longest of the same kind in the [LongSessionCheck.WINDOW_DAYS] days before it.
 */
data class LongSession(val workout: Workout, val value: Int, val previousLongest: Int, val byDistance: Boolean) {
    /** How much longer, in percent, rounded. */
    val pctLonger: Int get() = if (previousLongest <= 0) 0 else ((value.toDouble() / previousLongest - 1) * 100).roundToInt()
}

/**
 * The long-session check (C20): a run, walk or hike more than [THRESHOLD_PCT] longer than the longest of
 * the same kind in the [WINDOW_DAYS] days before it. The evidence (WP-15, C20) is from runners; walks
 * and hikes are checked the same way, compared only with walks and hikes. Distance is compared when the
 * session has one, else duration.
 */
object LongSessionCheck {
    /** Days before a session it is compared with. */
    const val WINDOW_DAYS: Int = 30

    /** Sessions in the last this many days are checked. */
    const val RECENT_DAYS: Int = 7

    /** Flag above this many percent longer. */
    const val THRESHOLD_PCT: Int = 10

    private const val DAY_SEC = 86_400L
    private const val FAMILY_RUN = 1
    private const val FAMILY_WALK = 2

    /** The kind a sport is compared within, or null when it is not checked. */
    fun family(sport: Int): Int? = when (sport) {
        Sports.RUNNING -> FAMILY_RUN
        Sports.WALKING, Sports.HIKING -> FAMILY_WALK
        else -> null
    }

    /** The newest flagged session that started in the [RECENT_DAYS] days before [nowSec], or null. */
    fun check(workouts: List<Workout>, nowSec: Long): LongSession? {
        val recent = workouts.filter { family(it.sport) != null && it.startSec >= nowSec - RECENT_DAYS * DAY_SEC && it.startSec <= nowSec }
        for (w in recent.sortedByDescending { it.startSec }) {
            val fam = family(w.sport)
            val byDistance = (w.distanceM ?: 0) > 0
            val value = if (byDistance) w.distanceM ?: 0 else w.durationSec
            if (value <= 0) continue
            val before = workouts.filter {
                it !== w && family(it.sport) == fam && it.startSec < w.startSec && it.startSec >= w.startSec - WINDOW_DAYS * DAY_SEC &&
                    (!byDistance || (it.distanceM ?: 0) > 0)
            }
            val longest = before.maxOfOrNull { if (byDistance) it.distanceM ?: 0 else it.durationSec } ?: continue
            if (longest <= 0) continue
            if (value.toLong() * 100 > longest.toLong() * (100 + THRESHOLD_PCT)) return LongSession(w, value, longest, byDistance)
        }
        return null
    }
}
