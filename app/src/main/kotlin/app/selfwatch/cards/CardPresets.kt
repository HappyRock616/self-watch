/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.cards

import app.selfwatch.activity.cards.ActiveDaysCard
import app.selfwatch.activity.cards.ActiveKcalCard
import app.selfwatch.activity.cards.EasyHardCard
import app.selfwatch.activity.cards.IntensityMinutesCard
import app.selfwatch.activity.cards.StepsTodayCard
import app.selfwatch.activity.cards.StrengthSessionsCard
import app.selfwatch.activity.cards.TodayRingsCard
import app.selfwatch.activity.cards.TrainingLoadCard
import app.selfwatch.activity.cards.TrendsCard
import app.selfwatch.activity.cards.WeeklyVolumeCard
import app.selfwatch.activity.cards.WorkoutsCard
import app.selfwatch.body.cards.BodyFatCard
import app.selfwatch.body.cards.BodySignalsCard
import app.selfwatch.body.cards.BodyStressCard
import app.selfwatch.body.cards.EnergyCard
import app.selfwatch.body.cards.HrvBaselineCard
import app.selfwatch.body.cards.MeasurementsCard
import app.selfwatch.body.cards.RestingHrCard
import app.selfwatch.body.cards.SleepCard
import app.selfwatch.body.cards.WaistHeightCard
import app.selfwatch.body.cards.WeightCard
import app.selfwatch.cards.MainGoal.ACTIVE_AGING
import app.selfwatch.cards.MainGoal.ATHLETIC
import app.selfwatch.cards.MainGoal.BUILD_MUSCLE
import app.selfwatch.cards.MainGoal.ENDURANCE
import app.selfwatch.cards.MainGoal.GENERAL_HEALTH
import app.selfwatch.cards.MainGoal.HEART_HEALTH
import app.selfwatch.cards.MainGoal.STRESS_WELLBEING
import app.selfwatch.cards.MainGoal.WEIGHT_LOSS
import app.selfwatch.cards.MainGoal.WEIGHT_MANAGEMENT
import app.selfwatch.food.cards.CarbsPerKgCard
import app.selfwatch.food.cards.FibreCard
import app.selfwatch.food.cards.FuelBalanceCard
import app.selfwatch.food.cards.LoggingDaysCard
import app.selfwatch.food.cards.MacrosTodayCard
import app.selfwatch.food.cards.ProteinPerKgCard
import app.selfwatch.food.cards.ProteinPerMealCard
import app.selfwatch.food.cards.SodiumCard
import app.selfwatch.food.cards.WaterCard
import app.selfwatch.food.cards.WorkoutFuelCard

/**
 * The one table of default cards: per tab, per main goal, most important first. Revise the lists
 * here; nothing else needs to change. A goal missing from a tab's table falls back to general health.
 * Every card listed must be in its tab's registry (a test checks).
 */
object CardPresets {

    private val BODY: Map<MainGoal, List<Card<*>>> = mapOf(
        WEIGHT_LOSS to listOf(WeightCard, EnergyCard, WaistHeightCard, SleepCard, MeasurementsCard),
        WEIGHT_MANAGEMENT to listOf(WeightCard, EnergyCard, WaistHeightCard, SleepCard),
        BUILD_MUSCLE to listOf(WeightCard, WaistHeightCard, BodyFatCard, SleepCard, EnergyCard),
        ATHLETIC to listOf(HrvBaselineCard, SleepCard, RestingHrCard, WeightCard, BodyFatCard),
        ENDURANCE to listOf(HrvBaselineCard, SleepCard, RestingHrCard),
        HEART_HEALTH to listOf(RestingHrCard, SleepCard, WaistHeightCard, HrvBaselineCard, WeightCard),
        STRESS_WELLBEING to listOf(SleepCard, HrvBaselineCard, BodyStressCard, RestingHrCard, BodySignalsCard),
        ACTIVE_AGING to listOf(SleepCard, WeightCard, RestingHrCard),
        GENERAL_HEALTH to listOf(SleepCard, WaistHeightCard, RestingHrCard, WeightCard, EnergyCard),
    )

    private val ACTIVITY: Map<MainGoal, List<Card<*>>> = mapOf(
        WEIGHT_LOSS to listOf(IntensityMinutesCard, StepsTodayCard, TodayRingsCard, TrendsCard, WorkoutsCard),
        WEIGHT_MANAGEMENT to listOf(IntensityMinutesCard, ActiveKcalCard, StepsTodayCard, TodayRingsCard, TrendsCard, WorkoutsCard),
        BUILD_MUSCLE to listOf(StrengthSessionsCard, WorkoutsCard, IntensityMinutesCard, TrendsCard),
        ATHLETIC to listOf(TrainingLoadCard, WorkoutsCard, EasyHardCard, TrendsCard),
        ENDURANCE to listOf(WeeklyVolumeCard, EasyHardCard, TrainingLoadCard, WorkoutsCard, TrendsCard),
        HEART_HEALTH to listOf(IntensityMinutesCard, StepsTodayCard, TodayRingsCard, TrendsCard),
        STRESS_WELLBEING to listOf(StepsTodayCard, IntensityMinutesCard, TodayRingsCard),
        ACTIVE_AGING to listOf(StepsTodayCard, IntensityMinutesCard, StrengthSessionsCard, ActiveDaysCard, TodayRingsCard),
        GENERAL_HEALTH to listOf(IntensityMinutesCard, StepsTodayCard, StrengthSessionsCard, TodayRingsCard, TrendsCard, WorkoutsCard),
    )

    private val FOOD: Map<MainGoal, List<Card<*>>> = mapOf(
        WEIGHT_LOSS to listOf(ProteinPerKgCard, LoggingDaysCard, WaterCard, MacrosTodayCard),
        WEIGHT_MANAGEMENT to listOf(LoggingDaysCard, WaterCard, MacrosTodayCard),
        BUILD_MUSCLE to listOf(ProteinPerKgCard, ProteinPerMealCard, MacrosTodayCard, WaterCard),
        ATHLETIC to listOf(ProteinPerKgCard, CarbsPerKgCard, FuelBalanceCard, WaterCard),
        ENDURANCE to listOf(CarbsPerKgCard, WorkoutFuelCard, ProteinPerKgCard, WaterCard),
        HEART_HEALTH to listOf(SodiumCard, FibreCard, WaterCard),
        STRESS_WELLBEING to listOf(WaterCard),
        ACTIVE_AGING to listOf(ProteinPerKgCard, WaterCard),
        GENERAL_HEALTH to listOf(SodiumCard, FibreCard, WaterCard),
    )

    /** Goals for which onboarding also asks for a daily protein goal. */
    val ASK_PROTEIN: Set<MainGoal> = setOf(BUILD_MUSCLE, ATHLETIC, ENDURANCE)

    /** The default cards for [goal] on [tab], in order. */
    fun cardsFor(goal: MainGoal, tab: CardTab): List<Card<*>> {
        val table = when (tab) {
            CardTab.BODY -> BODY
            CardTab.ACTIVITY -> ACTIVITY
            CardTab.FOOD -> FOOD
        }
        return table[goal] ?: table[GENERAL_HEALTH].orEmpty()
    }
}
