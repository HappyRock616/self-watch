/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.shell

import android.app.Application
import android.os.Looper
import android.view.View
import app.selfwatch.R
import app.selfwatch.activity.ExerciseSleepDetailFragment
import app.selfwatch.activity.OverlayLayers
import app.selfwatch.activity.TrainingDetailFragment
import app.selfwatch.activity.cards.ActivityCards
import app.selfwatch.activity.cards.WeeklyVolumeCard
import app.selfwatch.body.TestDb
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardStore
import app.selfwatch.cards.CardTab
import app.selfwatch.demo.DemoData
import app.selfwatch.model.time.SystemClock
import app.selfwatch.ui.charts.OverlayChartView
import app.selfwatch.ui.format.Units
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Every phase-2 Activity card shows the demo data (no empty state), and the two overlay screens open
 * from their cards with their layers, in portrait and in landscape at 2× font.
 */
@RunWith(RobolectricTestRunner::class)
class ActivityCardsSmokeTest {
    private val ctx: Application = RuntimeEnvironment.getApplication()

    private val emptyIds = listOf(
        R.id.im_empty, R.id.st_empty, R.id.ak_empty, R.id.ss_empty, R.id.ad_empty, R.id.tl_empty, R.id.wv_empty, R.id.eh_empty,
        R.id.tt_empty, R.id.rhr_empty, R.id.dt_empty, R.id.ct_empty,
    )

    private fun idle() {
        repeat(3) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun waitFor(what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!cond()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for $what")
            idle()
        }
    }

    @Test
    fun activityCardsShowDemoDataAndOpenOverlays() {
        val db = TestDb.fresh(ctx)
        runBlocking { DemoData(ctx, db).load() }
        // The demo's long run two days ago is flagged by the long-session check.
        val volume = runBlocking { WeeklyVolumeCard.load(CardEnv(db, SystemClock, SystemClock.today(), Units.METRIC)) }
        assertNotNull("long run flagged", volume.longSession)
        runBlocking { CardStore(db.settings).save(CardTab.ACTIVITY, ActivityCards.ALL) }
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        idle()
        activity.select(Tab.ACTIVITY)
        idle()
        waitFor("every Activity card with demo data") {
            emptyIds.all { id -> activity.findViewById<View>(id)?.let { it.visibility == View.GONE } == true }
        }
        val opens = mapOf(
            R.id.intensity_minutes_card to (ExerciseSleepDetailFragment::class.java to OverlayLayers.EXERCISE_SLEEP),
            R.id.training_load_card to (TrainingDetailFragment::class.java to OverlayLayers.TRAINING),
        )
        for ((card, target) in opens) {
            val (type, layers) = target
            assertTrue(activity.findViewById<View>(card).performClick())
            activity.supportFragmentManager.executePendingTransactions()
            idle()
            val shown = activity.supportFragmentManager.findFragmentById(R.id.detail_container)
            assertTrue("card opens ${type.simpleName}", type.isInstance(shown))
            val view = shown?.requireView() ?: error("no view")
            waitFor("${type.simpleName} layers") {
                val chart = findChart(view)
                chart != null && chart.layers.map { it.id }.containsAll(layers.toggleable)
            }
            activity.onBackPressedDispatcher.onBackPressed()
            activity.supportFragmentManager.executePendingTransactions()
            idle()
        }
        runBlocking { CardStore(db.settings).reset(CardTab.ACTIVITY) }
    }

    @Test
    @Config(qualifiers = "land", fontScale = 2.0f)
    fun landscapeAtDoubleFont() {
        activityCardsShowDemoDataAndOpenOverlays()
    }

    private fun findChart(v: View): OverlayChartView? {
        if (v is OverlayChartView) return v
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) findChart(v.getChildAt(i))?.let { return it }
        return null
    }
}
