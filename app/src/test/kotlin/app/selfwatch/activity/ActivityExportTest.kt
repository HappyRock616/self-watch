/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.activity

import android.app.Application
import app.selfwatch.interop.api.ExportSource
import app.selfwatch.interop.api.MemorySink
import app.selfwatch.me.MeTestSupport
import app.selfwatch.model.health.DataSource
import app.selfwatch.model.health.TrackPoint
import app.selfwatch.model.health.Workout
import app.selfwatch.model.time.Clock
import app.selfwatch.model.time.Day
import app.selfwatch.model.time.DayRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.zip.ZipInputStream

/** The range rules of Export activities (plain JVM). */
class ActivityExportPlanTest {

    private val today = Day(20261123)

    @Test
    fun presetsEndTodayAndCountWholeDays() {
        assertEquals(DayRange(Day(20261117), today), ActivityExportPlan.days(ExportRange.LAST_7, today))
        assertEquals(DayRange(Day(20261025), today), ActivityExportPlan.days(ExportRange.LAST_30, today))
        assertEquals(90, ActivityExportPlan.days(ExportRange.LAST_90, today)!!.days().size)
        assertEquals(DayRange(Day(20260101), today), ActivityExportPlan.days(ExportRange.THIS_YEAR, today))
        assertNull(ActivityExportPlan.days(ExportRange.ALL, today))
    }

    @Test
    fun rangesCrossMonthAndYearEnds() {
        assertEquals(DayRange(Day(20251226), Day(20260101)), ActivityExportPlan.days(ExportRange.LAST_7, Day(20260101)))
        assertEquals(DayRange(Day(20240101), Day(20240101)), ActivityExportPlan.days(ExportRange.THIS_YEAR, Day(20240101)))
        assertEquals(DayRange(Day(20240201), Day(20240301)), ActivityExportPlan.days(ExportRange.LAST_30, Day(20240301)))
    }

    @Test
    fun customUsesItsEndsSwapsReversedOnesAndDefaultsToThirtyDays() {
        val a = Day(20260301)
        val b = Day(20260315)
        assertEquals(DayRange(a, b), ActivityExportPlan.days(ExportRange.CUSTOM, today, a, b))
        assertEquals(DayRange(a, b), ActivityExportPlan.days(ExportRange.CUSTOM, today, b, a))
        assertEquals(DayRange(a, a), ActivityExportPlan.days(ExportRange.CUSTOM, today, a, a))
        assertEquals(ActivityExportPlan.days(ExportRange.LAST_30, today), ActivityExportPlan.days(ExportRange.CUSTOM, today))
    }

    @Test
    fun requestNeverNamesWorkoutsSoTheExportIsAlwaysAZip() {
        val days = DayRange(Day(20260301), Day(20260315))
        val req = ActivityExportPlan.request(days, ZoneOffset.UTC, 42)
        assertEquals(days, req.days)
        assertTrue(req.workoutIds.isEmpty())
        assertEquals(42L, req.createdSec)
        assertEquals("application/zip", ActivityExportPlan.MIME)
    }

    @Test
    fun fileNameNamesFormatAndRange() {
        assertEquals("selfwatch-workouts-gpx-2026-03-01-2026-03-15.zip", ActivityExportPlan.fileName("gpx", DayRange(Day(20260301), Day(20260315))))
        assertEquals("selfwatch-workouts-tcx-all.zip", ActivityExportPlan.fileName("tcx", null))
    }
}

/** The export view model with the real GPX, FIT and TCX exporters over an in-memory source. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ActivityExportViewModelTest {

    private val zone: ZoneId = ZoneOffset.UTC
    private val clock = object : Clock {
        override fun nowSec(): Long = LocalDate.of(2026, 11, 23).atTime(12, 0).toEpochSecond(ZoneOffset.UTC)
        override fun zone(): ZoneId = zone
    }

    private fun startOf(y: Int, m: Int, d: Int): Long = LocalDate.of(y, m, d).atTime(8, 0).toEpochSecond(ZoneOffset.UTC)

    private fun workout(id: Long, start: Long, fit: Boolean) = Workout(
        id = id, deviceId = null, sport = 1, subSport = null, startSec = start, endSec = start + 600, durationSec = 600,
        distanceM = 1500, kcal = 100, avgHr = 140, maxHr = 160, ascentM = null, descentM = null, hasTrack = true,
        fitPath = if (fit) "w$id.fit" else null, trackPath = null, routePreview = null, source = DataSource.WATCH,
    )

    /** Filters by the requested days like the database source does. */
    private class Source(val all: List<Workout>) : ExportSource {
        val asked = ArrayList<DayRange?>()

        override suspend fun workouts(days: DayRange?, ids: List<Long>, zone: ZoneId): List<Workout> {
            asked.add(days)
            if (days == null) return all
            val start = days.first.startSec(zone)
            val end = days.last.plusDays(1).startSec(zone)
            return all.filter { it.startSec in start until end }
        }

        override suspend fun track(workout: Workout): List<TrackPoint> = (0 until 5).map {
            TrackPoint(workout.startSec + it * 10, 476_000_000 + it * 100, -1_223_000_000, null, 120, null, null, null)
        }

        override fun openFit(workout: Workout): InputStream? =
            workout.fitPath?.let { ByteArrayInputStream(byteArrayOf(14, 16, 0, 0, 0, 0, 0, 0, '.'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'T'.code.toByte())) }
    }

    private val source = Source(
        listOf(
            workout(1, startOf(2026, 1, 5), fit = true),
            workout(2, startOf(2026, 11, 1), fit = false),
            workout(3, startOf(2026, 11, 20), fit = true),
            workout(4, startOf(2026, 11, 23), fit = false),
        ),
    )

    private fun entries(bytes: ByteArray): List<String> {
        val out = ArrayList<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) out.add((z.nextEntry ?: break).name)
        }
        return out
    }

    private fun idle(vm: ActivityExportViewModel, what: String, cond: (ActivityExportUi) -> Boolean) =
        MeTestSupport.waitFor(what, { vm.state.value.toString() }) { cond(vm.state.value) }

    @Test
    fun countsTheChosenRange() {
        val vm = ActivityExportViewModel(source, clock)
        idle(vm, "count") { it.total != null }
        assertEquals(3, vm.state.value.total) // last 30 days: Nov 1, 20 and 23
        assertEquals(1, vm.state.value.withFit)
        vm.setRange(ExportRange.LAST_7)
        idle(vm, "count 7") { it.total == 2 }
        vm.setRange(ExportRange.ALL)
        idle(vm, "count all") { it.total == 4 }
        assertNull(source.asked.last())
        vm.setCustom(Day(20260101), Day(20260110))
        idle(vm, "count custom") { it.total == 1 && it.range == ExportRange.CUSTOM }
        assertEquals(DayRange(Day(20260101), Day(20260110)), vm.customDays())
    }

    @Test
    fun gpxExportZipsOneFilePerWorkoutInRangeAndReportsProgress() {
        val vm = ActivityExportViewModel(source, clock)
        idle(vm, "count") { it.total == 3 }
        assertEquals("selfwatch-workouts-gpx-2026-10-25-2026-11-23.zip", vm.target().name)
        val sink = MemorySink()
        vm.export(sink)
        idle(vm, "export") { it.result != null }
        assertEquals(ActivityExportResult.Ok(3, 0), vm.state.value.result)
        assertEquals(3, vm.state.value.done)
        assertFalse(vm.state.value.running)
        val names = entries(sink.bytes())
        assertEquals(3, names.size)
        assertTrue(names.all { it.endsWith(".gpx") })
        assertTrue(names.any { it.startsWith("2026-11-01") })
        assertFalse(names.any { it.startsWith("2026-01-05") })
    }

    @Test
    fun fitSkipsWorkoutsWithoutAFitFileAndSaysSoWhenNoneHasOne() {
        val vm = ActivityExportViewModel(source, clock)
        idle(vm, "count") { it.total == 3 }
        vm.setFormat("fit")
        val sink = MemorySink()
        vm.export(sink)
        idle(vm, "fit export") { it.result != null }
        assertEquals(ActivityExportResult.Ok(1, 2), vm.state.value.result)
        assertEquals(listOf("fit"), entries(sink.bytes()).map { it.substringAfterLast('.') })

        vm.setCustom(Day(20261101), Day(20261101))
        idle(vm, "count one") { it.total == 1 && it.result == null }
        assertFalse("FIT with no FIT files cannot start", vm.state.value.canExport)
        var discarded = false
        vm.export(MemorySink()) { discarded = true }
        idle(vm, "no fit") { it.result != null }
        assertEquals(ActivityExportResult.NoFit, vm.state.value.result)
        assertTrue("the empty file is removed", discarded)
    }

    @Test
    fun emptyRangeCannotExport() {
        val vm = ActivityExportViewModel(source, clock)
        vm.setCustom(Day(20250101), Day(20250131))
        idle(vm, "empty") { it.total == 0 }
        assertFalse(vm.state.value.canExport)
    }
}
