/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.interop.hc

import android.content.Context
import android.health.connect.HealthPermissions
import android.health.connect.datatypes.ExerciseSessionRecord
import android.health.connect.datatypes.ExerciseSessionType
import android.health.connect.datatypes.MealType
import android.health.connect.datatypes.NutritionRecord
import android.health.connect.datatypes.SleepSessionRecord
import android.health.connect.datatypes.StepsRecord
import android.health.connect.datatypes.WeightRecord
import android.health.connect.datatypes.units.Energy
import android.health.connect.datatypes.units.Mass
import app.selfwatch.interop.db.MealSlots
import app.selfwatch.model.food.DiaryEntry
import app.selfwatch.model.food.MealSlot
import app.selfwatch.model.food.Nutrients
import app.selfwatch.model.health.BodyKind
import app.selfwatch.model.health.BodyMeasure
import app.selfwatch.model.health.DataSource
import app.selfwatch.model.health.MetricKind
import app.selfwatch.model.health.MetricSample
import app.selfwatch.model.health.SleepSession
import app.selfwatch.model.health.SleepStage
import app.selfwatch.model.health.SleepStageSample
import app.selfwatch.model.health.Workout
import app.selfwatch.model.time.Day
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HcMappingTest {
    private val zone = ZoneId.of("Europe/London")
    private val t = 1_780_000_000L
    private val v = 42L

    private fun body(id: Long, kind: BodyKind, x100: Int) = BodyMeasure(id, t, kind, null, x100, DataSource.MANUAL, null, null)

    @Test
    fun bodyUnitsAndIds() {
        val w = HcMapping.weight(body(7, BodyKind.WEIGHT, 7235), v, zone)!!
        assertEquals(72.35, w.weight.inGrams / 1000, 1e-9)
        assertEquals("sw:body_measure:7", w.metadata.clientRecordId)
        assertEquals(v, w.metadata.clientRecordVersion)
        assertEquals(zone.rules.getOffset(Instant.ofEpochSecond(t)), w.zoneOffset)
        val f = HcMapping.bodyFat(body(8, BodyKind.BODY_FAT, 1850), v, zone)!!
        assertEquals(18.5, f.percentage.value, 1e-9)
        // Out of Health Connect's range: no record rather than a crash.
        assertNull(HcMapping.weight(body(9, BodyKind.WEIGHT, 200_000_00), v, zone))
    }

    @Test
    fun idsAreStable() {
        val a = HcMapping.weight(body(7, BodyKind.WEIGHT, 7000), 1, zone)!!
        val b = HcMapping.weight(body(7, BodyKind.WEIGHT, 7100), 2, zone)!!
        assertEquals(a.metadata.clientRecordId, b.metadata.clientRecordId)
        assertEquals("sw:activity_minute:3-7200-steps", HcIds.activityHour(3, 7200, "steps"))
        assertEquals("sw:metric:2-3-99", HcIds.metric(MetricSample(3, MetricKind.HRV_RMSSD, 99, 40)))
        assertTrue(HcIds.isOurs("sw:diary_entry:1"))
        assertTrue(!HcIds.isOurs("other") && !HcIds.isOurs(null))
    }

    @Test
    fun nutritionUnits() {
        val e = DiaryEntry(
            5, Day(20260601), 1, t, null, "Porridge", null, 100, "bowl", null,
            Nutrients(5255, 123, 87, 15, 650, 120, 80, 450, 12), null,
        )
        val r = HcMapping.nutrition(DiaryOut(e, "Breakfast"), v, zone)!!
        assertEquals(525.5, r.energy!!.inCalories / 1000, 1e-9)
        assertEquals(12.3, r.protein!!.inGrams, 1e-9)
        assertEquals(8.7, r.totalFat!!.inGrams, 1e-9)
        assertEquals(1.5, r.saturatedFat!!.inGrams, 1e-9)
        assertEquals(65.0, r.totalCarbohydrate!!.inGrams, 1e-9)
        assertEquals(12.0, r.sugar!!.inGrams, 1e-9)
        assertEquals(8.0, r.dietaryFiber!!.inGrams, 1e-9)
        assertEquals(450.0, r.sodium!!.inGrams * 1000, 1e-9)
        assertEquals(12.0, r.cholesterol!!.inGrams * 1000, 1e-9)
        assertEquals("Porridge", r.mealName)
        assertEquals(MealType.MEAL_TYPE_BREAKFAST, r.mealType)
        assertEquals("sw:diary_entry:5", r.metadata.clientRecordId)
        assertEquals(MealType.MEAL_TYPE_UNKNOWN, HcMapping.mealType("Elevenses", 9))
        assertEquals(MealType.MEAL_TYPE_DINNER, HcMapping.mealType("Supper", 9))
        assertEquals(MealType.MEAL_TYPE_SNACK, HcMapping.mealType("Fuel", 4))
    }

    @Test
    fun waterActivityAndMetricUnits() {
        assertEquals(250.0, HcMapping.hydration(WaterOut(1, t, 250), v, zone)!!.volume.inLiters * 1000, 1e-9)
        val h = ActivityHour(2, 3600, 3600, 3600 + 600, 1234, 98_765, 456)
        assertEquals(1234L, HcMapping.steps(h, v, zone)!!.count)
        assertEquals(987.65, HcMapping.distance(h, v, zone)!!.distance.inMeters, 1e-9)
        assertEquals(45.6, HcMapping.activeKcal(h, v, zone)!!.energy.inCalories / 1000, 1e-9)
        assertNull(HcMapping.steps(h.copy(steps = 0), v, zone))
        val hr = HcMapping.heartRate(2, 3600, listOf(MetricSample(2, MetricKind.HEART_RATE, 3700, 61), MetricSample(2, MetricKind.HEART_RATE, 3660, 0)), v, zone)!!
        assertEquals(listOf(61L), hr.samples.map { it.beatsPerMinute })
        assertEquals(48.0, HcMapping.hrv(MetricSample(2, MetricKind.HRV_RMSSD, t, 48), v, zone)!!.heartRateVariabilityMillis, 1e-9)
        assertEquals(97.0, HcMapping.spo2(MetricSample(2, MetricKind.SPO2, t, 97), v, zone)!!.percentage.value, 1e-9)
        assertEquals(15.5, HcMapping.respiration(MetricSample(2, MetricKind.RESPIRATION, t, 155), v, zone)!!.rate, 1e-9)
    }

    @Test
    fun workoutAndSleepOut() {
        val w = Workout(3, 1, 1, 1, t, t + 1800, 1800, 5000, 300, 150, 170, null, null, false, null, null, null, DataSource.WATCH)
        val ex = HcMapping.exercise(w, v, zone)!!
        assertEquals(ExerciseSessionType.EXERCISE_SESSION_TYPE_RUNNING_TREADMILL, ex.exerciseType)
        assertEquals("sw:workout:3", ex.metadata.clientRecordId)
        val s = SleepOut(4, SleepSession(1, t, t + 3600, 80), listOf(
            SleepStageSample(1, t, SleepStage.LIGHT), SleepStageSample(1, t + 1200, SleepStage.DEEP), SleepStageSample(1, t + 2400, SleepStage.REM),
        ))
        val sl = HcMapping.sleep(s, v, zone)!!
        assertEquals(3, sl.stages.size)
        assertEquals(SleepSessionRecord.StageType.STAGE_TYPE_SLEEPING_REM, sl.stages[2].type)
        assertEquals(Instant.ofEpochSecond(t + 3600), sl.stages[2].endTime)
    }

    @Test
    fun readMappings() {
        val m = HcMapping.bodyFrom(WeightRecord.Builder(origin("x"), Instant.ofEpochSecond(t), Mass.fromGrams(80_456.0)).build(), 3)
        assertEquals(8046, m.valueX100)
        assertEquals(DataSource.HEALTH_CONNECT, m.source)
        val slots = MealSlots(listOf(MealSlot(1, "Breakfast", 1, false), MealSlot(2, "Lunch", 2, false), MealSlot(3, "Dinner", 3, false), MealSlot(4, "Snacks", 4, false)))
        val start = Instant.parse("2026-06-01T12:30:00Z")
        val n = nutritionRecord(start, MealType.MEAL_TYPE_DINNER)
        val d = HcMapping.diaryFrom(n, slots, ZoneId.of("UTC"), 3)
        assertEquals(6123, d.nutrients.kcalX10)
        assertEquals(301, d.nutrients.proteinX10)
        assertEquals(800, d.nutrients.sodiumMg)
        assertEquals("Soup", d.name)
        assertEquals(3L, d.mealSlotId)
        assertEquals(Day(20260601), d.day)
        assertEquals(2L, HcMapping.diaryFrom(nutritionRecord(start, MealType.MEAL_TYPE_UNKNOWN), slots, ZoneId.of("UTC"), 3).mealSlotId)

        val steps = StepsRecord.Builder(origin("x"), Instant.ofEpochSecond(600), Instant.ofEpochSecond(780), 100).build()
        assertEquals(listOf(34, 33, 33), HcMapping.stepsToMinutes(steps, 9).map { it.steps })

        val sl = SleepSessionRecord.Builder(origin("x"), Instant.ofEpochSecond(0), Instant.ofEpochSecond(3600))
            .setStages(
                listOf(
                    SleepSessionRecord.Stage(Instant.ofEpochSecond(0), Instant.ofEpochSecond(1000), SleepSessionRecord.StageType.STAGE_TYPE_SLEEPING_LIGHT),
                    SleepSessionRecord.Stage(Instant.ofEpochSecond(1200), Instant.ofEpochSecond(3600), SleepSessionRecord.StageType.STAGE_TYPE_SLEEPING_DEEP),
                ),
            ).build()
        val (session, stages) = HcMapping.sleepFrom(sl, 9)
        assertEquals(3600L, session.endSec)
        assertEquals(listOf(SleepStage.LIGHT, SleepStage.UNMEASURABLE, SleepStage.DEEP), stages.map { it.stage })

        val ex = ExerciseSessionRecord.Builder(origin("x"), Instant.ofEpochSecond(0), Instant.ofEpochSecond(900),
            ExerciseSessionType.EXERCISE_SESSION_TYPE_BIKING).build()
        val w = HcMapping.workoutFrom(ex)
        assertEquals(2, w.sport)
        assertEquals(900, w.durationSec)
        assertEquals(DataSource.HEALTH_CONNECT, w.source)
    }

    private fun nutritionRecord(start: Instant, mealType: Int): NutritionRecord =
        NutritionRecord.Builder(origin("x"), start, start.plusSeconds(60))
            .setStartZoneOffset(ZoneOffset.UTC).setEndZoneOffset(ZoneOffset.UTC)
            .setEnergy(Energy.fromCalories(612_340.0)).setProtein(Mass.fromGrams(30.06)).setSodium(Mass.fromGrams(0.8004))
            .setMealName("Soup").setMealType(mealType)
            .build()

    @Test
    fun sportsRoundTrip() {
        for (sport in listOf(1, 2, 5, 11, 17, 15)) {
            val type = HcSports.toExerciseType(sport, null)
            assertEquals(sport, HcSports.fromExerciseType(type).first)
        }
        assertEquals(0, HcSports.fromExerciseType(9999).first)
    }

    @Test
    fun availabilityNeedsAndroid14AndTheService() {
        assertEquals(HcStatus.UNSUPPORTED, HcAvailability.classify(26, hasService = false))
        assertEquals(HcStatus.UNSUPPORTED, HcAvailability.classify(33, hasService = true))
        assertEquals(HcStatus.UNSUPPORTED, HcAvailability.classify(34, hasService = false))
        assertEquals(HcStatus.AVAILABLE, HcAvailability.classify(34, hasService = true))
        assertEquals(HcStatus.AVAILABLE, HcAvailability.classify(37, hasService = true))
    }

    @Test
    @Config(sdk = [26])
    fun unsupportedBelowAndroid14() {
        val ctx: Context = RuntimeEnvironment.getApplication()
        assertEquals(HcStatus.UNSUPPORTED, HcAvailability.status(ctx))
        assertNull(HcAvailability.api(ctx))
        assertNull(HcAvailability.settingsIntent(ctx))
        assertTrue(HcPermissions.granted(ctx).isEmpty())
    }

    @Test
    fun permissions() {
        val base = HcPermissions.build()
        assertEquals(13 + 6, base.size)
        assertTrue(HcPermissions.HISTORY !in base && HcPermissions.BACKGROUND !in base)
        assertTrue(HcPermissions.HISTORY in HcPermissions.build(history = true))
        val api = FakeHcApi()
        assertTrue(HcPermissions.HISTORY !in HcPermissions.build(api, history = true, background = true))
        api.supported = setOf(HcPermissions.HISTORY)
        assertTrue(HcPermissions.HISTORY in HcPermissions.build(api, history = true, background = true))
        // The permission strings are the platform's.
        assertEquals(HealthPermissions.WRITE_WEIGHT, HcType.WEIGHT.writePermission)
        assertEquals(HealthPermissions.READ_EXERCISE, HcType.EXERCISE.readPermission)
        assertEquals(HealthPermissions.WRITE_ACTIVE_CALORIES_BURNED, HcType.ACTIVE_KCAL.writePermission)
        assertEquals(HealthPermissions.WRITE_HEART_RATE_VARIABILITY, HcType.HRV.writePermission)
        assertEquals(HealthPermissions.WRITE_OXYGEN_SATURATION, HcType.SPO2.writePermission)
        assertEquals(HealthPermissions.WRITE_RESPIRATORY_RATE, HcType.RESPIRATORY_RATE.writePermission)
        assertEquals(HealthPermissions.WRITE_HYDRATION, HcType.HYDRATION.writePermission)
        assertEquals(HealthPermissions.READ_SLEEP, HcType.SLEEP.readPermission)
        assertEquals(HealthPermissions.READ_NUTRITION, HcType.NUTRITION.readPermission)
        assertEquals("android.permission.health.READ_HEALTH_DATA_HISTORY", HcPermissions.HISTORY)
        assertEquals("android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND", HcPermissions.BACKGROUND)
        for (type in HcType.entries) assertEquals(type, HcType.of(recordOf(type)))
        // Every permission we may request is declared in the manifest.
        val manifest = listOf(File("src/main/AndroidManifest.xml"), File("interop/src/main/AndroidManifest.xml")).first { it.isFile }.readText()
        for (p in HcPermissions.build(history = true, background = true)) assertTrue(p, "\"$p\"" in manifest)
    }

    /** One record of [type], built through the mapping. */
    private fun recordOf(type: HcType): android.health.connect.datatypes.Record {
        val h = ActivityHour(2, 3600, 3600, 4200, 10, 1000, 50)
        val sample = MetricSample(2, MetricKind.HRV_RMSSD, t, 40)
        val r = when (type) {
            HcType.WEIGHT -> HcMapping.weight(body(1, BodyKind.WEIGHT, 7000), v, zone)
            HcType.BODY_FAT -> HcMapping.bodyFat(body(1, BodyKind.BODY_FAT, 2000), v, zone)
            HcType.NUTRITION -> nutritionRecord(Instant.ofEpochSecond(t), MealType.MEAL_TYPE_LUNCH)
            HcType.HYDRATION -> HcMapping.hydration(WaterOut(1, t, 200), v, zone)
            HcType.STEPS -> HcMapping.steps(h, v, zone)
            HcType.DISTANCE -> HcMapping.distance(h, v, zone)
            HcType.ACTIVE_KCAL -> HcMapping.activeKcal(h, v, zone)
            HcType.EXERCISE -> HcMapping.exercise(Workout(3, 1, 1, null, t, t + 60, 60, null, null, null, null, null, null, false, null, null, null,
                DataSource.WATCH), v, zone)
            HcType.SLEEP -> HcMapping.sleep(SleepOut(1, SleepSession(1, t, t + 60, null), emptyList()), v, zone)
            HcType.HEART_RATE -> HcMapping.heartRate(2, 0, listOf(MetricSample(2, MetricKind.HEART_RATE, t, 60)), v, zone)
            HcType.HRV -> HcMapping.hrv(sample, v, zone)
            HcType.SPO2 -> HcMapping.spo2(sample, v, zone)
            HcType.RESPIRATORY_RATE -> HcMapping.respiration(sample, v, zone)
        }
        return r!!
    }


    @Test
    fun rateLimitRecognised() {
        assertTrue(HcErrors.isRateLimit(IllegalStateException(RuntimeException("Rate limited request quota has been exceeded"))))
        assertTrue(!HcErrors.isRateLimit(java.io.IOException("disk")))
    }
}
