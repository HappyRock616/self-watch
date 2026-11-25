/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.cards

import app.selfwatch.activity.OverlayLayers
import app.selfwatch.activity.TimelineLayers
import app.selfwatch.activity.TrendMetric
import app.selfwatch.body.BodyCompLayers
import app.selfwatch.body.HeartTrendLayers
import app.selfwatch.body.HeartTrendOverlay
import app.selfwatch.body.SignalsOverlay
import app.selfwatch.body.SleepLayers
import app.selfwatch.body.WeightLayers
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.SettingKeys
import app.selfwatch.db.SettingsStore
import app.selfwatch.food.SleepCaloriesLayers

/**
 * Every chart with layer chips: its settings key (the comma list of hidden layer ids), the layers its
 * chips switch (in chip order) and the layers that are always on.
 */
enum class ChartId(
    /** Settings key of the user's choice. */
    val key: String,
    /** Layers the chips switch, in chip order. */
    val toggleable: List<String>,
    /** Layers that are always shown (no chip, or a locked chip). */
    val locked: List<String> = emptyList(),
) {
    /** Body › Weight. */
    WEIGHT(WeightLayers.KEY, WeightLayers.TOGGLEABLE, listOf(WeightLayers.WEIGHT)),

    /** Body › Sleep, the night view. */
    SLEEP_NIGHT("chart.sleep.layers", SleepLayers.NIGHT),

    /** Body › Sleep, the week and month views. */
    SLEEP_PERIOD("chart.sleep_period.layers", SleepLayers.PERIOD),

    /** Body › Heart, the 26-week trend. */
    HEART_TREND("chart.heart_trend.layers", listOf(HeartTrendOverlay.INTENSITY, HeartTrendOverlay.RESTING)),

    /** Body › Measurements, protein and body composition. */
    BODY_COMP("chart.body_comp.layers", listOf(BodyCompLayers.PROTEIN, BodyCompLayers.STRENGTH), listOf(BodyCompLayers.MEASURE)),

    /** Body › Body signals. */
    BODY_SIGNALS(
        "chart.body_signals.layers",
        listOf(SignalsOverlay.SKIN_TEMP, SignalsOverlay.RESTING_HR, SignalsOverlay.RESPIRATION, SignalsOverlay.BAND),
    ),

    /** Activity › Training and recovery. */
    TRAINING(
        "chart.training.layers",
        listOf(OverlayLayers.LOAD, OverlayLayers.INTENSITY, OverlayLayers.HRV, OverlayLayers.HRV_BAND, OverlayLayers.RESTING_HR),
    ),

    /** Activity › Exercise and sleep. */
    EXERCISE_SLEEP("chart.exercise_sleep.layers", listOf(OverlayLayers.EXERCISE, OverlayLayers.SLEEP)),

    /** Activity › the Trends card. */
    ACTIVITY_TRENDS("chart.activity_trends.layers", TrendMetric.entries.map { it.id }),

    /** Food › Sleep and calories. */
    SLEEP_KCAL(SleepCaloriesLayers.KEY, SleepCaloriesLayers.ALL),

    /** Body › the Heart rate trends card. */
    HEART_RATE_TRENDS("chart.heart_rate_trends.layers", HeartTrendLayers.ALL),

    /** Activity › the Today card (hour by hour). */
    TODAY_TIMELINE("chart.today_timeline.layers", TimelineLayers.ALL),
}

/**
 * The layers each chart shows until the user picks their own, by main goal: two layers per chart (three
 * where a chart means nothing with fewer, like body signals). Every other layer starts off but stays one
 * tap away. The table lives only here; docs/build-plan/WP-15-cards.md mirrors it.
 */
object ChartDefaults {

    /** The layers [chart] shows by default for [goal] (always-on layers included). */
    fun visible(goal: MainGoal, chart: ChartId): Set<String> = chart.locked.toSet() + on(goal, chart)

    /** The layers [chart] hides by default for [goal]. */
    fun hidden(goal: MainGoal, chart: ChartId): Set<String> {
        val on = on(goal, chart)
        return chart.toggleable.filter { it !in on }.toSet()
    }

    /** Every chart's settings key. */
    val KEYS: List<String> = ChartId.entries.map { it.key }

    /** Every chart's stored choice removed (as a settings write): charts fall back to the goal's defaults. */
    val CLEARED: Map<String, String?> = KEYS.associateWith { null }

    private fun on(goal: MainGoal, chart: ChartId): Set<String> = when (chart) {
        ChartId.WEIGHT -> setOf(
            when (goal) {
                MainGoal.WEIGHT_LOSS, MainGoal.WEIGHT_MANAGEMENT, MainGoal.ATHLETIC, MainGoal.ENDURANCE -> WeightLayers.NET
                MainGoal.BUILD_MUSCLE -> WeightLayers.KCAL_IN
                MainGoal.HEART_HEALTH, MainGoal.ACTIVE_AGING, MainGoal.GENERAL_HEALTH, MainGoal.STRESS_WELLBEING -> WeightLayers.ACTIVE_MIN
            },
        )
        ChartId.SLEEP_NIGHT -> setOf(SleepLayers.STAGES, if (hrvFirst(goal)) SleepLayers.HRV else SleepLayers.HR)
        ChartId.SLEEP_PERIOD -> setOf(SleepLayers.SLEEP, if (hrvFirst(goal)) SleepLayers.HRV else SleepLayers.RESTING_HR)
        ChartId.ACTIVITY_TRENDS -> when (goal) {
            // No strength-days or intensity metric on this card: steps and distance stand in.
            MainGoal.BUILD_MUSCLE -> setOf(TrendMetric.ACTIVE_MIN.id, TrendMetric.STEPS.id)
            MainGoal.ATHLETIC, MainGoal.ENDURANCE -> setOf(TrendMetric.ACTIVE_MIN.id, TrendMetric.DISTANCE.id)
            else -> setOf(TrendMetric.STEPS.id, TrendMetric.ACTIVE_MIN.id)
        }
        ChartId.TRAINING -> setOf(OverlayLayers.LOAD, OverlayLayers.HRV)
        ChartId.BODY_COMP ->
            if (goal == MainGoal.BUILD_MUSCLE) setOf(BodyCompLayers.PROTEIN, BodyCompLayers.STRENGTH) else setOf(BodyCompLayers.PROTEIN)
        // Every layer: the three signals with their usual band, both lines of the two-layer charts.
        ChartId.BODY_SIGNALS, ChartId.HEART_TREND, ChartId.EXERCISE_SLEEP, ChartId.SLEEP_KCAL,
        ChartId.HEART_RATE_TRENDS, ChartId.TODAY_TIMELINE,
        -> chart.toggleable.toSet()
    }

    /** Goals that read recovery from HRV rather than heart rate. */
    private fun hrvFirst(goal: MainGoal): Boolean =
        goal == MainGoal.ATHLETIC || goal == MainGoal.ENDURANCE || goal == MainGoal.STRESS_WELLBEING
}

/**
 * The user's choice of hidden layers for [chart], kept in settings under [ChartId.key]. With nothing
 * stored, the main goal's defaults ([ChartDefaults]) apply.
 */
class ChartChoice(val chart: ChartId) {
    /** Settings key. */
    val key: String get() = chart.key

    /** Layers that can be switched, in chip order. */
    val toggleable: List<String> get() = chart.toggleable

    /** The hidden ids stored as [stored]; null (never chosen) gives [goal]'s defaults. Unknown ids are dropped. */
    fun decode(stored: String?, goal: MainGoal): Set<String> {
        if (stored == null) return ChartDefaults.hidden(goal, chart)
        return stored.split(',').map { it.trim() }.filter { it in toggleable }.toSet()
    }

    /** [hidden] as stored (in chip order, so equal sets store equal text). */
    fun encode(hidden: Set<String>): String = toggleable.filter { it in hidden }.joinToString(",")

    /** The hidden layer ids. */
    suspend fun load(settings: SettingsStore): Set<String> =
        decode(settings.get(key), MainGoal.byId(settings.get(SettingKeys.MAIN_GOAL)))

    /** The hidden layer ids. */
    suspend fun load(db: SelfWatchDb): Set<String> = load(db.settings)

    /** Saves the hidden layer ids. */
    suspend fun save(settings: SettingsStore, hidden: Set<String>) {
        settings.set(key, encode(hidden))
    }

    /** Saves the hidden layer ids. */
    suspend fun save(db: SelfWatchDb, hidden: Set<String>): Unit = save(db.settings, hidden)
}
