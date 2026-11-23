/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.interop.diary

import app.selfwatch.fit.decode.WeightDecoder
import app.selfwatch.interop.TestSupport
import app.selfwatch.interop.api.DiaryRow
import app.selfwatch.interop.api.ExerciseRow
import app.selfwatch.interop.api.ExportRequest
import app.selfwatch.interop.api.MemorySink
import app.selfwatch.interop.api.NoteRow
import app.selfwatch.interop.api.WeightUnit
import app.selfwatch.interop.food.FoodListCsv
import app.selfwatch.interop.food.FoodListExporter
import app.selfwatch.interop.food.FoodListImporter
import app.selfwatch.interop.io.Zips
import app.selfwatch.model.food.Basis
import app.selfwatch.model.food.Nutrients
import app.selfwatch.model.health.BodyKind
import app.selfwatch.model.json.Json
import app.selfwatch.model.json.JsonValue
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.time.Day
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.time.LocalDateTime

class ExportersTest {
    private val zone = TestSupport.ZONE
    private val req = ExportRequest(zone = zone, createdSec = 1_776_300_000)

    private fun t(d: Int, h: Int, m: Int): Long = LocalDateTime.of(2026, 3, d, h, m).atZone(zone).toEpochSecond()

    private val diary = listOf(
        DiaryRow(Day(20260301), "Breakfast", 7 * 3600 + 30 * 60, "Porridge, with \"honey\"", null, 150, "cup", null, Nutrients(2405, 81, 52, 10, 410, 120, 50, 95, 5)),
        DiaryRow(Day(20260301), "Lunch", null, "Tomato soup", null, 100, "bowl", null, Nutrients(1800, 40, 60, null, 250, 150, 30, 700, null)),
        DiaryRow(Day(20260302), "Dinner", 19 * 3600, "Rice", null, 20000, "g", 2000, Nutrients(2600, 54, 6, 1, 564, 1, 8, 2, 0)),
    )
    private val body = listOf(
        importedMeasure(t(1, 7, 10), BodyKind.WEIGHT, 7840),
        importedMeasure(t(1, 7, 10), BodyKind.BODY_FAT, 2150),
        importedMeasure(t(1, 7, 10), BodyKind.BMI, 2410),
        importedMeasure(t(2, 7, 5), BodyKind.WEIGHT, 7820),
        importedMeasure(t(2, 7, 5), BodyKind.WAIST, 8500),
    )
    private val exercises = listOf(ExerciseRow(Day(20260301), 17 * 3600, "Walk", 2700, 210))
    private val notes = listOf(NoteRow(Day(20260301), "Felt good,\nslept well"))
    private val source = TestSupport.FakeSource(diaryRows = diary, bodyRows = body, exerciseRows = exercises, noteRows = notes)

    @Test
    fun cronometerExportThenImportGivesTheSameDiary() = runBlocking {
        val sink = MemorySink()
        val res = (CronometerExporter(source).export(req, sink) as Outcome.Ok).value
        assertEquals(3, res.counts["diary"])
        val files = Zips.readAll(sink.bytes()).associate { it.name to String(it.bytes, Charsets.UTF_8) }
        assertEquals(setOf("servings.csv", "dailySummary.csv", "exercises.csv", "biometrics.csv", "notes.csv"), files.keys)
        assertTrue(files.getValue("servings.csv").startsWith(0xFEFF.toChar() + "Day,Time,Group,Food Name,Amount,Energy (kcal),Carbs (g)"))
        assertTrue(files.getValue("servings.csv").contains("2026-03-01,07:30,Breakfast,\"Porridge, with \"\"honey\"\"\",1.50 cup,240.5,41,5,12,5.2,5,1,95,8.1"))
        assertTrue(files.getValue("dailySummary.csv").contains("2026-03-01,420.5,66,8,27,11.2,5,1,795,12.1"))

        val plan = TestSupport.plan(CronometerImporter(zone = zone), "cronometer.zip", sink.bytes())
        val back = TestSupport.data(plan)
        assertEquals(diary, back.diary)
        assertEquals(exercises, back.exercises)
        assertEquals(notes, back.notes)
        assertEquals(body.map { it.kind to it.valueX100 }, back.body.map { it.kind to it.valueX100 })
        assertEquals(body.map { it.tSec }, back.body.map { it.tSec })
    }

    @Test
    fun garminBodyCsvGolden() = runBlocking {
        val sink = MemorySink()
        val res = (GarminBodyCsvExporter(source).export(req, sink) as Outcome.Ok).value
        assertEquals(2, res.counts["body"])
        assertEquals(
            "Body\r\nDate,Time,Weight,BMI,Fat\r\n2026-03-01,07:10,78.4,24.1,21.5\r\n2026-03-02,07:05,78.2,,\r\n",
            String(sink.bytes(), Charsets.UTF_8),
        )
        val lb = MemorySink()
        GarminBodyCsvExporter(source).export(req.copy(weightUnit = WeightUnit.LB), lb)
        assertTrue(String(lb.bytes(), Charsets.UTF_8).contains("2026-03-01,07:10,172.8,24.1,21.5"))
    }

    @Test
    fun fitWeightFileDecodesBack() = runBlocking {
        val sink = MemorySink()
        assertTrue(FitWeightExporter(source).export(req, sink) is Outcome.Ok)
        val back = (WeightDecoder.decode(ByteArrayInputStream(sink.bytes())) as Outcome.Ok).value
        val weights = back.filter { it.kind == BodyKind.WEIGHT }
        assertEquals(listOf(7840, 7820), weights.map { it.valueX100 })
        assertEquals(listOf(t(1, 7, 10), t(2, 7, 5)), weights.map { it.tSec })
        assertTrue(FitWeightExporter(TestSupport.FakeSource()).export(req, MemorySink()) is Outcome.Fail)
    }

    @Test
    fun jsonExportHasOneArrayPerArea() = runBlocking {
        val sink = MemorySink()
        val withWorkout = TestSupport.FakeSource(diaryRows = diary, bodyRows = body, workouts = listOf(TestSupport.workout()))
        assertTrue(JsonExporter(withWorkout).export(req, sink) is Outcome.Ok)
        val files = Zips.readAll(sink.bytes()).associate { it.name to (Json.parse(String(it.bytes, Charsets.UTF_8)) as Outcome.Ok).value }
        assertEquals(setOf("diary.json", "exercises.json", "notes.json", "body.json", "workouts.json", "recipes.json", "foods.json"), files.keys)
        val first = files.getValue("diary.json")[0]!!
        assertEquals(20260301L, first["day"]?.asLong)
        assertEquals(2405L, first["nutrients"]?.get("kcalX10")?.asLong)
        assertEquals(JsonValue.Null, files.getValue("diary.json")[1]!!["nutrients"]!!["satfatX10"])
        assertEquals("WEIGHT", files.getValue("body.json")[0]!!["kind"]?.asString)
        assertEquals(600L, files.getValue("workouts.json")[0]!!["durationSec"]?.asLong)
    }

    @Test
    fun foodListSampleReadsWithDecimalCommaAndPerServingConversion() {
        val plan = TestSupport.plan(FoodListImporter(), "foodlist.csv", TestSupport.bytes("imports-synthetic/foods/foodlist.csv"))
        val foods = TestSupport.data(plan).foods
        assertEquals(3, foods.size)
        val granola = foods[0]
        assertEquals(Basis.PER_100G, granola.basis)
        assertEquals(4500, granola.per.kcalX10) // 495 kcal per 110 g
        assertEquals(114, granola.per.proteinX10) // 12,5 g per 110 g
        assertEquals(1100, granola.servings.single().gramsX10)
        assertNull(granola.brand)
        assertEquals("Acme Snacks", foods[1].brand)
        assertEquals(Basis.PER_ITEM, foods[2].basis)
        assertEquals(800, foods[2].per.kcalX10)
    }

    @Test
    fun foodListRoundTrip() = runBlocking {
        val foods = TestSupport.data(TestSupport.plan(FoodListImporter(), "foodlist.csv", TestSupport.bytes("imports-synthetic/foods/foodlist.csv"))).foods
        val sink = MemorySink()
        FoodListExporter(TestSupport.FakeSource(foods = foods)).export(req, sink)
        val text = String(sink.bytes(), Charsets.UTF_8)
        assertTrue(text.contains(FoodListCsv.HEADER.joinToString(",")))
        val importer = FoodListImporter()
        assertEquals(95, importer.sniff("foods.csv", sink.bytes()))
        val back = TestSupport.data(TestSupport.plan(importer, "foods.csv", sink.bytes())).foods
        assertEquals(foods, back)
    }
}
