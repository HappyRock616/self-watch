/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.interop.diary

import app.selfwatch.interop.TestSupport
import app.selfwatch.interop.api.ImportChoices
import app.selfwatch.interop.api.ImportData
import app.selfwatch.interop.api.ImportResult
import app.selfwatch.interop.api.ImportSink
import app.selfwatch.interop.api.WeightUnit
import app.selfwatch.model.food.Nutrients
import app.selfwatch.model.health.BodyKind
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.time.Day
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class DiaryImportersTest {
    private val zone = TestSupport.ZONE

    private fun epoch(y: Int, m: Int, d: Int, h: Int, min: Int): Long = LocalDateTime.of(y, m, d, h, min).atZone(zone).toEpochSecond()

    @Test
    fun cronometerZipGoldenCounts() {
        val plan = TestSupport.plan(CronometerImporter(zone = zone), "cronometer.zip", TestSupport.zipOfDir("imports-synthetic/cronometer"))
        assertEquals(mapOf("diary" to 6, "body" to 4, "exercise" to 2, "note" to 2), plan.counts)
        assertEquals("yyyy-MM-dd", plan.dateFormat.best)
        assertTrue(plan.warnings.any { it.contains("Blood Pressure") })
        assertTrue(plan.warnings.any { it.contains("dailySummary") })
        val data = TestSupport.data(plan)
        val oats = data.diary[0]
        assertEquals(Day(20260301), oats.day)
        assertEquals("Breakfast", oats.meal)
        assertEquals(7 * 3600 + 45 * 60, oats.secOfDay)
        assertEquals("Oats, Rolled", oats.name)
        assertEquals(50, oats.amountX100)
        assertEquals("cup", oats.servingLabel)
        assertEquals(Nutrients(1539, 53, 26, 5, 274, 5, 41, 2, 0, transfatX10 = 0, monofatX10 = 9, polyfatX10 = 10), oats.nutrients)
        val apple = data.diary[3]
        assertEquals(null, apple.secOfDay)
        assertEquals(1820, apple.gramsX10)
        val weights = data.body.filter { it.kind == BodyKind.WEIGHT }
        assertEquals(listOf(7840, 7820), weights.map { it.valueX100 })
        assertEquals(epoch(2026, 3, 1, 7, 10), weights[0].tSec)
        assertEquals(8509, data.body.single { it.kind == BodyKind.WAIST }.valueX100)
        assertEquals(listOf(2700, 1800), data.exercises.map { it.durationSec })
        assertEquals(listOf(211, 285), data.exercises.map { it.kcal })
        assertEquals("Rest day,\nmostly", data.notes[1].text)
    }

    @Test
    fun cronometerSingleFile() {
        val plan = TestSupport.plan(CronometerImporter(zone = zone), "servings.csv", TestSupport.bytes("imports-synthetic/cronometer/servings.csv"))
        assertEquals(mapOf("diary" to 6), plan.counts)
        assertTrue(plan.columns.first { it.field == NutrientColumns.SODIUM }.header == "Sodium (mg)")
        assertEquals(listOf("Day", "Time", "Group", "Food Name"), plan.preview[0].take(4))
    }

    @Test
    fun loseItUsesUsDatesSkipsDeletedAndAsksAboutAmbiguousDates() {
        val plan = TestSupport.plan(LoseItImporter(zone = zone), "loseit.zip", TestSupport.zipOfDir("imports-synthetic/loseit"))
        assertEquals(mapOf("diary" to 5, "body" to 3, "exercise" to 2), plan.counts)
        assertTrue(plan.dateFormat.ambiguous)
        assertEquals("MM/dd/yyyy", plan.dateFormat.best)
        assertEquals(WeightUnit.LB, plan.units.unit)
        val data = TestSupport.data(plan)
        assertEquals(Day(20260304), data.diary[0].day)
        assertEquals("Breakfast", data.diary[0].meal)
        assertTrue(data.diary.none { it.name == "Chips" })
        val yogurt = data.diary[1]
        assertEquals(17000, yogurt.amountX100)
        assertEquals(1700, yogurt.gramsX10)
        assertEquals(1000, yogurt.nutrients.kcalX10)
        assertEquals(170, yogurt.nutrients.proteinX10)
        assertEquals(61, yogurt.nutrients.sodiumMg)
        assertEquals(8219, data.body[0].valueX100) // 181.2 lb
        assertEquals(listOf(1800, 3600), data.exercises.map { it.durationSec })

        // The user picks European order instead: 03/04 becomes 3 April.
        val eu = (plan.handle.data(ImportChoices("dd/MM/yyyy", WeightUnit.KG)) as Outcome.Ok).value
        assertEquals(Day(20260403), eu.diary[0].day)
        assertEquals(18120, eu.body[0].valueX100)
    }

    @Test
    fun loseItMealColumnAlias() {
        val csv = "Date,Name,Icon,Meal,Quantity,Units,Calories\n12/31/2026,Toast,Bread,Breakfast,2,Slice,160\n"
        val data = TestSupport.data(TestSupport.plan(LoseItImporter(zone = zone), "food-logs.csv", csv.toByteArray()))
        assertEquals("Breakfast", data.diary.single().meal)
        assertEquals(Day(20261231), data.diary.single().day)
    }

    @Test
    fun myFitnessPalMealRows() {
        val plan = TestSupport.plan(MfpImporter(zone = zone), "mfp-export.zip", TestSupport.zipOfDir("imports-synthetic/mfp", "export/"))
        assertEquals(mapOf("diary" to 5, "body" to 3, "exercise" to 2), plan.counts)
        assertEquals(WeightUnit.LB, plan.units.unit)
        val data = TestSupport.data(plan)
        val lunch = data.diary[1]
        assertEquals("MyFitnessPal Lunch", lunch.name)
        assertEquals("Lunch", lunch.meal)
        assertEquals(12 * 3600 + 45 * 60, lunch.secOfDay)
        // Vitamin A, vitamin C, calcium and iron are % of the daily value in MyFitnessPal files, so they stay unknown.
        assertEquals(Nutrients(6150, 350, 220, 75, 705, 90, 85, 1100, 60, transfatX10 = 2, monofatX10 = 98, polyfatX10 = 31, potassiumMg = 700), lunch.nutrients)
        assertEquals(listOf(BodyKind.WEIGHT, BodyKind.BODY_FAT, BodyKind.WEIGHT), data.body.map { it.kind })
        assertEquals(7824, data.body[0].valueX100) // 172.5 lb
        assertEquals(2400, data.body[1].valueX100)
        assertEquals(listOf(2400, 1800), data.exercises.map { it.durationSec })
    }

    @Test
    fun mfpLongMeasurementLayout() {
        val csv = "Date,Measurement,Value\n2026-02-01,Weight,80.5\n2026-02-01,Waist,90\n2026-02-01,Mood,3\n"
        val data = TestSupport.data(TestSupport.plan(MfpImporter(zone = zone), "Measurement-Summary.csv", csv.toByteArray()))
        assertEquals(listOf(BodyKind.WEIGHT to 8050, BodyKind.WAIST to 9000), data.body.map { it.kind to it.valueX100 })
    }

    @Test
    fun commitGoesThroughTheSink() = runBlocking {
        var written: ImportData? = null
        val sink = object : ImportSink {
            override suspend fun countDuplicates(importerId: String, data: ImportData): Int = data.diary.size
            override suspend fun write(importerId: String, fileName: String, data: ImportData, skipDuplicates: Boolean): Outcome<ImportResult> {
                written = data
                return Outcome.Ok(ImportResult(7, data.counts()))
            }
        }
        val importer = CronometerImporter(sink, zone)
        val plan = (importer.plan(app.selfwatch.interop.api.InputSource.ofBytes("servings.csv", TestSupport.bytes("imports-synthetic/cronometer/servings.csv"))) as Outcome.Ok).value
        assertEquals(6, plan.duplicates)
        val r = importer.commit(plan, plan.defaultChoices())
        assertEquals(7L, (r as Outcome.Ok).value.importId)
        assertEquals(6, written?.diary?.size)
        // Without a database the default sink refuses to write.
        val noDb = CronometerImporter(zone = zone)
        assertTrue(noDb.commit(plan, plan.defaultChoices()) is Outcome.Fail)
    }

    @Test
    fun unrelatedCsvFails() = runBlocking {
        val r = CronometerImporter(zone = zone).plan(app.selfwatch.interop.api.InputSource.ofBytes("x.csv", "a,b\n1,2\n".toByteArray()))
        assertTrue(r is Outcome.Fail)
    }
}
