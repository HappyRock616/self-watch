/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.activity.cards

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import app.selfwatch.R
import app.selfwatch.activity.ActivityWeek
import app.selfwatch.activity.Sports
import app.selfwatch.activity.WorkoutDetailFragment
import app.selfwatch.activity.WorkoutListFragment
import app.selfwatch.cards.Card
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardSize
import app.selfwatch.cards.CardStatus
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.CardView
import app.selfwatch.cards.DataKind
import app.selfwatch.cards.SmallFace
import app.selfwatch.db.Table
import app.selfwatch.graph
import app.selfwatch.model.health.Workout
import app.selfwatch.model.time.Day
import app.selfwatch.model.time.DayRange
import app.selfwatch.shell.Views
import app.selfwatch.ui.charts.ChartPalette
import app.selfwatch.ui.components.BarSeries
import app.selfwatch.ui.components.BarsView
import app.selfwatch.ui.components.EmptyState
import app.selfwatch.ui.components.ListRow
import app.selfwatch.ui.components.StatTile
import app.selfwatch.ui.components.SwCard
import app.selfwatch.ui.format.Fmt
import app.selfwatch.ui.format.FmtLabels
import app.selfwatch.ui.format.Units
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import app.selfwatch.ui.R as UiR

/** One week of one sport: sessions, distance (m), time (s) and the average pace in s/km (null: no distance). */
data class SportWeek(val start: Day, val sessions: Int, val distanceM: Int, val durationSec: Int) {
    /** Seconds per kilometre over the week's sessions with a distance, or null. */
    val secPerKm: Double? get() = if (distanceM <= 0 || durationSec <= 0) null else durationSec * 1000.0 / distanceM
}

/**
 * One sport over time: the [weeks] (oldest first), the [longest] and [fastest] sessions, and the average
 * heart rate at the usual pace early and late in the period ([hrEarly], [hrLate]: lower at the same
 * pace means fitter).
 */
data class SportProgress(
    val sport: Int,
    val sports: List<Int>,
    val weeks: List<SportWeek>,
    val longest: Workout?,
    val fastest: Workout?,
    val hrEarly: Int?,
    val hrLate: Int?,
) {
    /** Sessions per week over the period. */
    val sessionsPerWeek: Double get() = if (weeks.isEmpty()) 0.0 else weeks.sumOf { it.sessions }.toDouble() / weeks.size

    /** Distance over the period, in metres. */
    val distanceM: Int get() = weeks.sumOf { it.distanceM }

    /** The pace over the last 4 weeks (s/km), or null. */
    val recentSecPerKm: Double? get() = weeks.takeLast(RECENT).let { w -> SportWeek(w.first().start, 0, w.sumOf { it.distanceM }, w.sumOf { it.durationSec }).secPerKm }

    private companion object {
        const val RECENT = 4
    }
}

/** Sport progress arithmetic. */
object SportProgressMath {
    /** Sessions within this share of the median pace count as "the same pace". */
    const val SAME_PACE: Double = 0.07

    /** Weeks shown. */
    const val WEEKS: Int = 12

    /** The sport with most sessions in [workouts] (ties: the most recent), or null. */
    fun mainSport(workouts: List<Workout>): Int? =
        workouts.groupBy { it.sport }.maxWithOrNull(compareBy({ it.value.size }, { g -> g.value.maxOf { it.startSec } }))?.key

    /** [workouts] of one sport summed per week starting on each of [mondays]. */
    fun weeks(workouts: List<Workout>, mondays: List<Day>, zone: ZoneId): List<SportWeek> = mondays.map { m ->
        val inWeek = workouts.filter { val d = Day.of(it.startSec, zone); d >= m && d < m.plusDays(ActivityWeek.DAYS) }
        val withDistance = inWeek.filter { (it.distanceM ?: 0) > 0 }
        SportWeek(m, inWeek.size, withDistance.sumOf { it.distanceM ?: 0 }, withDistance.sumOf { it.durationSec })
    }

    /** Seconds per kilometre of [w], or null without distance. */
    fun secPerKm(w: Workout): Double? = w.distanceM?.takeIf { it > 0 && w.durationSec > 0 }?.let { w.durationSec * 1000.0 / it }

    /**
     * Average heart rate at the usual pace in the first and second half of [workouts] (oldest first):
     * sessions within [SAME_PACE] of the median pace with a heart rate; null with fewer than two in
     * either half.
     */
    fun hrAtSamePace(workouts: List<Workout>): Pair<Int, Int>? {
        val paced = workouts.filter { it.avgHr != null && secPerKm(it) != null }.sortedBy { it.startSec }
        if (paced.size < MIN_SAME_PACE * 2) return null
        val paces = paced.mapNotNull { secPerKm(it) }.sorted()
        val median = paces[paces.size / 2]
        val same = paced.filter { abs((secPerKm(it) ?: 0.0) - median) <= median * SAME_PACE }
        if (same.size < MIN_SAME_PACE * 2) return null
        val half = same.size / 2
        val early = same.take(half).mapNotNull { it.avgHr }.average()
        val late = same.drop(half).mapNotNull { it.avgHr }.average()
        return early.roundToInt() to late.roundToInt()
    }

    /** The whole picture for [sport] from [all] workouts, the [WEEKS] weeks up to [today]. */
    fun progress(sport: Int, all: List<Workout>, today: Day, zone: ZoneId): SportProgress {
        val first = ActivityWeek.start(today).plusDays(-(WEEKS - 1) * ActivityWeek.DAYS)
        val mine = all.filter { it.sport == sport && Day.of(it.startSec, zone) >= first }.sortedBy { it.startSec }
        val mondays = (0 until WEEKS).map { first.plusDays(it * ActivityWeek.DAYS) }
        val hr = hrAtSamePace(mine)
        return SportProgress(
            sport = sport,
            sports = all.map { it.sport }.distinct(),
            weeks = weeks(mine, mondays, zone),
            longest = mine.maxByOrNull { it.distanceM ?: 0 }?.takeIf { (it.distanceM ?: 0) > 0 } ?: mine.maxByOrNull { it.durationSec },
            fastest = mine.filter { secPerKm(it) != null }.minByOrNull { secPerKm(it) ?: Double.MAX_VALUE },
            hrEarly = hr?.first,
            hrLate = hr?.second,
        )
    }

    private const val MIN_SAME_PACE = 2
}

/**
 * Sport progress: one sport over the last 12 weeks (pick it with the chips): pace or speed, sessions per
 * week, distance, the longest and fastest sessions, and heart rate at the same pace (lower is fitter).
 * "All activities" opens that sport's workouts.
 */
object SportProgressCard : Card<SportProgressCard.Data>("sport_progress", CardTab.ACTIVITY, R.string.card_sport_progress, R.string.card_sport_progress_summary) {

    /** Setting: the sport picked (FIT sport code); unset follows the sport done most. */
    const val SPORT_KEY: String = "card.sport_progress.sport"

    /** The progress (null: no workouts yet) in display [units]. */
    data class Data(val progress: SportProgress?, val units: Units)

    override val tables: Set<Table> = setOf(Table.WORKOUT, Table.SETTING)

    override val needs: Set<DataKind> = setOf(DataKind.WORKOUTS)

    override val defaultSize: CardSize get() = CardSize.LARGE

    override suspend fun load(env: CardEnv): Data {
        val all = env.db.workouts.forExport(DayRange(env.clock.today().plusDays(-YEAR_DAYS), env.clock.today()), env.clock.zone())
        val picked = env.db.settings.getInt(SPORT_KEY)?.takeIf { s -> all.any { it.sport == s } }
        val sport = picked ?: SportProgressMath.mainSport(all) ?: return Data(null, env.units)
        return Data(SportProgressMath.progress(sport, all, env.clock.today(), env.clock.zone()), env.units)
    }

    /** "5:12 /km" or "24.3 km/h" for [secPerKm] of [sport]. */
    fun paceText(ctx: Context, sport: Int, secPerKm: Double, units: Units): String {
        val metresPer = Sports.metersPerUnit(units)
        return if (Sports.usesPace(sport)) {
            Sports.paceText(secPerKm, units) + " " + Sports.paceUnit(ctx, units)
        } else {
            Fmt.number(HOUR / (secPerKm * metresPer / KM), 1) + " " + ctx.getString(if (units == Units.METRIC) R.string.unit_kmh else R.string.unit_mph)
        }
    }

    override fun small(ctx: Context, data: Data): SmallFace? {
        val p = data.progress ?: return null
        val pace = p.recentSecPerKm
        return SmallFace(
            value = pace?.let { paceText(ctx, p.sport, it, data.units).substringBefore(' ') } ?: Fmt.number(p.sessionsPerWeek, 1),
            unit = pace?.let { paceText(ctx, p.sport, it, data.units).substringAfter(' ') } ?: ctx.getString(R.string.sport_progress_per_week),
            label = ctx.getString(Sports.label(p.sport)),
            status = hrStatus(p),
            note = hrNote(ctx, p),
        )
    }

    private fun hrStatus(p: SportProgress): CardStatus {
        val e = p.hrEarly ?: return CardStatus.NONE
        val l = p.hrLate ?: return CardStatus.NONE
        return if (l <= e) CardStatus.GOOD else CardStatus.CLOSE
    }

    private fun hrNote(ctx: Context, p: SportProgress): String? {
        val e = p.hrEarly ?: return null
        val l = p.hrLate ?: return null
        return ctx.getString(R.string.sport_progress_hr, e, l)
    }

    override fun createView(host: CardHost, parent: ViewGroup): CardView<Data> = Holder(host, parent)

    private class Holder(private val host: CardHost, parent: ViewGroup) : CardView<Data>(SwCard(parent.context)) {
        private val ctx = parent.context
        private val sw = root as SwCard
        private val chips = ChipGroup(ctx).apply {
            isSingleSelection = true
            isSelectionRequired = true
        }
        private val tile = StatTile(ctx).apply { setValueAppearance(UiR.style.TextAppearance_SelfWatch_Display_Number) }
        private val bars = BarsView(ctx).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        private val rows = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        private val empty = EmptyState(ctx).apply { message = ctx.getString(R.string.activity_recent_empty) }
        private var sport: Int? = null

        init {
            sw.title = ctx.getString(R.string.card_sport_progress)
            sw.actionText = ctx.getString(R.string.activity_all_activities)
            sw.onAction = { host.open(WorkoutListFragment.newInstance(sport)) }
            sw.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(chips) }, Views.matchWrap(ctx))
            sw.addView(tile, Views.matchWrap(ctx, 8))
            sw.addView(bars, Views.matchWrap(ctx, 8).apply { height = ctx.resources.getDimensionPixelSize(R.dimen.trend_card_bars) })
            sw.addView(rows, Views.matchWrap(ctx, 8))
            sw.addView(empty, Views.matchWrap(ctx))
        }

        override fun bind(data: Data) {
            val p = data.progress
            for (v in listOf(chips, tile, bars, rows)) v.visibility = if (p == null) View.GONE else View.VISIBLE
            empty.visibility = if (p == null) View.VISIBLE else View.GONE
            if (p == null) return
            sport = p.sport
            chips.removeAllViews()
            for (s in p.sports.sortedBy { ctx.getString(Sports.label(it)) }) {
                chips.addView(
                    Chip(ctx).apply {
                        id = View.generateViewId()
                        text = ctx.getString(Sports.label(s))
                        isCheckable = true
                        isChecked = s == p.sport
                        setOnClickListener { host.launch { ctx.graph.db.settings.setInt(SPORT_KEY, s) } }
                    },
                )
            }
            val labels = FmtLabels.from(ctx)
            val loc = Locale.getDefault()
            val pace = p.recentSecPerKm
            if (pace != null) {
                val text = paceText(ctx, p.sport, pace, data.units)
                tile.value = text.substringBefore(' ')
                tile.unit = text.substringAfter(' ')
                tile.label = ctx.getString(R.string.sport_progress_pace_label)
            } else {
                tile.value = Fmt.number(p.sessionsPerWeek, 1)
                tile.unit = ctx.getString(R.string.sport_progress_per_week)
                tile.label = ctx.getString(R.string.sport_progress_sessions_label)
            }
            bars.labels = StatCardView.weekLabels(p.weeks.map { it.start.yyyymmdd })
            bars.series = listOf(
                BarSeries(ctx.getString(R.string.sport_progress_sessions_label), ChartPalette.of(ctx).primary, p.weeks.map { it.sessions }.toIntArray()),
            )
            rows.removeAllViews()
            fun row(title: Int, value: String, workout: Workout? = null) {
                rows.addView(
                    ListRow(ctx).apply {
                        this.title = ctx.getString(title)
                        trailing = value
                        if (workout != null) onClick = { host.open(WorkoutDetailFragment.newInstance(workout.id)) }
                    },
                    Views.matchWrap(ctx),
                )
            }
            row(R.string.sport_progress_sessions_week, Fmt.number(p.sessionsPerWeek, 1))
            if (p.distanceM > 0) row(R.string.sport_progress_distance, Fmt.distance(p.distanceM.toDouble(), data.units, loc, labels))
            p.longest?.let { w ->
                row(R.string.sport_progress_longest, w.distanceM?.takeIf { it > 0 }?.let { Fmt.distance(it.toDouble(), data.units, loc, labels) } ?: Fmt.duration(w.durationSec.toLong(), loc, labels), w)
            }
            p.fastest?.let { w -> SportProgressMath.secPerKm(w)?.let { row(R.string.sport_progress_fastest, paceText(ctx, p.sport, it, data.units), w) } }
            hrNote(ctx, p)?.let { row(R.string.sport_progress_hr_title, it) }
            root.contentDescription = listOfNotNull(sw.title, ctx.getString(Sports.label(p.sport)), "${tile.value} ${tile.unit}", tile.label).joinToString(". ")
        }
    }

    private const val YEAR_DAYS = 365
    private const val KM = 1000.0
    private const val HOUR = 3600.0
}
