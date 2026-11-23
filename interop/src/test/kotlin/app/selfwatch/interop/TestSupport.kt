/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.interop

import app.selfwatch.interop.api.ExportSource
import app.selfwatch.interop.api.ImportPlan
import app.selfwatch.interop.api.Importer
import app.selfwatch.interop.api.InputSource
import app.selfwatch.interop.api.DiaryRow
import app.selfwatch.interop.api.ExerciseRow
import app.selfwatch.interop.api.NoteRow
import app.selfwatch.model.food.Food
import app.selfwatch.model.food.Recipe
import app.selfwatch.model.health.BodyMeasure
import app.selfwatch.model.health.DataSource
import app.selfwatch.model.health.TrackPoint
import app.selfwatch.model.health.Workout
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.time.DayRange
import kotlinx.coroutines.runBlocking
import org.junit.Assert.fail
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Test files and fakes. */
object TestSupport {
    /** The repository's `testdata` folder (unit tests run in the module folder). */
    val testdata: File = listOf(File("../testdata"), File("testdata")).first { it.isDirectory }

    /** A file under `testdata`. */
    fun file(path: String): File = File(testdata, path)

    /** Bytes of a file under `testdata`. */
    fun bytes(path: String): ByteArray = file(path).readBytes()

    /** A zip of every file in the folder [path] under `testdata` (entries named by file name). */
    fun zipOfDir(path: String, prefix: String = ""): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            file(path).listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }.forEach { f ->
                z.putNextEntry(ZipEntry(prefix + f.name))
                z.write(f.readBytes())
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** A zip of named byte arrays. */
    fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((n, b) in entries) {
                z.putNextEntry(ZipEntry(n))
                z.write(b)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** Runs [importer]'s plan on [bytes] named [name] and returns it, failing the test on [Outcome.Fail]. */
    fun plan(importer: Importer, name: String, bytes: ByteArray): ImportPlan = runBlocking {
        when (val r = importer.plan(InputSource.ofBytes(name, bytes))) {
            is Outcome.Ok -> r.value
            is Outcome.Fail -> {
                fail("plan failed: ${r.error}")
                throw AssertionError()
            }
        }
    }

    /** The plan's data with its default choices. */
    fun data(plan: ImportPlan): app.selfwatch.interop.api.ImportData =
        (plan.handle.data(plan.defaultChoices()) as Outcome.Ok).value

    /**
     * Every sample as (name, bytes): the calorie-app folders as zips, every file under the sample
     * folders, and the made-up Gadgetbridge backup and Garmin export (needs Robolectric for SQLite).
     */
    fun samples(): List<Pair<String, ByteArray>> {
        val out = ArrayList<Pair<String, ByteArray>>()
        out.add("cronometer-export.zip" to zipOfDir("imports-synthetic/cronometer"))
        out.add("loseit-export.zip" to zipOfDir("imports-synthetic/loseit"))
        out.add("mfp-export.zip" to zipOfDir("imports-synthetic/mfp"))
        for (dir in listOf("imports-synthetic/cronometer", "imports-synthetic/loseit", "imports-synthetic/mfp", "imports-synthetic/workouts", "imports-synthetic/foods", "recipes", "fit/synthetic")) {
            file(dir).listFiles().orEmpty().filter { it.isFile && !it.name.endsWith(".md") }.sortedBy { it.name }.forEach { out.add(it.name to it.readBytes()) }
        }
        out.add("Gadgetbridge_backup.zip" to app.selfwatch.interop.device.DeviceFixtures.gadgetbridgeZip(org.robolectric.RuntimeEnvironment.getApplication().cacheDir))
        out.add("garmin-export.zip" to app.selfwatch.interop.device.DeviceFixtures.garminExportZip())
        return out
    }

    /** A fixed zone for date tests. */
    val ZONE: ZoneId = ZoneId.of("Europe/Berlin")

    /** A workout with sensible values. */
    fun workout(id: Long = 1, start: Long = 1_776_000_000, durationSec: Int = 600, sport: Int = 1): Workout = Workout(
        id = id, deviceId = null, sport = sport, subSport = null, startSec = start, endSec = start + durationSec,
        durationSec = durationSec, distanceM = 1500, kcal = 120, avgHr = 140, maxHr = 160, ascentM = 10, descentM = 9,
        hasTrack = true, fitPath = null, trackPath = null, routePreview = null, source = DataSource.WATCH,
    )

    /** A short track with positions, heart rate, cadence and temperature, one point every 10 s. */
    fun track(start: Long = 1_776_000_000, n: Int = 20): List<TrackPoint> = (0 until n).map { i ->
        TrackPoint(
            tSec = start + i * 10L, latE7 = 476_000_000 + i * 900, lonE7 = -1_223_300_000 + i * 450,
            altCm = 1200 + i * 35, hrBpm = 120 + i, cadence = 80 + i % 5, tempC10 = 95 - i, speedMms = 3000,
        )
    }

    /** An in-memory export source. */
    class FakeSource(
        val workouts: List<Workout> = emptyList(),
        val tracks: Map<Long, List<TrackPoint>> = emptyMap(),
        val fits: Map<Long, ByteArray> = emptyMap(),
        val diaryRows: List<DiaryRow> = emptyList(),
        val exerciseRows: List<ExerciseRow> = emptyList(),
        val bodyRows: List<BodyMeasure> = emptyList(),
        val noteRows: List<NoteRow> = emptyList(),
        val recipeList: List<Recipe> = emptyList(),
        val foods: List<Food> = emptyList(),
    ) : ExportSource {
        override suspend fun workouts(days: DayRange?, ids: List<Long>, zone: ZoneId): List<Workout> =
            if (ids.isNotEmpty()) workouts.filter { it.id in ids } else workouts

        override suspend fun track(workout: Workout): List<TrackPoint> = tracks[workout.id].orEmpty()
        override fun openFit(workout: Workout): InputStream? = fits[workout.id]?.let { ByteArrayInputStream(it) }
        override suspend fun diary(days: DayRange?): List<DiaryRow> = diaryRows
        override suspend fun exercises(days: DayRange?): List<ExerciseRow> = exerciseRows
        override suspend fun body(days: DayRange?, zone: ZoneId): List<BodyMeasure> = bodyRows
        override suspend fun notes(days: DayRange?): List<NoteRow> = noteRows
        override suspend fun recipes(): List<Recipe> = recipeList
        override suspend fun customFoods(): List<Food> = foods
    }
}
