/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.demo

import android.content.Context
import androidx.core.database.sqlite.transaction
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.Table
import app.selfwatch.fit.decode.WorkoutDecoder
import app.selfwatch.model.device.DeviceInfo
import app.selfwatch.model.device.DeviceKind
import app.selfwatch.model.device.SupportTier
import app.selfwatch.model.food.DiaryEntry
import app.selfwatch.model.food.Goal
import app.selfwatch.model.food.GoalKind
import app.selfwatch.model.food.Nutrients
import app.selfwatch.model.health.ActivityMinute
import app.selfwatch.model.health.BodyKind
import app.selfwatch.model.health.BodyMeasure
import app.selfwatch.model.health.DataSource
import app.selfwatch.model.health.MetricKind
import app.selfwatch.model.health.MetricSample
import app.selfwatch.model.health.SleepSession
import app.selfwatch.model.health.SleepStage
import app.selfwatch.model.health.SleepStageSample
import app.selfwatch.model.health.Workout
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.time.Clock
import app.selfwatch.model.time.Day
import app.selfwatch.model.time.SystemClock
import app.selfwatch.service.health.SummaryBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Debug-only demo data: a realistic stretch of generated watch data so the Body, Activity, Sleep,
 * Heart, Weight and Workout screens can be reviewed without a watch.
 *
 * Everything is tied to one dedicated `device` row (address [DEMO_ADDRESS], marked removed so the
 * service never tries to connect to it) and one import batch, so [clear] removes exactly what [load]
 * added: minutes, metrics and sleep by device; workouts, body measurements and diary lines by
 * import id (via `ImportDao.undo`); the workout FIT files in `files/workouts/demo`; goals and profile
 * values only if the demo had to fill them in. Day summaries are rebuilt from what remains.
 */
class DemoData(private val ctx: Context, private val db: SelfWatchDb, private val clock: Clock = SystemClock) {

    /** Fills the database. Clears an earlier demo first. Returns a short count summary. */
    suspend fun load(): String = withContext(Dispatchers.IO) {
        clear()
        val zone = clock.zone()
        val now = clock.nowSec()
        val today = clock.today()
        val deviceId = db.devices.upsert(
            DeviceInfo(0, DeviceKind.GARMIN_WATCH, DEMO_ADDRESS, "Demo watch", "Demo watch", null, null, null, SupportTier.UNVERIFIED, emptySet(), null),
            now,
        )
        db.devices.markRemoved(deviceId)
        val importId = db.imports.begin(now, IMPORTER, null)
        val first = today.plusDays(-HISTORY_DAYS)
        db.settings.setAll(
            mapOf(KEY_DEVICE to deviceId.toString(), KEY_IMPORT to importId.toString(), KEY_FIRST to first.yyyymmdd.toString()),
        )

        // Workouts first: their minutes count in the day's activity.
        val workouts = demoWorkouts(today, zone, now) + longRide(today, zone, now)
        val dir = File(ctx.filesDir, DEMO_DIR).apply { mkdirs() }
        for ((i, w) in workouts.withIndex()) {
            val points = DemoFit.track(w, BASE_LAT, BASE_LON, SEED + i)
            val bytes = DemoFit.write(w, points)
            val name = "${w.startSec}.fit"
            File(dir, name).writeBytes(bytes)
            when (val r = WorkoutDecoder.summary(bytes.inputStream(), deviceId)) {
                is Outcome.Ok -> db.workouts.insert(r.value.copy(fitPath = "$DEMO_DIR/$name"), importId)
                is Outcome.Fail -> SwLog.w(TAG, "demo workout did not decode: ${r.error}")
            }
        }
        val gym = DemoTraining.gymSessions(db, deviceId, importId, today, zone, now)

        var minutes = 0
        var metrics = 0
        for (offset in HISTORY_DAYS downTo 0) {
            val day = today.plusDays(-offset)
            val rnd = Random(SEED * 31 + day.yyyymmdd)
            val start = day.startSec(zone)
            val end = minOf(day.plusDays(1).startSec(zone), now)
            val dayWorkouts = workouts.filter { it.startSec in start until end }
            val list = dayMinutes(deviceId, start, end, offset, dayWorkouts, rnd)
            minutes += db.activity.insert(list)
            val samples = ArrayList<MetricSample>()
            samples += MetricSample(deviceId, MetricKind.RESTING_HR, start + 8 * HOUR, (56 + 3 * sin(offset / 9.0) + rnd.nextInt(-2, 3)).roundToInt())
            if (offset <= DETAIL_DAYS) samples += dayMetrics(deviceId, start, end, dayWorkouts, rnd) else samples += DemoTraining.workoutHeartRate(deviceId, dayWorkouts)
            metrics += db.metrics.insert(samples)
        }

        var nights = 0
        for (offset in SLEEP_DAYS downTo 0) {
            val wake = today.plusDays(-offset)
            val rnd = Random(SEED * 17 + wake.yyyymmdd)
            val bed = wake.plusDays(-1).startSec(zone) + 22 * HOUR + 45 * 60 + rnd.nextInt(0, 60) * 60
            // Before the usual wake-up time, last night ends a quarter of an hour ago (as after an early sync).
            val up = minOf(wake.startSec(zone) + 6 * HOUR + 15 * 60 + rnd.nextInt(0, 75) * 60, now - 15 * 60)
            if (up - bed < 4 * HOUR) continue
            val stages = nightStages(deviceId, bed, up, rnd)
            db.sleep.insertSession(SleepSession(deviceId, bed, up, 70 + rnd.nextInt(0, 22)))
            db.sleep.insertStages(stages)
            metrics += db.metrics.insert(nightMetrics(deviceId, bed, up, rnd))
            nights++
        }

        metrics += heartCardData(deviceId, workouts, today, zone, now)
        metrics += bodyCardHistory(deviceId, importId, today, zone, clock.nowSec())
        val weights = bodyData(importId, today, zone)
        val diary = diaryData(importId, today, zone, now) + rideFuel(importId, today, zone, now)
        fillGoalsAndProfile(today)

        SummaryBuilder(db, clock).refresh(first.let { f -> (0..HISTORY_DAYS).map { f.plusDays(it) }.toSet() })
        db.imports.setCounts(importId, mapOf("workout" to (workouts.size + gym).toLong(), "body" to weights.toLong(), "diary" to diary.toLong()))
        SwLog.i(TAG, "demo data loaded")
        "${workouts.size + gym} workouts, $minutes minutes, $metrics readings, $nights nights, $weights body, $diary diary"
    }

    /** Removes everything [load] added (no-op when there is no demo data). */
    suspend fun clear(): Unit = withContext(Dispatchers.IO) {
        val deviceId = db.settings.getLong(KEY_DEVICE) ?: db.devices.byAddress(DEMO_ADDRESS)?.id
        val importId = db.settings.getLong(KEY_IMPORT)
        val first = db.settings.getInt(KEY_FIRST)?.let { Day(it) }
        if (deviceId == null && importId == null) return@withContext
        val raw = db.writableDatabase
        raw.transaction {
            if (deviceId != null) {
                val a = arrayOf(deviceId.toString())
                for (t in listOf("activity_minute", "metric", "sleep_stage", "sleep_session")) delete(t, "device_id = ?", a)
            }
        }
        db.changes.notify(setOf(Table.ACTIVITY, Table.METRIC, Table.SLEEP_STAGE, Table.SLEEP_SESSION))
        if (importId != null) db.imports.undo(importId)
        File(ctx.filesDir, DEMO_DIR).deleteRecursively()

        // Goals and profile values the demo filled in.
        for (entry in db.settings.getList(KEY_GOALS)) {
            val (code, from) = entry.split(':').let { it[0].toInt() to it[1].toInt() }
            GoalKind.fromCode(code)?.let { db.goals.delete(it, Day(from)) }
        }
        if (db.settings.getBool(KEY_PROFILE)) {
            val p = db.settings.profile()
            db.settings.setProfile(p.copy(heightCm = null, birthYear = null))
        }

        if (deviceId != null) {
            try {
                raw.delete("device", "id = ?", arrayOf(deviceId.toString()))
                db.changes.notify(setOf(Table.DEVICE))
            } catch (e: android.database.SQLException) {
                SwLog.w(TAG, "demo device row kept", e)
            }
        }
        val today = clock.today()
        if (first != null) {
            raw.delete("day_summary", "day >= ? AND day <= ?", arrayOf(first.yyyymmdd.toString(), today.yyyymmdd.toString()))
            db.changes.notify(setOf(Table.DAY_SUMMARY))
            SummaryBuilder(db, clock).refresh((0..HISTORY_DAYS + 1).map { first.plusDays(it) }.filter { it <= today }.toSet())
        }
        db.settings.setAll(mapOf(KEY_DEVICE to null, KEY_IMPORT to null, KEY_FIRST to null, KEY_GOALS to null, KEY_PROFILE to null))
        SwLog.i(TAG, "demo data cleared")
    }

    /** True when demo data is loaded. */
    suspend fun isLoaded(): Boolean = db.settings.getLong(KEY_IMPORT) != null

    private fun demoWorkouts(today: Day, zone: java.time.ZoneId, now: Long): List<DemoWorkout> {
        // (days ago, sport, start hour, minutes, metres, avg HR, kcal)
        val plan = listOf(
            listOf(6, WALK, 7, 42, 3_200, 104, 210),
            listOf(4, RUN, 18, 28, 5_000, 151, 390),
            listOf(3, BIKE, 10, 55, 18_000, 132, 480),
            listOf(1, WALK, 12, 35, 2_800, 101, 180),
            listOf(0, RUN, 7, 31, 5_400, 148, 410),
        )
        val base = plan.map { p ->
            val start = today.plusDays(-p[0]).startSec(zone) + p[2] * HOUR + 5 * 60
            DemoWorkout(p[1], start, p[3] * 60, p[4], p[5], p[6])
        }
        return (base + DemoTraining.plan(today, zone)).filter { it.startSec + it.durationSec <= now }
    }

    private fun dayMinutes(deviceId: Long, start: Long, end: Long, offset: Int, workouts: List<DemoWorkout>, rnd: Random): List<ActivityMinute> {
        val out = ArrayList<ActivityMinute>()
        // The last two weeks mostly reach 10,000 steps (a streak); older days vary more.
        val walkMin = if (offset <= STREAK_DAYS) rnd.nextInt(55, 80) else rnd.nextInt(20, 75)
        val walkStart = start + (7 * 60 + rnd.nextInt(0, 60)) * 60L
        val lunchStart = start + (12 * 60 + rnd.nextInt(0, 30)) * 60L
        var t = start + 7 * HOUR
        val stop = minOf(end, start + 22 * HOUR)
        while (t < stop) {
            val w = workouts.firstOrNull { t >= it.startSec && t < it.startSec + it.durationSec }
            val inWalk = (t >= walkStart && t < walkStart + walkMin / 2 * 60L) || (t >= lunchStart && t < lunchStart + (walkMin - walkMin / 2) * 60L)
            val m = when {
                w != null && w.sport == BIKE -> ActivityMinute(deviceId, t, 0, 85 + rnd.nextInt(0, 15), 32_000, 3, 2)
                w != null -> {
                    val steps = if (w.sport == RUN) 160 + rnd.nextInt(0, 12) else 112 + rnd.nextInt(0, 10)
                    ActivityMinute(deviceId, t, steps, if (w.sport == RUN) 130 + rnd.nextInt(0, 15) else 55 + rnd.nextInt(0, 10), steps * (if (w.sport == RUN) 105 else 75), if (w.sport == RUN) 3 else 2, w.sport)
                }
                inWalk -> {
                    val steps = 105 + rnd.nextInt(0, 15)
                    ActivityMinute(deviceId, t, steps, 50 + rnd.nextInt(0, 12), steps * 74, 2, 6)
                }
                rnd.nextInt(0, 4) == 0 -> {
                    val steps = rnd.nextInt(5, 36)
                    ActivityMinute(deviceId, t, steps, 12 + rnd.nextInt(0, 10), steps * 70, 1, null)
                }
                else -> null
            }
            if (m != null) out += m
            t += 60
        }
        return out
    }

    private fun dayMetrics(deviceId: Long, start: Long, end: Long, workouts: List<DemoWorkout>, rnd: Random): List<MetricSample> {
        val out = ArrayList<MetricSample>()
        var t = start + 7 * HOUR
        val stop = minOf(end, start + 22 * HOUR)
        while (t < stop) {
            val w = workouts.firstOrNull { t >= it.startSec && t < it.startSec + it.durationSec }
            val hr = if (w != null) w.avgHr + rnd.nextInt(-8, 9) else 68 + (8 * sin((t - start) / 7_000.0)).roundToInt() + rnd.nextInt(-4, 5)
            out += MetricSample(deviceId, MetricKind.HEART_RATE, t, hr)
            if ((t / 60) % 3 == 0L) {
                val stress = if (w != null) 70 + rnd.nextInt(0, 20) else (30 + 20 * sin((t - start) / 9_000.0)).roundToInt() + rnd.nextInt(-8, 9)
                out += MetricSample(deviceId, MetricKind.STRESS, t, stress.coerceIn(0, 100))
            }
            t += 120
        }
        return out
    }

    private fun nightStages(deviceId: Long, bed: Long, up: Long, rnd: Random): List<SleepStageSample> {
        val out = ArrayList<SleepStageSample>()
        var t = bed
        out += SleepStageSample(deviceId, t, SleepStage.AWAKE)
        t += rnd.nextInt(8, 18) * 60
        var cycle = 0
        while (t < up - 20 * 60) {
            // A 90-minute cycle: light, deep (less later in the night), light, REM (more later), sometimes a short wake.
            val deep = (35 - cycle * 8).coerceAtLeast(5) + rnd.nextInt(-4, 5)
            val rem = (12 + cycle * 7).coerceAtMost(40) + rnd.nextInt(-3, 4)
            val parts = listOf(
                SleepStage.LIGHT to rnd.nextInt(18, 30),
                SleepStage.DEEP to deep,
                SleepStage.LIGHT to rnd.nextInt(10, 20),
                SleepStage.REM to rem,
            )
            for ((stage, min) in parts) {
                if (t >= up - 10 * 60) break
                out += SleepStageSample(deviceId, t, stage)
                t += min.coerceAtLeast(3) * 60
            }
            if (rnd.nextInt(0, 3) == 0 && t < up - 20 * 60) {
                out += SleepStageSample(deviceId, t, SleepStage.AWAKE)
                t += rnd.nextInt(2, 7) * 60
            }
            cycle++
        }
        out += SleepStageSample(deviceId, minOf(t, up - 5 * 60), SleepStage.AWAKE)
        return out.distinctBy { it.tSec }
    }

    private fun nightMetrics(deviceId: Long, bed: Long, up: Long, rnd: Random): List<MetricSample> {
        val out = ArrayList<MetricSample>()
        val len = (up - bed).toDouble()
        var t = bed
        while (t < up) {
            val f = (t - bed) / len
            // Heart rate dips through the first half of the night, rises towards morning.
            val hr = 58 - 7 * sin(f * Math.PI) + rnd.nextInt(-2, 3)
            out += MetricSample(deviceId, MetricKind.HEART_RATE, t, hr.roundToInt())
            out += MetricSample(deviceId, MetricKind.RESPIRATION, t, (145 - 15 * sin(f * Math.PI) + rnd.nextInt(-6, 7)).roundToInt())
            if ((t - bed) % 300 < 120) {
                out += MetricSample(deviceId, MetricKind.HRV_RMSSD, t, (44 + 14 * sin(f * Math.PI) + rnd.nextInt(-5, 6)).roundToInt())
                out += MetricSample(deviceId, MetricKind.SPO2, t, 95 + rnd.nextInt(-2, 4))
            }
            if ((t - bed) % 1_800 < 120) {
                out += MetricSample(deviceId, MetricKind.SKIN_TEMP_DELTA, t, (-20 + 40 * f + rnd.nextInt(-10, 11)).roundToInt())
            }
            t += 120
        }
        return out
    }

    /**
     * Additions for the heart-rate cards: heart rate every minute today, the minutes after the last two days'
     * workouts (a recovery to read), and the watch's energy level every 15 minutes today.
     */
    private suspend fun heartCardData(deviceId: Long, workouts: List<DemoWorkout>, today: Day, zone: java.time.ZoneId, now: Long): Int {
        val out = ArrayList<MetricSample>()
        val start = today.startSec(zone)
        val rnd = Random(SEED * 41 + today.yyyymmdd)
        // The odd minutes between the existing every-other-minute readings, 7:00 until now.
        var t = start + 7 * HOUR + 60
        while (t < minOf(now, start + 22 * HOUR)) {
            val w = workouts.firstOrNull { t >= it.startSec && t < it.startSec + it.durationSec }
            val hr = if (w != null) w.avgHr + rnd.nextInt(-8, 9) else 68 + (8 * sin((t - start) / 7_000.0)).roundToInt() + rnd.nextInt(-4, 5)
            out += MetricSample(deviceId, MetricKind.HEART_RATE, t, hr)
            t += 120
        }
        // After each workout of today and yesterday: the end, then a fall over the next minutes.
        for (w in workouts.filter { it.startSec >= today.plusDays(-1).startSec(zone) && it.sport != BIKE }) {
            val end = w.startSec + w.durationSec
            val peak = w.avgHr + 8
            for ((i, drop) in RECOVERY_DROP.withIndex()) {
                val at = end + i * 60L
                if (at <= now) out += MetricSample(deviceId, MetricKind.HEART_RATE, at, peak - drop)
            }
            out += MetricSample(deviceId, MetricKind.HEART_RATE, end - 60, peak - 2)
        }
        // Energy level: refilling through the night, then down through the day with a small lift after lunch.
        var e = start
        while (e <= now) {
            val h = (e - start) / 3_600.0
            val base = if (h < 6.5) 60 + 5 * h else 92 - 3.2 * (h - 6.5) + (if (h in 12.5..14.0) 4 else 0)
            val level = (base + rnd.nextInt(-2, 3)).roundToInt().coerceIn(5, 100)
            out += MetricSample(deviceId, MetricKind.ENERGY_LEVEL, e, level)
            e += 15 * 60
        }
        return db.metrics.insert(out)
    }

    private suspend fun bodyData(importId: Long, today: Day, zone: java.time.ZoneId): Int {
        var n = 0
        val rnd = Random(SEED + 7)
        for (offset in WEIGHT_DAYS downTo 0) {
            val d = today.plusDays(-offset)
            val t = d.startSec(zone) + 7 * HOUR + 10 * 60
            if (t > clock.nowSec()) continue
            val progress = (WEIGHT_DAYS - offset).toDouble() / WEIGHT_DAYS
            if (rnd.nextInt(0, 7) < 5) {
                val kg = 84.6 - 2.3 * progress + 0.25 * sin(offset / 2.3) + rnd.nextDouble(-0.35, 0.35)
                db.body.insert(BodyMeasure(0, t, BodyKind.WEIGHT, null, (kg * 100).roundToInt(), DataSource.SCALE, null, importId))
                n++
            }
            if (offset % 7 == 0) {
                db.body.insert(BodyMeasure(0, t + 60, BodyKind.BODY_FAT, null, ((26.1 - 1.8 * progress + rnd.nextDouble(-0.3, 0.3)) * 100).roundToInt(), DataSource.SCALE, null, importId))
                n++
            }
            if (offset % 14 == 0) {
                db.body.insert(BodyMeasure(0, t + 120, BodyKind.WAIST, null, ((93.6 - 2.4 * progress) * 100).roundToInt(), DataSource.MANUAL, null, importId))
                db.body.insert(BodyMeasure(0, t + 180, BodyKind.HIPS, null, ((103.4 - 1.5 * progress) * 100).roundToInt(), DataSource.MANUAL, null, importId))
                n += 2
            }
            if (offset % 30 == 0) {
                db.body.insert(BodyMeasure(0, t + 240, BodyKind.CHEST, null, ((101.0 - 1.0 * progress) * 100).roundToInt(), DataSource.MANUAL, null, importId))
                n++
            }
        }
        return n
    }

    /**
     * Extra history for the Body cards and their charts: lighter nights (sleep, overnight HRV, breathing,
     * skin temperature) from [SLEEP_DAYS] + 1 back to [BODY_NIGHT_DAYS] days ago, so the HRV and body
     * signal baselines have about two months to learn from, and strength sessions twice a week (from two
     * days ago back, so the newest workout is still one with a FIT file) for the protein and strength chart.
     * Returns the readings added.
     */
    private suspend fun bodyCardHistory(deviceId: Long, importId: Long, today: Day, zone: java.time.ZoneId, now: Long): Int {
        var n = 0
        for (offset in BODY_NIGHT_DAYS downTo SLEEP_DAYS + 1) {
            val wake = today.plusDays(-offset)
            val rnd = Random(SEED * 23 + wake.yyyymmdd)
            val bed = wake.plusDays(-1).startSec(zone) + 23 * HOUR + rnd.nextInt(0, 40) * 60
            val up = wake.startSec(zone) + 6 * HOUR + 30 * 60 + rnd.nextInt(0, 60) * 60
            db.sleep.insertSession(SleepSession(deviceId, bed, up, 68 + rnd.nextInt(0, 22)))
            val stages = ArrayList<SleepStageSample>()
            var t = bed
            val cycle = listOf(SleepStage.LIGHT to 25, SleepStage.DEEP to 25, SleepStage.LIGHT to 15, SleepStage.REM to 25)
            stages += SleepStageSample(deviceId, t, SleepStage.AWAKE)
            t += 10 * 60
            while (t < up - 30 * 60) {
                for ((stage, min) in cycle) {
                    if (t >= up - 15 * 60) break
                    stages += SleepStageSample(deviceId, t, stage)
                    t += (min + rnd.nextInt(-5, 6)) * 60L
                }
            }
            stages += SleepStageSample(deviceId, up - 5 * 60, SleepStage.AWAKE)
            db.sleep.insertStages(stages.distinctBy { it.tSec })
            val samples = ArrayList<MetricSample>()
            // A slow drift in HRV over the weeks, so the baseline band has some width.
            val hrvBase = 48 + 4 * sin(offset / 6.0)
            var m = bed + 15 * 60
            while (m < up) {
                val f = (m - bed).toDouble() / (up - bed)
                samples += MetricSample(deviceId, MetricKind.HRV_RMSSD, m, (hrvBase + 10 * sin(f * Math.PI) + rnd.nextInt(-4, 5)).roundToInt())
                samples += MetricSample(deviceId, MetricKind.RESPIRATION, m, (142 - 12 * sin(f * Math.PI) + rnd.nextInt(-5, 6)).roundToInt())
                samples += MetricSample(deviceId, MetricKind.SKIN_TEMP_DELTA, m, (-15 + 30 * f + rnd.nextInt(-10, 11)).roundToInt())
                m += 30 * 60
            }
            n += db.metrics.insert(samples)
        }
        val strength = (STRENGTH_FIRST_DAY..WEIGHT_DAYS).filter { it % 7 == 2 || it % 7 == 5 }.mapNotNull { offset ->
            val start = today.plusDays(-offset).startSec(zone) + 17 * HOUR + 30 * 60
            val minutes = 45
            if (start + minutes * 60 > now) return@mapNotNull null
            Workout(
                0, deviceId, TRAINING, STRENGTH_SUB_SPORT, start, start + minutes * 60, minutes * 60, null, 240,
                112, 148, null, null, false, null, null, null, DataSource.WATCH,
            )
        }
        for (w in strength) db.workouts.insert(w, importId)
        return n
    }

    private suspend fun diaryData(importId: Long, today: Day, zone: java.time.ZoneId, now: Long): Int {
        val meals = listOf(Triple(1L, 8, 380..460), Triple(2L, 13, 560..760), Triple(3L, 19, 620..860), Triple(4L, 16, 120..260))
        val names = listOf(
            listOf("Greek yogurt with berries", "Oatmeal with banana", "Eggs on toast"),
            listOf("Chicken wrap", "Turkey sandwich", "Lentil soup with bread"),
            listOf("Salmon with rice", "Pasta with tomato sauce", "Chicken stir-fry"),
            listOf("Apple and almonds", "Protein bar", "Hummus and carrots"),
        )
        val list = ArrayList<DiaryEntry>()
        // The whole weight range is logged, so the Weight chart's calorie layers line up with the weigh-ins.
        // Friday and Saturday dinners run larger: the weekly bumps show against the weight trend.
        for (offset in DIARY_DAYS downTo 0) {
            val d = today.plusDays(-offset)
            val rnd = Random(SEED * 5 + d.yyyymmdd)
            val weekend = d.toLocalDate().dayOfWeek.let { it == java.time.DayOfWeek.FRIDAY || it == java.time.DayOfWeek.SATURDAY }
            for ((i, m) in meals.withIndex()) {
                val t = d.startSec(zone) + m.second * HOUR
                if (t > now) continue
                val kcal = rnd.nextInt(m.third.first, m.third.last) + if (weekend && m.first == DINNER_SLOT) WEEKEND_EXTRA_KCAL else 0
                val n = demoNutrients(Nutrients(kcal * 10, kcal / 5 * 10 / 4, kcal / 3 * 10 / 9, null, kcal / 2 * 10 / 4, null, null, null, null), d, i)
                list += DiaryEntry(0, d, m.first, t, null, names[i][rnd.nextInt(names[i].size)], null, 100, "1 serving", null, n, importId)
            }
        }
        return db.diary.addAll(list)
    }

    /** A long bike ride yesterday morning, so the Food tab's workout fuel card has something to check. */
    private fun longRide(today: Day, zone: java.time.ZoneId, now: Long): List<DemoWorkout> {
        val start = today.plusDays(-1).startSec(zone) + 8 * HOUR + 5 * 60
        return listOf(DemoWorkout(BIKE, start, LONG_RIDE_MIN * 60, 52_000, 138, 1_350)).filter { it.startSec + it.durationSec <= now }
    }

    /** Snacks logged before and during [longRide], with carbs (and one without sodium data). */
    private suspend fun rideFuel(importId: Long, today: Day, zone: java.time.ZoneId, now: Long): Int {
        val day = today.plusDays(-1)
        val start = day.startSec(zone) + 8 * HOUR + 5 * 60
        if (start + LONG_RIDE_MIN * 60 > now) return 0
        // (minutes from the start, name, kcal, carbs g ×10, sugar g ×10, fibre g ×10, sodium mg or null)
        val snacks = listOf(
            listOf(-20, "Banana", 105, 270, 144, 31, 1),
            listOf(40, "Energy gel", 100, 250, 120, 0, 60),
            listOf(80, "Sports drink, 500 ml", 120, 300, 280, 0, null),
        )
        val list = snacks.map { s ->
            val kcal = s[2] as Int
            val n = Nutrients(kcal * 10, 10, 3, null, s[3] as Int, s[4] as Int, s[5] as Int, s[6] as Int?, null)
            DiaryEntry(0, day, SNACK_SLOT, start + (s[0] as Int) * 60L, null, s[1] as String, null, 100, "1 serving", null, n, importId)
        }
        return db.diary.addAll(list)
    }

    /**
     * [base] with sodium, fibre and total sugar added, from the calories of meal slot index [slot] on
     * [day]. Snacks on odd days have no sodium or fibre value, so the Food cards show partial coverage.
     */
    private fun demoNutrients(base: Nutrients, day: Day, slot: Int): Nutrients {
        val kcal = (base.kcalX10 ?: 0) / 10
        val rnd = Random(SEED * 11 + day.yyyymmdd * 4 + slot)
        val unknown = slot == 3 && day.yyyymmdd % 2 == 1
        val sugarPerKcal = if (slot == 0) 0.05 else 0.025
        return base.copy(
            sugarX10 = (kcal * sugarPerKcal * 10 * rnd.nextDouble(0.7, 1.3)).roundToInt(),
            fibreX10 = if (unknown) null else (kcal * 0.012 * 10 * rnd.nextDouble(0.6, 1.4)).roundToInt(),
            sodiumMg = if (unknown) null else (kcal * 1.15 * rnd.nextDouble(0.6, 1.4)).roundToInt(),
        )
    }

    private suspend fun fillGoalsAndProfile(today: Day) {
        val added = ArrayList<String>()
        val from = today.plusDays(-HISTORY_DAYS)
        for ((kind, value) in listOf(GoalKind.KCAL_IN to 2_100, GoalKind.STEPS to 10_000, GoalKind.WEIGHT_KG_X100 to 78_00, GoalKind.ACTIVE_MIN to 300)) {
            if (db.goals.history(kind).isEmpty()) {
                db.goals.set(Goal(kind, value, from))
                added += "${kind.code}:${from.yyyymmdd}"
            }
        }
        db.settings.setList(KEY_GOALS, added)
        val p = db.settings.profile()
        if (p.heightCm == null && p.birthYear == null) {
            db.settings.setProfile(p.copy(heightCm = 178, birthYear = today.year - 41))
            db.settings.setBool(KEY_PROFILE, true)
        }
    }

    /** Constants. */
    companion object {
        private const val TAG = "DemoData"

        /** The demo device's (invalid, documentation-range) Bluetooth address. */
        const val DEMO_ADDRESS: String = "00:00:5E:00:53:D0"

        /** Importer id of the demo batch. */
        const val IMPORTER: String = "demo"

        /** Folder of the demo FIT files, relative to `filesDir`. */
        const val DEMO_DIR: String = "workouts/demo"

        private const val KEY_DEVICE = "demo.device_id"
        private const val KEY_IMPORT = "demo.import_id"
        private const val KEY_FIRST = "demo.first_day"
        private const val KEY_GOALS = "demo.goals"
        private const val KEY_PROFILE = "demo.profile"

        private const val SEED = 2026
        private const val HOUR = 3_600L
        private const val HISTORY_DAYS = 120
        private const val DETAIL_DAYS = 7
        private const val SLEEP_DAYS = 30
        private const val STREAK_DAYS = 12
        private const val WEIGHT_DAYS = 90
        private const val DIARY_DAYS = WEIGHT_DAYS
        private const val DINNER_SLOT = 3L
        private const val WEEKEND_EXTRA_KCAL = 350
        private const val RUN = 1
        private const val BIKE = 2
        private const val WALK = 11
        private const val SNACK_SLOT = 4L
        private const val LONG_RIDE_MIN = 130
        private const val TRAINING = 10
        private const val STRENGTH_SUB_SPORT = 20
        private const val STRENGTH_FIRST_DAY = 2
        private const val BODY_NIGHT_DAYS = 70
        private val RECOVERY_DROP = listOf(0, 22, 35, 40, 43, 45)

        // A neutral spot for the generated routes (a park loop; no real person's route).
        private const val BASE_LAT = 47.3600
        private const val BASE_LON = 8.5500
    }
}
