/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.activity

import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.SettingKeys
import app.selfwatch.model.food.Goal
import app.selfwatch.model.food.GoalKind
import app.selfwatch.model.time.Day
import kotlin.math.roundToInt

/**
 * The step and active-time goals every screen reads. Steps are per day (default 10,000); active time
 * ([GoalKind.ACTIVE_MIN]) is minutes per **week** (default 300). Views that work per day use a seventh
 * of the weekly goal ([dailyShare]).
 */
object ActivityGoals {
    /** Step goal per day before one is set. */
    const val DEFAULT_STEPS: Int = 10_000

    /** Active minutes per week before a goal is set. */
    const val DEFAULT_ACTIVE_MIN_WEEK: Int = 300

    /** WHO 2020 minimum of moderate minutes a week (vigorous minutes counting double), shown as a note. */
    const val WHO_MIN_WEEK: Int = 150

    /** The step goal in force on [day], else [DEFAULT_STEPS]. */
    suspend fun steps(db: SelfWatchDb, day: Day): Int = db.goals.goalOn(GoalKind.STEPS, day)?.value ?: DEFAULT_STEPS

    /** The weekly active-minutes goal in force on [day], else [DEFAULT_ACTIVE_MIN_WEEK]. */
    suspend fun activeMinWeek(db: SelfWatchDb, day: Day): Int =
        db.goals.goalOn(GoalKind.ACTIVE_MIN, day)?.value ?: DEFAULT_ACTIVE_MIN_WEEK

    /** A seventh of [weekly] minutes, rounded: the daily share of a weekly goal. */
    fun dailyShare(weekly: Int): Int = (weekly / ActivityWeek.DAYS.toDouble()).roundToInt()

    // Goals the shared goal table has no kind for are settings (one value, from when it was set).

    /** Setting: workouts per week. */
    const val WORKOUTS_WEEK_KEY: String = "goal.workouts_week"

    /** Setting: active days per week (days meeting a seventh of the weekly active-minutes goal). */
    const val ACTIVE_DAYS_KEY: String = "goal.active_days_week"

    /** Setting: distance per day, in metres (no default). */
    const val DISTANCE_KEY: String = "goal.distance_m_day"

    /** Setting: floors climbed per day. */
    const val FLOORS_KEY: String = "goal.floors_day"

    /** Workouts per week before a goal is set. */
    const val DEFAULT_WORKOUTS_WEEK: Int = 3

    /** Active days per week before a goal is set. */
    const val DEFAULT_ACTIVE_DAYS: Int = 5

    /** Floors per day before a goal is set (a common watch default). */
    const val DEFAULT_FLOORS: Int = 10

    /** The workouts-per-week goal. */
    suspend fun workoutsWeek(db: SelfWatchDb): Int = db.settings.getInt(WORKOUTS_WEEK_KEY) ?: DEFAULT_WORKOUTS_WEEK

    /** The active-days-per-week goal. */
    suspend fun activeDays(db: SelfWatchDb): Int = db.settings.getInt(ACTIVE_DAYS_KEY) ?: DEFAULT_ACTIVE_DAYS

    /** The distance-per-day goal in metres, or null when none is set. */
    suspend fun distanceM(db: SelfWatchDb): Int? = db.settings.getInt(DISTANCE_KEY)

    /** The floors-per-day goal. */
    suspend fun floors(db: SelfWatchDb): Int = db.settings.getInt(FLOORS_KEY) ?: DEFAULT_FLOORS

    /** The active-calories-per-day goal. */
    suspend fun activeKcal(db: SelfWatchDb): Int = db.settings.getInt(SettingKeys.GOAL_ACTIVE_KCAL) ?: ActivityRules.DEFAULT_ACTIVE_KCAL
}

/**
 * One-time move of the active-minutes goal from minutes per day to minutes per week. Every stored
 * [GoalKind.ACTIVE_MIN] row of at most [LEGACY_DAILY_MAX] minutes is a daily value and is multiplied by 7;
 * the setting [DONE_KEY] records that the move ran, so it never runs twice.
 */
object ActiveGoalMigration {
    /** Setting set to "1" once the move has run. */
    const val DONE_KEY: String = "goal.active_min_weekly"

    /** The largest stored value read as minutes per day (3 hours a day). */
    const val LEGACY_DAILY_MAX: Int = 180

    /** The weekly value of a stored [value]. */
    fun weekly(value: Int): Int = if (value in 1..LEGACY_DAILY_MAX) value * ActivityWeek.DAYS else value

    /** Runs the move unless it already ran; returns the number of goal rows changed. */
    suspend fun run(db: SelfWatchDb): Int {
        if (db.settings.get(DONE_KEY) != null) return 0
        var changed = 0
        for (g in db.goals.history(GoalKind.ACTIVE_MIN)) {
            val w = weekly(g.value)
            if (w != g.value) {
                db.goals.set(Goal(GoalKind.ACTIVE_MIN, w, g.fromDay))
                changed++
            }
        }
        db.settings.set(DONE_KEY, "1")
        return changed
    }
}
