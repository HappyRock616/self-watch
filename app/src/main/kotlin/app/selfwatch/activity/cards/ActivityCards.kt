/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.activity.cards

import app.selfwatch.body.cards.IntensityCard
import app.selfwatch.cards.Card

/**
 * Every card whose home is Activity (things you do: movement, workouts, training, fitness results). Any
 * of them can be added to any tab. A new Activity card is one more entry here (and in `CardPresets` when
 * it is a default).
 */
object ActivityCards {
    /** All Activity cards, in Edit cards order. */
    val ALL: List<Card<*>> = listOf(
        ActivityGoalsCard,
        WorkoutsCard,
        TodayRingsCard,
        StepsTodayCard,
        TodayTimelineCard,
        IntensityMinutesCard,
        RecoveryHrCard,
        Vo2MaxCard,
        TrainingLoadCard,
        WeeklyVolumeCard,
        DistanceTodayCard,
        ActiveTimeCard,
        ActiveKcalCard,
        CaloriesTodayCard,
        IntensityCard,
        ActiveDaysCard,
        StrengthSessionsCard,
        EasyHardCard,
        TrendsCard,
        SportProgressCard,
        LastActivityCard,
        PersonalRecordsCard,
        StreaksCard,
        WeeklyReviewCard,
        TrainingStatusCard,
        RecoveryTimeCard,
        FloorsCard,
        PaiCard,
        RaceTimesCard,
        EnduranceCard,
        HillFitnessCard,
        LactateThresholdCard,
        CyclingPowerCard,
        HeatAltitudeCard,
        SpeedZonesCard,
        CyclingCard,
        ElevationCard,
        DayClockCard,
        LiveCard,
        WeatherCard,
        SuggestedWorkoutCard,
        AchievementsCard,
    )
}
