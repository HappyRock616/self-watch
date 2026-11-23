/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food.cards

import android.app.Application
import app.selfwatch.body.TestDb
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.MainGoal
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.SettingKeys
import app.selfwatch.model.fake.FakeClock
import app.selfwatch.model.food.DiaryEntry
import app.selfwatch.model.food.Goal
import app.selfwatch.model.food.GoalKind
import app.selfwatch.model.food.Nutrients
import app.selfwatch.model.health.BodyKind
import app.selfwatch.model.health.BodyMeasure
import app.selfwatch.model.health.DataSource
import app.selfwatch.model.health.DaySummary
import app.selfwatch.model.health.Workout
import app.selfwatch.model.time.Day
import app.selfwatch.ui.format.Units
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.DayOfWeek

private const val HOUR = 3_600L

private fun nutrients(kcal: Int? = 500, protein: Int? = null, carb: Int? = null, sugar: Int? = null, fibre: Int? = null, sodium: Int? = null) =
    Nutrients(kcal?.let { it * 10 }, protein, null, null, carb, sugar, fibre, sodium, null)

private fun workout(start: Long, minutes: Int, sport: Int = 1, subSport: Int? = null) = Workout(
    0, null, sport, subSport, start, start + minutes * 60L, minutes * 60, null, null, null, null, null, null, false, null, null, null, DataSource.MANUAL,
)

/** The pure arithmetic of the Food cards. */
class FoodCardMathTest {
    @Test
    fun proteinTargetsFollowTheMainGoal() {
        assertEquals(ProteinTarget(PerKgTarget(1.2, null), ProteinReason.LOSS), FoodCardMath.proteinTarget(MainGoal.WEIGHT_LOSS, false))
        assertEquals(ProteinTarget(PerKgTarget(1.6, null), ProteinReason.LOSS_STRENGTH), FoodCardMath.proteinTarget(MainGoal.WEIGHT_LOSS, true))
        assertEquals(PerKgTarget(1.6, null), FoodCardMath.proteinTarget(MainGoal.BUILD_MUSCLE, false).target)
        assertEquals(PerKgTarget(1.2, 2.0), FoodCardMath.proteinTarget(MainGoal.ATHLETIC, false).target)
        assertEquals(PerKgTarget(1.2, 2.0), FoodCardMath.proteinTarget(MainGoal.ENDURANCE, true).target)
        assertEquals(PerKgTarget(1.0, 1.2), FoodCardMath.proteinTarget(MainGoal.ACTIVE_AGING, false).target)
        for (g in listOf(MainGoal.GENERAL_HEALTH, MainGoal.HEART_HEALTH, MainGoal.STRESS_WELLBEING, MainGoal.WEIGHT_MANAGEMENT)) {
            assertEquals(ProteinTarget(PerKgTarget(1.2, null), ProteinReason.GENERAL), FoodCardMath.proteinTarget(g, true))
        }
        // A goal of 120 g at 80 kg is 1.5 g/kg.
        assertEquals(1.5, FoodCardMath.ownProteinTarget(120, 80_00).target.low, 1e-9)
    }

    @Test
    fun strengthAndMealThreshold() {
        assertTrue(FoodCardMath.isStrength(workout(0, 45, sport = 10)))
        assertTrue(FoodCardMath.isStrength(workout(0, 45, sport = 4, subSport = 20)))
        assertFalse(FoodCardMath.isStrength(workout(0, 45, sport = 10, subSport = 26)))
        assertFalse(FoodCardMath.isStrength(workout(0, 45, sport = 1)))
        assertEquals(20, FoodCardMath.mealProteinThresholdG(null))
        assertEquals(20, FoodCardMath.mealProteinThresholdG(60_00)) // 15 g, clamped up
        assertEquals(25, FoodCardMath.mealProteinThresholdG(100_00))
        assertEquals(40, FoodCardMath.mealProteinThresholdG(180_00)) // 45 g, clamped down
    }

    @Test
    fun carbBandFromAverageTrainingTime() {
        assertEquals(CarbBand(PerKgTarget(3.0, 5.0), CarbLevel.LIGHT), FoodCardMath.carbBand(0))
        assertEquals(CarbLevel.LIGHT, FoodCardMath.carbBand(44).level)
        assertEquals(CarbBand(PerKgTarget(5.0, 7.0), CarbLevel.MODERATE), FoodCardMath.carbBand(60))
        assertEquals(CarbBand(PerKgTarget(6.0, 10.0), CarbLevel.HIGH), FoodCardMath.carbBand(120))
        assertEquals(CarbBand(PerKgTarget(8.0, 12.0), CarbLevel.VERY_HIGH), FoodCardMath.carbBand(250))
        // 7 × 60 min over 7 days is an hour a day.
        assertEquals(60, FoodCardMath.avgTrainingMin(List(7) { workout(0, 60) }, 7))
        assertEquals(0, FoodCardMath.avgTrainingMin(emptyList(), 7))
    }

    @Test
    fun fuelWindowBandAndRate() {
        val start = 100_000L
        val w = workout(start, 150)
        val day = Day(20261122)
        fun at(t: Long, carb: Int?) = DiaryEntry(0, day, 4, t, null, "x", null, 100, "1", null, nutrients(carb = carb), null)
        val entries = listOf(at(start - 31 * 60, 500), at(start - 30 * 60, 200), at(start + 60 * 60, 300), at(start + 180 * 60, 100), at(start + 181 * 60, 900))
        // 30 minutes either side, inclusive.
        assertEquals(listOf(200, 300, 100), FoodCardMath.fuelEntries(w, entries).map { it.nutrients.carbX10 })
        assertEquals(30..60, FoodCardMath.fuelBandGPerH(150 * 60))
        assertEquals(30..90, FoodCardMath.fuelBandGPerH(151 * 60))
        assertEquals(40, FoodCardMath.gramsPerHour(1_000, 150 * 60)) // 100 g over 2.5 h
        // The latest workout of an hour or more.
        val short = workout(start + 99_000, 59)
        val long2 = workout(start + 50_000, 70)
        assertEquals(long2, FoodCardMath.longWorkout(listOf(w, short, long2)))
        assertNull(FoodCardMath.longWorkout(listOf(short)))
    }

    @Test
    fun coverageCountsUnknownValues() {
        val day = Day(20261122)
        fun e(sodium: Int?) = DiaryEntry(0, day, 1, 0, null, "x", null, 100, "1", null, nutrients(sodium = sodium), null)
        val c = FoodCardMath.coverage(listOf(e(400), e(null), e(250))) { it.nutrients.sodiumMg }
        assertEquals(Coverage(650, 2, 3), c)
        assertTrue(c.partial)
        assertEquals(Coverage(null, 0, 2), FoodCardMath.coverage(listOf(e(null), e(null))) { it.nutrients.sodiumMg })
        assertFalse(Coverage(10, 1, 1).partial)
        assertEquals(28, FoodCardMath.fibreTargetG(2_000))
        assertNull(FoodCardMath.fibreTargetG(0))
        assertEquals(61.5, FoodCardMath.fatFreeKg(82_00, 25_00), 1e-9)
        val week = FoodCardMath.weekOf(Day(20261126), DayOfWeek.MONDAY)
        assertEquals(Day(20261123), week.first())
        assertEquals(Day(20261129), week.last())
        assertEquals(Day(20261122), FoodCardMath.weekOf(Day(20261126), DayOfWeek.SUNDAY).first())
    }
}

/** Each Food card's loader against a seeded database. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FoodCardsTest {
    private val ctx: Application = RuntimeEnvironment.getApplication()
    private val clock = FakeClock()
    private val today: Day get() = clock.today()
    private val zone get() = clock.zone()

    private fun env(db: SelfWatchDb, day: Day = today) = CardEnv(db, clock, day, Units.METRIC)

    private fun entry(day: Day, slot: Long, n: Nutrients, hour: Int = 12) =
        DiaryEntry(0, day, slot, day.startSec(zone) + hour * HOUR, null, "Food", null, 100, "1 serving", null, n, null)

    private suspend fun weigh(db: SelfWatchDb, kgX100: Int, day: Day = today.plusDays(-1)) {
        db.body.insert(BodyMeasure(0, day.startSec(zone) + 7 * HOUR, BodyKind.WEIGHT, null, kgX100, DataSource.MANUAL, null, null))
    }

    @Test
    fun emptyDatabase() = runBlocking {
        val db = TestDb.fresh(ctx)
        val week = LoggingDaysCard.load(env(db))
        assertEquals(0, week.count)
        assertEquals(7, week.days.size)
        val p = ProteinPerKgCard.load(env(db)).value
        assertNull(p.perKg)
        assertNull(p.kgX100)
        assertEquals(ProteinReason.GENERAL, p.target.reason)
        val meals = ProteinPerMealCard.load(env(db))
        assertEquals(0, meals.eaten)
        assertEquals(listOf("Breakfast", "Lunch", "Dinner", "Snacks"), meals.meals.map { it.name })
        assertNull(CarbsPerKgCard.load(env(db)).value.perKg)
        assertNull(WorkoutFuelCard.load(env(db)).workout)
        assertEquals(FuelMissing.WEIGHT, FuelBalanceCard.load(env(db)).missing)
        assertEquals(Coverage(null, 0, 0), SodiumCard.load(env(db)).coverage)
        // With nothing logged the fibre target still comes from the calorie goal (the default goal here).
        assertEquals(true, (FibreCard.load(env(db)).targetG ?: 0) > 0)
        assertEquals(Coverage(null, 0, 0), TotalSugarCard.load(env(db)).coverage)
    }

    @Test
    fun loggingDaysCountsDiaryDaysOfTheWeek() = runBlocking {
        val db = TestDb.fresh(ctx)
        // Today is Monday 23 Nov 2026; with Sunday-first weeks the week began yesterday.
        db.diary.addAll(listOf(entry(today, 1, nutrients()), entry(today.plusDays(-1), 2, nutrients(kcal = null)), entry(today.plusDays(-3), 1, nutrients())))
        val w = FoodCardLoader(db, clock).loggingWeek(today, DayOfWeek.SUNDAY)
        assertEquals(Day(20261122), w.days.first())
        assertEquals(listOf(true, true, false, false, false, false, false), w.logged)
        assertEquals(listOf(false, false, true, true, true, true, true), w.ahead)
        assertEquals(2, w.count)
    }

    @Test
    fun proteinPerKgTargetsPerGoalAndOwnGoal() = runBlocking {
        val db = TestDb.fresh(ctx)
        weigh(db, 80_00)
        db.diary.addAll(listOf(entry(today, 1, nutrients(protein = 600)), entry(today, 2, nutrients(protein = 520))))
        var p = ProteinPerKgCard.load(env(db)).value
        assertEquals(112, p.proteinG)
        assertEquals(1.4, p.perKg ?: 0.0, 1e-9)
        assertEquals(ProteinReason.GENERAL, p.target.reason)
        db.settings.set(SettingKeys.MAIN_GOAL, MainGoal.WEIGHT_LOSS.id)
        assertEquals(ProteinReason.LOSS, ProteinPerKgCard.load(env(db)).value.target.reason)
        // A strength session this week raises the loss target to 1.6.
        db.workouts.insert(workout(today.plusDays(-2).startSec(zone) + 18 * HOUR, 45, sport = 10))
        p = ProteinPerKgCard.load(env(db)).value
        assertEquals(ProteinTarget(PerKgTarget(1.6, null), ProteinReason.LOSS_STRENGTH), p.target)
        db.settings.set(SettingKeys.MAIN_GOAL, MainGoal.ATHLETIC.id)
        assertEquals(PerKgTarget(1.2, 2.0), ProteinPerKgCard.load(env(db)).value.target.target)
        // The user's own protein goal wins: 140 g at 80 kg is 1.75 g/kg.
        db.goals.set(Goal(GoalKind.PROTEIN_G, 140, today.plusDays(-10)))
        p = ProteinPerKgCard.load(env(db)).value
        assertEquals(ProteinReason.OWN_GOAL, p.target.reason)
        assertEquals(1.75, p.target.target.low, 1e-9)
        assertEquals(140, p.goalG)
    }

    @Test
    fun proteinPerMealDotsAndThreshold() = runBlocking {
        val db = TestDb.fresh(ctx)
        weigh(db, 100_00) // 25 g a meal
        db.diary.addAll(
            listOf(
                entry(today, 1, nutrients(protein = 300)),
                entry(today, 2, nutrients(protein = 120)),
                entry(today, 2, nutrients(protein = 100)),
                entry(today, 3, nutrients(protein = null)),
            ),
        )
        val m = ProteinPerMealCard.load(env(db))
        assertEquals(25, m.thresholdG)
        assertTrue(m.fromWeight)
        assertEquals(listOf(30, 22, null, null), m.meals.map { it.proteinG })
        assertEquals(listOf(true, true, true, false), m.meals.map { it.eaten })
        assertEquals(1, m.reached)
        assertEquals(3, m.eaten)
    }

    @Test
    fun carbsPerKgAutoBand() = runBlocking {
        val db = TestDb.fresh(ctx)
        weigh(db, 70_00)
        db.diary.addAll(listOf(entry(today, 1, nutrients(carb = 2_800)), entry(today, 3, nutrients(carb = 1_400))))
        var c = CarbsPerKgCard.load(env(db)).value
        assertEquals(420, c.carbsG)
        assertEquals(6.0, c.perKg ?: 0.0, 1e-9)
        assertEquals(CarbLevel.LIGHT, c.band.level)
        // 7 h of training over the last 7 days: an hour a day.
        for (d in 0 until 7) db.workouts.insert(workout(today.plusDays(-d).startSec(zone) + 6 * HOUR, 60))
        // A workout 8 days ago is outside the window.
        db.workouts.insert(workout(today.plusDays(-8).startSec(zone) + 6 * HOUR, 600))
        c = CarbsPerKgCard.load(env(db)).value
        assertEquals(60, c.avgTrainingMin)
        assertEquals(CarbBand(PerKgTarget(5.0, 7.0), CarbLevel.MODERATE), c.band)
    }

    @Test
    fun workoutFuelWindowAroundYesterdaysLongRide() = runBlocking {
        val db = TestDb.fresh(ctx)
        val y = today.plusDays(-1)
        val start = y.startSec(zone) + 9 * HOUR
        db.workouts.insert(workout(start, 180, sport = 2))
        db.workouts.insert(workout(today.startSec(zone) + 6 * HOUR, 30))
        db.diary.addAll(
            listOf(
                entry(y, 1, nutrients(carb = 600), hour = 7), // two hours before: not fuel
                DiaryEntry(0, y, 4, start - 20 * 60, null, "Banana", null, 100, "1", null, nutrients(carb = 270), null),
                DiaryEntry(0, y, 4, start + 90 * 60, null, "Gel", null, 100, "1", null, nutrients(carb = 250), null),
                DiaryEntry(0, y, 4, start + 120 * 60, null, "Drink", null, 100, "1", null, nutrients(carb = null), null),
                DiaryEntry(0, y, 4, start + 200 * 60, null, "Recovery", null, 100, "1", null, nutrients(carb = 400), null),
            ),
        )
        val f = WorkoutFuelCard.load(env(db))
        assertEquals(180 * 60, f.workout?.durationSec)
        assertEquals(4, f.entries)
        assertEquals(1, f.unknown)
        assertEquals(92, f.carbsG)
        assertEquals(31, f.gPerHour)
        assertEquals(30..90, f.band)
        // Two days later the ride is out of reach and the card has nothing to check.
        assertNull(WorkoutFuelCard.load(env(db, today.plusDays(1))).workout)
    }

    @Test
    fun fuelBalanceNeedsBodyFatAndDaysThenFlagsLowFuel() = runBlocking {
        val db = TestDb.fresh(ctx)
        weigh(db, 80_00)
        assertEquals(FuelMissing.BODY_FAT, FuelBalanceCard.load(env(db)).missing)
        db.body.insert(BodyMeasure(0, today.plusDays(-1).startSec(zone) + 7 * HOUR + 60, BodyKind.BODY_FAT, null, 20_00, DataSource.SCALE, null, null))
        // Three days: not enough yet.
        for (d in 1..3) db.diary.add(entry(today.plusDays(-d), 2, nutrients(kcal = 2_400)))
        val few = FuelBalanceCard.load(env(db))
        assertEquals(FuelMissing.DAYS, few.missing)
        assertEquals(3, few.loggedDays)
        // Today's partial log does not count; a fourth earlier day does.
        db.diary.add(entry(today, 1, nutrients(kcal = 300)))
        db.diary.add(entry(today.plusDays(-4), 2, nutrients(kcal = 2_400)))
        for (d in 1..4) db.summaries.upsertDay(summary(today.plusDays(-d), active = 480), 0)
        val ok = FuelBalanceCard.load(env(db))
        assertEquals(FuelMissing.NONE, ok.missing)
        assertEquals(4, ok.loggedDays)
        // (2,400 − 480) ÷ 64 kg fat-free mass = 30.
        assertEquals(64.0, ok.ffmKg ?: 0.0, 1e-9)
        assertEquals(30, ok.kcalPerKgFfm)
        assertFalse(ok.low)
        for (d in 1..4) db.summaries.upsertDay(summary(today.plusDays(-d), active = 900), 0)
        val low = FuelBalanceCard.load(env(db))
        assertEquals(23, low.kcalPerKgFfm) // 1,500 ÷ 64
        assertTrue(low.low)
    }

    @Test
    fun nutrientCardsCountUnknownValues() = runBlocking {
        val db = TestDb.fresh(ctx)
        db.diary.addAll(
            listOf(
                entry(today, 1, nutrients(kcal = 1_000, sodium = 800, fibre = 120, sugar = 300)),
                entry(today, 2, nutrients(kcal = 1_000, sodium = null, fibre = 80, sugar = null)),
                entry(today, 3, nutrients(kcal = null, sodium = 1_700, fibre = null, sugar = 200)),
            ),
        )
        val s = SodiumCard.load(env(db))
        assertEquals(Coverage(2_500, 2, 3), s.coverage)
        assertEquals(2_300, s.limitMg)
        val f = FibreCard.load(env(db))
        assertEquals(Coverage(200, 2, 3), f.coverage)
        assertEquals(2_000, f.kcal)
        assertEquals(28, f.targetG)
        val sugar = TotalSugarCard.load(env(db))
        assertEquals(Coverage(500, 2, 3), sugar.coverage)
        // 50 g of sugar is 200 kcal: a tenth of 2,000 kcal.
        assertEquals(0.1, TotalSugarCard.kcalShare(500, sugar.kcal) ?: 0.0, 1e-9)
        // Another day: nothing logged.
        assertEquals(0, SodiumCard.load(env(db, today.plusDays(-1))).coverage.entries)
    }

    private fun summary(day: Day, active: Int) =
        DaySummary(day, 0, null, active, null, null, null, null, null, null, null, null, null, null, null, null)
}
