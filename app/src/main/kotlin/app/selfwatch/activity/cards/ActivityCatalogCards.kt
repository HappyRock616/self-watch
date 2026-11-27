/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.activity.cards

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import app.selfwatch.R
import app.selfwatch.activity.ActivityGoals
import app.selfwatch.activity.ActivityLoader
import app.selfwatch.activity.ActivityRules
import app.selfwatch.activity.ActivityWeek
import app.selfwatch.activity.IntensityMath
import app.selfwatch.activity.Sports
import app.selfwatch.activity.WorkoutDetailFragment
import app.selfwatch.activity.WorkoutListFragment
import app.selfwatch.activity.WorkoutSummaryRow
import app.selfwatch.cards.Card
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardSize
import app.selfwatch.cards.CardStatus
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.CardView
import app.selfwatch.cards.DataKind
import app.selfwatch.cards.SmallFace
import app.selfwatch.cards.CardVisuals
import app.selfwatch.cards.TrendCard
import app.selfwatch.cards.TrendData
import app.selfwatch.cards.WaitingCard
import app.selfwatch.db.Table
import app.selfwatch.model.food.GoalKind
import app.selfwatch.model.health.Workout
import app.selfwatch.model.time.Day
import app.selfwatch.model.time.DayRange
import app.selfwatch.model.time.TimeRange
import app.selfwatch.shell.Views
import app.selfwatch.ui.charts.LevelColors
import app.selfwatch.ui.components.BarsView
import app.selfwatch.ui.components.DotsView
import app.selfwatch.ui.components.EmptyState
import app.selfwatch.ui.components.RingItem
import app.selfwatch.ui.components.RingRowView
import app.selfwatch.ui.components.RingView
import app.selfwatch.ui.components.StatTile
import app.selfwatch.ui.components.SwCard
import app.selfwatch.ui.format.Fmt
import app.selfwatch.ui.format.FmtLabels
import app.selfwatch.ui.format.Units
import java.util.Locale
import app.selfwatch.ui.R as UiR

/**
 * Active time (card design standard): minutes moving today (moderate and vigorous minutes, each counted
 * once) with the last 7 days as bars and the daily share of the weekly active-minutes goal as the goal
 * line. [TrendData.extras]: the daily share, then the 7 days (yyyymmdd, today last). Small form: the bars.
 */
object ActiveTimeCard : TrendCard("active_time", CardTab.ACTIVITY, R.string.card_active_time, R.string.card_active_time_summary) {
    override fun open(host: CardHost): Boolean {
        host.open(app.selfwatch.activity.DailyActivityDetailFragment())
        return true
    }

    override val tables: Set<Table> = setOf(Table.ACTIVITY, Table.DAY_SUMMARY, Table.GOAL)

    override val needs: Set<DataKind> = setOf(DataKind.ACTIVE_TIME, DataKind.STEPS)

    override suspend fun load(env: CardEnv): TrendData {
        val zone = env.clock.zone()
        val days = (WEEK - 1 downTo 0).map { env.day.plusDays(-it) }
        val range = DayRange(days.first(), days.last())
        val minutes = env.db.activity.minutes(TimeRange(range.first.startSec(zone), range.last.plusDays(1).startSec(zone)))
        val byDay = IntensityMath.byDay(minutes, zone)
        val summary = env.db.summaries.days(range).mapNotNull { s -> s.activeMin?.let { s.day to it } }.toMap()
        val perDay = days.map { d -> byDay[d]?.let { it.moderateMin + it.vigorousMin } ?: summary[d] }
        val today = perDay.last() ?: return TrendData(null, units = env.units)
        val avg = perDay.dropLast(1).filterNotNull().takeIf { it.isNotEmpty() }?.average()
        val share = ActivityGoals.dailyShare(ActivityGoals.activeMinWeek(env.db, env.day))
        return TrendData(
            today.toDouble(), perDay.map { (it ?: 0).toDouble() }, avg?.let { today - it }, env.units,
            extras = listOf(share.toDouble()) + days.map { it.yyyymmdd.toDouble() },
        )
    }

    override fun format(ctx: Context, value: Double, units: Units): String = Fmt.number(value, 0)

    override fun unit(ctx: Context, units: Units): String = ctx.getString(R.string.unit_min)

    override fun label(ctx: Context, data: TrendData): String = ctx.getString(R.string.tag_today)

    override fun note(ctx: Context, data: TrendData): String? = data.change?.let { c ->
        ctx.getString(if (c >= 0) R.string.active_time_above else R.string.active_time_below)
    }

    /** The minutes per day as bars with weekday letters and the daily share as the goal line. */
    private fun bars(chart: BarsView, data: TrendData) {
        val share = data.extras.firstOrNull()?.toInt()
        val days = data.extras.drop(1).mapNotNull { it?.toInt() }
        val labels = if (days.size == data.series.size) StatCardView.weekdayLabels(days) else List(data.series.size) { "" }
        ActivityVisuals.weekBars(chart, chart.context.getString(R.string.card_active_time), data.series.map { it.toInt() }, labels, share)
    }

    override fun createSmallGraphic(ctx: Context): View = ActivityVisuals.smallBars(ctx)

    override fun bindSmallGraphic(graphic: View, data: TrendData) = bars(graphic as BarsView, data)

    override fun createView(host: CardHost, parent: ViewGroup): CardView<TrendData> =
        object : CardView<TrendData>(CardView.inflate(parent, R.layout.card_activity_active_time)) {
            private val tile = find<StatTile>(R.id.at_tile).apply { setValueAppearance(UiR.style.TextAppearance_SelfWatch_Display_Number) }
            private val chart = find<BarsView>(R.id.at_bars)
            private val empty = find<EmptyState>(R.id.at_empty)

            override fun bind(data: TrendData) {
                val ctx = root.context
                val v = data.latest
                ActivityVisuals.show(v != null, tile, chart)
                ActivityVisuals.show(v == null, empty)
                if (v == null) {
                    root.contentDescription = empty.message
                    return
                }
                ActivityVisuals.number(tile, format(ctx, v, data.units), unit(ctx, data.units), label(ctx, data))
                bars(chart, data)
                root.contentDescription = ctx.getString(R.string.design_active_time_description, tile.value)
            }
        }

    private const val WEEK = 7
}

/**
 * Streaks (card design standard): days in a row meeting the step goal (today counts once it is met),
 * with a flame and the last 14 days as dots, filled when the goal was met. [TrendData.extras]: the 14
 * days, oldest first (1.0 met, 0.0 not, null no steps), then the 14 days (yyyymmdd). Small form: the dots.
 */
object StreaksCard : TrendCard("streaks", CardTab.ACTIVITY, R.string.card_streaks, R.string.card_streaks_summary) {
    override fun open(host: CardHost): Boolean {
        host.open(app.selfwatch.activity.DailyActivityDetailFragment())
        return true
    }

    /** Days shown as dots. */
    const val DOTS: Int = 14

    override val tables: Set<Table> = setOf(Table.DAY_SUMMARY, Table.ACTIVITY, Table.GOAL)

    override val needs: Set<DataKind> = setOf(DataKind.STEPS)

    override suspend fun load(env: CardEnv): TrendData {
        val streak = ActivityLoader(env.db, env.clock).streak()
        val today = env.clock.today()
        val days = (DOTS - 1 downTo 0).map { today.plusDays(-it) }
        val steps = env.db.summaries.days(DayRange(days.first(), days.last())).mapNotNull { s -> s.steps?.let { s.day to it } }.toMap()
        val history = env.db.goals.history(GoalKind.STEPS)
        val met = days.map { d -> steps[d]?.let { if (it >= ActivityRules.goalOn(history, d, ActivityRules.DEFAULT_STEPS)) 1.0 else 0.0 } }
        return TrendData(streak.toDouble(), units = env.units, extras = met + days.map { it.yyyymmdd.toDouble() })
    }

    /** One dot per day of the last [DOTS] days: true when the step goal was met. */
    fun dots(data: TrendData): BooleanArray = BooleanArray(DOTS) { data.extras.getOrNull(it) == 1.0 }

    override fun format(ctx: Context, value: Double, units: Units): String = Fmt.number(value, 0)

    override fun unit(ctx: Context, units: Units): String = ctx.getString(R.string.streaks_unit)

    override fun label(ctx: Context, data: TrendData): String = ctx.getString(R.string.tag_in_a_row)

    override fun note(ctx: Context, data: TrendData): String? = null

    override fun status(data: TrendData): CardStatus = if ((data.latest ?: 0.0) > 0) CardStatus.GOOD else CardStatus.NONE

    private fun bindDots(view: DotsView, data: TrendData, labels: Boolean) {
        view.filled = dots(data)
        val days = data.extras.drop(DOTS).mapNotNull { it?.toInt() }
        view.labels = if (labels && days.size == DOTS) StatCardView.weekdayLabels(days) else emptyList()
        view.color = LevelColors.of(view.context).good
    }

    override fun createSmallGraphic(ctx: Context): View = ActivityVisuals.smallDots(ctx)

    override fun bindSmallGraphic(graphic: View, data: TrendData) = bindDots(graphic as DotsView, data, labels = false)

    override fun createView(host: CardHost, parent: ViewGroup): CardView<TrendData> =
        object : CardView<TrendData>(CardView.inflate(parent, R.layout.card_activity_streaks)) {
            private val row = find<View>(R.id.sk_row)
            private val flame = find<ImageView>(R.id.sk_flame)
            private val tile = find<StatTile>(R.id.sk_tile).apply { setValueAppearance(UiR.style.TextAppearance_SelfWatch_Display_Number) }
            private val dotsView = find<DotsView>(R.id.sk_dots)
            private val empty = find<EmptyState>(R.id.sk_empty)

            override fun bind(data: TrendData) {
                val ctx = root.context
                val v = data.latest
                ActivityVisuals.show(v != null, row, dotsView)
                ActivityVisuals.show(v == null, empty)
                if (v == null) {
                    root.contentDescription = empty.message
                    return
                }
                val c = LevelColors.of(ctx)
                flame.imageTintList = ColorStateList.valueOf(if (v > 0) c.stressMedium else c.track)
                ActivityVisuals.number(tile, format(ctx, v, data.units), unit(ctx, data.units), label(ctx, data))
                bindDots(dotsView, data, labels = true)
                root.contentDescription = ctx.getString(R.string.design_streaks_description, tile.value)
            }
        }
}

/**
 * Weekly review (card design standard): last week's steps a day against the week before (Monday to
 * Sunday) as two rings toward the step goal. [TrendData.extras]: the week before's average (or null),
 * then the step goal. Small form: the rings.
 */
object WeeklyReviewCard : TrendCard("weekly_review", CardTab.ACTIVITY, R.string.card_weekly_review, R.string.card_weekly_review_summary) {
    override fun open(host: CardHost): Boolean {
        host.open(app.selfwatch.activity.DailyActivityDetailFragment())
        return true
    }

    override val tables: Set<Table> = setOf(Table.DAY_SUMMARY, Table.GOAL)

    override val needs: Set<DataKind> = setOf(DataKind.STEPS)

    override suspend fun load(env: CardEnv): TrendData {
        val thisMonday = ActivityWeek.start(env.clock.today())
        val last = DayRange(thisMonday.plusDays(-WEEK), thisMonday.plusDays(-1))
        val before = DayRange(thisMonday.plusDays(-2 * WEEK), thisMonday.plusDays(-WEEK - 1))
        suspend fun avg(r: DayRange): Double? = env.db.summaries.days(r).mapNotNull { it.steps }.takeIf { it.isNotEmpty() }?.average()
        val a = avg(last) ?: return TrendData(null, units = env.units)
        val b = avg(before)
        val goal = ActivityGoals.steps(env.db, last.last)
        return TrendData(a, change = b?.let { a - it }, units = env.units, extras = listOf(b, goal.toDouble()))
    }

    /** Last week against the week before, as a share (+0.08 = 8% more), or null. */
    fun share(last: Double, before: Double?): Double? = before?.takeIf { it > 0 }?.let { (last - it) / it }

    override fun format(ctx: Context, value: Double, units: Units): String = Fmt.number(value, 0)

    override fun unit(ctx: Context, units: Units): String? = null

    override fun label(ctx: Context, data: TrendData): String = ctx.getString(R.string.tag_steps_a_day)

    override fun note(ctx: Context, data: TrendData): String? {
        val s = share(data.latest ?: return null, data.extras.firstOrNull()) ?: return null
        val pct = Fmt.percent(kotlin.math.abs(s))
        return ctx.getString(if (s >= 0) R.string.weekly_review_up else R.string.weekly_review_down, pct)
    }

    override fun status(data: TrendData): CardStatus {
        val s = share(data.latest ?: return CardStatus.NONE, data.extras.firstOrNull()) ?: return CardStatus.NONE
        return if (s >= 0) CardStatus.GOOD else CardStatus.CLOSE
    }

    /** Last week and the week before as rings toward the step goal (7,500 when unknown). */
    fun rings(ctx: Context, data: TrendData): List<RingItem> {
        val goal = data.extras.getOrNull(1)?.toInt() ?: ActivityGoals.DEFAULT_STEPS
        val c = LevelColors.of(ctx)
        fun ring(v: Double?, label: Int): RingItem = if (v == null) {
            RingItem(0f, c.track, ctx.getString(R.string.dash), ctx.getString(label))
        } else {
            val n = v.toInt()
            RingItem(if (goal > 0) n.toFloat() / goal else 0f, ActivityVisuals.goalColor(ctx, n, goal), ActivityGoalsCard.compact(n), ctx.getString(label))
        }
        return listOf(ring(data.latest, R.string.ring_last_week), ring(data.extras.firstOrNull(), R.string.ring_week_before))
    }

    override fun createSmallGraphic(ctx: Context): View = ActivityVisuals.smallRingRow(ctx)

    override val smallGraphicShowsValue: Boolean = true

    override fun bindSmallGraphic(graphic: View, data: TrendData) {
        (graphic as RingRowView).items = rings(graphic.context, data)
    }

    override fun createView(host: CardHost, parent: ViewGroup): CardView<TrendData> =
        object : CardView<TrendData>(CardView.inflate(parent, R.layout.card_activity_weekly_review)) {
            private val row = find<RingRowView>(R.id.wr_rings)
            private val empty = find<EmptyState>(R.id.wr_empty)

            override fun bind(data: TrendData) {
                val ctx = root.context
                val v = data.latest
                ActivityVisuals.show(v != null, row)
                ActivityVisuals.show(v == null, empty)
                if (v == null) {
                    root.contentDescription = empty.message
                    return
                }
                row.items = rings(ctx, data)
                val dash = ctx.getString(R.string.dash)
                val goal = data.extras.getOrNull(1)?.toInt() ?: ActivityGoals.DEFAULT_STEPS
                root.contentDescription = listOfNotNull(
                    ctx.getString(
                        R.string.design_weekly_review_description, format(ctx, v, data.units),
                        data.extras.firstOrNull()?.let { format(ctx, it, data.units) } ?: dash, Fmt.number(goal.toLong()),
                    ),
                    note(ctx, data),
                ).joinToString(" ")
            }
        }

    private const val WEEK = 7
}

/** Last activity: the latest workout as a big card (sport, date and every summary stat). */
object LastActivityCard : Card<LastActivityCard.Data>("last_activity", CardTab.ACTIVITY, R.string.card_last_activity, R.string.card_last_activity_summary) {

    /** The latest workout (null: none) in display [units]. */
    data class Data(val workout: Workout?, val units: Units)

    override val tables: Set<Table> = setOf(Table.WORKOUT)

    override val needs: Set<DataKind> = setOf(DataKind.WORKOUTS)

    override val defaultSize: CardSize get() = CardSize.LARGE

    override suspend fun load(env: CardEnv): Data = Data(env.db.workouts.list(1).firstOrNull(), env.units)

    override fun small(ctx: Context, data: Data): SmallFace? {
        val w = data.workout ?: return null
        val labels = FmtLabels.from(ctx)
        val dist = w.distanceM?.takeIf { it > 0 }
        return SmallFace(
            value = dist?.let { Fmt.distance(it.toDouble(), data.units, Locale.getDefault(), labels) } ?: Fmt.duration(w.durationSec.toLong(), Locale.getDefault(), labels),
            label = ctx.getString(Sports.label(w.sport)),
            note = Fmt.day(ctx, Day.of(w.startSec, java.time.ZoneId.systemDefault()).yyyymmdd),
        )
    }

    override fun createView(host: CardHost, parent: ViewGroup): CardView<Data> = object : CardView<Data>(SwCard(parent.context)) {
        private val ctx = parent.context
        private val holder = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        private val empty = EmptyState(ctx).apply { message = ctx.getString(R.string.activity_recent_empty) }

        init {
            (root as SwCard).title = ctx.getString(R.string.card_last_activity)
            root.addView(holder, Views.matchWrap(ctx))
            root.addView(empty, Views.matchWrap(ctx))
        }

        override fun bind(data: Data) {
            holder.removeAllViews()
            val w = data.workout
            empty.visibility = if (w == null) View.VISIBLE else View.GONE
            if (w != null) holder.addView(WorkoutSummaryRow.create(ctx, w, data.units) { host.open(WorkoutDetailFragment.newInstance(w.id)) }, Views.matchWrap(ctx))
        }
    }

    override fun openDetail(host: CardHost): Boolean {
        host.open(WorkoutListFragment())
        return true
    }
}

/** One personal record: what it is and the workout that holds it. */
data class PersonalRecord(val kind: RecordKind, val workout: Workout)

/** The records kept. */
enum class RecordKind { LONGEST_DISTANCE, LONGEST_TIME, FASTEST_RUN_5K, BIGGEST_CLIMB }

/** Personal records arithmetic. */
object RecordMath {
    /** Shortest run that counts for the fastest pace: 5 km. */
    const val RUN_MIN_M: Int = 5_000

    /** The records found in [workouts]. */
    fun records(workouts: List<Workout>): List<PersonalRecord> = listOfNotNull(
        workouts.filter { (it.distanceM ?: 0) > 0 }.maxByOrNull { it.distanceM ?: 0 }?.let { PersonalRecord(RecordKind.LONGEST_DISTANCE, it) },
        workouts.filter { it.durationSec > 0 }.maxByOrNull { it.durationSec }?.let { PersonalRecord(RecordKind.LONGEST_TIME, it) },
        workouts.filter { it.sport == Sports.RUNNING && (it.distanceM ?: 0) >= RUN_MIN_M && it.durationSec > 0 }
            .minByOrNull { it.durationSec.toDouble() / (it.distanceM ?: 1) }?.let { PersonalRecord(RecordKind.FASTEST_RUN_5K, it) },
        workouts.filter { (it.ascentM ?: 0) > 0 }.maxByOrNull { it.ascentM ?: 0 }?.let { PersonalRecord(RecordKind.BIGGEST_CLIMB, it) },
    )
}

/** Personal records: longest distance, longest time, fastest run of 5 km or more, biggest climb. */
object PersonalRecordsCard : Card<PersonalRecordsCard.Data>(
    "personal_records", CardTab.ACTIVITY, R.string.card_personal_records, R.string.card_personal_records_summary,
) {
    /** The records in display [units]. */
    data class Data(val records: List<PersonalRecord>, val units: Units)

    override val tables: Set<Table> = setOf(Table.WORKOUT)

    override val needs: Set<DataKind> = setOf(DataKind.WORKOUTS)

    override val defaultSize: CardSize get() = CardSize.LARGE

    override suspend fun load(env: CardEnv): Data = Data(RecordMath.records(env.db.workouts.all()), env.units)

    private fun value(ctx: Context, r: PersonalRecord, units: Units): String {
        val labels = FmtLabels.from(ctx)
        val loc = Locale.getDefault()
        val w = r.workout
        return when (r.kind) {
            RecordKind.LONGEST_DISTANCE -> Fmt.distance((w.distanceM ?: 0).toDouble(), units, loc, labels)
            RecordKind.LONGEST_TIME -> Fmt.duration(w.durationSec.toLong(), loc, labels)
            RecordKind.FASTEST_RUN_5K -> (Sports.pace(w.durationSec, w.distanceM, units) ?: "") + " " + Sports.paceUnit(ctx, units)
            RecordKind.BIGGEST_CLIMB -> Fmt.elevation((w.ascentM ?: 0).toDouble(), units, loc, labels)
        }
    }

    private fun name(r: RecordKind): Int = when (r) {
        RecordKind.LONGEST_DISTANCE -> R.string.record_longest_distance
        RecordKind.LONGEST_TIME -> R.string.record_longest_time
        RecordKind.FASTEST_RUN_5K -> R.string.record_fastest_run
        RecordKind.BIGGEST_CLIMB -> R.string.record_biggest_climb
    }

    override fun small(ctx: Context, data: Data): SmallFace? {
        val r = data.records.firstOrNull() ?: return null
        return SmallFace(value(ctx, r, data.units), ctx.getString(name(r.kind)), note = ctx.resources.getQuantityString(R.plurals.records_count, data.records.size, data.records.size))
    }

    override fun createView(host: CardHost, parent: ViewGroup): CardView<Data> = object : CardView<Data>(SwCard(parent.context)) {
        private val ctx = parent.context
        private val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        private val empty = EmptyState(ctx).apply { message = ctx.getString(R.string.records_empty) }

        init {
            (root as SwCard).title = ctx.getString(R.string.card_personal_records)
            root.addView(list, Views.matchWrap(ctx))
            root.addView(empty, Views.matchWrap(ctx))
        }

        override fun bind(data: Data) {
            list.removeAllViews()
            empty.visibility = if (data.records.isEmpty()) View.VISIBLE else View.GONE
            for (r in data.records) {
                val row = app.selfwatch.ui.components.ListRow(ctx).apply {
                    title = ctx.getString(name(r.kind))
                    subtitle = ctx.getString(Sports.label(r.workout.sport)) + " · " + Fmt.day(ctx, Day.of(r.workout.startSec, java.time.ZoneId.systemDefault()).yyyymmdd)
                    trailing = value(ctx, r, data.units)
                    onClick = { host.open(WorkoutDetailFragment.newInstance(r.workout.id)) }
                }
                list.addView(row, Views.matchWrap(ctx))
            }
        }
    }
}

// Catalog cards for data no device sends to Self Watch yet: each says "Your devices don't record this".

/** Training status. */
val TrainingStatusCard = WaitingCard("training_status", CardTab.ACTIVITY, R.string.card_training_status, R.string.card_training_status_summary, DataKind.TRAINING_STATUS)

/** Hours until recovered. */
/**
 * Recovery time, from the watch (FIT `metric_recovery`): the hours left until you are ready for the next
 * hard workout, counted down from when the watch set it (Garmin caps it at 4 days). 0 means recovered.
 */
object RecoveryTimeCard : TrendCard("recovery_time", CardTab.ACTIVITY, R.string.card_recovery_time, R.string.card_recovery_time_summary) {
    override val tables: Set<Table> = setOf(Table.METRIC)

    override val needs: Set<DataKind> = setOf(DataKind.RECOVERY_TIME)

    override suspend fun load(env: CardEnv): TrendData {
        val last = env.db.metrics.latest(app.selfwatch.model.health.MetricKind.RECOVERY_MIN) ?: return TrendData(null, units = env.units)
        val leftMin = (last.value - (env.clock.nowSec() - last.tSec) / 60).coerceAtLeast(0)
        return TrendData(leftMin / 60.0, units = env.units)
    }

    override fun format(ctx: Context, value: Double, units: Units): String = Fmt.number(kotlin.math.ceil(value), 0)

    override fun unit(ctx: Context, units: Units): String = ctx.getString(R.string.recovery_time_unit)

    override fun label(ctx: Context, data: TrendData): String =
        ctx.getString(if ((data.latest ?: 0.0) <= 0.0) R.string.recovery_time_ready else R.string.recovery_time_left)

    override fun note(ctx: Context, data: TrendData): String? = null

    override fun status(data: TrendData): CardStatus = when {
        data.latest == null -> CardStatus.NONE
        data.latest <= 0.0 -> CardStatus.GOOD
        else -> CardStatus.CLOSE
    }

    override fun emptyMessage(ctx: Context): String = ctx.getString(R.string.recovery_time_empty)

    /** Garmin's longest recovery time, 4 days: a full countdown ring. */
    const val MAX_HOURS: Double = 96.0

    /** The countdown: hours left of [MAX_HOURS] inside the ring; recovered is a full ring in the goal color. */
    private fun ring(view: RingView, data: TrendData) {
        val ctx = view.context
        val c = LevelColors.of(ctx)
        val h = data.latest ?: 0.0
        val ready = h <= 0.0
        view.progress = if (ready) 1f else (h / MAX_HOURS).toFloat().coerceIn(0f, 1f)
        view.color = if (ready) c.good else c.close
        view.centerText = format(ctx, h, data.units)
        view.subText = ctx.getString(if (ready) R.string.tag_ready else R.string.tag_hours)
    }

    override fun createSmallGraphic(ctx: Context): View = CardVisuals.smallRing(ctx)

    override val smallGraphicShowsValue: Boolean = true

    override fun bindSmallGraphic(graphic: View, data: TrendData) = ring(graphic as RingView, data)

    override fun createView(host: CardHost, parent: ViewGroup): CardView<TrendData> =
        object : CardView<TrendData>(CardView.inflate(parent, R.layout.card_activity_recovery_time)) {
            private val ringView = find<RingView>(R.id.rt_ring)
            private val empty = find<EmptyState>(R.id.rt_empty)

            override fun bind(data: TrendData) {
                val ctx = root.context
                val v = data.latest
                ActivityVisuals.show(v != null, ringView)
                ActivityVisuals.show(v == null, empty)
                if (v == null) {
                    root.contentDescription = empty.message
                    return
                }
                ring(ringView, data)
                root.contentDescription = if (v <= 0.0) {
                    ctx.getString(R.string.design_recovery_time_ready)
                } else {
                    ctx.getString(R.string.design_recovery_time_description, format(ctx, v, data.units))
                }
            }
        }
}

/** Floors climbed. */
val FloorsCard = WaitingCard("floors", CardTab.ACTIVITY, R.string.card_floors, R.string.card_floors_summary, DataKind.FLOORS)

/** PAI weekly activity score. */
val PaiCard = WaitingCard("pai", CardTab.ACTIVITY, R.string.card_pai, R.string.card_pai_summary, DataKind.PAI)

/** Predicted race times. */
val RaceTimesCard = WaitingCard("race_times", CardTab.ACTIVITY, R.string.card_race_times, R.string.card_race_times_summary, DataKind.RACE_TIMES, CardSize.LARGE)

/** Endurance score. */
val EnduranceCard = WaitingCard("endurance", CardTab.ACTIVITY, R.string.card_endurance, R.string.card_endurance_summary, DataKind.ENDURANCE)

/** Hill fitness. */
val HillFitnessCard = WaitingCard("hill_fitness", CardTab.ACTIVITY, R.string.card_hill_fitness, R.string.card_hill_fitness_summary, DataKind.HILL)

/** Lactate threshold. */
val LactateThresholdCard =
    WaitingCard("lactate_threshold", CardTab.ACTIVITY, R.string.card_lactate_threshold, R.string.card_lactate_threshold_summary, DataKind.LACTATE_THRESHOLD)

/** Cycling power (FTP, power curve). */
val CyclingPowerCard = WaitingCard("cycling_power", CardTab.ACTIVITY, R.string.card_cycling_power, R.string.card_cycling_power_summary, DataKind.CYCLING_POWER)

/** Heat and altitude acclimation. */
val HeatAltitudeCard = WaitingCard("heat_altitude", CardTab.ACTIVITY, R.string.card_heat_altitude, R.string.card_heat_altitude_summary, DataKind.HEAT_ALTITUDE)

/** Speed zones. */
val SpeedZonesCard = WaitingCard("speed_zones", CardTab.ACTIVITY, R.string.card_speed_zones, R.string.card_speed_zones_summary, DataKind.SPEED_ZONES)

/** Bike sensor speed and cadence. */
val CyclingCard = WaitingCard("cycling", CardTab.ACTIVITY, R.string.card_cycling, R.string.card_cycling_summary, DataKind.CYCLING_SENSOR)

/** Altitude and climb today. */
val ElevationCard = WaitingCard("elevation", CardTab.ACTIVITY, R.string.card_elevation, R.string.card_elevation_summary, DataKind.ELEVATION)

/** Today's workout from a training plan. */
val SuggestedWorkoutCard =
    WaitingCard("suggested_workout", CardTab.ACTIVITY, R.string.card_suggested_workout, R.string.card_suggested_workout_summary, DataKind.SUGGESTED_WORKOUT)

/** Badges for milestones. */
val AchievementsCard = WaitingCard("achievements", CardTab.ACTIVITY, R.string.card_achievements, R.string.card_achievements_summary, DataKind.ACHIEVEMENTS)
