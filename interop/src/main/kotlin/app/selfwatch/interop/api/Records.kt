/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.interop.api

import app.selfwatch.model.food.Food
import app.selfwatch.model.food.Nutrients
import app.selfwatch.model.food.Recipe
import app.selfwatch.model.health.ActivityMinute
import app.selfwatch.model.health.BodyMeasure
import app.selfwatch.model.health.MetricSample
import app.selfwatch.model.health.SleepSession
import app.selfwatch.model.health.SleepStageSample
import app.selfwatch.model.health.TrackPoint
import app.selfwatch.model.health.Workout
import app.selfwatch.model.time.Day

/**
 * One food diary line as it travels between Self Watch and other apps. Unlike
 * [app.selfwatch.model.food.DiaryEntry] it names its meal instead of pointing at a meal slot row,
 * and carries a local time of day instead of an epoch time, so it needs no database to build.
 *
 * @property meal meal name as the source wrote it, for example "Breakfast".
 * @property secOfDay local time of day in seconds, if the source had one.
 * @property amountX100 number of servings ×100.
 * @property servingLabel serving unit, for example "cup" or "g".
 * @property gramsX10 total grams ×10, if known.
 * @property nutrients totals for this line.
 */
data class DiaryRow(
    val day: Day,
    val meal: String,
    val secOfDay: Int?,
    val name: String,
    val brand: String?,
    val amountX100: Int,
    val servingLabel: String,
    val gramsX10: Int?,
    val nutrients: Nutrients,
)

/**
 * A logged exercise without a track (calorie-app exercise logs).
 *
 * @property durationSec duration, if known.
 * @property kcal calories burned, if known.
 */
data class ExerciseRow(
    val day: Day,
    val secOfDay: Int?,
    val name: String,
    val durationSec: Int?,
    val kcal: Int?,
)

/** A free-text note for a day. */
data class NoteRow(val day: Day, val text: String)

/** The file type a workout came from. */
enum class TrackFormat(val extension: String) { FIT("fit"), GPX("gpx"), TCX("tcx") }

/**
 * A workout read from a file, with its points and the original bytes (kept as the workout's
 * `fit_path` or `track_path` file when committed).
 */
class ParsedWorkout(
    /** Summary; `id` is 0 and the file paths are null until committed. */
    val workout: Workout,
    /** Points in time order. */
    val points: List<TrackPoint>,
    /** What the original file was. */
    val format: TrackFormat,
    /** File name inside the import, for the preview. */
    val fileName: String,
    /** The original file, byte for byte; empty when there is no file (the workout is saved without one). */
    val original: ByteArray,
)

/**
 * A water log entry.
 *
 * @property secOfDay local time of day in seconds, if the source had one.
 */
data class WaterRow(val day: Day, val secOfDay: Int?, val ml: Int)

/**
 * Watch data (activity minutes, metrics, sleep) from one device of another app. Every `deviceId` in
 * the lists is a placeholder (0); the [ImportSink] stores the series under a device row of its own
 * for the import, so undo can remove it.
 *
 * @property sourceKey stable key of the device in the source, for example its Bluetooth address.
 * @property name display name, for example "Gadgetbridge: Forerunner 255".
 * @property model model name, if known.
 */
data class DeviceSeries(
    val sourceKey: String,
    val name: String,
    val model: String,
    val activity: List<ActivityMinute> = emptyList(),
    val metrics: List<MetricSample> = emptyList(),
    val sleepStages: List<SleepStageSample> = emptyList(),
    val sleepSessions: List<SleepSession> = emptyList(),
) {
    /** True when the series holds nothing. */
    fun isEmpty(): Boolean = activity.isEmpty() && metrics.isEmpty() && sleepStages.isEmpty() && sleepSessions.isEmpty()
}

/**
 * Everything an import produces, fully parsed and ready to write. Ids are 0 and import ids null;
 * the [ImportSink] fills them in.
 */
data class ImportData(
    val diary: List<DiaryRow> = emptyList(),
    val body: List<BodyMeasure> = emptyList(),
    val exercises: List<ExerciseRow> = emptyList(),
    val notes: List<NoteRow> = emptyList(),
    val workouts: List<ParsedWorkout> = emptyList(),
    val recipes: List<Recipe> = emptyList(),
    val foods: List<Food> = emptyList(),
    val water: List<WaterRow> = emptyList(),
    val devices: List<DeviceSeries> = emptyList(),
) {
    /**
     * Non-zero counts by area name ("diary", "body", "exercise", "note", "workout", "recipe", "food",
     * "water", "activity", "metric", "sleep_stage", "sleep_session").
     */
    fun counts(): Map<String, Int> {
        val m = LinkedHashMap<String, Int>()
        fun put(k: String, n: Int) {
            if (n > 0) m[k] = n
        }
        put("diary", diary.size)
        put("body", body.size)
        put("exercise", exercises.size)
        put("note", notes.size)
        put("workout", workouts.size)
        put("recipe", recipes.size)
        put("food", foods.size)
        put("water", water.size)
        put("activity", devices.sumOf { it.activity.size })
        put("metric", devices.sumOf { it.metrics.size })
        put("sleep_stage", devices.sumOf { it.sleepStages.size })
        put("sleep_session", devices.sumOf { it.sleepSessions.size })
        return m
    }

    /** True when nothing was found. */
    fun isEmpty(): Boolean = counts().isEmpty()

    /** This data plus [o], area by area. */
    operator fun plus(o: ImportData): ImportData = ImportData(
        diary + o.diary, body + o.body, exercises + o.exercises, notes + o.notes,
        workouts + o.workouts, recipes + o.recipes, foods + o.foods, water + o.water, devices + o.devices,
    )
}
