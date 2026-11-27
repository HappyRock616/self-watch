/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.body.cards

import app.selfwatch.cards.Card

/**
 * Every card whose home is Body (passive signals: sleep, heart, stress, energy, breathing, body
 * measurements). Any of them can be added to any tab. A new Body card is one more entry here (and in
 * `CardPresets` when it is a default).
 */
object BodyCards {
    /** All Body cards, in Edit cards order. */
    val ALL: List<Card<*>> = listOf(
        BodyTodayCard,
        SleepCard,
        SleepChartCard,
        HeartChartCard,
        WeightChartCard,
        SleepScoreCard,
        HrvBaselineCard,
        RestingHrCard,
        BodyStressCard,
        ReadinessCard,
        BodySignalsCard,
        Spo2Card,
        BreathingRateCard,
        EnergyLevelCard,
        HeartRateNowCard,
        HeartRateTrendsCard,
        RecoveryCard,
        MorningSummaryCard,
        NapsCard,
        TemperatureCard,
        WeightCard,
        BodyFatCard,
        MuscleMassCard,
        LeanMassCard,
        BodyWaterCard,
        BoneMassCard,
        BmiCard,
        BmrCard,
        MeasurementsCard,
        WaistHeightCard,
        BloodPressureCard,
        SleepNeedCard,
        HealthCheckCard,
        FitnessAgeCard,
        EcgCard,
        CycleTrackingCard,
        JetLagCard,
        BloodGlucoseCard,
    )
}
