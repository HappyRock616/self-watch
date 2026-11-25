/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.cards

import app.selfwatch.activity.cards.ActivityGoalsCard
import app.selfwatch.activity.cards.DistanceTodayCard
import app.selfwatch.activity.cards.RecoveryHrCard
import app.selfwatch.activity.cards.StepsTodayCard
import app.selfwatch.activity.cards.TodayTimelineCard
import app.selfwatch.activity.cards.TrainingLoadCard
import app.selfwatch.activity.cards.Vo2MaxCard
import app.selfwatch.activity.cards.WeeklyVolumeCard
import app.selfwatch.activity.cards.WorkoutsCard
import app.selfwatch.body.cards.BodySignalsCard
import app.selfwatch.body.cards.BodyStressCard
import app.selfwatch.body.cards.BodyTodayCard
import app.selfwatch.body.cards.BreathingRateCard
import app.selfwatch.body.cards.HrvBaselineCard
import app.selfwatch.body.cards.ReadinessCard
import app.selfwatch.body.cards.RestingHrCard
import app.selfwatch.body.cards.SleepCard
import app.selfwatch.body.cards.SleepScoreCard
import app.selfwatch.body.cards.Spo2Card
import app.selfwatch.body.cards.WeightCard
import app.selfwatch.cards.CardSize.LARGE
import app.selfwatch.cards.CardSize.SMALL
import app.selfwatch.food.cards.DailyBalanceCard
import app.selfwatch.food.cards.ProteinPerMealCard
import app.selfwatch.food.cards.WaterCard
import app.selfwatch.food.cards.WeightGoalCard

/**
 * The one default layout per tab (CARDS_PLAN.md "Default layout"), the same for everyone: goals first,
 * then the large cards, then the small ones in pairs (on Activity the tall Recent activities list goes
 * last, after the small cards). A default whose data none of the user's devices
 * records is left out and the next backup that is recorded takes its place. Revise the lists here;
 * every card listed must be registered (a test checks).
 */
object CardPresets {

    private val LAYOUT: Map<CardTab, List<Pair<Card<*>, CardSize>>> = mapOf(
        CardTab.BODY to listOf(
            BodyTodayCard to LARGE,
            SleepCard to LARGE,
            HrvBaselineCard to SMALL,
            RestingHrCard to SMALL,
            BodyStressCard to SMALL,
            ReadinessCard to SMALL,
            BodySignalsCard to SMALL,
            Spo2Card to SMALL,
        ),
        CardTab.ACTIVITY to listOf(
            ActivityGoalsCard to LARGE,
            TrainingLoadCard to SMALL,
            Vo2MaxCard to SMALL,
            RecoveryHrCard to SMALL,
            WeeklyVolumeCard to SMALL,
            // Recent activities is a tall list: it goes last, after the small cards.
            WorkoutsCard to LARGE,
        ),
        CardTab.FOOD to listOf(
            DailyBalanceCard to LARGE,
            WeightGoalCard to LARGE,
            WaterCard to SMALL,
            ProteinPerMealCard to SMALL,
        ),
    )

    private val BACKUPS: Map<CardTab, List<Pair<Card<*>, CardSize>>> = mapOf(
        CardTab.BODY to listOf(WeightCard to SMALL, BreathingRateCard to SMALL, SleepScoreCard to SMALL),
        CardTab.ACTIVITY to listOf(StepsTodayCard to SMALL, DistanceTodayCard to SMALL, TodayTimelineCard to LARGE),
        CardTab.FOOD to emptyList(),
    )

    /** The full default layout of [tab], whatever the devices record. */
    fun layout(tab: CardTab): List<CardSlot> = LAYOUT[tab].orEmpty().map { (c, s) -> CardSlot(c, s, auto = true) }

    /** The backups of [tab], in the order they fill gaps. */
    fun backups(tab: CardTab): List<CardSlot> = BACKUPS[tab].orEmpty().map { (c, s) -> CardSlot(c, s, auto = true) }

    /**
     * The default cards of [tab] for what [recorded] covers: every default (each shows once its data is
     * recorded; until then it is skipped) plus, for each default not recorded, the next recorded backup.
     */
    fun defaults(tab: CardTab, recorded: Recorded): List<CardSlot> {
        val layout = layout(tab)
        val gaps = layout.count { !recorded.records(it.card) }
        val fill = backups(tab).filter { b -> recorded.records(b.card) && layout.none { it.card == b.card } }.take(gaps)
        // A filler stands where the first missing default would be, so the order stays large-then-small.
        val out = ArrayList<CardSlot>(layout.size + fill.size)
        val queue = ArrayDeque(fill)
        for (slot in layout) {
            // The missing default stays in the list (hidden while not recorded) so it appears in place by
            // itself once a device starts recording its data.
            out += slot
            if (!recorded.records(slot.card) && queue.isNotEmpty()) {
                val next = queue.removeFirst()
                // A large filler in a run of small cards would break the two-a-row pairs: hold it back.
                if (next.size == LARGE) queue.addLast(next) else out += next
            }
        }
        // Large fillers go after the leading large defaults (before the first small card); anything left
        // goes at the end.
        val firstSmall = out.indexOfFirst { it.size != LARGE }.let { if (it < 0) out.size else it }
        out.addAll(firstSmall, queue.filter { it.size == LARGE })
        out += queue.filter { it.size != LARGE }
        return out
    }
}
