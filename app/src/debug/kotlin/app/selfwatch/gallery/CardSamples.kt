/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.gallery

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.fragment.app.Fragment
import app.selfwatch.activity.Sports
import app.selfwatch.activity.cards.ActiveTimeCard
import app.selfwatch.activity.cards.ActivityGoalsCard
import app.selfwatch.activity.cards.LastActivityCard
import app.selfwatch.activity.cards.LiveCard
import app.selfwatch.activity.cards.PersonalRecordsCard
import app.selfwatch.activity.cards.RecordMath
import app.selfwatch.activity.cards.SportProgressCard
import app.selfwatch.activity.cards.SportProgressMath
import app.selfwatch.activity.cards.StreaksCard
import app.selfwatch.activity.cards.Vo2MaxCard
import app.selfwatch.activity.cards.WeatherCard
import app.selfwatch.activity.cards.WeeklyReviewCard
import app.selfwatch.activity.cards.WorkoutsCard
import app.selfwatch.body.cards.BloodPressureCard
import app.selfwatch.body.cards.BmiCard
import app.selfwatch.body.cards.BmrCard
import app.selfwatch.body.cards.BodyTodayCard
import app.selfwatch.body.cards.LeanMassCard
import app.selfwatch.body.cards.MorningSummaryCard
import app.selfwatch.body.cards.MuscleMassCard
import app.selfwatch.body.cards.NapsCard
import app.selfwatch.body.cards.ReadinessCard
import app.selfwatch.body.cards.SleepScoreCard
import app.selfwatch.body.cards.TemperatureCard
import app.selfwatch.cards.Card
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardSize
import app.selfwatch.cards.CardView
import app.selfwatch.cards.GoalLine
import app.selfwatch.cards.GoalMetric
import app.selfwatch.cards.SmallCardView
import app.selfwatch.cards.TrendData
import app.selfwatch.cards.WaitingCard
import app.selfwatch.cards.WaitingCardView
import app.selfwatch.db.dao.BatteryReading
import app.selfwatch.devices.cards.BatteryCard
import app.selfwatch.devices.cards.LastSyncCard
import app.selfwatch.devices.cards.RoomClimateCard
import app.selfwatch.devices.cards.SolarCard
import app.selfwatch.devices.cards.SyncedKind
import app.selfwatch.food.cards.BalanceNutrient
import app.selfwatch.food.cards.BurnSource
import app.selfwatch.food.cards.DailyBalanceCard
import app.selfwatch.food.cards.DailyBalanceMath
import app.selfwatch.food.cards.FoodGroup
import app.selfwatch.food.cards.FoodGroupsCard
import app.selfwatch.food.cards.NutrientLine
import app.selfwatch.food.cards.WeightGoalCard
import app.selfwatch.food.cards.WeightGoalMath
import app.selfwatch.model.health.BodyKind
import app.selfwatch.model.health.ActivityMinute
import app.selfwatch.model.health.BodyMeasure
import app.selfwatch.model.health.DataSource
import app.selfwatch.model.health.SleepSession
import app.selfwatch.model.health.Workout
import app.selfwatch.model.time.Day
import app.selfwatch.model.watch.WatchCommand
import app.selfwatch.model.watch.WeatherCondition
import app.selfwatch.model.watch.WeatherDay
import app.selfwatch.model.watch.WeatherNow
import app.selfwatch.shell.Tab
import app.selfwatch.ui.format.Units
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * Sample data for the cards added with the card framework, so the gallery can show each in its small
 * and large form in every theme and font size (no database needed).
 */
object CardSamples {

    /** Each card with sample data, in gallery order. */
    fun all(units: Units = Units.METRIC): List<Pair<Card<*>, Any?>> {
        val zone = ZoneId.systemDefault()
        val today = Day.of(System.currentTimeMillis() / 1000, zone)
        val workouts = workouts(today, zone)
        return listOf(
            BodyTodayCard to BodyTodayCard.Data(
                listOf(
                    GoalLine(GoalMetric.KCAL_EATEN, 1_450, 2_000),
                    GoalLine(GoalMetric.KCAL_BURNED, 1_980, 2_050),
                    GoalLine(GoalMetric.WATER, 1_250, 2_000),
                    GoalLine(GoalMetric.ENERGY, 64, null),
                    GoalLine(GoalMetric.SLEEP, 452, 480),
                    GoalLine(GoalMetric.WEIGHT, 78_40, 75_00),
                ),
                units,
            ),
            ActivityGoalsCard to ActivityGoalsCard.Data(
                listOf(
                    GoalLine(GoalMetric.STEPS, 7_420, 10_000),
                    GoalLine(GoalMetric.ACTIVE_MIN, 185, 300),
                    GoalLine(GoalMetric.ACTIVE_KCAL, 410, 400),
                    GoalLine(GoalMetric.WORKOUTS, 2, 3),
                    GoalLine(GoalMetric.ACTIVE_DAYS, 3, 5),
                    GoalLine(GoalMetric.DISTANCE, 5_600, 8_000),
                ),
                units,
            ),
            WorkoutsCard to WorkoutsCard.Data(workouts.sortedByDescending { it.startSec }.take(5), units),
            DailyBalanceCard to dailyBalance(),
            WeightGoalCard to weightGoal(today, units),
            SportProgressCard to SportProgressCard.Data(SportProgressMath.progress(Sports.RUNNING, workouts, today, zone), units),
            FoodGroupsCard to FoodGroupsCard.Data(mapOf(FoodGroup.FRUITS to 2, FoodGroup.VEGETABLES to 1, FoodGroup.GRAINS to 3, FoodGroup.PROTEIN to 2, FoodGroup.DAIRY to 0), true),
            ReadinessCard to TrendData(74.0, units = units, extras = listOf(0.0)),
            SleepScoreCard to TrendData(82.0, listOf(70.0, 76.0, 81.0, 68.0, 85.0, 82.0), 4.0, units),
            TemperatureCard to TrendData(0.3, listOf(-0.1, 0.0, 0.2, 0.1, 0.3), units = units),
            MuscleMassCard to TrendData(33.4, listOf(33.0, 33.1, 33.2, 33.4), 0.4, units),
            LeanMassCard to TrendData(61.2, listOf(60.8, 61.0, 61.2), 0.4, units),
            BmiCard to TrendData(24.2, listOf(24.6, 24.4, 24.2), -0.4, units),
            BmrCard to TrendData(1_640.0, units = units, extras = listOf(1.0)),
            BloodPressureCard to TrendData(118.0, listOf(124.0, 121.0, 118.0), units = units, extras = listOf(76.0)),
            NapsCard to TrendData(35.0, units = units),
            MorningSummaryCard to TrendData(452.0, units = units, extras = listOf(54.0, 78.0)),
            ActiveTimeCard to TrendData(64.0, listOf(40.0, 55.0, 30.0, 70.0, 64.0), 12.0, units),
            StreaksCard to TrendData(6.0, units = units),
            WeeklyReviewCard to TrendData(8_640.0, change = 640.0, units = units, extras = listOf(8_000.0)),
            LastActivityCard to LastActivityCard.Data(workouts.maxByOrNull { it.startSec }, units),
            PersonalRecordsCard to PersonalRecordsCard.Data(RecordMath.records(workouts), units),
            Vo2MaxCard to TrendData(48.3, listOf(46.9, 47.2, 47.6, 48.0, 48.3), 1.1, units, listOf(51.0, 0.0)),
            LiveCard to LiveCard.Data(64, System.currentTimeMillis() / 1000 - 180, 7_420, mapOf(1L to "vívoactive 5")),
            WeatherCard to WeatherCard.Data(weather(today), true, today, units),
            BatteryCard to battery(),
            LastSyncCard to lastSync(),
            SolarCard to Unit,
            RoomClimateCard to Unit,
        )
    }

    /** A day: asleep until 6:45, a morning walk, a desk day with short walks, a run at 18:00. */
    private fun weather(today: Day): WatchCommand.Weather = WatchCommand.Weather(
        WeatherNow(System.currentTimeMillis() / 1000 - 1_500, 128, 110, WeatherCondition.PARTLY_CLOUDY, 62, 140, 220, "Chicago"),
        listOf(WeatherDay(today, 61, 154, WeatherCondition.PARTLY_CLOUDY, 20), WeatherDay(today.plusDays(1), 40, 120, WeatherCondition.RAIN, 70)),
    )

    private fun battery(): BatteryCard.Data {
        val now = System.currentTimeMillis() / 1000
        val watch = (0 until 14).map { i -> BatteryReading(1, now - (13 - i) * 12 * 3_600L, if (i < 8) 92 - i * 9 else 100 - (i - 8) * 8) }
        val strap = listOf(BatteryReading(2, now - 5 * 86_400L, 40), BatteryReading(2, now - 3_600, 31))
        return BatteryCard.Data(
            listOf(
                BatteryCard.Device(1, "vívoactive 5", watch.last().pct, watch.last().tSec, watch),
                BatteryCard.Device(2, "HRM strap", strap.last().pct, strap.last().tSec, strap),
            ),
            now,
        )
    }

    private fun lastSync(): LastSyncCard.Data {
        val now = System.currentTimeMillis() / 1000
        return LastSyncCard.Data(
            listOf(
                LastSyncCard.Device(1, "vívoactive 5", now - 1_500, linkedMapOf(SyncedKind.WORKOUTS to 1, SyncedKind.ACTIVITY to 3, SyncedKind.SLEEP to 1)),
                LastSyncCard.Device(2, "Scale", now - 3 * 86_400L, linkedMapOf(SyncedKind.MEASUREMENTS to 1)),
                LastSyncCard.Device(3, "HRM strap", null, emptyMap()),
            ),
            now,
        )
    }

    private fun dailyBalance(): DailyBalanceCard.Data {
        val lines = linkedMapOf(
            BalanceNutrient.PROTEIN to NutrientLine(82.0, 100.0, false),
            BalanceNutrient.CARBS to NutrientLine(170.0, 250.0, false),
            BalanceNutrient.FAT to NutrientLine(52.0, 66.7, false),
            BalanceNutrient.SAT_FAT to NutrientLine(18.0, 22.2, true),
            BalanceNutrient.FIBER to NutrientLine(19.0, 28.0, false),
            BalanceNutrient.SODIUM to NutrientLine(1_900.0, 2_300.0, true),
            BalanceNutrient.SUGAR to NutrientLine(48.0, null, false),
        )
        return DailyBalanceCard.Data(
            goalKcal = 2_000, eatenKcal = 1_450, activeKcal = 420, restingKcal = 1_650, leftKcal = 970,
            lines = lines,
            shares = DailyBalanceMath.shares(82.0, 170.0, 52.0),
            top = mapOf(BalanceNutrient.PROTEIN to listOf("Greek yogurt" to 20.0, "Chicken breast" to 38.0)),
        )
    }

    private fun weightGoal(today: Day, units: Units): WeightGoalCard.Data {
        val zone = ZoneId.systemDefault()
        val start = today.startSec(zone)
        val weighIns = (0 until 30).map { i ->
            val kgX100 = (81_00 - i * 9 + (if (i % 3 == 0) 40 else -20)).toDouble().roundToInt()
            BodyMeasure(i.toLong(), start - (60 - i * 2) * DAY_SEC, BodyKind.WEIGHT, null, kgX100, DataSource.MANUAL, null, null)
        }
        val trend = weighIns.map { it.valueX100 + 10 }
        val goalDate = today.plusDays(12 * 7)
        val plan = WeightGoalMath.plan(trend.last(), 75_00, today, goalDate, 2_350, BurnSource.ADAPTIVE, 1_500)
        val history = (5 downTo 0).map { today.plusDays(-7 * it) to plan.targetKcal + it * 20 }
        return WeightGoalCard.Data(plan, trend.last(), 75_00, goalDate, weighIns, trend, history, WeightGoalMath.bmi(trend.last(), 178), WeightGoalMath.bmi(75_00, 178), today, units)
    }

    private fun workouts(today: Day, zone: ZoneId): List<Workout> {
        val base = today.plusDays(-70).startSec(zone) + 7 * 3_600
        val runs = (0 until 10).map { i ->
            val start = base + i * 7 * DAY_SEC
            Workout(i.toLong() + 1, 1, Sports.RUNNING, null, start, start + 1_860 - i * 10, 1_860 - i * 10, 6_000 + i * 50, 420, 152 - i, 171, 40, 38, false, null, null, null, DataSource.WATCH)
        }
        val ride = Workout(100, 1, Sports.CYCLING, null, base + 3 * DAY_SEC, base + 3 * DAY_SEC + 4_200, 4_200, 31_500, 780, 138, 165, 420, 410, false, null, null, null, DataSource.WATCH)
        val walk = Workout(101, 1, Sports.WALKING, null, today.plusDays(-1).startSec(zone) + 18 * 3_600, 0, 2_400, 3_100, 160, 104, 120, 12, 12, false, null, null, null, DataSource.WATCH)
        val strength = Workout(102, 1, Sports.TRAINING, null, today.plusDays(-2).startSec(zone) + 17 * 3_600, 0, 2_700, null, 240, 118, 150, null, null, false, null, null, null, DataSource.WATCH)
        return runs + ride + walk + strength
    }

    /** A host for gallery cards: taps show a note instead of opening screens. */
    class Host(override val context: Context) : CardHost {
        override val fragment: Fragment get() = throw UnsupportedOperationException("gallery cards have no screen")
        override fun open(screen: Fragment) = Toast.makeText(context, screen.javaClass.simpleName, Toast.LENGTH_SHORT).show()
        override fun openTab(tab: Tab) = Toast.makeText(context, tab.name, Toast.LENGTH_SHORT).show()
        override fun launch(block: suspend () -> Unit) = Unit
    }

    /** [card]'s small form bound to [data]. */
    @Suppress("UNCHECKED_CAST")
    fun <T> small(card: Card<T>, data: Any?, host: CardHost, parent: ViewGroup): View =
        if (card is WaitingCard) {
            // As on a tab: a card no device records shows "Your devices don't record this".
            WaitingCardView.create(card, CardSize.SMALL, parent.context)
        } else {
            SmallCardView(card, host, parent).also { it.bind(data as T) }.root
        }

    /** [card]'s large form bound to [data]. */
    @Suppress("UNCHECKED_CAST")
    fun <T> large(card: Card<T>, data: Any?, host: CardHost, parent: ViewGroup): View {
        val v: CardView<T> = card.createView(host, parent)
        v.bind(data as T)
        return v.root
    }

    /** Two small cards side by side, as on a tab. */
    fun pair(ctx: Context, left: View, right: View?, gap: Int): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        isBaselineAligned = false
        addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        addView(right ?: View(ctx), LinearLayout.LayoutParams(0, if (right == null) 0 else ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = gap })
    }

    private const val DAY_SEC = 86_400L
}
