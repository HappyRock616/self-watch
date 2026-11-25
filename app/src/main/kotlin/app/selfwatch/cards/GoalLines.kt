/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.cards

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import app.selfwatch.R
import app.selfwatch.food.WaterGlass
import app.selfwatch.shell.Views
import app.selfwatch.ui.charts.ChartPalette
import app.selfwatch.ui.components.SwCard
import app.selfwatch.ui.format.Fmt
import app.selfwatch.ui.format.FmtLabels
import app.selfwatch.ui.format.Units
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.util.Locale
import kotlin.math.abs
import app.selfwatch.ui.R as UiR

/** How a goal line is judged. */
enum class GoalRule {
    /** More is better: reached at the goal (steps, water, sleep). */
    REACH,

    /** A budget: on track while at or under it (calories eaten). */
    LIMIT,

    /** A target value: on track when close to it (weight). */
    TARGET,

    /** A level out of 100 (energy level): high is good. */
    LEVEL,
}

/** What one goal line measures; [label] names it, [rule] judges it. */
enum class GoalMetric(@field:StringRes @get:StringRes val label: Int, val rule: GoalRule) {
    KCAL_EATEN(R.string.goal_line_kcal_eaten, GoalRule.LIMIT),
    KCAL_BURNED(R.string.goal_line_kcal_burned, GoalRule.REACH),
    WATER(R.string.goal_line_water, GoalRule.REACH),
    ENERGY(R.string.goal_line_energy, GoalRule.LEVEL),
    SLEEP(R.string.goal_line_sleep, GoalRule.REACH),
    WEIGHT(R.string.goal_line_weight, GoalRule.TARGET),
    STEPS(R.string.goal_line_steps, GoalRule.REACH),
    ACTIVE_MIN(R.string.goal_line_active_min, GoalRule.REACH),
    ACTIVE_KCAL(R.string.goal_line_active_kcal, GoalRule.REACH),
    WORKOUTS(R.string.goal_line_workouts, GoalRule.REACH),
    ACTIVE_DAYS(R.string.goal_line_active_days, GoalRule.REACH),
    DISTANCE(R.string.goal_line_distance, GoalRule.REACH),
    FLOORS(R.string.goal_line_floors, GoalRule.REACH),
}

/**
 * One goal at a glance: [done] against [goal] in the metric's stored unit (kcal, ml, minutes, steps,
 * kg ×100, metres, count). [done] null: nothing recorded yet; [goal] null: no goal set.
 */
data class GoalLine(val metric: GoalMetric, val done: Int?, val goal: Int?) {
    /** How far along, 0–1 (null when there is nothing to draw). */
    val progress: Float? get() = GoalMath.progress(metric.rule, done, goal)

    /** How it stands. */
    val status: CardStatus get() = GoalMath.status(metric.rule, done, goal)
}

/** Pure goal arithmetic shared by the goal cards. */
object GoalMath {
    /** Within this share of a target weight counts as there. */
    private const val TARGET_NEAR = 0.01

    /** Within this share of a target weight counts as close. */
    private const val TARGET_CLOSE = 0.05

    /** A budget up to this share over is "close". */
    private const val LIMIT_CLOSE = 1.1

    /** Energy level from which it is "good", and from which "close". */
    private const val LEVEL_GOOD = 50
    private const val LEVEL_CLOSE = 25
    private const val LEVEL_MAX = 100f

    /** [done] of [goal] as 0–1 for a bar, by [rule]. */
    fun progress(rule: GoalRule, done: Int?, goal: Int?): Float? = when (rule) {
        GoalRule.REACH, GoalRule.LIMIT -> if (done == null || goal == null || goal <= 0) null else (done.toFloat() / goal).coerceIn(0f, 1f)
        GoalRule.LEVEL -> done?.let { (it / LEVEL_MAX).coerceIn(0f, 1f) }
        GoalRule.TARGET -> null
    }

    /** How [done] stands against [goal] by [rule]. */
    fun status(rule: GoalRule, done: Int?, goal: Int?): CardStatus {
        if (done == null) return CardStatus.NONE
        return when (rule) {
            GoalRule.REACH -> if (goal == null) CardStatus.NONE else CardStatusColors.ofProgress(done.toDouble(), goal.toDouble())
            GoalRule.LIMIT -> when {
                goal == null || goal <= 0 -> CardStatus.NONE
                done <= goal -> CardStatus.GOOD
                done <= goal * LIMIT_CLOSE -> CardStatus.CLOSE
                else -> CardStatus.OFF
            }
            GoalRule.TARGET -> when {
                goal == null || goal <= 0 -> CardStatus.NONE
                abs(done - goal) <= goal * TARGET_NEAR -> CardStatus.GOOD
                abs(done - goal) <= goal * TARGET_CLOSE -> CardStatus.CLOSE
                else -> CardStatus.NONE
            }
            GoalRule.LEVEL -> when {
                done >= LEVEL_GOOD -> CardStatus.GOOD
                done >= LEVEL_CLOSE -> CardStatus.CLOSE
                else -> CardStatus.OFF
            }
        }
    }

    /** How many of [lines] are on track, of those that can be judged. */
    fun onTrack(lines: List<GoalLine>): Pair<Int, Int> {
        val judged = lines.filter { it.done != null && (it.goal != null || it.metric.rule == GoalRule.LEVEL) }
        return judged.count { it.status == CardStatus.GOOD } to judged.size
    }
}

/** Formats goal values in the user's units. */
object GoalText {
    /** [value] of [metric] for display (no unit for counts). */
    fun value(ctx: Context, metric: GoalMetric, value: Int, units: Units): String {
        val loc = Locale.getDefault()
        val labels = FmtLabels.from(ctx)
        return when (metric) {
            GoalMetric.KCAL_EATEN, GoalMetric.KCAL_BURNED, GoalMetric.ACTIVE_KCAL -> Fmt.number(value.toLong(), loc)
            GoalMetric.WATER -> Fmt.volume(value.toDouble(), units, loc, labels)
            GoalMetric.SLEEP -> Fmt.duration(value * 60L, loc, labels)
            GoalMetric.WEIGHT -> Fmt.number(if (units == Units.METRIC) value / 100.0 else value / 100.0 / Fmt.KG_PER_POUND, 1, loc)
            GoalMetric.DISTANCE -> Fmt.distance(value.toDouble(), units, loc, labels)
            else -> Fmt.number(value.toLong(), loc)
        }
    }

    /** The unit after "done / goal", or null (durations and distances carry their own). */
    fun unit(ctx: Context, metric: GoalMetric, units: Units): String? = when (metric) {
        GoalMetric.KCAL_EATEN, GoalMetric.KCAL_BURNED, GoalMetric.ACTIVE_KCAL -> ctx.getString(R.string.goal_unit_kcal)
        GoalMetric.WEIGHT -> ctx.getString(if (units == Units.METRIC) R.string.goal_unit_kg else R.string.goal_unit_lb)
        GoalMetric.ACTIVE_MIN -> ctx.getString(R.string.goal_line_unit_min)
        else -> null
    }

    /** "6,240 / 10,000", "3 of 8 glasses (36 of 96 fl oz)"…; "—" parts for missing values. */
    fun doneOfGoal(ctx: Context, line: GoalLine, units: Units): String {
        if (line.metric == GoalMetric.WATER && line.goal != null) return WaterGlass.ofGoal(ctx, line.done ?: 0, line.goal, units)
        val dash = ctx.getString(R.string.dash)
        val done = line.done?.let { value(ctx, line.metric, it, units) } ?: dash
        val goal = line.goal?.let { value(ctx, line.metric, it, units) }
        val unit = unit(ctx, line.metric, units)
        val core = if (goal == null) done else ctx.getString(R.string.goal_line_of, done, goal)
        return if (unit == null) core else "$core $unit"
    }
}

/**
 * A large card listing goal lines: each with its name, "done / goal" and a bar in the status color (all
 * from the theme). The card's action opens Goals.
 */
class GoalLinesView(parent: ViewGroup, @StringRes title: Int, onGoals: () -> Unit, @androidx.annotation.IdRes rootId: Int) {
    private val ctx: Context = parent.context

    /** The card. */
    val root: SwCard = SwCard(ctx).apply {
        id = rootId
        this.title = ctx.getString(title)
        actionText = ctx.getString(R.string.goal_lines_edit)
        onAction = onGoals
    }

    private val summary: TextView = Views.text(ctx, UiR.style.TextAppearance_SelfWatch_Body).also { root.addView(it, Views.matchWrap(ctx)) }
    private val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }.also { root.addView(it, Views.matchWrap(ctx, 4)) }

    /** Shows [lines] in [units]; [empty] is said when there are none. */
    fun show(lines: List<GoalLine>, units: Units, empty: CharSequence) {
        list.removeAllViews()
        val (good, judged) = GoalMath.onTrack(lines)
        summary.text = if (lines.isEmpty()) empty else ctx.resources.getQuantityString(R.plurals.goal_lines_on_track, judged, good, judged)
        val palette = ChartPalette.of(ctx)
        val spoken = ArrayList<String>()
        for (line in lines) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            val top = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val name = ctx.getString(line.metric.label)
            val value = GoalText.doneOfGoal(ctx, line, units)
            top.addView(Views.text(ctx, UiR.style.TextAppearance_SelfWatch_Body, name), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            top.addView(
                Views.text(ctx, UiR.style.TextAppearance_SelfWatch_Body_Number, value).apply { gravity = Gravity.END },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = Views.dp(ctx, 8)
                },
            )
            row.addView(top, Views.matchWrap(ctx))
            val p = line.progress
            if (p != null) {
                val bar = LinearProgressIndicator(ctx).apply {
                    max = BAR_MAX
                    setIndicatorColor(CardStatusColors.of(ctx, line.status) ?: palette.primary)
                    trackColor = palette.track
                    setProgressCompat((p * BAR_MAX).toInt(), false)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                row.addView(bar, Views.matchWrap(ctx, 4))
            }
            row.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            list.addView(row, Views.matchWrap(ctx, 12))
            spoken += listOfNotNull(name, value, CardStatusColors.spoken(ctx, line.status)).joinToString(", ")
        }
        root.contentDescription = (listOf(root.title, summary.text) + spoken).joinToString(". ")
    }

    private companion object {
        const val BAR_MAX = 1000
    }
}
