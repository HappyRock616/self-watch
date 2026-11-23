/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.interop.db

import android.database.sqlite.SQLiteDatabase
import app.selfwatch.interop.api.DeviceSeries
import app.selfwatch.interop.api.ImportData
import app.selfwatch.interop.io.LocalStamp
import app.selfwatch.model.health.MetricKind
import java.time.ZoneId
import java.util.Locale

/**
 * Which items of an [ImportData] already exist in `selfwatch.db`. One flag array per area, in the
 * order of the data's lists. Rules:
 * - diary: same day, meal slot, name and kcal;
 * - body: same time, kind (and custom kind);
 * - workouts and exercises: a workout with the same start time;
 * - water: same time and amount;
 * - foods: a custom food with the same name and brand (case-insensitive); recipes: same name;
 * - watch data: any device already has a minute (activity), sample of the kind (metric), stage or
 *   sleep session at that time, so re-importing a backup, or importing data the watch already synced,
 *   does not count twice.
 */
internal class Duplicates private constructor(
    val diary: BooleanArray,
    val body: BooleanArray,
    val exercises: BooleanArray,
    val workouts: BooleanArray,
    val recipes: BooleanArray,
    val foods: BooleanArray,
    val water: BooleanArray,
    val activity: List<BooleanArray>,
    val metrics: List<BooleanArray>,
    val stages: List<BooleanArray>,
    val sessions: List<BooleanArray>,
) {
    /** The number of duplicates over every area. */
    val total: Int
        get() = listOf(diary, body, exercises, workouts, recipes, foods, water).sumOf { it.count { b -> b } } +
            (activity + metrics + stages + sessions).sumOf { it.count { b -> b } }

    /** Builders. */
    companion object {
        /** Nothing is a duplicate (the user chose to import everything). */
        fun none(data: ImportData): Duplicates = Duplicates(
            BooleanArray(data.diary.size), BooleanArray(data.body.size), BooleanArray(data.exercises.size),
            BooleanArray(data.workouts.size), BooleanArray(data.recipes.size), BooleanArray(data.foods.size),
            BooleanArray(data.water.size), data.devices.map { BooleanArray(it.activity.size) },
            data.devices.map { BooleanArray(it.metrics.size) }, data.devices.map { BooleanArray(it.sleepStages.size) },
            data.devices.map { BooleanArray(it.sleepSessions.size) },
        )

        /** Compares [data] with the rows in [d]; diary lines are placed with [slots] and local times read in [zone]. */
        fun find(d: SQLiteDatabase, data: ImportData, slots: MealSlots, zone: ZoneId): Duplicates {
            val diary = BooleanArray(data.diary.size)
            if (data.diary.isNotEmpty()) {
                val lo = data.diary.minOf { it.day.yyyymmdd }
                val hi = data.diary.maxOf { it.day.yyyymmdd }
                val keys = HashSet<String>()
                d.rawQuery("SELECT day, meal_slot_id, name, kcal_x10 FROM diary_entry WHERE day BETWEEN ? AND ?", arrayOf("$lo", "$hi")).use { c ->
                    while (c.moveToNext()) keys.add(diaryKey(c.getInt(0), c.getLong(1), c.getString(2), c.getInt(3)))
                }
                data.diary.forEachIndexed { i, r ->
                    diary[i] = diaryKey(r.day.yyyymmdd, slots.resolve(r.meal, r.secOfDay), r.name, r.nutrients.kcalX10 ?: 0) in keys
                }
            }

            val body = BooleanArray(data.body.size)
            if (data.body.isNotEmpty()) {
                val keys = HashSet<String>()
                d.rawQuery(
                    "SELECT t_sec, kind, custom_kind_id FROM body_measure WHERE t_sec BETWEEN ? AND ?",
                    arrayOf("${data.body.minOf { it.tSec }}", "${data.body.maxOf { it.tSec }}"),
                ).use { c -> while (c.moveToNext()) keys.add("${c.getLong(0)}|${c.getInt(1)}|${if (c.isNull(2)) "" else c.getLong(2)}") }
                data.body.forEachIndexed { i, m -> body[i] = "${m.tSec}|${m.kind.code}|${m.customKindId ?: ""}" in keys }
            }

            val starts = data.workouts.map { it.workout.startSec } + data.exercises.map { exerciseStart(it.day, it.secOfDay, zone) }
            val known = if (starts.isEmpty()) LongArray(0) else times(d, "SELECT start_sec FROM workout WHERE start_sec BETWEEN ? AND ?", starts.min(), starts.max())
            val workouts = BooleanArray(data.workouts.size) { known.has(data.workouts[it].workout.startSec) }
            val exercises = BooleanArray(data.exercises.size) { known.has(exerciseStart(data.exercises[it].day, data.exercises[it].secOfDay, zone)) }

            val water = BooleanArray(data.water.size)
            if (data.water.isNotEmpty()) {
                val stamps = data.water.map { waterTime(it.day, it.secOfDay, zone) }
                val keys = HashSet<String>()
                d.rawQuery("SELECT t_sec, ml FROM water WHERE t_sec BETWEEN ? AND ?", arrayOf("${stamps.min()}", "${stamps.max()}")).use { c ->
                    while (c.moveToNext()) keys.add("${c.getLong(0)}|${c.getInt(1)}")
                }
                data.water.forEachIndexed { i, w -> water[i] = "${stamps[i]}|${w.ml}" in keys }
            }

            val foods = BooleanArray(data.foods.size)
            if (data.foods.isNotEmpty()) {
                val keys = HashSet<String>()
                d.rawQuery("SELECT name, brand FROM custom_food", null).use { c ->
                    while (c.moveToNext()) keys.add(foodKey(c.getString(0), if (c.isNull(1)) null else c.getString(1)))
                }
                data.foods.forEachIndexed { i, f -> foods[i] = foodKey(f.name, f.brand) in keys }
            }

            val recipes = BooleanArray(data.recipes.size)
            if (data.recipes.isNotEmpty()) {
                val keys = HashSet<String>()
                d.rawQuery("SELECT name FROM recipe", null).use { c -> while (c.moveToNext()) keys.add(c.getString(0).trim().lowercase(Locale.ROOT)) }
                data.recipes.forEachIndexed { i, r -> recipes[i] = r.name.trim().lowercase(Locale.ROOT) in keys }
            }

            return Duplicates(
                diary, body, exercises, workouts, recipes, foods, water,
                data.devices.map { s -> flags(d, s.activity.map { it.tSec }, "SELECT t_sec FROM activity_minute WHERE t_sec BETWEEN ? AND ?") },
                data.devices.map { s -> metricFlags(d, s) },
                data.devices.map { s -> flags(d, s.sleepStages.map { it.tSec }, "SELECT t_sec FROM sleep_stage WHERE t_sec BETWEEN ? AND ?") },
                data.devices.map { s -> flags(d, s.sleepSessions.map { it.startSec }, "SELECT start_sec FROM sleep_session WHERE start_sec BETWEEN ? AND ?") },
            )
        }

        private fun metricFlags(d: SQLiteDatabase, s: DeviceSeries): BooleanArray {
            val out = BooleanArray(s.metrics.size)
            val byKind = s.metrics.withIndex().groupBy { it.value.kind }
            for ((kind: MetricKind, items) in byKind) {
                val known = times(
                    d, "SELECT t_sec FROM metric WHERE kind = ${kind.code} AND t_sec BETWEEN ? AND ?",
                    items.minOf { it.value.tSec }, items.maxOf { it.value.tSec },
                )
                for (it in items) out[it.index] = known.has(it.value.tSec)
            }
            return out
        }

        private fun flags(d: SQLiteDatabase, ts: List<Long>, sql: String): BooleanArray {
            if (ts.isEmpty()) return BooleanArray(0)
            val known = times(d, sql, ts.min(), ts.max())
            return BooleanArray(ts.size) { known.has(ts[it]) }
        }

        /** The sorted values of the single-column [sql] with the range [lo]..[hi] bound. */
        private fun times(d: SQLiteDatabase, sql: String, lo: Long, hi: Long): LongArray {
            var arr = LongArray(256)
            var n = 0
            d.rawQuery(sql, arrayOf("$lo", "$hi")).use { c ->
                while (c.moveToNext()) {
                    if (n == arr.size) arr = arr.copyOf(n * 2)
                    arr[n++] = c.getLong(0)
                }
            }
            val out = arr.copyOf(n)
            out.sort()
            return out
        }

        private fun LongArray.has(v: Long): Boolean = java.util.Arrays.binarySearch(this, v) >= 0

        private fun diaryKey(day: Int, slot: Long, name: String, kcalX10: Int): String =
            "$day|$slot|${name.trim().lowercase(Locale.ROOT)}|$kcalX10"

        private fun foodKey(name: String, brand: String?): String =
            name.trim().lowercase(Locale.ROOT) + "|" + (brand ?: "").trim().lowercase(Locale.ROOT)

        /** Start time of an exercise logged without a track (noon when it has no time). */
        fun exerciseStart(day: app.selfwatch.model.time.Day, secOfDay: Int?, zone: ZoneId): Long =
            LocalStamp(day, secOfDay?.coerceIn(0, MAX_SEC)).epochSec(zone)

        /** Time of a water entry (noon when it has no time). */
        fun waterTime(day: app.selfwatch.model.time.Day, secOfDay: Int?, zone: ZoneId): Long =
            LocalStamp(day, secOfDay?.coerceIn(0, MAX_SEC)).epochSec(zone)

        /** Last second of a day. */
        const val MAX_SEC: Int = 86_399
    }
}
