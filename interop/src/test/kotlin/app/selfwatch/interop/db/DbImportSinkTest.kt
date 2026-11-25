/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.interop.db

import app.selfwatch.interop.DbTestSupport
import app.selfwatch.interop.TestSupport
import app.selfwatch.interop.api.DeviceSeries
import app.selfwatch.interop.api.DiaryRow
import app.selfwatch.interop.api.ExerciseRow
import app.selfwatch.interop.api.ImportData
import app.selfwatch.interop.api.NoteRow
import app.selfwatch.interop.api.TrackFormat
import app.selfwatch.interop.api.WaterRow
import app.selfwatch.interop.workout.WorkoutFiles
import app.selfwatch.model.food.Basis
import app.selfwatch.model.food.Food
import app.selfwatch.model.food.FoodOrigin
import app.selfwatch.model.food.FoodRef
import app.selfwatch.model.food.MatchState
import app.selfwatch.model.food.Nutrients
import app.selfwatch.model.food.Recipe
import app.selfwatch.model.food.RecipeItem
import app.selfwatch.model.food.RecipeKind
import app.selfwatch.model.food.Serving
import app.selfwatch.model.health.ActivityMinute
import app.selfwatch.model.health.BodyKind
import app.selfwatch.model.health.BodyMeasure
import app.selfwatch.model.health.DataSource
import app.selfwatch.model.health.MetricKind
import app.selfwatch.model.health.MetricSample
import app.selfwatch.model.health.SleepSession
import app.selfwatch.model.health.SleepStage
import app.selfwatch.model.health.SleepStageSample
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.time.Day
import app.selfwatch.model.time.DayRange
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DbImportSinkTest {
    private lateinit var files: AppFiles
    private lateinit var sink: DbImportSink
    private val day = Day(20260301)

    @Before
    fun setUp() {
        files = DbTestSupport.fresh()
        sink = DbTestSupport.sink(files)
    }

    private fun n(kcal: Int) = Nutrients(kcal * 10, 100, 50, null, 200, null, null, 120, null)

    private fun sample(): ImportData {
        val fit = TestSupport.bytes("fit/synthetic/activity_run_gps.fit")
        val run = (WorkoutFiles.parse(TrackFormat.FIT, "activity_run_gps.fit", fit) as Outcome.Ok).value.single()
        val t0 = 1_776_000_000L
        return ImportData(
            diary = listOf(
                DiaryRow(day, "Breakfast", 8 * 3600, "Oats", null, 100, "cup", 800, n(300)),
                DiaryRow(day, "lunch", null, "Soup", "Acme", 100, "bowl", null, n(250)),
                DiaryRow(day, "Pre-workout", 7 * 3600, "Banana", null, 100, "each", 1200, n(105)),
                DiaryRow(day, "Mystery", null, "Chocolate", null, 50, "bar", null, n(120)),
                DiaryRow(day, "Abendessen", 19 * 3600, "Pasta", null, 200, "g", null, n(600)),
            ),
            body = listOf(BodyMeasure(0, t0, BodyKind.WEIGHT, null, 7550, DataSource.IMPORT, null, null)),
            exercises = listOf(ExerciseRow(day, 18 * 3600, "Yoga", 1800, 120)),
            notes = listOf(NoteRow(day, "felt good")),
            workouts = listOf(run),
            recipes = listOf(
                Recipe(0, RecipeKind.RECIPE, "Lentil soup", 40, null, 10, 30, "Cook.", null, null, null, null, setOf("soup"),
                    listOf(RecipeItem(1, "200 g lentils", null, null, null, null, MatchState.UNMATCHED)), null),
            ),
            foods = listOf(
                Food(FoodRef(FoodOrigin.MY_FOOD, 0), "Granola", "Acme", 0, Basis.PER_100G, n(450), listOf(Serving(1, "cup", 600)), null, "", null),
            ),
            water = listOf(WaterRow(day, 9 * 3600, 500), WaterRow(day, null, 250)),
            devices = listOf(
                DeviceSeries(
                    "AA:BB", "Test watch", "FR255",
                    activity = (0 until 5).map { ActivityMinute(0, t0 + it * 60, 40, 30, 3000, 1, null) },
                    metrics = (0 until 5).map { MetricSample(0, MetricKind.HEART_RATE, t0 + it * 60, 70 + it) },
                    sleepStages = listOf(SleepStageSample(0, t0 + 40_000, SleepStage.LIGHT), SleepStageSample(0, t0 + 41_800, SleepStage.DEEP)),
                    sleepSessions = listOf(SleepSession(0, t0 + 40_000, t0 + 43_600, 77)),
                ),
            ),
        )
    }

    @Test
    fun writesEverythingAsOneTaggedBatch() = runBlocking {
        val before = DbTestSupport.counts()
        val r = (sink.write("test", "sample.zip", sample(), true) as Outcome.Ok).value
        assertEquals(
            mapOf(
                "diary" to 5, "body" to 1, "exercise" to 1, "workout" to 1, "food" to 1, "recipe" to 1, "water" to 2,
                "activity" to 5, "metric" to 5, "sleep_stage" to 2, "sleep_session" to 1,
            ),
            r.counts,
        )
        val batch = DbTestSupport.db.imports.get(r.importId)!!
        assertEquals("test", batch.importer)
        assertEquals(r.counts.mapValues { it.value.toLong() }, batch.counts)
        val after = DbTestSupport.counts()
        assertEquals(5L, after.getValue("diary_entry") - before.getValue("diary_entry"))
        assertEquals(2L, after.getValue("workout") - before.getValue("workout"))
        assertEquals(1L, after.getValue("device") - before.getValue("device"))
        val d = DbTestSupport.db.readableDatabase
        val tagged = listOf(
            "diary_entry", "body_measure", "workout", "custom_food", "water", "recipe",
            "activity_minute", "metric", "sleep_stage", "sleep_session",
        )
        for (t in tagged) {
            d.rawQuery("SELECT count(*) FROM $t WHERE import_id IS NOT ${r.importId}", null).use { c ->
                c.moveToFirst()
                assertEquals("untagged rows in $t", 0, c.getInt(0))
            }
        }
        // The device row holding the watch data is marked removed, so it is never connected to.
        d.rawQuery("SELECT removed, address FROM device", null).use { c ->
            c.moveToFirst()
            assertEquals(1, c.getInt(0))
            assertEquals("import:${r.importId}:AA:BB", c.getString(1))
        }
    }

    @Test
    fun mealNamesMapToSlotsWithoutCreatingAny() = runBlocking {
        val r = (sink.write("test", "x", ImportData(diary = sample().diary), true) as Outcome.Ok).value
        val entries = DbTestSupport.db.diary.day(day).associate { it.name to it.mealSlotId }
        assertEquals(1L, entries["Oats"])
        assertEquals(2L, entries["Soup"])
        assertEquals(1L, entries["Banana"]) // unknown name, 07:00 → breakfast
        assertEquals(4L, entries["Chocolate"]) // unknown name, no time → snacks
        assertEquals(3L, entries["Pasta"]) // German keyword
        assertEquals(4, DbTestSupport.db.diary.mealSlots().size)
        // Lines without a time get the slot's nominal time.
        val soup = DbTestSupport.db.diary.day(day).first { it.name == "Soup" }
        assertEquals(12 * 3600 + 1800, java.time.Instant.ofEpochSecond(soup.tSec).atZone(TestSupport.ZONE).toLocalTime().toSecondOfDay())
        assertTrue(r.importId > 0)
    }

    @Test
    fun workoutOriginalIsSavedWithARelativePath() = runBlocking {
        val r = (sink.write("fit", "run.fit", ImportData(workouts = sample().workouts), true) as Outcome.Ok).value
        val w = DbTestSupport.db.workouts.list(10).single()
        val path = w.fitPath!!
        assertTrue(path, path.startsWith("workouts/import/${r.importId}/"))
        assertTrue(files.resolve(path).readBytes().contentEquals(TestSupport.bytes("fit/synthetic/activity_run_gps.fit")))
        assertEquals(DataSource.IMPORT, w.source)
        assertTrue(w.hasTrack)
    }

    @Test
    fun duplicatesAreCountedAndSkipped() = runBlocking {
        val data = sample()
        assertEquals(0, sink.countDuplicates("test", data))
        sink.write("test", "a", data, true)
        val total = data.counts().filterKeys { it != "note" }.values.sum()
        assertEquals(total, sink.countDuplicates("test", data))
        val again = (sink.write("test", "a", data, true) as Outcome.Ok).value
        assertEquals(emptyMap<String, Int>(), again.counts)
        val forced = (sink.write("test", "a", ImportData(diary = data.diary), false) as Outcome.Ok).value
        assertEquals(mapOf("diary" to 5), forced.counts)
    }

    @Test
    fun undoRemovesExactlyWhatWasAdded() = runBlocking {
        sink.write("first", "keep", ImportData(diary = sample().diary.take(1)), true)
        val before = DbTestSupport.checksums()
        val r = (sink.write("test", "sample.zip", sample(), true) as Outcome.Ok).value
        assertTrue(files.importDir(r.importId).isDirectory)
        assertTrue(sink.undo(r.importId) > 0)
        val after = DbTestSupport.checksums()
        for (t in before.keys - "import_batch") assertEquals("table $t", before[t], after[t])
        assertTrue(DbTestSupport.db.imports.get(r.importId)!!.undone)
        assertFalse(files.importDir(r.importId).exists())
    }

    @Test
    fun importDaoUndoAloneRemovesImportedWatchData() = runBlocking {
        val r = (sink.write("test", "sample.zip", sample(), true) as Outcome.Ok).value
        DbTestSupport.db.imports.undo(r.importId)
        val c = DbTestSupport.counts()
        for (t in listOf("activity_minute", "metric", "sleep_stage", "sleep_session")) assertEquals(t, 0L, c.getValue(t))
        // Only the (removed) device row is left for DbImportSink.undo to clean up.
        assertEquals(1L, c.getValue("device"))
        sink.undo(r.importId)
        assertEquals(0L, DbTestSupport.counts().getValue("device"))
    }

    @Test
    fun aFailingWriteLeavesNothingBehind() = runBlocking {
        val before = DbTestSupport.checksums()
        // A custom measurement pointing at a kind that doesn't exist breaks the foreign key mid-import.
        val bad = sample().copy(body = listOf(BodyMeasure(0, 1_776_000_000, BodyKind.CUSTOM, 999_999, 100, DataSource.IMPORT, null, null)))
        val r = sink.write("test", "bad", bad, true)
        assertTrue(r is Outcome.Fail)
        assertEquals(before, DbTestSupport.checksums())
        assertEquals(0, files.filesDir.walkTopDown().count { it.isFile })
    }

    @Test
    fun exportSourceReadsBackWhatWasImported() = runBlocking {
        sink.write("test", "sample.zip", sample(), true)
        val src = DbExportSource(DbTestSupport.db, files, TestSupport.ZONE)
        val range = DayRange(day, day)
        val diary = src.diary(range)
        assertEquals(5, diary.size)
        assertEquals(setOf("Breakfast", "Lunch", "Dinner", "Snacks"), diary.map { it.meal }.toSet())
        assertEquals(8 * 3600, diary.first { it.name == "Oats" }.secOfDay)
        assertEquals(diary, src.diary(null))
        assertEquals(1, src.body(null, TestSupport.ZONE).size)
        val workouts = src.workouts(null, emptyList(), TestSupport.ZONE)
        assertEquals(2, workouts.size)
        val run = workouts.first { it.fitPath != null }
        assertTrue(src.track(run).size > 100)
        assertNotNull(src.openFit(run)?.use { it.readBytes() })
        assertEquals(listOf("Granola"), src.customFoods().map { it.name })
        assertEquals(listOf("Lentil soup"), src.recipes().map { it.name })
        assertEquals(2, src.water(range).size)
        assertEquals(1, src.workouts(null, listOf(run.id), TestSupport.ZONE).size)
    }

    @Test
    fun trimmedWorkoutExportsOnlyItsWindowAndStaysADuplicate() = runBlocking {
        val data = sample()
        sink.write("test", "sample.zip", data, true)
        val src = DbExportSource(DbTestSupport.db, files, TestSupport.ZONE)
        val run = src.workouts(null, emptyList(), TestSupport.ZONE).first { it.fitPath != null }
        val all = src.track(run)
        val start = all[all.size / 4].tSec
        val end = all[all.size / 2].tSec
        val cut = run.copy(startSec = start, endSec = end, durationSec = (end - start).toInt(), distanceM = 1_000)
        assertTrue(DbTestSupport.db.workouts.saveTrim(cut, start, end))
        val trimmed = DbTestSupport.db.workouts.get(run.id)!!
        // Track (GPX, TCX) inside the window only; the whole recording is still there.
        val kept = src.track(trimmed)
        assertTrue(kept.isNotEmpty() && kept.all { it.tSec in start..end })
        assertEquals(all.size, src.fullTrack(trimmed).size)
        // FIT: written from the trimmed track and summary; the original file is unchanged.
        val fit = requireNotNull(src.fitFile(trimmed)).use { it.readBytes() }
        val back = (app.selfwatch.fit.decode.WorkoutDecoder.summary(fit.inputStream(), null) as Outcome.Ok).value
        assertEquals(start to end, back.startSec to back.endSec)
        assertEquals(1_000, back.distanceM)
        assertTrue(requireNotNull(src.openFit(trimmed)).use { it.readBytes() }.size > fit.size)
        // Importing the same file again: still a duplicate (its original start), so nothing is added.
        assertEquals(1, sink.countDuplicates("test", data.copy(diary = emptyList(), body = emptyList(), exercises = emptyList(), recipes = emptyList(), foods = emptyList(), water = emptyList(), devices = emptyList(), notes = emptyList())))
        // Undo: everything comes back.
        assertTrue(DbTestSupport.db.workouts.undoTrim(run.id))
        assertEquals(all.size, src.track(DbTestSupport.db.workouts.get(run.id)!!).size)
    }
}
