/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.interop.db

import android.content.Context
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.fit.decode.WorkoutDecoder
import app.selfwatch.interop.api.DiaryRow
import app.selfwatch.interop.api.ExportSource
import app.selfwatch.interop.api.TrackFormat
import app.selfwatch.interop.io.Texts
import app.selfwatch.interop.workout.WorkoutFiles
import app.selfwatch.model.food.Food
import app.selfwatch.model.food.Recipe
import app.selfwatch.model.health.BodyMeasure
import app.selfwatch.model.health.TrackPoint
import app.selfwatch.model.health.Workout
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.time.Day
import app.selfwatch.model.time.DayRange
import app.selfwatch.model.time.TimeRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.time.Instant
import java.time.ZoneId

/**
 * The [ExportSource] backed by `selfwatch.db` and the workout files in [files].
 *
 * Day ranges are inclusive local days: diary and water use their stored `day`; workouts and body
 * measurements are selected by time within the range's days in the request's zone. Diary times of
 * day are read in [zone].
 *
 * Exercises logged in calorie apps are stored as workouts (see [DbImportSink]), so [exercises] is
 * empty and they come out with [workouts]. Notes have no table, so [notes] is empty too.
 */
class DbExportSource(
    private val db: SelfWatchDb,
    private val files: AppFiles,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ExportSource {

    /** The source for this process's database and files. */
    constructor(ctx: Context, zone: ZoneId = ZoneId.systemDefault()) : this(SelfWatchDb.get(ctx), AppFiles(ctx.filesDir), zone)

    override suspend fun workouts(days: DayRange?, ids: List<Long>, zone: ZoneId): List<Workout> = when {
        ids.isNotEmpty() -> ids.mapNotNull { db.workouts.get(it) }.sortedBy { it.startSec }
        days != null -> db.workouts.forExport(days, zone)
        else -> db.workouts.all()
    }

    override suspend fun track(workout: Workout): List<TrackPoint> = withContext(Dispatchers.IO) {
        try {
            val fit = workout.fitPath?.let { files.resolve(it) }?.takeIf { it.isFile }
            if (fit != null) {
                val points = ArrayList<TrackPoint>()
                val r = FileInputStream(fit).use { WorkoutDecoder.track(it) { p -> points.add(p) } }
                return@withContext if (r is Outcome.Ok) points else emptyList()
            }
            val track = workout.trackPath?.let { files.resolve(it) }?.takeIf { it.isFile } ?: return@withContext emptyList()
            val format = TrackFormat.entries.firstOrNull { it.extension == Texts.extension(track.name) } ?: return@withContext emptyList()
            when (val r = WorkoutFiles.parse(format, track.name, track.readBytes())) {
                is Outcome.Ok -> (r.value.minByOrNull { kotlin.math.abs(it.workout.startSec - workout.startSec) } ?: return@withContext emptyList()).points
                is Outcome.Fail -> emptyList()
            }
        } catch (e: IOException) {
            SwLog.w(TAG, "track of workout ${workout.id} unreadable", e)
            emptyList()
        }
    }

    override fun openFit(workout: Workout): InputStream? {
        val f = workout.fitPath?.let { files.resolve(it) }?.takeIf { it.isFile } ?: return null
        return try {
            FileInputStream(f)
        } catch (e: IOException) {
            SwLog.w(TAG, "FIT of workout ${workout.id} unreadable", e)
            null
        }
    }

    override suspend fun diary(days: DayRange?): List<DiaryRow> {
        val slots = db.diary.mealSlots(includeHidden = true).associate { it.id to it.name }
        return db.diary.range(days).map { e ->
            DiaryRow(
                day = e.day, meal = slots[e.mealSlotId] ?: "",
                secOfDay = Instant.ofEpochSecond(e.tSec).atZone(zone).toLocalTime().toSecondOfDay(),
                name = e.name, brand = e.brand, amountX100 = e.amountX100, servingLabel = e.servingLabel,
                gramsX10 = e.gramsX10, nutrients = e.nutrients,
            )
        }
    }

    override suspend fun body(days: DayRange?, zone: ZoneId): List<BodyMeasure> =
        db.body.all(days?.let { TimeRange(it.first.startSec(zone), it.last.plusDays(1).startSec(zone)) })

    override suspend fun recipes(): List<Recipe> = db.recipes.list().mapNotNull { db.recipes.get(it.id) }

    override suspend fun customFoods(): List<Food> = db.foods.list(includeHidden = false).map { it.food }

    /** Water entries in [days] as (day, epoch second, ml), oldest first. */
    suspend fun water(days: DayRange?): List<Triple<Day, Long, Int>> = db.water.range(days).map { Triple(it.day, it.tSec, it.ml) }

    private companion object {
        const val TAG = "DbExportSource"
    }
}
