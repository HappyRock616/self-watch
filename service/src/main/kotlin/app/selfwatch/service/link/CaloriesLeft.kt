/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service.link

import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.SettingKeys
import app.selfwatch.db.Table
import app.selfwatch.model.food.GoalKind
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.time.Clock
import app.selfwatch.model.time.Day
import app.selfwatch.model.watch.CiqValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * Sends today's energy budget to the watch app the user chose (F08, setting [LinkSettings.CALORIES_APP]):
 * `{"kcalLeft": n, "kcalIn": a, "kcalOut": b}`, when the numbers change, at most every [minIntervalSec].
 *
 * The numbers come from today's `day_summary` (the service never reads the food module):
 * - `kcalIn`: food eaten (`kcal_in`);
 * - `kcalOut`: active + resting calories;
 * - `kcalLeft`: goal − eaten + active (resting calories are part of the goal, as in the food budget),
 *   where the goal is the `KCAL_IN` goal in force today, else the food module's automatic goal
 *   ([SettingKeys.FOOD_GOAL_EFFECTIVE_KCAL]); null when neither is set.
 *
 * [poke] asks for a send check (for example when a watch connects); a send that reached no watch is
 * tried again on the next change or poke.
 *
 * @param send sends the message to every connected watch; true when at least one took it.
 * @param sleep waits (tests replace it).
 */
public class CaloriesLeft(
    private val db: SelfWatchDb,
    private val clock: Clock,
    private val send: suspend (appUuid: UUID, message: CiqValue) -> Boolean,
    private val minIntervalSec: Long = MIN_INTERVAL_SEC,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    /** The numbers sent. */
    public data class Values(val kcalLeft: Int?, val kcalIn: Int, val kcalOut: Int) {
        /** As the watch message. */
        public fun message(): CiqValue = CiqValue.Dict(
            linkedMapOf(
                CiqValue.Str("kcalLeft") to (kcalLeft?.let { CiqValue.Num(it.toLong()) } ?: CiqValue.Null),
                CiqValue.Str("kcalIn") to CiqValue.Num(kcalIn.toLong()),
                CiqValue.Str("kcalOut") to CiqValue.Num(kcalOut.toLong()),
            ),
        )
    }

    private val trigger = Channel<Unit>(Channel.CONFLATED)
    private var lastSentSec: Long? = null
    private var lastSent: Pair<UUID, Values>? = null

    /** Starts following the database in [scope]; cancel the job to stop. */
    public fun start(scope: CoroutineScope): Job = scope.launch {
        launch {
            db.changes.observe(setOf(Table.DAY_SUMMARY, Table.GOAL, Table.SETTING)).collect { poke() }
        }
        while (true) {
            trigger.receive()
            try {
                step()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SwLog.w(TAG, "calories left update failed", e)
            }
        }
    }

    /** Asks for a check (the numbers are re-read and sent if they changed). */
    public fun poke() {
        trigger.trySend(Unit)
    }

    /** One check: wait out the interval, read, send if changed. Public for tests. */
    public suspend fun step() {
        val app = appUuid() ?: return
        val last = lastSentSec
        if (last != null) {
            val waitSec = last + minIntervalSec - clock.nowSec()
            if (waitSec > 0) sleep(waitSec * 1000)
        }
        val v = compute()
        if (lastSent == (app to v)) return
        if (send(app, v.message())) {
            lastSent = app to v
            lastSentSec = clock.nowSec()
            SwLog.d(TAG) { "sent calories left $v" }
        }
    }

    /** Today's numbers. */
    public suspend fun compute(): Values {
        val today = clock.today()
        val s = db.summaries.day(today)
        val eaten = s?.kcalIn ?: 0
        val active = s?.activeKcal ?: 0
        val resting = s?.restingKcal ?: 0
        val goal = goal(today)
        return Values(goal?.let { it - eaten + active }, eaten, active + resting)
    }

    /**
     * Today's goal: the manual `KCAL_IN` goal, or when there is none (or the food goal mode is `auto`)
     * the food module's effective goal it published in [SettingKeys.FOOD_GOAL_EFFECTIVE_KCAL].
     */
    private suspend fun goal(today: Day): Int? {
        val manual = db.goals.goalOn(GoalKind.KCAL_IN, today)?.value
        val effective = db.settings.getInt(SettingKeys.FOOD_GOAL_EFFECTIVE_KCAL)?.takeIf { it > 0 }
        val auto = db.settings.get(SettingKeys.FOOD_GOAL_MODE) == MODE_AUTO
        return if (auto) effective ?: manual else manual ?: effective
    }

    private suspend fun appUuid(): UUID? = db.settings.get(LinkSettings.CALORIES_APP)?.let {
        try {
            UUID.fromString(it.trim())
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /** Defaults. */
    public companion object {
        private const val TAG = "CaloriesLeft"

        /** At most one message per this many seconds. */
        public const val MIN_INTERVAL_SEC: Long = 5 * 60

        private const val MODE_AUTO = "auto"
    }
}
