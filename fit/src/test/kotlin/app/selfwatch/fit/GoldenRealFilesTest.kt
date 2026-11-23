/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.fit

import app.selfwatch.fit.decode.MonitoringCarry
import app.selfwatch.fit.decode.MonitoringDecoder
import app.selfwatch.fit.decode.SleepDecoder
import app.selfwatch.fit.decode.WorkoutDecoder
import app.selfwatch.fit.io.FitReader
import app.selfwatch.model.health.SleepStage
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.valueOrNull
import app.selfwatch.model.time.Day
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import java.io.File
import java.time.ZoneId
import java.util.Properties

/**
 * Golden tests against HappyRock's real vívoactive 5 files (task H02). Waiting for the files: remove
 * `@Ignore` once `testdata/fit/real/` holds them and `expected.properties` holds the watch's totals
 * (see `testdata/fit/README.md`).
 */
@Ignore("waiting for real watch files from task H02 in testdata/fit/real/")
class GoldenRealFilesTest {
    private val dir = File(testdataFit(), "real")
    private val expected = Properties().apply {
        val f = File(dir, "expected.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    private val zone: ZoneId = ZoneId.of(expected.getProperty("zone", "America/Chicago"))

    private fun files(sub: String): List<File> =
        File(dir, sub).listFiles { f -> f.name.endsWith(".fit", ignoreCase = true) }?.sortedBy { it.name }.orEmpty()

    @Test
    fun everyRealFileReadsWithGoodCrc() {
        val all = dir.walkTopDown().filter { it.isFile && it.name.endsWith(".fit", ignoreCase = true) }.toList()
        assertTrue("no real files yet", all.isNotEmpty())
        for (f in all) {
            val r = f.inputStream().use { FitReader(it).read { } }
            assertTrue("${f.name}: $r", r is Outcome.Ok && r.value.crcOk)
        }
    }

    /** `steps.YYYYMMDD=<steps the watch showed>` for each day in Monitor/. */
    @Test
    fun monitoringDayStepsMatchTheWatch() {
        val carry = MonitoringCarry()
        val steps = HashMap<Day, Int>()
        for (f in files("Monitor")) {
            val batch = f.inputStream().use { MonitoringDecoder.decode(it, 1, zone, carry) }.valueOrNull()!!
            for (m in batch.activity) steps.merge(Day.of(m.tSec, zone), m.steps, Int::plus)
        }
        val keys = expected.stringPropertyNames().filter { it.startsWith("steps.") }
        assertTrue("no steps.* in expected.properties", keys.isNotEmpty())
        for (k in keys) assertEquals(k, expected.getProperty(k).toInt(), steps[Day(k.removePrefix("steps.").toInt())])
    }

    /** `sleep.<file>.deep|light|rem|awake=<minutes>` and `sleep.<file>.score`. */
    @Test
    fun sleepStageMinutesMatchTheWatch() {
        for (f in files("Sleep")) {
            val batch = f.inputStream().use { SleepDecoder.decode(it, 1) }.valueOrNull()!!
            val st = batch.sleepStages
            val minutes = HashMap<SleepStage, Long>()
            for (i in st.indices) {
                val end = if (i + 1 < st.size) st[i + 1].tSec else batch.sleepSessions.firstOrNull()?.endSec ?: st[i].tSec
                minutes.merge(st[i].stage, end - st[i].tSec, Long::plus)
            }
            val p = "sleep.${f.nameWithoutExtension}."
            expected.getProperty(p + "deep")?.let { assertEquals(it.toLong(), (minutes[SleepStage.DEEP] ?: 0) / 60) }
            expected.getProperty(p + "light")?.let { assertEquals(it.toLong(), (minutes[SleepStage.LIGHT] ?: 0) / 60) }
            expected.getProperty(p + "rem")?.let { assertEquals(it.toLong(), (minutes[SleepStage.REM] ?: 0) / 60) }
            expected.getProperty(p + "awake")?.let { assertEquals(it.toLong(), (minutes[SleepStage.AWAKE] ?: 0) / 60) }
            expected.getProperty(p + "score")?.let { assertEquals(it.toInt(), batch.sleepSessions.single().score) }
        }
    }

    /** `workout.<file>.distanceM` and `workout.<file>.durationSec`. */
    @Test
    fun workoutDistanceAndTimeMatchTheWatch() {
        for (f in files("Activity")) {
            val w = f.inputStream().use { WorkoutDecoder.summary(it, 1) }.valueOrNull()!!
            val p = "workout.${f.nameWithoutExtension}."
            expected.getProperty(p + "distanceM")?.let { assertEquals(it.toInt().toDouble(), (w.distanceM ?: 0).toDouble(), 10.0) }
            expected.getProperty(p + "durationSec")?.let { assertEquals(it.toInt(), w.durationSec) }
        }
    }
}
