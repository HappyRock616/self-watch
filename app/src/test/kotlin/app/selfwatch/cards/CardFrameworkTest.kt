/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.selfwatch.cards

import android.app.Application
import app.selfwatch.activity.cards.ActivityGoalsCard
import app.selfwatch.activity.cards.StepsTodayCard
import app.selfwatch.activity.cards.TrendsCard
import app.selfwatch.activity.cards.Vo2MaxCard
import app.selfwatch.activity.cards.WorkoutsCard
import app.selfwatch.body.TestDb
import app.selfwatch.body.cards.BodyTodayCard
import app.selfwatch.body.cards.EcgCard
import app.selfwatch.body.cards.HrvBaselineCard
import app.selfwatch.body.cards.SleepCard
import app.selfwatch.body.cards.Spo2Card
import app.selfwatch.body.cards.WeightCard
import app.selfwatch.db.SettingKeys
import app.selfwatch.food.cards.DailyBalanceCard
import app.selfwatch.food.cards.MacrosTodayCard
import app.selfwatch.food.cards.WaterCard
import app.selfwatch.me.GoalsViewModel
import app.selfwatch.model.device.DeviceInfo
import app.selfwatch.model.device.DeviceKind
import app.selfwatch.model.device.Feature
import app.selfwatch.model.device.SupportTier
import app.selfwatch.model.fake.FakeClock
import app.selfwatch.model.health.MetricKind
import app.selfwatch.model.health.MetricSample
import app.selfwatch.ui.format.Units
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The card framework: the registry, the one default layout per tab (device-aware, with backups), sizes
 * and the stored format, migration of saved layouts, Edit cards and the main goal.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CardFrameworkTest {
    private val ctx: Application = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun registryIdsAreStableAndUniqueAcrossTabs() {
        assertEquals(listOf("body", "activity", "food"), CardTab.entries.map { it.key })
        assertEquals("cards.body", CardTab.BODY.cardsKey)
        assertEquals("cards.activity.known", CardTab.ACTIVITY.knownKey)
        val all = CardRegistry.all
        assertEquals("ids are unique across tabs", all.size, all.map { it.id }.toSet().size)
        for (tab in CardTab.entries) {
            assertTrue("$tab has cards", CardRegistry.of(tab).isNotEmpty())
            for (c in CardRegistry.of(tab)) {
                assertEquals("$c is listed under its home", tab, c.tab)
                assertTrue("$c id is a plain word", c.id.matches(Regex("[a-z0-9_]+")))
                assertTrue("$c reloads on something", c.tables.isNotEmpty())
                assertEquals(c, CardRegistry.byId(c.id))
                assertEquals(c, CardRegistry.byId(tab, c.id))
            }
        }
        assertNull(CardRegistry.byId("no_such_card"))
        assertNull("not its home", CardRegistry.byId(CardTab.FOOD, SleepCard.id))
        // Every id already stored on phones still resolves (the merged Body steps and macros cards map on).
        val stored = listOf(
            "energy", "intensity", "weight", "measurements", "recovery", "spo2", "sleep", "waist_height", "body_fat", "hrv_baseline",
            "resting_hr", "body_stress", "body_signals", "heart_rate_now", "heart_rate_trends", "breathing_rate", "energy_level",
            "today_rings", "trends", "workouts", "intensity_minutes", "steps_today", "active_kcal", "strength_sessions", "active_days",
            "training_load", "weekly_volume", "easy_hard", "today_timeline", "recovery_hr", "distance_today", "calories_today",
            "macros_today", "water", "protein_per_kg", "protein_per_meal", "carbs_per_kg", "workout_fuel", "fuel_balance", "logging_days",
            "sodium", "fibre", "total_sugar",
        )
        for (id in stored) assertTrue(id, CardRegistry.byId(id) != null)
        assertEquals(StepsTodayCard, CardStore.decode("steps")?.card)
        assertEquals(MacrosTodayCard, CardStore.decode("macros")?.card)
        // Names stay free of trademarks.
        for (c in all) {
            val name = ctx.getString(c.title)
            for (tm in listOf("Body Battery", "Training Readiness", "Pulse Ox", "HRV Status", "Endurance Score", "Hill Score", "Sleep Coach")) {
                assertFalse("$c: $name", name.contains(tm, ignoreCase = true))
            }
        }
    }

    @Test
    fun defaultLayoutPerTabMatchesThePlan() {
        fun layout(tab: CardTab) = CardPresets.layout(tab).map { it.card.id to it.size }
        val s = CardSize.SMALL
        val l = CardSize.LARGE
        assertEquals(
            listOf(
                "body_today" to l, "sleep" to l, "hrv_baseline" to s, "resting_hr" to s, "body_stress" to s, "readiness" to s,
                "body_signals" to s, "spo2" to s,
            ),
            layout(CardTab.BODY),
        )
        assertEquals(
            listOf("activity_goals" to l, "training_load" to s, "vo2_max" to s, "recovery_hr" to s, "weekly_volume" to s, "workouts" to l),
            layout(CardTab.ACTIVITY),
        )
        assertEquals(listOf("daily_balance" to l, "weight_goal" to l, "water" to s, "protein_per_meal" to s), layout(CardTab.FOOD))
        assertEquals(listOf("weight", "breathing_rate", "sleep_score"), CardPresets.backups(CardTab.BODY).map { it.card.id })
        assertEquals(listOf("steps_today", "distance_today", "today_timeline"), CardPresets.backups(CardTab.ACTIVITY).map { it.card.id })
        for (tab in CardTab.entries) {
            for (slot in CardPresets.layout(tab) + CardPresets.backups(tab)) {
                assertEquals("$slot is registered", slot.card, CardRegistry.byId(slot.card.id))
                assertTrue("defaults follow the devices", slot.auto)
            }
        }
    }

    @Test
    fun defaultsFollowTheDevicesAndBackupsFillGaps() {
        // A watch that records everything Self Watch stores: all Body defaults show, no backup needed.
        val everything = CardPresets.defaults(CardTab.BODY, Recorded.ALL)
        assertEquals(CardPresets.layout(CardTab.BODY), everything)
        // No device: only the goal card and what can be typed in (Weight fills the first gap).
        val none = CardStore.shownOf(CardPresets.defaults(CardTab.BODY, Recorded.NONE), Recorded.NONE)
        assertEquals(listOf(BodyTodayCard, WeightCard), none.map { it.card })
        // A band with steps and sleep but no HRV: HRV and readiness wait, the backups take their place.
        val band = Recorded(Recorded.MANUAL + setOf(DataKind.STEPS, DataKind.SLEEP, DataKind.HEART_RATE, DataKind.RESTING_HR))
        val shown = CardStore.shownOf(CardPresets.defaults(CardTab.BODY, band), band).map { it.card.id }
        assertEquals(listOf("body_today", "sleep", "weight", "resting_hr"), shown.take(4))
        assertFalse("hrv_baseline" in shown)
        // Activity: with VO2 max stored, all defaults show; before the first estimate Steps stands in for it.
        val activity = CardStore.shownOf(CardPresets.defaults(CardTab.ACTIVITY, Recorded.ALL), Recorded.ALL).map { it.card }
        assertTrue(Vo2MaxCard in activity)
        assertFalse(StepsTodayCard in activity)
        val noVo2 = Recorded(Recorded.ALL.kinds - DataKind.VO2_MAX)
        val waiting = CardStore.shownOf(CardPresets.defaults(CardTab.ACTIVITY, noVo2), noVo2).map { it.card }
        assertFalse(Vo2MaxCard in waiting)
        assertTrue(StepsTodayCard in waiting)
        // Food needs no device at all.
        assertEquals(CardPresets.layout(CardTab.FOOD).map { it.card }, CardStore.shownOf(CardPresets.defaults(CardTab.FOOD, Recorded.NONE), Recorded.NONE).map { it.card })
    }

    @Test
    fun storedFormatKeepsSizesAndMigratesOldLists() {
        val rec = Recorded.ALL
        // Never saved: the defaults.
        assertEquals(CardPresets.defaults(CardTab.BODY, rec), CardStore.resolve(CardTab.BODY, null, null, rec))
        // A list from before sizes: every card large and chosen by the user (the layout looks as before).
        val old = CardStore.resolve(CardTab.BODY, "weight,energy,steps,gone_card,,weight", "weight,energy,steps", rec)
        assertEquals(listOf(CardSlot(WeightCard, CardSize.LARGE), CardSlot(app.selfwatch.body.cards.EnergyCard, CardSize.LARGE), CardSlot(StepsTodayCard, CardSize.LARGE)), old)
        // Sizes and auto marks round-trip.
        val slots = listOf(CardSlot(SleepCard, CardSize.LARGE, auto = true), CardSlot(WaterCard, CardSize.SMALL), CardSlot(TrendsCard, CardSize.LARGE))
        val text = CardStore.encode(slots)
        assertEquals("sleep:l:a,water:s,trends:l", text)
        assertEquals(slots, CardStore.resolve(CardTab.BODY, text, CardRegistry.all.joinToString(",") { it.id }, rec))
        // Any card on any tab.
        assertEquals(listOf(WaterCard, TrendsCard), CardStore.resolve(CardTab.BODY, "water:s,trends:l", null, rec).map { it.card })
        // Food lists saved before Daily balance existed get it at the top (it replaced the fixed header)...
        val food = CardStore.resolve(CardTab.FOOD, "water,sodium", "macros_today,water,sodium", rec)
        assertEquals(listOf(DailyBalanceCard, WaterCard, app.selfwatch.food.cards.SodiumCard), food.map { it.card })
        // ...but once the user has seen it, hiding it sticks; other new cards start hidden.
        assertEquals(listOf(WaterCard), CardStore.resolve(CardTab.FOOD, "water:s", CardRegistry.all.joinToString(",") { it.id }, rec).map { it.card })
        assertEquals(listOf(WeightCard), CardStore.resolve(CardTab.BODY, "weight", "weight", rec).map { it.card })
    }

    @Test
    fun storeRoundTripsShowsAutoCardsOnlyWithDataAndResets() = runBlocking {
        val db = TestDb.fresh(ctx)
        var rec = Recorded.ALL
        val store = CardStore(db.settings) { rec }
        assertFalse(store.isSaved(CardTab.BODY))
        store.saveSlots(CardTab.BODY, listOf(CardSlot(Spo2Card, CardSize.SMALL), CardSlot(HrvBaselineCard, CardSize.SMALL, auto = true), CardSlot(EcgCard, CardSize.LARGE)))
        assertTrue(store.isSaved(CardTab.BODY))
        assertEquals("spo2:s,hrv_baseline:s:a,ecg:l", db.settings.get("cards.body"))
        // ECG is never recorded yet, but the user chose it: it shows (and says so). HRV is auto: it shows with data.
        assertEquals(listOf(Spo2Card, HrvBaselineCard, EcgCard), store.visible(CardTab.BODY))
        rec = Recorded(Recorded.MANUAL + DataKind.SPO2)
        assertEquals(listOf(Spo2Card, EcgCard), store.visible(CardTab.BODY))
        // Cards saved with plain lists are large and chosen.
        store.save(CardTab.ACTIVITY, listOf(WorkoutsCard, WaterCard))
        assertEquals(listOf(CardSlot(WorkoutsCard, CardSize.LARGE), CardSlot(WaterCard, CardSize.LARGE)), store.slots(CardTab.ACTIVITY))
        // Reset: back to the defaults, following the devices again.
        val reset = store.reset(CardTab.ACTIVITY)
        assertFalse(store.isSaved(CardTab.ACTIVITY))
        assertEquals(CardPresets.defaults(CardTab.ACTIVITY, rec), reset)
        assertEquals(listOf(Spo2Card, EcgCard), store.visible(CardTab.BODY))
    }

    @Test
    fun renamedActivityCardsKeepTheirIds() {
        val effort = app.selfwatch.activity.cards.TrainingLoadCard
        val time = app.selfwatch.activity.cards.WeeklyVolumeCard
        assertEquals("training_load", effort.id)
        assertEquals("weekly_volume", time.id)
        assertEquals("Weekly effort", ctx.getString(effort.title))
        assertEquals("How hard you trained this week, from heart-rate zones", ctx.getString(effort.summary))
        assertEquals("Workout time this week", ctx.getString(time.title))
        assertEquals("Workout time and distance this week", ctx.getString(time.summary))
        // Saved layouts that name the old ids still find the cards.
        assertEquals(listOf(effort, time), CardStore.resolve(CardTab.ACTIVITY, "training_load:s,weekly_volume:s", null, Recorded.ALL).map { it.card })
    }

    @Test
    fun savedActivityLayoutsGetRecentActivitiesLastOnce() = runBlocking {
        // Pure: only the Recent activities entry moves; unknown ids and sizes stay as typed.
        assertEquals("activity_goals:l,gone:s,vo2_max:s,workouts:l:a", CardStore.recentLast("activity_goals:l,workouts:l:a,gone:s,vo2_max:s"))
        assertEquals("steps_today:s", CardStore.recentLast("steps_today:s"))
        val db = TestDb.fresh(ctx)
        val store = CardStore(db.settings) { Recorded.ALL }
        db.settings.setAll(mapOf(CardTab.ACTIVITY.cardsKey to "activity_goals:l:a,workouts:l:a,training_load:s:a", CardTab.ACTIVITY.knownKey to "activity_goals,workouts"))
        assertEquals(listOf(ActivityGoalsCard, app.selfwatch.activity.cards.TrainingLoadCard, WorkoutsCard), store.slots(CardTab.ACTIVITY).map { it.card })
        assertEquals("activity_goals:l:a,training_load:s:a,workouts:l:a", db.settings.get(CardTab.ACTIVITY.cardsKey))
        assertEquals("activity_goals,workouts", db.settings.get(CardTab.ACTIVITY.knownKey))
        // Once only: a later order the user picks stays.
        store.saveSlots(CardTab.ACTIVITY, listOf(CardSlot(WorkoutsCard, CardSize.LARGE), CardSlot(ActivityGoalsCard, CardSize.LARGE)))
        assertEquals(listOf(WorkoutsCard, ActivityGoalsCard), store.slots(CardTab.ACTIVITY).map { it.card })
    }

    @Test
    fun oldBodyKeysMoveToTheNewOnes() = runBlocking {
        val db = TestDb.fresh(ctx)
        db.settings.setAll(
            mapOf(
                SettingKeys.MAIN_GOAL to MainGoal.ATHLETIC.id,
                SettingKeys.BODY_CARDS to "weight,energy",
                SettingKeys.BODY_CARDS_KNOWN to "energy,macros,steps,intensity,weight",
                SettingKeys.BODY_CARDS_PRESET to MainGoal.WEIGHT_LOSS.id,
            ),
        )
        val store = CardStore(db.settings)
        assertEquals(listOf(WeightCard, app.selfwatch.body.cards.EnergyCard), store.visible(CardTab.BODY))
        assertEquals("weight,energy", db.settings.get("cards.body"))
        for (k in listOf(SettingKeys.BODY_CARDS, SettingKeys.BODY_CARDS_KNOWN, SettingKeys.BODY_CARDS_PRESET)) assertNull("$k removed", db.settings.get(k))
        // When both exist (an old backup restored over new settings), the new keys win and the old go.
        db.settings.set(SettingKeys.BODY_CARDS, "spo2")
        assertEquals(listOf(WeightCard, app.selfwatch.body.cards.EnergyCard), store.visible(CardTab.BODY))
        assertNull(db.settings.get(SettingKeys.BODY_CARDS))
    }

    @Test
    fun editCardsMovesAddsResizesAndListsEveryCardByHome() = runBlocking {
        val db = TestDb.fresh(ctx)
        val rec = Recorded(Recorded.MANUAL + setOf(DataKind.SLEEP, DataKind.STEPS))
        val store = CardStore(db.settings) { rec }
        val vm = EditCardsViewModel(store, CardTab.BODY)
        withTimeout(5_000) { vm.state.first { !it.loading } }
        val s = vm.state.value
        assertEquals(CardPresets.defaults(CardTab.BODY, rec), s.slots)
        // Rows: "On this tab" then the other cards grouped by home tab, this tab first.
        val rows = s.rows(CardTab.BODY)
        val headers = rows.filterIsInstance<EditRow.Header>().map { it.title }
        assertEquals(listOf(app.selfwatch.R.string.edit_cards_on_tab, CardTab.BODY.label, CardTab.ACTIVITY.label, CardTab.FOOD.label), headers)
        // Without "Show all", cards no device records are left out (HRV waits; ECG is never recorded).
        assertFalse(rows.any { it is EditRow.Shown && it.slot.card == HrvBaselineCard })
        assertFalse(rows.any { it is EditRow.Hidden && it.card == EcgCard })
        val all = s.copy(showAll = true).rows(CardTab.BODY)
        assertTrue(all.any { it is EditRow.Shown && it.slot.card == HrvBaselineCard && !it.recorded })
        assertTrue(all.any { it is EditRow.Hidden && it.card == EcgCard && !it.recorded })
        // Every card appears exactly once with "Show all".
        val listed = all.mapNotNull { (it as? EditRow.Shown)?.slot?.card ?: (it as? EditRow.Hidden)?.card }
        assertEquals(CardRegistry.all.toSet(), listed.toSet())
        assertEquals(listed.size, listed.toSet().size)

        // Move Sleep before Body today, add Water (a Food card) small, make Water large, take Sleep off.
        vm.move(SleepCard, BodyTodayCard)
        assertEquals(listOf(SleepCard, BodyTodayCard), vm.state.value.slots.take(2).map { it.card })
        vm.setShown(WaterCard, true)
        assertEquals(CardSlot(WaterCard, WaterCard.defaultSize), vm.state.value.slots.last())
        vm.setSize(WaterCard, CardSize.LARGE)
        vm.setShown(SleepCard, false)
        waitFor { store.slots(CardTab.BODY).let { it.first().card == BodyTodayCard && it.last() == CardSlot(WaterCard, CardSize.LARGE) } }
        assertFalse(store.slots(CardTab.BODY).any { it.card == SleepCard })

        var done = false
        vm.reset { done = true }
        waitFor { done && !store.isSaved(CardTab.BODY) }
        assertEquals(CardPresets.defaults(CardTab.BODY, rec), vm.state.value.slots)
    }

    @Test
    fun rowsPairSmallCards() {
        fun rows(sizes: String) = CardStack.rowsOf(sizes.toList()) { it == 's' }.map { it.joinToString("") }
        assertEquals(listOf("l", "l", "ss", "ss", "ss"), rows("llssssss"))
        assertEquals(listOf("s", "l", "ss", "s"), rows("slsss"))
        assertEquals(emptyList<String>(), rows(""))
    }

    @Test
    fun recordedComesFromDeviceFeaturesAndStoredData() = runBlocking {
        val db = TestDb.fresh(ctx)
        val clock = FakeClock()
        val loader = RecordedLoader(db, clock)
        assertEquals(Recorded.MANUAL, loader.load().kinds)
        db.devices.upsert(
            DeviceInfo(0, DeviceKind.GARMIN_WATCH, "AA:BB", "Watch", "vivoactive 5", null, null, null, SupportTier.VERIFIED,
                setOf(Feature.HEALTH_ACTIVITY, Feature.HEALTH_SLEEP, Feature.HEALTH_STRESS), null),
        )
        val watch = loader.load()
        assertTrue(DataKind.STEPS in watch.kinds)
        assertTrue("Garmin stress tracking brings the energy level", DataKind.ENERGY_LEVEL in watch.kinds)
        assertFalse(DataKind.SPO2 in watch.kinds)
        // Stored data counts too (an import, or a device removed since).
        db.metrics.insert(listOf(MetricSample(9, MetricKind.SPO2, clock.nowSec() - 3_600, 96)))
        assertTrue(DataKind.SPO2 in loader.load().kinds)
        assertTrue(watch.records(BodyTodayCard))
        assertTrue(watch.records(ActivityGoalsCard))
        // VO2 max has no capability flag: the card counts once the watch has sent an estimate.
        assertFalse(watch.records(Vo2MaxCard))
        db.metrics.insert(listOf(MetricSample(9, MetricKind.VO2_MAX_CYCLING, clock.nowSec() - 3_600, 510)))
        assertTrue(loader.load().records(Vo2MaxCard))
    }

    @Test
    fun theMainGoalNoLongerChangesCards() = runBlocking {
        val db = TestDb.fresh(ctx)
        val store = CardStore(db.settings)
        store.save(CardTab.BODY, listOf(WeightCard))
        val vm = GoalsViewModel(db, FakeClock())
        vm.setMainGoalNow(MainGoal.ATHLETIC)
        assertEquals(MainGoal.ATHLETIC, vm.load(Units.METRIC).mainGoal)
        assertEquals(listOf(WeightCard), store.visible(CardTab.BODY))
        assertFalse(store.isSaved(CardTab.FOOD))
        assertTrue(MainGoal.ATHLETIC.asksProtein)
        assertFalse(MainGoal.GENERAL_HEALTH.asksProtein)
        assertEquals(MainGoal.GENERAL_HEALTH, MainGoal.byId("nonsense"))
    }

    private suspend fun waitFor(cond: suspend () -> Boolean) {
        withTimeout(5_000) { while (!cond()) kotlinx.coroutines.delay(10) }
    }
}
