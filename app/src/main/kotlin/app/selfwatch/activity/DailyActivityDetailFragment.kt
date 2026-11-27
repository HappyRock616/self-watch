/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.activity

import android.content.Context
import app.selfwatch.R
import app.selfwatch.cards.ChartChoice
import app.selfwatch.cards.ChartId
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.Table
import app.selfwatch.model.time.Clock
import app.selfwatch.model.time.Day
import app.selfwatch.model.time.DayRange
import app.selfwatch.model.time.TimeRange
import app.selfwatch.shell.DisplayUnits
import app.selfwatch.ui.charts.Axis
import app.selfwatch.ui.charts.BarLayer
import app.selfwatch.ui.charts.ChartLayer
import app.selfwatch.ui.charts.LineLayer
import app.selfwatch.ui.format.Units
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * The days behind the daily activity chart: steps, distance (m), active kcal and active minutes per day
 * from the day summaries ([OverlayMath.MISSING] where a day has none), and the display units.
 */
data class DailyActivityOverlay(
    val firstDay: Day,
    val steps: IntArray,
    val distanceM: IntArray,
    val activeKcal: IntArray,
    val activeMin: IntArray,
    val units: Units,
) {
    /** Days covered. */
    val size: Int get() = steps.size

    /** True when any day has a value. */
    val hasData: Boolean
        get() = listOf(steps, distanceM, activeKcal, activeMin).any { a -> a.any { it != OverlayMath.MISSING } }

    override fun equals(other: Any?): Boolean = other is DailyActivityOverlay && other.firstDay == firstDay &&
        other.steps.contentEquals(steps) && other.distanceM.contentEquals(distanceM) &&
        other.activeKcal.contentEquals(activeKcal) && other.activeMin.contentEquals(activeMin) && other.units == units

    override fun hashCode(): Int = firstDay.hashCode() * 31 + steps.contentHashCode()

    /** Loading. */
    companion object {
        /** Days shown (pinch to zoom in). */
        const val DAYS: Int = 90

        /** The last [DAYS] days up to today. */
        suspend fun load(db: SelfWatchDb, clock: Clock): DailyActivityOverlay {
            val today = clock.today()
            val first = today.plusDays(-(DAYS - 1))
            val byDay = db.summaries.days(DayRange(first, today)).associateBy { it.day }
            val days = DayRange(first, today).days()
            fun col(f: (app.selfwatch.model.health.DaySummary) -> Int?) =
                IntArray(days.size) { i -> byDay[days[i]]?.let(f) ?: OverlayMath.MISSING }
            return DailyActivityOverlay(
                first,
                col { it.steps },
                col { it.distanceM },
                col { it.activeKcal },
                col { it.activeMin },
                DisplayUnits.of(db.settings.profile().units),
            )
        }
    }
}

/**
 * Daily activity, opened from the Steps, Distance, Calories, Active days and other day-goal cards: steps
 * per day as bars with distance, active calories and active minutes as toggles, over the last 90 days.
 * Pinch to zoom, tap to read a day.
 */
class DailyActivityDetailFragment : ActivityOverlayFragment<DailyActivityOverlay>() {
    override val layerStore: ChartChoice = OverlayLayers.DAILY_ACTIVITY
    override val tables: Set<Table> = setOf(Table.DAY_SUMMARY, Table.SETTING)
    override val chartId: Int = R.id.daily_activity_chart
    override val noteRes: Int = R.string.daily_activity_note
    override val emptyRes: Int = R.string.daily_activity_empty

    override fun title(): CharSequence = getString(R.string.daily_activity_title)

    override suspend fun loadData(db: SelfWatchDb, clock: Clock): DailyActivityOverlay = DailyActivityOverlay.load(db, clock)

    override fun hasData(data: DailyActivityOverlay): Boolean = data.hasData

    override fun range(data: DailyActivityOverlay, zone: ZoneId): TimeRange =
        TimeRange(data.firstDay.startSec(zone), data.firstDay.plusDays(data.size).startSec(zone))

    override fun layers(ctx: Context, data: DailyActivityOverlay, zone: ZoneId): List<ChartLayer> = layersOf(ctx, data, zone)

    /** Layer assembly, public for tests. */
    companion object {
        private const val NOON_SEC = 12 * 3_600L
        private const val M_PER_MI = 1609.344
        private const val KCAL_HEIGHT = 0.6f

        /** Steps (bars, left axis), distance (line), active calories (low bars), active minutes (line); empty layers left out. */
        fun layersOf(ctx: Context, o: DailyActivityOverlay, zone: ZoneId): List<ChartLayer> {
            val out = ArrayList<ChartLayer>()
            val start = o.firstDay.startSec(zone)
            fun noon(i: Int): Long = o.firstDay.plusDays(i).startSec(zone) + NOON_SEC
            if (present(o.steps).isNotEmpty()) {
                out += BarLayer(OverlayLayers.STEPS, ctx.getString(R.string.daily_activity_steps), series(ctx, 0), Axis.LEFT, start, DAY_SEC, o.steps)
            }
            val dist = present(o.distanceM)
            if (dist.isNotEmpty()) {
                val metric = o.units == Units.METRIC
                // Hundredths of a km or mile, shown with scale 100.
                val v = IntArray(dist.size) { val m = o.distanceM[dist[it]]; if (metric) (m / 10.0).roundToInt() else (m * 100 / M_PER_MI).roundToInt() }
                out += LineLayer(
                    OverlayLayers.DISTANCE, ctx.getString(R.string.daily_activity_distance), series(ctx, 1), Axis.HIDDEN,
                    LongArray(dist.size) { noon(dist[it]) }, v, scale = 100, gapSec = 2 * DAY_SEC,
                    unit = ctx.getString(if (metric) R.string.goal_unit_km else R.string.goal_unit_mi),
                )
            }
            if (present(o.activeKcal).isNotEmpty()) {
                out += BarLayer(
                    OverlayLayers.ACTIVE_KCAL, ctx.getString(R.string.daily_activity_kcal), series(ctx, 2), Axis.HIDDEN,
                    start, DAY_SEC, o.activeKcal, unit = ctx.getString(R.string.unit_kcal), heightFraction = KCAL_HEIGHT,
                )
            }
            val min = present(o.activeMin)
            if (min.isNotEmpty()) {
                out += LineLayer(
                    OverlayLayers.ACTIVE_MIN, ctx.getString(R.string.daily_activity_minutes), series(ctx, 3), Axis.HIDDEN,
                    LongArray(min.size) { noon(min[it]) }, IntArray(min.size) { o.activeMin[min[it]] }, gapSec = 2 * DAY_SEC,
                    unit = ctx.getString(R.string.unit_min),
                )
            }
            return out
        }
    }
}
