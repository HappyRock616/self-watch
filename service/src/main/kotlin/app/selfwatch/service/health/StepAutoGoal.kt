/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service.health

import app.selfwatch.db.SelfWatchDb
import app.selfwatch.model.food.Goal
import app.selfwatch.model.food.GoalKind
import app.selfwatch.model.time.Clock
import app.selfwatch.model.time.Day
import kotlin.math.roundToInt

/**
 * The automatic daily step goal, after Garmin's Auto Goal: it starts at 7,500 and goes up after each day
 * the goal is met. Unlike Garmin's it never goes down (the user's choice). Typing a step goal in Goals
 * turns it off ([AUTO_KEY] "0"); switching it back on carries on from the goal in force.
 *
 * Garmin does not publish its formula; the steps here follow the behavior users have measured: about
 * 6% up below 7,500 steps, 4% from 7,500 to 10,000 and 2% above, rounded to 10 steps.
 * Ref: forums.garmin.com "How does auto goal work" (thread 117509); vívosmart 5 owner's manual (7,500 start).
 */
public object StepAutoGoal {
    /** Setting: "0" when the user set a fixed step goal; anything else (or absent) means auto. */
    public const val AUTO_KEY: String = "goal.steps_auto"

    /** Setting: the last day already checked (yyyymmdd). */
    public const val CHECKED_KEY: String = "goal.steps_auto_checked"

    /** Garmin's starting goal. */
    public const val START: Int = 7_500

    /** Highest automatic goal. */
    public const val MAX: Int = 50_000

    private const val TIER_1 = 7_500
    private const val TIER_2 = 10_000

    /** The goal after a met day with goal [goal]. */
    public fun raised(goal: Int): Int {
        val pct = when {
            goal < TIER_1 -> 6
            goal < TIER_2 -> 4
            else -> 2
        }
        val next = ((goal * (100 + pct) / 100.0) / 10.0).roundToInt() * 10
        if (goal >= MAX) return goal
        return next.coerceIn(goal + 10, MAX)
    }

    /** True when the automatic step goal is on. */
    public suspend fun isOn(db: SelfWatchDb): Boolean = db.settings.get(AUTO_KEY) != "0"

    /**
     * Brings the goal up to date: for every day since the last check up to yesterday on which the
     * goal in force was met, the goal from the next day is raised. Runs after syncs and at app start.
     */
    public suspend fun update(db: SelfWatchDb, clock: Clock) {
        if (!isOn(db)) return
        val today = clock.today()
        val yesterday = today.plusDays(-1)
        if (db.goals.goalOn(GoalKind.STEPS, today) == null) db.goals.set(Goal(GoalKind.STEPS, START, today))
        val checked = db.settings.getInt(CHECKED_KEY)?.let { Day(it) }
        var d = checked?.plusDays(1) ?: yesterday
        if (d < today.plusDays(-MAX_CATCH_UP_DAYS)) d = today.plusDays(-MAX_CATCH_UP_DAYS)
        while (d <= yesterday) {
            val goal = db.goals.goalOn(GoalKind.STEPS, d)?.value
            val steps = db.summaries.day(d)?.steps
            if (goal != null && steps != null && steps >= goal) {
                val next = d.plusDays(1)
                val later = db.goals.goalOn(GoalKind.STEPS, next)?.value ?: goal
                db.goals.set(Goal(GoalKind.STEPS, maxOf(raised(goal), later), next))
            }
            d = d.plusDays(1)
        }
        db.settings.set(CHECKED_KEY, yesterday.yyyymmdd.toString())
    }

    private const val MAX_CATCH_UP_DAYS = 30
}
