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
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import app.selfwatch.R
import app.selfwatch.activity.TrainingDetailFragment
import app.selfwatch.activity.cards.ActivityChartCard
import app.selfwatch.body.HeartDetailFragment
import app.selfwatch.body.SleepDetailFragment
import app.selfwatch.body.SleepLayers
import app.selfwatch.body.TestDb
import app.selfwatch.body.WeightDetailFragment
import app.selfwatch.body.cards.HeartChartCard
import app.selfwatch.body.cards.SleepChartCard
import app.selfwatch.body.cards.WeightChartCard
import app.selfwatch.cards.CardSize
import app.selfwatch.cards.CardSlot
import app.selfwatch.cards.CardStore
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.MainGoal
import app.selfwatch.demo.DemoData
import app.selfwatch.ui.charts.OverlayChartView
import app.selfwatch.ui.components.ToggleChipGroup
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * The chart cards on the tabs: the Sleep chart is on Body by default with the Night view's default chips,
 * every chart card draws its chart with the demo data, its title row opens the detail screen, and a chip
 * changed on the card is saved for the detail screen.
 */
@RunWith(RobolectricTestRunner::class)
class ChartCardsSmokeTest {
    private val ctx: Application = RuntimeEnvironment.getApplication()

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

    private fun <T : View> find(v: View, type: Class<T>): T? {
        if (type.isInstance(v)) return type.cast(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i), type)?.let { return it }
        return null
    }

    @Test
    fun chartCardsShowOpenAndShareTheirChips() {
        val db = TestDb.fresh(ctx)
        runBlocking {
            DemoData(ctx, db).load()
            CardStore(db.settings).setMainGoal(MainGoal.WEIGHT_LOSS)
        }
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        idle()

        // Default Body layout: the Sleep chart, large, in the old Sleep card's place.
        activity.select(Tab.BODY)
        idle()
        waitFor("sleep chart card") { activity.findViewById<View>(R.id.sleep_chart_card)?.isShown == true }
        val sleepCard = activity.findViewById<View>(R.id.sleep_chart_card)
        val chart = find(sleepCard, OverlayChartView::class.java) ?: error("no chart")
        val chips = find(sleepCard, ToggleChipGroup::class.java) ?: error("no chips")
        waitFor("sleep chart layers") { chart.layers.isNotEmpty() && chips.childCount == chart.layers.size }
        val present = chart.layers.map { it.id }.toSet()
        assertEquals(setOf(SleepLayers.STAGES, SleepLayers.HR).intersect(present), chips.onIds)

        // A chip switched on the card is the detail screen's stored choice.
        val hrv = (0 until chips.childCount).map { chips.getChildAt(it) }.firstOrNull { (it as? android.widget.TextView)?.text?.toString() == ctx.getString(R.string.body_hrv) }
        if (hrv != null && SleepLayers.HRV in present) {
            hrv.performClick()
            waitFor("choice saved") { runBlocking { SleepLayers.NIGHT_CHOICE.load(db) }.contains(SleepLayers.HRV).not() }
        }

        // Every chart card draws and its title row opens the detail screen.
        runBlocking {
            CardStore(db.settings).saveSlots(
                CardTab.BODY,
                listOf(CardSlot(SleepChartCard, CardSize.LARGE), CardSlot(HeartChartCard, CardSize.LARGE), CardSlot(WeightChartCard, CardSize.LARGE)),
            )
            CardStore(db.settings).saveSlots(CardTab.ACTIVITY, listOf(CardSlot(ActivityChartCard, CardSize.LARGE)))
        }
        val cases: List<Triple<Tab, Int, Class<out Fragment>>> = listOf(
            Triple(Tab.BODY, R.id.sleep_chart_card, SleepDetailFragment::class.java),
            Triple(Tab.BODY, R.id.heart_chart_card, HeartDetailFragment::class.java),
            Triple(Tab.BODY, R.id.weight_chart_card, WeightDetailFragment::class.java),
            Triple(Tab.ACTIVITY, R.id.activity_chart_card, TrainingDetailFragment::class.java),
        )
        for ((tab, id, type) in cases) {
            activity.select(tab)
            idle()
            waitFor("$type card") { activity.findViewById<View>(id)?.isShown == true }
            val card = activity.findViewById<ViewGroup>(id)
            waitFor("$type chart") { find(card, OverlayChartView::class.java)?.layers?.isNotEmpty() == true }
            // The title row is the first clickable row in the card.
            val header = clickableRow(card) ?: error("no title row in $type card")
            assertTrue(header.performClick())
            activity.supportFragmentManager.executePendingTransactions()
            idle()
            val shown = activity.supportFragmentManager.findFragmentById(R.id.detail_container)
            assertEquals(type, shown?.javaClass)
            activity.onBackPressedDispatcher.onBackPressed()
            activity.supportFragmentManager.executePendingTransactions()
            idle()
        }
    }

    private fun clickableRow(v: View): View? {
        if (v is android.widget.LinearLayout && v.isClickable && v.hasOnClickListeners()) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) clickableRow(v.getChildAt(i))?.let { return it }
        return null
    }
}
