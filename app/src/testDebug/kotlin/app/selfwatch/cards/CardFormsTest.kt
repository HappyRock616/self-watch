/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.cards

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import app.selfwatch.activity.cards.ActivityGoalsCard
import app.selfwatch.activity.cards.WorkoutsCard
import app.selfwatch.body.TestDb
import app.selfwatch.body.cards.BodyTodayCard
import app.selfwatch.food.cards.DailyBalanceCard
import app.selfwatch.gallery.CardSamples
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.demo.DemoData
import app.selfwatch.model.time.SystemClock
import app.selfwatch.shell.Tab
import app.selfwatch.ui.SwTheme
import app.selfwatch.ui.format.Units
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import app.selfwatch.ui.R as UiR

/**
 * Every card in both forms: loads (empty database and demo data), says what its small form shows, and
 * builds and binds its small and large views. The goal and new cards are laid out in every color theme,
 * dark and light, at 1.0×, 1.5× and 2.0× font, at a phone's width (small cards at half of it).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CardFormsTest {
    private val app: Application = RuntimeEnvironment.getApplication()

    /** A screen fragment with a view, as the tabs have (some cards follow its view lifecycle). */
    private val screen: Fragment by lazy {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val f = ScreenFragment()
        activity.supportFragmentManager.beginTransaction().add(android.R.id.content, f).commitNow()
        f
    }

    private inner class Host(override val context: Context) : CardHost {
        override val fragment: Fragment get() = screen
        val opened = ArrayList<Fragment>()
        override fun open(screen: Fragment) {
            opened += screen
        }
        override fun openTab(tab: Tab) = Unit
        override fun launch(block: suspend () -> Unit) = Unit
    }

    private fun themed(theme: SwTheme.Theme, night: Boolean, fontScale: Float): Context {
        val cfg = Configuration(app.resources.configuration).apply {
            this.fontScale = fontScale
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or (if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
        }
        return ContextThemeWrapper(app, UiR.style.Theme_SelfWatch).also {
            it.applyOverrideConfiguration(cfg)
            SwTheme.applyTheme(it, theme)
        }
    }

    private fun env(db: SelfWatchDb) = CardEnv(db, SystemClock, SystemClock.today(), Units.METRIC)

    @Suppress("UNCHECKED_CAST")
    private fun <T> render(card: Card<T>, data: Any?, ctx: Context, size: CardSize, widthPx: Int): View {
        val parent = FrameLayout(ctx)
        val host = Host(ctx)
        val view: CardView<T> = if (size == CardSize.SMALL) SmallCardView(card, host, parent) else card.createView(host, parent)
        view.bind(data as T)
        parent.addView(view.root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        parent.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        parent.layout(0, 0, widthPx, parent.measuredHeight)
        return view.root
    }

    private fun loadAll(db: SelfWatchDb): Map<Card<*>, Any?> = CardRegistry.all.associateWith { c ->
        runBlocking { c.load(env(db)) }
    }

    @Test
    fun everyCardShowsBothFormsEmptyAndWithDemoData() {
        val ctx = themed(SwTheme.Theme.TURQUOISE, night = true, fontScale = 1f)
        val empty = TestDb.fresh(app)
        val demo = TestDb.fresh(app).also { runBlocking { DemoData(app, it).load() } }
        for (db in listOf(empty, demo)) {
            for ((card, data) in loadAll(db)) {
                @Suppress("UNCHECKED_CAST")
                (card as Card<Any?>).small(ctx, data)
                for (size in CardSize.entries) {
                    val v = render(card, data, ctx, size, if (size == CardSize.SMALL) HALF else WIDTH)
                    assertTrue("$card $size has a height", v.measuredHeight > 0)
                    assertTrue("$card $size is described", !v.contentDescription.isNullOrBlank() || v is ViewGroup)
                }
            }
        }
        // With the demo data, the goal cards and the new cards have something to say.
        val data = loadAll(demo)
        for (c in listOf<Card<*>>(BodyTodayCard, ActivityGoalsCard, DailyBalanceCard, WorkoutsCard)) {
            @Suppress("UNCHECKED_CAST")
            assertTrue("$c small form", (c as Card<Any?>).small(ctx, data[c]) != null)
        }
    }

    @Test
    fun newCardsWorkInEveryThemeAndFontSize() {
        val demo = TestDb.fresh(app).also { runBlocking { DemoData(app, it).load() } }
        val data = loadAll(demo)
        val cards = NEW_CARDS.map { id -> CardRegistry.byId(id) ?: error("no $id") }
        for (theme in SwTheme.Theme.entries) {
            for (night in listOf(false, true)) {
                for (scale in listOf(1f, 1.5f, 2f)) {
                    val ctx = themed(theme, night, scale)
                    assertEquals(night, SwTheme.isNight(ctx))
                    for (c in cards) {
                        for (size in CardSize.entries) {
                            val w = if (size == CardSize.SMALL) HALF else WIDTH
                            val v = render(c, data[c], ctx, size, w)
                            // Nothing spills out of the card's width.
                            assertTrue("$c $size $theme night=$night ×$scale fits", v.measuredWidth <= w)
                            for (t in texts(v)) assertTrue("$c: text '${t.text}' is laid out", t.layout != null)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun finishedCardsWithDataWorkInEveryThemeAndFontSize() {
        // Day clock, Live, Weather and the device cards with real-looking data (the gallery's samples),
        // small and large, in every theme, dark and light, at 1.0×, 1.5× and 2.0× font.
        val ids = setOf("day_clock", "live", "weather", "battery", "last_sync", "solar", "room_climate")
        val samples = CardSamples.all().filter { it.first.id in ids }
        assertEquals(ids, samples.map { it.first.id }.toSet())
        for (theme in SwTheme.Theme.entries) {
            for (night in listOf(false, true)) {
                for (scale in listOf(1f, 1.5f, 2f)) {
                    val ctx = themed(theme, night, scale)
                    for ((card, data) in samples) {
                        if (card !is WaitingCard) {
                            @Suppress("UNCHECKED_CAST")
                            assertTrue("$card small form", (card as Card<Any?>).small(ctx, data) != null)
                        }
                        for (size in CardSize.entries) {
                            val w = if (size == CardSize.SMALL) HALF else WIDTH
                            val v = render(card, data, ctx, size, w)
                            assertTrue("$card $size $theme night=$night ×$scale fits", v.measuredWidth <= w && v.measuredHeight > 0)
                            assertTrue("$card $size is described", !v.contentDescription.isNullOrBlank())
                            for (t in texts(v)) assertTrue("$card: text '${t.text}' is laid out", t.layout != null)
                        }
                    }
                }
            }
        }
    }

    private fun texts(v: View): List<TextView> = when (v) {
        is TextView -> listOf(v)
        is ViewGroup -> (0 until v.childCount).map { v.getChildAt(it) }.filter { it.visibility == View.VISIBLE }.flatMap { texts(it) }
        else -> emptyList()
    }

    private companion object {
        const val WIDTH = 1080
        const val HALF = 520

        /** The cards added with the card framework (goal cards, Daily balance, Weight goal, catalog). */
        val NEW_CARDS = listOf(
            "body_today", "activity_goals", "workouts", "daily_balance", "weight_goal", "sport_progress", "food_groups",
            "readiness", "sleep_score", "temperature", "muscle_mass", "lean_mass", "bmi", "bmr", "blood_pressure", "naps",
            "morning_summary", "active_time", "streaks", "weekly_review", "last_activity", "personal_records", "vo2_max",
            "day_clock", "live", "weather", "battery", "last_sync", "solar", "room_climate",
        )
    }
}

/** A bare screen with a view (test host for cards). */
class ScreenFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View = FrameLayout(inflater.context)
}
